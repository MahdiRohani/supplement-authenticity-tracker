package product

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"math"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ipfs"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type fakeStore struct {
	products     map[string]db.Product
	created      []db.CreateProductParams
	statusCalls  []db.SetProductStatusParams
	countArg     db.CountProductsParams
	listArg      db.ListProductsParams
	listRows     []db.ListProductsRow
	count        int64
	events       []db.ListOwnershipEventsRow
	batchRows    []db.ListProductsByBatchRow
	chainIDCalls []db.SetProductChainIdentityParams
}

func (f *fakeStore) CreateProduct(_ context.Context, arg db.CreateProductParams) (db.Product, error) {
	f.created = append(f.created, arg)
	return db.Product{
		ID: arg.ID, ChainProductID: arg.ChainProductID, OwnerAddress: arg.OwnerAddress, Status: arg.Status,
		Name: arg.Name, BatchCode: arg.BatchCode, MetadataCID: arg.MetadataCID, MetadataHash: arg.MetadataHash,
	}, nil
}

func (f *fakeStore) SetProductChainIdentity(_ context.Context, arg db.SetProductChainIdentityParams) (db.Product, error) {
	f.chainIDCalls = append(f.chainIDCalls, arg)
	last := f.created[len(f.created)-1]
	return db.Product{
		ID: arg.ID, ChainProductID: arg.ChainProductID, OwnerAddress: arg.OwnerAddress, Status: last.Status,
		Name: last.Name, BatchCode: last.BatchCode, MetadataCID: last.MetadataCID, MetadataHash: last.MetadataHash,
	}, nil
}

func (f *fakeStore) SetProductStatus(_ context.Context, arg db.SetProductStatusParams) (int64, error) {
	f.statusCalls = append(f.statusCalls, arg)
	return 1, nil
}

func (f *fakeStore) GetProductByChainID(_ context.Context, id string) (db.Product, error) {
	for _, p := range f.products {
		if p.ChainProductID == id {
			return p, nil
		}
	}
	return db.Product{}, pgx.ErrNoRows
}

func (f *fakeStore) FindProduct(_ context.Context, key string) (db.Product, error) {
	for _, p := range f.products {
		if p.ChainProductID == key || p.ID == key {
			return p, nil
		}
	}
	return db.Product{}, pgx.ErrNoRows
}

func (f *fakeStore) CountProducts(_ context.Context, arg db.CountProductsParams) (int64, error) {
	f.countArg = arg
	return f.count, nil
}

func (f *fakeStore) ListProducts(_ context.Context, arg db.ListProductsParams) ([]db.ListProductsRow, error) {
	f.listArg = arg
	return f.listRows, nil
}

func (f *fakeStore) ListProductsByBatch(_ context.Context, _ *string) ([]db.ListProductsByBatchRow, error) {
	return f.batchRows, nil
}

func (f *fakeStore) ListOwnershipEvents(_ context.Context, _ string) ([]db.ListOwnershipEventsRow, error) {
	return f.events, nil
}

type fakeRelayer struct {
	registerErr error
	consumeErr  error
	consumed    int
}

func (f *fakeRelayer) RegisterUnit(_ context.Context, in chain.RegisterUnitInput) (chain.RegisteredUnit, error) {
	if f.registerErr != nil {
		return chain.RegisteredUnit{}, f.registerErr
	}
	return chain.RegisteredUnit{ChainProductID: "12", OwnerAddress: in.ManufacturerAddress, TxHash: "0xtx", BlockNumber: 3}, nil
}

func (f *fakeRelayer) RegisterBatch(_ context.Context, in chain.RegisterBatchInput) (chain.RegisteredBatch, error) {
	if f.registerErr != nil {
		return chain.RegisteredBatch{}, f.registerErr
	}
	return chain.RegisteredBatch{FirstChainProductID: "20", Count: len(in.SecretHashes), TxHash: "0xbatch"}, nil
}

func (f *fakeRelayer) TransferOwnership(_ context.Context, owner, _, _ string) (chain.TxResult, error) {
	return chain.TxResult{Actor: owner, TxHash: "0xtransfer", BlockNumber: 5}, nil
}

