// Package product implements registration, inventory, transfer, consumption
// and label export for supplement units.
package product

import (
	"context"
	"crypto/rand"
	"fmt"
	"log/slog"
	"math"
	"math/big"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/crypto"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ipfs"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// DefaultManufacturer is Hardhat account #0, used when a request names no
// manufacturer.
const DefaultManufacturer = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266"

type Store interface {
	CreateProduct(ctx context.Context, arg db.CreateProductParams) (db.Product, error)
	SetProductChainIdentity(ctx context.Context, arg db.SetProductChainIdentityParams) (db.Product, error)
	SetProductStatus(ctx context.Context, arg db.SetProductStatusParams) (int64, error)
	GetProductByChainID(ctx context.Context, chainProductID string) (db.Product, error)
	FindProduct(ctx context.Context, key string) (db.Product, error)
	CountProducts(ctx context.Context, arg db.CountProductsParams) (int64, error)
	ListProducts(ctx context.Context, arg db.ListProductsParams) ([]db.ListProductsRow, error)
	ListProductsByBatch(ctx context.Context, batchCode *string) ([]db.ListProductsByBatchRow, error)
	ListOwnershipEvents(ctx context.Context, productID string) ([]db.ListOwnershipEventsRow, error)
}

type Relayer interface {
	RegisterUnit(ctx context.Context, in chain.RegisterUnitInput) (chain.RegisteredUnit, error)
	RegisterBatch(ctx context.Context, in chain.RegisterBatchInput) (chain.RegisteredBatch, error)
	TransferOwnership(ctx context.Context, ownerAddress, chainProductID, toAddress string) (chain.TxResult, error)
	Consume(ctx context.Context, ownerAddress, chainProductID, secret string) (chain.TxResult, error)
}

type Pinner interface {
	PinJSON(ctx context.Context, body []byte) (ipfs.Pinned, error)
}

type Auditor interface {
	Record(ctx context.Context, e audit.Entry) error
}

// CacheInvalidator drops cached verify results after a state change.
type CacheInvalidator interface {
	Delete(key string)
}

type Service struct {
	store        Store
	relayer      Relayer
	pinner       Pinner
	audit        Auditor
	cache        CacheInvalidator
	log          *slog.Logger
	chainTimeout time.Duration
	now          func() time.Time
}

func NewService(st Store, relayer Relayer, pinner Pinner, auditor Auditor, cache CacheInvalidator, log *slog.Logger) *Service {
	return &Service{
		store:        st,
		relayer:      relayer,
		pinner:       pinner,
		audit:        auditor,
		cache:        cache,
		log:          log,
		chainTimeout: 2 * time.Minute,
		now:          time.Now,
	}
}

// chainContext detaches on-chain writes from the HTTP request: once a
// transaction is sent, a client disconnect must not skip the bookkeeping.
func (s *Service) chainContext(ctx context.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.WithoutCancel(ctx), s.chainTimeout)
}

type RegisterInput struct {
	Name                string
	Batch               *string
	ManufacturerAddress *string
	PhysicalID          *string
}

type Registered struct {
	ID                 string  `json:"id"`
	ChainProductID     string  `json:"chainProductId"`
	MetadataCID        *string `json:"metadataCid"`
	MetadataHash       *string `json:"metadataHash"`
	MetadataGatewayURL string  `json:"metadataGatewayUrl"`
	IPFSPinned         bool    `json:"ipfsPinned"`
	Secret             string  `json:"secret"`
	SecretHash         string  `json:"secretHash"`
	PhysicalID         string  `json:"physicalId"`
	Status             string  `json:"status"`
	Name               *string `json:"name"`
	BatchCode          *string `json:"batchCode"`
	SecretRevealOnce   bool    `json:"secretRevealOnce"`
	MintedOnChain      bool    `json:"mintedOnChain"`
}

type unitMetadata struct {
	Name          string  `json:"name"`
	Batch         *string `json:"batch"`
	SchemaVersion int     `json:"schemaVersion"`
}

