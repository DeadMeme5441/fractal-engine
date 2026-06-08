# 02 · State Port

The runtime loop, kernel, recursion layer, and readback seams do not talk to a
database, blob directory, or file layout directly. They talk to one port:
`fractal.engine.store/SessionStore`.

For the current Phase 1-4 implementation:

- `:memory` and `:sqlite` implement the same protocol and fold the same event
  model.
- When `:store :sqlite`, **SQLite session rows plus per-session event rows are
  the canonical durable record**.
- The **BlobStore is canonical for payload bytes** addressed by content hash.
- `current-view` is an in-memory fold/projection over durable events.
- Published immutable **heads** are the authoritative resume boundary.
- Legacy `:vars-ref` remains a projection/fallback seam, not a second mutable
  authority.

This doc describes the folded view, event taxonomy, payload model, store
protocol, and the invariants the rest of the system depends on.

---

## 1. Canonical model

The implementation now has three distinct layers:

1. **Durable event log**
   - `:memory`: ephemeral only.
   - `:sqlite`: canonical durable session/event history.
2. **Content-addressed payload store**
   - Global across sessions.
   - Canonical for large payload bytes and snapshots.
3. **Folded in-memory view**
   - Pure projection derived from the event log.
   - Used for reads, request assembly, live progress, and runtime decisions.

The important authority rules are:

- A successful `FINAL` publishes an immutable `:turn-final` head.
- Compaction publishes an immutable `:compaction` head.
- `current-head` is the authoritative restore source for resume and attach.
- Older `:session/vars-snapshotted` events remain useful as fallback/projection
  data only.
- Invocation and derivation edges are durable events folded into `:edges`.

---

## 2. Folded session view

`fractal.engine.store/empty-view` is the shape every store folds toward:

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

Notes:

- `:heads` is the immutable head history for the session.
- `:current-head` is the currently selected head id, not a second state map.
- `:edges` holds folded durable lineage edges.
- `:vars-ref` is the latest vars snapshot projection. It is still updated, but
  restore prefers `current-head`.
- `:compact-from-event-id` is the prune boundary used by `kept-messages`.
- `:events` is the ordered canonical event stream as seen by the fold.

### Session entities

Common `:session` fields:

```clojure
{:session/id          "s-..."
 :session/status      :running | :stop-requested | :stopped | :error
 :session/created-at  "..."
 :session/provider    ...
 :session/model       "..."
 :session/capability  :default | :locked-down | ...
 :session/cache-id    "..."
 :session/system-overlay nil}
```

Optional fields appear on derived sessions:

```clojure
{:session/kind           :child | :attached-child
 :session/source-session "s-..."
 :session/source-head-id "sha256:..."}
```

### Turns, steps, evals, messages

The runtime stores full turn/step entities and replace-updates them with
`.../put` events:

```clojure
;; turn
{:turn/id              1
 :turn/status          :running | :final | :error | :timeout | :budget-exceeded
 :turn/started-at      "..."
 :turn/ended-at        nil
 :turn/user-message-id 1
 :turn/final-ref       nil | <payload-ref-or-inline>
 :turn/final-preview   nil | "..."
 :turn/usage           nil | {:usage/status ...}
 :turn/cost            nil | {:cost/status ...}
 :turn/cache           nil | {:cache/status ...}
 :turn/error           nil | {:error/type ...}}

;; step
{:step/id                   1
 :step/turn-id              1
 :step/status               :running | :done
 :step/started-at           "..."
 :step/ended-at             nil
 :step/assistant-message-id nil | 3
 :step/response             nil | {:finish-reason ...
                                   :usage ...
                                   :cost ...
                                   :model ...
                                   :provider ...
                                   :cache ...}}

;; eval
{:eval/id             1
 :eval/turn-id        1
 :eval/step-id        1
 :eval/block-index    0
 :eval/code-or-ref    "..." | <payload-ref>
 :eval/status         :ok | :final | :error
 :eval/result-ref     <inline-value-or-payload-ref>
 :eval/result-preview "..."
 :eval/stdout         "..."
 :eval/stderr         "..."
 :eval/error          nil | {:error/type ...}
 :eval/elapsed-ms     12
 :eval/forms-count    3}

;; message
{:message/id             1
 :message/role           :system | :user | :assistant | :observation
 :message/turn-id        1 | nil
 :message/step-id        1 | nil
 :message/content-or-ref "..." | <payload-ref>}
```

