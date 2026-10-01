package httpapi

import (
	"context"
	"encoding/json"
	"math"
	"strings"
	"testing"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/config"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/protocol"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/roles"
)

// fakeProtocol records what the handlers pass to the v2 services.
type fakeProtocol struct {
	registered []protocol.RegisterBatchInput
	transfers  []protocol.TransferInput
	consumes   []protocol.ConsumeInput
	verifies   []protocol.VerifyInput
	recalls    []protocol.RecallInput
	labels     []protocol.RenderLabelsInput
	segments   []protocol.SegmentListQuery
	suspicious []protocol.SuspiciousQuery
}

func (f *fakeProtocol) RegisterBatch(_ context.Context, in protocol.RegisterBatchInput) (protocol.RegisteredBatch, error) {
	f.registered = append(f.registered, in)
	return protocol.RegisteredBatch{Batch: protocol.Batch{BatchID: "1", Size: int32(in.Size)}, SegmentID: "1", KeysRevealOnce: true, Units: []protocol.UnitCredential{}}, nil
}

func (f *fakeProtocol) ListBatches(context.Context, protocol.BatchListQuery) (protocol.Page[protocol.Batch], error) {
	return protocol.Page[protocol.Batch]{Page: 1, Limit: 20, TotalPages: 1, Items: []protocol.Batch{}}, nil
}

func (f *fakeProtocol) GetBatch(_ context.Context, id string) (protocol.BatchDetail, error) {
	return protocol.BatchDetail{}, apperr.NotFound("Batch " + id + " not found")
}

func (f *fakeProtocol) Proof(_ context.Context, batchID, index string) (protocol.UnitProof, error) {
	return protocol.UnitProof{BatchID: batchID, Proof: []string{}}, nil
}

func (f *fakeProtocol) History(_ context.Context, batchID, index string) (protocol.UnitHistory, error) {
	return protocol.UnitHistory{BatchID: batchID, Custody: []protocol.CustodyStep{}}, nil
}

func (f *fakeProtocol) ListSegments(_ context.Context, q protocol.SegmentListQuery) (protocol.Page[protocol.Segment], error) {
	f.segments = append(f.segments, q)
	return protocol.Page[protocol.Segment]{Page: 1, Limit: 20, TotalPages: 1, Items: []protocol.Segment{}}, nil
}

func (f *fakeProtocol) GetSegment(_ context.Context, id string) (protocol.SegmentDetail, error) {
	return protocol.SegmentDetail{}, apperr.NotFound("Segment " + id + " not found")
}

func (f *fakeProtocol) Transfer(_ context.Context, in protocol.TransferInput) (protocol.TransferResult, error) {
	f.transfers = append(f.transfers, in)
	return protocol.TransferResult{FromSegmentID: in.SegmentID, To: in.ToAddress}, nil
}

func (f *fakeProtocol) Consume(_ context.Context, in protocol.ConsumeInput) (protocol.ConsumeResult, error) {
	f.consumes = append(f.consumes, in)
	return protocol.ConsumeResult{Status: "Consumed", BatchID: in.BatchID}, nil
}

func (f *fakeProtocol) Verify(_ context.Context, in protocol.VerifyInput) (protocol.VerifyResult, error) {
	f.verifies = append(f.verifies, in)
	return protocol.VerifyResult{Authenticity: protocol.Authentic, BatchID: in.BatchID}, nil
}

func (f *fakeProtocol) RenderLabels(_ context.Context, in protocol.RenderLabelsInput) ([]byte, error) {
	f.labels = append(f.labels, in)
	return []byte("%PDF-1.3"), nil
}

func (f *fakeProtocol) Recall(_ context.Context, in protocol.RecallInput) (protocol.RecallResult, error) {
	f.recalls = append(f.recalls, in)
	return protocol.RecallResult{Scope: "batch", BatchID: in.BatchID}, nil
}

func (f *fakeProtocol) ListSuspicious(_ context.Context, q protocol.SuspiciousQuery) ([]protocol.SuspiciousUnit, error) {
	f.suspicious = append(f.suspicious, q)
	return []protocol.SuspiciousUnit{}, nil
}