func (f *fakeRelayer) Consume(_ context.Context, owner, _, _ string) (chain.TxResult, error) {
	f.consumed++
	if f.consumeErr != nil {
		return chain.TxResult{}, f.consumeErr
	}
	return chain.TxResult{Actor: owner, TxHash: "0xconsume", BlockNumber: 6}, nil
}

type fakePinner struct{ bodies []string }

func (f *fakePinner) PinJSON(_ context.Context, body []byte) (ipfs.Pinned, error) {
	f.bodies = append(f.bodies, string(body))
	return ipfs.Pinned{CID: "bafystub", ContentHash: "0xhash", GatewayURL: "https://ipfs.io/ipfs/bafystub"}, nil
}

type fakeAudit struct{ entries []audit.Entry }

func (f *fakeAudit) Record(_ context.Context, e audit.Entry) error {
	f.entries = append(f.entries, e)
	return nil
}

type fakeCache struct{ deleted []string }

func (f *fakeCache) Delete(key string) { f.deleted = append(f.deleted, key) }

type fixture struct {
	svc     *Service
	store   *fakeStore
	relayer *fakeRelayer
	pinner  *fakePinner
	audit   *fakeAudit
	cache   *fakeCache
}

func newFixture() fixture {
	f := fixture{
		store:   &fakeStore{products: map[string]db.Product{}},
		relayer: &fakeRelayer{},
		pinner:  &fakePinner{},
		audit:   &fakeAudit{},
		cache:   &fakeCache{},
	}
	f.svc = NewService(f.store, f.relayer, f.pinner, f.audit, f.cache, slog.New(slog.NewTextHandler(io.Discard, nil)))
	f.svc.now = func() time.Time { return time.UnixMilli(1_700_000_000_000) }
	return f
}

func ptr(s string) *string { return &s }

func statusOf(err error) int {
	var appErr *apperr.Error
	if errors.As(err, &appErr) {
		return appErr.Status
	}
	return 500
}

func TestConsumeRejectsSecondConsumeWithoutAudit(t *testing.T) {
	f := newFixture()
	f.store.products["p1"] = db.Product{ID: "p1", ChainProductID: "7", OwnerAddress: "0xowner", Status: db.ProductStatusAtPointOfSale}
	f.relayer.consumeErr = apperr.Conflict("Product already consumed; refill is not allowed")

	_, err := f.svc.Consume(context.Background(), "7", "0x"+strings.Repeat("11", 32))
	if statusOf(err) != 409 {
		t.Fatalf("err = %v", err)
	}
	if len(f.audit.entries) != 0 || len(f.store.statusCalls) != 0 {
		t.Fatal("a rejected consume must not be audited or persisted")
	}
}

func TestConsumeAlreadyConsumedShortCircuits(t *testing.T) {
	f := newFixture()
	f.store.products["p1"] = db.Product{ID: "p1", ChainProductID: "7", OwnerAddress: "0xowner", Status: db.ProductStatusConsumed}
	_, err := f.svc.Consume(context.Background(), "7", "0x"+strings.Repeat("11", 32))
	if statusOf(err) != 409 || f.relayer.consumed != 0 {
		t.Fatalf("err = %v, relayer calls = %d", err, f.relayer.consumed)
	}
}

func TestConsumeSuccessPersistsAndInvalidatesCache(t *testing.T) {
	f := newFixture()
	f.store.products["p1"] = db.Product{ID: "p1", ChainProductID: "7", OwnerAddress: "0xowner", Status: db.ProductStatusAtPointOfSale}
	out, err := f.svc.Consume(context.Background(), "p1", "0x"+strings.Repeat("11", 32))
	if err != nil {
		t.Fatal(err)
	}
	if out.Status != "Consumed" || out.ChainProductID != "7" || out.TxHash != "0xconsume" {
		t.Fatalf("out = %+v", out)
	}
	if len(f.store.statusCalls) != 1 || f.store.statusCalls[0].Status != db.ProductStatusConsumed {
		t.Fatalf("status calls = %+v", f.store.statusCalls)
	}
	if strings.Join(f.cache.deleted, ",") != "verify:p1,verify:7" {
		t.Fatalf("invalidated = %v", f.cache.deleted)
	}
	if len(f.audit.entries) != 1 || f.audit.entries[0].Action != "product.consume" {
		t.Fatalf("audit = %+v", f.audit.entries)
	}
}

