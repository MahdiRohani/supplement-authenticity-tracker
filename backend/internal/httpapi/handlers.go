package httpapi

import (
	"context"
	"encoding/json"
	"math"
	"net/http"
	"strings"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/product"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/reports"
)

const apiVersion = "1.1.0"

var (
	registerProductSchema = schema{
		stringField("name").minLength(1),
		stringField("batch").optionalField(),
		stringField("manufacturerAddress").optionalField(),
		stringField("physicalId").optionalField(),
	}
	registerBatchSchema = schema{
		stringField("name").minLength(1),
		stringField("batch").optionalField(),
		intField("count").min(1).max(100),
		stringField("manufacturerAddress").optionalField(),
	}
	transferSchema = schema{stringField("toAddress").minLength(42)}
	consumeSchema  = schema{stringField("secret").minLength(66)}
	reportSchema   = schema{
		stringField("chainProductId").minLength(1),
		stringField("note").optionalField(),
		stringField("reporter").optionalField(),
	}
	signMetadataSchema = schema{
		stringField("metadataHash").minLength(66),
		stringField("manufacturer").minLength(42),
		stringField("privateKey").minLength(66),
		intField("nonce").optionalField().min(0),
		intField("chainId").optionalField(),
	}
	verifyMetadataSchema = schema{
		stringField("metadataHash").minLength(66),
		stringField("manufacturer").minLength(42),
		stringField("signature").minLength(10),
		intField("nonce").min(0),
		intField("chainId").optionalField(),
	}
	metaConsumeSchema = schema{
		stringField("productId").minLength(1),
		stringField("secret").minLength(66),
		stringField("consumer").minLength(42),
		intField("deadline").min(1),
		stringField("signature").minLength(10),
	}
)

func (s *Server) ok(w http.ResponseWriter, r *request, v any) error {
	writeJSON(w, r.Request, created(r.Request), v)
	return nil
}

// query returns a query parameter; repeated keys are joined with commas, which
// is what String(array) produced for the previous API.
func query(r *request, key string) string {
	return strings.Join(r.URL.Query()[key], ",")
}

// queryNumber is Number(value) for a present, non-empty parameter and NaN
// otherwise.
func queryNumber(r *request, key string) float64 {
	raw := query(r, key)
	if raw == "" {
		return math.NaN()
	}
	return jsStringToNumber(raw)
}

type healthChecks struct {
	API      string `json:"api"`
	Database string `json:"database"`
	RPC      string `json:"rpc"`
	IPFS     string `json:"ipfs"`
}

func (s *Server) health(w http.ResponseWriter, r *request) error {
	checks := healthChecks{API: "ok", Database: "ok", RPC: "missing", IPFS: "missing"}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if err := s.DB.Ping(ctx); err != nil {
		checks.Database = "down"
	}
	if s.cfg.RPCURL != "" {
		checks.RPC = "configured"
	}
	if s.cfg.IPFSAPIURL != "" {
		checks.IPFS = "configured"
	}
	status := "ok"
	if checks.Database != "ok" {
		status = "degraded"
	}
	return s.ok(w, r, struct {
		Status    string       `json:"status"`
		Version   string       `json:"version"`
		API       string       `json:"api"`
		Checks    healthChecks `json:"checks"`
		Timestamp string       `json:"timestamp"`
	}{status, apiVersion, "v1", checks, jsonx.ISOTime(time.Now())})
}

func (s *Server) ready(w http.ResponseWriter, r *request) error {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	if err := s.DB.Ping(ctx); err != nil {
		s.log.WarnContext(ctx, "readiness check failed", "err", err)
		return apperr.ServiceUnavailable("Database is not reachable")
	}
	return s.ok(w, r, struct {
		Status string `json:"status"`
	}{"ready"})
}

func (s *Server) chains(w http.ResponseWriter, r *request) error {
	active := s.Network.Deployments.Entry(s.Network.ChainID)
	if active == nil {
		active = json.RawMessage("null")
	}
	return s.ok(w, r, struct {
		ActiveChainID   int64           `json:"activeChainId"`
		RegistryAddress string          `json:"registryAddress"`
		Active          json.RawMessage `json:"active"`
		Deployments     json.RawMessage `json:"deployments"`
	}{s.Network.ChainID, s.Network.RegistryAddress(), active, s.Network.Deployments.All()})
}