type fakeParties struct{ bound []roles.BindInput }

func (f *fakeParties) List(context.Context, string) ([]roles.Party, error) {
	return []roles.Party{}, nil
}

func (f *fakeParties) Bind(_ context.Context, in roles.BindInput) (roles.BoundParty, error) {
	f.bound = append(f.bound, in)
	return roles.BoundParty{Party: roles.Party{Address: in.Address, Role: in.Role}}, nil
}

func v2Harness(t *testing.T, mutate func(*config.Config, *Deps)) (harness, *fakeProtocol, *fakeParties) {
	t.Helper()
	p, parties := &fakeProtocol{}, &fakeParties{}
	h := newHarness(t, func(cfg *config.Config, d *Deps) {
		d.Protocol, d.Parties = p, parties
		d.Network = d.Network.WithRegistryV2("0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512")
		if mutate != nil {
			mutate(cfg, d)
		}
	})
	return h, p, parties
}

func TestV2RoutesNeedTheRegistry(t *testing.T) {
	h := newHarness(t, nil)
	expect(t, h.do(t, call{target: "/v2/batches"}), 503,
		`{"message":"Protocol v2 (SupplementRegistryV2) is not configured","error":"Service Unavailable","statusCode":503}`)
	rec := h.do(t, call{target: "/v2/health"})
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"protocol":"missing"`) {
		t.Fatalf("health = %d %s", rec.Code, rec.Body.String())
	}
	expect(t, h.do(t, call{target: "/v2/roles"}), 503,
		`{"message":"Role profiles are not configured","error":"Service Unavailable","statusCode":503}`)

	v2, _, _ := v2Harness(t, nil)
	rec = v2.do(t, call{target: "/v2/health"})
	if !strings.Contains(rec.Body.String(), `"protocol":"configured"`) || !strings.Contains(rec.Body.String(), `"version":"2.0.0"`) {
		t.Fatalf("health = %s", rec.Body.String())
	}
}

func TestV2ChainsPublishesTheConsumeDomain(t *testing.T) {
	h, _, _ := v2Harness(t, nil)
	rec := h.do(t, call{target: "/v2/chains"})
	var body struct {
		ActiveChainID   int64  `json:"activeChainId"`
		RegistryAddress string `json:"registryAddress"`
		PrimaryType     string `json:"primaryType"`
		EIP712Domain    struct {
			Name, Version     string
			ChainID           int64  `json:"chainId"`
			VerifyingContract string `json:"verifyingContract"`
		} `json:"eip712Domain"`
		ConsumeTypes map[string][]struct{ Name, Type string } `json:"consumeTypes"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body.ActiveChainID != 31337 || body.EIP712Domain.Version != "2" || body.EIP712Domain.ChainID != 31337 ||
		!strings.EqualFold(body.EIP712Domain.VerifyingContract, body.RegistryAddress) || body.RegistryAddress == "" {
		t.Fatalf("chains = %s", rec.Body.String())
	}
	if body.PrimaryType != chain.UnitConsumeTypeName || len(body.ConsumeTypes[body.PrimaryType]) == 0 {
		t.Fatalf("consume types = %s", rec.Body.String())
	}
}

