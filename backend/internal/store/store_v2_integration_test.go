package store_test

import (
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/risk"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

const (
	maker    = "0x00000000000000000000000000000000000000aa"
	shipper  = "0x00000000000000000000000000000000000000bb"
	pharmacy = "0x00000000000000000000000000000000000000cc"
	buyer    = "0x00000000000000000000000000000000000000dd"
)

func pos(block uint64, logIndex uint) chain.EventPosition {
	return chain.EventPosition{TxHash: fmt.Sprintf("0x%064x", block*1000+uint64(logIndex)), BlockNumber: block, LogIndex: logIndex}
}

// lifecycle is batch 1 of 10 units: units [0,4) go to the shipper as a new
// segment 2, the shipper hands them to the pharmacy, which sells unit 2.
func lifecycle() (chain.BatchRegistered, []chain.SegmentTransferred, chain.UnitConsumed) {
	reg := chain.BatchRegistered{
		EventPosition: pos(10, 0), BatchID: 1, SegmentID: 1, Manufacturer: maker, Size: 10,
		MerkleRoot: "0x01", PhysicalBatchID: "0x02", MetadataCID: "bafy", MetadataHash: "0x03",
	}
	transfers := []chain.SegmentTransferred{
		{EventPosition: pos(11, 0), BatchID: 1, FromSegmentID: 1, ToSegmentID: 2, From: maker, To: shipper, Start: 0, End: 4, Status: db.SegmentStatusTransferred},
		{EventPosition: pos(12, 1), BatchID: 1, FromSegmentID: 2, ToSegmentID: 2, From: shipper, To: pharmacy, Start: 0, End: 4, Status: db.SegmentStatusAtPointOfSale},
		{EventPosition: pos(13, 0), BatchID: 1, FromSegmentID: 1, ToSegmentID: 3, From: maker, To: shipper, Start: 4, End: 6, Status: db.SegmentStatusTransferred},
	}
	consumed := chain.UnitConsumed{EventPosition: pos(14, 0), BatchID: 1, Index: 2, SegmentID: 2, UnitKey: "0xkey2", Consumer: buyer}
	return reg, transfers, consumed
}

func applyAll(t *testing.T, ctx context.Context, p *chain.Projector, reg chain.BatchRegistered, transfers []chain.SegmentTransferred, consumed chain.UnitConsumed, order []int) {
	t.Helper()
	if _, err := p.BatchRegistered(ctx, reg, chain.BatchLabels{}); err != nil {
		t.Fatal(err)
	}
	for _, i := range order {
		if err := p.SegmentTransferred(ctx, transfers[i]); err != nil {
			t.Fatal(err)
		}
	}
	if err := p.UnitConsumed(ctx, consumed); err != nil {
		t.Fatal(err)
	}
}

func segmentsOf(t *testing.T, ctx context.Context, st *store.Store) map[int64]db.Segment {
	t.Helper()
	rows, err := st.ListBatchSegments(ctx, 1)
	if err != nil {
		t.Fatal(err)
	}
	out := map[int64]db.Segment{}
	for _, r := range rows {
		out[r.SegmentID] = r
	}
	return out
}

// The API projects its own receipts as they arrive, possibly ahead of the
// indexer, which later replays every log in chain order. Whatever the API
// applied first, the replay must converge on the chain state.
func TestV2ProjectionConvergesInAnyOrder(t *testing.T) {
	ctx := context.Background()
	reg, transfers, consumed := lifecycle()
	chainOrder := []int{0, 1, 2}
	apiOrders := map[string][]int{
		"nothing ahead":     {},
		"chain order":       {0, 1, 2},
		"later move first":  {1, 0, 2},
		"later split first": {2, 1, 0},
		"only the move":     {1},
	}
	for name, apiOrder := range apiOrders {
		t.Run(name, func(t *testing.T) {
			st := migrated(t)
			changed := map[int64]int{}
			p := chain.NewProjector(st, func(id int64) { changed[id]++ })
			label, lot := "Vitamin D", "LOT-7"
			if _, err := p.BatchRegistered(ctx, reg, chain.BatchLabels{Name: &label, LotCode: &lot}); err != nil {
				t.Fatal(err)
			}
			for _, i := range apiOrder {
				if err := p.SegmentTransferred(ctx, transfers[i]); err != nil {
					t.Fatal(err)
				}
			}
			if err := p.UnitConsumed(ctx, consumed); err != nil {
				t.Fatal(err)
			}
			applyAll(t, ctx, p, reg, transfers, consumed, chainOrder)

			segs := segmentsOf(t, ctx, st)
			want := map[int64]struct {
				owner      string
				start, end int32
				status     db.SegmentStatus
			}{
				1: {maker, 6, 10, db.SegmentStatusCreated},
				2: {pharmacy, 0, 4, db.SegmentStatusAtPointOfSale},
				3: {shipper, 4, 6, db.SegmentStatusTransferred},
			}
			if len(segs) != len(want) {
				t.Fatalf("segments = %+v", segs)
			}
			for id, w := range want {
				got, ok := segs[id]
				if !ok || got.Owner != w.owner || got.Start != w.start || got.End != w.end || got.Status != w.status {
					t.Fatalf("segment %d = %+v (present %v), want %+v", id, got, ok, w)
				}
			}

			batch, err := st.GetBatch(ctx, 1)
			if err != nil {
				t.Fatal(err)
			}
			if batch.ConsumedCount != 1 || batch.Name == nil || *batch.Name != "Vitamin D" || *batch.LotCode != "LOT-7" {
				t.Fatalf("batch = %+v", batch)
			}
			unit, err := st.GetUnit(ctx, db.GetUnitParams{BatchID: 1, Index: 2})
			if err != nil || unit.Consumer == nil || *unit.Consumer != buyer || unit.ConsumedAt == nil {
				t.Fatalf("unit = %+v, %v", unit, err)
			}
			history, err := st.ListUnitCustody(ctx, db.ListUnitCustodyParams{BatchID: 1, Index: 2})
			if err != nil || len(history) != 2 || history[0].ToAddress != shipper || history[1].ToAddress != pharmacy {
				t.Fatalf("custody = %+v, %v", history, err)
			}
			if changed[1] == 0 {
				t.Fatal("projector must report changed batches")
			}
		})
	}
}

func TestV2UnitsSegmentsAndRecall(t *testing.T) {
	st := migrated(t)
	ctx := context.Background()
	reg, transfers, consumed := lifecycle()
	p := chain.NewProjector(st, nil)
	applyAll(t, ctx, p, reg, transfers, consumed, []int{0, 1, 2})

	n, err := st.InsertUnits(ctx, db.InsertUnitsParams{
		BatchID:  1,
		Indexes:  []int32{0, 1, 2},
		UnitKeys: []string{"0xkey0", "0xkey1", "0xkey2"},
		Proofs:   [][]byte{{1}, {2}, {3}},
		Now:      time.Now().UTC(),
	})
	if err != nil || n != 3 {
		t.Fatalf("insert units = %d, %v", n, err)
	}
	consumedUnit, _ := st.GetUnit(ctx, db.GetUnitParams{BatchID: 1, Index: 2})
	if string(consumedUnit.Proof) != "\x03" || consumedUnit.ConsumedTxHash == nil {
		t.Fatalf("a unit consumed before its proof was stored keeps both: %+v", consumedUnit)
	}
	open, err := st.ListUnits(ctx, db.ListUnitsParams{BatchID: 1, Consumed: new(false), PageLimit: 10})
	if err != nil || len(open) != 2 {
		t.Fatalf("unconsumed units = %+v, %v", open, err)
	}

	seg, err := st.FindSegmentForUnit(ctx, db.FindSegmentForUnitParams{BatchID: 1, Index: 5})
	if err != nil || seg.SegmentID != 3 {
		t.Fatalf("segment for unit 5 = %+v, %v", seg, err)
	}
	if _, err := st.FindSegmentForUnit(ctx, db.FindSegmentForUnitParams{BatchID: 1, Index: 10}); !store.IsNotFound(err) {
		t.Fatalf("unit outside the batch: %v", err)
	}
	total, _ := st.CountSegments(ctx, db.CountSegmentsParams{Owner: new(shipper)})
	if total != 1 {
		t.Fatalf("shipper segments = %d", total)
	}

	if err := p.SegmentInvalidated(ctx, chain.SegmentInvalidated{EventPosition: pos(15, 0), SegmentID: 3, BatchID: 1}); err != nil {
		t.Fatal(err)
	}
	if err := p.BatchInvalidated(ctx, chain.BatchInvalidated{EventPosition: pos(16, 0), BatchID: 1}); err != nil {
		t.Fatal(err)
	}
	if s, _ := st.GetSegment(ctx, 3); s.Status != db.SegmentStatusInvalid {
		t.Fatalf("segment 3 = %+v", s)
	}
	if b, _ := st.GetBatch(ctx, 1); !b.Invalid {
		t.Fatal("batch should be recalled")
	}

	if err := st.SetIndexerCursor(ctx, db.SetIndexerCursorParams{Name: "c", LastBlock: 16, BlockHash: "0xh", Now: time.Now().UTC()}); err != nil {
		t.Fatal(err)
	}
	if err := st.ResetV2Projection(ctx, "c"); err != nil {
		t.Fatal(err)
	}
	if _, err := st.GetIndexerCursor(ctx, "c"); !store.IsNotFound(err) {
		t.Fatalf("cursor after reset: %v", err)
	}
	if count, _ := st.CountBatches(ctx, nil); count != 0 {
		t.Fatalf("batches after reset = %d", count)
	}
}

func TestV2ScanSignalsMatchRiskFromHistory(t *testing.T) {
	st := migrated(t)
	ctx := context.Background()
	base := time.Date(2026, 10, 1, 8, 0, 0, 0, time.UTC)
	consumedAt := base.Add(30 * time.Minute)
	scans := []risk.Scan{
		{Device: "pharmacist", Region: "tehran", At: base},
		{Device: "buyer", Region: "tehran", At: base.Add(10 * time.Minute)},
		{Device: "buyer", At: consumedAt},
		{Device: "buyer", Region: "tehran", At: base.Add(40 * time.Minute)},
		{Device: "clone-1", Region: "mashhad", At: base.Add(50 * time.Minute)},
		{Device: "clone-2", Region: "shiraz", At: base.Add(60 * time.Minute)},
		{Device: "clone-2", Region: "shiraz", At: base.Add(61 * time.Minute)},
	}
	for _, s := range scans {
		var region *string
		if s.Region != "" {
			region = new(s.Region)
		}
		err := st.InsertScanEvent(ctx, db.InsertScanEventParams{
			ID: cuid.New(), BatchID: 1, Index: 2, DeviceHash: s.Device, Region: region,
			StatusAtScan: "Authentic", RiskScore: 0.7, Now: s.At,
		})
		if err != nil {
			t.Fatal(err)
		}
	}
	// Scans of another unit must not leak into the aggregate.
	_ = st.InsertScanEvent(ctx, db.InsertScanEventParams{ID: cuid.New(), BatchID: 1, Index: 3, DeviceHash: "x", StatusAtScan: "Authentic", Now: base})

	for _, tc := range []struct {
		name     string
		consumed *time.Time
		region   string
	}{
		{"consumed with owner region", &consumedAt, "tehran"},
		{"not consumed, no region", nil, ""},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var ownerRegion *string
			if tc.region != "" {
				ownerRegion = new(tc.region)
			}
			row, err := st.UnitScanSignals(ctx, db.UnitScanSignalsParams{BatchID: 1, Index: 2, ConsumedAt: tc.consumed, OwnerRegion: ownerRegion})
			if err != nil {
				t.Fatal(err)
			}
			want := risk.FromHistory(scans, tc.consumed, tc.region)
			got := risk.Signals{
				TotalScans:             int(row.TotalScans),
				DevicesBeforeConsume:   int(row.DevicesBeforeConsume),
				ScansAfterConsume:      int(row.ScansAfterConsume),
				NewDevicesAfterConsume: int(row.NewDevicesAfterConsume),
				DistinctRegions:        int(row.DistinctRegions),
				ForeignRegions:         int(row.ForeignRegions),
				OwnerRegionKnown:       want.OwnerRegionKnown,
				Consumed:               want.Consumed,
			}
			if got != want {
				t.Fatalf("sql %+v != memory %+v", got, want)
			}
		})
	}

	suspicious, err := st.ListSuspiciousUnits(ctx, db.ListSuspiciousUnitsParams{Since: base.Add(-time.Hour), MinRisk: 0.6, MaxRows: 10})
	if err != nil || len(suspicious) != 1 || suspicious[0].Index != 2 || suspicious[0].Devices != 4 {
		t.Fatalf("suspicious = %+v, %v", suspicious, err)
	}
}
