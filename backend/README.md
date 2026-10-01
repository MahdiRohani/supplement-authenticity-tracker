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

Postgres integration tests (`internal/store`, `internal/protocol`) run only
when `TEST_DATABASE_URL` is set; each run uses a throwaway schema that is
dropped afterwards. The protocol tests drive the real services and SQL against
an in-memory fake of `SupplementRegistryV2` that enforces the contract's rules:

```bash
TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:5432/supplement_tracker go test ./internal/store/ ./internal/protocol/
```

The black-box end-to-end suite (`e2e/`, build tag `e2e`) runs the whole v2
lifecycle against a live API on a Hardhat chain. The API needs the Hardhat
accounts 0–2 in `RELAYER_KEYS_JSON`, as in `.env.example`:

```bash
cd contracts && npx hardhat node &                      # terminal 1
npx hardhat run scripts/deploy.ts --network localhost   # deploys v1 + v2
cd ../backend && go run ./cmd/api &                     # with .env from .env.example
E2E_API_URL=http://127.0.0.1:3000 go test -tags e2e -count=1 ./e2e/
```

`scripts/ci-integration.sh` does all of this when `TEST_DATABASE_URL` is set.

Handler tests in `internal/httpapi` pin the exact response bodies the NestJS
backend produced (validation messages, error shapes, 404 text, headers), which
is what the Android client and admin-web parse.

After editing `internal/store/queries/*.sql` or the migration, regenerate:

```bash
sqlc generate
```

## Endpoints (v1)

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

## Protocol v2 (`/v2`, SupplementRegistryV2)

v2 tracks individual units instead of one product per transaction:

- **Merkle-batch registration.** A batch of *n* units costs one transaction.
  Each unit gets its own key pair. The leaf `(index, unitKey)` goes into a
  Merkle tree whose root is stored on-chain. The manifest listing every unit
  key is pinned to IPFS, so anyone can recompute the root. Private keys are
  never stored: they are returned once in the registration response.
- **Splittable custody segments.** A segment is a range of units held by one
  wallet. Shipping part of a segment splits it, and custody must go
  manufacturer → distributor → pharmacy (enforced on-chain by role).
- **Gasless one-time consumption.** The hidden label holds the unit's private
  key. The buyer's app signs an EIP-712 `UnitConsume(batchId, index,
  consumer, deadline)` with it, and the relayer pays gas. A unit can be
  consumed once, so a refilled box cannot be consumed again.
- **Two-layer labels.** The public QR
  `{PUBLIC_VERIFY_BASE_URL}/{chainId}/{batchId}/{index}` is printed in the
  open. The secret QR `satk2:{chainId}:{batchId}:{index}:{key}` sits under a
  scratch-off layer.
- **Clone detection.** Every public scan is stored with a keyed device hash
  and a coarse region, then scored by `internal/risk`: a noisy-OR over
  *many devices before sale*, *new devices after consumption* and *regions
  away from the custodian*. High risk turns `Authentic`/`InTransit` into
  `Suspicious`.
- **Recall** of a whole batch or of one segment.

The v2 indexer backfills from the contract's deploy block and persists its
cursor. Its projection converges whatever order API writes and indexer
replays arrive in: segment writes are ordered by `(block, logIndex)`.

Endpoints (writes need the API key unless marked *public*):

- `GET /v2/health`, `GET /v2/health/ready`, `GET /v2/flags` — *public*
- `GET /v2/chains` — *public*; registry address, deploy block, EIP-712 domain and `UnitConsume` types for clients
- `POST /v2/batches` `{ "name", "lotCode", "size", "manufacturerAddress"?, "expiresAt"? }` — returns every unit's public and secret QR **once**; a lot can be registered only once per manufacturer (409)
- `GET /v2/batches?manufacturer=&page=&limit=`, `GET /v2/batches/:batchId` (segments + distribution by stage)
- `GET /v2/batches/:batchId/units/:index/proof` — Merkle proof, to check a unit against the on-chain root without trusting the API
- `GET /v2/batches/:batchId/units/:index/history` — custody steps + consumption
- `POST /v2/batches/:batchId/recall` `{ "segmentId"?, "reason"? }`
- `GET /v2/segments?owner=&batchId=&status=`, `GET /v2/segments/:segmentId`
- `POST /v2/segments/:segmentId/transfer` `{ "toAddress", "count"? }` — `count` ships the first *count* units; omitted or 0 ships the whole segment
- `POST /v2/consume` `{ "chainId"?, "batchId", "index", "consumer", "deadline", "signature" }` — *public*, rate-limited; signature, Merkle proof and custody are checked before relaying
- `GET /v2/verify/:chainId/:batchId/:index` — *public*, rate-limited; records the scan (`X-Device-Id`, `X-Scan-Region` or `?region=`) and returns status, custodian profile, risk with reasons, and on-chain evidence. Outside production `Cache-Control: no-cache` bypasses the snapshot cache.
- `POST /v2/labels/render` `{ "batchId", "secretQrs": [...] }` — A4 PDF, 8 two-layer labels per page; each key is checked against the committed unit key (`FF_LABELS_PDF`)
- `GET /v2/scans/suspicious?sinceHours=&minRisk=&limit=` — units whose scans reached the risk threshold
- `GET /v2/roles?address=`, `POST /v2/roles` `{ "address", "role", "displayName"?, "region"?, "onChain"? }` — grants the role on SupplementRegistryV2 with a managed admin key (default) and stores the public profile shown to buyers; `DELETE /v2/roles/:address/:role` removes only the off-chain binding (revoking the on-chain role is an admin transaction)
- `/v2/reports/counterfeit`, `/v2/analytics/snapshot`, `/v2/admin/relayer-keys/reload` — same as v1

## Evaluation tools

Both write CSV files to `bench/results/`:

- `go run ./cmd/scansim [-seed 7 -units 5000 -runs 10]` simulates genuine and
  cloned units, replays every scan through `internal/risk`, and reports
  precision, recall, F1 and FPR per threshold, against naive baselines
  (status-only, scan count, device count) and with each rule ablated. It
  also reports recall by number of copies. All model assumptions are written
  to `scansim_config.json`.
- `go run ./cmd/loadgen -url http://127.0.0.1:3000 -setup -levels 1,8,32,64`
  measures throughput and p50/p95/p99 latency of `verify`, `proof` or `batch`
  per concurrency level (`-fresh` bypasses the snapshot cache). Raise the
  API's `VERIFY_RATE_LIMIT` first.

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
