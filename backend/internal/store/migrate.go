package store

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/stdlib"
	"github.com/pressly/goose/v3"
	"github.com/pressly/goose/v3/database"
	"github.com/pressly/goose/v3/lock"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

const versionTable = "goose_db_version"

// Migrate applies pending migrations, retrying while the database is still
// starting up. A session-level advisory lock keeps concurrent replicas from
// racing each other.
func (s *Store) Migrate(ctx context.Context, log *slog.Logger, wait time.Duration) error {
	sqlDB := stdlib.OpenDBFromPool(s.Pool)
	defer sqlDB.Close()

	deadline := time.Now().Add(wait)
	for {
		err := sqlDB.PingContext(ctx)
		if err == nil {
			break
		}
		if time.Now().After(deadline) {
			return fmt.Errorf("database not reachable: %w", err)
		}
		log.Warn("waiting for database", "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}

	if s.schema != "" {
		if _, err := sqlDB.ExecContext(ctx, "CREATE SCHEMA IF NOT EXISTS "+pgx.Identifier{s.schema}.Sanitize()); err != nil {
			return fmt.Errorf("create schema %s: %w", s.schema, err)
		}
	}
	if err := baselinePrismaSchema(ctx, sqlDB, log); err != nil {
		return err
	}

	migrations, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		return err
	}
	locker, err := lock.NewPostgresSessionLocker()
	if err != nil {
		return err
	}
	provider, err := goose.NewProvider(goose.DialectPostgres, sqlDB, migrations, goose.WithSessionLocker(locker))
	if err != nil {
		return err
	}
	results, err := provider.Up(ctx)
	if err != nil {
		return fmt.Errorf("apply migrations: %w", err)
	}
	for _, r := range results {
		log.Info("migration applied", "version", r.Source.Version, "file", r.Source.Path, "duration", r.Duration)
	}
	return nil
}

// baselinePrismaSchema marks the initial migration as applied when the tables
// already exist because the database was created by `prisma db push`.
func baselinePrismaSchema(ctx context.Context, sqlDB *sql.DB, log *slog.Logger) error {
	var productTable, gooseTable sql.NullString
	err := sqlDB.QueryRowContext(ctx,
		`SELECT to_regclass('"Product"')::text, to_regclass($1)::text`, versionTable,
	).Scan(&productTable, &gooseTable)
	if err != nil {
		return fmt.Errorf("inspect schema: %w", err)
	}
	if !productTable.Valid || gooseTable.Valid {
		return nil
	}

	gooseStore, err := database.NewStore(database.DialectPostgres, versionTable)
	if err != nil {
		return err
	}

	tx, err := sqlDB.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback() }()
	if err := gooseStore.CreateVersionTable(ctx, tx); err != nil {
		return fmt.Errorf("create %s: %w", versionTable, err)
	}
	for _, version := range []int64{0, 1} {
		if err := gooseStore.Insert(ctx, tx, database.InsertRequest{Version: version}); err != nil {
			return fmt.Errorf("baseline version %d: %w", version, err)
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	log.Info("adopted existing Prisma schema as migration baseline")
	return nil
}