func (s *Server) flags(w http.ResponseWriter, r *request) error {
	return s.ok(w, r, s.cfg.Flags)
}

func (s *Server) listProducts(w http.ResponseWriter, r *request) error {
	page, err := s.Products.List(r.Context(), product.ListQuery{
		Owner:  query(r, "owner"),
		Status: query(r, "status"),
		Q:      query(r, "q"),
		Page:   queryNumber(r, "page"),
		Limit:  queryNumber(r, "limit"),
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, page)
}

func (s *Server) labelsPDF(w http.ResponseWriter, r *request) error {
	if !s.cfg.Flags.LabelsPDFEnabled {
		return apperr.ServiceUnavailable("Labels PDF export is disabled")
	}
	batch := strings.TrimSpace(query(r, "batch"))
	if batch == "" {
		return apperr.BadRequest("batch query parameter is required")
	}
	pdf, err := s.Products.LabelsPDF(r.Context(), batch)
	if err != nil {
		return err
	}
	w.Header().Set("Content-Disposition", `attachment; filename="labels-`+batch+`.pdf"`)
	writeBody(w, r.Request, http.StatusOK, "application/pdf", pdf)
	return nil
}

func (s *Server) registerProduct(w http.ResponseWriter, r *request) error {
	v, err := registerProductSchema.validate(r.body)
	if err != nil {
		return err
	}
	out, err := s.Products.Register(r.Context(), product.RegisterInput{
		Name:                v.str("name"),
		Batch:               v.optStr("batch"),
		ManufacturerAddress: v.optStr("manufacturerAddress"),
		PhysicalID:          v.optStr("physicalId"),
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) registerBatch(w http.ResponseWriter, r *request) error {
	v, err := registerBatchSchema.validate(r.body)
	if err != nil {
		return err
	}
	out, err := s.Products.RegisterBatch(r.Context(), product.RegisterBatchInput{
		Name:                v.str("name"),
		Batch:               v.optStr("batch"),
		Count:               v.int("count"),
		ManufacturerAddress: v.optStr("manufacturerAddress"),
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) transferProduct(w http.ResponseWriter, r *request) error {
	v, err := transferSchema.validate(r.body)
	if err != nil {
		return err
	}
	out, err := s.Products.Transfer(r.Context(), r.param("id"), v.str("toAddress"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) consumeProduct(w http.ResponseWriter, r *request) error {
	v, err := consumeSchema.validate(r.body)
	if err != nil {
		return err
	}
	out, err := s.Products.Consume(r.Context(), r.param("id"), v.str("secret"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) productHistory(w http.ResponseWriter, r *request) error {
	out, err := s.Products.History(r.Context(), r.param("id"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) productByChainID(w http.ResponseWriter, r *request) error {
	out, err := s.Products.ByChainID(r.Context(), r.param("chainProductId"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) verifyProduct(w http.ResponseWriter, r *request) error {
	out, err := s.Verify.Verify(r.Context(), r.param("id"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) listRoles(w http.ResponseWriter, r *request) error {
	out, err := s.Roles.List(r.Context())
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) listRolesForAddress(w http.ResponseWriter, r *request) error {
	out, err := s.Roles.ListForAddress(r.Context(), r.param("address"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

// bindRole has no DTO in the original API: the raw body is used, so a
// non-string role is reported via its String() form ("Unknown role: 5").
func (s *Server) bindRole(w http.ResponseWriter, r *request) error {
	address, _ := r.body.values["address"].(string)
	rawRole, present := r.body.get("role")
	out, err := s.Roles.Bind(r.Context(), address, jsToString(rawRole, present))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) unbindRole(w http.ResponseWriter, r *request) error {
	out, err := s.Roles.Unbind(r.Context(), r.param("address"), r.param("role"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) reportCounterfeit(w http.ResponseWriter, r *request) error {
	v, err := reportSchema.validate(r.body)
	if err != nil {
		return err
	}
	out, err := s.Reports.Report(r.Context(), reports.Input{
		ChainProductID: v.str("chainProductId"),
		Note:           v.optStr("note"),
		Reporter:       v.optStr("reporter"),
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) listReports(w http.ResponseWriter, r *request) error {
	out, err := s.Reports.List(r.Context(), queryNumber(r, "limit"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) trackEvent(name string) handlerFunc {
	return func(w http.ResponseWriter, r *request) error {
		out, err := s.Analytics.Increment(r.Context(), name)
		if err != nil {
			return err
		}
		return s.ok(w, r, out)
	}
}

func (s *Server) analyticsSnapshot(w http.ResponseWriter, r *request) error {
	out, err := s.Analytics.Snapshot(r.Context())
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) signMetadata(w http.ResponseWriter, r *request) error {
	v, err := signMetadataSchema.validate(r.body)
	if err != nil {
		return err
	}
	if !s.cfg.Flags.EIP712MetadataEnabled {
		return apperr.ServiceUnavailable("EIP-712 metadata signing disabled")
	}
	chainID := s.cfg.ActiveChainID()
	if id := v.optInt("chainId"); id != nil {
		chainID = *id
	}
	var nonce int64
	if n := v.optInt("nonce"); n != nil {
		nonce = *n
	}
	signature, err := s.EIP712.SignManufacturerMetadata(v.str("privateKey"), chain.ManufacturerMetadata{
		MetadataHash: v.str("metadataHash"),
		Manufacturer: v.str("manufacturer"),
		ChainID:      chainID,
		Nonce:        nonce,
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, struct {
		Signature string `json:"signature"`
		ChainID   int64  `json:"chainId"`
		Nonce     int64  `json:"nonce"`
	}{signature, chainID, nonce})
}

func (s *Server) verifyMetadata(w http.ResponseWriter, r *request) error {
	v, err := verifyMetadataSchema.validate(r.body)
	if err != nil {
		return err
	}
	if !s.cfg.Flags.EIP712MetadataEnabled {
		return apperr.ServiceUnavailable("EIP-712 metadata verify disabled")
	}
	chainID := s.cfg.ActiveChainID()
	if id := v.optInt("chainId"); id != nil {
		chainID = *id
	}
	manufacturer := v.str("manufacturer")
	recovered, err := s.EIP712.RecoverManufacturerMetadata(v.str("signature"), chain.ManufacturerMetadata{
		MetadataHash: v.str("metadataHash"),
		Manufacturer: manufacturer,
		ChainID:      chainID,
		Nonce:        v.int("nonce"),
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, struct {
		Valid     bool   `json:"valid"`
		Recovered string `json:"recovered"`
		ChainID   int64  `json:"chainId"`
	}{strings.EqualFold(recovered.Hex(), manufacturer), recovered.Hex(), chainID})
}

// metaConsume lets a relayer submit a consume that the consumer authorized
// off-chain with an EIP-712 signature.
func (s *Server) metaConsume(w http.ResponseWriter, r *request) error {
	v, err := metaConsumeSchema.validate(r.body)
	if err != nil {
		return err
	}
	if !s.cfg.Flags.MetaTxConsumeEnabled {
		return apperr.ServiceUnavailable("Meta-tx consume disabled")
	}
	deadline := v.int("deadline")
	if deadline < time.Now().Unix() {
		return apperr.BadRequest("Authorization deadline expired")
	}
	consumer := v.str("consumer")
	recovered, err := s.EIP712.RecoverConsumeAuthorization(v.str("signature"), chain.ConsumeAuthorization{
		ProductID: v.str("productId"),
		Secret:    v.str("secret"),
		Consumer:  consumer,
		Deadline:  deadline,
		ChainID:   s.cfg.ActiveChainID(),
	})
	if err != nil {
		return err
	}
	if !strings.EqualFold(recovered.Hex(), consumer) {
		return apperr.BadRequest("Invalid consume authorization signature")
	}
	out, err := s.Products.Consume(r.Context(), v.str("productId"), v.str("secret"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) reloadRelayerKeys(w http.ResponseWriter, r *request) error {
	addresses, err := s.ReloadKeys(r.Context())
	if err != nil {
		return err
	}
	if addresses == nil {
		addresses = []string{}
	}
	return s.ok(w, r, struct {
		Reloaded        bool     `json:"reloaded"`
		ActiveAddresses []string `json:"activeAddresses"`
	}{true, addresses})
}
