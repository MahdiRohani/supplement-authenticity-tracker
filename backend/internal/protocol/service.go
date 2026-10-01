// Package protocol implements the unit-level v2 protocol on top of
// SupplementRegistryV2: Merkle-committed batches with one key pair per unit,
// splittable custody segments, gasless consumption authorized by the unit
// key, public verification with clone detection, and two-layer labels.
package protocol

import (
	"context"
	"encoding/json"
	"log/slog"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cache"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ipfs"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/risk"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type Store interface {
	chain.ProjectionStore
	GetBatch(ctx context.Context, batchID int64) (db.Batch, error)
	CountBatches(ctx context.Context, manufacturer *string) (int64, error)
	ListBatches(ctx context.Context, arg db.ListBatchesParams) ([]db.Batch, error)
	GetSegment(ctx context.Context, segmentID int64) (db.Segment, error)
	FindSegmentForUnit(ctx context.Context, arg db.FindSegmentForUnitParams) (db.Segment, error)
	ListBatchSegments(ctx context.Context, batchID int64) ([]db.Segment, error)
	CountSegments(ctx context.Context, arg db.CountSegmentsParams) (int64, error)
	ListSegments(ctx context.Context, arg db.ListSegmentsParams) ([]db.Segment, error)
	ListUnitCustody(ctx context.Context, arg db.ListUnitCustodyParams) ([]db.CustodyEvent, error)
	InsertUnits(ctx context.Context, arg db.InsertUnitsParams) (int64, error)
	GetUnit(ctx context.Context, arg db.GetUnitParams) (db.Unit, error)
	InsertScanEvent(ctx context.Context, arg db.InsertScanEventParams) error
	SetScanRisk(ctx context.Context, arg db.SetScanRiskParams) error
	UnitScanSignals(ctx context.Context, arg db.UnitScanSignalsParams) (db.UnitScanSignalsRow, error)
	ListSuspiciousUnits(ctx context.Context, arg db.ListSuspiciousUnitsParams) ([]db.ListSuspiciousUnitsRow, error)
	GetPartyProfile(ctx context.Context, address string) (db.GetPartyProfileRow, error)
}

// Registry is the SupplementRegistryV2 relayer.
type Registry interface {
	RegisterBatch(ctx context.Context, in chain.RegisterBatchV2Input) (chain.BatchRegistered, error)
	TransferSegment(ctx context.Context, owner string, segmentID int64, to string, count uint32) (chain.SegmentTransferred, error)
	Consume(ctx context.Context, in chain.ConsumeV2Input) (chain.UnitConsumed, error)
	InvalidateBatch(ctx context.Context, actors []string, batchID int64) (chain.BatchInvalidated, error)
	InvalidateSegment(ctx context.Context, actors []string, segmentID int64) (chain.SegmentInvalidated, error)
}

type Pinner interface {
	PinJSON(ctx context.Context, body []byte) (ipfs.Pinned, error)
	ResolveJSON(ctx context.Context, cid string) json.RawMessage
	GatewayURL(cid string) string
}

type Auditor interface {
	Record(ctx context.Context, e audit.Entry) error
}

type Options struct {
	ChainID int64
	// PublicBaseURL prefixes the public label QR (no trailing slash).
	PublicBaseURL string
	MaxBatchUnits int
	// ScanSalt keys the device hashes stored with scan events.
	ScanSalt            string
	DefaultManufacturer string
	Risk                risk.Config
	// SnapshotTTL bounds how stale cached batch state may be; projected
	// changes drop it immediately.
	SnapshotTTL time.Duration
}

type Service struct {
	store     Store
	registry  Registry
	projector *chain.Projector
	pinner    Pinner
	audit     Auditor
	eip712    *chain.EIP712
	log       *slog.Logger
	opts      Options

	snapshots    *cache.TTL[batchSnapshot]
	chainTimeout time.Duration
	now          func() time.Time
}

