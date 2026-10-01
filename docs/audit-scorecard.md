# Full-system audit scorecard

## Protocol v2 audit (2026-10-01)

Scope: `SupplementRegistryV2` and `/v2` (`2.0.0`), covering:

- Merkle batch registration;
- splittable custody segments;
- gasless unit-key consume;
- two-layer labels;
- clone detection;
- recall.

Each item was checked across the contract, backend, Android, admin-web and
subgraph. The specification is in [`protocol-v2.md`](protocol-v2.md), the
threats in [`threat-model.md`](threat-model.md), and the measurements in
[`evaluation.md`](evaluation.md).

### Feature matrix (v2)

| Area | Implemented | Tested | Demoable |
| --- | --- | --- | --- |
| `SupplementRegistryV2` (batches, segments, consume, recall, pause) | Yes, non-upgradeable, ABI `2.0.0` | 67 Hardhat tests (v1 + v2 + vectors); v2 coverage 100% statements/functions/lines; Slither 0 findings in the last full run | `npm run e2e:local` (split to two pharmacies, refill/forged-key/recall blocked) |
| Cross-language vectors | `packages/abis/test-vectors/` | Contracts, Go and Android test against the same file; CI fails on drift | `npm run vectors` |
| Go `/v2` API + indexer | Register, transfer/split, consume relay, verify + risk, proof, labels PDF, recall, suspicious scans | `go test -race` with Postgres; `backend/e2e` against a real chain (100-unit batch → partial transfers → consume → 409 refill → verify → clones → recall) | `scripts/ci-integration.sh` |
| Clone detection | Noisy-OR over devices, post-consumption scans and regions; salted device hash | Unit tests plus `cmd/scansim` (10 seeds): F1 0.895, FPR 1.5% | `GET /v2/scans/suspicious` |
| Android v2 | Public/secret QR parsing, `/u/` App Link, verify with an independent on-chain check, unit-key consume, segment stock/transfer, batch registration with label PDF | 47 JVM unit tests (model, domain, data contract, blockchain vectors, UI state); `assembleLocalDebug`; androidTest compile | Emulator / device |
| admin-web v2 | Batches with segment distribution, recall, clone suspects, reports | Script syntax check in CI; manual run against the live API | `python3 -m http.server` |
| subgraph v2 | `Batch`, `Segment` (with parent), `SegmentTransfer`, `UnitConsumption`, `Recall` | Event signatures, handlers and schema checked statically against the ABI; `graph build` in CI | Optional |
| Docs | Protocol, threat model, evaluation, decisions, meta-tx | Reviewed against the code | — |
| Evaluation tooling | `bench:gas`, `bench:cost`, `cmd/scansim`, `cmd/labelbench`, `cmd/loadgen` (incl. `-endpoint consume`), `scripts/eval/` (latency, plots, TikZ) | Each tool regenerates its CSV; figures rebuilt from the CSVs | `docs/figures/` |

### Scores (0–5, v2)

| Dimension | Score | Notes |
| --- | --- | --- |
| Correct execution / E2E | 5 | The full lifecycle runs over HTTP against a real chain in CI |
| Security posture | 4 | Threats T1–T15 analysed. A single clone before consumption can only be detected statistically (T1). Demo role keys are held by the API (assumption 5). |
| Evaluation evidence | 5 | Gas, off-chain and USD costs (L1 median/p90, Arbitrum, Base) from a dated fee snapshot; label payload and PDF size; read and end-to-end consume latency at three block times; clone detector against baselines and ablations; security table. All reproducible from scripts, with 600 dpi figures. |
| Architecture / layers | 5 | v2 lives in its own modules and packages (`internal/protocol`, `merkle`, `risk`; `ProtocolRepository`); v1 is unchanged |
| Spec coverage | 5 | All six v2 capabilities work end to end |

### Remaining (non-blocking)

- P2: The clone detector is evaluated on simulated scans only. There is no field data.
- P3: Latency comes from one laptop over loopback, with Hardhat emulating block times. There is no measurement on a public L2 testnet (see [`evaluation.md`](evaluation.md), section 10).
- P3: `graph build` has not been run on the development machine (the npm registry is unreachable there). CI runs it.
- P3: The Android consumer identity key is stored in private app storage, not in the Android Keystore. It only labels history and controls no funds.
- P3: A manual role walkthrough on a device is still recommended for camera permissions and App Link verification (`assetlinks.json` must be hosted on the verify domain).

