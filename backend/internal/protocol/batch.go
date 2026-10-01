package protocol

import (
	"context"
	"crypto/ecdsa"
	"encoding/json"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/merkle"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type RegisterBatchInput struct {
	Name                string
	LotCode             string
	Size                int64
	ManufacturerAddress *string
	// ExpiresAt is an optional YYYY-MM-DD date published in the manifest.
	ExpiresAt *string
}

type Batch struct {
	BatchID            string  `json:"batchId"`
	Manufacturer       string  `json:"manufacturer"`
	Size               int32   `json:"size"`
	ConsumedCount      int32   `json:"consumedCount"`
	Recalled           bool    `json:"recalled"`
	Name               *string `json:"name"`
	LotCode            *string `json:"lotCode"`
	MerkleRoot         string  `json:"merkleRoot"`
	PhysicalBatchID    string  `json:"physicalBatchId"`
	MetadataCID        string  `json:"metadataCid"`
	MetadataHash       string  `json:"metadataHash"`
	MetadataGatewayURL string  `json:"metadataGatewayUrl"`
	TxHash             string  `json:"txHash"`
	BlockNumber        int64   `json:"blockNumber"`
	CreatedAt          string  `json:"createdAt"`
}

type UnitCredential struct {
	Index   uint32 `json:"index"`
	UnitKey string `json:"unitKey"`
	// PublicQR goes on the open label, SecretQR under the scratch-off layer.
	PublicQR string `json:"publicQr"`
	SecretQR string `json:"secretQr"`
}

type RegisteredBatch struct {
	Batch
	SegmentID  string `json:"segmentId"`
	IPFSPinned bool   `json:"ipfsPinned"`
	// KeysRevealOnce: unit private keys are never stored and are only
	// returned in this response.
	KeysRevealOnce bool             `json:"keysRevealOnce"`
	Units          []UnitCredential `json:"units"`
}

// manifest is the public batch document pinned to IPFS. Listing every unit
// key lets anyone recompute the on-chain Merkle root, and lets the API
// rebuild proofs if its database loses them.
type manifest struct {
	SchemaVersion int      `json:"schemaVersion"`
	Protocol      string   `json:"protocol"`
	Name          string   `json:"name"`
	LotCode       string   `json:"lotCode"`
	Manufacturer  string   `json:"manufacturer"`
	ExpiresAt     *string  `json:"expiresAt"`
	Size          int      `json:"size"`
	MerkleRoot    string   `json:"merkleRoot"`
	LeafEncoding  string   `json:"leafEncoding"`
	UnitKeys      []string `json:"unitKeys"`
}

const (
	manifestProtocol = "supplement-registry/2"
	leafEncoding     = "keccak256(bytes.concat(keccak256(abi.encode(uint32 index, address unitKey)))), sorted-pair tree"
	maxNameRunes     = 200
	maxLotRunes      = 100
)

