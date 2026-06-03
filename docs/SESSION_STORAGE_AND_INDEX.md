# Session Storage

This document replaces both older intermediate models: per-session filesystem homes and
Datahike-as-the-hot-store. There is no derived filesystem source of truth for sessions.

## Final model

```text
SQLite   = canonical hot facts, identity, refs, counters, and transaction batches
BlobStore = canonical bytes and payloads
Datahike = derived Datalog query index
Filesystem = physical backend for the local SQLite, Datahike, and BlobStore implementations
```

SQLite stores facts the engine queries or uses for transitions. BlobStore stores payload
bytes the engine replays, restores, renders, inspects, or verifies. Datahike indexes the
SQLite facts for graph/Datalog reads and can be deleted/rebuilt.

Session homes are not part of the domain model. If a future command emits a file tree for
debugging or export, that tree is a projection of SQLite + BlobStore and must not be
required for reads or restore.

## Store roots

A store root is a physical directory containing:

```text
<root>/
  store.sqlite    # canonical SQLite hot store
  blobs/          # local content-addressed BlobStore backend
  datahike/       # optional derived Datahike backend, rebuilt on demand
  datahike-id.edn # local derived-index identity
```

The exact physical layout is an implementation detail. Public callers should carry
session locators:

```clojure
{:store/root ".fractal"
 :session/id "session-..."
 :head/id "head-..."} ; optional
```

The locator names canonical SQLite identity. It does not name a session directory.

## SQLite rows and Datahike facts

SQLite stores typed rows for operational reads and append-only transaction batches for
the derived Datahike index. Datahike uses stable unique identity for domain ids and refs
for relationships where joins matter. The derived index uses `:keep-history? false`
because Merkle heads and SQLite rows are the durable history.

Representative canonical rows/facts:

```clojure
:session/id
:session/cache-id
:session/title
:session/status
:session/kind
:session/origin
:session/created-at
:session/updated-at
:session/current-head

:alias/name
:alias/session

:event/id
:event/session
:event/type
:event/at
:event/row-kind
:event/row-id
:event/turn-id
:event/message-id
:event/eval-id
:event/call-id
:event/snapshot-id
:event/head-id
:event/invocation-id
:event/status
:event/source-session-id
:event/source-head-id
:event/payload-ref

:head/id
:head/session
:head/basis
:head/status
:head/fingerprint
:head/state-ref
:head/state-version
:head/final-ref
:head/final-summary
:head/snapshot-id
:head/snapshot-ref
:head/created-at
:head/turn-id
:head/cache-id

:derivation/id
:derivation/type
:derivation/source-session
:derivation/source-head
:derivation/target-session
:derivation/created-at

:message/id
:message/session
:message/turn-id
:message/role
:message/content-ref
:message/char-count
:message/created-at

:eval/id
:eval/session
:eval/turn-id
:eval/message-id
:eval/status
:eval/code-ref
:eval/result-ref
:eval/final-ref
:eval/error-ref

:invocation/id
:invocation/type
:invocation/status
:invocation/caller-session
:invocation/caller-head-before
:invocation/caller-head-after
:invocation/callee-session
:invocation/callee-head-before
:invocation/callee-head-after
:invocation/call-id
:invocation/label
:invocation/created-at
:invocation/completed-at
:attach/source-session
:attach/source-head
:attach/source-snapshot-ref
:attach/source-fingerprint

:call/id
:call/session
:call/head
:call/turn-id
:call/type
:call/status
:call/request-ref
:call/response-ref
:call/result-ref
:call/error-ref
:call/model
:call/provider
:call/input-tokens
:call/output-tokens
:call/cost-usd
:call/created-at
:call/completed-at

:blob/id
:blob/sha256
:blob/size
:blob/media-type
:blob/encoding
:blob/store
:blob/key
:blob/created-at
```

Small scalar metadata stays queryable. Generated or arbitrary content does not move into
SQLite/Datahike just because it is small.

`:session/kind` is an origin/read-surface tag. It helps render whether a session was
born as an entry session, spawned child, or attached child, but it is not the behavior
prompt and it is not the session's permanent semantic role. Runtime role is read from the
invocation edge and the input frame that produced a head transition.

## Blob refs

Blob refs are content-addressed:

