# Contracts

`SupplementRegistry` is **intentionally non-upgradeable** (`UPGRADEABLE = false`). There is no UUPS/transparent proxy. Protocol fixes require a new deployment, ABI bump (`abiVersion`), and address cutover in `packages/abis/deployments.json`.

```bash
cd contracts && npm install && npx hardhat test
```

Local deploy (writes address + ABI under `packages/abis/`):

```bash
cd contracts && npm run deploy:local
```

Gas report:

```bash
cd contracts && npm run test:gas
```

Coverage:

```bash
cd contracts && npm run test:coverage
```

Gas packing note: `Product` stores `owner` + `status` + `exists` contiguously before `bytes32` fields and keeps dynamic `metadataCid` last to reduce storage slots on register/consume paths.

Registration requires a non-zero `physicalId`; the same id cannot be minted twice (`PhysicalIdAlreadyRegistered`).

`consume` is allowed only when status is `AtPointOfSale`, requires the scratch secret, and emits `ProductConsumed`. A second consume reverts with `ProductAlreadyConsumed`.

Admin `invalidate` marks an active product `Invalid` and emits `ProductInvalidated`.

E2E supply-chain path (register → distributor → pharmacy):

```bash
cd contracts && npm run e2e:local
```

Sepolia deploy (set `SEPOLIA_RPC_URL` and `SEPOLIA_PRIVATE_KEY`):

```bash
cd contracts && cp .env.example .env
# fill secrets, then:
cd contracts && npm run deploy:sepolia
```

Slither (Critical/High fail the run):

```bash
cd contracts && npm run slither
```

Persistent local node:

```bash
cd contracts && npm run node
# other terminal
cd contracts && npx hardhat run scripts/deploy.ts --network localhost
```
