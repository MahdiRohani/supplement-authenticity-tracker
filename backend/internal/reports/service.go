// Package reports stores counterfeit reports submitted by consumers.
package reports

import (
	"context"
	"math"
	"strings"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cuid"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type Store interface {
	CreateCounterfeitReport(ctx context.Context, arg db.CreateCounterfeitReportParams) (db.CounterfeitReport, error)
	ListCounterfeitReports(ctx context.Context, maxRows int32) ([]db.CounterfeitReport, error)
}

type Auditor interface {
	Record(ctx context.Context, e audit.Entry) error
}

type Input struct {
	ChainProductID string
	Note           *string
	Reporter       *string
}

type Created struct {
	ID             string `json:"id"`
	ChainProductID string `json:"chainProductId"`
	CreatedAt      string `json:"createdAt"`
}

type Item struct {
	ID             string  `json:"id"`
	ChainProductID string  `json:"chainProductId"`
	Note           *string `json:"note"`
	Reporter       *string `json:"reporter"`
	CreatedAt      string  `json:"createdAt"`
}

type List struct {
	Items []Item `json:"items"`
}

type Service struct {
	store   Store
	audit   Auditor
	enabled bool
}

func NewService(st Store, auditor Auditor, enabled bool) *Service {
	return &Service{store: st, audit: auditor, enabled: enabled}
}

func (s *Service) Report(ctx context.Context, in Input) (Created, error) {
	if !s.enabled {
		return Created{}, apperr.ServiceUnavailable("Counterfeit reports are disabled")
	}
	chainProductID := strings.TrimSpace(in.ChainProductID)
	if chainProductID == "" {
		return Created{}, apperr.BadRequest("chainProductId is required")
	}
	report, err := s.store.CreateCounterfeitReport(ctx, db.CreateCounterfeitReportParams{
		ID:             cuid.New(),
		ChainProductID: chainProductID,
		Note:           trimmedOrNil(in.Note, false),
		Reporter:       trimmedOrNil(in.Reporter, true),
		Now:            jsonx.Now(),
	})
	if err != nil {
		return Created{}, err
	}
	actor := ""
	if report.Reporter != nil {
		actor = *report.Reporter
	}
	if err := s.audit.Record(ctx, audit.Entry{
		Action:   "report.counterfeit",
		EntityID: report.ID,
		Actor:    actor,
		Detail: struct {
			ChainProductID string `json:"chainProductId"`
		}{report.ChainProductID},
	}); err != nil {
		return Created{}, err
	}
	return Created{
		ID:             report.ID,
		ChainProductID: report.ChainProductID,
		CreatedAt:      jsonx.ISOTime(report.CreatedAt),
	}, nil
}

// List returns the newest reports; limit is NaN when absent or unparseable.
func (s *Service) List(ctx context.Context, limit float64) (List, error) {
	take := int32(50)
	if !math.IsNaN(limit) && !math.IsInf(limit, 0) {
		take = int32(math.Floor(math.Max(1, math.Min(100, limit))))
	}
	rows, err := s.store.ListCounterfeitReports(ctx, take)
	if err != nil {
		return List{}, err
	}
	items := make([]Item, 0, len(rows))
	for _, r := range rows {
		items = append(items, Item{
			ID:             r.ID,
			ChainProductID: r.ChainProductID,
			Note:           r.Note,
			Reporter:       r.Reporter,
			CreatedAt:      jsonx.ISOTime(r.CreatedAt),
		})
	}
	return List{Items: items}, nil
}

func trimmedOrNil(s *string, lower bool) *string {
	if s == nil {
		return nil
	}
	v := strings.TrimSpace(*s)
	if lower {
		v = strings.ToLower(v)
	}
	if v == "" {
		return nil
	}
	return &v
}
