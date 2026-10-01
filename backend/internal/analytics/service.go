// Package analytics keeps anonymous technical counters (no wallets or
// product ids).
package analytics

import (
	"context"
	"strconv"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

const snapshotNote = "Technical counters only; no wallets or product IDs are stored here."

type Store interface {
	IncrementAnalyticsCounter(ctx context.Context, arg db.IncrementAnalyticsCounterParams) (db.IncrementAnalyticsCounterRow, error)
	ListAnalyticsCounters(ctx context.Context) ([]db.ListAnalyticsCountersRow, error)
}

type Counter struct {
	Name  string `json:"name"`
	Value string `json:"value"`
}

type Skipped struct {
	Skipped bool `json:"skipped"`
}

type Snapshot struct {
	Counters []Counter `json:"counters"`
	Note     string    `json:"note"`
}

type Service struct {
	store   Store
	enabled bool
}

func NewService(st Store, enabled bool) *Service {
	return &Service{store: st, enabled: enabled}
}

// Increment bumps a counter and returns it, or Skipped when analytics are
// disabled.
func (s *Service) Increment(ctx context.Context, name string) (any, error) {
	if !s.enabled {
		return Skipped{Skipped: true}, nil
	}
	row, err := s.store.IncrementAnalyticsCounter(ctx, db.IncrementAnalyticsCounterParams{
		ID:   cuid.New(),
		Name: name,
		Now:  jsonx.Now(),
	})
	if err != nil {
		return nil, err
	}
	return Counter{Name: row.Name, Value: strconv.FormatInt(row.Value, 10)}, nil
}

func (s *Service) Snapshot(ctx context.Context) (Snapshot, error) {
	rows, err := s.store.ListAnalyticsCounters(ctx)
	if err != nil {
		return Snapshot{}, err
	}
	counters := make([]Counter, 0, len(rows))
	for _, r := range rows {
		counters = append(counters, Counter{Name: r.Name, Value: strconv.FormatInt(r.Value, 10)})
	}
	return Snapshot{Counters: counters, Note: snapshotNote}, nil
}
