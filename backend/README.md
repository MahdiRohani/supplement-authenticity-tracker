# Backend

```bash
cd backend && cp .env.example .env && npm install && npx prisma generate && npm run start:dev
```

Requires Postgres matching `DATABASE_URL` in `.env`.

Useful endpoints:

- `GET /v1/health`
- `POST /v1/products` with `{ "name": "...", "batch": "..." }`
- `POST /v1/products/:id/transfer` with `{ "toAddress": "0x..." }` (backend relayer)
- `GET /v1/products/:chainProductId`
- `GET /v1/products/:id/history`
- `GET /v1/roles`, `POST /v1/roles`, `GET /v1/roles/:address`, `DELETE /v1/roles/:address/:role`

Indexer polls `RPC_URL` for `ProductRegistered` and `OwnershipTransferred`. Relayer keys come from `RELAYER_KEYS_JSON`.
