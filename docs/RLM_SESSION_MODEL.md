# RLM Session Model

This is the runtime contract for recursive sessions. It is storage-aware because
session identity, head movement, invocation relationships, and restore points are
canonical facts, not files.

## Model-facing surface

The model-facing REPL surface is exactly:

```text
FINAL lm map-lm rlm map-rlm attach-rlm
```

No storage, workflow, product, or compatibility helper functions are interned into the
model namespace. Application patterns belong in vars the model defines or in host
namespaces it chooses to require.

The implementation mirrors that surface:

- `session-loop` runs one session input until a completed head boundary;
- `leaf` implements `lm` / `map-lm` as call records only;
- `session-invocation` implements `rlm` / `map-rlm` / `attach-rlm` as session edges;
- `rlm` holds pure envelopes, handles, and invocation facts;
- `process` wires configuration and installs these functions into the runtime namespace.

## Prompt and edge semantics

Every RLM session gets the same base behavior prompt. A session does not become a
different kind of machine because one caller invoked it as a child, because it later
gets resumed directly, or because another session attaches to it. The stable thing is
the session identity plus its immutable heads; "root", "child", and "attached" are
views of invocation edges.

Edge-specific instructions are therefore represented as ordinary input to the callee:

- `rlm` and `map-rlm` create or invoke child sessions with a child invocation frame plus
  the assigned task;
- `attach-rlm` with a session handle continues the callee session's current head with an
  attach invocation frame;
- `attach-rlm` with a head handle branches from the immutable source head into a new
  attached child session with an attach invocation frame;
- direct user/API turns are simply inputs to the selected session.

This keeps the Merkle model honest: behavior prompt identity is not a mutable role flag,
while each head records the actual input that produced it. A child can later be resumed
as an entry session, and an entry session can later be attached as a child, without
rewriting its prior transcript or changing what its older heads mean.

Model selection is still edge-aware. An invocation from one RLM to another may use the
configured child model for that edge, while a direct resumed session may use the root
model. That selection is call execution policy, not session identity and not a different
prompt system.

## Canonical stores

SQLite is the canonical durable hot fact/ref store:

- session ids, aliases, titles, status, kind, origin, cache ids, timestamps;
- session current-head and immutable head lineage;
- call, turn, message, eval, snapshot, and invocation identities;
- compact event records: event id, type, timestamp, session, row identity, status, and
  payload refs where an event has no typed row;
- caller/callee/source relationships;
- model/provider ids, token counts, costs, labels, statuses, and bounded previews;
- blob identity facts: hash, size, key, media type, encoding, store.

Datahike is a derived Datalog index over those facts. It can be deleted and rebuilt from
SQLite transaction batches without changing runtime restore behavior.

BlobStore is the canonical payload store:

- message content and observations;
- provider request descriptors and response bodies;
- eval code, raw values, observations, errors, and stack traces;
- final values;
- snapshots and vars;
- head state roots and compact collection deltas;
- rendered/exported bodies when those are produced.

The local implementation uses SQLite, filesystem-backed Datahike projection, and
filesystem-backed blobs. Those files are physical backends only. There is no per-session
filesystem home that acts as truth. Optional projections/exports may be generated for
debugging, but reads, restore, attach, inspect, tree, JSON output, and consistency checks
read SQLite plus BlobStore; Datalog queries use the derived Datahike index.

## Facts vs payloads

The storage boundary is semantic, not a size threshold.

Facts are stored in SQLite when the engine queries, joins, filters, counts,
identifies, relates, or uses them for state transitions. Payloads are stored as blobs
when the engine replays, restores, renders, inspects, or verifies them. Small final
values and small snapshots are still payloads and still blobbed. Arbitrary generated
conversation content is never inlined into SQLite or Datahike.

Examples:

- message fact: role, session, turn, content-ref, char count, created-at;
- message blob: full message content;
- event fact: type, session, timestamp, row kind/id, turn/call/head/invocation id;
- call fact: type, status, model, provider, request-ref, response-ref, token/cost facts;
- call blob: root request descriptor or bounded leaf request, plus raw provider response;
- head fact: basis, session, final-ref, snapshot-id, snapshot-ref, fingerprint,
  state root ref, bounded summary;