func (s *Service) RegisterBatch(ctx context.Context, in RegisterBatchInput) (RegisteredBatch, error) {
	name := strings.TrimSpace(in.Name)
	lot := strings.TrimSpace(in.LotCode)
	switch {
	case name == "" || utf8.RuneCountInString(name) > maxNameRunes:
		return RegisteredBatch{}, apperr.BadRequest(fmt.Sprintf("name must be 1-%d characters", maxNameRunes))
	case lot == "" || utf8.RuneCountInString(lot) > maxLotRunes:
		return RegisteredBatch{}, apperr.BadRequest(fmt.Sprintf("lotCode must be 1-%d characters", maxLotRunes))
	case in.Size < 1 || in.Size > int64(s.opts.MaxBatchUnits):
		return RegisteredBatch{}, apperr.BadRequest(fmt.Sprintf("size must be between 1 and %d", s.opts.MaxBatchUnits))
	}
	if in.ExpiresAt != nil {
		if _, err := time.Parse(time.DateOnly, *in.ExpiresAt); err != nil {
			return RegisteredBatch{}, apperr.BadRequest("expiresAt must be a YYYY-MM-DD date")
		}
	}
	manufacturer := lowerOr(in.ManufacturerAddress, s.opts.DefaultManufacturer)
	if !common.IsHexAddress(manufacturer) {
		return RegisteredBatch{}, apperr.BadRequest("manufacturerAddress must be a hex address")
	}

	size := int(in.Size)
	keys := make([]*ecdsa.PrivateKey, size)
	addresses := make([]string, size)
	leaves := make([]merkle.Hash, size)
	for i := range size {
		key, err := crypto.GenerateKey()
		if err != nil {
			return RegisteredBatch{}, fmt.Errorf("generate unit key: %w", err)
		}
		addr := crypto.PubkeyToAddress(key.PublicKey)
		keys[i], addresses[i], leaves[i] = key, strings.ToLower(addr.Hex()), merkle.UnitLeaf(uint32(i), addr)
	}
	tree, err := merkle.Build(leaves)
	if err != nil {
		return RegisteredBatch{}, err
	}

	body, err := jsonx.Marshal(manifest{
		SchemaVersion: 2,
		Protocol:      manifestProtocol,
		Name:          name,
		LotCode:       lot,
		Manufacturer:  manufacturer,
		ExpiresAt:     in.ExpiresAt,
		Size:          size,
		MerkleRoot:    tree.Root().Hex(),
		LeafEncoding:  leafEncoding,
		UnitKeys:      addresses,
	})
	if err != nil {
		return RegisteredBatch{}, err
	}
	pinned, err := s.pinner.PinJSON(ctx, body)
	if err != nil {
		return RegisteredBatch{}, err
	}

	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()
	event, err := s.registry.RegisterBatch(chainCtx, chain.RegisterBatchV2Input{
		Manufacturer:    manufacturer,
		MerkleRoot:      tree.Root(),
		Size:            uint32(size),
		MetadataCID:     pinned.CID,
		MetadataHash:    common.HexToHash(pinned.ContentHash),
		PhysicalBatchID: PhysicalBatchID(manufacturer, lot),
	})
	if err != nil {
		return RegisteredBatch{}, err
	}

	row := db.Batch{
		BatchID: event.BatchID, Manufacturer: event.Manufacturer, Size: int32(event.Size),
		MerkleRoot: event.MerkleRoot, PhysicalBatchID: event.PhysicalBatchID,
		MetadataCID: event.MetadataCID, MetadataHash: event.MetadataHash,
		Name: &name, LotCode: &lot, TxHash: event.TxHash, BlockNumber: int64(event.BlockNumber),
		CreatedAt: jsonx.Now(),
	}
	s.project(ctx, "BatchRegistered", func() error {
		projected, err := s.projector.BatchRegistered(chainCtx, event, chain.BatchLabels{Name: &name, LotCode: &lot})
		if err == nil {
			row = projected
			err = s.storeUnits(chainCtx, event.BatchID, addresses, tree)
		}
		return err
	})

	units := make([]UnitCredential, size)
	for i := range size {
		units[i] = UnitCredential{
			Index:    uint32(i),
			UnitKey:  addresses[i],
			PublicQR: s.PublicQR(event.BatchID, uint32(i)),
			SecretQR: SecretQR(s.opts.ChainID, event.BatchID, uint32(i), keys[i]),
		}
	}

	s.record(chainCtx, audit.Entry{
		Action:   "batch.register",
		EntityID: formatID(event.BatchID),
		Actor:    manufacturer,
		Detail: struct {
			Size        int    `json:"size"`
			MerkleRoot  string `json:"merkleRoot"`
			MetadataCID string `json:"metadataCid"`
			TxHash      string `json:"txHash"`
		}{size, event.MerkleRoot, event.MetadataCID, event.TxHash},
	})

	return RegisteredBatch{
		Batch:          s.toBatch(row),
		SegmentID:      formatID(event.SegmentID),
		IPFSPinned:     pinned.Pinned,
		KeysRevealOnce: true,
		Units:          units,
	}, nil
}

// PhysicalBatchID binds a lot code to its manufacturer; the registry rejects
// a second batch with the same id, so a lot cannot be registered twice.
func PhysicalBatchID(manufacturer, lotCode string) common.Hash {
	return crypto.Keccak256Hash([]byte("lot:" + strings.ToLower(manufacturer) + ":" + lotCode))
}

func (s *Service) storeUnits(ctx context.Context, batchID int64, addresses []string, tree *merkle.Tree) error {
	indexes := make([]int32, len(addresses))
	proofs := make([][]byte, len(addresses))
	for i := range addresses {
		proof, err := tree.Proof(i)
		if err != nil {
			return err
		}
		indexes[i], proofs[i] = int32(i), merkle.PackProof(proof)
	}
	_, err := s.store.InsertUnits(ctx, db.InsertUnitsParams{
		BatchID:  batchID,
		Indexes:  indexes,
		UnitKeys: addresses,
		Proofs:   proofs,
		Now:      jsonx.Now(),
	})
	return err
}

