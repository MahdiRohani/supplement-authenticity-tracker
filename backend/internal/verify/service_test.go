package verify

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/cache"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

type fakeStore struct {
	product *db.Product
	calls   int
}

func (f *fakeStore) FindProduct(_ context.Context, _ string) (db.Product, error) {
	f.calls++
	if f.product == nil {
		return db.Product{}, pgx.ErrNoRows
	}
	return *f.product, nil
}

type fakeChain struct {
	view  chain.ProductView
	found bool
	calls int
}

func (f *fakeChain) ProductStatus(_ context.Context, _ string) (chain.ProductView, bool) {
	f.calls++
	return f.view, f.found
}

type fakeMetadata struct{}

func (fakeMetadata) ResolveJSON(_ context.Context, _ string) json.RawMessage {
	return json.RawMessage(`{"name":"Vitamin D3","batch":"B-001","expiresAt":"2027-01-01","image":"ipfs://img"}`)
}

func (fakeMetadata) GatewayURL(cid string) string { return "https://ipfs.io/ipfs/" + cid }

func ptr(s string) *string { return &s }

func indexed(status db.ProductStatus) *db.Product {
	return &db.Product{
		ID:             "cuid-1",
		ChainProductID: "42",
		OwnerAddress:   "0xabc",
		Status:         status,
		MetadataCID:    ptr("bafytest"),
		MetadataHash:   ptr("0xhash"),
	}
}

func newService(st Store, reader ChainReader) *Service {
	return NewService(st, reader, fakeMetadata{}, cache.NewTTL[Result](), time.Minute)
}

func TestVerifyReturnsAuthenticityAndCaches(t *testing.T) {
	st := &fakeStore{product: indexed(db.ProductStatusAtPointOfSale)}
	svc := newService(st, nil)

	first, err := svc.Verify(context.Background(), "42")
	if err != nil {
		t.Fatal(err)
	}
	if first.Authenticity != "Authentic" || first.Cached || first.Source != "db" {
		t.Fatalf("first = %+v", first)
	}
	if !strings.Contains(string(first.Metadata), `"Vitamin D3"`) {
		t.Fatalf("metadata = %s", first.Metadata)
	}
	if first.MetadataGatewayURL == nil || *first.MetadataGatewayURL != "https://ipfs.io/ipfs/bafytest" {
		t.Fatalf("gateway = %v", first.MetadataGatewayURL)
	}

	second, err := svc.Verify(context.Background(), "42")
	if err != nil {
		t.Fatal(err)
	}
	if !second.Cached || st.calls != 1 {
		t.Fatalf("second cached=%v, store calls=%d", second.Cached, st.calls)
	}
}

func TestVerifyMarksConsumedWithAntiRefillMessage(t *testing.T) {
	svc := newService(&fakeStore{product: indexed(db.ProductStatusConsumed)}, nil)
	result, err := svc.Verify(context.Background(), "42")
	if err != nil {
		t.Fatal(err)
	}
	if result.Authenticity != "Consumed" || !strings.Contains(strings.ToLower(result.Message), "already consumed") {
		t.Fatalf("result = %+v", result)
	}
}

func TestVerifyFallsBackToChainForNumericIDs(t *testing.T) {
	reader := &fakeChain{found: true, view: chain.ProductView{
		Status:       db.ProductStatusTransferred,
		CurrentOwner: "0xdef",
		MetadataCID:  "bafychain",
	}}
	svc := newService(&fakeStore{}, reader)

	result, err := svc.Verify(context.Background(), "7")
	if err != nil {
		t.Fatal(err)
	}
	if result.Source != "chain" || result.ProductID != "7" || result.MetadataHash != nil || result.Authenticity != "Authentic" {
		t.Fatalf("result = %+v", result)
	}

	_, err = svc.Verify(context.Background(), "not-numeric")
	var appErr *apperr.Error
	if !errors.As(err, &appErr) || appErr.Status != 404 || appErr.Message != "Product not-numeric not found" {
		t.Fatalf("err = %v", err)
	}
	if reader.calls != 1 {
		t.Fatalf("non-numeric ids must not hit the chain, calls=%d", reader.calls)
	}
}

func TestAuthenticityMapping(t *testing.T) {
	cases := map[db.ProductStatus]string{
		db.ProductStatusCreated:       "Authentic",
		db.ProductStatusTransferred:   "Authentic",
		db.ProductStatusAtPointOfSale: "Authentic",
		db.ProductStatusConsumed:      "Consumed",
		db.ProductStatusInvalid:       "Invalid",
		db.ProductStatus("Bogus"):     "NotFound",
	}
	for status, want := range cases {
		if got := Authenticity(status); got != want {
			t.Errorf("Authenticity(%s) = %s, want %s", status, got, want)
		}
	}
}
