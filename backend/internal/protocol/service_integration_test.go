package protocol_test

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net/url"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/crypto"
	"github.com/jackc/pgx/v5"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ipfs"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/merkle"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/protocol"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/risk"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/roles"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

const (
	chainID     = 31337
	registry    = "0xe7f1725e7734ce288f8367e1bb143e90bb3f0512"
	maker       = "0x00000000000000000000000000000000000000aa"
	distributor = "0x00000000000000000000000000000000000000bb"
	pharmacy    = "0x00000000000000000000000000000000000000cc"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func migrated(t *testing.T) *store.Store {
	t.Helper()
	base := os.Getenv("TEST_DATABASE_URL")
	if base == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	schema := fmt.Sprintf("proto_%d", time.Now().UnixNano())
	u, err := url.Parse(base)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("schema", schema)
	u.RawQuery = q.Encode()
	ctx := context.Background()
	st, err := store.Open(ctx, u.String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = st.Pool.Exec(context.Background(), "DROP SCHEMA IF EXISTS "+pgx.Identifier{schema}.Sanitize()+" CASCADE")
		st.Close()
	})
	if err := st.Migrate(ctx, quiet, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	return st
}

// fakeRegistry mirrors the SupplementRegistryV2 rules the services depend
// on: unique physical lots, count in 1..units, custody strictly
// manufacturer -> distributor -> pharmacy, one-shot consumption at a point
// of sale, recalls.
type fakeRegistry struct {
	mu        sync.Mutex
	roles     map[string]db.SupplyRole
	lots      map[common.Hash]bool
	segments  map[int64]*fakeSegment
	consumed  map[string]bool
	recalled  map[int64]bool
	nextBatch int64
	nextSeg   int64
	block     uint64
}

type fakeSegment struct {
	batchID    int64
	owner      string
	start, end uint32
	status     db.SegmentStatus
}

func newFakeRegistry() *fakeRegistry {
	return &fakeRegistry{
		roles:    map[string]db.SupplyRole{distributor: db.SupplyRoleDistributor, pharmacy: db.SupplyRolePharmacy},
		lots:     map[common.Hash]bool{},
		segments: map[int64]*fakeSegment{},
		consumed: map[string]bool{},
		recalled: map[int64]bool{},
		block:    100,
	}
}

func (r *fakeRegistry) position() chain.EventPosition {
	r.block++
	return chain.EventPosition{TxHash: fmt.Sprintf("0x%064x", r.block), BlockNumber: r.block}
}

func (r *fakeRegistry) RegisterBatch(_ context.Context, in chain.RegisterBatchV2Input) (chain.BatchRegistered, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.lots[in.PhysicalBatchID] {
		return chain.BatchRegistered{}, apperr.Conflict("This lot is already registered")
	}
	r.lots[in.PhysicalBatchID] = true
	r.nextBatch++
	r.nextSeg++
	r.segments[r.nextSeg] = &fakeSegment{batchID: r.nextBatch, owner: in.Manufacturer, end: in.Size, status: db.SegmentStatusCreated}
	return chain.BatchRegistered{
		EventPosition: r.position(), BatchID: r.nextBatch, SegmentID: r.nextSeg, Manufacturer: in.Manufacturer,
		Size: in.Size, MerkleRoot: in.MerkleRoot.Hex(), PhysicalBatchID: in.PhysicalBatchID.Hex(),
		MetadataCID: in.MetadataCID, MetadataHash: in.MetadataHash.Hex(),
	}, nil
}

func (r *fakeRegistry) TransferSegment(_ context.Context, owner string, segmentID int64, to string, count uint32) (chain.SegmentTransferred, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	seg := r.segments[segmentID]
	if seg == nil || seg.owner != owner {
		return chain.SegmentTransferred{}, apperr.BadRequest("not the segment owner")
	}
	if count == 0 || count > seg.end-seg.start {
		return chain.SegmentTransferred{}, apperr.BadRequest("count exceeds the units in this segment")
	}
	var status db.SegmentStatus
	switch {
	case seg.status == db.SegmentStatusCreated && r.roles[to] == db.SupplyRoleDistributor:
		status = db.SegmentStatusTransferred
	case seg.status == db.SegmentStatusTransferred && r.roles[to] == db.SupplyRolePharmacy:
		status = db.SegmentStatusAtPointOfSale
	default:
		return chain.SegmentTransferred{}, apperr.BadRequest("Transfer not allowed: the recipient lacks the next custody role")
	}
	ev := chain.SegmentTransferred{
		EventPosition: r.position(), BatchID: seg.batchID, FromSegmentID: segmentID, ToSegmentID: segmentID,
		From: owner, To: to, Start: seg.start, End: seg.start + count, Status: status,
	}
	if count < seg.end-seg.start {
		r.nextSeg++
		ev.ToSegmentID = r.nextSeg
		r.segments[r.nextSeg] = &fakeSegment{batchID: seg.batchID, owner: to, start: seg.start, end: seg.start + count, status: status}
		seg.start += count
	} else {
		seg.owner, seg.status = to, status
	}
	return ev, nil
}

func (r *fakeRegistry) Consume(_ context.Context, in chain.ConsumeV2Input) (chain.UnitConsumed, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	key := fmt.Sprintf("%d:%d", in.BatchID, in.Index)
	seg := r.segments[in.SegmentID]
	switch {
	case r.consumed[key]:
		return chain.UnitConsumed{}, apperr.Conflict("already consumed")
	case seg == nil || seg.status != db.SegmentStatusAtPointOfSale || in.Index < seg.start || in.Index >= seg.end:
		return chain.UnitConsumed{}, apperr.Conflict("Unit is not at a point of sale yet")
	}
	r.consumed[key] = true
	return chain.UnitConsumed{
		EventPosition: r.position(), BatchID: in.BatchID, Index: in.Index, SegmentID: in.SegmentID,
		UnitKey: strings.ToLower(in.UnitKey.Hex()), Consumer: strings.ToLower(in.Consumer.Hex()), Submitter: maker,
	}, nil
}

func (r *fakeRegistry) InvalidateBatch(_ context.Context, actors []string, batchID int64) (chain.BatchInvalidated, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.recalled[batchID] = true
	return chain.BatchInvalidated{EventPosition: r.position(), BatchID: batchID, Actor: actors[0]}, nil
}

func (r *fakeRegistry) InvalidateSegment(_ context.Context, actors []string, segmentID int64) (chain.SegmentInvalidated, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	seg := r.segments[segmentID]
	seg.status = db.SegmentStatusInvalid
	return chain.SegmentInvalidated{EventPosition: r.position(), SegmentID: segmentID, BatchID: seg.batchID, Actor: actors[0]}, nil
}

// memPinner is a content-addressed in-memory IPFS.
type memPinner struct {
	mu   sync.Mutex
	docs map[string][]byte
}

func (p *memPinner) PinJSON(_ context.Context, body []byte) (ipfs.Pinned, error) {
	sum := sha256.Sum256(body)
	cid := "bafy" + hex.EncodeToString(sum[:8])
	p.mu.Lock()
	p.docs[cid] = bytes.Clone(body)
	p.mu.Unlock()
	return ipfs.Pinned{CID: cid, ContentHash: "0x" + hex.EncodeToString(sum[:]), Pinned: true}, nil
}

func (p *memPinner) ResolveJSON(_ context.Context, cid string) json.RawMessage {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.docs[cid]
}

func (p *memPinner) GatewayURL(cid string) string { return "https://ipfs.test/ipfs/" + cid }

type memAudit struct {
	mu      sync.Mutex
	actions []string
}

func (a *memAudit) Record(_ context.Context, e audit.Entry) error {
	a.mu.Lock()
	a.actions = append(a.actions, e.Action)
	a.mu.Unlock()
	return nil
}

type fixture struct {
	st     *store.Store
	reg    *fakeRegistry
	pinner *memPinner
	audit  *memAudit
	svc    *protocol.Service
	eip712 *chain.EIP712
}

func newFixture(t *testing.T) fixture {
	t.Helper()
	st := migrated(t)
	f := fixture{st: st, reg: newFakeRegistry(), pinner: &memPinner{docs: map[string][]byte{}}, audit: &memAudit{}, eip712: chain.NewEIP712V2(registry)}
	f.svc = protocol.NewService(st, f.reg, f.pinner, f.audit, f.eip712, quiet, protocol.Options{
		ChainID: chainID, PublicBaseURL: "https://verify.test/u/", MaxBatchUnits: 50,
		ScanSalt: "salt", DefaultManufacturer: maker, SnapshotTTL: time.Minute,
	})
	parties := roles.NewPartyService(st, nil)
	off := false
	for addr, p := range map[string][3]string{
		maker:       {"Manufacturer", "Sina Nutrition", "tehran"},
		distributor: {"Distributor", "Pakhsh Co", "tehran"},
		pharmacy:    {"Pharmacy", "Darou Pharmacy", "Tehran"},
	} {
		if _, err := parties.Bind(context.Background(), roles.BindInput{Address: addr, Role: p[0], DisplayName: &p[1], Region: &p[2], OnChain: &off}); err != nil {
			t.Fatal(err)
		}
	}
	return f
}

func status(err error) int {
	var ae *apperr.Error
	if errors.As(err, &ae) {
		return ae.Status
	}
	return 0
}

func wantStatus(t *testing.T, err error, code int) {
	t.Helper()
	if status(err) != code {
		t.Fatalf("error = %v (status %d), want %d", err, status(err), code)
	}
}

func (f fixture) register(t *testing.T, size int64) protocol.RegisteredBatch {
	t.Helper()
	reg, err := f.svc.RegisterBatch(context.Background(), protocol.RegisterBatchInput{
		Name: "Omega 3", LotCode: fmt.Sprintf("LOT-%d", time.Now().UnixNano()), Size: size,
	})
	if err != nil {
		t.Fatal(err)
	}
	return reg
}

func (f fixture) transfer(t *testing.T, segmentID, to string, count int64) protocol.TransferResult {
	t.Helper()
	res, err := f.svc.Transfer(context.Background(), protocol.TransferInput{SegmentID: segmentID, ToAddress: to, Count: count})
	if err != nil {
		t.Fatal(err)
	}
	return res
}

// toPharmacy ships the first count units of a manufacturer segment through
// the distributor to the pharmacy and returns the pharmacy's segment.
func (f fixture) toPharmacy(t *testing.T, segmentID string, count int64) protocol.TransferResult {
	t.Helper()
	shipped := f.transfer(t, segmentID, distributor, count)
	return f.transfer(t, shipped.ToSegmentID, pharmacy, 0)
}

func (f fixture) verify(t *testing.T, batchID string, index int, device, region string) protocol.VerifyResult {
	t.Helper()
	res, err := f.svc.Verify(context.Background(), protocol.VerifyInput{
		ChainID: fmt.Sprint(chainID), BatchID: batchID, Index: fmt.Sprint(index), Device: device, Region: region,
	})
	if err != nil {
		t.Fatal(err)
	}
	return res
}

func (f fixture) consumeInput(t *testing.T, batchID string, index uint32, key *ecdsa.PrivateKey, deadline time.Time) protocol.ConsumeInput {
	t.Helper()
	consumer := common.HexToAddress("0x00000000000000000000000000000000000000dd")
	sig, err := f.eip712.SignUnitConsume(key, chain.UnitConsumeAuthorization{
		BatchID: mustBig(t, batchID), Index: index, Consumer: consumer, Deadline: big.NewInt(deadline.Unix()), ChainID: chainID,
	})
	if err != nil {
		t.Fatal(err)
	}
	return protocol.ConsumeInput{BatchID: batchID, Index: int64(index), Consumer: consumer.Hex(), Deadline: deadline.Unix(), Signature: hexutil.Encode(sig)}
}

func mustBig(t *testing.T, s string) *big.Int {
	n, ok := new(big.Int).SetString(s, 10)
	if !ok {
		t.Fatalf("not a number: %s", s)
	}
	return n
}

func secretKey(t *testing.T, u protocol.UnitCredential) *ecdsa.PrivateKey {
	t.Helper()
	p, err := protocol.ParseSecretQR(u.SecretQR)
	if err != nil {
		t.Fatal(err)
	}
	return p.Key
}

func TestRegisterBatchCommitsEveryUnitKey(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	reg := f.register(t, 7)
	if !reg.KeysRevealOnce || !reg.IPFSPinned || len(reg.Units) != 7 || reg.SegmentID == "" {
		t.Fatalf("registration = %+v", reg)
	}
	root := common.HexToHash(reg.MerkleRoot)
	for i, u := range reg.Units {
		key := secretKey(t, u)
		addr := crypto.PubkeyToAddress(key.PublicKey)
		if !strings.EqualFold(addr.Hex(), u.UnitKey) {
			t.Fatalf("unit %d: secret key does not match unit key", i)
		}
		if u.PublicQR != fmt.Sprintf("https://verify.test/u/%d/%s/%d", chainID, reg.BatchID, i) {
			t.Fatalf("public QR = %s", u.PublicQR)
		}
		proof, err := f.svc.Proof(ctx, reg.BatchID, fmt.Sprint(i))
		if err != nil {
			t.Fatal(err)
		}
		hashes := make([]merkle.Hash, len(proof.Proof))
		for j, h := range proof.Proof {
			hashes[j] = common.HexToHash(h)
		}
		if !merkle.Verify(root, merkle.UnitLeaf(uint32(i), addr), hashes) {
			t.Fatalf("unit %d: proof does not verify against the batch root", i)
		}
	}

	var m struct {
		UnitKeys   []string `json:"unitKeys"`
		MerkleRoot string   `json:"merkleRoot"`
	}
	if err := json.Unmarshal(f.pinner.ResolveJSON(ctx, reg.MetadataCID), &m); err != nil {
		t.Fatal(err)
	}
	if len(m.UnitKeys) != 7 || m.MerkleRoot != reg.MerkleRoot {
		t.Fatalf("manifest = %+v", m)
	}
	if strings.Contains(string(f.pinner.ResolveJSON(ctx, reg.MetadataCID)), strings.Split(reg.Units[0].SecretQR, ":")[4]) {
		t.Fatal("a private key leaked into the public manifest")
	}

	_, err := f.svc.RegisterBatch(ctx, protocol.RegisterBatchInput{Name: "Omega 3", LotCode: *reg.LotCode, Size: 2})
	wantStatus(t, err, 409)
	for _, bad := range []protocol.RegisterBatchInput{
		{Name: "", LotCode: "L", Size: 1},
		{Name: "x", LotCode: "", Size: 1},
		{Name: "x", LotCode: "L", Size: 0},
		{Name: "x", LotCode: "L", Size: 51},
		{Name: "x", LotCode: "L", Size: 1, ExpiresAt: new("31/12/2027")},
		{Name: "x", LotCode: "L", Size: 1, ManufacturerAddress: new("acme")},
	} {
		_, err := f.svc.RegisterBatch(ctx, bad)
		wantStatus(t, err, 400)
	}
}

func TestTransferSplitsAndMovesWholeSegments(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	reg := f.register(t, 10)

	split := f.transfer(t, reg.SegmentID, distributor, 4)
	if !split.Split || split.Start != 0 || split.End != 4 || split.Status != "Transferred" || split.FromSegmentID != reg.SegmentID {
		t.Fatalf("split = %+v", split)
	}
	whole := f.transfer(t, split.ToSegmentID, pharmacy, 0)
	if whole.Split || whole.Status != "AtPointOfSale" || whole.Start != 0 || whole.End != 4 {
		t.Fatalf("whole-segment transfer = %+v", whole)
	}

	detail, err := f.svc.GetBatch(ctx, reg.BatchID)
	if err != nil {
		t.Fatal(err)
	}
	if detail.Distribution["Created"] != 6 || detail.Distribution["AtPointOfSale"] != 4 || len(detail.Segments) != 2 {
		t.Fatalf("detail = %+v", detail)
	}
	page, err := f.svc.ListSegments(ctx, protocol.SegmentListQuery{Owner: strings.ToUpper("0x" + pharmacy[2:]), Status: "AtPointOfSale", Paging: protocol.Paging{Page: 1, Limit: 10}})
	if err != nil || page.Total != 1 || page.Items[0].Units != 4 {
		t.Fatalf("pharmacy segments = %+v, %v", page, err)
	}

	_, err = f.svc.Transfer(ctx, protocol.TransferInput{SegmentID: reg.SegmentID, ToAddress: distributor, Count: 7})
	wantStatus(t, err, 400)
	_, err = f.svc.Transfer(ctx, protocol.TransferInput{SegmentID: whole.ToSegmentID, ToAddress: pharmacy})
	wantStatus(t, err, 400)
	_, err = f.svc.Transfer(ctx, protocol.TransferInput{SegmentID: "999", ToAddress: pharmacy})
	wantStatus(t, err, 404)
	_, err = f.svc.ListSegments(ctx, protocol.SegmentListQuery{Status: "Lost"})
	wantStatus(t, err, 400)

	history, err := f.svc.History(ctx, reg.BatchID, "3")
	if err != nil {
		t.Fatal(err)
	}
	if len(history.Custody) != 2 || history.Custody[0].To != distributor || history.Custody[1].To != pharmacy {
		t.Fatalf("history = %+v", history)
	}
	if history, _ := f.svc.History(ctx, reg.BatchID, "8"); len(history.Custody) != 0 {
		t.Fatalf("unit 8 never moved: %+v", history.Custody)
	}
}

func TestConsumeChecksAuthorizationBeforeRelaying(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	reg := f.register(t, 6)
	moved := f.toPharmacy(t, reg.SegmentID, 3)
	soon := time.Now().Add(5 * time.Minute)

	other, _ := crypto.GenerateKey()
	_, err := f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 1, other, soon))
	wantStatus(t, err, 400)
	_, err = f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 1, secretKey(t, reg.Units[2]), soon))
	wantStatus(t, err, 400)
	_, err = f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 1, secretKey(t, reg.Units[1]), time.Now().Add(-time.Second)))
	wantStatus(t, err, 400)
	wrongChain := f.consumeInput(t, reg.BatchID, 1, secretKey(t, reg.Units[1]), soon)
	wrongChain.ChainID = new(int64(1))
	_, err = f.svc.Consume(ctx, wrongChain)
	wantStatus(t, err, 400)
	_, err = f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 4, secretKey(t, reg.Units[4]), soon))
	wantStatus(t, err, 409)

	res, err := f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 1, secretKey(t, reg.Units[1]), soon))
	if err != nil {
		t.Fatal(err)
	}
	if res.Status != "Consumed" || res.SegmentID != moved.ToSegmentID || !strings.EqualFold(res.UnitKey, reg.Units[1].UnitKey) {
		t.Fatalf("consume = %+v", res)
	}
	_, err = f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 1, secretKey(t, reg.Units[1]), soon))
	wantStatus(t, err, 409)

	history, err := f.svc.History(ctx, reg.BatchID, "1")
	if err != nil || history.Consumption == nil || history.Consumption.TxHash != res.TxHash {
		t.Fatalf("history = %+v, %v", history, err)
	}
	if !strings.Contains(strings.Join(f.audit.actions, ","), "unit.consume") {
		t.Fatalf("audit = %v", f.audit.actions)
	}
}