func TestConsumeValidation(t *testing.T) {
	f := newFixture()
	f.store.products["p1"] = db.Product{ID: "p1", ChainProductID: "pending-1", Status: db.ProductStatusCreated}

	if _, err := f.svc.Consume(context.Background(), "p1", "0x12"); statusOf(err) != 400 {
		t.Fatalf("short secret: %v", err)
	}
	secret := "0x" + strings.Repeat("11", 32)
	if _, err := f.svc.Consume(context.Background(), "missing", secret); statusOf(err) != 404 {
		t.Fatalf("missing product: %v", err)
	}
	_, err := f.svc.Consume(context.Background(), "p1", secret)
	if statusOf(err) != 400 || !strings.Contains(err.Error(), "not minted on-chain yet") {
		t.Fatalf("pending product: %v", err)
	}
}

func TestHistoryReturnsOrderedEventsWithElapsed(t *testing.T) {
	f := newFixture()
	f.store.products["p1"] = db.Product{ID: "p1", ChainProductID: "7", OwnerAddress: "0xowner", Status: db.ProductStatusTransferred}
	f.store.events = []db.ListOwnershipEventsRow{{
		ID: "e1", FromAddress: "0xa", ToAddress: "0xb", TxHash: "0xtx", BlockNumber: 10,
		CreatedAt: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC),
	}}
	h, err := f.svc.History(context.Background(), "7")
	if err != nil {
		t.Fatal(err)
	}
	if len(h.Events) != 1 || h.Events[0].BlockNumber != "10" || h.Events[0].CreatedAt != "2026-01-01T00:00:00.000Z" {
		t.Fatalf("history = %+v", h)
	}
	if h.ElapsedMs < 0 || h.CurrentOwner != "0xowner" {
		t.Fatalf("history = %+v", h)
	}
}

func TestListFiltersAndPaginates(t *testing.T) {
	f := newFixture()
	f.store.count = 1
	f.store.listRows = []db.ListProductsRow{{
		ID: "p1", ChainProductID: "7", OwnerAddress: "0xowner", Status: db.ProductStatusCreated,
		Name: ptr("Vitamin D3"), BatchCode: ptr("B-1"), MetadataCID: ptr("bafy"),
		CreatedAt: time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC),
	}}
	page, err := f.svc.List(context.Background(), ListQuery{Owner: "0xOwner", Status: "Created", Q: " 50%_off ", Page: 1, Limit: 10})
	if err != nil {
		t.Fatal(err)
	}
	if page.Total != 1 || page.TotalPages != 1 || *page.Items[0].Name != "Vitamin D3" {
		t.Fatalf("page = %+v", page)
	}
	arg := f.store.listArg
	if *arg.Owner != "0xowner" || *arg.Status != db.ProductStatusCreated || *arg.Pattern != `%50\%\_off%` {
		t.Fatalf("filters = owner %v status %v pattern %v", *arg.Owner, *arg.Status, *arg.Pattern)
	}
	if arg.PageLimit != 10 || arg.PageOffset != 0 {
		t.Fatalf("paging = %+v", arg)
	}
}

func TestListClampsPaging(t *testing.T) {
	f := newFixture()
	f.store.count = 45
	page, err := f.svc.List(context.Background(), ListQuery{Status: "Bogus", Page: math.NaN(), Limit: 500})
	if err != nil {
		t.Fatal(err)
	}
	if page.Page != 1 || page.Limit != 100 || page.TotalPages != 1 || f.store.listArg.Status != nil {
		t.Fatalf("page = %+v status = %v", page, f.store.listArg.Status)
	}
	page, _ = f.svc.List(context.Background(), ListQuery{Page: 2.7, Limit: 0})
	if page.Page != 2 || page.Limit != 1 || page.TotalPages != 45 || f.store.listArg.PageOffset != 1 {
		t.Fatalf("page = %+v offset = %d", page, f.store.listArg.PageOffset)
	}
}

