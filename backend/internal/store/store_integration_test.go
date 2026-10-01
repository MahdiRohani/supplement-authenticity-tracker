package store_test

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/analytics"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/audit"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/ipfs"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/product"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/reports"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/roles"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

// openSchema opens TEST_DATABASE_URL on a throwaway schema that is dropped
// when the test ends. Tests are skipped when the variable is unset.
func openSchema(t *testing.T) (*store.Store, string) {
	t.Helper()
	base := os.Getenv("TEST_DATABASE_URL")
	if base == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	schema := fmt.Sprintf("it_%d", time.Now().UnixNano())
	u, err := url.Parse(base)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("schema", schema)
	u.RawQuery = q.Encode()

	ctx := context.Background()
	st, err := store.Open(ctx, u.String())
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = st.Pool.Exec(context.Background(), "DROP SCHEMA IF EXISTS "+pgx.Identifier{schema}.Sanitize()+" CASCADE")
		st.Close()
	})
	return st, schema
}

func migrated(t *testing.T) *store.Store {
	t.Helper()
	st, _ := openSchema(t)
	if err := st.Migrate(context.Background(), quiet, 10*time.Second); err != nil {
		t.Fatal(err)
	}
	return st
}

func TestMigrateIsIdempotent(t *testing.T) {
	st := migrated(t)
	if err := st.Migrate(context.Background(), quiet, time.Second); err != nil {
		t.Fatalf("second migrate: %v", err)
	}
	if err := st.Ping(context.Background()); err != nil {
		t.Fatal(err)
	}
}

