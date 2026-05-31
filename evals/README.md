# fractal-engine eval harness

A small, self-contained harness that measures the **fractal-engine** on real
long-context benchmarks. It is an **external consumer** of the engine: everything
lives under `evals/` behind the `:evals` deps alias, imports the engine's public
seams (`fractal-engine.process/run-task!`, `provider/complete`, `artifacts`), and
adds **nothing** to the core surface. The uberjar build only sees `src/`, so the
harness can never leak into the shipped engine.

> **Run it from a fresh session?** Read this whole file first, then the
> [Run](#run-engine-only) section has the exact commands. There is no budget/timeout
> governor in the engine — **always leash live runs** (budget cap + call timeout +
> background + monitor).

---

## The two benchmarks

| | OOLONG-synth | FanOutQA (dev, evidence-provided) |
|---|---|---|
| **what it tests** | aggregation over a long, heterogeneous surface — count / classify-then-count / distributional questions where the answer depends on the *whole* context | multi-document fan-out-and-join — a question splits into many per-entity sub-questions whose answers must be gathered and combined |
| **why it fits** | the purest existing match for the engine's thesis; the benchmark RLM led with | cleanest "fan-out + aggregate" task; stresses `map-lm`/`map-rlm` fanout |
| **surface size** | `context_len` 1K–262K tokens (subset spans all buckets) | inlined Wikipedia evidence, ~230K–1.4M **chars** (≈ 60K–350K tokens) per example |
| **headline metric** | **exact-match accuracy** (`accuracy` column) + `numeric-accuracy` for counts (how close a wrong count was) | **loose accuracy** (`loose-acc` column) — fraction of gold reference-strings present in the answer, faithful to FanOutQA |
| **secondary** | — | **strict** = all gold strings present (`accuracy` column; harsh, not the headline) |
| **source** | HF `oolongbench/oolong-synth` (test split, 2,852 ex) | `fanoutqa` PyPI `load_dev()` |

**Read the right column per benchmark.** For OOLONG the headline is `accuracy`
(exact match). For FanOutQA the headline is `loose-acc`; its `accuracy` column is the
*strict* (all-strings-present) metric and will look low even on good answers — that is
expected and matches the benchmark.

### Scoring is deterministic Clojure (no model in the loop)
- **OOLONG** (`oolong.clj`): strips the `Label:` / `Answer:` / `User:` lead-in the
  questions demand ("give your answer in the form 'Label: answer'"), accepts **tie
  golds** (a list — match any), exact-or-contained match. Counts parse the integer out
  of prose. Numeric-accuracy = `max(0, 1 − |pred−gold|/max(1,|gold|))`.
- **FanOutQA** (`fanoutqa.clj`): faithful loose/strict. The model's answer is one
  text blob (lists joined); **loose** = fraction of gold reference-strings found as a
  SQuAD-normalized substring; **strict** = all present. Handles repeated values and
  single-string answers (the naive set-EM did not).

---

## Layout

```
evals/
  src/fractal_eval/
    core.clj        CLI entry (clojure -M:evals run ...)
    runner.clj      drives the engine per example; captures cost/tokens/wall-clock; budget cap
    oolong.clj      OOLONG engine-task + deterministic scorer
    fanoutqa.clj    FanOutQA engine-task + faithful loose/strict scorer
    scoring.clj     shared extract/normalize helpers (squad-normalize, strip-answer-prefix, …)
    dataset.clj     normalized-JSONL loader + dataset fingerprint
    report.clj      aggregate → results.{edn,json,md}
    repro.clj       reproducibility manifest (git sha, prompt-version, models, dataset hash, …)
  scripts/
    build_oolong.py    HF oolong-synth → stratified smart subset + full-set meta
    build_fanoutqa.py  fanoutqa dev → stratified smart subset (evidence inlined)
  resources/fixtures/  tiny hand-written examples for offline tests (NOT the real corpora)
  test/                offline scorer + harness smoke tests (no keys, no spend)
  data/                built smart-subset datasets (see "Build datasets")
  results/             run outputs (per run: results.{edn,json,md} + runs/<sessions>)
```

---

## Prerequisites

- **JDK 21+ and Clojure CLI** (same as the engine).
- **Provider auth (live runs):** the runs below use **vertex-gemini**. Export into the
  **JVM environment** (the engine's `.env` loader does *not* push these to
  `System/getenv`): `GOOGLE_CLOUD_PROJECT`, `GOOGLE_CLOUD_LOCATION`, plus valid ADC
  (`gcloud auth application-default login`). Verify:
  `clojure -M -m fractal-engine ... ` or just `printenv GOOGLE_CLOUD_PROJECT GOOGLE_CLOUD_LOCATION`.
- **Python (only to (re)build datasets):** `datasets` + `huggingface_hub` for OOLONG;
  `fanoutqa` for FanOutQA. On a PEP-668 system, install into a venv (preferred) or with
  `pip install --break-system-packages ...`. **Datasets are already built — you only
  need Python to regenerate them.**

---

## Offline first (no keys, no spend)

Always confirm green before spending:

```bash
clojure -M:evals-test      # harness scorer + smoke tests
clojure -M:test            # engine suite — proves core is intact
```

Scripted parse-check (validates a dataset end-to-end with the offline fake provider):

```bash
clojure -M:evals run --benchmark oolong --data evals/data/oolong-smart.jsonl \
  --mode engine --provider scripted --runs-dir /tmp/check --out /tmp/check-out
```

---

## Build / refresh datasets

Already built under `evals/data/`. To regenerate (deterministic — same examples, run
from repo root):

```bash
python3 evals/scripts/build_oolong.py     # → oolong-smart.jsonl (+ contexts/, full-meta)
python3 evals/scripts/build_fanoutqa.py   # → fanoutqa-smart.jsonl (evidence inlined)
```

Each builds a **stratified smart subset (~15 examples)**: OOLONG by context-length ×
answer-type × task-group; FanOutQA by fan-out width. OOLONG uses the **unlabeled**
context (`context_window_text`) — never the labeled column (it leaks gold). Golds are
parsed with `ast.literal_eval` (OOLONG stores answers as Python lists; ties are kept as
lists). `oolong-full-meta.jsonl` records all 2,852 ids/lengths (no context) for cost
extrapolation.

---

## Run (engine only)

We measure **just the fractal-engine** (`--mode engine`). Model split: strong root +
child, cheap leaf. **No turn cap** (the engine's loop needs an integer, so pass a huge
value; the real leashes are the dollar budget + call timeout). The dollar cap is
enforced **between examples** by the runner reading each run's `usage.edn`.

```bash
# OOLONG — all 15, engine only, leashed
clojure -M:evals run \
  --benchmark oolong --data evals/data/oolong-smart.jsonl --mode engine \
  --provider vertex-gemini       --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini  --leaf-model gemini-3.1-flash-lite-preview \
  --budget-usd 30 --max-turns 1000000 --call-timeout-ms 180000 \
  --runs-dir evals/results/oolong-run/runs --out evals/results/oolong-run

# FanOutQA — same flags, different data/out
clojure -M:evals run \
  --benchmark fanoutqa --data evals/data/fanoutqa-smart.jsonl --mode engine \
  --provider vertex-gemini       --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini  --leaf-model gemini-3.1-flash-lite-preview \
  --budget-usd 30 --max-turns 1000000 --call-timeout-ms 180000 \
  --runs-dir evals/results/fanoutqa-run/runs --out evals/results/fanoutqa-run
```

Flags: `--mode` = `engine|flat|engine-norec|both|all` (we use `engine`); `--limit N`
(first N examples, e.g. a 1-example canary); `--budget-usd` is a hard backstop checked
between examples; leaf concurrency is capped at 50 by the engine (`:max-leaf-concurrency`,
default). Per-example sessions land under `--runs-dir`; aggregate under `--out`.

### Run it leashed (background + monitor)
Live runs can hang and the 262K / huge-evidence examples take minutes each. Launch in
the background, log to a file, and watch per-example cost; kill if a single example
stalls or cost approaches the cap:

```bash
nohup clojure -M:evals run --benchmark oolong ... > /tmp/oolong-run.log 2>&1 &
# then tail the log for per-example lines:  [engine] <id>  ✓/✗  cost=$..  cum=$..  ..ms
```

---

## Read the results

Each run writes `results.{edn,json,md}` under `--out`:
- `results.md` — the headline table (per-mode: n, correct, accuracy, num-acc, **loose-acc**, cost, tokens, mean ms) + the reproduce command.
- `results.json` — full per-example rows (`:correct?`, `:numeric-accuracy`/`:loose-accuracy`, `:cost-usd`, `:tokens-total`, `:wall-ms`, `:run-dir`, and `:error`/`:error-type` if any) + the repro manifest.
- `results.edn` — same, pretty EDN.

**Cost/question** = `total-cost-usd / n` (extrapolate to the full set with
`oolong-full-meta.jsonl`'s length distribution — don't flat-multiply; weight by bucket).

Inspect what the engine actually did on any example via its session under
`--runs-dir` (the `:run-dir` in the result row): `final.edn` has the `{:answer …}`,
`events.ednl` is the source of truth, and per-call `:usage{:input-tokens …}` in
`calls.edn` shows fan-out sizes.

---

## Gotchas (learned the hard way)

- **Answer format ≠ wrong answer.** OOLONG asks for `Label: X` / `Answer: N`; the
  engine returns exactly that. The scorer strips the lead-in — don't "fix" answers that
  look prefixed.
- **`:turn-final` is the terminal state.** Intermediate `leaf-batch-failed` /
  macroexpand errors in `events.ednl` are usually *recovered* — check the terminal
  event, not mid-run events, before calling something an error.
- **Cost is read from `usage.edn`** at `[:cost/total-tree :cost/usd]`. If it ever reads
  `nil`, the budget cap goes blind — confirm a non-zero `cost=` on the first example.
- **Huge contexts cost real money.** OOLONG 262K and FanOutQA 700K–1.4M-char examples
  fan out hundreds of leaf calls (~$0.3–1 each). Budget and monitor accordingly.
- **Clean up `.fractal/`** if you ever run without `--runs-dir`: eval sessions are the
  ones whose `messages.edn` references `fractal-eval-` temp context files. Removing
  those is safe; leave `codebrain/` and unrelated sessions.
- **Public repo.** Never write a cloud project id, ADC path, or any internal identifier
  into a tracked file under `evals/` (results/docs included).

---

## Reproducibility

Every results file embeds a manifest: engine git sha + dirty flag, `prompt-name` /
`prompt-version` / `prompt-hash`, the exact model split, dataset path + count + sha256,
seed, limits (budget/turns/fanout/timeout), and the exact command. Same engine + same
dataset fingerprint ⇒ a re-runnable claim.

## Status

- Engine: `prompt-version 17`, leaf-concurrency capped at 50 (commit `78444ee`).
- Published v17 aggregates live under `evals/results/oolong-v17/results.*` and
  `evals/results/fanoutqa-v17/results.*`. Raw per-example `runs/` journals and logs
  are local-only and ignored.
- OOLONG: **13/15 exact** (`accuracy 0.867`, `num-acc 0.998`), `$3.3989` total spend,
  no terminal errors.
- FanOutQA: official **loose-acc 0.695** (`strict 8/15`), `$4.5360` total spend, no
  terminal errors. A human audit of the final answers counts **11/15 semantically
  correct**: three official misses were scorer/gold false negatives, and four rows
  were genuinely wrong.
- Full public report: [`docs/EVALS.md`](../docs/EVALS.md).
