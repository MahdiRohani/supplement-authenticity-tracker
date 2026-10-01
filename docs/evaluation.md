# Evaluation

How protocol v2 is evaluated, the results collected so far, and how to
reproduce them. Raw data lives next to the tools that produce it:

- `contracts/bench/results/`: gas, off-chain and USD costs
- `backend/bench/results/`: clone-detection simulation, label payload, label
  PDF and API latency
- `docs/data/security-analysis.csv`: the security analysis table

Every number below is read from those CSV files. Figures are in
[`figures/`](figures/) (600 dpi PNG and LZW TIFF, plus vector PDF).

## Schemes compared

| Scheme | Description |
| --- | --- |
| **S0** | v1 `SupplementRegistry`: one on-chain record per unit with a secret hash; consumption reveals the secret |
| **S1** | v2 with one batch of size 1 per unit (per-unit signature key, empty proof) |
| **S2** | v2 with one Merkle batch of size n (proposed) |

## 1. Gas

Setup (`contracts/bench/results/environment.json`):

- Hardhat 2.29.1, solc 0.8.28, optimizer at 200 runs, EVM `paris`, block gas
  limit 60M.
- Values are receipt `gasUsed` after warm-up transactions.
- S0 is measured up to n = 10 000 (registration in chunks of 50). S1 is
  measured up to n = 1000. Larger sizes for those two are linear
  extrapolations, marked in the CSV. S2 is measured at every size.

### Registration per unit (`gas-registration.csv`)

| n | S0 | S1 | S2 | S0 / S2 |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 217 650 | 266 990 | 266 990 | 0.8× |
| 10 | 186 928 | 266 985 | 26 699 | 7× |
| 100 | 184 207 | 266 987 | 2 670 | 69× |
| 1 000 | 184 206 | 266 987 | 267 | 690× |
| 10 000 | 184 206 | 266 987 | 26.7 | 6 899× |
| 100 000 | 184 206 | 266 987 | 2.7 | 68 991× |

S2 registration is one transaction of about 267k gas whatever the size of the
batch, because only the root and fixed metadata are stored. For a single unit,
S0 is cheaper (217k gas), since it has no Merkle or signature machinery.

### Per-operation costs (`gas-operations.csv`)

| Operation | S0 | S1 | S2 (n = 100) | S2 (n = 100 000) |
| --- | ---: | ---: | ---: | ---: |
| Whole-segment transfer, hop 1 / hop 2 | 34 434 / 34 629 | 41 093 / 41 177 | 41 093 / 41 177 | 41 129 / 41 201 |
| Partial transfer (split) | n/a | n/a | 91 193 | 91 217 |
| Consume, new bitmap word (cold) | 33 570 | 72 354 | 77 649 | 85 308 |
| Consume, same bitmap word (warm) | n/a | n/a | 59 828 | 68 192 |
| Proof length (siblings) | 0 | 0 | 7 (cold) / 6 (warm) | 17 |

A v2 consume costs more than an S0 consume, because it pays for
`ecrecover`, the proof and the larger calldata. About 2.4k gas is added for
every doubling of n, since the proof grows by one sibling. A transfer in S2
moves a whole range for the price of one transaction. In S0 it is one
transaction per unit.

### Full lifecycle per unit (`gas-lifecycle.csv`)

The lifecycle is registration, two whole-lot custody hops, and consumption of
every unit.

| n | S0 | S1 | S2 | S2 vs S0 | S2 vs S1 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 320 283 | 421 614 | 421 606 | +31.6% | 0% |
| 10 | 289 561 | 421 610 | 94 184 | −67.5% | −77.7% |
| 100 | 286 839 | 421 611 | 63 499 | −77.9% | −84.9% |
| 1 000 | 286 839 | 421 611 | 63 318 | −77.9% | −85.0% |
| 10 000 | 286 839 | 421 611 | 65 270 | −77.2% | −84.5% |
| 100 000 | 286 839 | 421 611 | 68 262 | −76.2% | −83.8% |

