# Architecture

`fractal-engine` is a small recursive compute engine with a canonical storage layer
around it. The compute kernel remains `input -> processing -> output`; storage records
what happened without adding model-facing concepts.

```text
┌─────────────────────────────┐
│ CLI / API / codebrain       │
│ agentcli · api · render     │
└──────────────┬──────────────┘
               │
┌──────────────▼──────────────┐
│ read surface                 │
│ projection · inspect · trust │
└──────────────┬──────────────┘
               │
┌──────────────▼──────────────┐
│ canonical storage            │
│ session-db · store.*         │
└──────────────┬──────────────┘
               │
┌──────────────▼──────────────┐        ┌──────────────────────┐
│ compute engine               │        │ provider             │
│ process · session-loop       │◄──────►│ clojure-llm-sdk      │
│ leaf · session-invocation    │        │                      │
│ runtime · rlm                │        │                      │
│ artifacts · snapshot · event │        └──────────────────────┘
└─────────────────────────────┘
```

## Compute engine

- **`process`** owns configuration and session execution assembly. It starts sessions,
  installs the six REPL functions, selects the configured model for the current
  invocation edge, and delegates the actual loop/call/invocation work to smaller
  namespaces.
- **`session-loop`** owns one session input: provider message, Clojure eval,
  observation, repeat, then commit a completed head when `(FINAL value)` appears. The
  persisted row is still named `turn` for the read surface, but the semantic boundary is
  "session input to immutable head."
- **`leaf`** owns `lm` / `map-lm`: bounded provider calls recorded as call rows and
  payload refs only. Leaves never create sessions, heads, or invocation edges.
- **`session-invocation`** owns `rlm` / `map-rlm` / `attach-rlm`: normal session
  creation/continuation plus invocation edges between caller and callee heads. It frames
  child/attach instructions as the callee's user input for that edge; it does not install
  a different behavior prompt into the callee session.
- **`provider-call`** owns provider request construction, cache descriptors, traced
  provider completions, leaf parsing, and fanout bounds.
- **`rlm`** owns RLM invocation values: child result validation, stable handles,
  envelopes, attach target resolution, and invocation fact recording.
- **`runtime`** owns namespace creation, model-facing function interning, fenced Clojure
  extraction, eval, and observations.
- **`call`** is the traced-call skeleton: reserve id, record running call, run work,
  record success/failure.
- **`event`** folds rich runtime event values into the in-memory view used while a
  process is running. Persisted event history is compact SQLite rows plus row/payload
  refs, not full event-body blobs.
- **`artifacts`** owns live session state and writes canonical facts/payloads through
  storage. It does not maintain per-session filesystem homes.
- **`snapshot`** captures/restores EDN-safe vars through BlobStore payloads and
  SQLite snapshot rows.

The model-facing REPL surface is exactly:

```text
FINAL lm map-lm rlm map-rlm attach-rlm
```

## Canonical storage

- **`session-db`** owns the session-store facade: SQLite hot rows, head/current-ref
  writes, handle/alias resolution, materialized state reads, and relationship reads.
- **`session-sqlite`** owns the local SQLite database, WAL setup, typed row writes,
  and transaction-batch storage for the derived Datalog index.
- **`store.index`** owns Datahike projection catch-up/rebuild/query over SQLite
  transaction batches.
- **`store.consistency`** owns quick/deep integrity checks over SQLite rows, BlobStore
  bytes, and the derived Datahike index.
- **`store.io`** owns filesystem/EDN mechanics for local backends and product-side
  exports.
- **`store.payload`** owns content-addressed payload refs and blob identity facts.
- **`store.schema`** owns the Datahike schema value.
- **`store.value`** owns pure lookup-ref/value-shaping helpers.
- **`blob-store`** owns content-addressed payload bytes. The local implementation writes
  under the store root and verifies hash/size before SQLite rows point at blobs.
- **`session-model`** defines stable ids, head fingerprints, and invocation handles.

SQLite is canonical for hot facts, identity, refs, time, and graph relationships. BlobStore
is canonical for payloads. Datahike is a derived Datalog index rebuilt or lazily caught up
from SQLite transaction batches. Filesystem paths are backend details for the local SQLite,
Datahike, and BlobStore implementations; they are not session identity.

There is no filesystem session-home source of truth.

## Fact/payload boundary

SQLite stores queryable hot rows: ids, refs, statuses, timestamps, kinds, labels,
aliases, compact event records, head basis/current-head, derivation edges, invocation
caller/callee/source relationships, model/provider ids, token/cost facts, cache ids, and blob
hash/size/key/media-type facts. Datahike indexes the same facts for Datalog graph reads.