func TestRegisterMintsAndRevealsSecretOnce(t *testing.T) {
	f := newFixture()
	out, err := f.svc.Register(context.Background(), RegisterInput{Name: `Vitamin <D3> & "Co"`})
	if err != nil {
		t.Fatal(err)
	}
	if !out.MintedOnChain || out.ChainProductID != "12" || !out.SecretRevealOnce {
		t.Fatalf("out = %+v", out)
	}
	if len(out.Secret) != 66 || len(out.SecretHash) != 66 || len(out.PhysicalID) != 66 {
		t.Fatalf("secret material = %s %s %s", out.Secret, out.SecretHash, out.PhysicalID)
	}
	if f.pinner.bodies[0] != `{"name":"Vitamin <D3> & \"Co\"","batch":null,"schemaVersion":1}` {
		t.Fatalf("metadata = %s", f.pinner.bodies[0])
	}
	if f.store.created[0].OwnerAddress != strings.ToLower(DefaultManufacturer) {
		t.Fatalf("owner = %s", f.store.created[0].OwnerAddress)
	}
	if f.store.created[0].ChainProductID != "pending-1700000000000" {
		t.Fatalf("pending id = %s", f.store.created[0].ChainProductID)
	}
}

func TestRegisterKeepsPendingProductWhenMintFails(t *testing.T) {
	f := newFixture()
	f.relayer.registerErr = errors.New("rpc down")
	physical := "0x" + strings.Repeat("ab", 32)
	out, err := f.svc.Register(context.Background(), RegisterInput{Name: "Zinc", PhysicalID: &physical})
	if err != nil {
		t.Fatal(err)
	}
	if out.MintedOnChain || !strings.HasPrefix(out.ChainProductID, "pending-") || out.PhysicalID != physical {
		t.Fatalf("out = %+v", out)
	}
	if len(f.audit.entries) != 1 {
		t.Fatal("registration must be audited even when minting fails")
	}
}

func TestRegisterBatchAssignsSequentialChainIDs(t *testing.T) {
	f := newFixture()
	out, err := f.svc.RegisterBatch(context.Background(), RegisterBatchInput{Name: "Omega", Batch: ptr("B-1"), Count: 3})
	if err != nil {
		t.Fatal(err)
	}
	ids := []string{out.Items[0].ChainProductID, out.Items[1].ChainProductID, out.Items[2].ChainProductID}
	if strings.Join(ids, ",") != "20,21,22" || !out.MintedOnChain || *out.TxHash != "0xbatch" {
		t.Fatalf("out = %+v", out)
	}
	if f.pinner.bodies[0] != `{"name":"Omega","batch":"B-1","schemaVersion":1,"count":3}` {
		t.Fatalf("metadata = %s", f.pinner.bodies[0])
	}

	if _, err := f.svc.RegisterBatch(context.Background(), RegisterBatchInput{Name: "x", Count: 101}); statusOf(err) != 400 {
		t.Fatalf("count 101: %v", err)
	}
}

func TestTransferRequiresHexAddress(t *testing.T) {
	f := newFixture()
	f.store.products["p1"] = db.Product{ID: "p1", ChainProductID: "7", OwnerAddress: "0xowner"}
	if _, err := f.svc.Transfer(context.Background(), "7", strings.Repeat("f", 42)); statusOf(err) != 400 {
		t.Fatalf("err = %v", err)
	}
	out, err := f.svc.Transfer(context.Background(), "7", "0xABCDEF0000000000000000000000000000000001")
	if err != nil {
		t.Fatal(err)
	}
	if out.ToAddress != "0xabcdef0000000000000000000000000000000001" || out.FromAddress != "0xowner" {
		t.Fatalf("out = %+v", out)
	}
}

func TestLabelsPDF(t *testing.T) {
	f := newFixture()
	if _, err := f.svc.LabelsPDF(context.Background(), "NOPE"); statusOf(err) != 404 {
		t.Fatalf("err = %v", err)
	}
	f.store.batchRows = []db.ListProductsByBatchRow{{ChainProductID: "7", Status: db.ProductStatusCreated}}
	pdf, err := f.svc.LabelsPDF(context.Background(), "B-1")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(pdf), `(Product | id=7 | status=Created | {"v":1,"productId":"7","chainId":31337})`) {
		t.Fatalf("pdf = %s", pdf)
	}
}
