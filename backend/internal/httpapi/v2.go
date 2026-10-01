package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"reflect"
	"strconv"
	"strings"
	"time"

	"github.com/ethereum/go-ethereum/signer/core/apitypes"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/jsonx"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/protocol"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/roles"
)

const apiVersionV2 = "2.0.0"

// ProtocolService is the unit-level v2 protocol (SupplementRegistryV2).
type ProtocolService interface {
	RegisterBatch(ctx context.Context, in protocol.RegisterBatchInput) (protocol.RegisteredBatch, error)
	ListBatches(ctx context.Context, q protocol.BatchListQuery) (protocol.Page[protocol.Batch], error)
	GetBatch(ctx context.Context, batchID string) (protocol.BatchDetail, error)
	Proof(ctx context.Context, batchID, index string) (protocol.UnitProof, error)
	History(ctx context.Context, batchID, index string) (protocol.UnitHistory, error)
	ListSegments(ctx context.Context, q protocol.SegmentListQuery) (protocol.Page[protocol.Segment], error)
	GetSegment(ctx context.Context, segmentID string) (protocol.SegmentDetail, error)
	Transfer(ctx context.Context, in protocol.TransferInput) (protocol.TransferResult, error)
	Consume(ctx context.Context, in protocol.ConsumeInput) (protocol.ConsumeResult, error)
	Verify(ctx context.Context, in protocol.VerifyInput) (protocol.VerifyResult, error)
	RenderLabels(ctx context.Context, in protocol.RenderLabelsInput) ([]byte, error)
	Recall(ctx context.Context, in protocol.RecallInput) (protocol.RecallResult, error)
	ListSuspicious(ctx context.Context, q protocol.SuspiciousQuery) ([]protocol.SuspiciousUnit, error)
}

type PartyService interface {
	List(ctx context.Context, address string) ([]roles.Party, error)
	Bind(ctx context.Context, in roles.BindInput) (roles.BoundParty, error)
}

func (s *Server) registerV2Routes() {
	s.handle(http.MethodGet, "/v2/health", s.healthV2, public)
	s.handle(http.MethodGet, "/v2/health/ready", s.ready, public)
	s.handle(http.MethodGet, "/v2/chains", s.chainsV2, public)
	s.handle(http.MethodGet, "/v2/flags", s.flags, public)

	s.handle(http.MethodPost, "/v2/batches", s.v2(s.registerBatchV2))
	s.handle(http.MethodGet, "/v2/batches", s.v2(s.listBatchesV2))
	s.handle(http.MethodGet, "/v2/batches/:batchId", s.v2(s.getBatchV2))
	s.handle(http.MethodPost, "/v2/batches/:batchId/recall", s.v2(s.recallV2))
	s.handle(http.MethodGet, "/v2/batches/:batchId/units/:index/proof", s.v2(s.unitProofV2))
	s.handle(http.MethodGet, "/v2/batches/:batchId/units/:index/history", s.v2(s.unitHistoryV2))

	s.handle(http.MethodGet, "/v2/segments", s.v2(s.listSegmentsV2))
	s.handle(http.MethodGet, "/v2/segments/:segmentId", s.v2(s.getSegmentV2))
	s.handle(http.MethodPost, "/v2/segments/:segmentId/transfer", s.v2(s.transferSegmentV2))

	s.handle(http.MethodPost, "/v2/consume", s.v2(s.consumeV2), public, limited(consumeLimit))
	s.handle(http.MethodGet, "/v2/verify/:chainId/:batchId/:index", s.v2(s.verifyV2), limited(verifyLimit))
	s.handle(http.MethodPost, "/v2/labels/render", s.v2(s.renderLabelsV2))
	s.handle(http.MethodGet, "/v2/scans/suspicious", s.v2(s.suspiciousV2))

	s.handle(http.MethodGet, "/v2/roles", s.listPartiesV2)
	s.handle(http.MethodPost, "/v2/roles", s.bindPartyV2)
	s.handle(http.MethodDelete, "/v2/roles/:address/:role", s.unbindRole)

	s.handle(http.MethodPost, "/v2/reports/counterfeit", s.reportCounterfeit, public)
	s.handle(http.MethodGet, "/v2/reports/counterfeit", s.listReports)
	s.handle(http.MethodGet, "/v2/analytics/snapshot", s.analyticsSnapshot)
	s.handle(http.MethodPost, "/v2/admin/relayer-keys/reload", s.reloadRelayerKeys)
}

