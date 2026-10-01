# Admin web (minimal)

Static ops panel for demo/support use. Serve with any static file server:

```bash
cd admin-web
python3 -m http.server 8080
```

Open `http://127.0.0.1:8080` and set the **API origin** to the backend (`http://127.0.0.1:3000`). The panel appends `/v1/...` and `/v2/...` itself; an old value ending in `/v1` or `/v2` is accepted.

The **API key** field is sent as `X-Api-Key`. With `API_WRITE_KEY` set on the backend it is required for every non-public route, which includes the v2 batch and segment lists, suspicious scans, reports and recall.

## Sections

| Section | Endpoint(s) |
| --- | --- |
| Health | `GET /v1/health`, `GET /v2/health` (database and protocol checks) |
| Feature flags | `GET /v2/flags` |
| Active chain | `GET /v2/chains` (RegistryV2 address, EIP-712 domain, public QR base) |
| Analytics | `GET /v2/analytics/snapshot` |
| Batches | `GET /v2/batches?page&limit&manufacturer`, expand: `GET /v2/batches/:id` (stage distribution and custody segments) |
| Recall | `POST /v2/batches/:id/recall` with `{ "segmentId"?, "reason"? }` (whole batch or one segment) |
| Clone suspects | `GET /v2/scans/suspicious?sinceHours&minRisk&limit` |
| Counterfeit reports | `GET /v2/reports/counterfeit?limit=20` (v2 reports carry the unit path `chain/batch/index`) |

Each section loads independently, so a backend without `SupplementRegistryV2` (v2 routes answer 503) still shows the v1 health and the error inline. All server values are HTML-escaped before rendering.
