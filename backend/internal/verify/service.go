// Package verify answers the public "is this product authentic?" question,
// preferring the indexed database and falling back to a direct chain read.
package verify

import (
	"context"
	"encoding/json"
	"time"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/apperr"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/chain"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store"
	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

const consumedMessage = "Product already consumed; a second use or refill is not authentic"

type Store interface {
	FindProduct(ctx context.Context, key string) (db.Product, error)
}

type ChainReader interface {
	ProductStatus(ctx context.Context, chainProductID string) (chain.ProductView, bool)
}

type Metadata interface {
	ResolveJSON(ctx context.Context, cid string) json.RawMessage
	GatewayURL(cid string) string
}

type Cache interface {
	Get(key string) (Result, bool)
	Set(key string, value Result, ttl time.Duration)
}

type Result struct {
	ProductID          string          `json:"productId"`
	ChainProductID     string          `json:"chainProductId"`
	Status             string          `json:"status"`
	Authenticity       string          `json:"authenticity"`
	CurrentOwner       string          `json:"currentOwner"`
	MetadataCID        *string         `json:"metadataCid"`
	MetadataHash       *string         `json:"metadataHash"`
	MetadataGatewayURL *string         `json:"metadataGatewayUrl"`
	Metadata           json.RawMessage `json:"metadata"`
	Cached             bool            `json:"cached"`
	Source             string          `json:"source"`
	Message            string          `json:"message,omitempty"`
}

type Service struct {
	store    Store
	chain    ChainReader
	metadata Metadata
	cache    Cache
	ttl      time.Duration
}

// NewService builds the verifier; reader may be nil when no RPC endpoint or
// registry address is configured.
func NewService(st Store, reader ChainReader, metadata Metadata, cache Cache, ttl time.Duration) *Service {
	return &Service{store: st, chain: reader, metadata: metadata, cache: cache, ttl: ttl}
}

func (s *Service) Verify(ctx context.Context, id string) (Result, error) {
	key := "verify:" + id
	if cached, ok := s.cache.Get(key); ok {
		cached.Cached = true
		return cached, nil
	}

	result, found, err := s.fromDB(ctx, id)
	if err != nil {
		return Result{}, err
	}
	if !found {
		result, found = s.fromChain(ctx, id)
	}
	if !found {
		return Result{}, apperr.NotFound("Product " + id + " not found")
	}

	if result.MetadataCID != nil && *result.MetadataCID != "" {
		result.Metadata = s.metadata.ResolveJSON(ctx, *result.MetadataCID)
		gateway := s.metadata.GatewayURL(*result.MetadataCID)
		result.MetadataGatewayURL = &gateway
	}
	if result.Authenticity == "Consumed" {
		result.Message = consumedMessage
	}

	s.cache.Set(key, result, s.ttl)
	return result, nil
}

func (s *Service) fromDB(ctx context.Context, id string) (Result, bool, error) {
	p, err := s.store.FindProduct(ctx, id)
	if store.IsNotFound(err) {
		return Result{}, false, nil
	}
	if err != nil {
		return Result{}, false, err
	}
	return Result{
		ProductID:          p.ID,
		ChainProductID:     p.ChainProductID,
		Status:             string(p.Status),
		Authenticity:       Authenticity(p.Status),
		CurrentOwner:       p.OwnerAddress,
		MetadataCID:        p.MetadataCID,
		MetadataHash:       p.MetadataHash,
		MetadataGatewayURL: s.gateway(p.MetadataCID),
		Source:             "db",
	}, true, nil
}

func (s *Service) fromChain(ctx context.Context, id string) (Result, bool) {
	if s.chain == nil || !isDigits(id) {
		return Result{}, false
	}
	view, ok := s.chain.ProductStatus(ctx, id)
	if !ok {
		return Result{}, false
	}
	cid := view.MetadataCID
	return Result{
		ProductID:          id,
		ChainProductID:     id,
		Status:             string(view.Status),
		Authenticity:       Authenticity(view.Status),
		CurrentOwner:       view.CurrentOwner,
		MetadataCID:        &cid,
		MetadataGatewayURL: s.gateway(&cid),
		Source:             "chain",
	}, true
}

func (s *Service) gateway(cid *string) *string {
	if cid == nil || *cid == "" {
		return nil
	}
	url := s.metadata.GatewayURL(*cid)
	return &url
}

// Authenticity maps a lifecycle status to the verdict shown to consumers.
func Authenticity(status db.ProductStatus) string {
	switch status {
	case db.ProductStatusCreated, db.ProductStatusTransferred, db.ProductStatusAtPointOfSale:
		return "Authentic"
	case db.ProductStatusConsumed:
		return "Consumed"
	case db.ProductStatusInvalid:
		return "Invalid"
	default:
		return "NotFound"
	}
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}
