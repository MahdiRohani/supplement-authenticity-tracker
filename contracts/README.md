# Contracts

Two registries live side by side. Both are **intentionally non-upgradeable** (`UPGRADEABLE = false`); protocol changes ship as a new deployment plus an `abiVersion` bump in `packages/abis`.

| Contract | Role |
| --- | --- |
| `SupplementRegistry` (v1, ABI `1.3.0`) | Per-unit registry with a scratch-secret hash. Used by the current backend/Android and kept as the evaluation baseline. |
| `SupplementRegistryV2` (ABI `2.0.0`) | Merkle-batch registration, splittable custody segments, gasless signature-based consumption, recall. |

```bash
cd contracts && npm install && npx hardhat test
```

## SupplementRegistryV2

- **Issuance.** Every unit gets a one-time keypair. The batch is committed by one Merkle root over leaves `keccak256(bytes.concat(keccak256(abi.encode(uint32 index, address unitKey))))` (`@openzeppelin/merkle-tree` `StandardMerkleTree`, types `["uint32","address"]`). `registerBatch(root, size, metadataCid, metadataHash, physicalBatchId)` costs the same for 1 or 1,000,000 units (`MAX_BATCH_SIZE = 2^20`).
- **Custody.** A segment is a contiguous index range `[start, end)` of one batch with an owner and status `Created → Transferred → AtPointOfSale` (manufacturer → distributor → pharmacy, role-checked). `transferSegment(segmentId, to, count)` moves the whole segment (id kept) or splits off the first `count` units into a new segment.
- **Consumption.** `consume(request, proof, signature)` may be sent by anyone (a relayer pays gas). It requires an EIP-712 `ConsumeAuthorization(uint256 batchId,uint32 index,address consumer,uint256 deadline)` signed by the unit key (domain `SupplementRegistry` / `2`), a Merkle proof that the key belongs to `index`, and a segment at the point of sale that contains `index`. The secret never appears on-chain, a copied transaction cannot change the recorded consumer, and a per-batch bitmap rejects a second use.
- **Recall.** `invalidateBatch` / `invalidateSegment` by the admin or the batch's manufacturer; recall takes precedence over consumption in `unitStatus`.
- **Views.** `getBatch`, `getSegment`, `unitStatus(batchId, index, segmentId)`, `isConsumed`, `batchIdByPhysicalId`, `unitLeaf`, `hashConsumeAuthorization`, `eip712Domain`.

Off-chain helpers shared by tests, scripts and the benchmark are in `scripts/lib/v2.ts`.

## SupplementRegistry (v1)

Registration requires a non-zero `physicalId`; the same id cannot be minted twice (`PhysicalIdAlreadyRegistered`). `consume` is allowed only at `AtPointOfSale`, requires the scratch secret and emits `ProductConsumed`; a second consume reverts with `ProductAlreadyConsumed`. Admin `invalidate` marks an active product `Invalid`.

Gas packing note: `Product` stores `owner` + `status` + `exists` contiguously before `bytes32` fields and keeps dynamic `metadataCid` last.

## Scripts

| Command | What it does |
| --- | --- |
| `npm run deploy:local` | Deploys v1 then v2; writes `packages/abis/*.json` and `deployments.json` (with `deployBlock`) |
| `npm run e2e:local` | v2 lifecycle: batch of 10 → distributor → split over two pharmacies → relayed consume, refill/forged-key/recall rejections |
| `npm run e2e:v1` | v1 lifecycle with anti-refill check (`BACKEND_URL` optionally prints `/v1` history) |
| `npm run vectors` | Regenerates `packages/abis/test-vectors/supplement-registry-v2.json` |
| `npm run bench:gas` | Gas benchmark S0 (v1) / S1 (v2, single-unit batches) / S2 (v2, Merkle batches); CSVs in `bench/results/` (`BENCH_SIZES=1,10,100` for a quick run) |
| `npm run test:gas` | Hardhat gas reporter |
| `npm run test:coverage` | solidity-coverage |
| `npm run slither` | Slither (High fails the run) |

Sepolia deploy (set `SEPOLIA_RPC_URL` and `SEPOLIA_PRIVATE_KEY`):

```bash
cd contracts && cp .env.example .env
# fill secrets, then:
cd contracts && npm run deploy:sepolia
```

Persistent local node:

```bash
cd contracts && npm run node
# other terminal
cd contracts && npx hardhat run scripts/deploy.ts --network localhost
```
