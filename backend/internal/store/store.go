// Package store owns the Postgres connection pool, schema migrations and the
// sqlc-generated query layer in store/db.
package store

import (
	"context"
	"errors"
	"fmt"
	"net/url"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/MahdiRohani/supplement-authenticity-tracker/backend/internal/store/db"
)

// Store bundles the pool with the generated queries.
type Store struct {
	*db.Queries
	Pool *pgxpool.Pool
	// schema is the Prisma-style `?schema=` name, created on migrate.
	schema string
}

// Open creates a lazily-connecting pool. DATABASE_URL values written for Prisma
// carry a `schema` query parameter that Postgres rejects as a runtime setting,
// so it is translated into `search_path`.
func Open(ctx context.Context, databaseURL string) (*Store, error) {
	cleaned, schema, err := splitPrismaSchema(databaseURL)
	if err != nil {
		return nil, err
	}
	cfg, err := pgxpool.ParseConfig(cleaned)
	if err != nil {
		return nil, fmt.Errorf("parse DATABASE_URL: %w", err)
	}
	if schema != "" {
		cfg.ConnConfig.RuntimeParams["search_path"] = pgx.Identifier{schema}.Sanitize()
	}
	// Timestamps are stored as `timestamp(3)` without zone, in UTC.
	cfg.ConnConfig.RuntimeParams["timezone"] = "UTC"

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("create pool: %w", err)
	}
	return &Store{Queries: db.New(pool), Pool: pool, schema: schema}, nil
}

// Ping runs the same liveness probe the health endpoint reports on.
func (s *Store) Ping(ctx context.Context) error {
	_, err := s.Pool.Exec(ctx, "SELECT 1")
	return err
}

func (s *Store) Close() {
	s.Pool.Close()
}

// ResetV2Projection drops everything the v2 indexer derived from the chain
// (batches, segments, units, custody history and its cursor) so it can be
// rebuilt. Scan events are kept: they are observations, not chain state.
func (s *Store) ResetV2Projection(ctx context.Context, cursor string) error {
	tx, err := s.Pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `TRUNCATE "Unit", "CustodyEvent", "Segment", "Batch"`); err != nil {
		return fmt.Errorf("reset v2 projection: %w", err)
	}
	if _, err := tx.Exec(ctx, `DELETE FROM "IndexerCursor" WHERE "name" = $1`, cursor); err != nil {
		return fmt.Errorf("reset indexer cursor: %w", err)
	}
	return tx.Commit(ctx)
}

// IsNotFound reports whether err means a query matched no rows.
func IsNotFound(err error) bool {
	return errors.Is(err, pgx.ErrNoRows)
}

func splitPrismaSchema(raw string) (string, string, error) {
	u, err := url.Parse(raw)
	if err != nil {
		return "", "", fmt.Errorf("parse DATABASE_URL: %w", err)
	}
	q := u.Query()
	schema := q.Get("schema")
	if schema == "" {
		return raw, "", nil
	}
	q.Del("schema")
	u.RawQuery = q.Encode()
	return u.String(), schema, nil
}
