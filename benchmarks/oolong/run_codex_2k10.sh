#!/usr/bin/env bash
set -euo pipefail

ROOT_MODEL="${ROOT_MODEL:-gpt-5.5}"
CHILD_MODEL="${CHILD_MODEL:-gpt-5.4-mini}"
LEAF_MODEL="${LEAF_MODEL:-gpt-5.4-mini}"

for i in $(seq 50 59); do
  name="spam-2k-row-${i}"
  echo "=== ${name} ==="
  clojure -M -m fractal-engine run \
    --provider codex-backend \
    --model "${ROOT_MODEL}" \
    --leaf-provider codex-backend \
    --leaf-model "${LEAF_MODEL}" \
    --child-provider codex-backend \
    --child-model "${CHILD_MODEL}" \
    --session "oolong-codex-${name}" \
    --question-file "benchmarks/oolong/samples/${name}.prompt.txt"
done
