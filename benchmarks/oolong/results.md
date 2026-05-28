# Oolong Benchmark Replication Notes

Branch: `oolong-benchmark-rlm`

## Benchmark Source

The sample prompts come from Hugging Face dataset-server rows for:

- dataset: `oolongbench/oolong-synth`
- config: `default`
- split: `validation`
- local cache: `benchmarks/oolong/samples/`

The fixture generator is `benchmarks/oolong/fetch_samples.py`. It writes the
raw row JSON plus the prompt used for each run. The prompt preserves the Oolong
context and question and asks for `{:answer ...}` inside the engine's normal
`FINAL` value.

## Prompt / Engine Change

The first exploratory Vertex RLM run on `spam-2k-numeric` exposed an exact-task
failure:

- gold answer: `[12]`
- RLM final value: `{:answer 13, ...}`
- same final value's evidence text said `12`

That was a generic exact-answer failure, not an Oolong-specific issue. Prompt
version 9 added an "Aggregation and exact-answer discipline" section:

- use deterministic Clojure for parsing, counting, grouping, sorting;
- use `lm`/`map-lm` only for bounded semantic per-item labels/facts;
- compute the aggregate answer from an auditable ledger var;
- do not call `FINAL` when `:answer` contradicts ledger/evidence/method/notes.

After these runs, prompt version 10 superseded v9 for future reruns. It keeps
the exact-answer discipline and folds in older `clojure-rlm` live-eval lessons:
split by evidence type and uncertainty surface, use the root as coordinator,
use children only for scoped subproblems that need a loop, use leaves for
bounded semantic reading, and treat Clojure as the exact worker.

This is intentionally not Oolong-specific.

## Completed 2k Slice

Rows: `spam-2k-row-50` through `spam-2k-row-59`, all from the same 27-message
spam context with varied aggregate questions.

| Lane | Model stack | Accuracy | Calls | Root | Leaf | Child | Cost |
|---|---|---:|---:|---:|---:|---:|---:|
| Vertex RLM | root `gemini-3.1-pro-preview`, leaf `gemini-3.1-flash-lite-preview`, child `gemini-3.5-flash` | 10/10 | 190 | 50 | 140 | 0 | $0.7703 |
| Codex RLM | root `gpt-5.5`, leaf/child `gpt-5.4-mini` | 9/10 | 69 | 12 | 57 | 0 | $0.7253 |
| Pi plain | `google-vertex/gemini-3.1-pro-preview`, no tools | 10/10 | n/a | n/a | n/a | n/a | $0.4063 |

The Pi cost comes from a JSON-mode rerun over the same 10 prompt files:
10 JSON files, 53,804 total tokens, and $0.406288 visible cost.

## Size Ladder

Single samples:

- 2k numeric: already represented by row 53 in the 2k slice.
- 4k numeric: `spam-4k-numeric`, gold `[36]`.
- 16k numeric: `spam-16k-numeric`, gold `[140]`.

| Lane | 4k result | 16k result | Cost / status |
|---|---|---|---|
| Codex RLM | correct: 36 | wrong: 151 | $0.5090 total for 4k+16k |
| Pi plain | correct: 36 | wrong: 143 | text-mode cost unavailable |
| Vertex RLM | correct: 36 | stopped: provider/format hang | $0.2533 before 16k stop |

The Vertex 16k run first spent roughly 9m40s in a root call that returned empty
content with Gemini `MALFORMED_FUNCTION_CALL`, then the repair call remained
in-flight for roughly 16 more minutes before the process was stopped. It had
two root calls, zero eval rows, and a running turn. That is a provider/root-call
format plus latency failure mode, not a recursive-loop success.

## Failure Modes

### 1. Small Static Context Is Not Where RLM Wins

Pi plain got 10/10 on the completed 2k slice. This supports the professor's
claim that plain prompting can beat or match RLM on small static contexts. The
RLM result here is not "RLM beats prompting"; it is "RLM can match accuracy, but
costs more."

### 2. Semantic Labeling Errors Dominate

Codex RLM missed row 52. The aggregate comparison logic was deterministic after
classification, but the underlying spam/ham classification ledger differed from
gold and selected `more common than` instead of `less common than`.

### 3. Over-Verification Can Explode Cost

Vertex RLM row 54 in the 4k run finalized correctly but used 136 calls and 12
evals. It over-investigated ambiguous fragments before calling `FINAL`. The
prompt v9 consistency discipline helped correctness but can increase latency.

### 4. 16k Is Genuinely Hard

Both Codex RLM and Pi plain missed the 16k numeric sample. This is the first
result in this lane that shows the benchmark becoming nontrivial for strong
plain prompting and for our RLM stack.

### 5. No Child RLM Calls Fired

For these spam aggregation tasks, that is not automatically a failure. The
natural decomposition is root orchestration plus `map-lm` leaves and
deterministic aggregation. Child sessions are better suited for independent
multi-step investigation lanes, not simple per-message classification.

## Current Honest Verdict

This benchmark does not yet prove that RLM beats plain prompting. It does show:

- the engine can run Oolong-style exact aggregation tasks end to end;
- prompt v9 fixed a real consistency failure from the first exploratory run;
- Vertex RLM reached 10/10 on the 2k slice with source-backed artifacts;
- Codex RLM was cheaper in call count but missed one 2k row and the 16k row;
- Pi plain is a very strong baseline on the 2k slice and still missed 16k;
- the largest current RLM weakness is cost/latency control under uncertainty.
