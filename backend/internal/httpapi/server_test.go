package httpapi

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"math"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/analytics"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/config"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/product"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/reports"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/roles"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/verify"
)

const writeKey = "test-key"

type fakeDB struct{ err error }

func (f fakeDB) Ping(context.Context) error { return f.err }

type fakeProducts struct {
	registered []product.RegisterInput
	batches    []product.RegisterBatchInput
	consumed   []string
	list       []product.ListQuery
}

func (f *fakeProducts) List(_ context.Context, q product.ListQuery) (product.Page, error) {
	f.list = append(f.list, q)
	return product.Page{Page: 1, Limit: 20, TotalPages: 1, Items: []product.Summary{}}, nil
}

func (f *fakeProducts) LabelsPDF(_ context.Context, batch string) ([]byte, error) {
	if batch != "B-1" {
		return nil, apperr.NotFound("No products for batch " + batch)
	}
	return []byte("%PDF-1.4"), nil
}

func (f *fakeProducts) Register(_ context.Context, in product.RegisterInput) (product.Registered, error) {
	f.registered = append(f.registered, in)
	return product.Registered{ID: "p1", ChainProductID: "1", Status: "Created", Name: &in.Name}, nil
}

func (f *fakeProducts) RegisterBatch(_ context.Context, in product.RegisterBatchInput) (product.RegisteredBatch, error) {
	f.batches = append(f.batches, in)
	return product.RegisteredBatch{Count: int(in.Count), Items: []product.BatchItem{}}, nil
}

func (f *fakeProducts) Transfer(_ context.Context, id, to string) (product.TransferResult, error) {
	return product.TransferResult{ChainProductID: id, ToAddress: to}, nil
}

func (f *fakeProducts) Consume(_ context.Context, id, _ string) (product.ConsumeResult, error) {
	f.consumed = append(f.consumed, id)
	return product.ConsumeResult{ChainProductID: id, Status: "Consumed"}, nil
}

func (f *fakeProducts) History(_ context.Context, id string) (product.History, error) {
	return product.History{}, apperr.NotFound("Product " + id + " not found")
}

func (f *fakeProducts) ByChainID(_ context.Context, id string) (product.Detail, error) {
	return product.Detail{}, apperr.NotFound("Product " + id + " not found")
}

type fakeVerify struct{ ids []string }

func (f *fakeVerify) Verify(_ context.Context, id string) (verify.Result, error) {
	f.ids = append(f.ids, id)
	if id != "1" {
		return verify.Result{}, apperr.NotFound("Product " + id + " not found")
	}
	return verify.Result{ProductID: "p1", ChainProductID: "1", Status: "Created", Authenticity: "Authentic", Source: "db"}, nil
}

type fakeRoleStore struct{}

func (fakeRoleStore) ListRoleBindings(context.Context) ([]db.RoleBinding, error) { return nil, nil }

func (fakeRoleStore) ListRoleBindingsForAddress(context.Context, string) ([]db.RoleBinding, error) {
	return nil, nil
}

func (fakeRoleStore) UpsertRoleBinding(_ context.Context, arg db.UpsertRoleBindingParams) (db.RoleBinding, error) {
	return db.RoleBinding{ID: arg.ID, Address: arg.Address, Role: arg.Role}, nil
}

func (fakeRoleStore) DeleteRoleBinding(context.Context, db.DeleteRoleBindingParams) (int64, error) {
	return 0, nil
}

type fakeReports struct{}

func (fakeReports) Report(_ context.Context, in reports.Input) (reports.Created, error) {
	return reports.Created{ID: "r1", ChainProductID: strings.TrimSpace(in.ChainProductID)}, nil
}

func (fakeReports) List(context.Context, float64) (reports.List, error) {
	return reports.List{Items: []reports.Item{}}, nil
}

type fakeAnalytics struct{}

func (fakeAnalytics) Increment(_ context.Context, name string) (any, error) {
	return analytics.Counter{Name: name, Value: "1"}, nil
}

func (fakeAnalytics) Snapshot(context.Context) (analytics.Snapshot, error) {
	return analytics.Snapshot{Counters: []analytics.Counter{}}, nil
}

