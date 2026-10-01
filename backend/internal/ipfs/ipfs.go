// Package ipfs pins product metadata to a Kubo node and resolves it back
// through a gateway.
package ipfs

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"mime/multipart"
	"net/http"
	"net/textproto"
	"strings"
	"sync"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
)

const (
	defaultAPIURL     = "http://127.0.0.1:5001"
	defaultGatewayURL = "https://ipfs.io/ipfs"
	maxMetadataBytes  = 1 << 20
)

// Pinned describes where a metadata document ended up.
type Pinned struct {
	CID         string
	ContentHash string
	GatewayURL  string
	// Pinned is false when the node was unreachable and a deterministic stub
	// CID was issued instead (only allowed outside production).
	Pinned bool
}

type Client struct {
	apiURL     string
	gatewayURL string
	allowStub  bool
	http       *http.Client
	log        *slog.Logger

	mu    sync.RWMutex
	local map[string][]byte
}

// New builds a client. Empty URLs fall back to a local Kubo API and the public
// ipfs.io gateway.
func New(apiURL, gatewayURL string, allowStub bool, log *slog.Logger) *Client {
	if apiURL == "" {
		apiURL = defaultAPIURL
	}
	if gatewayURL == "" {
		gatewayURL = defaultGatewayURL
	}
	return &Client{
		apiURL:     strings.TrimRight(apiURL, "/"),
		gatewayURL: strings.TrimRight(gatewayURL, "/"),
		allowStub:  allowStub,
		http:       &http.Client{Timeout: 15 * time.Second},
		log:        log,
		local:      make(map[string][]byte),
	}
}

func (c *Client) GatewayURL(cid string) string {
	return c.gatewayURL + "/" + cid
}

// PinJSON pins an already-encoded JSON document. ContentHash is the sha256 of
// exactly these bytes.
func (c *Client) PinJSON(ctx context.Context, body []byte) (Pinned, error) {
	sum := sha256.Sum256(body)
	digest := hex.EncodeToString(sum[:])
	contentHash := "0x" + digest

	cid, err := c.add(ctx, body)
	if err == nil {
		c.remember(cid, body)
		return Pinned{CID: cid, ContentHash: contentHash, GatewayURL: c.GatewayURL(cid), Pinned: true}, nil
	}

	c.log.WarnContext(ctx, "IPFS pin failed", "err", err)
	if !c.allowStub {
		return Pinned{}, apperr.ServiceUnavailable("IPFS pin required but unavailable")
	}
	cid = "bafy" + digest[:46]
	c.remember(cid, body)
	return Pinned{CID: cid, ContentHash: contentHash, GatewayURL: c.GatewayURL(cid), Pinned: false}, nil
}

// ResolveJSON returns the metadata document for cid, or nil when it cannot be
// fetched or is not valid JSON.
func (c *Client) ResolveJSON(ctx context.Context, cid string) json.RawMessage {
	c.mu.RLock()
	local, ok := c.local[cid]
	c.mu.RUnlock()
	if ok {
		return json.RawMessage(local)
	}

	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.GatewayURL(cid), nil)
	if err != nil {
		return nil
	}
	resp, err := c.http.Do(req)
	if err != nil {
		c.log.WarnContext(ctx, "IPFS resolve failed", "cid", cid, "err", err)
		return nil
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return nil
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxMetadataBytes))
	if err != nil || !json.Valid(body) {
		c.log.WarnContext(ctx, "IPFS resolve returned unusable body", "cid", cid, "err", err)
		return nil
	}
	return json.RawMessage(body)
}

func (c *Client) add(ctx context.Context, body []byte) (string, error) {
	var form bytes.Buffer
	mw := multipart.NewWriter(&form)
	header := make(textproto.MIMEHeader)
	header.Set("Content-Disposition", `form-data; name="file"; filename="metadata.json"`)
	header.Set("Content-Type", "application/json")
	part, err := mw.CreatePart(header)
	if err != nil {
		return "", err
	}
	if _, err := part.Write(body); err != nil {
		return "", err
	}
	if err := mw.Close(); err != nil {
		return "", err
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.apiURL+"/api/v0/add?pin=true", &form)
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	resp, err := c.http.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return "", fmt.Errorf("IPFS add failed: %d", resp.StatusCode)
	}
	var result struct {
		Hash string `json:"Hash"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return "", fmt.Errorf("decode IPFS add response: %w", err)
	}
	if result.Hash == "" {
		return "", fmt.Errorf("IPFS add response has no Hash")
	}
	return result.Hash, nil
}

func (c *Client) remember(cid string, body []byte) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.local[cid] = body
}
