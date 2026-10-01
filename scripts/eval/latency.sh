#!/usr/bin/env bash
# API latency for the evaluation: read paths (verify with and without the
# snapshot cache, proof, batch) at several concurrency levels, then the
# end-to-end gasless consume under three block times. Everything runs on one
# machine over loopback; results go to backend/bench/results/loadgen.csv.
#
#   TEST_DATABASE_URL  Postgres to use (required; a throwaway schema is created)
#   HH_PORT            Hardhat port (default 8546; a dev node on 8545 is untouched)
#   API_PORT           API port (default 3099)
#   DURATION           seconds per read level (default 10)
#   BLOCK_TIMES        consume block times in ms, 0 = mine per transaction (default "0 2000 12000")
#   LABEL              free-form hardware label stored with each row
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
: "${TEST_DATABASE_URL:?set TEST_DATABASE_URL}"
HH_PORT="${HH_PORT:-8546}"
API_PORT="${API_PORT:-3099}"
DURATION="${DURATION:-10}"
BLOCK_TIMES="${BLOCK_TIMES:-0 2000 12000}"
LABEL="${LABEL:-$(lscpu 2>/dev/null | sed -n 's/^Model name: *//p' | head -1)}"
RPC="http://127.0.0.1:${HH_PORT}"
API="http://127.0.0.1:${API_PORT}"
OUT="$ROOT/backend/bench/results"
PIDS=()
ABI_BACKUP="$(mktemp -d)"
cp "$ROOT"/packages/abis/*.json "$ABI_BACKUP/"
stop() {
  kill "${PIDS[@]}" 2>/dev/null || true
  wait "${PIDS[@]}" 2>/dev/null || true
  PIDS=()
}
cleanup() {
  stop
  cp "$ABI_BACKUP"/*.json "$ROOT/packages/abis/" 2>/dev/null || true
  rm -rf "$ABI_BACKUP"
}
trap cleanup EXIT

wait_for() {
  for _ in $(seq 1 90); do
    if eval "$2" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  echo "timed out waiting for $1" >&2
  return 1
}

cd "$ROOT/backend"
go build -o bin/api ./cmd/api
go build -o bin/loadgen ./cmd/loadgen
RELAYER_KEYS_JSON="$(grep -o '^RELAYER_KEYS_JSON=.*' .env.example | cut -d= -f2-)"
SEP='?'
[[ "$TEST_DATABASE_URL" == *\?* ]] && SEP='&'
rm -f "$OUT/loadgen.csv"

start_stack() {
  local block_ms="$1" schema="eval_latency_$$_$1"
  (cd "$ROOT/contracts" && HARDHAT_BLOCK_TIME_MS="$block_ms" exec ./node_modules/.bin/hardhat node --port "$HH_PORT" > "/tmp/sat-eval-hh-$block_ms.log" 2>&1) &
  PIDS+=($!)
  wait_for hardhat "curl -fsS -X POST -H 'content-type: application/json' --data '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"eth_chainId\",\"params\":[]}' $RPC"
  local deploy
  deploy="$(cd "$ROOT/contracts" && LOCALHOST_RPC_URL="$RPC" npx hardhat run scripts/deploy.ts --network localhost)"
  local reg reg2
  reg="$(grep -o 'SupplementRegistry=0x[0-9a-fA-F]*' <<<"$deploy" | cut -d= -f2)"
  reg2="$(grep -o 'SupplementRegistryV2=0x[0-9a-fA-F]*' <<<"$deploy" | cut -d= -f2)"
  DATABASE_URL="${TEST_DATABASE_URL}${SEP}schema=${schema}" PORT="$API_PORT" RPC_URL="$RPC" \
    CHAIN_ID=31337 REGISTRY_ADDRESS="$reg" REGISTRY_V2_ADDRESS="$reg2" \
    ALLOW_IPFS_STUB=true IPFS_API_URL=http://127.0.0.1:1 APP_ENV=test SCAN_HASH_SALT=eval-salt LOG_LEVEL=warn \
    VERIFY_RATE_LIMIT=100000000 CONSUME_RATE_LIMIT=100000000 RELAYER_KEYS_JSON="$RELAYER_KEYS_JSON" \
    ./bin/api > "/tmp/sat-eval-api-$block_ms.log" 2>&1 &
  PIDS+=($!)
  wait_for api "curl -fsS $API/v2/health"
}

echo "==> read paths (Hardhat mining per transaction)"
start_stack 0
for endpoint in verify proof batch; do
  ./bin/loadgen -url "$API" -setup -units 1000 -endpoint "$endpoint" -levels 1,8,32,64 \
    -duration "${DURATION}s" -label "$LABEL" -out "$OUT"
done
./bin/loadgen -url "$API" -setup -units 1000 -endpoint verify -fresh -levels 1,8,32,64 \
  -duration "${DURATION}s" -label "$LABEL" -out "$OUT"

for block_ms in $BLOCK_TIMES; do
  echo "==> end-to-end consume, block time ${block_ms} ms"
  # A fresh chain per block time: automine stamps each block at least 1 s
  # after the previous one, so a long run drifts ahead of the wall clock.
  stop
  start_stack "$block_ms"
  case "$block_ms" in
    0) units=1500 duration=8 ;;
    2000) units=250 duration=40 ;;
    *) units=120 duration=120 ;;
  esac
  ./bin/loadgen -url "$API" -setup -units "$units" -endpoint consume -levels 1,8 \
    -duration "${duration}s" -label "${LABEL}; block=${block_ms}ms" -out "$OUT"
done
echo "results: $OUT/loadgen.csv"
