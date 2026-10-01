# Optional The Graph subgraph

The Go backend indexer remains the **primary** projection for verify/history APIs.

This folder is an optional Graph Protocol scaffold for environments that prefer a hosted or local Graph Node. Enable preference via `FF_SUBGRAPH=true` (clients may prefer Graph URLs when set; backend APIs stay on Postgres).

## Layout

- `subgraph.yaml`: two data sources on local Hardhat
  - `SupplementRegistry` (v1, one record per product)
  - `SupplementRegistryV2` (v2, Merkle batches, custody segments, unit consumption, recall)
- `schema.graphql`
  - v1: `Product`, `OwnershipTransfer`
  - v2: `Batch`, `Segment`, `SegmentTransfer`, `UnitConsumption`, `Recall`
- `src/mapping.ts`: v1 handlers
- `src/mappingV2.ts`: v2 handlers
- `scripts/extract-abis.mjs`: unwraps `packages/abis/*.json` artifacts into raw ABI arrays under `abis/` (git-ignored); runs automatically before `codegen` and `build`

## v2 indexing model

| Event | Effect |
| --- | --- |
| `BatchRegistered` | creates `Batch` and its root `Segment` `[0, size)` owned by the manufacturer |
| `SegmentTransferred` | full move: re-owns the segment; partial move: the head `[start, end)` becomes a new `Segment` (with `parent`) and the source keeps the tail. Always records a `SegmentTransfer` |
| `UnitConsumed` | creates `UnitConsumption` (`batchId-index`) and increments `Batch.consumedCount` |
| `BatchInvalidated` | marks `Batch.recalled` and records a `Recall` |
| `SegmentInvalidated` | marks the segment `Invalid` and records a `Recall` with the segment |

This mirrors `transferSegment` in `contracts/contracts/SupplementRegistryV2.sol`, so segment ranges match `GET /v2/batches/:id` from the backend.

Example query:

```graphql
{
  batches(orderBy: registeredAtBlock, orderDirection: desc, first: 5) {
    id
    size
    consumedCount
    recalled
    segments { id start end owner status }
  }
}
```

## Local use

1. Deploy the contracts. Then check `source.address` and `startBlock` in `subgraph.yaml` against `packages/abis/deployments.json`.
2. Run a Graph Node + IPFS.
3. Build the subgraph:

```bash
cd subgraph && npm install && npm run codegen && npm run build
```
