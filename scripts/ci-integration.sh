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

echo "==> backend unit tests"
cd "$ROOT/backend"
npm ci
npx prisma generate
npm test

echo "==> backend build"
npm run build

echo "Integration smoke ready. REGISTRY_ADDRESS=${REG}"
echo "Start Postgres and backend locally to run scripts/load-verify.sh"
