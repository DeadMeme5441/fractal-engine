# Concepts

How the engine actually thinks. If you've read the [README](../README.md), this is the
deeper model behind it.

## One shape: input → processing → output

Everything the engine does is a transformation: some input becomes some output. The
*only* thing that varies is the **kind** of processing in the middle:

| kind | how | cost & certainty | use it for |
|---|---|---|---|
| **deterministic** | ordinary Clojure | exact, cheap, certain | IO, parsing, regex, counting, sorting, grouping, joining, shape checks, composing values |
| **probabilistic** | `lm` / `map-lm` (a *leaf*) | a model judging one bounded input | semantic judgment over already-bounded material |
| **recursive** | `rlm` / `map-rlm` (a *child*) | a fresh run of the whole loop | a sub-problem that needs its own inspect/search/judge loop |

The whole skill of using the engine — and the whole skill the model is prompted toward
— is **choosing the cheapest sufficient kind of processing for each transformation**,
splitting where the surface is large or uncertain and collapsing back the moment a
sub-problem is bounded enough to solve directly.

## The loop

1. The conversation messages are the model's working transcript.
2. The model replies with plain text containing fenced ` ```clojure ` blocks (no
   provider "tool calls" — the host only evaluates text fences).
3. The host evaluates the blocks **in order** in a persistent namespace and returns one
   **compact observation** message. Several blocks in one reply are one batch with one
   combined observation.
4. The model reads the observation and decides the next move.
5. This repeats until `(FINAL value)`.

Two rules that matter:

- **A bare expression is only an observation.** Its value is shown back to the model but
  is *not* returned to a caller. Only `(FINAL value)` returns a value to whoever called
  this session (a parent `rlm`, or you).
- **Don't `FINAL` work you haven't seen.** If you need a result before deciding, bind it
  with `def` and inspect it next step; don't call `FINAL` in the same batch as work
  whose result you haven't observed.

### Persistent vars are the real memory

REPL vars are durable working memory for the whole session — across steps *and* across
turns. The observation text is a lossy projection; the real values live in your vars.
The model is prompted to bind large values with `def` and inspect *projections* (counts,
keys, samples) rather than dumping them. There is **no magic `context` variable**: if
the model wants state, it `def`s it.

## The six functions

```clojure
(FINAL value)                      ; end this turn, return value to the caller
(lm input query [mode])            ; one bounded input → one model judgment
(map-lm inputs query [mode])       ; lm over ≤50 inputs, in parallel, order preserved
(rlm task)                         ; run the whole loop on a sub-problem; returns its FINAL
(map-rlm tasks [shared])           ; rlm over ≤50 independent sub-problems, in parallel
(attach-rlm path task [opts])      ; resume a prior session as a child, then run task
```

- `mode` is `:string` (default) or `:edn` (the model returns parseable EDN).
- `map-lm` / `map-rlm` are **real parallel fanout**, capped at 50 inputs per call by
  default. For more, chunk in Clojure and compose the chunk results yourself.
- `attach-rlm` restores a completed prior session's last snapshot as a child, then runs
  `task` against that restored state — reach for it only when a prior session already
  holds state you need.

### Leaves vs. children — the key distinction

A **leaf** (`lm`/`map-lm`) is the non-recursive base case: a pure function whose body
happens to be a model. It reads one *already-bounded* input and returns one answer. It
does not loop, inspect, or decompose.

A **child** (`rlm`/`map-rlm`) is a full recursion of the engine: it gets a task and runs
its own evaluate-observe-until-`FINAL` loop, with its own REPL, its own leaves, and its
own children. A child *is* the root of its own subtree.

> The mistake to avoid: a `map-rlm` where every child does the same single bounded read
> is really a `map-lm`. Reserve children for sub-problems that need their own loop.

Children inherit none of the parent's vars, helpers, or working directory. A good child
task states the material (or handles to it), the boundary, the question, the missingness
rules, and the exact `FINAL` shape wanted back.

## Decomposition patterns

These are a few lines of Clojure over the surface; the model composes and reuses them
(often as `defn` helpers in its own session):

- **Map-and-aggregate** — `map-lm` per-item labels, then a deterministic reduce in
  Clojure. *The model labels; Clojure counts.* (Never ask a leaf for an exact count.)
- **Reconnoiter-then-decompose** — a cheap sizing/structure pass first, then one child
  per independent partition via `map-rlm`.
- **Chunk-and-reduce** — when material exceeds 50 items, partition in Clojure, process
  per chunk, reduce locally then globally.
- **Panel / cross-check** — for a load-bearing claim, get N independent reads each
  prompted to *refute* it; keep it only if it survives a majority.
- **Loop-until-dry** — for discovery of unknown size, keep finding until a round
  surfaces nothing new.

## Exact-answer discipline

For counting, ranking, set membership, or exact extraction, the model is steered to keep
an auditable ledger (parser checks, per-item labels, frequency maps, the selected
answer, uncertain items) and compute the aggregate **deterministically in Clojure** from
the model-produced vector — then run a consistency check before `FINAL`. Leaves judge;
Clojure tallies. This is how the engine keeps probabilistic processing from corrupting
exact results.

## The trust layer

Because a `FINAL` value is a *claim*, the engine ships a way to audit claims against the
source they cite. A claim typically looks like `{:description "…" :evidence "path: a
quote or code snippet"}`. Verification has two layers (`fractal verify`):

1. **Grep floor — deterministic, free.** Pull the code-shaped tokens out of the evidence
   (snake_case, dotted names, CamelCase) and check they actually occur in the cited file
   (resolved against `--root`). Verdicts: `supported` / `partial` / `unsupported` /
   `file-missing`. This catches a citation to a file the model never read, or invented
   symbols, *instantly and for free*. It cannot judge meaning.
2. **Engine judge — `--deep`.** Hand the claims back to the engine as a fresh task:
   *"read the cited code and decide whether it genuinely supports the claim; use a child
   or leaves, your call; be adversarial and try to refute."* The engine reads the real
   source and returns `:supported` / `:refuted` per claim. This catches the confabulation
   the grep waves through — a claim can cite entirely real symbols and still misdescribe
   what the code does.

The two are complementary on purpose. Grep answers *"are the cited symbols real?"* with
certainty and no cost; the judge answers *"does the code mean what's claimed?"* with the
engine's own recursive reading. Run the floor always; escalate to `--deep` for
load-bearing claims you're about to act on.

## Recursion is finite

Every level of leaves and children spends real calls. The model is prompted to split
only at genuine uncertainty, collapse to the cheapest sufficient processing the instant
a sub-problem is bounded, and never re-verify what is already certain. Scale the
decomposition to the question: a quick lookup wants one or two moves; a thorough audit
justifies wide fanout, panels, and adversarial checks. Over-investigation is a failure
mode, not thoroughness.

There is **no engine-level budget or timeout governor yet** — see the cost/leashing
warnings in the [README](../README.md#live-providers).
