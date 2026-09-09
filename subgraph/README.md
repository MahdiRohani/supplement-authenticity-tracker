# Optional The Graph subgraph

The NestJS indexer remains the **primary** projection for verify/history APIs.

This folder is an optional Graph Protocol scaffold for environments that prefer a hosted or local Graph Node. Enable preference via `FF_SUBGRAPH=true` (clients may prefer Graph URLs when set; backend APIs stay on Postgres).

## Layout

- `subgraph.yaml` — data source pointing at local Hardhat address
- `schema.graphql` — Product + OwnershipTransfer entities
- `src/mapping.ts` — event handlers including `ProductInvalidated`

## Local use

1. Deploy `SupplementRegistry` and update `source.address` in `subgraph.yaml`.
2. Run a Graph Node + IPFS.
3. `cd subgraph && npm install && npm run codegen && npm run build`

ABI is loaded from `packages/abis/SupplementRegistry.json` (artifact wrapper). Graph CLI expects a raw ABI array in some versions — if codegen fails, extract the `abi` field into `abis/SupplementRegistry.json` and retarget the YAML path.
