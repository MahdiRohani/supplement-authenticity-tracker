#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:3000/v1}"
PRODUCT_ID="${PRODUCT_ID:-1}"
CONCURRENCY="${CONCURRENCY:-20}"
THRESHOLD_MS="${THRESHOLD_MS:-3000}"

echo "Parallel verify load: n=${CONCURRENCY} id=${PRODUCT_ID} base=${BASE_URL}"

tmp="$(mktemp)"
start_ms="$(date +%s%3N)"
seq 1 "$CONCURRENCY" | xargs -P "$CONCURRENCY" -I{} bash -c "
  t0=\$(date +%s%3N)
  code=\$(curl -s -o /dev/null -w '%{http_code}' \"${BASE_URL}/verify/${PRODUCT_ID}\" || true)
  t1=\$(date +%s%3N)
  echo \"\$((t1 - t0)) \$code\"
" >"$tmp"
end_ms="$(date +%s%3N)"
wall=$((end_ms - start_ms))

max=0
ok=0
fail=0
while read -r elapsed code; do
  if [[ "$elapsed" -gt "$max" ]]; then
    max="$elapsed"
  fi
  if [[ "$code" == "200" ]]; then
    ok=$((ok + 1))
  else
    fail=$((fail + 1))
  fi
done <"$tmp"
rm -f "$tmp"

echo "wall_ms=${wall} max_request_ms=${max} ok=${ok} fail=${fail} threshold_ms=${THRESHOLD_MS}"
if [[ "$fail" -gt 0 ]]; then
  echo "Load check failed: non-200 responses" >&2
  exit 1
fi
if [[ "$max" -gt "$THRESHOLD_MS" ]]; then
  echo "Load check failed: max ${max}ms > ${THRESHOLD_MS}ms" >&2
  exit 1
fi
echo "Load check passed"
