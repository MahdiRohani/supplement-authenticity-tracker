# Backend

```bash
cd backend && cp .env.example .env && npm install && npx prisma generate && npm run start:dev
```

Requires Postgres matching `DATABASE_URL` in `.env`.

Useful endpoints:

- `GET /v1/health`
- `POST /v1/products` with `{ "name": "...", "batch": "..." }` — pins metadata via IPFS adapter (stub CID if Kubo is down) and stores a DB row
- `GET /v1/products/:chainProductId`
- `GET /v1/products/:id/history` — ownership timeline from indexed `OwnershipEvent` rows (target SLA &lt; 3s)

Indexer polls `RPC_URL` for `ProductRegistered` and `OwnershipTransferred` when `REGISTRY_ADDRESS` (or ABI file address) is set. Transfers are written to `OwnershipEvent` and product owner/status are updated.