// v2 fails protocol routes with 503 when SupplementRegistryV2 is not wired.
func (s *Server) v2(h handlerFunc) handlerFunc {
	return func(w http.ResponseWriter, r *request) error {
		if s.Protocol == nil {
			return apperr.ServiceUnavailable("Protocol v2 (SupplementRegistryV2) is not configured")
		}
		return h(w, r)
	}
}

// decodeV2 strictly decodes the already-parsed JSON body into dst; unknown
// properties are rejected.
func decodeV2(r *request, dst any) error {
	raw, err := json.Marshal(r.body.values)
	if err != nil {
		return apperr.Validation([]string{"body contains a non-finite number"})
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		var typeErr *json.UnmarshalTypeError
		switch {
		case errors.As(err, &typeErr):
			return apperr.Validation([]string{fmt.Sprintf("%s must be %s", typeErr.Field, jsonKind(typeErr.Type.Kind().String()))})
		case strings.HasPrefix(err.Error(), "json: unknown field "):
			field := strings.Trim(strings.TrimPrefix(err.Error(), "json: unknown field "), `"`)
			return apperr.Validation([]string{"property " + field + " should not exist"})
		default:
			var fieldErr interface{ Field() string }
			if errors.As(err, &fieldErr) {
				return apperr.Validation([]string{fieldErr.Field() + " is invalid"})
			}
			return apperr.Validation([]string{err.Error()})
		}
	}
	return nil
}

func jsonKind(kind string) string {
	switch {
	case strings.HasPrefix(kind, "int"), strings.HasPrefix(kind, "uint"):
		return "an integer"
	case strings.HasPrefix(kind, "float"):
		return "a number"
	case kind == "slice":
		return "a list"
	case kind == "bool":
		return "a boolean"
	case kind == "struct", kind == "map":
		return "an object"
	default:
		return "a " + kind
	}
}

// flexID accepts an id as a JSON string or integer; registry ids are uint256
// on-chain and travel as strings in responses.
type flexID string

func (f *flexID) UnmarshalJSON(b []byte) error {
	var str string
	if err := json.Unmarshal(b, &str); err == nil {
		*f = flexID(str)
		return nil
	}
	var n json.Number
	if err := json.Unmarshal(b, &n); err != nil {
		return &json.UnmarshalTypeError{Value: string(b), Type: stringType}
	}
	if _, err := strconv.ParseInt(n.String(), 10, 64); err != nil {
		return &json.UnmarshalTypeError{Value: string(b), Type: stringType}
	}
	*f = flexID(n.String())
	return nil
}

var stringType = reflect.TypeFor[string]()

func (s *Server) healthV2(w http.ResponseWriter, r *request) error {
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	database := "ok"
	if err := s.DB.Ping(ctx); err != nil {
		database = "down"
	}
	protocolStatus, registry := "missing", ""
	if s.Network != nil {
		registry = s.Network.RegistryV2().Address
	}
	if s.Protocol != nil && registry != "" {
		protocolStatus = "configured"
	}
	status := "ok"
	if database != "ok" {
		status = "degraded"
	}
	return s.ok(w, r, struct {
		Status    string            `json:"status"`
		Version   string            `json:"version"`
		API       string            `json:"api"`
		Checks    map[string]string `json:"checks"`
		Timestamp string            `json:"timestamp"`
	}{status, apiVersionV2, "v2", map[string]string{"api": "ok", "database": database, "protocol": protocolStatus}, jsonx.ISOTime(time.Now())})
}

type eip712Domain struct {
	Name              string `json:"name"`
	Version           string `json:"version"`
	ChainID           int64  `json:"chainId"`
	VerifyingContract string `json:"verifyingContract"`
}