func TestVerifyStatusesAndCloneDetection(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	reg := f.register(t, 8)
	f.toPharmacy(t, reg.SegmentID, 4)

	atMaker := f.verify(t, reg.BatchID, 6, "buyer", "tehran")
	if atMaker.Authenticity != protocol.InTransit || atMaker.Custodian == nil || atMaker.Custodian.Address != maker {
		t.Fatalf("unit at the manufacturer = %+v", atMaker)
	}
	sale := f.verify(t, reg.BatchID, 2, "buyer", "Tehran")
	if sale.Authenticity != protocol.Authentic || sale.Risk.Level != risk.LevelLow || sale.ScanCount != 1 ||
		*sale.Custodian.DisplayName != "Darou Pharmacy" || *sale.Product.Manufacturer.DisplayName != "Sina Nutrition" ||
		sale.Evidence == nil || sale.Evidence.MerkleRoot != reg.MerkleRoot {
		t.Fatalf("verify at the pharmacy = %+v", sale)
	}

	if _, err := f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 2, secretKey(t, reg.Units[2]), time.Now().Add(time.Minute))); err != nil {
		t.Fatal(err)
	}
	after := f.verify(t, reg.BatchID, 2, "buyer", "tehran")
	if after.Authenticity != protocol.Consumed || after.Risk.Level != risk.LevelLow || after.Consumption == nil {
		t.Fatalf("buyer re-scan = %+v", after)
	}
	var clone protocol.VerifyResult
	for i, region := range []string{"tabriz", "mashhad", "shiraz"} {
		clone = f.verify(t, reg.BatchID, 2, fmt.Sprintf("clone-%d", i), region)
	}
	if clone.Authenticity != protocol.Consumed || clone.Risk.Level != risk.LevelHigh || clone.ScanCount != 5 {
		t.Fatalf("clone scans = %+v", clone)
	}

	var spread protocol.VerifyResult
	for i, region := range []string{"tabriz", "mashhad", "shiraz", "yazd"} {
		spread = f.verify(t, reg.BatchID, 3, fmt.Sprintf("dev-%d", i), region)
	}
	if spread.Authenticity != protocol.Suspicious || spread.Risk.Level != risk.LevelHigh {
		t.Fatalf("unsold unit scanned across the country = %+v", spread)
	}

	suspicious, err := f.svc.ListSuspicious(ctx, protocol.SuspiciousQuery{})
	if err != nil {
		t.Fatal(err)
	}
	found := map[int32]bool{}
	for _, u := range suspicious {
		found[u.Index] = u.BatchID == reg.BatchID
	}
	if !found[2] || !found[3] || len(suspicious) != 2 {
		t.Fatalf("suspicious units = %+v", suspicious)
	}

	_, err = f.svc.Verify(ctx, protocol.VerifyInput{ChainID: "1", BatchID: reg.BatchID, Index: "0"})
	wantStatus(t, err, 404)
	_, err = f.svc.Verify(ctx, protocol.VerifyInput{ChainID: fmt.Sprint(chainID), BatchID: reg.BatchID, Index: "8"})
	wantStatus(t, err, 404)
}

