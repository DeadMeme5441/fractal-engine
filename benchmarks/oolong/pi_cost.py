#!/usr/bin/env python3
"""Summarize Pi JSON-mode cost/usage for completed Oolong rows."""

from __future__ import annotations

import json
from pathlib import Path


def message_cost(path: Path) -> dict:
    latest = None
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        msg = obj.get("message")
        if isinstance(msg, dict) and msg.get("role") == "assistant" and msg.get("usage"):
            latest = msg["usage"]
    if not latest:
        return {"file": path.name, "found": False}
    cost = latest.get("cost") or {}
    return {
        "file": path.name,
        "found": True,
        "input": latest.get("input", 0),
        "output": latest.get("output", 0),
        "cacheRead": latest.get("cacheRead", 0),
        "cacheWrite": latest.get("cacheWrite", 0),
        "totalTokens": latest.get("totalTokens", 0),
        "cost": cost.get("total", 0),
    }


def main() -> None:
    root = Path("benchmarks/oolong/pi-json-runs")
    rows = [message_cost(p) for p in sorted(root.glob("*.jsonl"))]
    totals = {
        "files": len(rows),
        "with_usage": sum(1 for r in rows if r.get("found")),
        "input": sum(r.get("input", 0) or 0 for r in rows),
        "output": sum(r.get("output", 0) or 0 for r in rows),
        "cacheRead": sum(r.get("cacheRead", 0) or 0 for r in rows),
        "cacheWrite": sum(r.get("cacheWrite", 0) or 0 for r in rows),
        "totalTokens": sum(r.get("totalTokens", 0) or 0 for r in rows),
        "cost": sum(r.get("cost", 0) or 0 for r in rows),
    }
    print(json.dumps({"totals": totals, "rows": rows}, indent=2))


if __name__ == "__main__":
    main()
