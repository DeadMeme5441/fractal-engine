# Oolong RLM Benchmark Lane

This branch uses Oolong as an adversarial check on the recursive compute
surface. The goal is not to hide prompt learning; it is to make each prompt and
behavioral change explicit, then rerun the same public samples.

## Source

- Dataset: `oolongbench/oolong-synth`
- Config: `default`
- Split: `validation`
- Fetch API: `https://datasets-server.huggingface.co/rows`
- Local sample cache: `benchmarks/oolong/samples/`

The fixture generator records each row JSON and prompt file so runs are
reproducible without repeated dataset-server calls.

## Behavioral Learning

The first Vertex RLM run on `spam-2k-numeric` failed in a useful way:

- Gold answer: `[12]`
- RLM final value: `{:answer 13, ...}`
- RLM evidence text: "12 were classified as spam"

That is not a recursion failure. It is an exact-answer discipline failure:
the root composed a final value that contradicted its own ledger/evidence.

Prompt version 9 therefore added generic, benchmark-neutral guidance:

- exact aggregation should use deterministic Clojure for parsing/counting;
- leaf calls may classify bounded items, but the aggregate must be computed
  from the returned vector;
- exact tasks need an auditable ledger var;
- `FINAL` should be blocked behaviorally when `:answer` contradicts the
  ledger, frequency map, method, evidence, or notes.

After the first benchmark pass, prompt version 10 generalized the same lesson
with the older `clojure-rlm` live-eval lessons:

- split by evidence type and uncertainty surface, not final report section;
- treat root as coordinator, children as scoped investigators, leaves as
  bounded semantic readers, and Clojure as the exact worker;
- make bounded material broader than raw text, including listings, search
  results, records, tables, handles, metadata, and transcript slices;
- explicitly forbid provider tool/function calls because this engine consumes
  plain assistant text containing Clojure fences.

## Child/Leaf Discipline

Do not force child RLM calls just to prove recursion. Use the right shape:

- `lm`: one bounded semantic judgment.
- `map-lm`: many independent bounded semantic judgments, followed by
  deterministic aggregation.
- `rlm`: a subproblem that requires its own inspect/search/evaluate loop.
- `map-rlm`: several independent evidence lanes that each need a loop and
  can return compact values to the root.

For Oolong spam-counting, `map-lm` plus deterministic aggregation is often the
correct RLM shape. `map-rlm` is only justified for larger contexts that can be
partitioned into chunks whose local counts are later summed and checked by the
root.

## Commands

Fetch/update local fixtures:

```bash
python3 benchmarks/oolong/fetch_samples.py
```

Run the 10-row 2k-context Vertex batch:

```bash
benchmarks/oolong/run_vertex_2k10.sh
```