// Register pins metadata, stores a pending product and tries to mint it
// on-chain. Minting failures are logged and leave a `pending-*` id; the
// scratch secret is only ever returned here.
func (s *Service) Register(ctx context.Context, in RegisterInput) (Registered, error) {
	secret, secretHash, err := newSecret()
	if err != nil {
		return Registered{}, err
	}
	physicalID := ""
	if in.PhysicalID != nil && strings.HasPrefix(*in.PhysicalID, "0x") {
		physicalID = *in.PhysicalID
	} else {
		physicalID = keccakText(fmt.Sprintf("%s:%s:%d:%s", in.Name, deref(in.Batch), s.now().UnixMilli(), secret))
	}

	body, err := jsonx.Marshal(unitMetadata{Name: in.Name, Batch: in.Batch, SchemaVersion: 1})
	if err != nil {
		return Registered{}, err
	}
	pinned, err := s.pinner.PinJSON(ctx, body)
	if err != nil {
		return Registered{}, err
	}
	manufacturer := strings.ToLower(valueOr(in.ManufacturerAddress, DefaultManufacturer))

	product, err := s.store.CreateProduct(ctx, db.CreateProductParams{
		ID:             cuid.New(),
		ChainProductID: fmt.Sprintf("pending-%d", s.now().UnixMilli()),
		OwnerAddress:   manufacturer,
		Status:         db.ProductStatusCreated,
		Name:           &in.Name,
		BatchCode:      in.Batch,
		MetadataCID:    &pinned.CID,
		MetadataHash:   &pinned.ContentHash,
		Now:            jsonx.Now(),
	})
	if err != nil {
		return Registered{}, err
	}

	minted := false
	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()
	unit, err := s.relayer.RegisterUnit(chainCtx, chain.RegisterUnitInput{
		ManufacturerAddress: manufacturer,
		SecretHash:          secretHash,
		MetadataCID:         pinned.CID,
		MetadataHash:        pinned.ContentHash,
		PhysicalID:          physicalID,
	})
	if err == nil {
		product, err = s.store.SetProductChainIdentity(chainCtx, db.SetProductChainIdentityParams{
			ID:             product.ID,
			ChainProductID: unit.ChainProductID,
			OwnerAddress:   unit.OwnerAddress,
			Now:            jsonx.Now(),
		})
		minted = err == nil
	}
	if err != nil {
		s.log.WarnContext(ctx, "on-chain register skipped", "productId", product.ID, "err", err)
	}

	err = s.audit.Record(chainCtx, audit.Entry{
		Action:   "product.register",
		EntityID: product.ID,
		Actor:    manufacturer,
		Detail: struct {
			ChainProductID string `json:"chainProductId"`
			MintedOnChain  bool   `json:"mintedOnChain"`
			SecretHash     string `json:"secretHash"`
			PhysicalID     string `json:"physicalId"`
		}{product.ChainProductID, minted, secretHash, physicalID},
	})
	if err != nil {
		return Registered{}, err
	}

	return Registered{
		ID:                 product.ID,
		ChainProductID:     product.ChainProductID,
		MetadataCID:        product.MetadataCID,
		MetadataHash:       product.MetadataHash,
		MetadataGatewayURL: pinned.GatewayURL,
		IPFSPinned:         pinned.Pinned,
		Secret:             secret,
		SecretHash:         secretHash,
		PhysicalID:         physicalID,
		Status:             string(product.Status),
		Name:               product.Name,
		BatchCode:          product.BatchCode,
		SecretRevealOnce:   true,
		MintedOnChain:      minted,
	}, nil
}

type RegisterBatchInput struct {
	Name                string
	Batch               *string
	Count               int64
	ManufacturerAddress *string
}

type BatchItem struct {
	ID             string `json:"id"`
	ChainProductID string `json:"chainProductId"`
	Secret         string `json:"secret"`
	SecretHash     string `json:"secretHash"`
	PhysicalID     string `json:"physicalId"`
}

type RegisteredBatch struct {
	Count            int         `json:"count"`
	MetadataCID      string      `json:"metadataCid"`
	MetadataHash     string      `json:"metadataHash"`
	MintedOnChain    bool        `json:"mintedOnChain"`
	TxHash           *string     `json:"txHash"`
	SecretRevealOnce bool        `json:"secretRevealOnce"`
	Items            []BatchItem `json:"items"`
}