func (s *Server) chainsV2(w http.ResponseWriter, r *request) error {
	v2 := s.Network.RegistryV2()
	domain := chain.NewEIP712V2(v2.Address)
	return s.ok(w, r, struct {
		ActiveChainID   int64                      `json:"activeChainId"`
		RegistryAddress string                     `json:"registryAddress"`
		ABIVersion      string                     `json:"abiVersion"`
		DeployBlock     uint64                     `json:"deployBlock"`
		PublicVerifyURL string                     `json:"publicVerifyBaseUrl"`
		EIP712Domain    eip712Domain               `json:"eip712Domain"`
		ConsumeTypes    map[string][]apitypes.Type `json:"consumeTypes"`
		PrimaryType     string                     `json:"primaryType"`
		Deployments     json.RawMessage            `json:"deployments"`
	}{
		ActiveChainID:   s.Network.ChainID,
		RegistryAddress: v2.Address,
		ABIVersion:      v2.ABIVersion,
		DeployBlock:     v2.DeployBlock,
		PublicVerifyURL: s.cfg.PublicVerifyBaseURL,
		EIP712Domain:    eip712Domain{domain.Name(), domain.Version(), s.Network.ChainID, domain.VerifyingContract()},
		ConsumeTypes:    chain.UnitConsumeTypes(),
		PrimaryType:     chain.UnitConsumeTypeName,
		Deployments:     s.Network.Deployments.All(),
	})
}

