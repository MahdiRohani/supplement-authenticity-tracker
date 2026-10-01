# Backend (Go)

Indexer + REST API for the supplement registry: Postgres projection of on-chain
events, IPFS metadata, relayer writes, EIP-712 meta-transactions, and the
labels PDF. Single static binary (`cmd/api`), Go 1.26.

| Concern | Library |
| --- | --- |
| HTTP | `net/http` + small in-house router (Nest/Express-compatible routing and error bodies) |
| Postgres | `pgx/v5` pool + [`sqlc`](https://sqlc.dev) generated queries (`internal/store/db`) |
| Migrations | [`goose`](https://github.com/pressly/goose) v3, SQL embedded in the binary |
| Chain | `go-ethereum` (ABI, relayer transactions, log polling, EIP-712) |
| Logging | `log/slog` JSON |

## Run

```bash
cd backend
cp .env.example .env        # loaded automatically; real env vars win
go run ./cmd/api
```

Requires Postgres matching `DATABASE_URL`. With `AUTO_MIGRATE=true` (default)
the embedded migrations are applied at boot under a Postgres advisory lock, so
several replicas can start together. Prisma-style `?schema=name` in
`DATABASE_URL` is honoured (the schema is created if missing).

A database created by the previous NestJS/Prisma backend is adopted in place:
if the `Product` table exists but goose has never run, the initial migration is
recorded as applied instead of re-created, and existing data is kept.

Env is validated at startup and every problem is reported at once. In
`APP_ENV=production` (`NODE_ENV` is read as a fallback) `API_WRITE_KEY` is
mandatory and `ALLOW_IPFS_STUB` must be false. Never commit a real `.env`.

## Test

```bash
cd backend
gofmt -l . && go vet ./...
go test -race ./...
```

Postgres integration tests (`internal/store`) run only when
`TEST_DATABASE_URL` is set; each run uses a throwaway schema that is dropped
afterwards:

```bash
TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:5432/supplement_tracker go test ./internal/store/
```

Handler tests in `internal/httpapi` pin the exact response bodies the NestJS
backend produced (validation messages, error shapes, 404 text, headers), which
is what the Android client and admin-web parse.

After editing `internal/store/queries/*.sql` or the migration, regenerate:

```bash
sqlc generate
```

## Endpoints

All under `/v1`. Writes need `x-api-key: $API_WRITE_KEY` when a key is set;
routes marked *public* never do.

- `GET /health`, `GET /health/ready` (503 when Postgres is unreachable) — *public*
- `GET /chains`, `GET /flags` — *public*
- `GET /verify/:id` — *public*, rate-limited, TTL-cached authenticity check with IPFS metadata
- `GET /products?owner=&status=&q=&page=&limit=` — searchable paginated inventory
- `GET /products/labels.pdf?batch=LOT-1` — printable QR labels for a batch (`FF_LABELS_PDF`)
- `POST /products` `{ "name", "batch" }` — returns the scratch `secret` once; only its hash goes on-chain
- `POST /products/batch` `{ "name", "batch", "count" }`
- `POST /products/:id/transfer` `{ "toAddress" }` — relayed with the current owner's key
- `POST /products/:id/consume` `{ "secret" }` — rate-limited anti-refill; a second consume is 409
- `GET /products/:chainProductId`, `GET /products/:id/history`
- `GET /roles`, `GET /roles/:address`, `POST /roles`, `DELETE /roles/:address/:role`
- `POST /reports/counterfeit` (*public*), `GET /reports/counterfeit` (`FF_REPORTS`)
- `POST /analytics/events/verify|scan` (*public*), `GET /analytics/snapshot` (`FF_ANALYTICS`)
- `POST /meta/eip712/metadata/sign|verify` (`FF_EIP712_METADATA`), `POST /meta/consume` (`FF_META_TX_CONSUME`, see `docs/meta-transactions.md`)
- `POST /admin/relayer-keys/reload` — re-reads `RELAYER_KEYS_JSON` / `RELAYER_KEYS_PREVIOUS_JSON`

The indexer goroutine polls `RPC_URL` for `ProductRegistered`,
`OwnershipTransferred` and `ProductConsumed`; if the RPC or registry is
unavailable it logs and stays disabled instead of crashing the API. Contract
custom errors (e.g. `ProductAlreadyConsumed`, `InvalidSecret`) are decoded from the
revert selector and returned as 409/400. Sensitive actions write `AuditLog`
rows.

## Docker

From the repo root: `docker compose up -d --build` (Postgres + Kubo IPFS +
backend). The image is a static binary on `distroless/static:nonroot`
(~27 MB); its `HEALTHCHECK` runs `/app/api healthcheck`, which exits 0 only
when `/v1/health/ready` returns 200.

## Differences from the NestJS backend

Response shapes, status codes and messages are unchanged (checked with a
side-by-side parity run against Postgres + Hardhat and a JVM contract test
that drives the Android `HttpProductRepository`). The rewrite fixes these TS
bugs:

- `VERIFY_RATE_LIMIT` / `CONSUME_RATE_LIMIT` were never enforced; they now return 429.
- Contract reverts were always 500 because ethers reported "unknown custom error"; they now map to 400/409.
- `q=` search treated `%` and `_` as wildcards; they are now matched literally.
- `POST /roles` without a body and malformed EIP-712 signatures returned 500; they now return 400.
- Non-numeric `page`/`limit` returned 500; they now fall back to the defaults (page 1, limit 20, max 100).
- `/health/ready` reports 503 when the database is down.
