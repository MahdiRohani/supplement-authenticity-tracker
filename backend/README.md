# Backend

```bash
cd backend && cp .env.example .env && npm install && npx prisma generate && npm run start:dev
```

Requires Postgres matching `DATABASE_URL` in `.env`.

Useful endpoints:

- `GET /v1/health`
- `GET /v1/verify/:id` — public authenticity check (in-memory TTL cache + IPFS metadata resolve)
- `GET /v1/products?owner=&status=&q=&page=&limit=` — searchable paginated inventory
- `POST /v1/products` with `{ "name": "...", "batch": "..." }` (returns scratch `secret` once; only hash is used on-chain)
- `POST /v1/products/batch` with `{ "name": "...", "batch": "...", "count": 5 }`
- `POST /v1/products/:id/transfer` with `{ "toAddress": "0x..." }` (backend relayer)
- `POST /v1/products/:id/consume` with `{ "secret": "0x..." }` (anti-refill; already-consumed returns 409)
- `GET /v1/products/:chainProductId`
- `GET /v1/products/:id/history`
- `GET /v1/roles`, `POST /v1/roles`, `GET /v1/roles/:address`, `DELETE /v1/roles/:address/:role`

Startup validates env with Zod (`src/config/env.validation.ts`). Copy `.env.example` → `.env` and never commit secrets.

```bash
cd backend && npm test
```

Docker: from repo root `docker compose up -d --build` (Postgres + Kubo IPFS + Backend).

Indexer polls `RPC_URL` for `ProductRegistered`, `OwnershipTransferred`, and `ProductConsumed`. Relayer keys come from `RELAYER_KEYS_JSON`. Sensitive actions write `AuditLog` rows. Verify/consume endpoints are rate-limited.