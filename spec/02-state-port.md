# 02 · State Port

The state layer is the spine of the engine. The loop never touches a database, a blob
store, or a file — it talks to a **`SessionStore` port**. Phase 1 ships an in-memory
impl; Phase 2 slots SQLite + content-addressed blobs under the *same* protocol with
**zero loop changes**. This doc defines the state value, the event taxonomy, the
payload abstraction, the protocol, the pure fold, and the invariants.

> This design was adversarially reviewed. Several "obvious" shortcuts are wrong and
> are called out inline (⛔). Do not re-introduce them.

---

## 1. The session view (state value)

The session view is a plain Clojure map — the fold of the event log. It is held in an
atom per session inside `MemoryStore`. `fractal.engine.store/empty-view`:

```clojure
{:session   nil   ; the session map, set by :session/started (see §2)
 :messages  []    ; vector of message maps, in append order
 :turns     []    ; vector of turn maps
 :steps     []    ; vector of step maps
 :evals     []    ; vector of eval maps
 :counters  {:event 0 :message 0 :turn 0 :step 0 :eval 0}
 :vars-ref  nil   ; payload-ref to the latest REPL-var snapshot (the head seam)
 :compact-from-event-id nil ; an :event/id boundary; request-assembly keeps every message whose owning message-bearing event (:message/appended | :session/compacted) has :event/id >= this — scanned off :events (compaction)
 :error     nil   ; session-fatal error, if any
 :events    []}   ; full ordered event log (in-memory cache; durable copy lives in the store)
```

### Entities

```clojure
;; session — set once by :session/started, status-mutated by stop/compact
{:session/id          "s-<uuid>"
 :session/status      :running        ; :running | :stop-requested | :stopped | :error
 :session/created-at  "<iso>"
 :session/provider    :anthropic      ; resolved from the model id (or the fake adapter)
 :session/model       "claude-..."    ; the concrete model id
 :session/capability  :default        ; the capability profile NAME (the value lives in cfg; see 04)
 :session/cache-id    "<logical-id>"  ; prompt-cache scope key (08); defaults to the logical session id
 :session/system-overlay nil}         ; extra session-level system text (set from start-session! opts; 05/06)

;; turn — one user-message → FINAL
{:turn/id            1
 :turn/status        :running         ; :running | :final | :error | :timeout | :budget-exceeded
 :turn/started-at    "<iso>"
 :turn/ended-at      nil
 :turn/user-message-id 3
 :turn/final-ref     nil              ; payload-ref to the FINAL value (set on :final)
 :turn/final-preview nil              ; a small fit-or-stub preview (inline, for cheap reads)
 :turn/usage         nil              ; honest usage (see 08), summed over steps, :unknown-aware
 :turn/cost          nil              ; honest cost (08), summed :unknown-aware
 :turn/cache         nil              ; honest cache rollup (08), summed :unknown-aware:
                                      ;   {:cache/status :hit|:miss|:unknown
                                      ;    :cache/cached-tokens <int>|:unknown
                                      ;    :cache/cache-write-tokens <int>|:unknown}
 :turn/error         nil}             ; the namespaced error map (see "The error map") on :status :error
;; ⛔ No :turn/step-ids / :turn/eval-ids: a turn's steps/evals are DERIVED by filtering
;;    :steps/:evals on :step/turn-id / :eval/turn-id. The …/put events carry no id lists.

;; step — one adapter-call iteration
{:step/id            1
 :step/turn-id       1
 :step/status        :running         ; :running | :done | :error
 :step/started-at    "<iso>"
 :step/ended-at      nil
 :step/request-ref   nil              ; payload-ref to the interned request (audit; optional in P1)
 :step/response      nil              ; the call record sans text: {:finish-reason :usage :cost
                                      ;   :model :provider :cache} — bare :cache key (05/08), small, inline
 :step/assistant-message-id nil}
;; ⛔ No :step/eval-ids: a step's evals are DERIVED by filtering :evals on :eval/step-id.

;; eval — one fenced Clojure block (the PERSISTED entity, carried by :eval/added)
{:eval/id            1
 :eval/turn-id       1
 :eval/step-id       1
 :eval/block-index   0
 :eval/code-or-ref   "..."            ; inline if small, else payload-ref (maybe-intern kind :code)
 :eval/status        :ok              ; :ok | :final | :error  (:final is the SOLE FINAL marker)
 :eval/result-ref    nil              ; payload-ref to the EDN-safe snapshot of the return value (audit/resume)
 :eval/result-preview nil            ; the fit-or-stub preview (inline)
 :eval/stdout        "..."            ; capped (see 03), inline
 :eval/stderr        "..."
 :eval/error         nil              ; the namespaced error map (see "The error map") when :status :error
 :eval/elapsed-ms    12
 :eval/forms-count   3}

;; message — flat conversation entry
{:message/id         3
 :message/role       :user            ; :system | :user | :assistant | :observation
 :message/turn-id    1
 :message/step-id    nil              ; set for :assistant / :observation
 :message/content-or-ref "..."}       ; inline if small, else payload-ref (kind :message);
                                      ;   payload-io/hydrate-message RENAMES it to :message/content for consumers
```