---

## v1 audit (2026-09-10)

Scope: Wave 0–7 / `v1.1.0` acceptance + production-lite hardening + UI/UX polish.

## Feature matrix

| Area | Spec’d | Implemented | Tested | Demoable |
| --- | --- | --- | --- | --- |
| SupplementRegistry lifecycle | Yes | Yes | 28 Hardhat tests Pass | Yes (`npm test`, `e2e:local`) |
| Non-upgradeable policy | Yes | `UPGRADEABLE=false`, ABI `1.3.0` | Pass | Yes |
| Go API verify/products/roles | Yes | Yes (NestJS → Go rewrite, same `/v1` contract) | `go test -race` + Postgres integration + TS parity run + Android contract test Pass | Yes (Docker) |
| Flags / reports / analytics / meta | Yes | Yes | wave7 + smoke Pass | Yes |
| Indexer resilience | Implied | Graceful disable on bad RPC | Smoke Pass | Yes |
| Android 8 features + roles | Yes | Yes | assembleLocalDebug Pass | Device/emulator manual |
| Scan + labels PDF | Yes | Yes | CI/smoke PDF Pass | Flag-gated |
| admin-web | Yes | Readable ops cards | Manual vs live API | Yes |
| subgraph | Optional | Scaffold only | N/A | Optional / Won’t-fix primary |

## Scores (0–5)

| Dimension | Score | Notes |
| --- | --- | --- |
| Correct execution / E2E | 5 | Health/flags/chains/register/verify/report/PDF/load Pass |
| Performance | 5 | load-verify max 30ms ≪ 3s (n=20) |
| Architecture / layers | 5 | Documented in `docs/architecture-decisions.md` |
| Design system clarity | 4 | Tokens used; nav icons + shared error i18n added |
| Modern UI consistency | 4 | Admin panel + Android polish; not a full visual redesign |
| Role UX + i18n | 4 | Logout, localized success/errors; API English messages may still surface |
| Spec coverage | 5 | Wave 7 surface covered; intentional omissions listed |
| Ops / prod-lite | 4 | Write-key + IPFS stub blocked in production; Sepolia via `local.properties` |

**Acceptance:** no open P0; demo-breaking P1s closed.

## Closed this audit

- P0: Backend crash when RPC host unresolved → indexer try/catch + demo leaves RPC unset + Docker `extra_hosts` + OpenSSL in image
- P1: Hardcoded EN snackbars / owner error → typed success effects + `localizedErrorMessage`
- P1: No logout → TopBar logout clears `SessionStore`
- P1: Letter-only nav icons → Material icons
- P2: Analytics no UI → dashboard local counters when flag on; admin-web cards
- P2: admin-web JSON dump → structured health/flags/reports/analytics UI
- P2: Sepolia placeholders → `*.example.invalid` defaults + `sepolia.properties.example`
- P2: Production env → `API_WRITE_KEY` / `ALLOW_IPFS_STUB` validated at boot + tests
- P3: Health `version` aligned to `1.1.0`
- Backend moved to Go (2026-09-27). The rewrite fixed these latent TS bugs:
  - Rate limits were never enforced.
  - Contract reverts surfaced as 500 instead of 400/409.
  - `%` and `_` in search were treated as wildcards.
  - A few malformed inputs returned 500 instead of 400.
  - `/health/ready` now returns 503 when the DB is down.

## Remaining (non-blocking)

- P3: On-chain mint in Docker demo needs a live Hardhat node (`RPC_URL` + `npx hardhat node`); pending IDs are expected without it
- P3: Manual role walkthrough on emulator still recommended for Scan camera permission
- P3: Full Slither job not re-run locally in this pass (covered by CI workflow)

## Out of scope (Won’t-fix)

- Mainnet launch, WalletConnect, ERC-4337 AA, thesis chapters, full Figma, Hilt migration, subgraph as primary index
