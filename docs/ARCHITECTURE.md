# fractal-engine-v1 Architecture

This document is a public maintainer and contributor map for the v1 runtime. It
describes the architecture that exists in the Phase 1-4 implementation and the
thin agent control plane around it.

The engine is a layered Clojure runtime where a session owns a persistent SCI
context, a model produces fenced Clojure, the host evaluates those blocks in a
capability sandbox, and the loop continues until a `FINAL` value or a terminal
condition. Recursive calls reuse the same session and storage model rather than
adding a second execution system.

## Layer Map

```text
public API
  fractal.engine.api
  fractal.engine.cli

composition roots
  fractal.engine.session
  fractal.engine.recursion

runtime spine
  fractal.engine.session-loop
  fractal.engine.kernel

ports and shared services
  fractal.engine.store
  fractal.engine.adapter
  fractal.engine.capability
  fractal.engine.compaction
  fractal.engine.live
  fractal.engine.payload-io

backends
  fractal.engine.store.memory
  fractal.engine.store.sqlite
  fractal.engine.store.blobstore
  fractal.engine.adapter.fake
  fractal.engine.adapter.sdk

foundations
  fractal.engine.payload
  fractal.engine.cache
  fractal.engine.prompt
  fractal.engine.catalog
  fractal.engine.concurrent
  fractal.engine.time
```

The namespace graph is expected to remain acyclic. `test/fractal/engine/deps_acyclic_test.clj`
guards the load-bearing dependency rules: the store port depends only on pure
payload helpers, live dispatch does not depend on the store, the adapter port is
engine-free, and capability profiles take host function implementations as data.

## Responsibilities

| Namespace | Responsibility |
|-----------|----------------|
| `fractal.engine.api` | Supported Clojure API for config, lifecycle, reads, payload hydration, live subscriptions, and the fake responder helper. |
| `fractal.engine.cli` | Agent-operable command seam over the public API. It handles CLI config files, JSON/EDN output, usage commands, and inspection commands without adding runtime semantics. |
| `fractal.engine.session` | Sole composition root. It builds stores and adapters, resolves config, creates and resumes sessions, owns the turn lock, initializes SCI contexts, and spawns child or attached sessions. |
| `fractal.engine.session-loop` | Turn and step spine: open turns, assemble requests, call adapters under deadline, append assistant/observation messages, finalize turns, and publish heads after successful `FINAL`. |
| `fractal.engine.kernel` | SCI evaluation mechanics, block extraction, eval records, `FINAL`, `inspect`, vars snapshotting, and vars restore. |
| `fractal.engine.recursion` | Leaf calls, child calls, attached-child calls, fan-out behavior, recursive envelopes, and lineage-edge recording. It receives spawn/run/stop closures from `session` instead of requiring `session` directly. |
| `fractal.engine.store` | `SessionStore` protocol, pure event fold, event taxonomy, head helpers, lineage-edge helpers, and payload-ref auditing. |
| `fractal.engine.store.sqlite` | Durable `SessionStore`: SQLite event storage plus a global BlobStore payload backend. |
| `fractal.engine.store.memory` | In-process `SessionStore` with the same semantic contract, primarily for tests and ephemeral runs. |
| `fractal.engine.store.blobstore` | File-backed content-addressed payload storage for SQLite durability. |
| `fractal.engine.payload` | Pure canonical EDN bytes, SHA-256 content ids, and tagged payload refs. |
| `fractal.engine.payload-io` | Store-coupled inline-vs-ref policy and payload hydration. |
| `fractal.engine.adapter` | Provider adapter port and call-record shape. |
| `fractal.engine.adapter.request` | Request assembly from the folded view, compaction boundary, system overlays, and cache metadata. |
| `fractal.engine.capability` | Capability profile lattice, clamp/validation rules, and SCI options. |
| `fractal.engine.compaction` | Context assessment and transcript compaction. |
| `fractal.engine.live` | Per-session live dispatch, transient notifications, backlog recovery, and progress projection. |

No namespace outside the store should invent a durable write path. Runtime code
appends facts through the `SessionStore` port and reads runtime state through the
folded view.

## Core Ontology

| Term | Meaning |
|------|---------|
| Session | Durable identity boundary for metadata, event history, counters, SCI context, heads, and lineage. |
| Turn | One user message processed until `FINAL` or a terminal non-final status. |
| Step | One adapter-call iteration inside a turn. |
| Eval | One fenced Clojure block evaluated by the SCI kernel. |
| Message | Transcript entry with role `:user`, `:assistant`, or `:observation`; system text is assembled for requests. |
| Event | Durable appended fact. Folding events produces the session view. |
| View | Pure projection over events. It is not a second persistence authority. |
| Payload ref | Tagged content-addressed reference to a value stored in the payload backend. |
| Head | Immutable content-addressed continuation boundary for a session. |
| Current head | Mutable pointer to the session head that resume and attach should advance from. |
| Lineage edge | Durable content-addressed relation between source/target sessions and heads. |

Filesystem locations, including `:store/dir`, are physical backend locations.
They are not logical session identity. Identity is carried by session ids, payload
ids, head ids, event ids, and edge ids.

## Normal Turn Flow

1. `session/run-turn!` checks stop status and `:max-turns`, acquires the turn
   lock, optionally compacts, appends the user message, appends `:turn/started`,
   and delegates to `session-loop`.
2. `session-loop/run-step!` appends `:step/started` before provider work so live
   observers can see in-flight progress.
3. `adapter.request/build-request` reads the current folded view, selects kept
   messages after compaction, hydrates payload refs, maps observations to
   adapter-facing user messages, prepends system text, and attaches cache
   metadata.
