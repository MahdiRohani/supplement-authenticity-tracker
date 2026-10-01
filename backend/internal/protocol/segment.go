package protocol

import (
	"context"
	"strings"

	"github.com/ethereum/go-ethereum/common"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// Segment is a contiguous range [start, end) of a batch's units held by one
// custodian. Shipping part of a segment splits it.
type Segment struct {
	SegmentID string `json:"segmentId"`
	BatchID   string `json:"batchId"`
	Owner     string `json:"owner"`
	Start     int32  `json:"start"`
	End       int32  `json:"end"`
	Units     int32  `json:"units"`
	Status    string `json:"status"`
	UpdatedAt string `json:"updatedAt"`
}

func toSegment(s db.Segment) Segment {
	return Segment{
		SegmentID: formatID(s.SegmentID),
		BatchID:   formatID(s.BatchID),
		Owner:     s.Owner,
		Start:     s.Start,
		End:       s.End,
		Units:     s.End - s.Start,
		Status:    string(s.Status),
		UpdatedAt: jsonx.ISOTime(s.UpdatedAt),
	}
}

type SegmentListQuery struct {
	Owner   string
	BatchID string
	Status  string
	Paging
}

func (s *Service) ListSegments(ctx context.Context, q SegmentListQuery) (Page[Segment], error) {
	page, limit, offset := q.resolve()
	var filter db.CountSegmentsParams
	if o := strings.TrimSpace(q.Owner); o != "" {
		filter.Owner = new(strings.ToLower(o))
	}
	if q.BatchID != "" {
		id, err := ParseID("batchId", q.BatchID)
		if err != nil {
			return Page[Segment]{}, err
		}
		filter.BatchID = &id
	}
	if q.Status != "" {
		status := db.SegmentStatus(q.Status)
		if !status.Valid() {
			return Page[Segment]{}, apperr.BadRequest("Unknown segment status: " + q.Status)
		}
		filter.Status = &status
	}
	total, err := s.store.CountSegments(ctx, filter)
	if err != nil {
		return Page[Segment]{}, err
	}
	rows, err := s.store.ListSegments(ctx, db.ListSegmentsParams{
		Owner: filter.Owner, BatchID: filter.BatchID, Status: filter.Status,
		PageLimit: int32(limit), PageOffset: int32(offset),
	})
	if err != nil {
		return Page[Segment]{}, err
	}
	items := make([]Segment, 0, len(rows))
	for _, r := range rows {
		items = append(items, toSegment(r))
	}
	return newPage(page, limit, total, items), nil
}

type SegmentDetail struct {
	Segment
	Batch Batch `json:"batch"`
}

func (s *Service) GetSegment(ctx context.Context, rawID string) (SegmentDetail, error) {
	id, err := ParseID("segmentId", rawID)
	if err != nil {
		return SegmentDetail{}, err
	}
	seg, err := s.segment(ctx, id)
	if err != nil {
		return SegmentDetail{}, err
	}
	batch, err := s.batch(ctx, seg.BatchID)
	if err != nil {
		return SegmentDetail{}, err
	}
	return SegmentDetail{Segment: toSegment(seg), Batch: s.toBatch(batch)}, nil
}

type TransferInput struct {
	SegmentID string
	ToAddress string
	// Count is how many units to ship from the front of the segment; 0 ships
	// the whole segment.
	Count int64
}

type TransferResult struct {
	BatchID       string `json:"batchId"`
	FromSegmentID string `json:"fromSegmentId"`
	ToSegmentID   string `json:"toSegmentId"`
	Split         bool   `json:"split"`
	From          string `json:"from"`
	To            string `json:"to"`
	Start         uint32 `json:"start"`
	End           uint32 `json:"end"`
	Status        string `json:"status"`
	TxHash        string `json:"txHash"`
	BlockNumber   uint64 `json:"blockNumber"`
}

func (s *Service) Transfer(ctx context.Context, in TransferInput) (TransferResult, error) {
	id, err := ParseID("segmentId", in.SegmentID)
	if err != nil {
		return TransferResult{}, err
	}
	to := strings.ToLower(strings.TrimSpace(in.ToAddress))
	if !common.IsHexAddress(to) {
		return TransferResult{}, apperr.BadRequest("toAddress must be a hex address")
	}
	seg, err := s.segment(ctx, id)
	if err != nil {
		return TransferResult{}, err
	}
	units := int64(seg.End - seg.Start)
	if in.Count < 0 || in.Count > units {
		return TransferResult{}, apperr.BadRequest("count must be between 0 (whole segment) and the segment's units")
	}
	if seg.Status == db.SegmentStatusInvalid {
		return TransferResult{}, apperr.Conflict("Segment has been recalled")
	}
	if strings.EqualFold(seg.Owner, to) {
		return TransferResult{}, apperr.BadRequest("toAddress already holds this segment")
	}
	count := in.Count
	if count == 0 {
		count = units
	}

	chainCtx, cancel := s.chainContext(ctx)
	defer cancel()
	event, err := s.registry.TransferSegment(chainCtx, seg.Owner, id, to, uint32(count))
	if err != nil {
		return TransferResult{}, err
	}
	s.project(ctx, "SegmentTransferred", func() error { return s.projector.SegmentTransferred(chainCtx, event) })

	result := TransferResult{
		BatchID:       formatID(event.BatchID),
		FromSegmentID: formatID(event.FromSegmentID),
		ToSegmentID:   formatID(event.ToSegmentID),
		Split:         event.Split(),
		From:          event.From,
		To:            event.To,
		Start:         event.Start,
		End:           event.End,
		Status:        string(event.Status),
		TxHash:        event.TxHash,
		BlockNumber:   event.BlockNumber,
	}
	s.record(chainCtx, audit.Entry{Action: "segment.transfer", EntityID: result.FromSegmentID, Actor: event.From, Detail: result})
	return result, nil
}

func (s *Service) segment(ctx context.Context, id int64) (db.Segment, error) {
	seg, err := s.store.GetSegment(ctx, id)
	if store.IsNotFound(err) {
		return db.Segment{}, apperr.NotFound("Segment " + formatID(id) + " not found")
	}
	return seg, err
}

// batchSnapshot is the cacheable, scan-independent state of a batch.
type batchSnapshot struct {
	batch    db.Batch
	segments []db.Segment
	parties  map[string]*Party
}

type Party struct {
	Address     string  `json:"address"`
	Role        *string `json:"role"`
	DisplayName *string `json:"displayName"`
	Region      *string `json:"region"`
}

func (s *Service) snapshot(ctx context.Context, batchID int64, fresh bool) (batchSnapshot, error) {
	key := formatID(batchID)
	if !fresh {
		if snap, ok := s.snapshots.Get(key); ok {
			return snap, nil
		}
	}
	batch, err := s.batch(ctx, batchID)
	if err != nil {
		return batchSnapshot{}, err
	}
	segments, err := s.store.ListBatchSegments(ctx, batchID)
	if err != nil {
		return batchSnapshot{}, err
	}
	parties := map[string]*Party{}
	for _, seg := range segments {
		if _, seen := parties[seg.Owner]; seen {
			continue
		}
		party := &Party{Address: seg.Owner}
		profile, err := s.store.GetPartyProfile(ctx, seg.Owner)
		if err == nil {
			party.Role = new(string(profile.Role))
			party.DisplayName, party.Region = profile.DisplayName, profile.Region
		} else if !store.IsNotFound(err) {
			return batchSnapshot{}, err
		}
		parties[seg.Owner] = party
	}
	snap := batchSnapshot{batch: batch, segments: segments, parties: parties}
	s.snapshots.Set(key, snap, s.opts.SnapshotTTL)
	return snap, nil
}

// segmentFor finds the segment holding index; segments never overlap.
func (b batchSnapshot) segmentFor(index uint32) (db.Segment, bool) {
	for _, seg := range b.segments {
		if int64(seg.Start) <= int64(index) && int64(index) < int64(seg.End) {
			return seg, true
		}
	}
	return db.Segment{}, false
}