func TestV2StrictBodiesAndForwarding(t *testing.T) {
	h, p, parties := v2Harness(t, nil)

	rec := h.do(t, call{method: "POST", target: "/v2/batches", body: `{"name":"D3","lotCode":"L1","size":12,"expiresAt":"2027-01-31"}`})
	if rec.Code != 201 || len(p.registered) != 1 || p.registered[0].Size != 12 || *p.registered[0].ExpiresAt != "2027-01-31" {
		t.Fatalf("register = %d %s %+v", rec.Code, rec.Body.String(), p.registered)
	}
	expect(t, h.do(t, call{method: "POST", target: "/v2/batches", body: `{"name":"D3","lotCode":"L1","size":12,"secret":"x"}`}), 400,
		`{"message":["property secret should not exist"],"error":"Bad Request","statusCode":400}`)
	expect(t, h.do(t, call{method: "POST", target: "/v2/batches", body: `{"name":"D3","lotCode":"L1","size":"12"}`}), 400,
		`{"message":["size must be an integer"],"error":"Bad Request","statusCode":400}`)
	expect(t, h.do(t, call{method: "POST", target: "/v2/batches", body: `{"name":"D3","lotCode":"L1","size":1.5}`}), 400,
		`{"message":["size must be an integer"],"error":"Bad Request","statusCode":400}`)
	if len(p.registered) != 1 {
		t.Fatalf("invalid bodies reached the service: %+v", p.registered)
	}

	h.do(t, call{method: "POST", target: "/v2/segments/7/transfer", body: `{"toAddress":"0xabc","count":3}`})
	h.do(t, call{method: "POST", target: "/v2/segments/8/transfer", body: `{"toAddress":"0xabc"}`})
	if len(p.transfers) != 2 || p.transfers[0] != (protocol.TransferInput{SegmentID: "7", ToAddress: "0xabc", Count: 3}) || p.transfers[1].Count != 0 {
		t.Fatalf("transfers = %+v", p.transfers)
	}

	h.do(t, call{method: "POST", target: "/v2/batches/5/recall", body: `{"segmentId":9,"reason":"mould"}`})
	h.do(t, call{method: "POST", target: "/v2/batches/5/recall", body: `{"segmentId":"10"}`})
	h.do(t, call{method: "POST", target: "/v2/batches/6/recall"})
	if len(p.recalls) != 3 || *p.recalls[0].SegmentID != "9" || *p.recalls[0].Reason != "mould" || *p.recalls[1].SegmentID != "10" ||
		p.recalls[2].SegmentID != nil || p.recalls[2].BatchID != "6" {
		t.Fatalf("recalls = %+v", p.recalls)
	}
	expect(t, h.do(t, call{method: "POST", target: "/v2/batches/5/recall", body: `{"segmentId":true}`}), 400,
		`{"message":["segmentId must be a string"],"error":"Bad Request","statusCode":400}`)

	h.do(t, call{method: "POST", target: "/v2/roles", body: `{"address":"0xabc","role":"Pharmacy","region":"Tehran","onChain":false}`})
	if len(parties.bound) != 1 || *parties.bound[0].Region != "Tehran" || *parties.bound[0].OnChain {
		t.Fatalf("bind = %+v", parties.bound)
	}

	h.do(t, call{target: "/v2/segments?owner=0xAB&status=AtPointOfSale&batchId=3&page=2&limit=5"})
	q := p.segments[0]
	if q.Owner != "0xAB" || q.Status != "AtPointOfSale" || q.BatchID != "3" || q.Page != 2 || q.Limit != 5 {
		t.Fatalf("segment query = %+v", q)
	}
	h.do(t, call{target: "/v2/scans/suspicious?minRisk=0.8"})
	if s := p.suspicious[0]; s.MinRisk != 0.8 || !math.IsNaN(s.Limit) || !math.IsNaN(s.SinceHours) {
		t.Fatalf("suspicious query = %+v", s)
	}
}

func TestV2ConsumeIsPublicAndWritesNeedTheKey(t *testing.T) {
	h, p, _ := v2Harness(t, nil)
	sig := "0x" + strings.Repeat("ab", 65)
	rec := h.do(t, call{method: "POST", target: "/v2/consume", noKey: true,
		body: `{"chainId":31337,"batchId":"4","index":2,"consumer":"0xdd","deadline":1900000000,"signature":"` + sig + `"}`})
	if rec.Code != 201 || len(p.consumes) != 1 {
		t.Fatalf("consume = %d %s", rec.Code, rec.Body.String())
	}
	c := p.consumes[0]
	if *c.ChainID != 31337 || c.BatchID != "4" || c.Index != 2 || c.Deadline != 1900000000 || c.Signature != sig {
		t.Fatalf("consume input = %+v", c)
	}
	h.do(t, call{method: "POST", target: "/v2/consume", noKey: true, body: `{"batchId":4,"index":0,"consumer":"0xdd","deadline":1,"signature":"0x"}`})
	if len(p.consumes) != 2 || p.consumes[1].BatchID != "4" || p.consumes[1].ChainID != nil {
		t.Fatalf("numeric batch id = %+v", p.consumes)
	}

	unauthorized := `{"message":"Invalid or missing write API key","error":"Unauthorized","statusCode":401}`
	for _, target := range []string{"/v2/batches", "/v2/segments/1/transfer", "/v2/batches/1/recall", "/v2/labels/render", "/v2/roles"} {
		expect(t, h.do(t, call{method: "POST", target: target, body: `{}`, noKey: true}), 401, unauthorized)
	}
	if rec := h.do(t, call{target: "/v2/verify/31337/1/0", noKey: true}); rec.Code != 200 {
		t.Fatalf("verify must be public: %d", rec.Code)
	}
}