// restoreUnits rebuilds the unit rows of a batch from its IPFS manifest,
// e.g. for batches registered while the API's projection failed.
func (s *Service) restoreUnits(ctx context.Context, batch db.Batch) error {
	unavailable := apperr.ServiceUnavailable("Unit data for batch " + formatID(batch.BatchID) + " is not available yet")
	raw := s.pinner.ResolveJSON(ctx, batch.MetadataCID)
	if raw == nil {
		return unavailable
	}
	var m manifest
	if err := json.Unmarshal(raw, &m); err != nil || len(m.UnitKeys) != int(batch.Size) {
		s.log.WarnContext(ctx, "batch manifest unusable", "batchId", batch.BatchID, "err", err)
		return unavailable
	}
	leaves := make([]merkle.Hash, len(m.UnitKeys))
	for i, k := range m.UnitKeys {
		if !common.IsHexAddress(k) {
			return unavailable
		}
		m.UnitKeys[i] = strings.ToLower(k)
		leaves[i] = merkle.UnitLeaf(uint32(i), common.HexToAddress(k))
	}
	tree, err := merkle.Build(leaves)
	if err != nil {
		return err
	}
	if !strings.EqualFold(tree.Root().Hex(), batch.MerkleRoot) {
		s.log.ErrorContext(ctx, "batch manifest does not match the on-chain root", "batchId", batch.BatchID)
		return unavailable
	}
	return s.storeUnits(ctx, batch.BatchID, m.UnitKeys, tree)
}

func (s *Service) unit(ctx context.Context, batch db.Batch, index uint32) (db.Unit, error) {
	key := db.GetUnitParams{BatchID: batch.BatchID, Index: int32(index)}
	u, err := s.store.GetUnit(ctx, key)
	if store.IsNotFound(err) {
		if err := s.restoreUnits(ctx, batch); err != nil {
			return db.Unit{}, err
		}
		u, err = s.store.GetUnit(ctx, key)
	}
	if err == nil && u.Proof == nil {
		// Consumed before its proof was stored (indexer-only row).
		if err := s.restoreUnits(ctx, batch); err != nil {
			return db.Unit{}, err
		}
		u, err = s.store.GetUnit(ctx, key)
	}
	return u, err
}

func (s *Service) batch(ctx context.Context, batchID int64) (db.Batch, error) {
	b, err := s.store.GetBatch(ctx, batchID)
	if store.IsNotFound(err) {
		return db.Batch{}, apperr.NotFound("Batch " + formatID(batchID) + " not found")
	}
	return b, err
}

type BatchListQuery struct {
	Manufacturer string
	Paging
}

func (s *Service) ListBatches(ctx context.Context, q BatchListQuery) (Page[Batch], error) {
	page, limit, offset := q.resolve()
	var manufacturer *string
	if m := strings.TrimSpace(q.Manufacturer); m != "" {
		manufacturer = new(strings.ToLower(m))
	}
	total, err := s.store.CountBatches(ctx, manufacturer)
	if err != nil {
		return Page[Batch]{}, err
	}
	rows, err := s.store.ListBatches(ctx, db.ListBatchesParams{Manufacturer: manufacturer, PageLimit: int32(limit), PageOffset: int32(offset)})
	if err != nil {
		return Page[Batch]{}, err
	}
	items := make([]Batch, 0, len(rows))
	for _, r := range rows {
		items = append(items, s.toBatch(r))
	}
	return newPage(page, limit, total, items), nil
}

type BatchDetail struct {
	Batch
	Segments []Segment `json:"segments"`
	// Units per custody stage, from the segments.
	Distribution map[string]int32 `json:"distribution"`
}

func (s *Service) GetBatch(ctx context.Context, rawID string) (BatchDetail, error) {
	batchID, err := ParseID("batchId", rawID)
	if err != nil {
		return BatchDetail{}, err
	}
	snap, err := s.snapshot(ctx, batchID, false)
	if err != nil {
		return BatchDetail{}, err
	}
	dist := map[string]int32{"consumed": snap.batch.ConsumedCount}
	segments := make([]Segment, 0, len(snap.segments))
	for _, seg := range snap.segments {
		segments = append(segments, toSegment(seg))
		dist[string(seg.Status)] += seg.End - seg.Start
	}
	return BatchDetail{Batch: s.toBatch(snap.batch), Segments: segments, Distribution: dist}, nil
}

