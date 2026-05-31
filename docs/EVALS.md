# Evaluations

This page records the first public long-context eval run for fractal-engine v17.
The harness lives under [`evals/`](../evals/README.md) as an external consumer of
the engine. It does not add model-facing functions or ship in the uberjar.

## Setup

Engine:

- commit: `78444eedd7261ef344998911f66a1918bfbe6e6e`
- prompt: `repl`, prompt-version `17`
- runtime change under test: leaf provider calls capped at 50 concurrent calls per
  run tree

The result manifests record `engine/git-dirty? true` because the eval harness and
result files were still untracked while the benchmarks ran. The engine commit under
test is the `engine/git-sha` above.

Models:

| role | provider | model |
|---|---|---|
| root | `vertex-gemini` | `gemini-3.5-flash` |
| child | `vertex-gemini` | `gemini-3.5-flash` |
| leaf | `vertex-gemini` | `gemini-3.1-flash-lite-preview` |

Limits:

- `--budget-usd 50`
- `--max-turns 1000000`
- `--call-timeout-ms 180000`
- engine-only mode, one benchmark at a time

Validation before spend:

- `clojure -M:evals-test`: green
- `clojure -M:test`: green
- live provider auth checked before the canary

The aggregate result files are tracked under `evals/results/*-v17/results.*`.
Raw `runs/` session journals and logs are intentionally not tracked: they are
large, noisy, and reproducible from the recorded commands in each result file.

## Results

| benchmark | n | headline | strict / exact | cost | cost / q | tokens | mean wall |
|---|--:|---:|---:|--:|--:|--:|--:|
| OOLONG-synth smart subset | 15 | exact `0.867` | 13 / 15 | `$3.3989` | `$0.2266` | 6,721,220 | 95,714 ms |
| FanOutQA smart subset | 15 | loose `0.695` | 8 / 15 | `$4.5360` | `$0.3024` | 7,129,764 | 91,578 ms |

For FanOutQA, the benchmark headline is loose accuracy. Strict accuracy means all
gold strings are present and is deliberately harsher.

Human audit of FanOutQA final answers:

| measure | value |
|---|---:|
| official strict rows | 8 / 15 = `0.533` |
| official loose accuracy | `0.695` |
| semantic row correctness | 11 / 15 = `0.733` |

The semantic audit is not a replacement benchmark metric; it explains where the
string scorer or stale gold under-counted the engine's final answer.

## OOLONG

Command:

```bash
clojure -M:evals run --benchmark oolong --data evals/data/oolong-smart.jsonl --mode engine \
  --provider vertex-gemini --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \
  --budget-usd 50 --max-turns 1000000 --call-timeout-ms 180000 \
  --runs-dir evals/results/oolong-v17/runs --out evals/results/oolong-v17
```

Result:

- exact accuracy: `13 / 15 = 0.867`
- numeric accuracy mean: `0.998`
- spend: `$3.3989`
- errors: `0`

The two misses were genuine:

| id | final | gold | note |
|---|---|---|---|
| `218020027` | `377` | `382` | 262K-token sentiment count; off by 5 |
| `211020009` | same frequency | more common | small date comparison; counted equal positive before/after |

The important success signal is not just the headline number. The six long OOLONG
examples include 262K-token contexts; the engine decomposed them into Clojure
parsing, `map-lm` chunks, and deterministic reductions instead of relying on one
flat read. The result was high exact accuracy with near-perfect count accuracy on
the numeric rows.

## FanOutQA

Command:

```bash
clojure -M:evals run --benchmark fanoutqa --data evals/data/fanoutqa-smart.jsonl --mode engine \
  --provider vertex-gemini --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \
  --budget-usd 50 --max-turns 1000000 --call-timeout-ms 180000 \
  --runs-dir evals/results/fanoutqa-v17/runs --out evals/results/fanoutqa-v17
```

Result:

- strict accuracy: `8 / 15 = 0.533`
- loose accuracy: `0.695`
- semantic row correctness after audit: `11 / 15 = 0.733`
- spend: `$4.5360`
- errors: `0`

Rows the official scorer marked wrong but the final answer was semantically right:

| id | reason |
|---|---|
| `71552a38345f892e` | all codons were present; the scorer missed labels and comma/`and` formatting |
| `ff866ee3e2bf4820` | all Ivy League acre values were present; the scorer missed comma-normalized numbers and extra detail |
| `146e74771fcf6a30` | founder ages matched the provided evidence; the gold was stale |

Actually wrong rows:

| id | reason |
|---|---|
| `29242cc91b49e88e` | returned only Samuel L. Jackson; incomplete for the cast-wide Academy Award question |
| `ae1c3cec94b75e55` | age answer used a stale/as-of date and several ages were wrong |
| `00065f204bddb94d` | missed J. K. Rowling's `1965` birth year |
| `585ead607ef66fb1` | included the United Kingdom via the wrong EGOT span interpretation |

FanOutQA is useful as a fan-out and join stress test, but this run shows why it
should not be treated as a clean headline benchmark without auditing. Some golds
are time-sensitive, and loose substring scoring can under-credit correct structured
answers.

## Runtime Notes

The v17 leaf concurrency cap did its main job on this run: there were no terminal
provider overloads, rate-limit failures, or fan-out limit failures. Across the two
benchmarks:

- terminal errors: `0`
- terminal `leaf-batch-failed`: `0`
- recovered intermediate `leaf-batch-failed`: 2 unique OOLONG batches, both caused
  by parse failures in a single leaf output and recovered by subsequent model work

That last point is still a real runtime weakness. `map-lm` is currently
all-or-nothing at the batch boundary; one malformed leaf result can throw the whole
batch, even though the model can often recover in the next step. The next hardening
pass should preserve retryable provider/error metadata and make batch retry/failure
semantics less brittle.

## What This Establishes

This run supports the engine thesis on long-context aggregation:

- The model used ordinary Clojure for parsing, partitioning, counting, and reducing.
- Leaf calls handled bounded probabilistic judgments.
- The host preserved order, costs, event journals, and reproducibility manifests.
- The engine completed both long-context suites under modest spend with no terminal
  runtime failures.

It does not yet establish a same-model flat baseline comparison. The harness can run
that with `--mode both` or `--mode all`; this public v17 report is engine-only.