Results:

- From n = 10 upward, S2 cuts the end-to-end on-chain cost of a unit by
  76–78% against S0. The remaining cost is dominated by consumption, which
  is paid once per sold unit by the relayer.
- S1 (per-unit keys without batching) is 47% more expensive than S0. The
  saving therefore comes from Merkle batching, not from switching to
  signatures.
- After n ≈ 1000 the per-unit lifecycle cost rises slowly, because the deeper
  proofs make each consume more expensive.

## 2. Off-chain cost and label payload (`offchain.csv`)

Measured with Node 20 on the development machine:

- unit key generation (secp256k1);
- `StandardMerkleTree` construction;
- proof size per unit.

| n | Key generation | Tree build | Proof length | Proof bytes |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 0.7 ms | 0.2 ms | 0 | 0 |
| 10 | 3.2 ms | 1.3 ms | 3 | 96 |
| 100 | 32 ms | 10 ms | 7 | 224 |
| 1 000 | 321 ms | 120 ms | 10 | 320 |
| 10 000 | 3.8 s | 1.2 s | 13 | 416 |
| 100 000 | 32.7 s | 13.8 s | 17 | 544 |

- **Labels.** The proof is not printed on the label; the API serves it. Each
  label carries a public URL (about 50 bytes) and a secret QR of
  `satk2:{chain}:{batch}:{index}:` plus 64 hex characters (about 85 bytes). Both
  fit a version-4/5 QR code at error-correction level M.
- **Manifest.** The IPFS manifest grows by one 42-character address per unit,
  about 4.3 MB for 100 000 units. The API caps a batch at 5000 by default
  (`MAX_BATCH_UNITS`).

## 3. Clone detection (`backend/bench/results/scansim_*.csv`)

### Method

`cmd/scansim` generates genuine units and units whose public label was copied
onto fakes. Every scan is replayed through the production `internal/risk`
code, and each unit is classified by its maximum score. The model has 5000
units per class, 31 regions with Zipf-distributed popularity, and a 90-day
horizon. The genuine-unit behaviour includes:

- a pharmacist scan with probability 0.5;
- shelf scans, on average 0.3;
- the buyer scanning at purchase with probability 0.8;
- consumption with probability 0.7;
- buyer re-scans, on average 0.6;
- family devices with probability 0.15;
- travel with probability 0.05.

Clones come with 1, 2, 3, 5, 10 or 20 copies. A copy is sold in the
custodian's region with probability 0.3. The full set of assumptions is
recorded in `scansim_config.json`. The results are means ± standard deviation
over 10 seeds (`scansim_summary.csv`).

### Detectors

| Detector | Precision | Recall | F1 | FPR |
| --- | ---: | ---: | ---: | ---: |
| **Proposed** (noisy-OR, threshold 0.60) | **0.982 ± 0.002** | **0.822 ± 0.004** | **0.895 ± 0.003** | **0.015 ± 0.001** |
| Status only (any scan after consumption) | 0.642 ± 0.005 | 0.673 ± 0.006 | 0.657 ± 0.005 | 0.375 ± 0.006 |
| Scan count > 3 | 0.882 ± 0.004 | 0.871 ± 0.003 | 0.876 ± 0.003 | 0.117 ± 0.004 |
| Scan count > 5 | 0.988 ± 0.002 | 0.654 ± 0.003 | 0.787 ± 0.003 | 0.008 ± 0.001 |
| Distinct devices > 3 | 0.963 ± 0.004 | 0.790 ± 0.005 | 0.868 ± 0.004 | 0.030 ± 0.003 |

The proposed detector has the best F1, and at a false-positive rate 7.6×
lower than the strongest high-recall baseline (scan count > 3). The naive
"scanned after it was consumed" rule, which status-only systems use, flags
37.5% of genuine units, because buyers and their families re-scan.

### Ablation (one rule removed)