type harness struct {
	handler  http.Handler
	products *fakeProducts
	verify   *fakeVerify
}

func newHarness(t *testing.T, mutate func(*config.Config, *Deps)) harness {
	t.Helper()
	cfg, err := config.Load(func(key string) (string, bool) {
		switch key {
		case "DATABASE_URL":
			return "postgresql://localhost/test", true
		case "API_WRITE_KEY":
			return writeKey, true
		}
		return "", false
	})
	if err != nil {
		t.Fatal(err)
	}
	h := harness{products: &fakeProducts{}, verify: &fakeVerify{}}
	deps := Deps{
		Log:       slog.New(slog.NewTextHandler(io.Discard, nil)),
		DB:        fakeDB{},
		Network:   chain.NewNetwork(31337, "", chain.LoadDeployments(filepath.Join(t.TempDir(), "none.json"))),
		Products:  h.products,
		Verify:    h.verify,
		Roles:     roles.NewService(fakeRoleStore{}),
		Reports:   fakeReports{},
		Analytics: fakeAnalytics{},
		EIP712:    chain.NewEIP712("0x5FbDB2315678afecb367f032d93F642f64180aa3"),
		ReloadKeys: func(context.Context) ([]string, error) {
			return []string{"0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"}, nil
		},
	}
	if mutate != nil {
		mutate(&cfg, &deps)
	}
	deps.Config = cfg
	h.handler = New(deps)
	return h
}

type call struct {
	method      string
	target      string
	body        string
	contentType string
	headers     map[string]string
	noKey       bool
}

func (h harness) do(t *testing.T, c call) *httptest.ResponseRecorder {
	t.Helper()
	method := c.method
	if method == "" {
		method = http.MethodGet
	}
	var body io.Reader
	if c.body != "" {
		body = strings.NewReader(c.body)
	}
	req := httptest.NewRequest(method, c.target, body)
	if c.body != "" {
		ct := c.contentType
		if ct == "" {
			ct = "application/json"
		}
		req.Header.Set("Content-Type", ct)
	} else if c.contentType != "" {
		req.Header.Set("Content-Type", c.contentType)
	}
	if !c.noKey {
		req.Header.Set("X-Api-Key", writeKey)
	}
	for k, v := range c.headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	h.handler.ServeHTTP(rec, req)
	return rec
}

func expect(t *testing.T, rec *httptest.ResponseRecorder, status int, body string) {
	t.Helper()
	if rec.Code != status || rec.Body.String() != body {
		t.Fatalf("got %d %s\nwant %d %s", rec.Code, rec.Body.String(), status, body)
	}
}