### Heads

Published heads are immutable content-addressed records:

```clojure
{:head/id           "sha256:..."
 :head/version      1
 :head/session      "s-..."
 :head/basis        nil | "sha256:..."
 :head/event-range  [from-event-id to-event-id]
 :head/kind         :turn-final | :compaction
 :head/turn-id      1 | nil
 :head/vars-ref     <payload-ref-or-inline>
 :head/final-ref    <payload-ref-or-inline> | nil
 :head/compact-from-event-id nil | 42}
```

`publish-head!` computes `:head/id`, `:head/basis`, and `:head/event-range`
from the folded view under the store lock. A stale expected basis throws
`:fractal/head-cas-failed`; the store never silently overwrites `current-head`.

### Lineage edges

Edges are also immutable content-addressed values:

```clojure
{:edge/id            "sha256:..."
 :edge/version       1
 :edge/type          :invocation | :derivation
 :edge/from-session  "s-..."
 :edge/to-session    "s-..."
 :edge/from-head     "sha256:..."
 :edge/to-head       "sha256:..."
 ...}
```

`rlm` and `map-rlm` append invocation edges. `attach-rlm` appends derivation
edges. The fold deduplicates by `:edge/id`.

---

## 3. Event taxonomy and fold rules

Every state change is represented as an appended event. Creating events mint
entity ids. `.../put` events replace by id inside the folded vectors.

| Event type | Payload | Fold effect |
|------------|---------|-------------|
| `:session/started` | `:session` | `assoc :session` |
| `:turn/started` | `:turn` | `conj :turns` |
| `:turn/put` | `:turn` | replace matching `:turn/id` |
| `:step/started` | `:step` | `conj :steps` |
| `:step/put` | `:step` | replace matching `:step/id` |
| `:message/appended` | `:message` | `conj :messages` |
| `:eval/added` | `:eval` | `conj :evals` |
| `:session/vars-snapshotted` | `:vars-ref` | `assoc :vars-ref` |
| `:session/compacted` | `:vars-ref`, `:compact-from-event-id`, `:message` | update vars/boundary and append compact message |
| `:head/published` | `:head` | append to `:heads`, set `:current-head` |
| `:lineage/edge-added` | `:edge` | append to `:edges` |
| `:session/stop-requested` | none | set status `:stop-requested` |
| `:session/stopped` | none | set status `:stopped` |
| `:session/error` | `:error` | set `:error`, set status `:error` |

Additional rules:

- Every stamped event is also appended to `:events`.
- `:counters` track the max assigned ids for events, messages, turns, steps, and
  evals.
- `:session/compacted` is intentionally one durable event, not a multi-event
  rewrite.
- Head publication is a separate event after the boundary event it pins.

### `kept-messages`

`fractal.engine.store/kept-messages` is the canonical compaction-aware message
projection shared by request assembly and compaction.

It scans `:events`, not `:messages`, and keeps the `:message` from each
`:message/appended` or `:session/compacted` event whose `:event/id` is greater
than or equal to `:compact-from-event-id`. This is why the compact frame itself
survives pruning.

---

## 4. Payload refs and the blob seam

Large payloads are not duplicated inline in the event log. They are
content-addressed into the BlobStore and referenced by an opaque tagged map:

```clojure
{:fractal/ref :payload
 :payload/id   "sha256:<hex>"
 :payload/kind :message | :eval-result | :final | :vars | :request | :code
 :payload/size 1234}
```

Implementation rules:

- `fractal.engine.payload/canonical-bytes` is the hashing basis.
- `fractal.engine.payload-io/maybe-intern` inlines EDN-safe values whose
  canonical bytes are `<= 512`, otherwise writes them to the blob store first.
- `read-payload` passes non-ref values through unchanged and dereferences tagged
  refs.
- The loop, request builder, and public readback surface use `read-payload` or
  `hydrate-message`; they do not branch on raw ref structure.