type UnitProof struct {
	ChainID    int64    `json:"chainId"`
	BatchID    string   `json:"batchId"`
	Index      uint32   `json:"index"`
	UnitKey    string   `json:"unitKey"`
	MerkleRoot string   `json:"merkleRoot"`
	Leaf       string   `json:"leaf"`
	Proof      []string `json:"proof"`
	SegmentID  *string  `json:"segmentId"`
	Consumed   bool     `json:"consumed"`
}

// Proof returns what a client needs to submit consume() itself or to check
// the unit against the on-chain root without trusting this API.
func (s *Service) Proof(ctx context.Context, rawBatchID, rawIndex string) (UnitProof, error) {
	batch, index, err := s.batchAndIndex(ctx, rawBatchID, rawIndex)
	if err != nil {
		return UnitProof{}, err
	}
	u, err := s.unit(ctx, batch, index)
	if err != nil {
		return UnitProof{}, err
	}
	proof, err := merkle.UnpackProof(u.Proof)
	if err != nil {
		return UnitProof{}, err
	}
	hexProof := make([]string, len(proof))
	for i, h := range proof {
		hexProof[i] = h.Hex()
	}
	out := UnitProof{
		ChainID:    s.opts.ChainID,
		BatchID:    formatID(batch.BatchID),
		Index:      index,
		UnitKey:    u.UnitKey,
		MerkleRoot: batch.MerkleRoot,
		Leaf:       merkle.UnitLeaf(index, common.HexToAddress(u.UnitKey)).Hex(),
		Proof:      hexProof,
		Consumed:   u.ConsumedTxHash != nil,
	}
	if seg, err := s.store.FindSegmentForUnit(ctx, db.FindSegmentForUnitParams{BatchID: batch.BatchID, Index: int32(index)}); err == nil {
		out.SegmentID = new(formatID(seg.SegmentID))
	} else if !store.IsNotFound(err) {
		return UnitProof{}, err
	}
	return out, nil
}

type CustodyStep struct {
	From        string `json:"from"`
	To          string `json:"to"`
	SegmentID   string `json:"segmentId"`
	Status      string `json:"status"`
	Units       int32  `json:"units"`
	TxHash      string `json:"txHash"`
	BlockNumber int64  `json:"blockNumber"`
	At          string `json:"at"`
}

type Consumption struct {
	Consumer    *string `json:"consumer"`
	TxHash      string  `json:"txHash"`
	BlockNumber *int64  `json:"blockNumber"`
	At          *string `json:"at"`
}

type UnitHistory struct {
	BatchID      string        `json:"batchId"`
	Index        uint32        `json:"index"`
	Manufacturer string        `json:"manufacturer"`
	RegisteredAt string        `json:"registeredAt"`
	RegisterTx   string        `json:"registerTxHash"`
	Custody      []CustodyStep `json:"custody"`
	Consumption  *Consumption  `json:"consumption"`
	Recalled     bool          `json:"recalled"`
}

func (s *Service) History(ctx context.Context, rawBatchID, rawIndex string) (UnitHistory, error) {
	batch, index, err := s.batchAndIndex(ctx, rawBatchID, rawIndex)
	if err != nil {
		return UnitHistory{}, err
	}
	rows, err := s.store.ListUnitCustody(ctx, db.ListUnitCustodyParams{BatchID: batch.BatchID, Index: int32(index)})
	if err != nil {
		return UnitHistory{}, err
	}
	steps := make([]CustodyStep, 0, len(rows))
	for _, r := range rows {
		steps = append(steps, CustodyStep{
			From: r.FromAddress, To: r.ToAddress, SegmentID: formatID(r.ToSegmentID), Status: string(r.Status),
			Units: r.End - r.Start, TxHash: r.TxHash, BlockNumber: r.BlockNumber, At: jsonx.ISOTime(r.CreatedAt),
		})
	}
	out := UnitHistory{
		BatchID:      formatID(batch.BatchID),
		Index:        index,
		Manufacturer: batch.Manufacturer,
		RegisteredAt: jsonx.ISOTime(batch.CreatedAt),
		RegisterTx:   batch.TxHash,
		Custody:      steps,
		Recalled:     batch.Invalid,
	}
	u, err := s.store.GetUnit(ctx, db.GetUnitParams{BatchID: batch.BatchID, Index: int32(index)})
	if err != nil && !store.IsNotFound(err) {
		return UnitHistory{}, err
	}
	if err == nil && u.ConsumedTxHash != nil {
		out.Consumption = &Consumption{Consumer: u.Consumer, TxHash: *u.ConsumedTxHash, BlockNumber: u.ConsumedBlock, At: isoPtr(u.ConsumedAt)}
	}
	return out, nil
}