| Variant | Precision | Recall | F1 | FPR |
| --- | ---: | ---: | ---: | ---: |
| Full model | 0.982 | 0.822 | 0.895 | 0.015 |
| without `many_devices` | 0.980 | 0.747 | 0.848 | 0.015 |
| without `scanned_after_consumption` | 1.000 | 0.550 | 0.710 | 0.000 |
| without region rules | 0.986 | 0.654 | 0.786 | 0.010 |

Every rule contributes. The post-consumption rule carries the most recall.
The region rules add about 17 points of recall for a 0.6-point rise in FPR.

### Threshold sweep (seed 7, `scansim_thresholds.csv`)

| Threshold | Precision | Recall | F1 | FPR |
| ---: | ---: | ---: | ---: | ---: |
| 0.30 (medium) | 0.843 | 0.957 | 0.896 | 0.178 |
| 0.50 | 0.980 | 0.841 | 0.905 | 0.017 |
| **0.60 (high, deployed)** | **0.982** | **0.828** | **0.898** | **0.015** |
| 0.70 | 0.999 | 0.711 | 0.831 | 0.001 |
| 0.80 | 1.000 | 0.596 | 0.747 | 0.000 |

0.60 was chosen over the slightly higher-F1 0.50 to keep the FPR at about
1.5%, because a false `Suspicious` verdict is shown to a real buyer. The
`medium` level (0.30) is only informational.

### Detection by number of copies (`scansim_by_clones.csv`)

| Copies | Recall | Median clone scans until flagged |
| ---: | ---: | ---: |
| 1 | 0.37 | 1 |
| 2 | 0.70 | 2 |
| 3 | 0.90 | 3 |
| 5 | 0.998 | 3 |
| 10 | 1.00 | 4 |
| 20 | 1.00 | 4 |

A single copy is the hard case (threat T1 in
[`threat-model.md`](threat-model.md)). Once a label is copied three or more
times, 90% of the cloned units are flagged, typically by the third scan of a
copy.

## 4. Functional end-to-end check

`backend/e2e/protocol_v2_test.go` runs in CI against a fresh Hardhat chain,
Postgres and the real API binary. It drives this sequence over HTTP:

1. Register a 100-unit batch. Each unit has a unique key, and the public QR
   encodes chain/batch/index. Re-registering the same lot returns 409.
2. Ship units [0,40) to the distributor. Shipping straight to a pharmacy is
   rejected.
3. The distributor forwards [0,15) to the pharmacy. The batch now shows three
   segments covering all 100 units (60 Created, 25 Transferred,
   15 AtPointOfSale). The pharmacy's stock is one segment of 15.
4. Verify: a unit at the pharmacy is `Authentic` with the pharmacy's profile
   and evidence that matches the root. Units at the distributor and at the
   manufacturer are `InTransit`.
5. Consume:
   - with another unit's key → 400;
   - with the right key → `Consumed` and the consumer recorded;
   - the same unit again (refill) → 409;
   - a unit still at the distributor → 409.
6. Scans of the consumed unit from three new devices in other regions →
   risk `high`, and the unit appears in `/v2/scans/suspicious`.
7. Proof, history and label PDF checks. A forged label key is rejected.
8. Recall: the unit becomes `Recalled`, and consuming after the recall
   returns 409.

## 5. Cost in USD (`cost-operations.csv`, `cost-lifecycle.csv`)

### Method

`npm run bench:cost` turns the measured gas into dollars using one fee
snapshot. The snapshot (`cost-prices.json`) was taken on 2026-10-01 at
15:33 UTC.

- **ETH price.** $2 677.70, the median of Coinbase ($2 677.28), CoinGecko
  ($2 681.70) and Kraken ($2 677.70).
- **Ethereum L1.** `eth_feeHistory` over the last 7168 blocks (about one day,
  up to block 26 098 374). The cost of a transaction is
  `gasUsed × (baseFee + tip)`:
  - median price 0.2098 gwei (base fee 0.1233 + tip 0.0924);
  - 90th percentile 1.0 gwei.
