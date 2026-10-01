// Package httpapi exposes the REST API. Under /v1 its wire format (paths,
// status codes, JSON shapes and error bodies) is the contract the Android app
// and admin panel were built against; /v2 serves the unit-level protocol of
// SupplementRegistryV2 with strictly decoded bodies.
package httpapi

import (
	"context"
	"log/slog"
	"net/http"

	"github.com/ethereum/go-ethereum/common"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/analytics"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/config"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/product"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ratelimit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/reports"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/roles"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/verify"
)

type Pinger interface {
	Ping(ctx context.Context) error
}

type ProductService interface {
	List(ctx context.Context, q product.ListQuery) (product.Page, error)
	LabelsPDF(ctx context.Context, batchCode string) ([]byte, error)
	Register(ctx context.Context, in product.RegisterInput) (product.Registered, error)
	RegisterBatch(ctx context.Context, in product.RegisterBatchInput) (product.RegisteredBatch, error)
	Transfer(ctx context.Context, id, toAddress string) (product.TransferResult, error)
	Consume(ctx context.Context, id, secret string) (product.ConsumeResult, error)
	History(ctx context.Context, id string) (product.History, error)
	ByChainID(ctx context.Context, chainProductID string) (product.Detail, error)
}

type VerifyService interface {
	Verify(ctx context.Context, id string) (verify.Result, error)
}

type RoleService interface {
	List(ctx context.Context) ([]roles.Binding, error)
	ListForAddress(ctx context.Context, address string) ([]roles.Binding, error)
	Bind(ctx context.Context, address, role string) (roles.Binding, error)
	Unbind(ctx context.Context, address, role string) (roles.Deleted, error)
}

type ReportService interface {
	Report(ctx context.Context, in reports.Input) (reports.Created, error)
	List(ctx context.Context, limit float64) (reports.List, error)
}

type AnalyticsService interface {
	Increment(ctx context.Context, name string) (any, error)
	Snapshot(ctx context.Context) (analytics.Snapshot, error)
}

type Signer interface {
	SignManufacturerMetadata(privateKey string, m chain.ManufacturerMetadata) (string, error)
	RecoverManufacturerMetadata(signature string, m chain.ManufacturerMetadata) (common.Address, error)
	RecoverConsumeAuthorization(signature string, a chain.ConsumeAuthorization) (common.Address, error)
}

// Deps are the collaborators the HTTP layer needs.
type Deps struct {
	Log       *slog.Logger
	Config    config.Config
	DB        Pinger
	Network   *chain.Network
	Products  ProductService
	Verify    VerifyService
	Roles     RoleService
	Reports   ReportService
	Analytics AnalyticsService
	EIP712    Signer
	// Protocol and Parties serve /v2; nil answers its routes with 503.
	Protocol ProtocolService
	Parties  PartyService
	// ReloadKeys re-reads relayer keys and returns the active addresses.
	ReloadKeys func(ctx context.Context) ([]string, error)
	Limiter    *ratelimit.Limiter
}

type Server struct {
	Deps
	log     *slog.Logger
	cfg     config.Config
	limiter *ratelimit.Limiter
	routes  []*route
}

func New(d Deps) http.Handler {
	s := &Server{Deps: d, log: d.Log, cfg: d.Config, limiter: d.Limiter}
	if s.limiter == nil {
		s.limiter = ratelimit.New()
	}
	s.registerRoutes()
	return s.observe(http.HandlerFunc(s.serve))
}

// serve runs the pipeline in the order the previous stack did: headers, CORS
// preflight, body parsing, routing, rate limit, write-key guard, handler.
func (s *Server) serve(w http.ResponseWriter, r *http.Request) {
	setBaseHeaders(w.Header())
	if r.Method == http.MethodOptions {
		writePreflight(w, r)
		return
	}
	body, err := readBody(r)
	if err != nil {
		s.writeError(w, r, err)
		return
	}
	rt, params := s.match(r)
	if rt == nil {
		s.writeError(w, r, apperr.NotFound("Cannot "+r.Method+" "+r.RequestURI))
		return
	}
	if err := s.rateLimit(r, rt); err != nil {
		s.writeError(w, r, err)
		return
	}
	if err := s.authorize(r, rt); err != nil {
		s.writeError(w, r, err)
		return
	}
	if err := rt.handle(w, &request{Request: r, params: params, body: body}); err != nil {
		s.writeError(w, r, err)
	}
}

func (s *Server) registerRoutes() {
	s.handle(http.MethodGet, "/v1/health", s.health, public)
	s.handle(http.MethodGet, "/v1/health/ready", s.ready, public)
	s.handle(http.MethodGet, "/v1/chains", s.chains, public)
	s.handle(http.MethodGet, "/v1/flags", s.flags, public)

	s.handle(http.MethodGet, "/v1/products", s.listProducts)
	s.handle(http.MethodGet, "/v1/products/labels.pdf", s.labelsPDF)
	s.handle(http.MethodPost, "/v1/products", s.registerProduct)
	s.handle(http.MethodPost, "/v1/products/batch", s.registerBatch)
	s.handle(http.MethodPost, "/v1/products/:id/transfer", s.transferProduct)
	s.handle(http.MethodPost, "/v1/products/:id/consume", s.consumeProduct, limited(consumeLimit))
	s.handle(http.MethodGet, "/v1/products/:id/history", s.productHistory)
	s.handle(http.MethodGet, "/v1/products/:chainProductId", s.productByChainID)

	s.handle(http.MethodGet, "/v1/verify/:id", s.verifyProduct, limited(verifyLimit))

	s.handle(http.MethodGet, "/v1/roles", s.listRoles)
	s.handle(http.MethodGet, "/v1/roles/:address", s.listRolesForAddress)
	s.handle(http.MethodPost, "/v1/roles", s.bindRole)
	s.handle(http.MethodDelete, "/v1/roles/:address/:role", s.unbindRole)

	s.handle(http.MethodPost, "/v1/reports/counterfeit", s.reportCounterfeit, public)
	s.handle(http.MethodGet, "/v1/reports/counterfeit", s.listReports)

	s.handle(http.MethodPost, "/v1/analytics/events/verify", s.trackEvent("verify_ok"), public)
	s.handle(http.MethodPost, "/v1/analytics/events/scan", s.trackEvent("scan_ok"), public)
	s.handle(http.MethodGet, "/v1/analytics/snapshot", s.analyticsSnapshot)

	s.handle(http.MethodPost, "/v1/meta/eip712/metadata/sign", s.signMetadata)
	s.handle(http.MethodPost, "/v1/meta/eip712/metadata/verify", s.verifyMetadata)
	s.handle(http.MethodPost, "/v1/meta/consume", s.metaConsume)

	s.handle(http.MethodPost, "/v1/admin/relayer-keys/reload", s.reloadRelayerKeys)

	s.registerV2Routes()
}