func TestMigrateAdoptsPrismaSchema(t *testing.T) {
	st, schema := openSchema(t)
	ctx := context.Background()
	if _, err := st.Pool.Exec(ctx, `CREATE SCHEMA `+pgx.Identifier{schema}.Sanitize()); err != nil {
		t.Fatal(err)
	}
	// Later migrations extend RoleBinding, which every Prisma schema has.
	if _, err := st.Pool.Exec(ctx, `CREATE TABLE "Product" ("id" TEXT PRIMARY KEY); CREATE TABLE "RoleBinding" ("id" TEXT PRIMARY KEY)`); err != nil {
		t.Fatal(err)
	}
	if err := st.Migrate(ctx, quiet, time.Second); err != nil {
		t.Fatal(err)
	}
	var auditTable, batchTable *string
	if err := st.Pool.QueryRow(ctx, `SELECT to_regclass('"AuditLog"')::text, to_regclass('"Batch"')::text`).Scan(&auditTable, &batchTable); err != nil {
		t.Fatal(err)
	}
	if auditTable != nil {
		t.Fatal("the initial migration must be skipped for an existing Prisma schema")
	}
	if batchTable == nil {
		t.Fatal("migrations after the baseline must still apply")
	}
	var baseline bool
	if err := st.Pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM goose_db_version WHERE version_id = 1)`).Scan(&baseline); err != nil {
		t.Fatal(err)
	}
	if !baseline {
		t.Fatal("baseline version 1 not recorded")
	}
}

type offlineRelayer struct{}

var errOffline = errors.New("chain offline")

func (offlineRelayer) RegisterUnit(context.Context, chain.RegisterUnitInput) (chain.RegisteredUnit, error) {
	return chain.RegisteredUnit{}, errOffline
}

func (offlineRelayer) RegisterBatch(context.Context, chain.RegisterBatchInput) (chain.RegisteredBatch, error) {
	return chain.RegisteredBatch{}, errOffline
}

func (offlineRelayer) TransferOwnership(context.Context, string, string, string) (chain.TxResult, error) {
	return chain.TxResult{}, errOffline
}

func (offlineRelayer) Consume(context.Context, string, string, string) (chain.TxResult, error) {
	return chain.TxResult{}, errOffline
}

type noCache struct{}

func (noCache) Delete(string) {}

func TestProductQueries(t *testing.T) {
	st := migrated(t)
	ctx := context.Background()
	pinner := ipfs.New("http://127.0.0.1:1", "", true, quiet)
	svc := product.NewService(st, offlineRelayer{}, pinner, audit.NewRecorder(st), noCache{}, quiet)

	batch := "B_1"
	first, err := svc.Register(ctx, product.RegisterInput{Name: "Vitamin 100%", Batch: &batch})
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(2 * time.Millisecond)
	if _, err := svc.Register(ctx, product.RegisterInput{Name: "Zinc", Batch: &batch}); err != nil {
		t.Fatal(err)
	}
	time.Sleep(2 * time.Millisecond)
	if _, err := svc.Register(ctx, product.RegisterInput{Name: "Magnesium"}); err != nil {
		t.Fatal(err)
	}

	all, err := svc.List(ctx, product.ListQuery{Page: math.NaN(), Limit: math.NaN()})
	if err != nil {
		t.Fatal(err)
	}
	if all.Total != 3 || *all.Items[0].Name != "Magnesium" {
		t.Fatalf("list = %+v", all)
	}

	literal, _ := svc.List(ctx, product.ListQuery{Q: "%", Page: math.NaN(), Limit: math.NaN()})
	if literal.Total != 1 || *literal.Items[0].Name != "Vitamin 100%" {
		t.Fatalf("q=%% should match the literal percent sign, got %+v", literal)
	}
	underscore, _ := svc.List(ctx, product.ListQuery{Q: "b_1", Page: math.NaN(), Limit: math.NaN()})
	if underscore.Total != 2 {
		t.Fatalf("batch search is case-insensitive, got %d", underscore.Total)
	}
	owner, _ := svc.List(ctx, product.ListQuery{Owner: strings.ToUpper(product.DefaultManufacturer), Status: "Created", Page: 2, Limit: 2})
	if owner.Total != 3 || owner.TotalPages != 2 || len(owner.Items) != 1 {
		t.Fatalf("owner page = %+v", owner)
	}

	byID, err := svc.History(ctx, first.ID)
	if err != nil || byID.ChainProductID != first.ChainProductID || len(byID.Events) != 0 {
		t.Fatalf("history by row id = %+v, %v", byID, err)
	}
	detail, err := svc.ByChainID(ctx, first.ChainProductID)
	if err != nil || detail.ID != first.ID || !strings.HasSuffix(detail.CreatedAt, "Z") {
		t.Fatalf("detail = %+v, %v", detail, err)
	}

	pdf, err := svc.LabelsPDF(ctx, batch)
	if err != nil || !strings.Contains(string(pdf), "Units: 2") {
		t.Fatalf("labels = %v", err)
	}

	var audits int
	if err := st.Pool.QueryRow(ctx, `SELECT count(*) FROM "AuditLog" WHERE action = 'product.register'`).Scan(&audits); err != nil {
		t.Fatal(err)
	}
	if audits != 3 {
		t.Fatalf("audit rows = %d", audits)
	}
}

func TestRolesReportsAndAnalytics(t *testing.T) {
	st := migrated(t)
	ctx := context.Background()

	roleSvc := roles.NewService(st)
	a, err := roleSvc.Bind(ctx, "0xABC", "Distributor")
	if err != nil {
		t.Fatal(err)
	}
	b, err := roleSvc.Bind(ctx, "0xabc", "Distributor")
	if err != nil || a.ID != b.ID || a.Address != "0xabc" {
		t.Fatalf("bind must be idempotent: %+v %+v %v", a, b, err)
	}
	if _, err := roleSvc.Bind(ctx, "0xabc", "Pharmacy"); err != nil {
		t.Fatal(err)
	}
	list, _ := roleSvc.ListForAddress(ctx, "0XABC")
	if len(list) != 2 || list[0].Role != "Distributor" {
		t.Fatalf("roles = %+v", list)
	}
	if _, err := roleSvc.Unbind(ctx, "0xabc", "Distributor"); err != nil {
		t.Fatal(err)
	}
	if _, err := roleSvc.Unbind(ctx, "0xabc", "Distributor"); err == nil {
		t.Fatal("second unbind should be 404")
	}

	reportSvc := reports.NewService(st, audit.NewRecorder(st), true)
	note := "  fake seal "
	created, err := reportSvc.Report(ctx, reports.Input{ChainProductID: " 7 ", Note: &note})
	if err != nil || created.ChainProductID != "7" {
		t.Fatalf("report = %+v, %v", created, err)
	}
	listed, _ := reportSvc.List(ctx, math.NaN())
	if len(listed.Items) != 1 || *listed.Items[0].Note != "fake seal" {
		t.Fatalf("reports = %+v", listed)
	}

	stats := analytics.NewService(st, true)
	for range 2 {
		if _, err := stats.Increment(ctx, "verify_ok"); err != nil {
			t.Fatal(err)
		}
	}
	snap, err := stats.Snapshot(ctx)
	if err != nil || len(snap.Counters) != 1 || snap.Counters[0].Value != "2" {
		t.Fatalf("snapshot = %+v, %v", snap, err)
	}
}

func TestNotFoundIsDetected(t *testing.T) {
	st := migrated(t)
	_, err := st.FindProduct(context.Background(), "missing")
	if !store.IsNotFound(err) {
		t.Fatalf("err = %v", err)
	}
}
