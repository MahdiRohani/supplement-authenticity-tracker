#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> contracts unit tests"
cd "$ROOT/contracts"
npm ci
npm test

echo "==> start hardhat node"
npx hardhat node > /tmp/sat-hh.log 2>&1 &
HH_PID=$!
trap 'kill $HH_PID 2>/dev/null || true' EXIT
sleep 3
npx hardhat run scripts/deploy.ts --network localhost | tee /tmp/sat-deploy-ci.log
REG="$(grep -o 'SupplementRegistry=0x[0-9a-fA-F]*' /tmp/sat-deploy-ci.log | cut -d= -f2)"

echo "==> backend vet + tests (set TEST_DATABASE_URL to include Postgres integration tests)"
cd "$ROOT/backend"
go vet ./...
go test -race -count=1 ./...

echo "==> backend build"
go build -o bin/api ./cmd/api

if [[ -n "${TEST_DATABASE_URL:-}" ]]; then
  echo "==> protocol v2 end-to-end against Hardhat + Postgres"
  RELAYER_KEYS_JSON="$(grep -o '^RELAYER_KEYS_JSON=.*' .env.example | cut -d= -f2-)"
  E2E_PORT="${E2E_PORT:-3099}"
  SEP='?'
  [[ "$TEST_DATABASE_URL" == *\?* ]] && SEP='&'
  DATABASE_URL="${TEST_DATABASE_URL}${SEP}schema=ci_e2e_$$" PORT="$E2E_PORT" RPC_URL=http://127.0.0.1:8545 \
    CHAIN_ID=31337 ALLOW_IPFS_STUB=true IPFS_API_URL=http://127.0.0.1:1 APP_ENV=test SCAN_HASH_SALT=ci-salt \
    RELAYER_KEYS_JSON="$RELAYER_KEYS_JSON" ./bin/api > /tmp/sat-api-e2e.log 2>&1 &
  API_PID=$!
  trap 'kill $HH_PID $API_PID 2>/dev/null || true' EXIT
  for _ in $(seq 1 40); do
    curl -fsS "http://127.0.0.1:${E2E_PORT}/v2/health" >/dev/null 2>&1 && break
    sleep 1
  done
  E2E_API_URL="http://127.0.0.1:${E2E_PORT}" go test -tags e2e -count=1 ./e2e/ || { cat /tmp/sat-api-e2e.log; exit 1; }
fi

echo "==> clone-detection simulation smoke"
go run ./cmd/scansim -units 500 -runs 2 -out /tmp/sat-scansim

echo "Integration smoke ready. REGISTRY_ADDRESS=${REG}"
echo "Start Postgres and backend/bin/api locally to run scripts/load-verify.sh"