- final blob: full final value.

Write order is always blob first, SQLite transaction second:

1. Canonicalize payload bytes.
2. Hash the bytes.
3. Write the content-addressed blob.
4. Verify the blob exists and its hash matches.
5. Commit SQLite rows and a projection transaction batch pointing at the blob.
6. Lazily catch up or rebuild Datahike from the SQLite batch when a Datalog read needs it.
7. If the transaction fails, the orphan blob is acceptable and can be garbage-collected
   later. A fact pointing at a missing blob is not acceptable.

## Session, head, and current ref

A session is a stable identity. A head is an immutable value. The mutable part is the
session's current ref, `:session/current-head`, which points at one head over time.

History and runtime state are deliberately separate:

```text
history-view(session) = all facts/events ever recorded for that session
head-state(head-id)   = exact immutable RLM state visible at that head
current-view(session) = dereference session/current-head -> head-state
restore(head H)       = build the live runtime from H.state
```

A completed turn is a transition from same-session basis head `H` to new head `H'`.
The new head's `:head/basis` is the selected same-session basis head. It is nil for a
session's first local completed head. Fork and attach source heads are not head basis
edges; they are derivation/provenance edges.

A head contains:

- head id, kind, session, same-session basis head, turn id, cache id;
- state root ref for the complete active RLM state at that boundary;
- snapshot blob ref;
- final blob ref when the turn reached `FINAL`;
- bounded final summary/preview;
- fingerprint over stable head facts plus the state ref;
- invocation ids observed during the turn.

The head state root is a content-addressed EDN value that materializes to the
branch-visible transcript, turns, evals, calls, snapshots, invocation rows, refs,
counters, final ref, and restorable var rows. Current roots use
`:state/version 2`: the root is a small manifest containing scalar state plus
per-collection delta refs. Those collection payloads are append/replace overlays over
the basis head, so a new head writes only the rows changed by that head instead of
rewriting every prior row. The root is written to BlobStore before the head fact is
committed in SQLite. Initial heads commit the initial transcript state after the
system/overlay message exists.

Example root shape:

```clojure
{:state/version 2
 :state/session-id "session-..."
 :state/head-id "head-..."
 :state/basis-head "same-session-head-or-nil"
 :session {...}
 :refs {:ref/session "session-..."
        :ref/current-head "head-..."}
 :final-ref {...}
 :counters {...}
 :vars-ref {...} ; the snapshot blob containing restorable vars
 :state/collections
 {:messages {:collection/count 7 :basis/head "head-..." :delta-ref {...}}
  :turns    {:collection/count 2 :basis/head "head-..." :delta-ref {...}}
  :evals    {:collection/count 2 :basis/head "head-..." :delta-ref {...}}
  :calls    {:collection/count 5 :basis/head "head-..." :delta-ref {...}}}}
```

`session-db/read-head-state` expands that root into the same full active-state shape
readers and restore code expect. The compact representation is a storage detail, not a
second semantic model.

The mutable state transition is the canonical `:session/current-head` ref. Advancing a
session means writing a new immutable head and the session's new current-head together.
The derived Datahike index carries the same fact for queries, but SQLite is the runtime
authority. History remains enabled, so the prior current-head is still auditable.

`FINAL` does not end a session. It only returns a value to the caller/user and creates a
restorable completed head. The same session remains resumable for another turn.

## Resume

Resume resolves the same session by id or alias, resolves the selected head/turn to a
concrete head, loads that head's state root, restores EDN-safe vars into that same
session namespace, runs a new turn, creates a new head whose basis is the selected
same-session source head, and advances the same
`:session/current-head`.

Resume preserves:

- logical session id;
- cache id/scope;
- alias;
- source lineage of the same session.

Resume is the only operation here that advances an existing source session. Resuming
from an older head does not fold latest session history and overlay older vars; it starts
from the selected head state. Later sibling heads remain queryable as history.

