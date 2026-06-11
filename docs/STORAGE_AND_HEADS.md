# Storage and Heads

This document describes fractal-engine storage authority, payload refs,
folded views, heads, lineage, and failure semantics. It is written for public
maintainers and contributors.

## Authority Model

The v1 runtime separates durable truth from projections:

```mermaid
flowchart LR
  SESSION["Session id"] --> EVENTS["SQLite event rows"]
  EVENTS --> VIEW["Folded view"]
  EVENTS --> HEADS["Immutable heads"]
  EVENTS --> EDGES["Lineage edges"]
  LARGE["Large values"] --> BLOBS["BlobStore bytes"]
  BLOBS --> REFS["Payload refs"]
  REFS --> EVENTS
  HEADS --> CURRENT["current-head pointer"]
  CURRENT --> RESUME["resume-session!"]
  CURRENT --> ATTACH["attach-rlm source selection"]
```

1. SQLite is canonical for durable session rows and per-session event rows when
   `:store :sqlite` is selected.
2. BlobStore is canonical for content-addressed payload bytes.
3. `MemoryStore` implements the same `SessionStore` contract for in-process and
   test use.
4. The session view is a pure fold over events.
5. `current-head` is the authoritative continuation pointer for resume and
   attach semantics.
6. Filesystem paths are physical backend locations only. They are not session,
   head, payload, or lineage identity.

The durable SQLite layout is intentionally small: one database for session and
event rows, plus a sharded BlobStore for payload bytes. The path passed as
`:store/dir` chooses where those physical files live; it does not participate in
semantic identity.

## SessionStore Port

All runtime storage goes through `fractal.engine.store/SessionStore`:

```clojure
(defprotocol SessionStore
  (create-session! [store session-map])
  (append-event! [store sid event])
  (append-events! [store sid events])
  (publish-head! [store sid head])
  (append-lineage-edge! [store sid edge])
  (intern-payload! [store value opts])
  (read-payload* [store ref])
  (current-view [store sid])
  (read-state [store sid])
  (peek-next-id [store sid counter-key])
  (notify-transient [store sid item])
  (subscribe! [store sid callback])
  (events-since [store sid event-id]))
```

Important contract points:

- `create-session!` is idempotent. Re-creating an existing session returns the
  existing slot/handle and does not clear state.
- SQLite `create-session!` can reopen a persisted session by folding its durable
  event log into a fresh in-process view.
- `append-event!` stamps event id, timestamp, and any entity id assigned by the
  event type.
- `append-events!` stamps consecutive ids and commits the batch atomically.
- `publish-head!` computes immutable head content under the same per-session
  store lock as event appends.
- `current-view` is the strong read-your-writes runtime projection.
- `read-state` is a relaxed external-reader hook; the current implementations
  return the folded cache.

## Folded View

The view shape from `empty-view` is:

```clojure
{:session   nil
 :messages  []
 :turns     []
 :steps     []
 :evals     []
 :heads     []
 :current-head nil
 :edges     []
 :counters  {:event 0 :message 0 :turn 0 :step 0 :eval 0}
 :vars-ref  nil
 :compact-from-event-id nil
 :error     nil
 :events    []}
```

The view is not stored as a competing authority. It is rebuilt by applying
`apply-event` over the event stream. SQLite recovery depends on this property:
closing and reopening a store reconstructs the same session view by folding the
durable log.

Projection fields have clear roles:

- `:events` is the ordered event stream folded so far.
- `:counters` tracks the max assigned ids for events and entities.
- `:heads` stores immutable head records.
- `:current-head` stores only the selected head id.
- `:edges` stores folded lineage edges.
- `:vars-ref` is the latest vars snapshot projection/fallback.
- `:compact-from-event-id` controls transcript pruning.

## Event Taxonomy

Every durable state change is an event:

