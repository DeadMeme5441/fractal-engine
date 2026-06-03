# Evaluations

This page records the public long-context eval evidence for fractal-engine. The harness
lives under [`evals/`](../evals/README.md) as an external consumer of the engine. It does
not add model-facing functions and does not ship in the uberjar.

Two result sets matter:

- **Tracked v17 baseline.** The first public run whose aggregate files are committed
  under `evals/results/*-v17/results.*`.
- **Release-branch v22 validation.** A fresh live validation run for the canonical
  session/storage/prompt branch. Its raw stores were local validation artifacts, not
  committed benchmark output.

## Model Split

Both runs used Vertex Gemini:

| role | provider | model |
|---|---|---|
| root | `vertex-gemini` | `gemini-3.5-flash` |
| child | `vertex-gemini` | `gemini-3.5-flash` |
| leaf | `vertex-gemini` | `gemini-3.1-flash-lite-preview` |

The root and child roles use the same model because both are full recursive sessions.
Leaves are cheaper non-recursive provider calls over bounded inputs.

## Results

| run | benchmark | n | headline | strict / exact | cost | tokens | mean wall |
|---|---|--:|---:|---:|--:|--:|--:|
| tracked v17 | OOLONG-synth smart subset | 15 | exact `0.867` | 13 / 15 | `$3.3989` | 6,721,220 | 95,714 ms |
| tracked v17 | FanOutQA smart subset | 15 | loose `0.695` | strict 8 / 15 | `$4.5360` | 7,129,764 | 91,578 ms |
| release v22 validation | OOLONG-synth smart subset | 15 | exact `0.933` | 14 / 15 | `$3.3086` | 6,243,085 | 116,802 ms |
| release v22 validation | FanOutQA smart subset | 15 | loose `0.704` | strict 7 / 15 | `$2.8873` | 4,805,633 | 54,115 ms |

For OOLONG, the headline is exact accuracy. For FanOutQA, the headline is loose
accuracy: fraction of gold reference strings present in the answer after SQuAD-style
normalization. FanOutQA strict accuracy means every gold string is present exactly after
normalization; it is useful as a diagnostic, but it under-credits some semantically
correct structured answers.

## Tracked v17 Baseline

Engine:

- commit: `78444eedd7261ef344998911f66a1918bfbe6e6e`
- prompt: `repl`, prompt-version `17`
- runtime change under test: leaf provider calls capped at 50 concurrent calls per run
  tree

The result manifests record `engine/git-dirty? true` because the eval harness and result
files were still untracked while the benchmarks ran. The engine commit under test is the
`engine/git-sha` above.

Commands:

```bash
clojure -M:evals run --benchmark oolong --data evals/data/oolong-smart.jsonl --mode engine \
  --provider vertex-gemini --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \
  --budget-usd 50 --max-turns 1000000 --call-timeout-ms 180000 \
  --runs-dir evals/results/oolong-v17/runs --out evals/results/oolong-v17

clojure -M:evals run --benchmark fanoutqa --data evals/data/fanoutqa-smart.jsonl --mode engine \
  --provider vertex-gemini --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \
  --budget-usd 50 --max-turns 1000000 --call-timeout-ms 180000 \
  --runs-dir evals/results/fanoutqa-v17/runs --out evals/results/fanoutqa-v17
```

OOLONG v17:

- exact accuracy: `13 / 15 = 0.867`
- numeric accuracy mean: `0.998`
- spend: `$3.3989`
- terminal errors: `0`

The two exact-unmatched rows were real answer errors:

| id | final | gold | note |
|---|---|---|---|
| `218020027` | `377` | `382` | 262K-token sentiment count; off by 5 |
| `211020009` | same frequency | more common | small date comparison; counted equal positive before/after |

FanOutQA v17:

- strict accuracy: `8 / 15 = 0.533`
- loose accuracy: `0.695`
- semantic row correctness after audit: `11 / 15 = 0.733`
- spend: `$4.5360`
- terminal errors: `0`

Rows strict scoring under-credited but the final answer was semantically right:

