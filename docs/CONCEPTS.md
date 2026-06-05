# Concepts

How the engine actually thinks. If you have read the [README](../README.md), this is the
deeper model behind it.

## One shape: input to processing to output

Everything the engine does is a transformation: some input becomes some output. The only
thing that varies is the kind of processing in the middle:

| kind | how | cost and certainty | use it for |
|---|---|---|---|
| deterministic | ordinary Clojure | exact, cheap, certain | IO, parsing, regex, counting, sorting, grouping, joining, shape checks, composing values |
| probabilistic | `lm` / `map-lm` | one bounded model judgment | semantic judgment over already-bounded material |
| recursive | `rlm` / `map-rlm` / `attach-rlm` | a fresh run of the whole loop | sub-problems that need their own inspect/search/judge loop or restored prior state |

The skill of using the engine is choosing the cheapest sufficient processing for each
transformation, splitting when the surface is large or uncertain and collapsing back when
the sub-problem is bounded enough to solve directly.

## The loop

1. Conversation messages are the model's working transcript.
2. The model replies with plain text containing fenced ` ```clojure ` blocks.
3. The host evaluates the blocks in order in a persistent namespace.
4. The host returns one compact observation message.
5. The loop repeats until `(FINAL value)`.

Rules that matter:

- A bare expression is only an observation. Its value is shown back to the model but is
  not returned to a caller.
- Only `(FINAL value)` returns a value to the caller/user.
- `FINAL` does not end the session. It records a completed head and returns control.
- Do not `FINAL` work you have not observed. Bind intermediate values with `def`, inspect
  them, then decide.

## Persistent vars

REPL vars are working memory for the session across steps and turns. The observation text
is a lossy projection; real EDN-safe values are snapshotted into BlobStore when a turn
reaches `FINAL`. There is no magic `context` variable: if the model wants state, it
defines a var.

## Provider context and compaction

The provider-visible transcript is not the session's source of truth. The source of
truth for continuation is the current immutable head: active messages, turns, calls,
evals, snapshots, refs, counters, and restorable REPL vars.

When the active provider transcript approaches the configured model window, the engine
can compact it by creating another normal same-session head. The compaction turn asks
the configured root model to read the current head transcript plus structured state
facts and produce one semantic continuation frame. That frame becomes a synthetic user
message on the new head.

After compaction:

- session identity is unchanged;
- `session/current-head` advances to the compacted head;
- the active provider messages are the system prompt plus the compact frame;
- REPL vars are preserved through a `:context-compaction` snapshot;
- prior transcript rows remain reachable through earlier heads and history reads.

The compact frame is provider-facing orientation, not runtime authority. If the model
needs exact values, it should inspect restored vars or derive fresh observations with
Clojure.

## The six functions

```clojure
(FINAL value)                         ; return value to caller/user for this turn
(lm input query [mode])               ; one bounded input -> one model judgment
(map-lm inputs query [mode])          ; leaf judgment over inputs, order preserved
(rlm task)                            ; new child session, returns an RLM envelope
(map-rlm tasks [shared])              ; child envelopes, order preserved
(attach-rlm handle task [opts])       ; continue a session ref or branch from a head ref
```

`mode` is `:string` or `:edn`. `map-lm` and `map-rlm` are parallel fanout, capped by the
host config.

Partial batch failures return a vector aligned to the input order. Successful `map-lm`
slots hold values. Successful `map-rlm` slots hold RLM envelopes whose child final is
under `:rlm/value`. Failed slots hold `{:fractal/failed true :index i :error ...}`.
Successful facts and child sessions remain durable and queryable.

## Leaves, children, and attach

A leaf (`lm`/`map-lm`) is the non-recursive base case. It records call facts and payload
blobs only. It does not create sessions and does not create invocations.

A child (`rlm`/`map-rlm`) is a normal session created by a caller invocation. It has its
own id, cache id, calls, snapshots, heads, and final value. The caller receives an RLM
envelope with `:rlm/value`, `:rlm/session` for continuation, `:rlm/head` for immutable
branch/provenance, and deterministic `:rlm/meta` for recognition. The caller records
outgoing invocation facts and the callee is queryable as a child session.

`attach-rlm` continues a session when given a session ref without `:head/id`. It branches
into a new attached child when given an immutable head ref. Head-ref attach records both
the caller invocation edge and the attached-from source session/head/snapshot/fingerprint
facts.

Do not confuse the operations:

| operation | advances source? | creates session? | creates invocation? |
|---|---:|---:|---:|
| next turn / resume | yes, same session | no | no |
| fork | no | yes, user/API session | no |
| `rlm` | no | yes, child session | yes |
| `map-rlm` | no | yes, child sessions | yes |
| `attach-rlm` session ref | yes, same callee session | no | yes |
| `attach-rlm` head ref | no | yes, attached child | yes |
| `lm` / `map-lm` | no | no | no |

## Canonical storage

SQLite stores canonical hot facts: ids, refs, aliases, statuses, timestamps, kinds,
current-head, head lineage, invocation relationships, model/provider ids, token/cost
metadata, cache ids, blob identity rows, and Datahike projection transaction batches.

BlobStore stores canonical payloads: message content, provider requests/responses, eval
code/results/observations, final values, snapshots, vars, raw errors, stack traces, and
rendered/export bodies.

The boundary is semantic, not size-based. Small final values and small snapshots are
still blobs. Arbitrary generated content is not inlined into SQLite or Datahike.

Datahike is a derived Datalog index over the SQLite facts. Local filesystem storage is
the current physical backend for SQLite, the Datahike projection, and blobs. Session
homes are not source truth.

## Decomposition patterns

- Map-and-aggregate: `map-lm` per-item labels, deterministic Clojure reduce.
- Reconnoiter-then-decompose: size/structure first, then one child per partition.
- Chunk-and-reduce: partition above fanout caps, process chunks, reduce locally and
  globally.
- Panel/cross-check: get independent reads prompted to refute a load-bearing claim.
- Loop-until-dry: keep discovering until a round surfaces nothing new.

For exact counting, ranking, set membership, or extraction, keep an auditable ledger and
compute the aggregate deterministically in Clojure. Leaves judge; Clojure tallies.

## Trust layer

A `FINAL` value is a claim. The trust layer audits claim-vs-evidence structures:

1. Grep floor: deterministic symbol/file existence checks.
2. Engine judge with `--deep`: a fresh engine task re-reads cited source and attempts to
   refute the claim.

Run the grep floor always. Use `--deep` for load-bearing claims you are about to act on.

## Recursion is finite

Every leaf and child spends real calls. The model is prompted to split only at genuine
uncertainty, collapse to deterministic processing when possible, and avoid re-verifying
what is already certain.

There is no engine-level budget governor yet. Use provider timeouts, max turns, and
fanout caps deliberately for normal live work.
