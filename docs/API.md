# Clojure API

`fractal-engine.api` is the supported public facade for Clojure consumers. It drives
sessions, reads canonical SQLite + BlobStore state, and exposes trust/provenance
helpers without depending on runtime namespace details.

```clojure
(require '[fractal-engine.api :as fe])
```

## Session drive

```clojure
(def cfg
  (fe/config {:runs-dir ".fractal"
              :models {:root  {:provider :scripted :model "scripted"}
                       :leaf  {:provider :scripted :model "scripted"}
                       :child {:provider :scripted :model "scripted"}}}))

(def s
  (fe/start-session! cfg {:id "demo"
                          :alias "demo"
                          :overlay "Additional role instructions can go here."}))

(def result
  (fe/run-turn! s "Define x and FINAL {:answer 42}."))

(def locator (:locator result))

(fe/stop-session! s)
```

Public drive functions:

| function | purpose |
|---|---|
| `config` | normalize engine configuration |
| `start-session!` | start a live canonical session; accepts `:id`, `:alias`, `:title`, `:overlay` |
| `run-turn!` | run one user message on a live session |
| `run-turn-async!` | run one turn asynchronously and poll `progress` mid-run |
| `stop-session!` | mark the live session stopped |
| `resume-session!` | restore a completed head into the same session and advance it |
| `fork-session!` | restore a source head into a new user/API session |
| `run-task!` | one-shot helper: start, run one task, stop |

`FINAL` does not terminate a session. A stopped live handle can be resumed later from the
canonical store.

Session aliases are canonical SQLite rows pointing at session ids. The local filesystem
is only the physical backend for SQLite, Datahike projection, and BlobStore files;
aliases do not live in a separate canonical alias directory.

## Locators

Read functions accept locators:

```clojure
{:store/root ".fractal"
 :session/id "demo"
 :head/id "head-..."} ; optional
```

Use:

| function | purpose |
|---|---|
| `session-locator` | construct a locator from store root/session id |
| `resolve-session` | resolve a session id, alias, or stable handle |
| `list-sessions` | list canonical sessions in a store root |
| `session-ref` | read the current-head ref for a session |

Locators are canonical identities, not filesystem session homes.

## Model-facing surface

The public Clojure API does not change what the model can call inside the session REPL.
The model-facing surface remains exactly:

```text
FINAL lm map-lm rlm map-rlm attach-rlm
```

## Read surface

Reads use SQLite rows and BlobStore payloads. Datalog queries use a derived Datahike
index that can be rebuilt from SQLite. Reads perform no provider calls and do not require
generated projection files.

| function | purpose |
|---|---|
| `view` | dereference the current or selected head state into the materialized session view |
| `event-stream` | read compact canonical event records in append order |
| `event-trace` | derive an operator audit trace from compact event rows |
| `event-chain` | return causal ancestors plus one event |
| `progress` | lightweight mid-run status for polling |
| `load-node` | load one node, including steps, leaves, children, and final value |
| `load-at` | resolve an address within a root run and load that node |
| `tree` | load the recursively expanded summary tree |
| `node-locator` | resolve a node address to its canonical locator |
| `inspect` | structured detail for a session/node |
| `check-consistency` | validate SQLite rows, BlobStore refs, and the Datahike projection |
| `rebuild-index!` | rebuild the derived Datahike index from canonical SQLite rows |

Example:

```clojure
(def root (fe/load-node locator))
(def full-tree (fe/tree locator))
(def events (fe/event-stream locator))
(def trace (fe/event-trace locator))
(def cause-chain (fe/event-chain locator 12))
(def report (fe/check-consistency ".fractal"))          ; deep by default
(def quick (fe/check-consistency ".fractal" {:mode :quick}))
(fe/rebuild-index! ".fractal")
```

The default node/read surface is the current/head view: it follows
`session/current-head` (or a locator's `:head/id`) to a complete immutable state root and
materializes that root into the active session view. Historical sibling heads and
abandoned branch events remain available through `event-stream`, inspection details, and
direct relationship queries. `event-stream` returns compact audit records: event id,
type, session, timestamp, row identity, status, source ids, and payload refs. It does not
duplicate full message/eval/call/snapshot/head payloads.

`event-trace` is the operator layer over `event-stream`. It adds compact summaries and
derived causal refs such as "this ref update was caused by this head event" or "this
head was sealed from this snapshot." `event-chain` walks those refs for a single event.
Both are audit views; restore and resume still dereference immutable head state roots.

`progress` remains useful while a turn is in flight because calls, messages,
invocations, and progress rows are committed as events arrive.

`check-consistency` defaults to `{:mode :deep}`, which verifies blob existence/hash and
compact head-state components. `{:mode :quick}` checks structural rows and relationships
without reading every blob.

## Recursive relationships

Datahike-backed queries make relationships inspectable:

| function | purpose |
|---|---|
| `outgoing-invocations` | invocations made by a caller session |
| `incoming-invocations` | invocations targeting a callee session |
| `attached-derived-sessions` | attached child sessions derived from a source session/head |
| `session-derivations` | derivation/provenance edges where a session is source or target |

`lm` and `map-lm` create calls only, so they do not appear as invocations. `rlm`,
`map-rlm`, and `attach-rlm` create invocation facts. `rlm`/`map-rlm` return RLM
envelopes: read the child final under `:rlm/value`, continue through `:rlm/session`,
and branch/prove from `:rlm/head`. Fork and attach source-head reuse is represented as
derivation, not as head basis.

## Trust and provenance

The trust helpers expose the same claim-vs-evidence data used by `fractal verify`:

| function | purpose |
|---|---|
| `extract-claims` | collect evidenced claims from a final value |
| `check-claims` | check cited evidence against files, optionally relative to a base dir |
| `summarize-claims` | roll check verdicts into totals and an overall status |
| `node-provenance` | return a node's final value, claims, child refs, and leaf refs |

```clojure
(def node (fe/load-at locator "root"))
(def checks (fe/check-claims (:final node) "."))
(fe/summarize-claims checks)
```

## Provider helpers

Provider auth is data:

| function | purpose |
|---|---|
| `provider-descriptor` | return the descriptor for a provider id |
| `auth-status` | report whether credentials are currently available |

```clojure
(fe/provider-descriptor :scripted)
(fe/auth-status :scripted)
```

## Storage boundary

SQLite stores operational facts, refs, and index transaction batches. BlobStore stores
payloads, including compact head state roots and root request descriptors. Datahike is a
derived query index. Filesystem paths are physical backend choices for the local
implementations. This API does not expose a session-home contract and does not require
filesystem projections for reads.

There is no S3/AWS implementation in this pass.

## Intentionally not exposed

Rendering functions remain outside the stable API. `fractal-engine.render` returns
human-oriented strings that include CLI hints and formatting choices; those can evolve
with the CLI. Library consumers should use data-returning API functions and render their
own views.

Compute internals also remain outside the public facade: `process/run-process!`,
runtime eval helpers, artifact writers, storage transactions, cache internals, and prompt
construction are implementation details.