```clojure
{:blob/id "sha256:<hash>"
 :blob/sha256 "<hash>"
 :blob/size 12345
 :blob/media-type "application/edn"
 :blob/encoding :utf-8
 :blob/compression nil
 :blob/store :file
 :blob/key "sha256/ab/cd/<hash>.edn"}
```

The local BlobStore supports:

- `put-bytes!` / `put-edn!`;
- `read-bytes` / `read-edn`;
- `exists?`;
- hash verification.

Optional orphan listing or GC can be added later. It is not required for correctness
because orphan blobs are allowed when a SQLite transaction fails after the blob write.

## Semantic boundary

SQLite row / Datahike index fact examples:

- ids, refs, statuses, timestamps, kinds/types, labels, aliases;
- model/provider ids;
- token counts, cost, cache ids;
- head basis/current-head;
- derivation and invocation caller/callee/source relationships;
- blob hash, size, key, media type;
- bounded display summaries.

Blob payload examples:

- message content;
- prompts/system prompts;
- root provider request descriptors, bounded leaf request bodies, and provider responses;
- leaf inputs/outputs;
- eval code and raw eval values;
- observations;
- final values;
- snapshots and vars;
- complete head state roots and collection component payloads;
- raw errors, ex-data, and stack traces;
- rendered markdown/export bodies.

Event payload blobs are not the normal event store. Persisted event records are compact
SQLite rows with append order, row identity, status, source ids, and payload refs. A
successful `:message/added` event points at the message row/content ref; `:eval/added`
points at eval code/result/error refs; `:call/started` and `:call/put` point at call
request/response/result/error refs; `:snapshot/added` points at the snapshot ref;
`:head/created` points at head facts and the head state/final/snapshot refs. The same
compact records are available to Datahike after index catch-up. Only exceptional
annotation events may carry a small `:event/payload-ref` containing compact metadata.

The read surface has two levels:

- `event-stream`: raw compact canonical event rows in append order;
- `event-trace`: a derived audit trace over those rows plus typed row metadata. Trace
  rows add `:trace/summary`, `:trace/row`, `:trace/causes`, and
  `:trace/cause-event-ids` so an operator can ask "what caused this head/ref/call?"
  without resolving raw payload blobs.

The trace is an audit/provenance view. It is not a replay mechanism and it is not used
to restore the REPL. Restore still reads `session/current-head -> head/state-ref`.

Removed behavior:

- no `4096` byte inline threshold as a primary storage rule;
- no "path supplied means maybe blob" rule;
- no inline `:message/content`, `:eval/code`, root `:request/messages`,
  `:response/body`, `:final/value`, `:snapshot/vars`, or `:var/value` facts;
- no generic full `{:event ev}` blob for every event;
- no session-home-relative blob refs.

## Write protocol

Every payload write follows the same discipline:

1. Canonicalize payload bytes.
2. Hash bytes.
3. Write the content-addressed blob.
4. Verify blob existence and hash match.
5. Commit SQLite rows plus a projection transaction batch referencing the blob.
6. Treat a failed transaction as an orphan-blob case, not a dangling-fact case.

The runtime never commits a row/fact pointing to a blob that has not first been written and
verified.

## Head state and runtime events

Runtime progress is visible mid-run because session, message, eval, call, invocation, and
snapshot rows are committed as work happens. There is no required `events.ednl` file.
The persisted event stream is compact: it records append order, type, session, timestamp,
row/entity identity, status, source head/session ids, and payload refs. It does not
duplicate full message text, eval code/result, call request/response bodies, snapshots,
or head states already stored under typed payload refs.

A completed head also points at a complete immutable active-state root. The read/restore
contract is still:

```text
read head/state-ref -> materialize exact active state at that head
```

Current roots use `:state/version 2`: a compact manifest plus per-collection component
refs. The root stores scalar state and refs; each collection stores only the rows appended
or replaced relative to the basis head.