> **Rec-only transient fields (NOT persisted).** The in-memory eval record that
> `kernel/eval-batch` returns additionally carries `:eval/raw-value` (the live JVM/SCI
> return value) and, on FINAL, `:eval/raw-final` — read by `observe/render-observation`
> and `commit-turn!` (03/07). Before appending `:eval/added` the kernel interns that raw
> value → `:eval/result-ref` (via `payload-io/maybe-intern`, kind `:eval-result`) and
> computes `:eval/result-preview` (via `observe`), then appends the **stripped** entity
> above (no raw fields). The raw value is never stored — it lives only in the SCI ctx and
> the in-flight record. Likewise the FINAL value is interned (`:turn/final-ref`) and
> hydrated for the caller by `run-turn!` (06).

### The error map

Every engine error is the same **namespaced** map — carried by `:eval/error`,
`:turn/error`, `:session/error`, the `TurnResult` `:error` (06), and every engine
`ex-info`'s `:data`. `kernel/err->map` (03) emits it.

```clojure
{:error/type    :fractal/max-steps   ; a namespaced kw — e.g. :fractal/turn-in-flight,
                                      ;   :fractal/session-turn-limit, :fractal/max-steps,
                                      ;   :fractal/deadline, :fractal/session-stopped, :provider/failed
 :error/message "…human-readable…"
 :error/data    {…}}                  ; optional extra context
```

---

## 2. The event taxonomy

Every state change is one appended event. **Events carry results, never recipes:**
`:eval/added` carries the eval's result/error, never "please run this code". Folding
the log reproduces the view. Mutations to mutable entities are `…/put` events that
carry the **full updated entity**; the fold replaces by id (append-only log, mutable
view).

