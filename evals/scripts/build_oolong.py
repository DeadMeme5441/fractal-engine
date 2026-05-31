#!/usr/bin/env python3
"""Build a stratified SMART SUBSET of the REAL OOLONG-synth `test` split for the
fractal-engine eval harness, plus a context-free full-set meta file.

Source: HuggingFace dataset `oolongbench/oolong-synth`, split `test` (public).

Outputs (relative to repo root):
  evals/data/oolong-smart.jsonl        the ~15-example stratified subset we run
  evals/data/oolong/contexts/<id>.txt  the UNLABELED surface for each subset example
  evals/data/oolong-full-meta.jsonl    one line per full-set example (NO context text):
                                        {id, context_len, answer_type, task_group}

CONTEXT: we use `context_window_text` (the UNLABELED surface). We NEVER use
`context_window_text_with_labels` (that leaks the per-item gold labels).

ANSWER-TYPE MAPPING (what the harness scorer can score exactly):
  ANSWER_TYPE.NUMERIC     -> "count"  (gold = the integer)
  ANSWER_TYPE.USER        -> "count"  (gold = the integer user id; exact numeric match)
  ANSWER_TYPE.LABEL       -> "label"  (gold = the category word)
  ANSWER_TYPE.COMPARISON  -> "label"  (gold = a 3-way categorical phrase)
  ANSWER_TYPE.MONTH_YEAR  -> "label"  (gold = e.g. "October 2022")
  ANSWER_TYPE.DATE        -> EXCLUDED (gold stored as a Python `datetime.date(...)`
                                       repr; would not normalize-equal a model's
                                       MM/DD/YYYY answer -> not cleanly scorable).

SMART SUBSET: stratify by context_len bucket (short <32k / medium 32k-128k /
long >128k tokens) CROSSED WITH the mapped answer_type (count vs label), and
spread across task_group. Deterministic: sort by id within each stratum and take
evenly. Big contexts go to disk and are referenced via "context_path".
"""
import ast
import json
import os
from collections import defaultdict, Counter

from datasets import load_dataset

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(REPO, "evals", "data")
CTX_DIR = os.path.join(DATA, "oolong", "contexts")
SMART = os.path.join(DATA, "oolong-smart.jsonl")
FULL_META = os.path.join(DATA, "oolong-full-meta.jsonl")

# answer_type -> harness answer_type, or None to exclude
ATYPE_MAP = {
    "ANSWER_TYPE.NUMERIC": "count",
    "ANSWER_TYPE.USER": "count",
    "ANSWER_TYPE.LABEL": "label",
    "ANSWER_TYPE.COMPARISON": "label",
    "ANSWER_TYPE.MONTH_YEAR": "label",
    "ANSWER_TYPE.DATE": None,  # excluded: datetime.date(...) repr, not clean
}

TARGET_N = 15


def parse_gold(answer, harness_type):
    """OOLONG stores answer as a stringified Python list, e.g. "[7]",
    "['incorrect']", or a TIE like "['positive', 'negative']". Parse it properly:
    a single-element list unwraps to a clean scalar; a multi-element list (a tie)
    is preserved as a list so the scorer can accept ANY of the listed answers.

    The previous version sliced "[...]" and one quote layer by hand, which mangled
    ties ("['positive', 'negative']" -> "positive', 'negative"). literal_eval fixes
    that for every shape."""
    try:
        val = ast.literal_eval(answer)
    except (ValueError, SyntaxError):
        val = answer
    items = list(val) if isinstance(val, (list, tuple)) else [val]
    if harness_type == "count":
        nums = [int(x) for x in items]
        return nums[0] if len(nums) == 1 else nums
    strs = [str(x).strip() for x in items]
    return strs[0] if len(strs) == 1 else strs


def len_bucket(n):
    if n < 32_768:
        return "short"
    if n <= 131_072:
        return "medium"
    return "long"