- **Base (OP Stack).** L2 execution at 0.00607 gwei (median over 1024
  blocks) plus the L1 data fee. The data fee comes from
  `GasPriceOracle.getL1Fee` applied to the real unsigned transaction bytes.
- **Arbitrum One.** `(gasUsed + L1 component) × baseFee`, with a base fee of
  0.02018 gwei. The L1 component comes from
  `NodeInterface.gasEstimateL1Component` for the same transaction bytes.

The calldata is built with the contract ABIs. For example, a v2 consume
carries the request, the proof and a 65-byte signature: 740 bytes at
n = 1000 and 964 bytes at n = 100 000.

### Lifecycle cost per unit

The lifecycle is the same as in section 1. Values are USD per unit.

| Network | n | S0 | S1 | S2 | S2 vs S0 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ethereum L1, median fee | 1 000 | 0.161 | 0.237 | 0.036 | −78% |
| Ethereum L1, p90 fee | 1 000 | 0.768 | 1.129 | 0.170 | −78% |
| Arbitrum One | 1 000 | 0.0164 | 0.0239 | 0.0039 | −76% |
| Base | 1 000 | 0.0047 | 0.0069 | 0.0011 | −77% |
| Ethereum L1, median fee | 100 000 | 0.161 | 0.237 | 0.038 | −76% |
| Base | 100 000 | 0.0047 | 0.0069 | 0.0012 | −75% |

### Single operations for S2 at n = 1000 (USD)

| Operation | Gas | L1 median | L1 p90 | Arbitrum | Base |
| --- | ---: | ---: | ---: | ---: | ---: |
| Register the whole lot | 267 002 | 0.150 | 0.715 | 0.0147 | 0.0044 |
| Custody hop (whole segment) | 41 117 | 0.023 | 0.110 | 0.0024 | 0.0007 |
| Consume, cold bitmap word | 79 977 | 0.045 | 0.214 | 0.0048 | 0.0014 |
| Consume, warm bitmap word | 62 900 | 0.035 | 0.168 | 0.0039 | 0.0011 |

Results:

- The relative saving of S2 is the same on every network (−75% to −78%
  against S0). It comes from executing less gas, not from the choice of
  chain.
- On a rollup, the full on-chain lifecycle of a unit costs less than half a
  US cent. Even at the p90 L1 fee it stays below $0.20 per unit, which is
  small next to the retail price of a supplement.
- Consumption dominates the per-unit cost. It is paid by the relayer, so
  the buyer needs no wallet and no ETH.

These figures rest on two assumptions. First, rollup execution gas equals
the gas measured on the Hardhat EVM. Second, fees change quickly, so the
dollar values hold only for the dated snapshot; the gas tables in section 1
are the stable result. `COST_OFFLINE=1` recomputes everything from the
saved snapshot.

## 6. Label payload and label PDF

### QR payload (`label-payload.csv`, `go run ./cmd/labelbench`)

Sizes are worst cases: the largest unit index of a batch with id 100 000.
QR codes use error-correction level M. The module width is for a 34 mm
printed code.

| Chain | n | Public URL | QR | Secret `satk2` | QR | Module (secret) |
| --- | ---: | ---: | :---: | ---: | :---: | ---: |
| Ethereum (1) | 1 000 | 47 chars | v4 | 83 chars | v5 | 0.92 mm |
| Ethereum (1) | 1 048 576 | 51 chars | v4 | 87 chars | v6 | 0.83 mm |
| Base (8453) | 1 000 | 50 chars | v4 | 86 chars | v6 | 0.83 mm |
| Arbitrum (42161) | 1 048 576 | 55 chars | v4 | 91 chars | v6 | 0.83 mm |

The payload grows by one character per decimal digit of the index, not
with the proof, because the proof is never printed. Every label in every
tested configuration fits a version-4 (public) or version-6 (secret) QR
code. Its modules stay above 0.8 mm, which phone cameras read easily.

### Printable label sheet (`label-pdf.csv`, `BenchmarkRenderLabelSheet`)

