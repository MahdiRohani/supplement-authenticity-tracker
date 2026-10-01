# Threat model (protocol v2)

Scope: `SupplementRegistryV2`, the Go API and indexer, the IPFS manifest, the
two-layer label and the Android client. The protocol itself is described in
[`protocol-v2.md`](protocol-v2.md).

## Assets

| Asset | Why it matters |
| --- | --- |
| A unit's one-time key `sk_i` (secret label) | Authorizes the single consumption of that unit |
| Batch Merkle root and consumption bitmap (on-chain) | Ground truth for "this unit is genuine" and "this unit was already used" |
| Custody segments and roles (on-chain) | Who may hold and sell which units |
| Scan history (API database) | Input of clone detection; also personal data |
| Relayer and managed role keys (API configuration) | Pay gas and sign custody transfers |
| Admin key | Grants roles, pauses the contract, recalls |

## Trust assumptions

1. The admin grants `MANUFACTURER_ROLE` only to vetted manufacturers. The
   system proves that a unit was registered by such a manufacturer. It cannot
   prove what is inside the box.
2. The secret layer is tamper-evident. A buyer can see whether it was
   scratched before purchase.
3. The chain (Ethereum or an L2) is live and final after confirmation.
   `keccak256` and secp256k1 ECDSA are secure.
4. The API operator is *not* trusted for authenticity. The app re-checks the
   proof and the on-chain root (see the API-compromise rows below). It *is*
   trusted for availability, for the risk score and for custodian profiles.
5. In the demo deployment the API holds the role holders' keys
   (`RELAYER_KEYS_JSON`) and signs custody transfers for them. A production
   deployment would move these keys to the parties' own wallets or HSMs. The
   contract rules are the same either way.

## Adversaries

| Adversary | Capabilities |
| --- | --- |
| Counterfeiter | Sees and photocopies any public label; prints unlimited copies; scans with many phones; cannot open sealed genuine stock |
| Refiller | Owns genuine used packaging (public and scratched secret label) and fills it with fake product |
| Insider in the supply chain | Holds sealed stock in transit or on a shelf; can scratch secret layers |
| Network attacker / MEV searcher | Reads the mempool, reorders and copies transactions |
| Malicious or compromised API | Returns arbitrary JSON, drops or forges scan data, uses the managed keys |
| Griefer | Wants a genuine unit to look suspicious or wants to drain the relayer |
| Curious observer | Reads the chain, IPFS and (if leaked) the scan table to track buyers |

## Threats, mitigations, residual risk