BlobStore stores replayable/restorable/renderable payloads: message content, root request
descriptors, bounded leaf requests, provider responses, eval code/results/observations,
final values, snapshots, vars, head state roots/components, raw errors, stack traces, and
export bodies. It does not store a generic full event payload for every event; compact
event records point at the typed payload refs that already own the bytes.

The boundary is semantic. Small final values and small snapshots are still blobs. Bounded
summaries/previews may be SQLite rows and Datahike index facts; arbitrary generated
content is not.

## Session transitions

- **Start session** creates a SQLite session row, optional alias, cache id, system
  transcript, and initial head state.
- **Prompt identity** is session-level and uniform: every RLM session starts with the same
  base behavior prompt, optionally plus a host overlay at birth. Root/child/attach are
  relation labels on invocation edges, so the edge-specific boundary is carried as the
  user input that caused that head transition.
- **Turn reaching `FINAL`** writes final/snapshot blobs, commits call/eval/message rows,
  writes a compact immutable head-state root, creates an immutable head, and advances the
  same session's current-head. `FINAL` does not terminate the session.
- **Provider-history compaction** is an internal model-mediated head transition. The
  engine asks the configured root model to rewrite the current head's transcript and
  state facts into one semantic continuation frame, records that output as a synthetic
  user message, writes a `:context-compaction` snapshot/head, and advances the same
  session's current-head. Prior heads keep the old transcript.
- **Resume** reads a selected head state, restores that exact state into the same
  session, and advances that same session from the selected basis head.
- **Fork** reads a selected source head state into a new user/API session and leaves the
  source unchanged. The source head is provenance, not the fork's same-session basis.
- **`rlm`/`map-rlm`** create child sessions and invocation facts; the model receives an
  RLM envelope with `:rlm/value`, continuation handle, head handle, and deterministic
  recognition metadata.
- **`attach-rlm`** continues a session ref or branches from a head ref. Session-ref attach
  advances the callee session's current-head. Head-ref attach creates a new attached
  child and records source -> attached-child derivation; the source head does not change.
- **`lm`/`map-lm`** create call facts and payload blobs only; no sessions or invocations.

## Read surface

- **`projection`** defaults to current/head state for addressable recursive node reads and
  resolves blob refs as needed. It exposes event/history reads separately.
- **`inspect`** returns structured call/session/head/invocation detail for CLI/API JSON.
- **`render`** formats `show`, `tree`, `cost`, `leaves`, `step`, and trust output.
- **`provenance`** extracts and checks claim-vs-evidence structures.
- **`api`** exposes the supported Clojure facade.
- **`agentcli`** is the command-line use surface.

Current reads dereference `session/current-head -> head/state-ref`, then materialize the
state root into the active session view; they do not mix abandoned branch events into
active output. History and progress reads use SQLite rows directly, so they remain useful
mid-run. No command requires Datahike or a generated projection file to exist.
The history/event stream is a compact audit surface: append order, event type, row
identity, status, source ids, and payload refs. It is not the current-state source and is
not a byte-for-byte replay of the rich in-memory event values. The derived event trace
adds summaries and causal refs over that compact stream for operators and product UIs;
it still does not participate in restore.

## Concurrency

The runtime serializes in-session event/call/invocation id allocation through the
session state's emit lock. SQLite commits are serialized per store root and use WAL mode
so parallel `map-lm`/`map-rlm` branches do not race current-head or row writes. Child work
can still run concurrently; only the canonical hot-store commits are serialized.

## Backends

This pass implements local SQLite, filesystem-backed Datahike projection, and local
filesystem-backed BlobStore. The protocols and config keep backend choices isolated, but
there is no S3/AWS implementation or live S3 validation.

## Invariants

- The model-facing surface is exactly six functions.
- Behavior prompt constraints remain tested.
- SQLite is canonical for session facts, refs, event rows, and index transaction batches.
- BlobStore is canonical for payload bytes.
- Datahike is rebuildable from SQLite and is not required for restore.
- Blob writes happen before SQLite rows/facts that reference them.
- A completed head points at a complete immutable state root.
- Restore/fork/attach build live runtime state from the selected head state, not a
  session-wide event fold.
- History/event reads return compact facts and refs; current reads return head-root
  state.
- Orphan blobs after a failed transaction are acceptable; dangling SQLite blob refs are
  not.
- Session homes are optional projections/exports only, never source truth.
- Folding recorded events never re-runs a model.
