#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v slither >/dev/null 2>&1; then
  echo "Slither not installed. Install with: pipx install slither-analyzer"
  exit 1
fi
slither . --config-file slither.config.json --fail-high
