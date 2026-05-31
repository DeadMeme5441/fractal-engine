#!/usr/bin/env python3
"""Build a stratified SMART SUBSET of the REAL FanOutQA `dev` split in the
EVIDENCE-PROVIDED setting for the fractal-engine eval harness.

Source: the `fanoutqa` PyPI package, `load_dev()` (dev split, public).

Evidence-provided setting: each example carries its relevant Wikipedia pages
(the `necessary_evidence`, fetched as markdown via `fanoutqa.wiki_content`) inline
as the context, so the task is decompose-gather-join, not web retrieval.

gold = the reference answer flattened to a list of answer-value strings (the
engine task asks the model to FINAL `{:answer [<strings>]}`; the harness scorer
coerces both sides to a normalized string set).

Outputs (relative to repo root):
  evals/data/fanoutqa-smart.jsonl  the ~15-example stratified subset we run

SMART SUBSET: stratify by fan-out width = number of sub-questions in the
decomposition:
  small  (width <= 4)
  medium (width 5-7)
  large  (width 8-12)   # capped at 12 so the inlined evidence stays bounded;
                        # the >12 tail is a handful of outliers (max 46).
Deterministic: sort by id within each stratum and take evenly. The full dev split
size is reported as full_n.
"""
import json
import os
from collections import Counter, defaultdict

import fanoutqa

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DATA = os.path.join(REPO, "evals", "data")
SMART = os.path.join(DATA, "fanoutqa-smart.jsonl")

TARGET_N = 15
LARGE_CAP = 12  # exclude the long fan-out tail (>12) so inlined evidence stays bounded


def width_bucket(w):
    if w <= 4:
        return "small"
    if w <= 7:
        return "medium"
    return "large"


def flatten_gold(ans):
    """FanOutQA gold is a dict (entity -> value), a list, or a scalar. Flatten to
    a list of answer-value strings for set-based exact match."""
    out = []
    if isinstance(ans, dict):
        for v in ans.values():
            out.extend(flatten_gold(v))
    elif isinstance(ans, (list, tuple)):
        for v in ans:
            out.extend(flatten_gold(v))
    elif isinstance(ans, bool):
        out.append("yes" if ans else "no")
    else:
        out.append(str(ans))
    return [s for s in (x.strip() for x in out) if s]


def build_context(q):
    """Concatenate the evidence pages (markdown) for the evidence-provided setting."""
    parts = []
    seen = set()
    for ev in q.necessary_evidence:
        key = (ev.pageid, ev.title)
        if key in seen:
            continue
        seen.add(key)
        try:
            md = fanoutqa.wiki_content(ev)
        except Exception as e:  # pragma: no cover - network hiccup
            md = f"[evidence fetch failed for {ev.title}: {e}]"
        parts.append(f"# {ev.title}\n(source: {ev.url})\n\n{md}")
    return "\n\n---\n\n".join(parts)


def main():
    os.makedirs(DATA, exist_ok=True)
    dev = fanoutqa.load_dev()
    full_n = len(dev)

    # stratify by fan-out width (number of sub-questions)
    strata = defaultdict(list)
    for q in dev:
        w = len(q.decomposition)
        if w > LARGE_CAP:
            continue  # exclude the long-tail outliers from selection
        strata[width_bucket(w)].append(q)
    for b in strata:
        strata[b].sort(key=lambda q: q.id)

    # evenly spaced pick within each bucket, round-robin across buckets to TARGET_N
    order = ["small", "medium", "large"]
    # evenly-spaced indices per bucket
    spaced = {}
    for b in order:
        lst = strata.get(b, [])
        spaced[b] = lst  # already id-sorted; we'll walk with an even stride below

    picks = []
    cursor = {b: 0 for b in order}
    stride = {}
    # aim for ~5 per bucket; even stride across the sorted bucket for spread
    per_bucket_target = {b: 0 for b in order}
    # distribute TARGET_N across the 3 buckets as evenly as availability allows
    remaining = TARGET_N
    avail = {b: len(spaced[b]) for b in order}
    # first pass: equal share
    base = TARGET_N // len(order)
    for b in order:
        per_bucket_target[b] = min(base, avail[b])
        remaining -= per_bucket_target[b]
    # distribute leftover to buckets with spare capacity
    i = 0
    while remaining > 0 and any(per_bucket_target[b] < avail[b] for b in order):
        b = order[i % len(order)]
        if per_bucket_target[b] < avail[b]:
            per_bucket_target[b] += 1
            remaining -= 1
        i += 1
    for b in order:
        lst = spaced[b]
        n = per_bucket_target[b]
        if n <= 0 or not lst:
            continue
        if n == 1:
            idxs = [0]
        else:
            step = (len(lst) - 1) / (n - 1)
            idxs = sorted(set(round(k * step) for k in range(n)))
            # if rounding collapsed picks, top up sequentially
            j = 0
            while len(idxs) < n and j < len(lst):
                if j not in idxs:
                    idxs.append(j)
                j += 1
            idxs = sorted(idxs)[:n]
        for ix in idxs:
            picks.append(spaced[b][ix])

    picks.sort(key=lambda q: (width_bucket(len(q.decomposition)), q.id))

    with open(SMART, "w") as f:
        for q in picks:
            w = len(q.decomposition)
            ctx = build_context(q)
            gold = flatten_gold(q.answer)
            line = {
                "id": q.id,
                "benchmark": "fanoutqa",
                "question": q.question,
                "context": ctx,
                "gold": gold,
                "meta": {
                    "source": "fanoutqa#dev (evidence-provided)",
                    "fanout_width": w,
                    "width_bucket": width_bucket(w),
                    "num_evidence_docs": len(q.necessary_evidence),
                    "categories": q.categories,
                },
            }
            f.write(json.dumps(line) + "\n")

    print(f"full_n (dev split) = {full_n}")
    print(f"selectable (width <= {LARGE_CAP}) = {sum(len(v) for v in strata.values())}")
    print(f"smart subset n = {len(picks)}")
    print(
        "subset width buckets:",
        dict(Counter(width_bucket(len(q.decomposition)) for q in picks)),
    )
    print(
        "subset widths:",
        sorted(len(q.decomposition) for q in picks),
    )
    print(f"wrote {SMART}")


if __name__ == "__main__":
    main()