The storage invariant is:

- orphan blobs are acceptable
- dangling refs are not

`verify-no-dangling-refs` exists specifically to prove that invariant.

---

## 5. The `SessionStore` protocol

The public contract in `src/fractal/engine/store.clj` is:

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

Required semantics:

- `create-session!`
  - idempotent and atomic
  - returns a handle pointing at stable `:sci-ctx` and `:busy` atoms
  - for SQLite, reopens an existing durable session by folding its event log
- `append-event!`
  - serializes on the per-session store lock
  - stamps ids and time
  - persists before fold for SQLite
  - schedules live delivery after the fold
- `append-events!`
  - stamps a consecutive batch
  - persists and folds atomically
- `publish-head!`
  - validates optional `:head/expected-basis`
  - computes basis, range, and id under the same store lock
  - appends a `:head/published` event and moves `current-head`
- `append-lineage-edge!`
  - content-addresses the edge and appends `:lineage/edge-added`
- `current-view`
  - strong read-your-writes view
  - used by runtime and public API
- `read-state`
  - relaxed projection hook for external readers
  - current implementation returns the same folded cache
- `peek-next-id`
  - valid only on the single writer thread
  - used for `open-turn!` and kernel eval id prediction
- `notify-transient`, `subscribe!`, `events-since`
  - drive the live query seam described in `09`

### Session handle

`create-session!` returns a runtime handle, not a raw slot map:

```clojure
{:store      <SessionStore>
 :session-id "s-..."
 :sci-ctx    <atom>
 :busy       <atom>}
```

`start-session!`, `resume-session!`, `spawn-child!`, and `spawn-attached!`
extend that base handle with runtime config, adapters, and capability data.

---

## 6. Resume, heads, and attach semantics

### Turn completion

On a successful `FINAL`:

1. the loop snapshots vars and appends `:session/vars-snapshotted`
2. it appends the final `:turn/put`
3. it publishes a `:turn-final` head

The published head becomes `current-head`.

### Compaction

Compaction:

1. summarizes the kept transcript
2. appends one `:session/compacted` event
3. publishes a `:compaction` head

That compaction head becomes the new `current-head`. Compaction therefore
creates a durable restore boundary, not just a transcript rewrite.

### Resume

`resume-session!` reopens a durable session by:

1. rebuilding the folded view from the durable log
2. building a fresh SCI context
3. restoring vars from `(:head/vars-ref (current-head view))` when available
4. falling back to top-level `:vars-ref` only when no current head exists

This is deliberate. `current-head` is the authoritative resume source; stale
top-level vars snapshots do not win over it.

### `attach-rlm`

`attach-rlm` does **not** continue the source session in place.

It resolves a source session/head, then:

1. selects an immutable source head
   - session handle or session id string: use that session's `current-head`
   - head handle or explicit `:head/id`: use that selected immutable head
2. creates a **fresh attached child**
3. restores that child from the source head's vars snapshot
4. runs the task in the new child
5. records a durable derivation edge

The source session is not advanced, and the source head does not move.

---

## 7. Store-specific notes

### MemoryStore

- single-process only
- same event model and head model as SQLite
- global in-memory blob map
- `current-view` and `read-state` both read the folded atom

### SqliteStore

- event rows are durable source of truth
- blobs live in a sharded on-disk BlobStore
- `create-session!` replays the durable log into a fresh folded cache
- append and head publication are persist-before-fold
- multiple store instances may reopen the same durable directory

---

## 8. Invariants

The current implementation depends on these invariants:

1. Store-assigned ids are monotonic per session.
2. SQLite persists before fold; a failed durable write does not advance the
   folded view.
3. Heads and edges are immutable, content-addressed, and additive.
4. `current-head` is the only mutable restore pointer.
5. `:vars-ref` is projection/fallback state, not competing authority.
6. Durable invocation and derivation relations live in `:edges`.
7. Request pruning is derived from event ids, not from mutating `:messages`.
8. Large values keep content identity in the blob seam.

If a future change breaks any of those rules, it must update the runtime,
resume, compaction, recursion, and live-query specs together.