type batchMetadata struct {
	Name          string  `json:"name"`
	Batch         *string `json:"batch"`
	SchemaVersion int     `json:"schemaVersion"`
	Count         int64   `json:"count"`
}

// RegisterBatch mints count units in one transaction when possible; product
// rows are created afterwards with sequential chain ids.
func (s *Service) RegisterBatch(ctx context.Context, in RegisterBatchInput) (RegisteredBatch, error) {
	if in.Count < 1 || in.Count > 100 {
		return RegisteredBatch{}, apperr.BadRequest("count must be between 1 and 100")
	}
	count := int(in.Count)
	manufacturer := strings.ToLower(valueOr(in.ManufacturerAddress, DefaultManufacturer))

	secrets := make([]string, count)
	secretHashes := make([]string, count)
	physicalIDs := make([]string, count)
	for i := range count {
		secret, secretHash, err := newSecret()
		if err != nil {
			return RegisteredBatch{}, err
		}
		secrets[i], secretHashes[i] = secret, secretHash
		physicalIDs[i] = keccakText(fmt.Sprintf("%s:%s:%d:%d:%s", in.Name, deref(in.Batch), s.now().UnixMilli(), i, secret))
	}

	body, err := jsonx.Marshal(batchMetadata{Name: in.Name, Batch: in.Batch, SchemaVersion: 1, Count: in.Count})
	if err != nil {
		return RegisteredBatch{}, err
	}
	pinned, err := s.pinner.PinJSON(ctx, body)
	if err != nil {
		return RegisteredBatch{}, err
	}

	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()
	var first *big.Int
	var txHash *string
	minted, err := s.relayer.RegisterBatch(chainCtx, chain.RegisterBatchInput{
		ManufacturerAddress: manufacturer,
		SecretHashes:        secretHashes,
		MetadataCID:         pinned.CID,
		MetadataHash:        pinned.ContentHash,
		PhysicalIDs:         physicalIDs,
	})
	if err == nil {
		if id, ok := new(big.Int).SetString(minted.FirstChainProductID, 10); ok {
			first = id
			txHash = &minted.TxHash
		} else {
			err = fmt.Errorf("unexpected first product id %q", minted.FirstChainProductID)
		}
	}
	if err != nil {
		s.log.WarnContext(ctx, "on-chain batch register skipped", "err", err)
	}

	items := make([]BatchItem, 0, count)
	for i := range count {
		chainProductID := fmt.Sprintf("pending-batch-%d-%d", s.now().UnixMilli(), i)
		if first != nil {
			chainProductID = new(big.Int).Add(first, big.NewInt(int64(i))).String()
		}
		row, err := s.store.CreateProduct(chainCtx, db.CreateProductParams{
			ID:             cuid.New(),
			ChainProductID: chainProductID,
			OwnerAddress:   manufacturer,
			Status:         db.ProductStatusCreated,
			Name:           &in.Name,
			BatchCode:      in.Batch,
			MetadataCID:    &pinned.CID,
			MetadataHash:   &pinned.ContentHash,
			Now:            jsonx.Now(),
		})
		if err != nil {
			return RegisteredBatch{}, err
		}
		items = append(items, BatchItem{
			ID:             row.ID,
			ChainProductID: row.ChainProductID,
			Secret:         secrets[i],
			SecretHash:     secretHashes[i],
			PhysicalID:     physicalIDs[i],
		})
	}

	var firstID *string
	if first != nil {
		id := first.String()
		firstID = &id
	}
	err = s.audit.Record(chainCtx, audit.Entry{
		Action: "product.register_batch",
		Actor:  manufacturer,
		Detail: struct {
			Count               int64   `json:"count"`
			MintedOnChain       bool    `json:"mintedOnChain"`
			TxHash              *string `json:"txHash"`
			FirstChainProductID *string `json:"firstChainProductId"`
		}{in.Count, first != nil, txHash, firstID},
	})
	if err != nil {
		return RegisteredBatch{}, err
	}

	return RegisteredBatch{
		Count:            len(items),
		MetadataCID:      pinned.CID,
		MetadataHash:     pinned.ContentHash,
		MintedOnChain:    first != nil,
		TxHash:           txHash,
		SecretRevealOnce: true,
		Items:            items,
	}, nil
}