// Expected bodies below were captured from the previous NestJS API.
func TestWireCompatibility(t *testing.T) {
	h := newHarness(t, nil)
	nameErrors := `"name must be longer than or equal to 1 characters","name must be a string"`
	reportErrors := `{"message":["chainProductId must be longer than or equal to 1 characters","chainProductId must be a string"],"error":"Bad Request","statusCode":400}`
	cases := []struct {
		name   string
		call   call
		status int
		body   string
	}{
		{"truncated json", call{method: "POST", target: "/v1/products", body: `{"name":`}, 400,
			`{"message":"Unexpected end of JSON input","error":"Bad Request","statusCode":400}`},
		{"primitive json", call{method: "POST", target: "/v1/products", body: `"str"`}, 400,
			`{"message":"Unexpected token '\"', \"\"str\"\" is not valid JSON","error":"Bad Request","statusCode":400}`},
		{"array json", call{method: "POST", target: "/v1/products", body: `[1]`}, 400,
			`{"message":["property 0 should not exist",` + nameErrors + `],"error":"Bad Request","statusCode":400}`},
		{"empty body", call{method: "POST", target: "/v1/reports/counterfeit", contentType: "application/json"}, 400, reportErrors},
		{"text body ignored", call{method: "POST", target: "/v1/reports/counterfeit", body: `{"chainProductId":"1"}`, contentType: "text/plain"}, 400, reportErrors},
		{"vendor json ignored", call{method: "POST", target: "/v1/reports/counterfeit", body: `{"chainProductId":"1"}`, contentType: "application/vnd.api+json"}, 400, reportErrors},
		{"json with charset", call{method: "POST", target: "/v1/reports/counterfeit", body: `{"chainProductId":" 9 "}`, contentType: "application/json; charset=utf-8"}, 201,
			`{"id":"r1","chainProductId":"9","createdAt":""}`},
		{"too large", call{method: "POST", target: "/v1/reports/counterfeit", body: `{"chainProductId":"` + strings.Repeat("a", 110_000) + `"}`}, 413,
			`{"statusCode":413,"message":"request entity too large"}`},
		{"unknown route keeps query", call{target: "/v1/nope?x=1"}, 404,
			`{"message":"Cannot GET /v1/nope?x=1","error":"Not Found","statusCode":404}`},
		{"root", call{target: "/"}, 404, `{"message":"Cannot GET /","error":"Not Found","statusCode":404}`},
		{"wrong method", call{method: "PUT", target: "/v1/health"}, 404,
			`{"message":"Cannot PUT /v1/health","error":"Not Found","statusCode":404}`},
		{"double slash", call{target: "/v1//health/ready"}, 404,
			`{"message":"Cannot GET /v1//health/ready","error":"Not Found","statusCode":404}`},
		{"trailing slash", call{target: "/v1/health/ready/"}, 200, `{"status":"ready"}`},
		{"case insensitive", call{target: "/V1/HEALTH/READY"}, 200, `{"status":"ready"}`},
		{"encoded param", call{target: "/v1/verify/a%20b"}, 404,
			`{"message":"Product a b not found","error":"Not Found","statusCode":404}`},
		{"analytics ignores body", call{method: "POST", target: "/v1/analytics/events/scan", body: `{"x":1}`, noKey: true}, 201,
			`{"name":"scan_ok","value":"1"}`},
		{"numeric role", call{method: "POST", target: "/v1/roles", body: `{"address":"0xabc","role":5}`}, 400,
			`{"message":"Unknown role: 5","error":"Bad Request","statusCode":400}`},
		{"role without body", call{method: "POST", target: "/v1/roles"}, 400,
			`{"message":"address must be a hex wallet address","error":"Bad Request","statusCode":400}`},
		{"delete unknown role", call{method: "DELETE", target: "/v1/roles/0xabc/Nope"}, 404,
			`{"message":"Role binding not found","error":"Not Found","statusCode":404}`},
		{"labels without batch", call{target: "/v1/products/labels.pdf?batch=%20"}, 400,
			`{"message":"batch query parameter is required","error":"Bad Request","statusCode":400}`},
		{"product not found", call{target: "/v1/products/999"}, 404,
			`{"message":"Product 999 not found","error":"Not Found","statusCode":404}`},
		{"meta sign validation", call{method: "POST", target: "/v1/meta/eip712/metadata/sign", body: `{"nonce":-1,"chainId":1.5}`}, 400,
			`{"message":["metadataHash must be longer than or equal to 66 characters","metadataHash must be a string","manufacturer must be longer than or equal to 42 characters","manufacturer must be a string","privateKey must be longer than or equal to 66 characters","privateKey must be a string","nonce must not be less than 0","chainId must be an integer number"],"error":"Bad Request","statusCode":400}`},
		{"meta consume validation", call{method: "POST", target: "/v1/meta/consume", body: `{"productId":5,"deadline":"x"}`}, 400,
			`{"message":["secret must be longer than or equal to 66 characters","secret must be a string","consumer must be longer than or equal to 42 characters","consumer must be a string","deadline must not be less than 1","deadline must be an integer number","signature must be longer than or equal to 10 characters","signature must be a string"],"error":"Bad Request","statusCode":400}`},
		{"whitelist before fields", call{method: "POST", target: "/v1/products", body: `{"name":"x","extra":1,"batch":{"a":1},"physicalId":[1],"0":2}`}, 400,
			`{"message":["property 0 should not exist","property extra should not exist","physicalId must be a string"],"error":"Bad Request","statusCode":400}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := h.do(t, tc.call)
			expect(t, rec, tc.status, tc.body)
			if ct := rec.Header().Get("Content-Type"); ct != "application/json; charset=utf-8" {
				t.Fatalf("content-type = %q", ct)
			}
		})
	}
}

func TestValidationMatchesClassValidator(t *testing.T) {
	cases := []struct {
		name string
		path string
		body string
		want string
	}{
		{"register empty", "/v1/products", `{}`, `["name must be longer than or equal to 1 characters","name must be a string"]`},
		{"whitelist order", "/v1/products", `{"1":0,"2":1,"name":"","extra":1,"zzz":true}`,
			`["property 1 should not exist","property 2 should not exist","property extra should not exist","property zzz should not exist","name must be longer than or equal to 1 characters"]`},
		{"implicit string conversion", "/v1/products", `{"name":5,"batch":{"a":1},"physicalId":[1]}`, `["physicalId must be a string"]`},
		{"null is missing", "/v1/products", `{"name":null,"batch":null}`, `["name must be longer than or equal to 1 characters","name must be a string"]`},
		{"batch empty", "/v1/products/batch", `{}`,
			`["name must be longer than or equal to 1 characters","name must be a string","count must not be greater than 100","count must not be less than 1","count must be an integer number"]`},
		{"count fraction", "/v1/products/batch", `{"name":"x","count":"5.5"}`, `["count must be an integer number"]`},
		{"count nan", "/v1/products/batch", `{"name":"x","count":"abc"}`,
			`["count must not be greater than 100","count must not be less than 1","count must be an integer number"]`},
		{"count zero", "/v1/products/batch", `{"name":"x","count":0}`, `["count must not be less than 1"]`},
		{"count too big", "/v1/products/batch", `{"name":"x","count":101}`, `["count must not be greater than 100"]`},
		{"count empty string", "/v1/products/batch", `{"name":"x","count":""}`, `["count must not be less than 1"]`},
		{"count array", "/v1/products/batch", `{"name":"x","count":[3]}`,
			`["count must not be greater than 100","count must not be less than 1","count must be an integer number"]`},
		{"count null", "/v1/products/batch", `{"name":"x","count":null}`,
			`["count must not be greater than 100","count must not be less than 1","count must be an integer number"]`},
		{"short address", "/v1/products/1/transfer", `{"toAddress":"0x12"}`, `["toAddress must be longer than or equal to 42 characters"]`},
	}
	h := newHarness(t, nil)
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rec := h.do(t, call{method: "POST", target: tc.path, body: tc.body})
			expect(t, rec, 400, `{"message":`+tc.want+`,"error":"Bad Request","statusCode":400}`)
		})
	}
}

func TestImplicitConversionReachesServices(t *testing.T) {
	h := newHarness(t, nil)
	expectStatus := func(rec *httptest.ResponseRecorder, want int) {
		t.Helper()
		if rec.Code != want {
			t.Fatalf("status %d: %s", rec.Code, rec.Body.String())
		}
	}
	expectStatus(h.do(t, call{method: "POST", target: "/v1/products", body: `{"name":1e21,"batch":{"a":1}}`}), 201)
	if in := h.products.registered[0]; in.Name != "1e+21" || *in.Batch != "[object Object]" {
		t.Fatalf("register input = %+v", in)
	}
	expectStatus(h.do(t, call{method: "POST", target: "/v1/products/batch", body: `{"name":"x","count":"5"}`}), 201)
	expectStatus(h.do(t, call{method: "POST", target: "/v1/products/batch", body: `{"name":"x","count":true}`}), 201)
	if h.products.batches[0].Count != 5 || h.products.batches[1].Count != 1 {
		t.Fatalf("batch inputs = %+v", h.products.batches)
	}
	secret := strings.Repeat("😀", 66)
	expectStatus(h.do(t, call{method: "POST", target: "/v1/products/7/consume", body: `{"secret":"` + secret + `"}`}), 201)

	h.do(t, call{target: "/v1/products?owner=A&owner=B&page=abc&limit=5"})
	q := h.products.list[0]
	if q.Owner != "A,B" || !math.IsNaN(q.Page) || q.Limit != 5 {
		t.Fatalf("list query = %+v", q)
	}
}

func TestWriteKeyGuard(t *testing.T) {
	h := newHarness(t, nil)
	body := `{"name":"x"}`
	unauthorized := `{"message":"Invalid or missing write API key","error":"Unauthorized","statusCode":401}`

	expect(t, h.do(t, call{method: "POST", target: "/v1/products", body: body, noKey: true}), 401, unauthorized)
	expect(t, h.do(t, call{method: "POST", target: "/v1/products", body: body, noKey: true,
		headers: map[string]string{"X-Api-Key": "wrong"}}), 401, unauthorized)
	if rec := h.do(t, call{method: "POST", target: "/v1/products", body: body, noKey: true,
		headers: map[string]string{"Authorization": "bearer   " + writeKey}}); rec.Code != 201 {
		t.Fatalf("bearer token rejected: %d %s", rec.Code, rec.Body.String())
	}
	if rec := h.do(t, call{target: "/v1/products", noKey: true}); rec.Code != 200 {
		t.Fatalf("GET must not need a key: %d", rec.Code)
	}
	if rec := h.do(t, call{method: "POST", target: "/v1/reports/counterfeit", body: `{"chainProductId":"1"}`, noKey: true}); rec.Code != 201 {
		t.Fatalf("public route rejected: %d", rec.Code)
	}

	prod := newHarness(t, func(cfg *config.Config, _ *Deps) {
		cfg.APIWriteKey = ""
		cfg.Env = "production"
	})
	expect(t, prod.do(t, call{method: "POST", target: "/v1/products", body: body, noKey: true}), 401,
		`{"message":"API_WRITE_KEY is required in production","error":"Unauthorized","statusCode":401}`)

	dev := newHarness(t, func(cfg *config.Config, _ *Deps) { cfg.APIWriteKey = "" })
	if rec := dev.do(t, call{method: "POST", target: "/v1/products", body: body, noKey: true}); rec.Code != 201 {
		t.Fatalf("development without a key should allow writes: %d", rec.Code)
	}
}

func TestRateLimitOnVerifyAndConsume(t *testing.T) {
	h := newHarness(t, func(cfg *config.Config, _ *Deps) {
		cfg.VerifyRateLimit = 2
		cfg.ConsumeRateLimit = 1
		cfg.RateLimitWindow = time.Minute
	})
	for range 2 {
		if rec := h.do(t, call{target: "/v1/verify/1"}); rec.Code != 200 {
			t.Fatalf("status %d", rec.Code)
		}
	}
	expect(t, h.do(t, call{target: "/v1/verify/1"}), 429, `{"statusCode":429,"message":"Too Many Requests"}`)

	secret := `{"secret":"0x` + strings.Repeat("11", 32) + `"}`
	h.do(t, call{method: "POST", target: "/v1/products/7/consume", body: secret})
	expect(t, h.do(t, call{method: "POST", target: "/v1/products/7/consume", body: secret}), 429,
		`{"statusCode":429,"message":"Too Many Requests"}`)

	if rec := h.do(t, call{target: "/v1/products"}); rec.Code != 200 {
		t.Fatalf("unlimited routes must not be throttled: %d", rec.Code)
	}
}

func TestHeadersCORSAndETag(t *testing.T) {
	h := newHarness(t, nil)
	rec := h.do(t, call{target: "/v1/flags"})
	if rec.Code != 200 {
		t.Fatalf("status %d", rec.Code)
	}
	for header, want := range map[string]string{
		"Access-Control-Allow-Origin": "*",
		"X-Frame-Options":             "SAMEORIGIN",
		"X-Content-Type-Options":      "nosniff",
		"Referrer-Policy":             "no-referrer",
	} {
		if got := rec.Header().Get(header); got != want {
			t.Errorf("%s = %q, want %q", header, got, want)
		}
	}
	if rec.Body.String() != `{"reportsEnabled":true,"scanEnabled":true,"labelsPdfEnabled":true,"analyticsEnabled":true,"eip712MetadataEnabled":true,"metaTxConsumeEnabled":true,"subgraphPreferred":false}` {
		t.Fatalf("flags = %s", rec.Body.String())
	}
	etag := rec.Header().Get("ETag")
	if !strings.HasPrefix(etag, `W/"`) {
		t.Fatalf("etag = %q", etag)
	}
	notModified := h.do(t, call{target: "/v1/flags", headers: map[string]string{"If-None-Match": etag}})
	if notModified.Code != http.StatusNotModified || notModified.Body.Len() != 0 {
		t.Fatalf("conditional GET: %d %q", notModified.Code, notModified.Body.String())
	}

	head := h.do(t, call{method: "HEAD", target: "/v1/health/ready"})
	if head.Code != 200 || head.Body.Len() != 0 {
		t.Fatalf("HEAD: %d %q", head.Code, head.Body.String())
	}

	pre := h.do(t, call{method: "OPTIONS", target: "/v1/products", noKey: true, headers: map[string]string{
		"Origin":                         "http://x.test",
		"Access-Control-Request-Method":  "POST",
		"Access-Control-Request-Headers": "x-api-key,content-type",
	}})
	if pre.Code != http.StatusNoContent ||
		pre.Header().Get("Access-Control-Allow-Methods") != "GET,HEAD,PUT,PATCH,POST,DELETE" ||
		pre.Header().Get("Access-Control-Allow-Headers") != "x-api-key,content-type" {
		t.Fatalf("preflight: %d %v", pre.Code, pre.Header())
	}
}

func TestHealthAndReadiness(t *testing.T) {
	down := newHarness(t, func(_ *config.Config, d *Deps) { d.DB = fakeDB{err: errors.New("dial tcp: refused")} })
	rec := down.do(t, call{target: "/v1/health"})
	if rec.Code != 200 || !strings.Contains(rec.Body.String(), `"status":"degraded"`) ||
		!strings.Contains(rec.Body.String(), `"database":"down"`) {
		t.Fatalf("health: %d %s", rec.Code, rec.Body.String())
	}
	expect(t, down.do(t, call{target: "/v1/health/ready"}), 503,
		`{"message":"Database is not reachable","error":"Service Unavailable","statusCode":503}`)

	up := newHarness(t, nil)
	rec = up.do(t, call{target: "/v1/health"})
	if !strings.HasPrefix(rec.Body.String(), `{"status":"ok","version":"1.1.0","api":"v1","checks":{"api":"ok","database":"ok","rpc":"missing","ipfs":"missing"},"timestamp":"`) {
		t.Fatalf("health: %s", rec.Body.String())
	}
	expect(t, up.do(t, call{target: "/v1/chains"}), 200,
		`{"activeChainId":31337,"registryAddress":"","active":null,"deployments":{}}`)
}

func TestFeatureFlagsGateEndpoints(t *testing.T) {
	h := newHarness(t, func(cfg *config.Config, _ *Deps) {
		cfg.Flags.LabelsPDFEnabled = false
		cfg.Flags.EIP712MetadataEnabled = false
	})
	expect(t, h.do(t, call{target: "/v1/products/labels.pdf?batch=B-1"}), 503,
		`{"message":"Labels PDF export is disabled","error":"Service Unavailable","statusCode":503}`)
	body := `{"metadataHash":"0x8c1502efdfb3eb924223e28a805f627a0373abad66ef963463dfb1d86bc859cb","manufacturer":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266","privateKey":"0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80"}`
	expect(t, h.do(t, call{method: "POST", target: "/v1/meta/eip712/metadata/sign", body: body}), 503,
		`{"message":"EIP-712 metadata signing disabled","error":"Service Unavailable","statusCode":503}`)
}

func TestLabelsPDFResponse(t *testing.T) {
	h := newHarness(t, nil)
	rec := h.do(t, call{target: "/v1/products/labels.pdf?batch=%20B-1%20"})
	if rec.Code != 200 || rec.Body.String() != "%PDF-1.4" {
		t.Fatalf("labels: %d %q", rec.Code, rec.Body.String())
	}
	if rec.Header().Get("Content-Type") != "application/pdf" ||
		rec.Header().Get("Content-Disposition") != `attachment; filename="labels-B-1.pdf"` {
		t.Fatalf("headers = %v", rec.Header())
	}
}

func TestMetaEndpoints(t *testing.T) {
	h := newHarness(t, nil)
	sign := `{"metadataHash":"0x8c1502efdfb3eb924223e28a805f627a0373abad66ef963463dfb1d86bc859cb","manufacturer":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266","privateKey":"0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80","nonce":1}`
	expect(t, h.do(t, call{method: "POST", target: "/v1/meta/eip712/metadata/sign", body: sign}), 201,
		`{"signature":"0xa4f2f505b78216b264c2bdd8650320ea0ae1ff27ca2a7601e763de0c353dd5927977f73968367f220a2d9ccce0123980078c94468e78ba6eca9aad3503d218a91b","chainId":31337,"nonce":1}`)

	verifyBody := `{"metadataHash":"0x8c1502efdfb3eb924223e28a805f627a0373abad66ef963463dfb1d86bc859cb","manufacturer":"0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266","signature":"0xa4f2f505b78216b264c2bdd8650320ea0ae1ff27ca2a7601e763de0c353dd5927977f73968367f220a2d9ccce0123980078c94468e78ba6eca9aad3503d218a91b","nonce":1}`
	expect(t, h.do(t, call{method: "POST", target: "/v1/meta/eip712/metadata/verify", body: verifyBody}), 201,
		`{"valid":true,"recovered":"0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266","chainId":31337}`)

	consume := `{"productId":"7","secret":"0x1111111111111111111111111111111111111111111111111111111111111111","consumer":"0x70997970C51812dc3A010C7d01b50e0d17dc79C8","deadline":4102444800,"signature":"0xfa7c89645034c292b5d2d910a72d3ff09927b2aeb2e6c96dc6633e5e4252755f3fad55d1c4f39f627c91b162c8350abe0aeb2b3ba7bb4de5659316bd48c6d19d1b"}`
	if rec := h.do(t, call{method: "POST", target: "/v1/meta/consume", body: consume}); rec.Code != 201 {
		t.Fatalf("meta consume: %d %s", rec.Code, rec.Body.String())
	}
	if len(h.products.consumed) != 1 || h.products.consumed[0] != "7" {
		t.Fatalf("consumed = %v", h.products.consumed)
	}

	wrongSigner := strings.Replace(consume, "0x70997970C51812dc3A010C7d01b50e0d17dc79C8", "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC", 1)
	expect(t, h.do(t, call{method: "POST", target: "/v1/meta/consume", body: wrongSigner}), 400,
		`{"message":"Invalid consume authorization signature","error":"Bad Request","statusCode":400}`)

	expired := strings.Replace(consume, "4102444800", "1", 1)
	expect(t, h.do(t, call{method: "POST", target: "/v1/meta/consume", body: expired}), 400,
		`{"message":"Authorization deadline expired","error":"Bad Request","statusCode":400}`)
}

func TestAdminReload(t *testing.T) {
	h := newHarness(t, nil)
	expect(t, h.do(t, call{method: "POST", target: "/v1/admin/relayer-keys/reload"}), 201,
		`{"reloaded":true,"activeAddresses":["0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266"]}`)
	if rec := h.do(t, call{method: "POST", target: "/v1/admin/relayer-keys/reload", noKey: true}); rec.Code != 401 {
		t.Fatalf("reload without key: %d", rec.Code)
	}
}

func TestInternalErrorsAreHidden(t *testing.T) {
	h := newHarness(t, func(_ *config.Config, d *Deps) {
		d.ReloadKeys = func(context.Context) ([]string, error) { return nil, pgx.ErrTxClosed }
	})
	expect(t, h.do(t, call{method: "POST", target: "/v1/admin/relayer-keys/reload"}), 500,
		`{"statusCode":500,"message":"Internal server error"}`)
}
