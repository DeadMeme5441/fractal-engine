#!/usr/bin/env bash
set -euo pipefail

ROOT_MODEL="${ROOT_MODEL:-gemini-3.1-pro-preview}"
CHILD_MODEL="${CHILD_MODEL:-gemini-3.5-flash}"
LEAF_MODEL="${LEAF_MODEL:-gemini-3.1-flash-lite-preview}"

for i in $(seq 50 59); do
  name="spam-2k-row-${i}"
  echo "=== ${name} ==="
  clojure -M -m fractal-engine run \
    --provider vertex-gemini \
    --model "${ROOT_MODEL}" \
    --leaf-provider vertex-gemini \
    --leaf-model "${LEAF_MODEL}" \
    --child-provider vertex-gemini \
    --child-model "${CHILD_MODEL}" \
    --session "oolong-rlm-${name}" \
    --question-file "benchmarks/oolong/samples/${name}.prompt.txt"
done
