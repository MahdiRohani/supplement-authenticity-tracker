# Supplement Authenticity Tracker

Blockchain-based supplement authenticity tracking: smart contracts, IPFS metadata, a Go indexer/API, and a multi-role Android app.

## Protocol v2 at a glance

v2 (`SupplementRegistryV2`, HTTP `/v2`) runs alongside v1 and changes how units are anchored:

- **Merkle batches.** A whole lot (up to 5000 units through the API, 2^20 on-chain) is registered with one transaction that stores only a Merkle root of `(index, unitKey)` leaves.
- **Splittable custody segments.** Custody moves as ranges of units. A partial shipment splits a segment, and the contract enforces Manufacturer → Distributor → Pharmacy.
- **Gasless, unit-key consume.** Each unit has its own secp256k1 key, hidden under a scratch-off secret QR. The buyer's app signs an EIP-712 `ConsumeAuthorization`, and the relayer submits it through `POST /v2/consume`. A second consume (refill) is rejected on-chain.
- **Two-layer label.** A public QR (`https://…/u/{chain}/{batch}/{index}`) is used for verification. A secret QR (`satk2:…`) is used only for consumption.
- **Clone detection.** Scans are scored with device, post-consumption and region rules, and a cloned public label turns the verdict into `Suspicious`.
- **Recall.** A batch or a single segment can be invalidated on-chain. Verify then shows `Recalled`, and consume is refused.

Start with [`docs/protocol-v2.md`](docs/protocol-v2.md) for the specification, [`docs/threat-model.md`](docs/threat-model.md) for the threat model, and [`docs/evaluation.md`](docs/evaluation.md) for the measured results. In those measurements, the full on-chain lifecycle costs a unit about 77% less gas than in v1.

## Repository layout

| Path | Purpose |
|------|---------|
| `android/` | Multi-module Android app (Compose), package `ir.aut.supplementtracker` |
| `contracts/` | Hardhat + Solidity (`SupplementRegistry` v1, `SupplementRegistryV2`), gas benchmarks in `contracts/bench/` |
| `backend/` | Go indexer/API (pgx + sqlc + goose, go-ethereum) + IPFS adapter; `cmd/scansim` and `cmd/loadgen` for evaluation |
| `packages/abis/` | Shared ABIs, `deployments.json` multi-chain map, and cross-language test vectors (`test-vectors/`) |
| `subgraph/` | Optional The Graph indexer for v1 and v2 events (Go indexer remains primary) |
| `admin-web/` | Static ops panel: health, batches/segments, recall, clone suspects, reports |
| `docs/protocol-v2.md` | Protocol v2 specification (Merkle batches, segments, EIP-712 consume, labels, recall) |
| `docs/threat-model.md` | Assets, adversaries, threats and mitigations |
| `docs/evaluation.md` | Gas, off-chain cost and clone-detection results, with reproduction commands |
| `docs/meta-transactions.md` | Relayer meta-tx / EIP-712 consume paths (v1 and v2) |
| `docs/architecture-decisions.md` | Locked architecture + intentional deviations |
| `docs/audit-scorecard.md` | Full-system audit scorecard |

## Prerequisites

- Git
- JDK 17+
- Android Studio (Ladybug or newer recommended) with Android SDK
- Node.js 20+ (for contracts)
- Go 1.26+ (for the backend; not needed when running it through Docker Compose)

## Clone

```bash
git clone https://github.com/MahdiRohani/supplement-authenticity-tracker.git
cd supplement-authenticity-tracker
```

## Run Android app

1. Open the `android/` directory in Android Studio (not the monorepo root).
2. Let Gradle sync finish. If prompted, set the Android SDK path (creates `android/local.properties` locally; it is gitignored).
3. Select product flavor **`local`** (emulator → `10.0.2.2`) or **`sepolia`**, then run `:app`.
   - On a **physical phone**, set `LOCAL_DEV_HOST` in `android/local.properties` to the dev machine's LAN IP (same Wi-Fi; start Hardhat with `--hostname 0.0.0.0`), or to `127.0.0.1` after `adb reverse tcp:3000 tcp:3000 && adb reverse tcp:8545 tcp:8545`.
   - For **sepolia**, copy keys from [`android/sepolia.properties.example`](android/sepolia.properties.example) into `android/local.properties` (`SEPOLIA_API_BASE_URL`, `SEPOLIA_RPC_URL`, `SEPOLIA_REGISTRY_ADDRESS`). Defaults use `*.example.invalid` so misconfiguration fails closed.

From the command line:

```bash
cd android
./gradlew :app:assembleLocalDebug
```

APK output: `android/app/build/outputs/apk/local/debug/`.

Deep links:

- v2 public label (App Link): `https://supplementtracker.aut.ir/u/31337/1/0`, which is chain/batch/index
- v1: `supplementtracker://verify/1`

## One-click demo

```bash
./scripts/demo.sh
```

Starts contract e2e, Docker Compose (Postgres + IPFS + Backend), and a sample register/list HTTP call.

## Docker Compose

