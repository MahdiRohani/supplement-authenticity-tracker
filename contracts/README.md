# Contracts

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

Persistent local node:

```bash
cd contracts && npm run node
# other terminal
cd contracts && npx hardhat run scripts/deploy.ts --network localhost
```
