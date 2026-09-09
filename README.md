# Supplement Authenticity Tracker

Blockchain-based supplement authenticity tracking: smart contracts, IPFS metadata, NestJS indexer/API, and a multi-role Android app.

## Repository layout

| Path | Purpose |
|------|---------|
| `android/` | Multi-module Android app (Compose), package `ir.aut.supplementtracker` |
| `contracts/` | Hardhat + Solidity (`SupplementRegistry`) |
| `backend/` | NestJS indexer/API + Prisma + IPFS adapter |
| `packages/abis/` | Shared `SupplementRegistry` ABI |

## Prerequisites

- Git
- JDK 17+
- Android Studio (Ladybug or newer recommended) with Android SDK
- Node.js 20+ (for contracts and backend)

## Clone

```bash
git clone https://github.com/MahdiRohani/supplement-authenticity-tracker.git
cd supplement-authenticity-tracker
```

## Run Android app

1. Open the `android/` directory in Android Studio (not the monorepo root).
2. Let Gradle sync finish. If prompted, set the Android SDK path (creates `android/local.properties` locally; it is gitignored).
3. Select product flavor **`local`** (emulator → `10.0.2.2`) or **`sepolia`**, then run `:app`.

From the command line:

```bash
cd android
./gradlew :app:assembleLocalDebug
```

APK output: `android/app/build/outputs/apk/local/debug/`.

Deep link example: `supplementtracker://verify/1`

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
- Backend: see `backend/README.md` (`GET /v1/health`, `GET /v1/products`, `POST /v1/products`, `POST /v1/products/batch`, ProductRegistered indexer)
- Env is validated with Zod at startup (see `backend/.env.example`; never commit real secrets)

## Operational runbook

1. **Local stack:** `./scripts/demo.sh` or `docker compose up -d --build` plus `cd contracts && npm run node` and deploy.
2. **Health:** `curl http://127.0.0.1:3000/v1/health` (expects `status=ok|degraded`) and `/v1/health/ready`.
3. **Write auth:** set `API_WRITE_KEY` and send `x-api-key` on POST/PUT/DELETE (GET verify stays public). Empty key is allowed only outside production.
4. **Relayer key rotate:** update `RELAYER_KEYS_JSON`, keep retiring keys in `RELAYER_KEYS_PREVIOUS_JSON`, then `POST /v1/admin/relayer-keys/reload` with the write key.
5. **ABI sync:** after contract changes run deploy/export, bump `abiVersion` in `packages/abis/SupplementRegistry.json`, restart backend.
6. **Load check:** with backend up, `PRODUCT_ID=1 ./scripts/load-verify.sh` (max request &lt; 3s).
7. **CI:** GitHub Actions runs contracts, backend, Hardhat+Postgres integration smoke, and Android `assembleLocalDebug`.
8. **Common failures:** pending `chainProductId` means mint skipped (check RPC/keys); IPFS stub only when `ALLOW_IPFS_STUB=true` or non-production.

## Versioning

- HTTP API prefix: `/v1` (package `1.0.0`)
- Shared ABI: `abiVersion` in `packages/abis/SupplementRegistry.json`

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