| # | Threat | Attacker capability | Mitigation | Residual risk |
| --- | --- | --- | --- | --- |
| T1 | **Cloned public label.** Copies of a genuine unit's public QR are stuck on fake boxes | Counterfeiter | The public QR carries no secret, so a copy cannot consume. Copies show up as scans from extra devices, after consumption and in other regions. The noisy-OR risk model turns a high score into `Suspicious` | A single copy sold in the custodian's region, before the genuine unit is scanned, looks normal. Recall by number of copies in [`evaluation.md`](evaluation.md): 0.37 for one copy, 0.90 for three, ≥0.99 for five or more |
| T2 | **Refilled genuine packaging** | Refiller | The secret was used once; the bitmap makes a second `consume` revert (`UnitAlreadyConsumed`, HTTP 409). Verify shows `Consumed`, and new devices scanning after consumption raise risk | Detection relies on the first buyer recording use. A buyer who never consumes leaves the unit "fresh". The consume flow is one tap after the purchase scan to encourage it |
| T3 | **Secret layer scratched or photographed before sale** | Insider | The tamper-evident layer warns the buyer. The key only authorizes consumption, not ownership. Consumption is publicly visible, so a box sold already consumed shows `Consumed` | The insider can "burn" units (denial of service on the buyer) or sell a box whose key they copied. Recovery is a refund and a segment recall. This is out of cryptographic reach |
| T4 | **Forged unit (key not in the batch)** | Counterfeiter | `consume` checks the Merkle proof of `(index, unitKey)` against the on-chain root. The API checks it before relaying (HTTP 400). Verify of an unregistered `batchId/index` returns 404 | None at the protocol level |
| T5 | **Consumption front-running or redirect** | MEV searcher | `consumer` is inside the signed EIP-712 data. A copied transaction consumes for the same consumer, so copying gains nothing | The race itself is harmless. Whoever submits first pays the gas |
| T6 | **Signature replay** | Network attacker | The EIP-712 domain binds `chainId` and the registry address. The bitmap blocks replay on the same deployment. `deadline` bounds how long a leaked authorization is valid | None |
| T7 | **Custody fraud** (skip the distributor, ship to a non-pharmacy, ship someone else's stock) | Insider | On-chain checks: segment owner, one-step status machine and recipient role. Segments partition `[0, size)` (property test) | In the demo deployment, whoever controls the API can sign as any party whose key it holds (assumption 5) |
| T8 | **Lying API** (fake `Authentic`, fake proof, hidden consumption) | Compromised API | The app recomputes the leaf and folds the proof. It reads `merkleRoot` and `isConsumed` from the chain over its own RPC. A failed proof or a root mismatch turns the verdict into `Suspicious` and hides the consume action. Checks that could not run are shown as skipped, never as passed | Custody stage, custodian profile, recall flag and the risk score still come from the API. The app also needs an honest RPC endpoint |
| T9 | **Database breach** | Curious observer, insider | No unit private key is ever stored. Keys are returned once and only pass through label rendering. Device identifiers are HMAC-SHA256 under `SCAN_HASH_SALT` (mandatory in production), and regions are coarse slugs. Consumer addresses are per-install keys with no funds | A leaked scan table shows the scan volume and coarse region per unit. Consumptions from one install share one consumer address, so they are linkable to each other, not to a person |
| T10 | **Framing a genuine unit** (scan it from many fake device ids to make it `Suspicious`) | Griefer | Per-IP rate limit on verify (`VERIFY_RATE_LIMIT`). Risk only changes the displayed verdict: `POST /v2/consume` does not consult it, and the reasons are shown to the user | A distributed griefer can still raise a unit's risk. The admin "clone suspects" list shows such spikes for review |
| T11 | **Relayer draining** | Griefer | Before relaying, the API checks the chain id, deadline, signature, Merkle membership, consumed state and custody. Only consumptions that will succeed cost gas. Rate limit `CONSUME_RATE_LIMIT`. Relayer keys can be rotated (`/v2/admin/relayer-keys/reload`) | Each genuine unit costs the operator one consume (about 58k–85k gas). That is bounded by the units sold |
| T12 | **Manifest tampering or loss on IPFS** | Network attacker | `metadataHash` is on-chain. The API keeps the proofs in Postgres and verifies a restored manifest against the root before using it | If both the database and every IPFS pin are lost, the proofs cannot be rebuilt (the on-chain root stays valid). Pin with more than one provider |
| T13 | **Lookalike verify site** (fake QR pointing to a phishing domain) | Counterfeiter | The app parses `/u/{chain}/{batch}/{index}` from any host but always verifies against its own configured API and chain. The App Link is verified only for `supplementtracker.aut.ir` | A user who opens a fake link in a browser instead of the app can be misled |
| T14 | **Contract bugs or admin misuse** | Insider with the admin key | Non-upgradeable (`UPGRADEABLE = false`). OpenZeppelin AccessControl, Pausable, EIP712, ECDSA, MerkleProof and BitMaps. 100% line coverage, property tests. Slither reported 0 findings in the last full run (two inline suppressions are justified in the code), and CI fails on any High finding | A stolen admin key can pause, grant roles and recall. Use a multisig in production |
| T15 | **Resource exhaustion at registration** | Malicious manufacturer | `MAX_BATCH_SIZE` = 2^20 on-chain. `MAX_BATCH_UNITS` (default 5000) in the API. Lot uniqueness per manufacturer | None beyond normal gas costs |

## Security properties (summary)

- **Authenticity.** A unit verifies only if `(index, unitKey)` is under a root
  written by a manufacturer.
- **Single use.** At most one successful `consume` per `(batchId, index)`.
- **Unforgeable consumption.** Consuming requires `sk_i`. Neither the public
  label nor any on-chain data reveals it.
- **Consumer binding.** A consumption credits exactly the consumer named in the
  signature.
- **Gasless for buyers.** The buyer needs no ETH and no funded wallet.
- **Verifiable without trusting the API.** The proof and the root are checked
  on the device against the chain.
- **Explainable clone signal.** Every risk point is attributable to a named
  rule.
