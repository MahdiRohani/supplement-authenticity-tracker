# Admin web (minimal)

Static ops panel for demo/support use. Serve with any static file server:

```bash
cd admin-web
python3 -m http.server 8080
```

Open `http://127.0.0.1:8080` and point the form at the Nest API (`http://127.0.0.1:3000/v1`).

Shows health, feature flags, multi-chain deployments, counterfeit reports, and privacy analytics snapshot.