| id | reason |
|---|---|
| `71552a38345f892e` | all codons were present; strict string matching under-credited labels and comma/`and` formatting |
| `ff866ee3e2bf4820` | all Ivy League acre values were present; strict string matching under-credited comma-normalized numbers and extra detail |
| `146e74771fcf6a30` | founder ages matched the provided evidence; the gold was stale |

Rows that were semantically wrong:

| id | reason |
|---|---|
| `29242cc91b49e88e` | returned only Samuel L. Jackson; incomplete for the cast-wide Academy Award question |
| `ae1c3cec94b75e55` | age answer used a stale/as-of date and several ages were wrong |
| `00065f204bddb94d` | omitted J. K. Rowling's `1965` birth year |
| `585ead607ef66fb1` | included the United Kingdom via the wrong EGOT span interpretation |

## Release-Branch v22 Validation

Engine:

- prompt: `repl`, prompt-version `22`
- storage: canonical SQLite facts + BlobStore payloads; derived Datahike query index
- eval runner: cost derived from canonical session call facts, not from old file-based
  usage summaries
- fanout: partial-failure tolerant `map-lm` / `map-rlm`
- parallelism: `5`
- call timeout: `600000` ms
- max turns: `1000000`

Commands:

```bash
clojure -M:evals run --benchmark oolong --data evals/data/oolong-smart.jsonl --mode engine \
  --provider vertex-gemini --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \
  --parallelism 5 --budget-usd 50 --max-turns 1000000 --call-timeout-ms 600000 \
  --runs-dir evals/results/oolong-v22/runs --out evals/results/oolong-v22

clojure -M:evals run --benchmark fanoutqa --data evals/data/fanoutqa-smart.jsonl --mode engine \
  --provider vertex-gemini --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \
  --parallelism 5 --budget-usd 50 --max-turns 1000000 --call-timeout-ms 600000 \
  --runs-dir evals/results/fanoutqa-v22/runs --out evals/results/fanoutqa-v22
```

The validation run used temporary local stores and did not commit raw session data.
Store consistency checks passed with `:status :ok` and `:issue-count 0` for both
benchmarks.

OOLONG v22:

- exact accuracy: `14 / 15 = 0.933`
- numeric accuracy mean: `0.998`
- spend: `$3.3086`
- tokens: `6,243,085`
- mean wall: `116,802` ms

FanOutQA v22:

- strict accuracy: `7 / 15 = 0.467`
- loose accuracy: `0.704`
- spend: `$2.8873`
- tokens: `4,805,633`
- mean wall: `54,115` ms

FanOutQA strict-unmatched rows need semantic audit before being called failures. The
observed strict-unmatched set mixed:

- real semantic issues or incomplete answers,
- scorer under-credit for grouping/formatting/aliases,
- time-sensitive or stale-gold disagreements,
- multiplicity issues where the answer was correct as a set but not in the expected
  repeated-string form.

## Runtime Notes

The v17 leaf concurrency cap did its main job: no terminal provider overloads,
rate-limit failures, or fan-out limit failures. Since then, `map-lm` and `map-rlm` have
also become partial-failure tolerant: failed slots return
`{:fractal/failed true :index i :error ...}` sentinels in an input-aligned vector while
successful slots remain usable.

The v22 run validates the newer canonical store path: per-example sessions are stored as
SQLite facts and BlobStore payloads, Datahike is a rebuildable query index, and the eval
runner computes cost/tokens from canonical call facts.

## What This Establishes

These runs support the engine thesis on long-context aggregation:

- The model used ordinary Clojure for parsing, partitioning, counting, and reducing.
- Leaf calls handled bounded probabilistic judgments.
- Recursive sessions handled decomposed sub-problems.
- The host preserved order, costs, canonical state, and reproducibility manifests.
- The engine completed both long-context suites under modest spend with no terminal
  runtime failures.

It does not yet establish a same-model flat baseline comparison. The harness can run
that with `--mode both` or `--mode all`; the public reports above are engine-only.