| Event type | Fold effect |
|------------|-------------|
| `:session/started` | Sets the session entity. |
| `:turn/started` | Appends a turn entity. |
| `:turn/put` | Replaces the matching turn by `:turn/id`. |
| `:step/started` | Appends a step entity. |
| `:step/put` | Replaces the matching step by `:step/id`. |
| `:message/appended` | Appends a transcript message. |
| `:eval/added` | Appends an eval record. |
| `:leaf/called` | Appends a leaf (`lm`/`map-lm`) call record — request content, response, usage/cost. Always recorded: leaf spend is never invisible, and recorded leaves replay. |
| `:surface/called` | Appends a surface-call record (opt-in via `:surface/record?`) — function, args hash, result-or-ref. The world-read replay seam. |
| `:session/vars-snapshotted` | Updates `:vars-ref`. |
| `:session/compacted` | Updates `:vars-ref`, updates the compaction boundary, and appends one compact message. |
| `:head/published` | Adds a head and sets `:current-head`. |
| `:lineage/edge-added` | Adds a lineage edge. |
| `:session/stop-requested` | Sets session status to `:stop-requested`. |
| `:session/stopped` | Sets session status to `:stopped`. |
| `:session/error` | Stores `:error` and sets session status to `:error`. |

All stamped durable events are appended to `:events`. `.../put` events are value
replacement events, not patches. Events carry results, never recipes; folding
must not replay provider calls or evals.

Transient live items such as `:delta/token` and `:subscribe/gap` are not durable
events. They do not receive `:event/id` and do not fold into the view.

## Payload Refs

Values are stored inline when they are small enough and content-addressed when
they are large. The current inline threshold is 512 canonical bytes.

A payload ref is an opaque tagged map:

```clojure
{:fractal/ref  :payload
 :payload/id   "sha256:<hex>"
 :payload/kind :message | :eval-result | :final | :vars | :code | :request
 :payload/size 1234}
```

The hashing basis is `fractal.engine.payload/canonical-bytes`: deterministic
EDN bytes with sorted maps/sets and stable printing. The same value produces the
same `sha256:` id across MemoryStore and BlobStore.

Payload rules:

- Blob writes happen before events that reference them.
- Identical payload values deduplicate to the same content node.
- `read-payload` passes non-ref values through unchanged and dereferences tagged
  refs.
- Request assembly and compaction hydrate messages through the payload seam.
- Orphan blobs are acceptable after a failed or abandoned operation.
- Dangling refs are invalid and are detectable with `verify-no-dangling-refs`.

## Head Records

A head is an immutable content-addressed continuation boundary:

```clojure
{:head/id           "sha256:..."
 :head/version      1
 :head/session      "s-..."
 :head/basis        nil | "sha256:..."
 :head/event-range  [from-event-id to-event-id]
 :head/kind         :turn-final | :compaction | :turn-aborted
 :head/turn-id      1 | nil
 :head/vars-ref     <inline-value-or-payload-ref>
 :head/final-ref    <inline-value-or-payload-ref> | nil
 :head/compact-from-event-id nil | 42}
```

`publish-head!` derives:

- `:head/basis` from the folded current head before publication
- `:head/event-range` from the previous current head range to the requested
  boundary event
- `:head/id` from the canonical content of the head without `:head/id`

Publishing a head appends a `:head/published` event and sets `:current-head`.
The current head pointer is the mutable authority. The head content itself is
immutable.

## When Heads Are Published

Successful `FINAL` turn:

1. Snapshot SCI vars.
2. Append `:session/vars-snapshotted`.
3. Append final `:turn/put`.
4. Publish a `:turn-final` head whose event range ends at the final `:turn/put`.

Compaction:

1. Summarize kept messages into one continuation frame.
2. Snapshot vars.
3. Append one `:session/compacted` event.
4. Publish a `:compaction` head whose event range ends at the compaction event.

Non-final terminal outcome (timeout, budget-exceeded, error) — 0.7:

1. Append the terminal `:turn/put` (status, error, honest rollups).
2. Best-effort snapshot the live SCI vars (intern only — no
   `:session/vars-snapshotted` event, so wreckage never contaminates the
   legacy `:vars-ref` fallback).
3. Publish a `:turn-aborted` WRECKAGE head carrying that snapshot.
4. The `TurnResult` carries the wreckage head id as `:turn/aborted-head`.

A wreckage head makes a dead turn's accumulated state recoverable instead of
lost: fork or attach it by explicit `:head/id` to inspect or repair what the
aborted turn had built. Wreckage is NEVER a default restore basis — resume,
default attach, and default fork all select through `current-resume-head`,
which skips `:turn-aborted` heads. If snapshotting itself fails, the abort
still completes without a head (best-effort by design).

## Current Head and Resume

`resume-session!` for SQLite:

1. Opens a fresh store on the configured durable backend.
2. Recreates the in-process slot by folding durable events.
3. Builds a fresh SCI context.
4. Restores vars from the latest NON-aborted head's `:head/vars-ref`
   (`store/current-resume-head` — `:turn-final` and `:compaction` heads
   qualify; `:turn-aborted` wreckage is skipped).
5. Falls back to the top-level `:vars-ref` projection only if no qualifying
   head is available.

This priority is deliberate. `:vars-ref` is useful projection state, but it is
not a second mutable restore authority — and a dead turn's wreckage must never
silently become the session's continuation point. If a later vars snapshot
projection exists after a published head, resume still prefers the head.

## Attach Semantics

`attach-rlm` is derivation, not in-place resume.

Source resolution:

- A source session handle or session id selects that session's latest
  non-aborted head (`current-resume-head`).
- A head map or explicit `:head/id` selects that immutable head — including a
  `:turn-aborted` wreckage head; the explicit id is the only way to reach one.
- The selected source session and source head must exist.

Execution:

1. Create a fresh attached child session in the same store.
2. Clamp capability by both the caller and source profile.
3. Restore the child's SCI vars from the selected source head's `:head/vars-ref`.
4. Run the requested task in the child.
5. Publish the child's own head on `FINAL`.
6. Record a `:derivation` lineage edge.

The source session is not advanced. The source head is immutable and remains a
historical continuation boundary.

## Host Forks (0.7)

`fork-session!` is the HOST-side counterpart of `attach-rlm`: the embedder
(not a model inside a session) materializes a fresh session from a selected
immutable head of a persisted `:store :sqlite` session, with the head's REPL
vars restored and the source never advanced.

```clojure
(fe/fork-session! cfg source-sid)                      ; latest non-aborted head
(fe/fork-session! cfg source-sid {:head/id "sha256:…"}) ; explicit head — incl. wreckage
```

Semantics, mirroring attach where a parent session would have stood:

- Capability is `clamp(cfg-resolved, source-profile)`; a source MORE privileged
  than the caller's cfg is rejected (`:fractal/fork-capability-rejected`).
- The fork must re-present the source's surfaces
  (`:bundle/surface-mismatch` otherwise; `:bundle/allow-mismatch?` is the
  explicit escape).
- The fork is a normal session (`:session/kind :host-fork`) with
  `:session/source-session` / `:session/source-head-id` recorded, and a
  `:derivation` / `:host-fork` lineage edge in the FORK's own log — the source
  log gains no events.
- Forking a `:turn-aborted` wreckage head by explicit id is the supported
  recovery path for aborted work.

## Lineage Edges

Lineage edges are durable, content-addressed facts:

```clojure
{:edge/id           "sha256:..."
 :edge/version      1
 :edge/type         :invocation | :derivation
 :edge/from-session "s-..."
 :edge/to-session   "s-..."
 :edge/from-head    "sha256:..."
 :edge/to-head      "sha256:..."
 ...}
```

`rlm` and `map-rlm` children record `:invocation` edges from the parent current
head to the child current head. `attach-rlm` records a `:derivation` edge from
the selected source head to the attached child's current head.

Edges fold into the caller/parent view under `:edges`. They should be treated as
durable graph facts, not reconstructed from directory names, child session ids,
or other physical layout details.

## Compaction and Kept Messages

Compaction is a durable state transition, not a destructive rewrite.

`kept-messages` derives the transcript from the event stream:

- It scans `:events`.
- It keeps message-bearing events: `:message/appended` and
  `:session/compacted`.
- If a compaction boundary exists, it keeps messages whose event id is greater
  than or equal to `:compact-from-event-id`.

This is why the compact continuation frame survives pruning. The projection is
computed over events instead of mutating the historical `:messages` vector.

## Store-Scoped Embedder Layer (0.6)

Alongside the per-session event logs, the durable store carries ONE
store-scoped embedder layer — so applications keep a single authority instead
of a shadow database for state that is about engine facts:

- **Facts** — opaque `{:fact/tag kw :fact/value edn}` records, stamped with a
  monotonic store-scoped `:fact/id` + `:fact/at`. The engine orders, persists,
  and serves them (`facts-since`) and NEVER interprets them: no schemas, no
  queries. Application indexes are disposable projections folded from this
  stream.
- **Pins** — named durable pointers (`:pin/name` → `:pin/ref`) with
  publish-head-style CAS via `:pin/expected-version` (absent ⇒ unconditional,
  nil ⇒ must-not-exist, N ⇒ must equal). `current-head` remains the only
  ANONYMOUS mutable continuation pointer; pins are the named ones.