type RecallInput struct {
	BatchID string
	// SegmentID limits the recall to one custody segment.
	SegmentID *string
	Reason    *string
}

type RecallResult struct {
	Scope       string  `json:"scope"`
	BatchID     string  `json:"batchId"`
	SegmentID   *string `json:"segmentId"`
	TxHash      string  `json:"txHash"`
	BlockNumber uint64  `json:"blockNumber"`
}

func (s *Service) Recall(ctx context.Context, in RecallInput) (RecallResult, error) {
	batchID, err := ParseID("batchId", in.BatchID)
	if err != nil {
		return RecallResult{}, err
	}
	batch, err := s.batch(ctx, batchID)
	if err != nil {
		return RecallResult{}, err
	}
	actors := []string{batch.Manufacturer}
	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()

	result := RecallResult{Scope: "batch", BatchID: formatID(batchID)}
	if in.SegmentID != nil {
		segmentID, err := ParseID("segmentId", *in.SegmentID)
		if err != nil {
			return RecallResult{}, err
		}
		seg, err := s.segment(ctx, segmentID)
		if err != nil {
			return RecallResult{}, err
		}
		if seg.BatchID != batchID {
			return RecallResult{}, apperr.BadRequest("Segment belongs to another batch")
		}
		event, err := s.registry.InvalidateSegment(chainCtx, actors, segmentID)
		if err != nil {
			return RecallResult{}, err
		}
		s.project(ctx, "SegmentInvalidated", func() error { return s.projector.SegmentInvalidated(chainCtx, event) })
		result.Scope, result.SegmentID = "segment", new(formatID(segmentID))
		result.TxHash, result.BlockNumber = event.TxHash, event.BlockNumber
	} else {
		event, err := s.registry.InvalidateBatch(chainCtx, actors, batchID)
		if err != nil {
			return RecallResult{}, err
		}
		s.project(ctx, "BatchInvalidated", func() error { return s.projector.BatchInvalidated(chainCtx, event) })
		result.TxHash, result.BlockNumber = event.TxHash, event.BlockNumber
	}

	s.record(chainCtx, audit.Entry{
		Action:   result.Scope + ".recall",
		EntityID: formatID(batchID),
		Actor:    batch.Manufacturer,
		Detail: struct {
			RecallResult
			Reason *string `json:"reason"`
		}{result, in.Reason},
	})
	return result, nil
}

func (s *Service) batchAndIndex(ctx context.Context, rawBatchID, rawIndex string) (db.Batch, uint32, error) {
	batchID, err := ParseID("batchId", rawBatchID)
	if err != nil {
		return db.Batch{}, 0, err
	}
	index, err := ParseIndex(rawIndex)
	if err != nil {
		return db.Batch{}, 0, err
	}
	batch, err := s.batch(ctx, batchID)
	if err != nil {
		return db.Batch{}, 0, err
	}
	if int64(index) >= int64(batch.Size) {
		return db.Batch{}, 0, apperr.NotFound(fmt.Sprintf("Unit %d is outside batch %d", index, batchID))
	}
	return batch, index, nil
}

func (s *Service) toBatch(b db.Batch) Batch {
	gateway := ""
	if b.MetadataCID != "" {
		gateway = s.pinner.GatewayURL(b.MetadataCID)
	}
	return Batch{
		BatchID:            formatID(b.BatchID),
		Manufacturer:       b.Manufacturer,
		Size:               b.Size,
		ConsumedCount:      b.ConsumedCount,
		Recalled:           b.Invalid,
		Name:               b.Name,
		LotCode:            b.LotCode,
		MerkleRoot:         b.MerkleRoot,
		PhysicalBatchID:    b.PhysicalBatchID,
		MetadataCID:        b.MetadataCID,
		MetadataHash:       b.MetadataHash,
		MetadataGatewayURL: gateway,
		TxHash:             b.TxHash,
		BlockNumber:        b.BlockNumber,
		CreatedAt:          jsonx.ISOTime(b.CreatedAt),
	}
}

func isoPtr(t *time.Time) *string {
	if t == nil {
		return nil
	}
	return new(jsonx.ISOTime(*t))
}
