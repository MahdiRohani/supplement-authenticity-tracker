# Architecture decisions (audit)

Locked four-layer flow remains the source of truth:

`Smart Contract → IPFS (CID only) → Go indexer/API + Postgres → Android`

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
| The Graph as primary index | Optional `subgraph/` scaffold; Go indexer is SoT | `FF_SUBGRAPH` client hint only |
| NestJS + Prisma + Zod backend | Go 1.26: `net/http`, pgx + sqlc, goose (embedded SQL), go-ethereum, `slog` | Rewritten with the same `/v1` contract (bodies, status codes, error shapes) so Android and admin-web are unchanged. Verified by a side-by-side parity run and an Android `HttpProductRepository` contract test. Brings a single static binary and a ~27 MB distroless image, native go-ethereum for decoding contract custom errors, and SQL that is type-checked at build time. Existing Prisma databases are adopted as the goose baseline. |

## Protocol v2 decisions

v2 keeps the four-layer flow. It replaces the per-unit on-chain record with
batch commitments. The specification is in [`protocol-v2.md`](protocol-v2.md)
and the threats are in [`threat-model.md`](threat-model.md).

| Question | Decision | Alternatives considered | Reason |
| --- | --- | --- | --- |
| How to anchor units | One Merkle root per lot over `(index, unitKey)` leaves (OpenZeppelin `StandardMerkleTree` encoding) | One record per unit (v1); per-unit keys without batching (S1) | Registration is constant (~267k gas) instead of linear. Lifecycle gas falls 76–78% from n = 10 (`evaluation.md`). Anyone can recompute the root from the IPFS manifest. |
| How to track custody | Contiguous index ranges ("segments") that split on partial transfer | Per-unit ownership; ERC-1155 balances | A pallet moves in one transaction. A partial shipment costs one split (~91k gas). Ranges keep the "which units are where" question answerable and indexable. |
| What proves possession at consumption | A per-unit secp256k1 key under a scratch-off layer, signing EIP-712 `ConsumeAuthorization` | Revealing a hashed secret (v1); NFC chips | The secret never appears on-chain or in the mempool. The consumer is bound into the signature, so front-running cannot redirect it. Paper labels remain the cost floor. |
| Who pays gas | A relayer pool; `consume` accepts any sender | Consumer wallets; ERC-4337 paymaster | Buyers need no wallet or ETH. The contract alone enforces correctness, so a relayer cannot forge or redirect a consumption. |
| Order of custody | Enforced on-chain by role: Created → Transferred (distributor) → AtPointOfSale (pharmacy) | Off-chain policy in the API | A compromised API still cannot skip the chain of custody. |
| Upgrades | A new non-upgradeable `SupplementRegistryV2` (`UPGRADEABLE = false`) deployed next to v1; `/v1` and `/v2` served side by side | UUPS proxy upgrade of v1 | Simpler audit surface. v1 data and clients stay valid during migration. |
| Recall | `invalidateBatch` / `invalidateSegment` by the manufacturer or admin; recall overrides every other verdict | Off-chain recall list | Every reader, including Android's independent check, sees the recall without trusting the API. |
| Trust in the API | Android re-checks the proof and the on-chain root and consumption bit through its own RPC (`Web3jChainAnchorReader`) | Trusting API verdicts | Turns "the API says authentic" into "the chain says the unit belongs to this batch". A contradiction is shown to the user. |
| Clone detection | A transparent rule-based noisy-OR score (devices, post-consumption scans, regions), with salted device hashes | Supervised ML model | There is no labelled real-world counterfeit data. Rules are explainable to users ("scanned on 4 devices") and were evaluated by simulation against baselines (`evaluation.md`). |
| Batch size | 2^20 on-chain cap; API default `MAX_BATCH_UNITS = 5000` | Unbounded | Keeps the label PDF, the manifest and the one-time key response within practical limits. Proof length stays ≤ 20 hashes. |
| Indexing | The Go indexer stays the source of truth. The subgraph mirrors v1 and v2 events for analytics. | The Graph as primary | The API needs transactional writes next to its projection. |

## Layer rules (Pass criteria)

- Features depend on `domain` + `designsystem` + `model` (not DB/HTTP details).
- Backend: HTTP handlers in `internal/httpapi` only validate and translate; business rules live in `internal/product`, `verify`, `roles` (v1) and `internal/protocol`, `merkle`, `risk` (v2), and talk to Postgres and the chain through small interfaces (faked in tests).
- `data` implements `ProductRepository` (v1) and `ProtocolRepository` (v2); blockchain verify/write, EIP-712 signing and Merkle verification stay in `core:blockchain`.
- Design tokens live in `core:designsystem`; feature screens use `SupplementSpacing` / theme, not raw hex colors.
