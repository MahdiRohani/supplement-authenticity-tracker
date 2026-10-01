package chain

import (
	"cmp"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"math/big"
	"slices"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum"
	"github.com/ethereum/go-ethereum/common"
	"github.com/ethereum/go-ethereum/core/types"
	"github.com/jackc/pgx/v5"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// CursorStore persists how far the v2 indexer got.
type CursorStore interface {
	GetIndexerCursor(ctx context.Context, name string) (db.IndexerCursor, error)
	SetIndexerCursor(ctx context.Context, arg db.SetIndexerCursorParams) error
	ResetV2Projection(ctx context.Context, cursor string) error
}

type IndexerV2Options struct {
	// StartBlock is where an empty projection starts (the deploy block).
	StartBlock uint64
	// Confirmations keeps the indexer this many blocks behind the head.
	Confirmations uint64
	// ChunkSize bounds each eth_getLogs range; providers reject large ones.
	ChunkSize uint64
	// AllowReset rebuilds the projection when the indexed chain disappears
	// (a restarted dev node or a deep reorg). Otherwise indexing stops.
	AllowReset bool
	Interval   time.Duration
}

// IndexerV2 projects SupplementRegistryV2 events into Postgres. Unlike the
// v1 indexer it backfills from the deploy block, resumes from a persisted
// cursor, and checks the cursor's block hash to detect reorgs and resets.
type IndexerV2 struct {
	backend   Backend
	contract  *Contract
	projector *Projector
	cursors   CursorStore
	log       *slog.Logger
	opts      IndexerV2Options
	name      string

	loaded   bool
	next     uint64
	halted   error
	topics   []common.Hash
	handlers map[common.Hash]func(context.Context, types.Log, map[string]any) error
}

func NewIndexerV2(backend Backend, contract *Contract, projector *Projector, cursors CursorStore, log *slog.Logger, opts IndexerV2Options) *IndexerV2 {
	if opts.ChunkSize == 0 {
		opts.ChunkSize = 2000
	}
	if opts.Interval == 0 {
		opts.Interval = 3 * time.Second
	}
	ix := &IndexerV2{
		backend:   backend,
		contract:  contract,
		projector: projector,
		cursors:   cursors,
		log:       log.With("component", "indexer-v2"),
		opts:      opts,
		name:      "registry-v2:" + strings.ToLower(contract.Address().Hex()),
		handlers:  map[common.Hash]func(context.Context, types.Log, map[string]any) error{},
	}
	register := func(event string, h func(context.Context, types.Log, map[string]any) error) {
		id := contract.EventID(event)
		ix.topics = append(ix.topics, id)
		ix.handlers[id] = h
	}
	register("BatchRegistered", func(ctx context.Context, l types.Log, f map[string]any) error {
		e, err := parseBatchRegistered(l, f)
		if err != nil {
			return err
		}
		_, err = projector.BatchRegistered(ctx, e, BatchLabels{})
		return err
	})
	register("SegmentTransferred", func(ctx context.Context, l types.Log, f map[string]any) error {
		e, err := parseSegmentTransferred(l, f)
		if err != nil {
			return err
		}
		return projector.SegmentTransferred(ctx, e)
	})
	register("UnitConsumed", func(ctx context.Context, l types.Log, f map[string]any) error {
		e, err := parseUnitConsumed(l, f)
		if err != nil {
			return err
		}
		header, err := backend.HeaderByNumber(ctx, new(big.Int).SetUint64(l.BlockNumber))
		if err != nil {
			return fmt.Errorf("header %d: %w", l.BlockNumber, err)
		}
		e.At = time.Unix(int64(header.Time), 0)
		return projector.UnitConsumed(ctx, e)
	})
	register("BatchInvalidated", func(ctx context.Context, l types.Log, f map[string]any) error {
		e, err := parseBatchInvalidated(l, f)
		if err != nil {
			return err
		}
		return projector.BatchInvalidated(ctx, e)
	})
	register("SegmentInvalidated", func(ctx context.Context, l types.Log, f map[string]any) error {
		e, err := parseSegmentInvalidated(l, f)
		if err != nil {
			return err
		}
		return projector.SegmentInvalidated(ctx, e)
	})
	return ix
}

func (ix *IndexerV2) Run(ctx context.Context) {
	ix.log.Info("indexer listening", "registry", ix.contract.Address().Hex(), "startBlock", ix.opts.StartBlock)
	ticker := time.NewTicker(ix.opts.Interval)
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

var errIndexerHalted = errors.New("indexer halted")

// Poll indexes every confirmed block after the cursor, in chunks, saving the
// cursor after each chunk.
func (ix *IndexerV2) Poll(ctx context.Context) error {
	if ix.halted != nil {
		return fmt.Errorf("%w: %v", errIndexerHalted, ix.halted)
	}
	latest, err := ix.backend.BlockNumber(ctx)
	if err != nil {
		return fmt.Errorf("block number: %w", err)
	}
	if !ix.loaded {
		if err := ix.loadCursor(ctx, latest); err != nil {
			return err
		}
	}
	if latest < ix.opts.Confirmations {
		return nil
	}
	head := latest - ix.opts.Confirmations
	for ix.next <= head {
		to := min(head, ix.next+ix.opts.ChunkSize-1)
		if err := ix.indexRange(ctx, ix.next, to); err != nil {
			return err
		}
		header, err := ix.backend.HeaderByNumber(ctx, new(big.Int).SetUint64(to))
		if err != nil {
			return fmt.Errorf("header %d: %w", to, err)
		}
		err = ix.cursors.SetIndexerCursor(ctx, db.SetIndexerCursorParams{
			Name:      ix.name,
			LastBlock: int64(to),
			BlockHash: header.Hash().Hex(),
			Now:       jsonx.Now(),
		})
		if err != nil {
			return fmt.Errorf("save cursor: %w", err)
		}
		ix.next = to + 1
	}
	return nil
}

// loadCursor resumes after the saved block when that block is still part of
// the chain; otherwise the projection is stale.
func (ix *IndexerV2) loadCursor(ctx context.Context, latest uint64) error {
	cursor, err := ix.cursors.GetIndexerCursor(ctx, ix.name)
	switch {
	case errors.Is(err, pgx.ErrNoRows):
		ix.next = ix.opts.StartBlock
		ix.loaded = true
		return nil
	case err != nil:
		return fmt.Errorf("load cursor: %w", err)
	}

	last := uint64(cursor.LastBlock)
	stale := last > latest
	if !stale {
		header, err := ix.backend.HeaderByNumber(ctx, new(big.Int).SetUint64(last))
		if err != nil && !errors.Is(err, ethereum.NotFound) {
			return fmt.Errorf("header %d: %w", last, err)
		}
		stale = err != nil || !strings.EqualFold(header.Hash().Hex(), cursor.BlockHash)
	}
	if !stale {
		ix.next = last + 1
		ix.loaded = true
		return nil
	}
	if !ix.opts.AllowReset {
		ix.halted = fmt.Errorf("block %d (%s) is no longer on the chain; reset the v2 projection to reindex", last, cursor.BlockHash)
		ix.log.Error("indexed chain changed; refusing to continue", "cursorBlock", last, "latest", latest)
		return ix.halted
	}
	ix.log.Warn("indexed chain changed; rebuilding v2 projection", "cursorBlock", last, "latest", latest)
	if err := ix.cursors.ResetV2Projection(ctx, ix.name); err != nil {
		return err
	}
	ix.next = ix.opts.StartBlock
	ix.loaded = true
	return nil
}

func (ix *IndexerV2) indexRange(ctx context.Context, from, to uint64) error {
	logs, err := ix.backend.FilterLogs(ctx, ethereum.FilterQuery{
		FromBlock: new(big.Int).SetUint64(from),
		ToBlock:   new(big.Int).SetUint64(to),
		Addresses: []common.Address{ix.contract.Address()},
		Topics:    [][]common.Hash{ix.topics},
	})
	if err != nil {
		return fmt.Errorf("filter logs %d-%d: %w", from, to, err)
	}
	slices.SortFunc(logs, func(a, b types.Log) int {
		if c := cmp.Compare(a.BlockNumber, b.BlockNumber); c != 0 {
			return c
		}
		return cmp.Compare(a.Index, b.Index)
	})
	for _, l := range logs {
		if l.Removed || len(l.Topics) == 0 {
			continue
		}
		handle, ok := ix.handlers[l.Topics[0]]
		if !ok {
			continue
		}
		var event string
		for name, e := range ix.contract.ABI().Events {
			if e.ID == l.Topics[0] {
				event = name
				break
			}
		}
		fields, _, err := ix.contract.ParseEvent(event, l)
		if err != nil {
			return fmt.Errorf("block %d log %d: %w", l.BlockNumber, l.Index, err)
		}
		if err := handle(ctx, l, fields); err != nil {
			return fmt.Errorf("block %d log %d %s: %w", l.BlockNumber, l.Index, event, err)
		}
	}
	if len(logs) > 0 {
		ix.log.Info("indexed range", "from", from, "to", to, "logs", len(logs))
	}
	return nil
}