def main():
    os.makedirs(CTX_DIR, exist_ok=True)
    ds = load_dataset("oolongbench/oolong-synth", split="test")
    full_n = len(ds)

    rows = []
    excluded = Counter()
    for r in ds:
        raw_at = r["answer_type"]
        mapped = ATYPE_MAP.get(raw_at)
        rec = {
            "id": str(r["id"]),
            "context_len": int(r["context_len"]),
            "raw_answer_type": raw_at,
            "answer_type": mapped,
            "task_group": r["task_group"],
            "question": r["question"],
            "answer": r["answer"],
            "context_window_text": r["context_window_text"],
        }
        rows.append(rec)
        if mapped is None:
            excluded[raw_at] += 1

    # full-set meta: every example, NO context text
    with open(FULL_META, "w") as f:
        for rec in rows:
            f.write(
                json.dumps(
                    {
                        "id": rec["id"],
                        "context_len": rec["context_len"],
                        "answer_type": rec["answer_type"],  # mapped (null if excluded)
                        "raw_answer_type": rec["raw_answer_type"],
                        "task_group": rec["task_group"],
                    }
                )
                + "\n"
            )

    # scorable population only
    scorable = [r for r in rows if r["answer_type"] is not None]

    # stratify: (len_bucket, answer_type) -> rows, sorted by id (stable, deterministic)
    strata = defaultdict(list)
    for r in scorable:
        strata[(len_bucket(r["context_len"]), r["answer_type"])].append(r)
    for k in strata:
        strata[k].sort(key=lambda r: r["id"])

    keys = sorted(strata.keys())
    # round-robin allocation across strata until TARGET_N, spreading task_group
    # within a stratum by walking it evenly.
    picks = []
    used_groups = defaultdict(Counter)  # stratum -> task_group counts (to spread)
    # build per-stratum evenly-spaced candidate order that diversifies task_group
    stratum_order = {}
    for k, lst in strata.items():
        by_group = defaultdict(list)
        for r in lst:
            by_group[r["task_group"]].append(r)
        # interleave groups for diversity, each group already id-sorted
        order = []
        groups = sorted(by_group.keys())
        gi = 0
        idxs = {g: 0 for g in groups}
        remaining = sum(len(v) for v in by_group.values())
        while remaining > 0:
            g = groups[gi % len(groups)]
            if idxs[g] < len(by_group[g]):
                order.append(by_group[g][idxs[g]])
                idxs[g] += 1
                remaining -= 1
            gi += 1
        stratum_order[k] = order

    cursor = {k: 0 for k in keys}
    while len(picks) < TARGET_N:
        progressed = False
        for k in keys:
            if len(picks) >= TARGET_N:
                break
            order = stratum_order[k]
            if cursor[k] < len(order):
                picks.append(order[cursor[k]])
                cursor[k] += 1
                progressed = True
        if not progressed:
            break

    picks.sort(key=lambda r: (len_bucket(r["context_len"]), r["answer_type"], r["id"]))

    # write contexts + smart jsonl
    with open(SMART, "w") as f:
        for r in picks:
            cid = r["id"]
            ctx_path = os.path.join(CTX_DIR, f"{cid}.txt")
            with open(ctx_path, "w") as cf:
                cf.write(r["context_window_text"])
            gold = parse_gold(r["answer"], r["answer_type"])
            rel = os.path.relpath(ctx_path, DATA)
            line = {
                "id": cid,
                "benchmark": "oolong",
                "question": r["question"],
                "context_path": rel,
                "answer_type": r["answer_type"],
                "gold": gold,
                "meta": {
                    "source": "oolongbench/oolong-synth#test",
                    "raw_answer_type": r["raw_answer_type"],
                    "task_group": r["task_group"],
                    "context_len": r["context_len"],
                    "len_bucket": len_bucket(r["context_len"]),
                },
            }
            f.write(json.dumps(line) + "\n")

    # report
    print(f"full_n (test split) = {full_n}")
    print(f"scorable population = {len(scorable)}")
    print("excluded:", dict(excluded))
    print(f"smart subset n = {len(picks)}")
    strat_counts = Counter(
        (len_bucket(r["context_len"]), r["answer_type"]) for r in picks
    )
    print("subset strata (len_bucket, answer_type):")
    for k in sorted(strat_counts):
        print(f"  {k}: {strat_counts[k]}")
    print("subset task_group spread:", dict(Counter(r["task_group"] for r in picks)))
    print("subset answer_type:", dict(Counter(r["answer_type"] for r in picks)))
    print("context_len buckets present:", sorted(set(r["context_len"] for r in picks)))
    print(f"wrote {SMART}")
    print(f"wrote {FULL_META}")
    print(f"wrote {len(picks)} contexts under {CTX_DIR}")


if __name__ == "__main__":
    main()