// NewService wires the v2 services. eip712 must use the v2 domain of the
// same registry the relayer writes to.
func NewService(st Store, registry Registry, pinner Pinner, auditor Auditor, eip712 *chain.EIP712, log *slog.Logger, opts Options) *Service {
	if opts.MaxBatchUnits <= 0 {
		opts.MaxBatchUnits = 5000
	}
	if opts.Risk == (risk.Config{}) {
		opts.Risk = risk.DefaultConfig()
	}
	if opts.SnapshotTTL <= 0 {
		opts.SnapshotTTL = 5 * time.Second
	}
	opts.PublicBaseURL = strings.TrimRight(opts.PublicBaseURL, "/")
	s := &Service{
		store:        st,
		registry:     registry,
		pinner:       pinner,
		audit:        auditor,
		eip712:       eip712,
		log:          log,
		opts:         opts,
		snapshots:    cache.NewTTL[batchSnapshot](),
		chainTimeout: 2 * time.Minute,
		now:          time.Now,
	}
	s.projector = chain.NewProjector(st, s.Invalidate)
	return s
}

// Projector applies chain events to the store and drops cached state of the
// batches they touch; the indexer must use it so caches stay coherent.
func (s *Service) Projector() *chain.Projector { return s.projector }

// Invalidate drops cached state of a batch.
func (s *Service) Invalidate(batchID int64) {
	s.snapshots.Delete(strconv.FormatInt(batchID, 10))
}

// chainContext detaches on-chain writes from the HTTP request: once a
// transaction is sent, a client disconnect must not skip the bookkeeping.
func (s *Service) chainContext(ctx context.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.WithoutCancel(ctx), s.chainTimeout)
}

// project applies an event the API itself caused. Failures are logged, not
// returned: the transaction is final and the indexer re-applies the event.
func (s *Service) project(ctx context.Context, what string, apply func() error) {
	if err := apply(); err != nil {
		s.log.WarnContext(ctx, "v2 projection deferred to indexer", "event", what, "err", err)
	}
}

func (s *Service) record(ctx context.Context, e audit.Entry) {
	if err := s.audit.Record(ctx, e); err != nil {
		s.log.ErrorContext(ctx, "audit record failed", "action", e.Action, "err", err)
	}
}

// ParseID parses a positive decimal registry id (batch or segment).
func ParseID(name, raw string) (int64, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > 18 || strings.TrimLeft(raw, "0123456789") != "" {
		return 0, apperr.BadRequest(name + " must be a positive integer")
	}
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 {
		return 0, apperr.BadRequest(name + " must be a positive integer")
	}
	return id, nil
}

// ParseIndex parses a unit index.
func ParseIndex(raw string) (uint32, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > 10 || strings.TrimLeft(raw, "0123456789") != "" {
		return 0, apperr.BadRequest("index must be a non-negative integer")
	}
	n, err := strconv.ParseUint(raw, 10, 32)
	if err != nil {
		return 0, apperr.BadRequest("index must be a non-negative integer")
	}
	return uint32(n), nil
}

func formatID(id int64) string { return strconv.FormatInt(id, 10) }

type Paging struct {
	// Page and Limit are NaN when absent or unparseable.
	Page  float64
	Limit float64
}

func (p Paging) resolve() (page, limit, offset int64) {
	page = clampInt(p.Page, 1, 1, math.MaxInt32)
	limit = clampInt(p.Limit, 20, 1, 100)
	offset = min((page-1)*limit, math.MaxInt32)
	return page, limit, offset
}

type Page[T any] struct {
	Page       int64 `json:"page"`
	Limit      int64 `json:"limit"`
	Total      int64 `json:"total"`
	TotalPages int64 `json:"totalPages"`
	Items      []T   `json:"items"`
}

func newPage[T any](page, limit, total int64, items []T) Page[T] {
	return Page[T]{Page: page, Limit: limit, Total: total, TotalPages: max(1, (total+limit-1)/limit), Items: items}
}

func clampInt(v float64, fallback, lo, hi int64) int64 {
	if math.IsNaN(v) || math.IsInf(v, 0) {
		return fallback
	}
	return int64(math.Floor(math.Max(float64(lo), math.Min(float64(hi), v))))
}

func positiveOrNaN(v float64) float64 {
	if v > 0 {
		return v
	}
	return math.NaN()
}

func lowerOr(s *string, fallback string) string {
	if s == nil || strings.TrimSpace(*s) == "" {
		return strings.ToLower(fallback)
	}
	return strings.ToLower(strings.TrimSpace(*s))
}
