#!/usr/bin/env bash
set -euo pipefail

MODEL="${PI_MODEL:-google-vertex/gemini-3.1-pro-preview}"
OUT_DIR="${OUT_DIR:-benchmarks/oolong/pi-runs}"
mkdir -p "${OUT_DIR}"

for i in $(seq 50 59); do
  name="spam-2k-row-${i}"
  echo "=== ${name} ==="
  pi \
    --model "${MODEL}" \
    --no-tools \
    --no-context-files \
    --no-session \
    -p \
    "@benchmarks/oolong/samples/${name}.prompt.txt" \
    > "${OUT_DIR}/${name}.out"
done
