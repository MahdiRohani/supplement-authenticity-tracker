# Architecture decisions (audit)

Locked four-layer flow remains the source of truth:

`Smart Contract → IPFS (CID only) → Nest indexer/API + Postgres → Android`

## Intentional deviations from the original thesis scaffold

| Planned | Actual | Decision |
| --- | --- | --- |
| `:core:ui`, `:core:network`, `:core:common` | Folded into `designsystem`, `data`, `domain`, `model` | Fewer modules, same boundaries; no layer leaks found in feature → data skips |
| Hilt / Koin DI | Manual composition root in `MainActivity` | Acceptable for thesis/demo size; factories stay next to each ViewModel |
| Classic MVI store | UDF with `UiState` / `UiEvent` / `UiEffect` + `StateFlow` | Matches Now in Android; not a single global store |
| `:feature:distributor-inbox` | Implemented inside `:feature:stock` (`StockMode`) | Same UX, less duplication |
| UUPS upgradeable proxy | `UPGRADEABLE = false` constant on registry | Explicit non-upgradeable policy for Wave 7 |
| WalletConnect second path | Managed test keys (`SIGNING_MODE=managed`) + API relayer | Enough for local/Sepolia demo |
| Full ERC-4337 AA | Relayer EIP-712 meta-tx only | Documented in `docs/meta-transactions.md` |
| The Graph as primary index | Optional `subgraph/` scaffold; Nest indexer is SoT | `FF_SUBGRAPH` client hint only |

## Layer rules (Pass criteria)

- Features depend on `domain` + `designsystem` + `model` (not Prisma/HTTP details).
- `data` implements `ProductRepository`; blockchain verify/write stay in `core:blockchain`.
- Design tokens live in `core:designsystem`; feature screens use `SupplementSpacing` / theme, not raw hex colors.