```clojure
{:state/version 2
 :state/session-id "session-..."
 :state/head-id "head-..."
 :state/basis-head "same-session-head-or-nil"
 :session {...}
 :refs {:ref/session "session-..."
        :ref/current-head "head-..."}
 :final-ref {...}
 :vars-ref {...}
 :counters {...}
 :state/collections
 {:messages    {:collection/count 7 :basis/head "head-..." :delta-ref {...}}
  :turns       {:collection/count 2 :basis/head "head-..." :delta-ref {...}}
  :evals       {:collection/count 2 :basis/head "head-..." :delta-ref {...}}
  :calls       {:collection/count 5 :basis/head "head-..." :delta-ref {...}}
  :snapshots   {:collection/count 2 :basis/head "head-..." :delta-ref {...}}
  :heads       {:collection/count 3 :basis/head "head-..." :delta-ref {...}}
  :invocations {:collection/count 1 :basis/head "head-..." :delta-ref {...}}}}
```

Each `delta-ref` payload is an EDN value:

```clojure
{:state-collection/version 1
 :state/head-id "head-..."
 :state/basis-head "same-session-head-or-nil"
 :collection/key :messages
 :append [...]
 :replace [...]
 :remove [...]}
```

The state root is the SQLite-referenced blob. Nested component refs live inside that root
and are verified by the consistency checker by walking the root. They are not promoted to
separate SQLite/Datahike graph facts because they are not queried independently.

That materialized state is runtime truth for restore and current reads. The event/fact
history is audit truth. Resuming from head `H` in the same session uses `H` as the new
head basis. Forking or attaching from head `H` reads `H`'s state into a new session and
records a derivation edge from the source session/head to the target session; the new
session's first local head has no same-session basis. All restore paths use high-water
counters for the target session so new message/turn/eval/call ids do not collide with
historical facts.

Root provider calls store compact request descriptors by default:

```clojure
{:request/version 2
 :request/kind :root-agent
 :request/rendered? false
 :request/message-ids [1 2 3]
 :request/message-count 3
 :request/system-hash "sha256:..."
 :request/cache {...}
 :request/provider :scripted
 :request/model "scripted-root"}
```

The provider still receives the rendered request at runtime. The durable root call ref
keeps enough provenance to identify the transcript slice without duplicating the full
growing transcript. Leaf calls keep rendered request blobs because their inputs are
bounded and independent.

`lm` and `map-lm` create call facts/payloads only. `rlm` and `map-rlm` create invocation
facts and child sessions, and return RLM envelopes with `:rlm/value`, `:rlm/session`,
`:rlm/head`, and deterministic `:rlm/meta`. `attach-rlm` creates invocation facts and
either continues a session ref or branches from a head ref. `FINAL` creates
final/snapshot payloads and an immutable head with `:head/state-ref`; it does not end
the session.

## Consistency checker

`fractal-engine.store.consistency/check-consistency` defaults to deep mode:

```clojure
{:check/kind :session-db/consistency
 :check/mode :deep
 :status :ok       ;; or :issues
 :counts {...}
 :issue-count 0
 :issues []}
```

Use `(check-consistency root {:mode :quick})` for structural checks that avoid blob hash
verification and compact head-state materialization. Quick mode is useful in semantic
tests and polling-style checks; deep mode is the storage-integrity check.

Quick mode verifies:

- every non-nil session current-head exists;
- every head's session exists;
- every head basis exists or is nil/genesis;
- every derivation source/target session and source head exists;
- every invocation caller/callee session exists;
- every invocation caller/callee/source head ref exists;
- every attach source session/head exists;
- every alias points to an existing session;
- cache ids are present;
- leaf calls do not create invocations or sessions;
- attach-derived sessions do not mutate their source refs.

Deep mode adds:

- every blob ref exists and hash-verifies;
- every head state/final/snapshot blob ref exists and hash-verifies;
- every compact head-state component ref exists and hash-verifies;
- head state session id, basis head, final ref, and snapshot ref agree with head facts;
- every current-head points at a head with a readable state root.

## Backend status

Only local SQLite, local filesystem-backed Datahike projection, and local
filesystem-backed blobs are implemented in this pass. The protocol/config shape leaves
room for S3 or another backend later, but there is no AWS credential handling, S3
BlobStore implementation, or live S3 validation here.

Known remaining local-backend costs: CLI commands still pay normal cold JVM startup, and
Datalog reads pay index catch-up/rebuild work when the derived Datahike projection is
stale or absent. The runtime hot path does not transact into Datahike.