func TestV2VerifyPassesScanContext(t *testing.T) {
	h, p, _ := v2Harness(t, nil)
	rec := h.do(t, call{target: "/v2/verify/31337/12/3?region=shiraz", noKey: true, headers: map[string]string{
		"X-Device-Id": "phone-1", "User-Agent": "SupplementApp/2", "Cache-Control": "no-cache", "X-Forwarded-For": "5.6.7.8",
	}})
	if rec.Code != 200 || rec.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("verify = %d %v", rec.Code, rec.Header())
	}
	v := p.verifies[0]
	if v.ChainID != "31337" || v.BatchID != "12" || v.Index != "3" || v.Device != "phone-1" || v.UserAgent != "SupplementApp/2" ||
		v.Region != "shiraz" || !v.Fresh || v.ClientIP == "" {
		t.Fatalf("verify input = %+v", v)
	}
	h.do(t, call{target: "/v2/verify/31337/12/3?region=shiraz", headers: map[string]string{"X-Scan-Region": "Tehran"}})
	if v := p.verifies[1]; v.Region != "Tehran" || v.Fresh {
		t.Fatalf("header region must win and caching applies by default: %+v", v)
	}

	prod, pp, _ := v2Harness(t, func(cfg *config.Config, _ *Deps) { cfg.Env = "production" })
	prod.do(t, call{target: "/v2/verify/31337/12/3", headers: map[string]string{"Cache-Control": "no-cache"}})
	if pp.verifies[0].Fresh {
		t.Fatal("production clients must not bypass the snapshot cache")
	}

	limited, _, _ := v2Harness(t, func(cfg *config.Config, _ *Deps) {
		cfg.VerifyRateLimit = 1
		cfg.RateLimitWindow = time.Minute
	})
	limited.do(t, call{target: "/v2/verify/31337/1/0"})
	expect(t, limited.do(t, call{target: "/v2/verify/31337/1/0"}), 429, `{"statusCode":429,"message":"Too Many Requests"}`)
}

func TestV2LabelsRender(t *testing.T) {
	h, p, _ := v2Harness(t, nil)
	rec := h.do(t, call{method: "POST", target: "/v2/labels/render", body: `{"batchId":3,"secretQrs":["satk2:1:3:0:aa"]}`})
	if rec.Code != 200 || rec.Header().Get("Content-Type") != "application/pdf" || rec.Body.String() != "%PDF-1.3" ||
		rec.Header().Get("Content-Disposition") != `attachment; filename="labels-batch-3.pdf"` {
		t.Fatalf("labels = %d %v %q", rec.Code, rec.Header(), rec.Body.String())
	}
	if len(p.labels) != 1 || p.labels[0].BatchID != "3" || p.labels[0].SecretQRs[0] != "satk2:1:3:0:aa" {
		t.Fatalf("labels input = %+v", p.labels)
	}
	expect(t, h.do(t, call{method: "POST", target: "/v2/labels/render", body: `{"batchId":3,"secretQrs":"x"}`}), 400,
		`{"message":["secretQrs must be a list"],"error":"Bad Request","statusCode":400}`)

	off, _, _ := v2Harness(t, func(cfg *config.Config, _ *Deps) { cfg.Flags.LabelsPDFEnabled = false })
	expect(t, off.do(t, call{method: "POST", target: "/v2/labels/render", body: `{"batchId":3,"secretQrs":[]}`}), 503,
		`{"message":"Labels PDF export is disabled","error":"Service Unavailable","statusCode":503}`)
}
