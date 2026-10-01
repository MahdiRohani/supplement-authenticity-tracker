# Evaluation

How protocol v2 is evaluated, the results collected so far, and how to
reproduce them. Raw data lives next to the tools that produce it:

- `contracts/bench/results/`: gas and off-chain costs
- `backend/bench/results/`: clone-detection simulation

Every number below is read from those CSV files.

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

## 5. Still to measure (Phase 5)

- **USD cost** on Ethereum L1 and on an L2, using a dated gas price and ETH
  price. Optionally a real deployment on an L2 testnet.
- **API latency.** `cmd/loadgen` for p50/p95/p99 of verify, proof and batch
  at concurrency 1, 8, 32 and 64, with and without the snapshot cache, plus
  the end-to-end consume latency (sign, relay, confirmation).
- **Plots** (matplotlib) and diagrams (TikZ) at 600 dpi for the paper and the
  thesis.

## Reproduce

```bash
# Gas and off-chain tables (BENCH_SIZES=1,10,100 for a quick run)
cd contracts && npm run bench:gas

# Clone-detection simulation (10 seeds, 5000 units per class)
cd backend && go run ./cmd/scansim -seed 7 -units 5000 -runs 10

# Latency (needs a running API; raise VERIFY_RATE_LIMIT first)
cd backend && go run ./cmd/loadgen -url http://127.0.0.1:3000 -setup -levels 1,8,32,64

# End-to-end suite (Hardhat on 8546, API on 3099, Postgres via TEST_DATABASE_URL)
TEST_DATABASE_URL=postgresql://postgres:postgres@127.0.0.1:5432/supplement_tracker ./scripts/ci-integration.sh
```