func TestRecallBlocksSaleAndConsumption(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	reg := f.register(t, 6)
	shop := f.toPharmacy(t, reg.SegmentID, 3)

	res, err := f.svc.Recall(ctx, protocol.RecallInput{BatchID: reg.BatchID, SegmentID: &shop.ToSegmentID, Reason: new("contamination")})
	if err != nil || res.Scope != "segment" {
		t.Fatalf("segment recall = %+v, %v", res, err)
	}
	if v := f.verify(t, reg.BatchID, 0, "buyer", ""); v.Authenticity != protocol.Recalled {
		t.Fatalf("recalled segment verifies as %s", v.Authenticity)
	}
	if v := f.verify(t, reg.BatchID, 4, "buyer", ""); v.Authenticity != protocol.InTransit {
		t.Fatalf("the rest of the batch is unaffected, got %s", v.Authenticity)
	}
	_, err = f.svc.Consume(ctx, f.consumeInput(t, reg.BatchID, 0, secretKey(t, reg.Units[0]), time.Now().Add(time.Minute)))
	wantStatus(t, err, 409)
	_, err = f.svc.Transfer(ctx, protocol.TransferInput{SegmentID: shop.ToSegmentID, ToAddress: distributor})
	wantStatus(t, err, 409)

	if _, err := f.svc.Recall(ctx, protocol.RecallInput{BatchID: reg.BatchID}); err != nil {
		t.Fatal(err)
	}
	if v := f.verify(t, reg.BatchID, 4, "buyer", ""); v.Authenticity != protocol.Recalled || !v.Recalled {
		t.Fatalf("recalled batch verifies as %s", v.Authenticity)
	}

	other := f.register(t, 2)
	_, err = f.svc.Recall(ctx, protocol.RecallInput{BatchID: other.BatchID, SegmentID: &shop.ToSegmentID})
	wantStatus(t, err, 400)
}

