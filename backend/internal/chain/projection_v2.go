package chain

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// ProjectionStore is the Postgres projection of SupplementRegistryV2.
type ProjectionStore interface {
	UpsertBatch(ctx context.Context, arg db.UpsertBatchParams) (db.Batch, error)
	SetBatchInvalid(ctx context.Context, arg db.SetBatchInvalidParams) (int64, error)
	PutSegment(ctx context.Context, arg db.PutSegmentParams) error
	MoveSegment(ctx context.Context, arg db.MoveSegmentParams) (int64, error)
	ShrinkSegment(ctx context.Context, arg db.ShrinkSegmentParams) (int64, error)
	InvalidateSegment(ctx context.Context, arg db.InvalidateSegmentParams) (int64, error)
	InsertCustodyEvent(ctx context.Context, arg db.InsertCustodyEventParams) error
	MarkUnitConsumed(ctx context.Context, arg db.MarkUnitConsumedParams) (int64, error)
}

// Projector applies v2 events to the projection. The API applies the events
// of its own receipts immediately and the indexer applies every log later;
// each apply is idempotent and ordered by event position, so the two paths
// converge on the same state.
type Projector struct {
	store ProjectionStore
	// onChange is told which batch changed, e.g. to drop cached verify results.
	onChange func(batchID int64)
}

func NewProjector(store ProjectionStore, onChange func(batchID int64)) *Projector {
	if onChange == nil {
		onChange = func(int64) {}
	}
	return &Projector{store: store, onChange: onChange}
}

// BatchLabels are the off-chain names only the registering API knows.
type BatchLabels struct {
	Name    *string
	LotCode *string
}

func (p *Projector) BatchRegistered(ctx context.Context, e BatchRegistered, labels BatchLabels) (db.Batch, error) {
	now := jsonx.Now()
	block, logIndex, err := position(e.EventPosition)
	if err != nil {
		return db.Batch{}, err
	}
	batch, err := p.store.UpsertBatch(ctx, db.UpsertBatchParams{
		BatchID:         e.BatchID,
		Manufacturer:    e.Manufacturer,
		Size:            int32(e.Size),
		MerkleRoot:      e.MerkleRoot,
		PhysicalBatchID: e.PhysicalBatchID,
		MetadataCID:     e.MetadataCID,
		MetadataHash:    e.MetadataHash,
		Name:            labels.Name,
		LotCode:         labels.LotCode,
		TxHash:          e.TxHash,
		BlockNumber:     block,
		Now:             now,
	})
	if err != nil {
		return db.Batch{}, err
	}
	err = p.store.PutSegment(ctx, db.PutSegmentParams{
		SegmentID:   e.SegmentID,
		BatchID:     e.BatchID,
		Owner:       e.Manufacturer,
		RangeStart:  0,
		RangeEnd:    int32(e.Size),
		Status:      db.SegmentStatusCreated,
		BlockNumber: block,
		LogIndex:    logIndex,
		Now:         now,
	})
	if err != nil {
		return db.Batch{}, err
	}
	p.onChange(e.BatchID)
	return batch, nil
}

func (p *Projector) SegmentTransferred(ctx context.Context, e SegmentTransferred) error {
	now := jsonx.Now()
	block, logIndex, err := position(e.EventPosition)
	if err != nil {
		return err
	}
	if e.Split() {
		err = p.store.PutSegment(ctx, db.PutSegmentParams{
			SegmentID:   e.ToSegmentID,
			BatchID:     e.BatchID,
			Owner:       e.To,
			RangeStart:  int32(e.Start),
			RangeEnd:    int32(e.End),
			Status:      e.Status,
			BlockNumber: block,
			LogIndex:    logIndex,
			Now:         now,
		})
		if err == nil {
			_, err = p.store.ShrinkSegment(ctx, db.ShrinkSegmentParams{
				SegmentID:  e.FromSegmentID,
				RangeStart: int32(e.End),
				Now:        now,
			})
		}
	} else {
		_, err = p.store.MoveSegment(ctx, db.MoveSegmentParams{
			SegmentID:   e.FromSegmentID,
			Owner:       e.To,
			Status:      e.Status,
			BlockNumber: block,
			LogIndex:    logIndex,
			Now:         now,
		})
	}
	if err != nil {
		return err
	}
	err = p.store.InsertCustodyEvent(ctx, db.InsertCustodyEventParams{
		ID:            cuid.New(),
		BatchID:       e.BatchID,
		FromSegmentID: e.FromSegmentID,
		ToSegmentID:   e.ToSegmentID,
		FromAddress:   e.From,
		ToAddress:     e.To,
		RangeStart:    int32(e.Start),
		RangeEnd:      int32(e.End),
		Status:        e.Status,
		TxHash:        e.TxHash,
		LogIndex:      logIndex,
		BlockNumber:   block,
		Now:           now,
	})
	if err != nil {
		return err
	}
	p.onChange(e.BatchID)
	return nil
}

func (p *Projector) UnitConsumed(ctx context.Context, e UnitConsumed) error {
	block, _, err := position(e.EventPosition)
	if err != nil {
		return err
	}
	consumer, txHash := e.Consumer, e.TxHash
	at := jsonx.Now()
	if !e.At.IsZero() {
		at = e.At.UTC().Truncate(time.Millisecond)
	}
	_, err = p.store.MarkUnitConsumed(ctx, db.MarkUnitConsumedParams{
		BatchID:     e.BatchID,
		Index:       int32(e.Index),
		UnitKey:     e.UnitKey,
		Consumer:    &consumer,
		TxHash:      &txHash,
		BlockNumber: &block,
		Now:         at,
	})
	if err != nil {
		return err
	}
	p.onChange(e.BatchID)
	return nil
}

func (p *Projector) BatchInvalidated(ctx context.Context, e BatchInvalidated) error {
	if _, err := p.store.SetBatchInvalid(ctx, db.SetBatchInvalidParams{BatchID: e.BatchID, Now: jsonx.Now()}); err != nil {
		return err
	}
	p.onChange(e.BatchID)
	return nil
}

func (p *Projector) SegmentInvalidated(ctx context.Context, e SegmentInvalidated) error {
	block, logIndex, err := position(e.EventPosition)
	if err != nil {
		return err
	}
	_, err = p.store.InvalidateSegment(ctx, db.InvalidateSegmentParams{
		SegmentID:   e.SegmentID,
		BlockNumber: block,
		LogIndex:    logIndex,
		Now:         jsonx.Now(),
	})
	if err != nil {
		return err
	}
	p.onChange(e.BatchID)
	return nil
}

func position(p EventPosition) (int64, int32, error) {
	if p.BlockNumber > 1<<62 || p.LogIndex > 1<<30 {
		return 0, 0, fmt.Errorf("event position %d/%d out of range", p.BlockNumber, p.LogIndex)
	}
	return int64(p.BlockNumber), int32(p.LogIndex), nil
}

// FormatID renders a chain id the way the API returns it.
func FormatID(id int64) string { return strconv.FormatInt(id, 10) }
