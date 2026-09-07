# Backend

```bash
cd backend && cp .env.example .env && npm install && npx prisma generate && npm run start:dev
```

Requires Postgres matching `DATABASE_URL` in `.env`.

Useful endpoints:

- `GET /v1/health`
- `GET /v1/verify/:id` — public authenticity check (in-memory TTL cache + IPFS metadata resolve)
- `POST /v1/products` with `{ "name": "...", "batch": "..." }` (returns scratch `secret` once; only hash is used on-chain)
- `POST /v1/products/:id/transfer` with `{ "toAddress": "0x..." }` (backend relayer)
- `POST /v1/products/:id/consume` with `{ "secret": "0x..." }` (anti-refill; already-consumed returns 409)
- `GET /v1/products/:chainProductId`
- `GET /v1/products/:id/history`
- `GET /v1/roles`, `POST /v1/roles`, `GET /v1/roles/:address`, `DELETE /v1/roles/:address/:role`

```bash
cd backend && npm test
```

Indexer polls `RPC_URL` for `ProductRegistered`, `OwnershipTransferred`, and `ProductConsumed`. Relayer keys come from `RELAYER_KEYS_JSON`. Sensitive actions write `AuditLog` rows. Verify/consume endpoints are rate-limited.