4. The adapter is called through the `LlmAdapter` port under one deadline.
   Streaming token fragments, when enabled, are transient live items only.
5. The loop appends the assistant message and `:step/put`.
6. The kernel extracts fenced Clojure blocks, evaluates them in the session SCI
   context, and appends one `:eval/added` event per block.
7. The loop appends one observation message derived from the eval records.
8. If no `FINAL` was called, the loop continues to another step.
9. If `FINAL` was called, the loop snapshots vars, appends
   `:session/vars-snapshotted`, appends the terminal `:turn/put`, publishes a
   `:turn-final` head, hydrates the final value, and returns a final turn result.
10. If timeout, provider failure, stop, context hard limit, or max steps occurs,
    the loop appends a terminal `:turn/put` with a non-final status and returns a
    non-final turn result. Non-final terminal paths do not publish heads.

## Recursive Flow

The recursive harness adds model-call host functions inside the SCI context. They
are not top-level API functions.

- `lm` and `map-lm` are leaf calls. They make bounded adapter calls and do not
  create sessions, heads, or lineage edges. `map-lm` preserves input order and
  returns per-slot failure sentinels instead of throwing the whole fan-out on a
  partial failure.
- `rlm` and `map-rlm` create fresh child sessions in the same store. Each child
  has its own SCI context, cache id, capability-clamped profile, loop, events,
  and heads. After the child reaches `FINAL`, the parent receives an envelope and
  records a `:invocation` edge to the child's current head.
- `attach-rlm` resolves a selected source session/head, creates a fresh attached
  child, restores that child from the source head's vars snapshot, runs one task,
  returns the usual envelope, and records a `:derivation` edge. The source
  session and selected source head are not advanced.

Recursive children are ordinary sessions. Storage, resume, live query, heads, and
payload refs use the same mechanisms as root sessions.

## Storage Authority

For durable v1 storage, SQLite plus BlobStore are canonical:

- SQLite stores session rows and per-session event rows.
- BlobStore stores payload bytes by content hash.
- The current view is a pure fold/projection over events.
- `current-head` is the authoritative continuation pointer.
- `MemoryStore` implements the same port for test and in-process use, but it is
  not the durable authority.

See `STORAGE_AND_HEADS.md` for the storage and head model in detail.

## High-Level Event Types

The folded view is advanced by a small event taxonomy:

| Group | Event types |
|-------|-------------|
| Session lifecycle | `:session/started`, `:session/stop-requested`, `:session/stopped`, `:session/error` |
| Turn lifecycle | `:turn/started`, `:turn/put` |
| Step lifecycle | `:step/started`, `:step/put` |
| Transcript and evals | `:message/appended`, `:eval/added` |
| Snapshots and compaction | `:session/vars-snapshotted`, `:session/compacted` |
| Heads and lineage | `:head/published`, `:lineage/edge-added` |
| Live transient only | `:delta/token`, `:subscribe/gap` |

Only durable events receive `:event/id` and fold into the view. Transient live
items are never persisted and are recoverable only through later durable state.

## Failure Semantics

- Store writes are single-writer per session under the store lock.
- SQLite persists before fold. A failed durable insert does not advance the
  in-process view.
- SQLite batch appends are one transaction. A failed batch folds nothing.
- Head publication validates an optional expected basis and fails with a typed
  CAS error instead of silently replacing a changed current head.
- Provider timeout, provider failure, eval error, stop, and budget exhaustion
  settle the turn as non-final terminal results. They do not publish heads.
- Failed evals are recorded as `:eval/added` with an error map and are returned
  to the model as observations when the turn can continue.
- Live delivery is off-lock. Slow or throwing subscribers cannot stall writes.
- Live queue overflow may drop transient items or skip push delivery, but it does
  not remove durable events from the log. Subscribers recover through
  `events-since`.
- Same-session writes from a subscriber callback are rejected as reentrant.

## Contributor Invariants

1. Keep `fractal.engine.api` thin. New runtime behavior should be composed below
   the API unless a supported surface change is intentional.
2. Keep the loop on ports. `session-loop` should not know SQLite, BlobStore, or a
   provider implementation.
3. Keep `apply-event` pure. Views are projections from events.
4. Persist payloads before appending events that reference them. Orphan payloads
   are acceptable; dangling refs are not.
5. Treat `current-head` as the only mutable continuation pointer.
6. Publish a head after every successful `FINAL` turn and after compaction.
7. Do not restore by replaying provider calls or evals. Restore from stored
   snapshots referenced by heads.
8. Keep attach additive. `attach-rlm` must create a fresh derived child and leave
   the selected source unchanged.
9. Keep lineage durable and content-addressed. Do not infer invocation or
   derivation edges from naming conventions or backend paths.
10. Treat filesystem paths as backend configuration only, never as session
    identity.

## Verification Pointers

The current implementation details above are covered by:

- `src/fractal/engine/session.clj`
- `src/fractal/engine/session_loop.clj`
- `src/fractal/engine/kernel.clj`
- `src/fractal/engine/recursion.clj`
- `src/fractal/engine/store.clj`
- `src/fractal/engine/store/sqlite.clj`
- `src/fractal/engine/store/blobstore.clj`
- `test/fractal/engine/store_contract_test.clj`
- `test/fractal/engine/store_sqlite_test.clj`
- `test/fractal/engine/session_test.clj`
- `test/fractal/engine/recursion_test.clj`
- `test/fractal/engine/phase4_test.clj`
- `test/fractal/engine/deps_acyclic_test.clj`