| Labels per PDF | Render time | Per label | PDF size per label | Total PDF |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 12.8 ms | 1.6 ms | 3.7 KB | 28.9 KB |
| 80 | 196 ms | 2.5 ms | 3.9 KB | 301 KB |
| 400 | 1.17 s | 2.9 ms | 3.9 KB | 1.5 MB |

Each label (two QR codes and its text) adds about 3.9 KB, and a sheet
renders at about 340–620 labels per second on one core. The timings were
taken on a busy laptop, so only their order of magnitude matters.

## 7. API and end-to-end latency (`loadgen.csv`)

### Setup

`scripts/eval/latency.sh` starts a fresh Hardhat node (port 8546), deploys
the contracts, and runs the real API binary (port 3099) on PostgreSQL.
Rate limits are lifted for the test.

- **Hardware.** Intel i7-1165G7 (4 cores, 8 threads) with 15 GB RAM. All
  traffic goes over loopback, so the numbers exclude network RTT.
- **Read endpoints.** 10 s per concurrency level on a 1000-unit batch.
  Verify is measured twice: with the in-process batch-snapshot cache, and
  with `-fresh`, which sends `Cache-Control: no-cache` (honoured outside
  production) so every request reloads the batch, segments and custodian
  profiles.
- **Consume.** Each `cmd/loadgen -endpoint consume` worker takes the next
  unused unit, signs the EIP-712 authorization with its unit key, and calls
  `POST /v2/consume`. The API returns only after the relayer's transaction
  is mined. Hardhat is restarted for each block time (`HARDHAT_BLOCK_TIME_MS`).

### Read endpoints (p50 / p95 in ms, throughput in requests per second)

| Endpoint | 1 client | 8 clients | 32 clients | 64 clients | Peak rps |
| --- | ---: | ---: | ---: | ---: | ---: |
| `GET /v2/verify`, cached | 7.0 / 15.6 | 10.4 / 21.4 | 42.4 / 67.0 | 81.2 / 162.9 | 691 |
| `GET /v2/verify`, no cache | 6.4 / 9.8 | 10.8 / 17.4 | 43.8 / 88.0 | 78.8 / 124.0 | 738 |
| `GET /v2/proof` | 0.20 / 0.32 | 0.55 / 1.10 | 2.07 / 3.58 | 4.32 / 6.72 | 13 978 |
| `GET /v2/batches/{id}` | 0.07 / 0.12 | 0.18 / 0.47 | 0.57 / 2.18 | 0.94 / 4.06 | 46 280 |

Every request succeeded, with no errors and no throttling.

- **Verify** saturates at about 700 requests per second. No endpoint
  touches the chain; all of them read the indexed state in PostgreSQL. What
  makes verify expensive is the clone-detection bookkeeping: every verify
  inserts a scan event and then aggregates that unit's scan history for the
  risk score. Next to that write, the three reads the snapshot cache saves
  hardly matter, so caching barely changes verify latency.
- **Proof** is two primary-key reads.
- **Batch detail** is served straight from the in-memory snapshot.

Both of those are two to three orders of magnitude faster than verify.

### End-to-end consume (sign, relay, mine, confirm)

| Block time | Clients | Requests | p50 | p95 | p99 | Errors |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| instant (automine) | 1 | 688 | 10.0 ms | 18.7 ms | 43.8 ms | 0 |
| instant (automine) | 8 | 812 | 34.3 ms | 92.5 ms | 159 ms | 0 |
| 2 s (L2-like) | 1 | 20 | 2.018 s | 2.029 s | 2.042 s | 0 |
| 2 s (L2-like) | 8 | 168 | 2.015 s | 2.036 s | 2.054 s | 0 |
| 12 s (L1-like) | 1 | 10 | 12.019 s | 12.024 s | 12.024 s | 0 |
| 12 s (L1-like) | 8 | 88 | 12.015 s | 12.030 s | 12.054 s | 0 |

- **The API itself adds about 10 ms.** That covers signature check, proof
  lookup, relayer transaction and receipt.
