# Full-system audit scorecard (2026-09-10)

Scope: Wave 0–7 / `v1.1.0` acceptance + production-lite hardening + UI/UX polish.

## Feature matrix

| Area | Spec’d | Implemented | Tested | Demoable |
| --- | --- | --- | --- | --- |
| SupplementRegistry lifecycle | Yes | Yes | 28 Hardhat tests Pass | Yes (`npm test`, `e2e:local`) |
| Non-upgradeable policy | Yes | `UPGRADEABLE=false`, ABI `1.3.0` | Pass | Yes |
| Nest API verify/products/roles | Yes | Yes | Jest Pass | Yes (Docker) |
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

## Remaining (non-blocking)

- P3: On-chain mint in Docker demo needs a live Hardhat node (`RPC_URL` + `npx hardhat node`); pending IDs are expected without it
- P3: Manual role walkthrough on emulator still recommended for Scan camera permission
- P3: Full Slither job not re-run locally in this pass (covered by CI workflow)

## Out of scope (Won’t-fix)

- Mainnet launch, WalletConnect, ERC-4337 AA, thesis chapters, full Figma, Hilt migration, subgraph as primary index
