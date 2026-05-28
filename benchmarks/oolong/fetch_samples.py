#!/usr/bin/env python3
"""Fetch a tiny public Oolong sample set for local RLM benchmarking.

The script intentionally uses only the Python standard library. It writes
prompt files plus a metadata JSON file under benchmarks/oolong/samples/.
"""

from __future__ import annotations

import json
import ssl
import urllib.request
from pathlib import Path


DATASET = "oolongbench/oolong-synth"
CONFIG = "default"
SPLIT = "validation"

# Rows chosen to approximate the professor's small/medium/larger Oolong
# contexts while keeping the live validation affordable and reproducible.
SAMPLES = [
    {"name": "spam-2k-numeric", "offset": 53},
    {"name": "spam-4k-numeric", "offset": 103},
    {"name": "spam-16k-numeric", "offset": 203},
]


def fetch_row(offset: int) -> dict:
    url = (
        "https://datasets-server.huggingface.co/rows"
        f"?dataset={DATASET}&config={CONFIG}&split={SPLIT}"
        f"&offset={offset}&length=1"
    )
    # Some local Python installs lack a usable CA bundle; the data source is
    # public and this benchmark records dataset/id metadata for verification.
    ctx = ssl._create_unverified_context()
    with urllib.request.urlopen(url, context=ctx, timeout=120) as resp:
        data = json.load(resp)
    return data["rows"][0]["row"]


def prompt_for(row: dict) -> str:
    return (
        "You are evaluating one public Oolong benchmark sample.\n"
        "Use the full context below and answer the benchmark question exactly.\n\n"
        "Important operating instructions for the RLM:\n"
        "- Use the Clojure REPL for deterministic parsing/counting when possible.\n"
        "- Bind the full context and intermediate parsed records to vars; do not rely on mental counting.\n"
        "- Use lm/map-lm only if a bounded semantic classification is needed.\n"
        "- Use rlm/map-rlm only if the context naturally splits into independent chunks.\n"
        "- FINAL an EDN map with {:answer value :method string :evidence string :notes [string]}.\n\n"
        "Expected answer format for scoring:\n"
        "Return the answer value in :answer, preserving number/string/vector shape where applicable.\n\n"
        "[BEGIN OOLONG CONTEXT]\n"
        f"{row['context_window_text']}\n"
        "[END OOLONG CONTEXT]\n\n"
        "[BEGIN OOLONG QUESTION]\n"
        f"{row['question']}\n"
        "[END OOLONG QUESTION]\n"
    )


def main() -> None:
    out = Path(__file__).resolve().parent / "samples"
    out.mkdir(parents=True, exist_ok=True)
    metadata = []
    for sample in SAMPLES:
        row = fetch_row(sample["offset"])
        prompt = prompt_for(row)
        prompt_path = out / f"{sample['name']}.prompt.txt"
        prompt_path.write_text(prompt)
        row_path = out / f"{sample['name']}.row.json"
        row_path.write_text(json.dumps(row, indent=2, ensure_ascii=False))
        metadata.append(
            {
                "name": sample["name"],
                "offset": sample["offset"],
                "dataset": DATASET,
                "config": CONFIG,
                "split": SPLIT,
                "id": row["id"],
                "context_len": row["context_len"],
                "dataset_name": row["dataset"],
                "task": row["task"],
                "answer": row["answer"],
                "answer_type": row["answer_type"],
                "prompt_path": str(prompt_path.relative_to(Path.cwd())),
                "row_path": str(row_path.relative_to(Path.cwd())),
                "context_chars": len(row["context_window_text"]),
            }
        )
    (out / "metadata.json").write_text(json.dumps(metadata, indent=2, ensure_ascii=False))
    print(json.dumps(metadata, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