func TestUnitsAreRestoredFromTheManifest(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	reg := f.register(t, 5)
	before, err := f.svc.Proof(ctx, reg.BatchID, "3")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.st.Pool.Exec(ctx, `DELETE FROM "Unit"`); err != nil {
		t.Fatal(err)
	}
	after, err := f.svc.Proof(ctx, reg.BatchID, "3")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Join(before.Proof, ",") != strings.Join(after.Proof, ",") || before.UnitKey != after.UnitKey {
		t.Fatalf("restored proof differs: %+v vs %+v", before, after)
	}

	if _, err := f.st.Pool.Exec(ctx, `DELETE FROM "Unit"`); err != nil {
		t.Fatal(err)
	}
	f.pinner.mu.Lock()
	f.pinner.docs = map[string][]byte{}
	f.pinner.mu.Unlock()
	_, err = f.svc.Proof(ctx, reg.BatchID, "3")
	wantStatus(t, err, 503)
}

func TestRenderLabelsOnlyPrintsCommittedKeys(t *testing.T) {
	f := newFixture(t)
	ctx := context.Background()
	reg := f.register(t, 9)
	secrets := make([]string, len(reg.Units))
	for i, u := range reg.Units {
		secrets[i] = u.SecretQR
	}
	pdf, err := f.svc.RenderLabels(ctx, protocol.RenderLabelsInput{BatchID: reg.BatchID, SecretQRs: secrets})
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(pdf, []byte("%PDF")) || bytes.Count(pdf, []byte("/Type /Page\n")) != 2 {
		t.Fatalf("expected a two-page PDF for 9 labels, got %d bytes / %d pages", len(pdf), bytes.Count(pdf, []byte("/Type /Page\n")))
	}

	forged, _ := crypto.GenerateKey()
	_, err = f.svc.RenderLabels(ctx, protocol.RenderLabelsInput{BatchID: reg.BatchID, SecretQRs: []string{protocol.SecretQR(chainID, mustBig(t, reg.BatchID).Int64(), 0, forged)}})
	wantStatus(t, err, 400)
	_, err = f.svc.RenderLabels(ctx, protocol.RenderLabelsInput{BatchID: reg.BatchID, SecretQRs: []string{protocol.SecretQR(1, mustBig(t, reg.BatchID).Int64(), 0, secretKey(t, reg.Units[0]))}})
	wantStatus(t, err, 400)
	_, err = f.svc.RenderLabels(ctx, protocol.RenderLabelsInput{BatchID: reg.BatchID})
	wantStatus(t, err, 400)
}
