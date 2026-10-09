#!/usr/bin/env bash
# Local one-click demo: Hardhat (v1 + v2) → Docker Postgres/IPFS → Go API →
# grant Distributor/Pharmacy roles → register a small batch → print QR samples.
#
# Requires Docker, Node 20+, and Go 1.26+ (GOTOOLCHAIN=auto downloads it).
# Leaves Hardhat and the API running in the background; Ctrl-C or
# `docker compose stop` / killing the PIDs recorded under /tmp/sat-demo.*.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

HH_PORT="${HH_PORT:-8545}"
API_PORT="${API_PORT:-3000}"
RPC="http://127.0.0.1:${HH_PORT}"
PIDS_FILE=/tmp/sat-demo.pids
LOG_DIR=/tmp/sat-demo
mkdir -p "$LOG_DIR"
: >"$PIDS_FILE"

cleanup_on_fail() {
  echo "Demo failed; stopping background processes…" >&2
  if [[ -f "$PIDS_FILE" ]]; then
    while read -r pid; do kill "$pid" 2>/dev/null || true; done <"$PIDS_FILE"
  fi
}
trap cleanup_on_fail ERR

wait_for() {
  local what="$1" cmd="$2"
  for _ in $(seq 1 60); do
    if eval "$cmd" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "timed out waiting for ${what}" >&2
  return 1
}

track() {
  echo "$1" >>"$PIDS_FILE"
}

echo "==> Contracts: install + compile"
cd "$ROOT/contracts"
if [[ ! -d node_modules ]]; then
  npm ci --silent
fi
npx hardhat compile

if curl -fsS -X POST -H 'content-type: application/json' \
    --data '{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}' \
    "$RPC" >/dev/null 2>&1; then
  echo "==> Reusing Hardhat already listening on ${RPC}"
else
  echo "==> Starting Hardhat node on 0.0.0.0:${HH_PORT}"
  npx hardhat node --hostname 0.0.0.0 --port "$HH_PORT" >"$LOG_DIR/hardhat.log" 2>&1 &
  track $!
  wait_for "hardhat" "curl -fsS -X POST -H 'content-type: application/json' --data '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_chainId\",\"params\":[]}' $RPC"
fi

echo "==> Deploying SupplementRegistry + SupplementRegistryV2"
LOCALHOST_RPC_URL="$RPC" npx hardhat run scripts/deploy.ts --network localhost | tee "$LOG_DIR/deploy.log"
REG="$(grep -o 'SupplementRegistry=0x[0-9a-fA-F]*' "$LOG_DIR/deploy.log" | cut -d= -f2)"
REG_V2="$(grep -o 'SupplementRegistryV2=0x[0-9a-fA-F]*' "$LOG_DIR/deploy.log" | cut -d= -f2)"
echo "REGISTRY_ADDRESS=${REG}"
echo "REGISTRY_V2_ADDRESS=${REG_V2}"

echo "==> Docker: Postgres + IPFS"
cd "$ROOT"
docker compose up -d postgres ipfs
# Prefer the host-run API so the v2 ABI path and RPC defaults stay correct.
docker compose stop backend >/dev/null 2>&1 || true

echo "==> Backend API on :${API_PORT}"
cd "$ROOT/backend"
if [[ ! -f .env ]]; then
  cp .env.example .env
fi
RELAYER_KEYS_JSON="$(grep -o '^RELAYER_KEYS_JSON=.*' .env.example | cut -d= -f2-)"
DATABASE_URL="${DATABASE_URL:-postgresql://postgres:postgres@127.0.0.1:5432/supplement_tracker?schema=public}" \
  PORT="$API_PORT" RPC_URL="$RPC" CHAIN_ID=31337 \
  REGISTRY_ADDRESS="$REG" REGISTRY_V2_ADDRESS="$REG_V2" \
  ALLOW_IPFS_STUB=true APP_ENV=development SCAN_HASH_SALT=dev-only-change-me \
  VERIFY_RATE_LIMIT=1000 RELAYER_KEYS_JSON="$RELAYER_KEYS_JSON" \
  go run ./cmd/api >"$LOG_DIR/api.log" 2>&1 &
track $!
wait_for "api /v2/health" "curl -fsS http://127.0.0.1:${API_PORT}/v2/health | grep -q configured"

API="http://127.0.0.1:${API_PORT}"
echo "==> Granting Distributor and Pharmacy roles (Hardhat accounts #1 and #2)"
curl -fsS -X POST "$API/v2/roles" -H 'content-type: application/json' \
  -d '{"address":"0x70997970c51812dc3a010c7d01b50e0d17dc79c8","role":"Distributor","displayName":"Pakhsh Co","region":"tehran"}' \
  | tee "$LOG_DIR/role-distributor.json"
echo
curl -fsS -X POST "$API/v2/roles" -H 'content-type: application/json' \
  -d '{"address":"0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc","role":"Pharmacy","displayName":"Darou Pharmacy","region":"tehran"}' \
  | tee "$LOG_DIR/role-pharmacy.json"
echo

LOT="DEMO-$(date +%s)"
echo "==> Registering a 5-unit demo batch (lot ${LOT})"
REGISTER_JSON="$(curl -fsS -X POST "$API/v2/batches" -H 'content-type: application/json' \
  -d "{\"name\":\"Demo Vitamin D3\",\"lotCode\":\"${LOT}\",\"size\":5}")"
echo "$REGISTER_JSON" | tee "$LOG_DIR/register.json"
BATCH_ID="$(echo "$REGISTER_JSON" | sed -n 's/.*"batchId"[[:space:]]*:[[:space:]]*"\?\([0-9][0-9]*\)"\?.*/\1/p' | head -1)"
PUBLIC_QR="$(echo "$REGISTER_JSON" | sed -n 's/.*"publicQr"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"
SECRET_QR="$(echo "$REGISTER_JSON" | sed -n 's/.*"secretQr"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -1)"

echo "==> Listing batches"
curl -fsS "$API/v2/batches?limit=5" | tee "$LOG_DIR/list.json"
echo
echo
echo "Demo ready."
echo "  Health:       curl $API/v2/health"
echo "  Batch:        $BATCH_ID"
echo "  Public QR:    ${PUBLIC_QR:-<see $LOG_DIR/register.json>}"
echo "  Secret QR:    ${SECRET_QR:-<see $LOG_DIR/register.json>}"
echo "  Admin panel:  cd admin-web && python3 -m http.server 8081"
echo "  Android:      open android/ in Studio, run flavor local"
echo "  Logs:         $LOG_DIR/  PIDs: $PIDS_FILE"
echo
# Disarm the fail-only trap so exiting 0 does not kill the stack.
trap - ERR