func (s *Server) registerBatchV2(w http.ResponseWriter, r *request) error {
	var body struct {
		Name                string  `json:"name"`
		LotCode             string  `json:"lotCode"`
		Size                int64   `json:"size"`
		ManufacturerAddress *string `json:"manufacturerAddress"`
		ExpiresAt           *string `json:"expiresAt"`
	}
	if err := decodeV2(r, &body); err != nil {
		return err
	}
	out, err := s.Protocol.RegisterBatch(r.Context(), protocol.RegisterBatchInput{
		Name:                body.Name,
		LotCode:             body.LotCode,
		Size:                body.Size,
		ManufacturerAddress: body.ManufacturerAddress,
		ExpiresAt:           body.ExpiresAt,
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func paging(r *request) protocol.Paging {
	return protocol.Paging{Page: queryNumber(r, "page"), Limit: queryNumber(r, "limit")}
}

func (s *Server) listBatchesV2(w http.ResponseWriter, r *request) error {
	out, err := s.Protocol.ListBatches(r.Context(), protocol.BatchListQuery{Manufacturer: query(r, "manufacturer"), Paging: paging(r)})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) getBatchV2(w http.ResponseWriter, r *request) error {
	out, err := s.Protocol.GetBatch(r.Context(), r.param("batchId"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) recallV2(w http.ResponseWriter, r *request) error {
	var body struct {
		SegmentID *flexID `json:"segmentId"`
		Reason    *string `json:"reason"`
	}
	if err := decodeV2(r, &body); err != nil {
		return err
	}
	in := protocol.RecallInput{BatchID: r.param("batchId"), Reason: body.Reason}
	if body.SegmentID != nil {
		in.SegmentID = new(string(*body.SegmentID))
	}
	out, err := s.Protocol.Recall(r.Context(), in)
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) unitProofV2(w http.ResponseWriter, r *request) error {
	out, err := s.Protocol.Proof(r.Context(), r.param("batchId"), r.param("index"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) unitHistoryV2(w http.ResponseWriter, r *request) error {
	out, err := s.Protocol.History(r.Context(), r.param("batchId"), r.param("index"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) listSegmentsV2(w http.ResponseWriter, r *request) error {
	out, err := s.Protocol.ListSegments(r.Context(), protocol.SegmentListQuery{
		Owner:   query(r, "owner"),
		BatchID: query(r, "batchId"),
		Status:  query(r, "status"),
		Paging:  paging(r),
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) getSegmentV2(w http.ResponseWriter, r *request) error {
	out, err := s.Protocol.GetSegment(r.Context(), r.param("segmentId"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) transferSegmentV2(w http.ResponseWriter, r *request) error {
	var body struct {
		ToAddress string `json:"toAddress"`
		Count     int64  `json:"count"`
	}
	if err := decodeV2(r, &body); err != nil {
		return err
	}
	out, err := s.Protocol.Transfer(r.Context(), protocol.TransferInput{SegmentID: r.param("segmentId"), ToAddress: body.ToAddress, Count: body.Count})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) consumeV2(w http.ResponseWriter, r *request) error {
	var body struct {
		ChainID   *int64 `json:"chainId"`
		BatchID   flexID `json:"batchId"`
		Index     int64  `json:"index"`
		Consumer  string `json:"consumer"`
		Deadline  int64  `json:"deadline"`
		Signature string `json:"signature"`
	}
	if err := decodeV2(r, &body); err != nil {
		return err
	}
	out, err := s.Protocol.Consume(r.Context(), protocol.ConsumeInput{
		ChainID:   body.ChainID,
		BatchID:   string(body.BatchID),
		Index:     body.Index,
		Consumer:  body.Consumer,
		Deadline:  body.Deadline,
		Signature: body.Signature,
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) verifyV2(w http.ResponseWriter, r *request) error {
	region := r.Header.Get("X-Scan-Region")
	if region == "" {
		region = query(r, "region")
	}
	out, err := s.Protocol.Verify(r.Context(), protocol.VerifyInput{
		ChainID:   r.param("chainId"),
		BatchID:   r.param("batchId"),
		Index:     r.param("index"),
		Device:    r.Header.Get("X-Device-Id"),
		ClientIP:  clientIP(r.Request),
		UserAgent: r.UserAgent(),
		Region:    region,
		Fresh:     !s.cfg.IsProduction() && strings.Contains(r.Header.Get("Cache-Control"), "no-cache"),
	})
	if err != nil {
		return err
	}
	w.Header().Set("Cache-Control", "no-store")
	return s.ok(w, r, out)
}

func (s *Server) renderLabelsV2(w http.ResponseWriter, r *request) error {
	if !s.cfg.Flags.LabelsPDFEnabled {
		return apperr.ServiceUnavailable("Labels PDF export is disabled")
	}
	var body struct {
		BatchID   flexID   `json:"batchId"`
		SecretQRs []string `json:"secretQrs"`
	}
	if err := decodeV2(r, &body); err != nil {
		return err
	}
	pdf, err := s.Protocol.RenderLabels(r.Context(), protocol.RenderLabelsInput{BatchID: string(body.BatchID), SecretQRs: body.SecretQRs})
	if err != nil {
		return err
	}
	w.Header().Set("Content-Disposition", `attachment; filename="labels-batch-`+string(body.BatchID)+`.pdf"`)
	w.Header().Set("Cache-Control", "no-store")
	writeBody(w, r.Request, http.StatusOK, "application/pdf", pdf)
	return nil
}

func (s *Server) suspiciousV2(w http.ResponseWriter, r *request) error {
	out, err := s.Protocol.ListSuspicious(r.Context(), protocol.SuspiciousQuery{
		SinceHours: queryNumber(r, "sinceHours"),
		MinRisk:    queryNumber(r, "minRisk"),
		Limit:      queryNumber(r, "limit"),
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, struct {
		Items []protocol.SuspiciousUnit `json:"items"`
	}{out})
}

func (s *Server) listPartiesV2(w http.ResponseWriter, r *request) error {
	if s.Parties == nil {
		return apperr.ServiceUnavailable("Role profiles are not configured")
	}
	out, err := s.Parties.List(r.Context(), query(r, "address"))
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}

func (s *Server) bindPartyV2(w http.ResponseWriter, r *request) error {
	if s.Parties == nil {
		return apperr.ServiceUnavailable("Role profiles are not configured")
	}
	var body struct {
		Address     string  `json:"address"`
		Role        string  `json:"role"`
		DisplayName *string `json:"displayName"`
		Region      *string `json:"region"`
		OnChain     *bool   `json:"onChain"`
	}
	if err := decodeV2(r, &body); err != nil {
		return err
	}
	out, err := s.Parties.Bind(r.Context(), roles.BindInput{
		Address: body.Address, Role: body.Role, DisplayName: body.DisplayName, Region: body.Region, OnChain: body.OnChain,
	})
	if err != nil {
		return err
	}
	return s.ok(w, r, out)
}
