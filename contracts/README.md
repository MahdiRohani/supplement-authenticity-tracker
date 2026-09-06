# Contracts

```bash
cd contracts && npm install && npx hardhat test
```

Local deploy (writes address + ABI under `packages/abis/`):

```bash
cd contracts && npm run deploy:local
```

Persistent local node:

```bash
cd contracts && npm run node
# other terminal
cd contracts && npx hardhat run scripts/deploy.ts --network localhost
```
