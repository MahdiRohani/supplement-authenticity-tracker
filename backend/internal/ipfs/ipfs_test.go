package ipfs

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

// Hashes produced by the previous implementation for the same bytes.
const (
	fixtureBody        = `{"name":"Vitamin <D3> & \"Co\"","batch":null,"schemaVersion":1}`
	fixtureContentHash = "0xef7617ce52b2b95a9d5502f30cc30409830104fbb3bcb7fc623a73647ba31afb"
	fixtureStubCID     = "bafyef7617ce52b2b95a9d5502f30cc30409830104fbb3bcb7"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

func unreachable(t *testing.T) string {
	t.Helper()
	srv := httptest.NewServer(http.NotFoundHandler())
	url := srv.URL
	srv.Close()
	return url
}

func TestPinFallsBackToStubCID(t *testing.T) {
	c := New(unreachable(t), "https://gw.test/ipfs/", true, quiet)
	pinned, err := c.PinJSON(context.Background(), []byte(fixtureBody))
	if err != nil {
		t.Fatal(err)
	}
	if pinned.CID != fixtureStubCID || pinned.ContentHash != fixtureContentHash || pinned.Pinned {
		t.Fatalf("pinned = %+v", pinned)
	}
	if pinned.GatewayURL != "https://gw.test/ipfs/"+fixtureStubCID {
		t.Fatalf("gateway = %s", pinned.GatewayURL)
	}
	if got := c.ResolveJSON(context.Background(), fixtureStubCID); string(got) != fixtureBody {
		t.Fatalf("stubbed metadata should resolve locally, got %s", got)
	}
}

func TestPinWithoutStubIsServiceUnavailable(t *testing.T) {
	c := New(unreachable(t), "", false, quiet)
	_, err := c.PinJSON(context.Background(), []byte(fixtureBody))
	var appErr *apperr.Error
	if !errors.As(err, &appErr) || appErr.Status != http.StatusServiceUnavailable {
		t.Fatalf("err = %v", err)
	}
}

func TestPinUsesKuboAddAndGatewayResolve(t *testing.T) {
	var uploaded string
	kubo := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/api/v0/add" || r.URL.Query().Get("pin") != "true" {
			http.NotFound(w, r)
			return
		}
		file, _, err := r.FormFile("file")
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		b, _ := io.ReadAll(file)
		uploaded = string(b)
		_, _ = io.WriteString(w, `{"Name":"metadata.json","Hash":"bafyreal","Size":"10"}`)
	}))
	defer kubo.Close()
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/ipfs/bafyremote":
			_, _ = io.WriteString(w, `{"name":"remote"}`)
		case "/ipfs/bafybroken":
			_, _ = io.WriteString(w, `not json`)
		default:
			http.NotFound(w, r)
		}
	}))
	defer gateway.Close()

	c := New(kubo.URL+"/", gateway.URL+"/ipfs", false, quiet)
	pinned, err := c.PinJSON(context.Background(), []byte(fixtureBody))
	if err != nil {
		t.Fatal(err)
	}
	if !pinned.Pinned || pinned.CID != "bafyreal" || uploaded != fixtureBody {
		t.Fatalf("pinned = %+v, uploaded = %q", pinned, uploaded)
	}
	if !strings.HasSuffix(pinned.GatewayURL, "/ipfs/bafyreal") {
		t.Fatalf("gateway = %s", pinned.GatewayURL)
	}

	if got := c.ResolveJSON(context.Background(), "bafyremote"); string(got) != `{"name":"remote"}` {
		t.Fatalf("resolve = %s", got)
	}
	if got := c.ResolveJSON(context.Background(), "bafybroken"); got != nil {
		t.Fatalf("invalid JSON should resolve to nil, got %s", got)
	}
	if got := c.ResolveJSON(context.Background(), "bafymissing"); got != nil {
		t.Fatalf("404 should resolve to nil, got %s", got)
	}
}