- **Block time is everything else.** A consume completes in one block with
  no queueing: at 8 concurrent buyers, every block carries up to eight
  consumes from the single relayer.
- **These are upper bounds.** The load generator is closed-loop: a worker
  sends its next request just after the previous block, so it waits almost
  a full interval. A buyer arriving at a random time waits about half a
  block on average (plus up to 500 ms of receipt polling).

## 8. Security analysis (`docs/data/security-analysis.csv`)

The table lists, for threats T1–T15 of [`threat-model.md`](threat-model.md):

- the adversary and its capability;
- the protocol mitigation;
- the residual risk and its level.

Summary:

- **None:** T4, T5, T6, T15.
- **Medium:** T1 (one undetected copy of a public label) and T3 (theft of a
  secret label before sale).
- **Low:** all other threats.

## 9. Figures

Plots are made with matplotlib (`scripts/eval/plots.py`) and diagrams with
TikZ (`scripts/eval/diagrams/`). All are sized for a 3.5-inch single column
or a full-width float, and fonts are embedded as Type 42 (no Type 3 fonts).

| File | Content |
| --- | --- |
| `fig-architecture` | System architecture and the independent verification path |
| `fig-merkle-label` | Merkle batch, two-layer label and the on-chain consume checks |
| `fig-segments` | Splitting custody segments of a 100-unit lot, consume and recall |
| `fig-gas-registration` | Registration gas per unit against n (log–log) |
| `fig-gas-lifecycle` | Lifecycle gas per unit for S0/S1/S2 |
| `fig-gas-consume` | Consume gas and proof length against n |
| `fig-cost-usd` | Lifecycle USD per unit on L1, Arbitrum and Base (n = 1000) |
| `fig-offchain` | Key generation and tree build time against n |
| `fig-clone-detectors` | Precision, recall and F1 of the detectors |
| `fig-clone-ablation` | Rule ablation |
| `fig-clone-threshold` | Threshold sweep |
| `fig-clone-copies` | Recall and detection delay against the number of copies |
| `fig-latency-read` | Read-endpoint p50/p95 against concurrency |
| `fig-latency-consume` | End-to-end consume latency by block time |

## 10. Limitations

- **Single machine.** Latency was measured on one laptop over loopback.
  Real deployments add network RTT and run PostgreSQL on separate hardware.
- **Emulated block times.** Hardhat interval mining reproduces block
  inclusion delay, but not mempool competition or reorgs.
- **Rollup gas.** Rollup execution gas is assumed equal to the measured
  EVM gas, and L1 data fees are quoted by the rollups' own oracles.
- **One price snapshot.** All USD values come from a single dated snapshot.
  The ratios between schemes do not depend on it; the absolute values do.
- **Simulated clones.** Clone detection runs on simulated scan traces whose
  parameters are documented, not on field data.
- **Noisy PDF timing.** Label-rendering times were taken on a busy laptop.

## Reproduce

```bash
# Gas and off-chain tables (BENCH_SIZES=1,10,100 for a quick run)
cd contracts && npm run bench:gas

# USD cost: live snapshot (needs network) or the saved one
cd contracts && npm run bench:cost
cd contracts && COST_OFFLINE=1 npm run bench:cost

# Clone-detection simulation (10 seeds, 5000 units per class)
cd backend && go run ./cmd/scansim -seed 7 -units 5000 -runs 10

# Label payload and label PDF
cd backend && go run ./cmd/labelbench
cd backend && go test -run '^$' -bench RenderLabelSheet -benchtime 20x ./internal/protocol/

# API and consume latency (Hardhat 8546, API 3099; about 15 minutes)
TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:5432/supplement_tracker ./scripts/eval/latency.sh

# Figures (Python venv with scripts/eval/requirements.txt; TeX Live for TikZ)
python scripts/eval/plots.py
bash scripts/eval/diagrams/build.sh

# End-to-end suite (Hardhat on 8546, API on 3099, Postgres via TEST_DATABASE_URL)
TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:5432/supplement_tracker ./scripts/ci-integration.sh
```