```bash
docker compose up -d --build
```

Services: `postgres`, `ipfs` (Kubo), `backend` on port `3000`.

## Contracts and backend

- Contracts: `cd contracts && npm install && npm test && npm run deploy:local`
- Backend: `cd backend && cp .env.example .env && go run ./cmd/api` (tests: `go test -race ./...`); endpoints, migrations and env in `backend/README.md`
- Env is validated at startup (see `backend/.env.example`; never commit real secrets)
- Android ↔ API contract test (runs the app's real HTTP client against a live backend):
  `cd android && SAT_API_BASE_URL=http://127.0.0.1:3000/v1/ ./gradlew :core:data:testLocalDebugUnitTest`
  (add `SAT_DISTRIBUTOR` / `SAT_PHARMACY` holding the on-chain roles to also run transfer → consume → refill rejection)
- Full local integration (the same steps as CI): `TEST_DATABASE_URL=postgresql://… ./scripts/ci-integration.sh`.
  It starts its own Hardhat node on `HH_PORT` (default `8546`) and the API on `E2E_PORT` (default `3099`), deploys v1 and v2, and runs the v2 end-to-end suite (`backend/e2e`). Without `TEST_DATABASE_URL`, the API end-to-end step is skipped.
- Benchmarks: `cd contracts && npm run bench:gas` and `cd backend && go run ./cmd/scansim` (see `docs/evaluation.md`)

## Operational runbook

1. **Local stack:** `./scripts/demo.sh` or `docker compose up -d --build` plus `cd contracts && npm run node` and deploy.
2. **Health:** `curl http://127.0.0.1:3000/v1/health` (expects `status=ok|degraded`) and `/v1/health/ready`. For v2, `curl http://127.0.0.1:3000/v2/health` (`protocol=configured` once `REGISTRY_V2_ADDRESS` is set).
3. **Write auth:** set `API_WRITE_KEY` and send `x-api-key` on POST/PUT/DELETE (GET verify stays public). Empty key is allowed only outside production.
4. **Relayer key rotate:** update `RELAYER_KEYS_JSON`, keep retiring keys in `RELAYER_KEYS_PREVIOUS_JSON`, then `POST /v1/admin/relayer-keys/reload` with the write key.
5. **ABI sync:** after contract changes run deploy/export, bump `abiVersion` in `packages/abis/SupplementRegistry.json` / `SupplementRegistryV2.json`, regenerate vectors with `npm run vectors`, restart backend.
6. **Load check:** with backend up, `PRODUCT_ID=1 ./scripts/load-verify.sh` (max request &lt; 3s).
7. **CI:** GitHub Actions runs:
   - contracts: tests, the v1/v2 Hardhat end-to-end scripts, and a test-vector determinism check;
   - Slither;
   - backend: gofmt, `go vet` including the `e2e` tag, and `go test -race` with Postgres;
   - Hardhat + Postgres integration: the v2 end-to-end suite registers a 100-unit batch, ships part of it, consumes, gets 409 on a refill, then verifies, flags clones and recalls;
   - Android: unit tests for all modules, `assembleLocalDebug`, `lintLocalDebug`, and the androidTest compile;
   - subgraph codegen/build;
   - an admin-web script syntax check.
8. **Common failures:** pending `chainProductId` means mint skipped (check RPC/keys); IPFS stub only when `ALLOW_IPFS_STUB=true` or non-production.
9. **Admin web:** `cd admin-web && python3 -m http.server 8080` against the API origin `http://127.0.0.1:3000` (v1 health plus v2 batches, segments, recall and clone suspects).
10. **Gasless consume:** see `docs/meta-transactions.md` (v2 `POST /v2/consume` with a unit-key `ConsumeAuthorization`; v1 `POST /v1/meta/consume`).
11. **Recall:** `POST /v2/batches/{batchId}/recall` (optionally with a `segmentId`) with the write key, or the admin web panel. Verify shows `Recalled` right away.

## Versioning

- HTTP API prefixes: `/v1` (`1.1.0`) and `/v2` (`2.0.0`), served side by side
- Shared ABIs: `abiVersion` `1.3.0` (`SupplementRegistry.json`) and `2.0.0` (`SupplementRegistryV2.json`), both listed in `deployments.json`
- Release tag: `v1.1.0`

## Branch and commits

- Default branch: `master`. Feature work lands here as sequential phase commits (or short-lived topic branches merged into `master`).
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/):

```text
type(scope): short summary

optional body
```

| Type | Use |
|------|-----|
| `feat` | New user-facing capability |
| `fix` | Bug fix |
| `refactor` | Internal change without behavior change |
| `chore` | Tooling, hygiene, scaffolding |
| `test` | Tests only |
| `docs` | Documentation only |
| `ci` | CI configuration |

Scopes match packages: `repo`, `android`, `contracts`, `backend`, `abis`.

Examples: `feat(contracts): register product units`, `chore(repo): add root gitignore`.

See `.gitmessage` for a local template (optional: `git config commit.template .gitmessage`).

## License

All rights reserved unless a license file is added later.