type ListQuery struct {
	Owner  string
	Status string
	Q      string
	// Page and Limit are NaN when absent or unparseable.
	Page  float64
	Limit float64
}

type Summary struct {
	ID             string  `json:"id"`
	ChainProductID string  `json:"chainProductId"`
	OwnerAddress   string  `json:"ownerAddress"`
	Status         string  `json:"status"`
	Name           *string `json:"name"`
	BatchCode      *string `json:"batchCode"`
	MetadataCID    *string `json:"metadataCid"`
	CreatedAt      string  `json:"createdAt"`
}

type Page struct {
	Page       int64     `json:"page"`
	Limit      int64     `json:"limit"`
	Total      int64     `json:"total"`
	TotalPages int64     `json:"totalPages"`
	Items      []Summary `json:"items"`
}

func (s *Service) List(ctx context.Context, q ListQuery) (Page, error) {
	page := clampInt(q.Page, 1, 1, math.MaxInt32)
	limit := clampInt(q.Limit, 20, 1, 100)

	var filter db.CountProductsParams
	if q.Owner != "" {
		owner := strings.ToLower(q.Owner)
		filter.Owner = &owner
	}
	if status := db.ProductStatus(q.Status); status.Valid() {
		filter.Status = &status
	}
	if term := strings.TrimSpace(q.Q); term != "" {
		pattern := "%" + likeEscaper.Replace(term) + "%"
		filter.Pattern = &pattern
	}

	total, err := s.store.CountProducts(ctx, filter)
	if err != nil {
		return Page{}, err
	}
	offset := (page - 1) * limit
	if offset > math.MaxInt32 {
		offset = math.MaxInt32
	}
	rows, err := s.store.ListProducts(ctx, db.ListProductsParams{
		Owner:      filter.Owner,
		Status:     filter.Status,
		Pattern:    filter.Pattern,
		PageLimit:  int32(limit),
		PageOffset: int32(offset),
	})
	if err != nil {
		return Page{}, err
	}

	items := make([]Summary, 0, len(rows))
	for _, r := range rows {
		items = append(items, Summary{
			ID:             r.ID,
			ChainProductID: r.ChainProductID,
			OwnerAddress:   r.OwnerAddress,
			Status:         string(r.Status),
			Name:           r.Name,
			BatchCode:      r.BatchCode,
			MetadataCID:    r.MetadataCID,
			CreatedAt:      jsonx.ISOTime(r.CreatedAt),
		})
	}
	totalPages := max(1, (total+limit-1)/limit)
	return Page{Page: page, Limit: limit, Total: total, TotalPages: totalPages, Items: items}, nil
}

type TransferResult struct {
	ChainProductID string `json:"chainProductId"`
	FromAddress    string `json:"fromAddress"`
	ToAddress      string `json:"toAddress"`
	TxHash         string `json:"txHash"`
	BlockNumber    uint64 `json:"blockNumber"`
}

func (s *Service) Transfer(ctx context.Context, id, toAddress string) (TransferResult, error) {
	if !strings.HasPrefix(toAddress, "0x") {
		return TransferResult{}, apperr.BadRequest("toAddress must be a hex address")
	}
	product, err := s.findMinted(ctx, id)
	if err != nil {
		return TransferResult{}, err
	}

	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()
	tx, err := s.relayer.TransferOwnership(chainCtx, product.OwnerAddress, product.ChainProductID, toAddress)
	if err != nil {
		return TransferResult{}, err
	}
	result := TransferResult{
		ChainProductID: product.ChainProductID,
		FromAddress:    product.OwnerAddress,
		ToAddress:      strings.ToLower(toAddress),
		TxHash:         tx.TxHash,
		BlockNumber:    tx.BlockNumber,
	}
	if err := s.audit.Record(chainCtx, audit.Entry{
		Action:   "product.transfer",
		EntityID: id,
		Actor:    result.FromAddress,
		Detail:   result,
	}); err != nil {
		return TransferResult{}, err
	}
	s.invalidate(id, result.ChainProductID)
	return result, nil
}

