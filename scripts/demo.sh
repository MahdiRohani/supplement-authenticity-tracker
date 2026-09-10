#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

echo "==> Contracts: compile + local deploy"
cd "$ROOT/contracts"
npm install --silent
npx hardhat compile
npx hardhat run scripts/deploy.ts --network hardhat > /tmp/sat-deploy.log
REGISTRY_ADDRESS="$(grep -o 'SupplementRegistry=0x[0-9a-fA-F]*' /tmp/sat-deploy.log | cut -d= -f2 || true)"
echo "REGISTRY_ADDRESS=${REGISTRY_ADDRESS:-from packages/abis}"

echo "==> Contracts: e2e consume/anti-refill"
npm run e2e:local

echo "==> Docker stack: Postgres + IPFS + Backend"
cd "$ROOT"
export REGISTRY_ADDRESS="${REGISTRY_ADDRESS:-}"
# Leave RPC unset unless a live Hardhat/Sepolia node is available (indexer stays off).
unset RPC_URL
docker compose up -d --build postgres ipfs backend

echo "==> Waiting for backend health"
for i in $(seq 1 40); do
  if curl -fsS "http://127.0.0.1:3000/v1/health" >/dev/null 2>&1; then
    echo "Backend is up"
    break
  fi
  sleep 2
  if [[ "$i" -eq 40 ]]; then
    echo "Backend did not become healthy in time" >&2
    docker compose logs backend | tail -80 >&2
    exit 1
  fi
done

echo "==> Demo API: register unit"
REGISTER_JSON="$(curl -fsS -X POST "http://127.0.0.1:3000/v1/products" \
  -H 'content-type: application/json' \
  -d '{"name":"Demo Vitamin D3","batch":"DEMO-001"}')"
echo "$REGISTER_JSON" | tee /tmp/sat-register.json

echo "==> Demo API: list products"
curl -fsS "http://127.0.0.1:3000/v1/products?limit=5" | tee /tmp/sat-list.json
echo
echo "Demo complete. Artifacts: /tmp/sat-register.json /tmp/sat-list.json"
echo "Android: assemble localDebug against http://10.0.2.2:3000/v1/"