| `:event/type` | Carries | Fold effect |
|---------------|---------|-------------|
| `:session/started` | `:session` (the session map) | `assoc :session` |
| `:turn/started` | `:turn` (running turn) | `conj :turns` |
| `:turn/put` | `:turn` (full updated turn) | replace-by `:turn/id` in `:turns` |
| `:step/started` | `:step` (running step) | `conj :steps` |
| `:step/put` | `:step` (full updated step) | replace-by `:step/id` in `:steps` |
| `:message/appended` | `:message` | `conj :messages` |
| `:eval/added` | `:eval` | `conj :evals` |
| `:session/vars-snapshotted` | `:vars-ref` | `assoc :vars-ref` |
| `:session/compacted` | `:vars-ref`, `:message` (the stamped compact msg), `:compact-from-event-id` (the prune boundary — this compact frame's own `:event/id`; request-assembly keeps every message whose owning event id `>=` it, so the compact frame and all after it survive) | `assoc :vars-ref :compact-from-event-id` + `conj :messages` (ONE event — see ⛔ below) |
| `:session/stop-requested` | — | set `:session/status :stop-requested` |
| `:session/stopped` | — | set `:session/status :stopped` |
| `:session/error` | `:error` (the namespaced error map — see "The error map") | `assoc :error`, set `:session/status :error` |

Every event also: `conj`'d to `:events`; `:counters` updated to the max of the entity
id and event id (defensive — the store *assigns* ids, the fold only maxes).

⛔ **Compaction is ONE event, not two.** Appending `:session/vars-snapshotted` then
`:session/compacted` separately opens a torn-write window (vars updated, messages not
yet collapsed) under persist-before-fold. Carry the snapshot ref *inside*
`:session/compacted`.

Every event, once stamped, looks like:
`{:event/id 42 :event/at "<iso>" :event/type :eval/added :eval {…}}`.

> **Emission (cross-ref 07).** `run-step!` appends `:step/started` **first** each step —
> *before* the adapter call — so a live observer sees the step in flight; the store assigns
> `:step/id` and sets `:step/status :running` / `:step/started-at` / `:step/turn-id`. The
> step is finalized with `:step/put` after the call. `commit-turn!` appends
> `:session/vars-snapshotted` (the content-addressed `:vars-ref`) **just before** the final
> `:turn/put`. Compaction folds its snapshot into the single `:session/compacted` event
> (above) — never a separate `:session/vars-snapshotted`.

> **The taxonomy is exhaustive for the DURABLE log only.** Transient live signals —
> `:delta/token` (streaming, 05/09) and `:subscribe/gap` (09) — and the
> `:subscribe/reentrant` throw (09) carry **no `:event/id`**, are **never persisted**, and
> are **never folded**. Only the rows above touch the view.

---

## 3. Payloads and refs — content-addressed (Merkle substrate)

Large values (message content, eval results, FINAL values, var snapshots, the interned
request) are not inlined into events — they are **interned** into a
**content-addressed blob store** and referenced by an **opaque tagged ref**. This is
the Merkle substrate (see §9): a ref *is* a Merkle node id.

```clojure
;; The ref is opaque to the loop and UNIFORM across all store impls. The id is a
;; content hash — same value ⇒ same ref ⇒ dedup ⇒ Merkle identity. No uuids.
{:fractal/ref :payload
 :payload/id   "sha256:<hex>"   ; = (sha256 (canonical-bytes value)) — the content hash
 :payload/kind :final           ; :message | :eval-result | :final | :vars | :request | :code
 :payload/size 1234}            ; bytes of the canonical encoding (optional, cheap)
```

`canonical-bytes` (in `fractal.engine.payload`) is the hashing basis and MUST be
deterministic: canonical EDN — sorted map keys, `*print-length*`/`*print-level*` nil,
no metadata, no namespaced-map shorthand. The same logical value always hashes the
same way, across processes and store impls. (⚠ volatile fields like timestamps are
**not** part of a blob's content — a blob is just the value, so equal values dedup.)

**Two namespaces (kept acyclic).** `fractal.engine.payload` is **pure** (zero engine
deps, built step 1): `canonical-bytes`, `sha256-hex`, `payload-ref?` (the tag predicate),
and the tagged-ref constructor. `fractal.engine.payload-io` is **store-coupled** (built
*after* `store`): it wraps the store's `intern-payload!`/`read-payload*` with the
inline/hydrate policy below. `store/verify-no-dangling-refs` uses the **pure**
`payload-ref?`, so `store` depends only on pure `payload`, never on `payload-io`.

Rules (`fractal.engine.payload-io`):

- `(maybe-intern store value opts)` → returns `value` **inline** (unchanged) if it is a
  scalar / string ≤512 chars / number / keyword / boolean / nil / a *small collection*
  whose `canonical-bytes` are ≤512; otherwise **interns** it (content-addressed) and
  returns a tagged ref, EDN-coercing any unrestorable members first. `opts` carries
  `:payload/kind`.
- `(read-payload store ref-or-value)` → if the arg is a tagged ref (`payload-ref?`),
  dereference it (global content lookup); otherwise return it unchanged. **This is the
  only way the loop reads any `…-or-ref` / `…-ref` / possibly-ref field** (incl.
  `:message/content-or-ref`). ⛔ The loop must never compare, log, or branch on a raw ref.
- `read-payload` is **PUBLIC**: `fractal.engine.api` exposes `(read-payload handle
  ref-or-value)`, delegating to `payload-io/read-payload` on the **handle's** store (06).
  The read/live surface returns refs everywhere, so callers need the hydrator.

**Events are per-session; the blob store is GLOBAL** (content-addressed, shared across
all sessions in a store — the shared Merkle substrate, exactly v1's per-session SQLite
facts + a single BlobStore). So `intern-payload!`/`read-payload*` take **no `sid`** —
the content hash is globally sufficient.

⛔ **The system is "event log + content-addressed blob store", not "event log alone".**
`fold(events)` reproduces the view *structure*; it does not reproduce payload
*contents*. Store obligation (mirrors v1): **a blob write precedes the event that
references it; orphan blobs are fine, dangling refs are forbidden.** Provide
`verify-no-dangling-refs` for tests.

---

## 4. The `SessionStore` protocol

```clojure
(defprotocol SessionStore
  (create-session! [store session-map]
    "Idempotent + atomic. Insert a fresh per-session slot (view-atom + lock + live
     dispatch + :sci-ctx/:busy atoms, §7) keyed by :session/id IF ABSENT; a second call
     returns the existing handle, never nuking state. Returns a SessionHandle (see §5).")

  (append-event! [store sid event]
    "THE write op. Under the session's per-session STORE LOCK: stamp :event/id + :event/at
     (always); assign entity ids per `stamp-ids+ts` (§7) — CREATING events (:turn/started,
     :step/started, :message/appended, :eval/added) mint a FRESH entity id from the matching
     counter; …/put events KEEP their entity id; :session/compacted mints its embedded
     compact-message id from the message counter — then PERSIST, then (on success) fold via
     apply-event into the view cache, then schedule a NON-BLOCKING live notification (out of
     the lock — see 09). RETURNS the stamped event (the loop reads assigned ids off it).
     Never invoked from inside a subscriber callback (throws :subscribe/reentrant — see 09).")

  (append-events! [store sid events]
    "Batch variant: assign consecutive ids + persist + fold for all events in ONE
     critical section. Reserved from day one for Phase-2 commit performance; the
     MemoryStore may delegate to append-event! in a loop. Used where several events
     are produced atomically.")

  (intern-payload! [store value opts]
    "Content-address a large value into the GLOBAL blob store: id = (sha256
     (canonical-bytes value)). Return a tagged opaque payload-ref. IDEMPOTENT —
     identical values return the identical ref (dedup / Merkle identity). Writes the
     blob BEFORE the referencing event is appended. No sid: blobs are global Merkle
     nodes shared across sessions.")

  (read-payload* [store ref]
    "Dereference a tagged ref to its value via global content lookup.
     (fractal.engine.payload-io/read-payload wraps this to pass non-ref values through.)")

  (current-view [store sid]
    "STRONG read-your-writes snapshot of the folded view — reflects every append that has
     already returned. ⛔ MUST NOT delegate to read-state (which is relaxed). The loop and
     api read state ONLY through this; external/cross-process tooling uses read-state.
     MemoryStore: @(:view (@sessions sid)). Non-blocking against writes (§8).")

  (read-state [store sid]
    "RELAXED snapshot of the folded view for external/cross-process consumers.
     Non-blocking; need not reflect an in-flight append synchronously.")

  (peek-next-id [store sid counter-key]
    "Return the id append-event! WILL assign next for counter-key (e.g. :eval, :turn).
     VALID ONLY on the single writer thread — for an id needed BEFORE its creating event
     exists: the kernel sets *current-eval-id* from (peek-next-id … :eval) during eval;
     open-turn! sets the user message's :message/turn-id from (peek-next-id … :turn) (07).")

  (notify-transient [store sid item]
    "Publish a TRANSIENT live signal — e.g. {:event/type :delta/token :text … :step/id …}.
     No :event/id; NEVER persisted, NEVER folded; delivered through the SAME per-session
     live dispatch as durable events (drop-transient + gap on overflow). store.memory
     delegates to live. (09)")

  (subscribe!   [store sid callback] "→ unsubscribe fn. store.memory delegates to live. See 09.")
  (events-since [store sid event-id] "→ ordered events with :event/id > event-id. store.memory delegates to live. See 09."))
```

### 5. The SessionHandle

`create-session!` returns a **handle**, never the raw atom — so loop code is identical
across MemoryStore and the future SQLiteStore:

```clojure
{:store      <the SessionStore>
 :session-id "s-<uuid>"
 :cfg        <the normalized engine config>          ; read by run-turn!/run-loop!/run-step! (05/07; GD5)
 :adapter    <the constructed LlmAdapter>            ; the SOLE adapter instance (built in start-session!, 05)
 :sci-ctx    <atom, the session's SCI ctx>           ; the live REPL (03); the slot atom, set after new-ctx
 :busy       <atom false>}                           ; the turn-lock (07); the slot atom
```

- `current-view` is a **store protocol method** (§4), not a handle field:
  `(current-view (:store handle) (:session-id handle))` → the **STRONG** read-your-writes
  view. MemoryStore: `@(:view (@sessions sid))`. SQLiteStore (P2): a write-synchronous
  in-process cache atom updated inside `append-event!`. ⛔ It MUST NOT delegate to
  `read-state` (relaxed). The loop and api read state ONLY through the port method; only
  external tooling uses `read-state`.
- `:cfg` / `:adapter` are stashed by `start-session!` — the sole composition root that
  builds the adapter (05/07; GD5). `:sci-ctx` / `:busy` are the slot's atoms (§7), shared
  by reference so the handle and the slot see the same REPL / turn-lock.

---

## 6. The pure fold

`fractal.engine.store/apply-event` is `(view event) → view'`, **pure** (no IO), living
in `store` with no storage deps. Both `MemoryStore.append-event!` (live) and any
recovery path (P2 fold-from-log) call it. Sketch:

```clojure
(defn apply-event [view {:keys [event/type] :as ev}]
  (-> (case type
        :session/started   (assoc view :session (:session ev))
        :turn/started      (update view :turns conj (:turn ev))
        :turn/put          (update view :turns replace-by-id :turn/id (:turn ev))
        :step/started      (update view :steps conj (:step ev))
        :step/put          (update view :steps replace-by-id :step/id (:step ev))
        :message/appended  (update view :messages conj (:message ev))
        :eval/added        (update view :evals conj (:eval ev))
        :session/vars-snapshotted (assoc view :vars-ref (:vars-ref ev))
        :session/compacted (-> view (assoc :vars-ref (:vars-ref ev)
                                           :compact-from-event-id (:compact-from-event-id ev))
                                    (update :messages conj (:message ev)))
        :session/stop-requested (assoc-in view [:session :session/status] :stop-requested)
        :session/stopped        (assoc-in view [:session :session/status] :stopped)
        :session/error     (-> view (assoc :error (:error ev))
                                    (assoc-in [:session :session/status] :error))
        view)
      (update :events conj ev)
      (bump-counters ev)))   ; max id per collection + :event
```

`replace-by-id` replaces the entity with matching id in the vector (preserving order)
or conj's if absent.

---

## 7. `MemoryStore` (Phase-1 impl)

```clojure
(defrecord MemoryStore [sessions blobs]
  ;; sessions : atom {sid -> slot}
  ;;   slot = {:view (atom empty-view) :lock (Object.) :dispatch <09>
  ;;           :sci-ctx (atom nil) :busy (atom false)}   ; atoms STABLE across idempotent re-create
  ;; blobs    : atom {content-id -> value}   ← GLOBAL, content-addressed (the Merkle substrate)
  SessionStore
  (create-session! [_ session-map]
    (let [sid (:session/id session-map)]
      (swap! sessions (fn [m] (if (m sid) m (assoc m sid (new-slot)))))  ; insert-if-absent
      (handle-for sid (@sessions sid))))   ; existing slot on 2nd call; start-session! resets
                                           ;   the slot's :sci-ctx atom after (kernel/new-ctx …)

  (append-event! [_ sid ev]
    (let [{:keys [view lock] :as slot} (@sessions sid)]
      (locking lock                              ; the per-session STORE LOCK (serializes all appends)
        (let [ev* (stamp-ids+ts slot ev)         ; assign :event/id + :event/at + entity id (GD34, below)
              _   (persist! slot ev*)            ; MemoryStore: no-op; P2: durable write (before fold)
              _   (swap! view apply-event ev*)]
          (schedule-notify! slot ev*)            ; NON-BLOCKING enqueue; an off-lock dispatcher
          ev*))))                                ;   delivers in :event/id order AFTER the lock (09)

  (intern-payload! [_ value opts]               ; GLOBAL, content-addressed, idempotent
    (let [bytes (canonical-bytes value)
          id    (str "sha256:" (sha256-hex bytes))]
      (swap! blobs assoc id value)               ; same value ⇒ same id ⇒ dedup
      {:fractal/ref :payload :payload/id id
       :payload/kind (:payload/kind opts) :payload/size (alength bytes)}))

  (read-payload* [_ ref] (get @blobs (:payload/id ref)))   ; global content lookup, no sid
  (current-view  [_ sid] @(:view (@sessions sid)))         ; STRONG; same atom — never read-state's job
  (read-state    [_ sid] @(:view (@sessions sid)))         ; RELAXED contract (impl coincides in P1)
  (peek-next-id  [_ sid k] (inc (get-in @(:view (@sessions sid)) [:counters k])))
  (notify-transient [_ sid item] (live/notify-transient (:dispatch (@sessions sid)) item))  ; 09
  ;; subscribe! / events-since → delegate to the slot's live dispatch (09)
  )
```

> ⚠ The blob map is global and not GC'd in Phase 1 — fine for short-lived in-process
> runs. (Phase 2's blob store GCs by reachability from heads.) Use real
> `sha256`/timestamps here — this is production code, not a workflow script.

**`stamp-ids+ts` (GD34).** Always assigns `:event/id` (next `:event` counter) and
`:event/at` (`time/now-str`). For a **creating** event it also mints a fresh entity id
from the matching counter and assocs it into the nested entity — `:turn/started`→
`:turn/id`, `:step/started`→`:step/id`, `:message/appended`→`:message/id`,
`:eval/added`→`:eval/id`. A `…/put` event KEEPS the entity id already on its full entity.
`:session/compacted` mints its embedded compact-message's `:message/id` from the message
counter. (Counters live in the view's `:counters`; the fold only maxes them, §6.)

---

## 8. Invariants (tested — see 10)

1. **Store assigns all ids.** No caller reads a counter to mint an id (except
   `peek-next-id` on the writer thread — for `*current-eval-id*` and the `open-turn!`
   turn id, §4). Tested: assigned id == folded counter max, always.
2. **Persist-before-fold.** A failed persist must not advance the view. (Moot for
   MemoryStore; the contract exists for Phase 2.)
3. **Append-only.** No event mutates a prior event. Entity mutation = a `…/put` event
   carrying the full new entity; the write surface exposes no "update".
4. **Single writer per session — two named locks.** (a) The **per-session store lock**
   (the slot's `:lock`) serializes *every* `append-event!`, so appends are totally
   ordered and the fold is race-free. (b) The **busy turn-lock** (the handle's `:busy`
   atom, 07) bounds *turn-running* writers to one — the turn's eval/loop thread. Live
   reads take neither lock. **Control** events (`:session/started`,
   `:session/stop-requested`, an idle `:session/stopped`) may be appended from a NON-turn
   thread (the caller of `start-session!`/`stop-session!`); that is safe because they
   still serialize on the store lock. Phase-4 parent-invocation events are appended only
   by the parent's eval thread.
5. **Foldability.** `(fold (:events (read-state store sid)))` reproduces the view
   structure. `verify-no-dangling-refs` confirms every ref resolves.
6. **Read surface ≠ write surface.** Writes (`create-session!`/`append-event!`/
   `intern-payload!`) are the single writer's; reads (`current-view`/`read-state`/
   `read-payload*`/`events-since`) are callable any time, never blocking on a write.
7. **Idempotent, atomic `create-session!`.** A second call returns the existing handle;
   it never resets state, and concurrent creation does not lose events.
8. **Content-addressed, opaque refs.** Every blob ref id is `sha256:<hex>` over
   `canonical-bytes` — uniform across impls, deduplicating, Merkle-stable. The loop
   branches only on `:fractal/ref`, never on the id's structure. (See §9.)

---

## 9. Merkle-DAG alignment (forward compatibility — MANDATORY)

The persistence design is a **Merkle DAG** (git-like): a graph of content-addressed,
immutable nodes. Phase 1's data model and REPL-state model were built to be compatible
with it; Phase 4 publishes heads and lineage edges through the same store port.

**The two node kinds.**
- **Blobs** = content-addressed leaves (`sha256:<hex>` over `canonical-bytes`). *Every*
  large value already becomes one: message content, eval results, the FINAL value, the
  interned request, and — critically — the **REPL var snapshot** (`:vars-ref`). These
  are Merkle leaves for free, today.
- **Heads** = content-addressed inner nodes. A head is:
  ```clojure
  {:head/id         "sha256:<hex>"      ; = fingerprint over the head's canonical content
   :head/session    "s-<uuid>"
   :head/basis      "sha256:<hex>|nil"  ; parent head ⇒ the per-session Merkle chain
   :head/event-range [from-event to-event]
   :head/vars-ref   {:fractal/ref :payload …}   ; the content-addressed REPL snapshot
   :head/final-ref  {:fractal/ref :payload …}   ; the content-addressed FINAL value
   :head/kind       :turn-final | :compaction}
  ```
  The **DAG** = heads linked by `:head/basis` (per session) plus **cross-session
  edges**: an *invocation* edge (parent head → child head, for `rlm`/`map-rlm`) and a
  *derivation* edge (source head → target head, for `attach-rlm`).

**Why Phase 1 is already aligned (do not break this):**
1. Blobs are content-addressed in *every* impl — so a head can pin any value by its
   hash, and equal values share one node (dedup / structural sharing, like git trees).
2. The turn-completion path already produces **every input a head needs**: a
   content-addressed `:vars-ref` (REPL snapshot), a content-addressed `:turn/final-ref`,
   an **event range** (the monotonic `:event/id` counter gives `[from to]`), and the
   *basis* (the previous head). Publishing a head is additive: it pins these by hash
   and computes a fingerprint.
3. **Determinism.** Entity ids are reproducible counters (a re-fold yields the same
   ids), and `canonical-bytes` gives stable hashes — so a fingerprint over a head's
   canonical content is reproducible and tamper-evident.

**Rules Phase 1 must hold to stay aligned:**
- Never inline a large value that should be a blob (it would have no Merkle identity).
- The **REPL var snapshot is content-addressed and canonical** (see 03 §snapshot): a
  deterministic value (sorted, no meta), so it is a stable Merkle leaf and equal REPL
  states dedup. Record which vars were `:unrestorable` *inside* the snapshot value, so
  the snapshot is a faithful, hashable record.
- `:event/id` is a **monotonic per-session counter** (never reused, never reordered) so
  it can serve as the head's `event-range` boundary.
- A turn boundary and a compaction are the two points where a head would be published;
  Phase 1 already emits the snapshot there.

**Implemented Phase-4 extension:** heads add top-level view keys (`:heads`,
`:current-head`) and store op `publish-head!` (optimistic CAS on the per-session
current-head ref, atomic with pinning an `:event/id`) without touching the original
session/turn/step/eval/message fields. `:vars-ref` remains the projection fallback;
resume prefers `:head/vars-ref` from the current head when present, else `:vars-ref`.
Lineage edges live in top-level `:edges` via `:lineage/edge-added`.