type ConsumeResult struct {
	ChainProductID string `json:"chainProductId"`
	ProductID      string `json:"productId"`
	Status         string `json:"status"`
	Actor          string `json:"actor"`
	TxHash         string `json:"txHash"`
	BlockNumber    uint64 `json:"blockNumber"`
}

// Consume burns the scratch secret on-chain. A second consume is rejected
// with 409 (anti-refill).
func (s *Service) Consume(ctx context.Context, id, secret string) (ConsumeResult, error) {
	if !strings.HasPrefix(secret, "0x") || len(secret) != 66 {
		return ConsumeResult{}, apperr.BadRequest("secret must be a bytes32 hex string")
	}
	product, err := s.findMinted(ctx, id)
	if err != nil {
		return ConsumeResult{}, err
	}
	if product.Status == db.ProductStatusConsumed {
		return ConsumeResult{}, apperr.Conflict("Product already consumed; refill is not allowed")
	}

	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()
	tx, err := s.relayer.Consume(chainCtx, product.OwnerAddress, product.ChainProductID, secret)
	if err != nil {
		return ConsumeResult{}, err
	}
	updated, err := s.store.SetProductStatus(chainCtx, db.SetProductStatusParams{
		ID:     product.ID,
		Status: db.ProductStatusConsumed,
		Now:    jsonx.Now(),
	})
	if err != nil {
		return ConsumeResult{}, err
	}
	if updated == 0 {
		return ConsumeResult{}, fmt.Errorf("product %s disappeared while consuming", product.ID)
	}
	s.invalidate(id, product.ChainProductID)

	if err := s.audit.Record(chainCtx, audit.Entry{
		Action:   "product.consume",
		EntityID: product.ID,
		Actor:    tx.Actor,
		Detail: struct {
			ChainProductID string `json:"chainProductId"`
			TxHash         string `json:"txHash"`
		}{product.ChainProductID, tx.TxHash},
	}); err != nil {
		return ConsumeResult{}, err
	}
	return ConsumeResult{
		ChainProductID: product.ChainProductID,
		ProductID:      product.ID,
		Status:         string(db.ProductStatusConsumed),
		Actor:          tx.Actor,
		TxHash:         tx.TxHash,
		BlockNumber:    tx.BlockNumber,
	}, nil
}

type Detail struct {
	ID             string  `json:"id"`
	ChainProductID string  `json:"chainProductId"`
	OwnerAddress   string  `json:"ownerAddress"`
	Status         string  `json:"status"`
	Name           *string `json:"name"`
	BatchCode      *string `json:"batchCode"`
	MetadataCID    *string `json:"metadataCid"`
	MetadataHash   *string `json:"metadataHash"`
	CreatedAt      string  `json:"createdAt"`
	UpdatedAt      string  `json:"updatedAt"`
}

func (s *Service) ByChainID(ctx context.Context, chainProductID string) (Detail, error) {
	p, err := s.store.GetProductByChainID(ctx, chainProductID)
	if store.IsNotFound(err) {
		return Detail{}, apperr.NotFound("Product " + chainProductID + " not found")
	}
	if err != nil {
		return Detail{}, err
	}
	return Detail{
		ID:             p.ID,
		ChainProductID: p.ChainProductID,
		OwnerAddress:   p.OwnerAddress,
		Status:         string(p.Status),
		Name:           p.Name,
		BatchCode:      p.BatchCode,
		MetadataCID:    p.MetadataCID,
		MetadataHash:   p.MetadataHash,
		CreatedAt:      jsonx.ISOTime(p.CreatedAt),
		UpdatedAt:      jsonx.ISOTime(p.UpdatedAt),
	}, nil
}

type HistoryEvent struct {
	ID          string `json:"id"`
	FromAddress string `json:"fromAddress"`
	ToAddress   string `json:"toAddress"`
	TxHash      string `json:"txHash"`
	BlockNumber string `json:"blockNumber"`
	CreatedAt   string `json:"createdAt"`
}