## Fork

Fork resolves a source session/head, reads the source head state, creates a new user/API
session with a new logical id and cache id by default, runs the task there, and advances
only the new session's current-head. The fork's first completed head has no
same-session basis; the selected source session/head is recorded as a derivation edge.
The source session's current-head and fingerprint do not change.

Fork is for creating a new independent branch from prior state.

## rlm and map-rlm

`rlm` and `map-rlm` create child sessions. Each child is a normal canonical session with
its own id, cache id, heads, calls, snapshots, and final. The caller records invocation
facts linking caller session/head/call to callee session/head.

The model-visible return is an RLM envelope, not the bare child final:

```clojure
{:rlm/result true
 :rlm/status :final
 :rlm/value child-final
 :rlm/session {:session/id ... :session/cache-id ... :store/root ...}
 :rlm/head {:session/id ... :head/id ... :session/cache-id ... :store/root ...}
 :rlm/invocation {:invocation/id ... :invocation/type ...}
 :rlm/meta {:kind :child
            :label "short deterministic label"
            :task/hash "sha256:..."
            :task/preview "..."
            :value/kind :map
            :value/preview "..."
            :value/keys [...]}}
```

`:rlm/session` is the continuation handle. `:rlm/head` is the immutable
branch/provenance handle. `:rlm/meta` is deterministic recognition data; it is not a
host-generated semantic summary.

`map-rlm` preserves input order in its returned vector. Partial failures do not erase
successful child facts; successful child sessions/invocations remain queryable and failed
slots return the typed `:fractal/failed` sentinel. Successful slots hold RLM envelopes.

## lm and map-lm

`lm` and `map-lm` create call facts and payload blobs only. They do not create session
entities and do not create invocation entities. A leaf call can have request/response,
result, error, model/provider, token, and cost facts, but it is not recursive state.

Root/agent provider calls still send the fully rendered transcript to the provider at
runtime, but durable root call request refs store a compact canonical descriptor by
default: message ids, message count, cache metadata, system hash, provider, and model.
That avoids writing the full growing transcript as a new request blob on every root
step. Leaf calls keep rendered request blobs because their input/query payload is bounded
and independent.

## attach-rlm

`attach-rlm` has two deliberate modes:

- a session ref (`"alias"`, `"session-id"`, or `{:session/id ...}` without `:head/id`)
  continues that session's current head. The callee session id/cache id stay the same,
  its current-head advances, and the caller receives an RLM envelope for the new head.
- a head ref (`{:session/id ... :head/id ...}` or opts `{:head head-id}`) branches from
  the immutable head into a new attached child session. The source session/head remains
  unchanged and a derivation edge records source -> attached child provenance.

Session-ref attach is how a caller keeps using a prior child RLM across later turns.
Head-ref attach is how a caller starts a new branch from a precise historical state.

## Read surface

`show`, `tree`, JSON output, and API node reads default to the current/head view: they
dereference the selected head or the session's current head and read that immutable state
value. They do not mix abandoned branch turns/calls into the active node.

History-oriented reads (`event-stream`, inspection details, direct head/invocation
queries, and consistency checks) enumerate historical facts and compact event records.
The event stream is an audit/read surface, not a byte-for-byte copy of the rich in-memory
runtime events. Large content-bearing events point at the same typed row/payload refs as
messages, evals, calls, snapshots, and heads instead of storing a second full event body.
They remain useful mid-run because events/calls/messages/invocations are transacted as
work progresses, not only after a filesystem export is generated.

Queryable relationships include:

- outgoing invocations from a caller session;
- incoming invocations to a callee session;
- sessions derived from a source session/head;
- current head and immutable same-session head basis lineage;
- calls and leaf calls by session/head/turn.

## S3 status

The config/protocol boundary leaves room for non-local physical backends. This pass does
not implement AWS credentials, S3 blobs, or live S3 validation. The validated backend is
local file-backed Datahike plus local file-backed BlobStore.
