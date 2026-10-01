#!/usr/bin/env bash
# Contracts + backend integration. With TEST_DATABASE_URL set it also runs the
# protocol v2 end-to-end suite (100-unit batch, two partial custody hops,
# gasless consume, refill rejected with 409, clone scans, recall) against a
# fresh Hardhat node and the real API binary.
#
#   HH_PORT       Hardhat JSON-RPC port (default 8546, so a dev node on 8545 is untouched)
#   E2E_PORT      API port for the end-to-end run (default 3099)
#   FORCE_NPM_CI  1 reinstalls contracts/node_modules even when it exists
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HH_PORT="${HH_PORT:-8546}"
E2E_PORT="${E2E_PORT:-3099}"
RPC="http://127.0.0.1:${HH_PORT}"
PIDS=()
ABI_BACKUP="$(mktemp -d)"
cleanup() {
  kill "${PIDS[@]}" 2>/dev/null || true
  # deploy.ts rewrites the shared ABI files for this throwaway chain; put the committed ones back.
  cp "$ABI_BACKUP"/*.json "$ROOT/packages/abis/" 2>/dev/null || true
  rm -rf "$ABI_BACKUP"
}
trap cleanup EXIT

wait_for() {
  local what="$1" cmd="$2"
  for _ in $(seq 1 60); do
    if eval "$cmd" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "timed out waiting for ${what}" >&2
  return 1
}

echo "==> contracts unit tests + in-process lifecycle scripts"
cd "$ROOT/contracts"
if [[ ! -d node_modules || "${FORCE_NPM_CI:-0}" == 1 ]]; then
  npm ci
fi
npm test
npm run e2e:local
npm run e2e:v1

echo "==> start hardhat node on ${RPC}"
./node_modules/.bin/hardhat node --port "$HH_PORT" > /tmp/sat-hh.log 2>&1 &
PIDS+=($!)
wait_for "hardhat" "curl -fsS -X POST -H 'content-type: application/json' --data '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_chainId\",\"params\":[]}' $RPC"
cp "$ROOT"/packages/abis/*.json "$ABI_BACKUP/"
LOCALHOST_RPC_URL="$RPC" npx hardhat run scripts/deploy.ts --network localhost | tee /tmp/sat-deploy-ci.log
REG="$(grep -o 'SupplementRegistry=0x[0-9a-fA-F]*' /tmp/sat-deploy-ci.log | cut -d= -f2)"
REG_V2="$(grep -o 'SupplementRegistryV2=0x[0-9a-fA-F]*' /tmp/sat-deploy-ci.log | cut -d= -f2)"

echo "==> backend gofmt + vet + tests (set TEST_DATABASE_URL to include Postgres integration tests)"
cd "$ROOT/backend"
test -z "$(gofmt -l .)" || { gofmt -l .; exit 1; }
go vet ./...
go vet -tags e2e ./e2e/
go test -race -count=1 ./...

echo "==> backend build"
go build -o bin/api ./cmd/api

if [[ -n "${TEST_DATABASE_URL:-}" ]]; then
  echo "==> protocol v2 end-to-end against Hardhat + Postgres"
  RELAYER_KEYS_JSON="$(grep -o '^RELAYER_KEYS_JSON=.*' .env.example | cut -d= -f2-)"
  SEP='?'
  [[ "$TEST_DATABASE_URL" == *\?* ]] && SEP='&'
  DATABASE_URL="${TEST_DATABASE_URL}${SEP}schema=ci_e2e_$$" PORT="$E2E_PORT" RPC_URL="$RPC" \
    CHAIN_ID=31337 REGISTRY_ADDRESS="$REG" REGISTRY_V2_ADDRESS="$REG_V2" \
    ALLOW_IPFS_STUB=true IPFS_API_URL=http://127.0.0.1:1 APP_ENV=test SCAN_HASH_SALT=ci-salt \
    VERIFY_RATE_LIMIT=1000 RELAYER_KEYS_JSON="$RELAYER_KEYS_JSON" ./bin/api > /tmp/sat-api-e2e.log 2>&1 &
  PIDS+=($!)
  wait_for "api" "curl -fsS http://127.0.0.1:${E2E_PORT}/v2/health"
  E2E_API_URL="http://127.0.0.1:${E2E_PORT}" go test -tags e2e -count=1 -v ./e2e/ || { cat /tmp/sat-api-e2e.log; exit 1; }
fi

echo "==> clone-detection simulation smoke"
go run ./cmd/scansim -units 500 -runs 2 -out /tmp/sat-scansim

echo "Integration ready. SupplementRegistry=${REG} SupplementRegistryV2=${REG_V2} (RPC ${RPC})"