type History struct {
	ProductID      string         `json:"productId"`
	ChainProductID string         `json:"chainProductId"`
	CurrentOwner   string         `json:"currentOwner"`
	Status         string         `json:"status"`
	ElapsedMs      int64          `json:"elapsedMs"`
	Events         []HistoryEvent `json:"events"`
}

func (s *Service) History(ctx context.Context, id string) (History, error) {
	started := time.Now()
	p, err := s.store.FindProduct(ctx, id)
	if store.IsNotFound(err) {
		return History{}, apperr.NotFound("Product " + id + " not found")
	}
	if err != nil {
		return History{}, err
	}
	rows, err := s.store.ListOwnershipEvents(ctx, p.ID)
	if err != nil {
		return History{}, err
	}
	events := make([]HistoryEvent, 0, len(rows))
	for _, e := range rows {
		events = append(events, HistoryEvent{
			ID:          e.ID,
			FromAddress: e.FromAddress,
			ToAddress:   e.ToAddress,
			TxHash:      e.TxHash,
			BlockNumber: fmt.Sprint(e.BlockNumber),
			CreatedAt:   jsonx.ISOTime(e.CreatedAt),
		})
	}
	return History{
		ProductID:      p.ID,
		ChainProductID: p.ChainProductID,
		CurrentOwner:   p.OwnerAddress,
		Status:         string(p.Status),
		ElapsedMs:      time.Since(started).Milliseconds(),
		Events:         events,
	}, nil
}

// LabelsPDF renders printable labels (one line per unit with its QR payload)
// for up to 500 units of a batch.
func (s *Service) LabelsPDF(ctx context.Context, batchCode string) ([]byte, error) {
	rows, err := s.store.ListProductsByBatch(ctx, &batchCode)
	if err != nil {
		return nil, err
	}
	if len(rows) == 0 {
		return nil, apperr.NotFound("No products for batch " + batchCode)
	}
	lines := []string{
		"Supplement batch labels",
		"Batch: " + batchCode,
		fmt.Sprintf("Units: %d", len(rows)),
		"",
	}
	for _, r := range rows {
		qr, err := jsonx.Marshal(struct {
			V         int    `json:"v"`
			ProductID string `json:"productId"`
			ChainID   int    `json:"chainId"`
		}{1, r.ChainProductID, 31337})
		if err != nil {
			return nil, err
		}
		name := "Product"
		if r.Name != nil {
			name = *r.Name
		}
		lines = append(lines, fmt.Sprintf("%s | id=%s | status=%s | %s", name, r.ChainProductID, r.Status, qr))
	}
	return RenderPDF(lines), nil
}

// findMinted loads a product by chain id or row id and requires it to have a
// numeric on-chain id.
func (s *Service) findMinted(ctx context.Context, id string) (db.Product, error) {
	p, err := s.store.FindProduct(ctx, id)
	if store.IsNotFound(err) {
		return db.Product{}, apperr.NotFound("Product " + id + " not found")
	}
	if err != nil {
		return db.Product{}, err
	}
	if !isDigits(p.ChainProductID) {
		return db.Product{}, apperr.BadRequest("Product " + id + " is not minted on-chain yet")
	}
	return p, nil
}

func (s *Service) invalidate(ids ...string) {
	for _, id := range ids {
		s.cache.Delete("verify:" + id)
	}
}

var likeEscaper = strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)

// newSecret returns a random bytes32 scratch secret and keccak256(secret),
// which is what the contract stores.
func newSecret() (secret, secretHash string, err error) {
	var b [32]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", "", fmt.Errorf("generate secret: %w", err)
	}
	return hexutil.Encode(b[:]), crypto.Keccak256Hash(b[:]).Hex(), nil
}

func keccakText(s string) string {
	return crypto.Keccak256Hash([]byte(s)).Hex()
}

func clampInt(v float64, fallback, lo, hi int64) int64 {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return fallback
	}
	n := int64(math.Floor(math.Max(float64(lo), math.Min(float64(hi), v))))
	return n
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

func valueOr(s *string, fallback string) string {
	if s == nil {
		return fallback
	}
	return *s
}