- Both are ref-validated at write (payload refs must resolve; a
  `{:session/id … :head/id …}` pin ref must name a published head) and
  EDN-coerced at write so a non-EDN member becomes an opaque marker instead of
  poisoning later reads. Writes take the store-scope advisory lease.

## Snapshot Reopen And The Writer Lease (0.6)

**Snapshots.** On every head publication the sqlite store writes a view
snapshot blob (events pruned to the message-bearing window kept-messages
reads) indexed by head id in a `snapshots` table — deliberately OUTSIDE head
content, so head ids stay impl-agnostic semantic identities. Reopen restores
the latest head's snapshot and folds only the tail: O(tail) instead of O(log).
The snapshot is a reopen ACCELERATOR, never a gate: writes are best-effort,
and any missing piece falls back to the full deterministic re-fold. After a
snapshot reopen the in-process view's `:events` is a bounded working window;
`events-since`/`event-stream` still serve the full durable log.

**Writer lease.** Writes are guarded by an advisory per-scope lease (scope = a
session id, or the store scope for facts/pins): a second live writer fails
typed with `:fractal/writer-lease-held`; a stale lease (heartbeat older than
`:writer-lease-ttl-ms`, default 10 minutes) is reclaimed; a writer whose lease
was taken over detects it on its next write as `:fractal/writer-lease-lost`.
Reads (`read-state`, `events-since`, reopen folds) never take leases.
`:writer-lease-steal?` is the explicit crashed-writer escape hatch.

## Failure Semantics

SQLite persist-before-fold:

- Single appends insert the event row before folding into the view cache.
- Failed inserts throw before the view changes.
- Batch appends run in one transaction; a mid-batch failure rolls back and folds
  nothing.

Head publication:

- Optional `:head/expected-basis` is checked against the folded current head.
- A stale expected basis throws a typed head CAS failure.
- The store does not silently move `current-head` over an unexpected basis.

Payload refs:

- A missing blob dereferences to nil at the BlobStore level.
- Dangling refs in a folded view violate the storage invariant.
- Payload writes can safely orphan content because no event refers to it unless
  the later event append succeeds.

Live delivery:

- Durable events remain recoverable through `events-since`.
- Transient token deltas may be dropped under load.
- A `:subscribe/gap` marker tells subscribers to recover from durable events.

Turn outcomes:

- `:final` publishes a `:turn-final` head.
- `:timeout`, `:budget-exceeded`, `:error`, and stopped-session results settle
  the turn and publish a best-effort `:turn-aborted` wreckage head (0.7) that
  never becomes a default restore basis.

## Maintainer Checklist

When changing storage, heads, compaction, resume, or recursion:

1. Keep SQLite event rows plus BlobStore payloads as the durable authority.
2. Keep `apply-event` pure and deterministic.
3. Keep `current-view` read-your-writes for runtime code.
4. Keep `current-head` the sole mutable pointer — and `current-resume-head`
   (latest non-aborted) the sole DEFAULT restore selector.
5. Keep `:vars-ref` as projection/fallback state; never write wreckage into it.
6. Keep head and edge ids content-addressed.
7. Keep attach and fork additive and source-preserving.
8. Keep filesystem paths out of logical identity.
9. Prove MemoryStore and SqliteStore still satisfy the shared port contract.
10. Prove SQLite failures do not advance folded state.

## Verification Pointers

The current storage model is exercised by:

- `src/fractal/engine/store.clj`
- `src/fractal/engine/store/memory.clj`
- `src/fractal/engine/store/sqlite.clj`
- `src/fractal/engine/store/blobstore.clj`
- `src/fractal/engine/payload.clj`
- `src/fractal/engine/payload_io.clj`
- `src/fractal/engine/session.clj`
- `src/fractal/engine/session_loop.clj`
- `src/fractal/engine/compaction.clj`
- `src/fractal/engine/recursion.clj`
- `test/fractal/engine/store_test.clj`
- `test/fractal/engine/store_contract_test.clj`
- `test/fractal/engine/store_sqlite_test.clj`
- `test/fractal/engine/blobstore_test.clj`
- `test/fractal/engine/payload_test.clj`
- `test/fractal/engine/payload_io_test.clj`
- `test/fractal/engine/session_test.clj`
- head and lineage regression tests under `test/fractal/engine/`
