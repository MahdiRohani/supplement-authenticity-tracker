#!/usr/bin/env bash
# Compiles every TikZ diagram here to docs/figures as a vector PDF and as
# 600 dpi PNG and LZW TIFF (the conference template asks for >= 600 dpi).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$(cd "$HERE/../../.." && pwd)/docs/figures"
BUILD="$(mktemp -d)"
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$OUT"
for tex in "$HERE"/*.tex; do
  name="fig-$(basename "$tex" .tex)"
  pdflatex -interaction=nonstopmode -halt-on-error -output-directory "$BUILD" "$tex" > "$BUILD/$name.log" 2>&1 \
    || { tail -30 "$BUILD/$name.log"; exit 1; }
  cp "$BUILD/$(basename "$tex" .tex).pdf" "$OUT/$name.pdf"
  pdftoppm -r 600 -png -singlefile "$OUT/$name.pdf" "$OUT/$name"
  pdftoppm -r 600 -tiff -tiffcompression lzw -singlefile "$OUT/$name.pdf" "$OUT/$name"
  mv "$OUT/$name.tif" "$OUT/$name.tiff"
  echo "  $name"
done
