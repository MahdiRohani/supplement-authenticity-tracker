package chain

import (
	"cmp"
	"context"
	"fmt"
	"log/slog"
	"math/big"
	"slices"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/common/hexutil"
	"github.com/ethereum/go-ethereum/core/types"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// IndexerStore is the persistence the indexer projects events into.
type IndexerStore interface {
	UpsertRegisteredProduct(ctx context.Context, arg db.UpsertRegisteredProductParams) error
	UpsertProductOwner(ctx context.Context, arg db.UpsertProductOwnerParams) (string, error)
	UpsertProductStatus(ctx context.Context, arg db.UpsertProductStatusParams) error
	OwnershipEventExists(ctx context.Context, arg db.OwnershipEventExistsParams) (bool, error)
	CreateOwnershipEvent(ctx context.Context, arg db.CreateOwnershipEventParams) error
}

// Indexer polls registry events and projects them into Postgres. It starts
// from the chain head at startup (no historical backfill); a failed poll is
// retried from the same block, and handlers are idempotent.
type Indexer struct {
	backend  Backend
	contract *Contract
	store    IndexerStore
	log      *slog.Logger
	interval time.Duration

	started   bool
	lastBlock uint64
	topics    []common.Hash
	handlers  map[common.Hash]eventHandler
}

type eventHandler struct {
	event  string
	handle func(context.Context, types.Log, map[string]any) error
}

func NewIndexer(backend Backend, contract *Contract, store IndexerStore, log *slog.Logger) *Indexer {
	ix := &Indexer{
		backend:  backend,
		contract: contract,
		store:    store,
		log:      log.With("component", "indexer"),
		interval: 3 * time.Second,
		handlers: map[common.Hash]eventHandler{},
	}
	for _, h := range []eventHandler{
		{"ProductRegistered", ix.onRegistered},
		{"OwnershipTransferred", ix.onTransferred},
		{"ProductConsumed", ix.onStatusEvent(db.ProductStatusConsumed)},
		{"ProductInvalidated", ix.onStatusEvent(db.ProductStatusInvalid)},
	} {
		id := contract.EventID(h.event)
		ix.topics = append(ix.topics, id)
		ix.handlers[id] = h
	}
	return ix
}

// Run polls until ctx is cancelled. An unreachable RPC endpoint is retried on
// every tick instead of disabling the indexer.
func (ix *Indexer) Run(ctx context.Context) {
	ix.log.Info("indexer listening", "registry", ix.contract.Address().Hex())
	ticker := time.NewTicker(ix.interval)
	defer ticker.Stop()
	for {
		if err := ix.Poll(ctx); err != nil && ctx.Err() == nil {
			ix.log.Warn("indexer poll failed", "err", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

// Poll processes all registry events between the last indexed block and the
// current head.
func (ix *Indexer) Poll(ctx context.Context) error {
	head, err := ix.backend.BlockNumber(ctx)
	if err != nil {
		return fmt.Errorf("block number: %w", err)
	}
	if !ix.started {
		ix.started = true
		ix.lastBlock = head
		return nil
	}
	if head <= ix.lastBlock {
		return nil
	}

	logs, err := ix.backend.FilterLogs(ctx, ethereum.FilterQuery{
		FromBlock: new(big.Int).SetUint64(ix.lastBlock + 1),
		ToBlock:   new(big.Int).SetUint64(head),
		Addresses: []common.Address{ix.contract.Address()},
		Topics:    [][]common.Hash{ix.topics},
	})
	if err != nil {
		return fmt.Errorf("filter logs: %w", err)
	}
	slices.SortFunc(logs, func(a, b types.Log) int {
		if c := cmp.Compare(a.BlockNumber, b.BlockNumber); c != 0 {
			return c
		}
		return cmp.Compare(a.Index, b.Index)
	})

	for _, log := range logs {
		if err := ix.handle(ctx, log); err != nil {
			return fmt.Errorf("block %d log %d: %w", log.BlockNumber, log.Index, err)
		}
	}
	ix.lastBlock = head
	return nil
}

func (ix *Indexer) handle(ctx context.Context, log types.Log) error {
	if len(log.Topics) == 0 {
		return nil
	}
	h, known := ix.handlers[log.Topics[0]]
	if !known {
		return nil
	}
	fields, _, err := ix.contract.ParseEvent(h.event, log)
	if err != nil {
		return err
	}
	return h.handle(ctx, log, fields)
}

func (ix *Indexer) onRegistered(ctx context.Context, _ types.Log, f map[string]any) error {
	productID := bigString(f["productId"])
	cid, _ := f["metadataCid"].(string)
	hash := hash32(f["metadataHash"])
	err := ix.store.UpsertRegisteredProduct(ctx, db.UpsertRegisteredProductParams{
		ID:             cuid.New(),
		ChainProductID: productID,
		OwnerAddress:   lowerAddress(f["manufacturer"]),
		Status:         StatusFromChain(f["status"], db.ProductStatusCreated),
		MetadataCID:    &cid,
		MetadataHash:   &hash,
		Now:            jsonx.Now(),
	})
	if err == nil {
		ix.log.Info("indexed ProductRegistered", "productId", productID)
	}
	return err
}

func (ix *Indexer) onTransferred(ctx context.Context, log types.Log, f map[string]any) error {
	chainProductID := bigString(f["productId"])
	txHash := log.TxHash.Hex()
	exists, err := ix.store.OwnershipEventExists(ctx, db.OwnershipEventExistsParams{TxHash: txHash, ChainProductID: chainProductID})
	if err != nil || exists {
		return err
	}

	onChain, err := ix.contract.Call(ctx, "getProduct", f["productId"])
	if err != nil {
		return fmt.Errorf("getProduct %s: %w", chainProductID, err)
	}
	from, to := lowerAddress(f["from"]), lowerAddress(f["to"])
	now := jsonx.Now()
	productRowID, err := ix.store.UpsertProductOwner(ctx, db.UpsertProductOwnerParams{
		ID:             cuid.New(),
		ChainProductID: chainProductID,
		OwnerAddress:   to,
		Status:         StatusFromChain(onChain[1], db.ProductStatusCreated),
		Now:            now,
	})
	if err != nil {
		return err
	}
	err = ix.store.CreateOwnershipEvent(ctx, db.CreateOwnershipEventParams{
		ID:             cuid.New(),
		ProductID:      productRowID,
		ChainProductID: chainProductID,
		FromAddress:    from,
		ToAddress:      to,
		TxHash:         txHash,
		BlockNumber:    int64(log.BlockNumber),
		Now:            now,
	})
	if err == nil {
		ix.log.Info("indexed OwnershipTransferred", "productId", chainProductID, "from", from, "to", to)
	}
	return err
}

func (ix *Indexer) onStatusEvent(status db.ProductStatus) func(context.Context, types.Log, map[string]any) error {
	return func(ctx context.Context, _ types.Log, f map[string]any) error {
		chainProductID := bigString(f["productId"])
		err := ix.store.UpsertProductStatus(ctx, db.UpsertProductStatusParams{
			ID:             cuid.New(),
			ChainProductID: chainProductID,
			OwnerAddress:   lowerAddress(f["actor"]),
			Status:         status,
			Now:            jsonx.Now(),
		})
		if err == nil {
			ix.log.Info("indexed status change", "productId", chainProductID, "status", status)
		}
		return err
	}
}

var chainStatuses = []db.ProductStatus{
	db.ProductStatusCreated,
	db.ProductStatusTransferred,
	db.ProductStatusAtPointOfSale,
	db.ProductStatusConsumed,
	db.ProductStatusInvalid,
}

// StatusFromChain maps the contract's ProductStatus enum (uint8) to the
// database enum, using fallback for unknown values.
func StatusFromChain(v any, fallback db.ProductStatus) db.ProductStatus {
	n, ok := v.(uint8)
	if !ok || int(n) >= len(chainStatuses) {
		return fallback
	}
	return chainStatuses[n]
}

func bigString(v any) string {
	if n, ok := v.(*big.Int); ok {
		return n.String()
	}
	return fmt.Sprint(v)
}

func lowerAddress(v any) string {
	if a, ok := v.(common.Address); ok {
		return strings.ToLower(a.Hex())
	}
	return strings.ToLower(fmt.Sprint(v))
}

func hash32(v any) string {
	if b, ok := v.([32]byte); ok {
		return hexutil.Encode(b[:])
	}
	return fmt.Sprint(v)
}
