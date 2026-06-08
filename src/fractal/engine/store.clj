(ns fractal.engine.store
  "L1 · the SessionStore PORT, the event taxonomy, and the pure fold (02). The
   loop talks to this protocol and nothing below it — swap MemoryStore for a
   Phase-2 SQLiteStore with zero loop changes. `apply-event` is PURE (no IO):
   store impls call it on the write path; a Phase-2 recovery path folds the log
   with it. Depends only on the PURE `payload` ns (for `payload-ref?`), never on
   `payload-io` — so there is no cycle."
  (:require [clojure.walk :as walk]
            [fractal.engine.payload :as payload]))

;; ---------------------------------------------------------------------------
;; The session view (state value) — the fold of the event log (02 §1)
;; ---------------------------------------------------------------------------

(defn empty-view []
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
   :events    []})

;; ---------------------------------------------------------------------------
;; The pure fold (02 §6)
;; ---------------------------------------------------------------------------

(defn- replace-by-id
  "Replace the entity with matching id in `coll` (order preserved) or conj if
   absent — the `…/put` fold semantics."
  [coll id-key entity]
  (let [id  (id-key entity)
        idx (first (keep-indexed (fn [i e] (when (= id (id-key e)) i)) coll))]
    (if idx (assoc coll idx entity) (conj coll entity))))

(defn- entity-counter-bump
  "`[counter-key entity-id]` for an event that mints/updates a counter-tracked
   entity, else nil. `:session/compacted` carries an embedded compact message,
   so it bumps the message counter."
  [{:keys [event/type] :as ev}]
  (case type
    (:turn/started :turn/put)  [:turn    (:turn/id (:turn ev))]
    (:step/started :step/put)  [:step    (:step/id (:step ev))]
    :message/appended          [:message (:message/id (:message ev))]
    :eval/added                [:eval    (:eval/id (:eval ev))]
    :session/compacted         [:message (:message/id (:message ev))]
    nil))

(defn- bump-counters
  "Defensive max of the per-collection counters against the ids the store
   ALREADY assigned (the fold never mints ids — it only maxes, 02 §2)."
  [view ev]
  (let [view (cond-> view
               (:event/id ev) (update-in [:counters :event] (fnil max 0) (:event/id ev)))]
    (if-let [[ck eid] (entity-counter-bump ev)]
      (cond-> view eid (update-in [:counters ck] (fnil max 0) eid))
      view)))

(defn- conj-by-id
  [coll id-key entity]
  (if (some #(= (id-key entity) (id-key %)) coll) coll (conj coll entity)))

(defn apply-event
  "`(view event) → view'`. PURE. The single source of truth for how an event
   advances the view (02 §6). Events carry results, never recipes."
  [view {:keys [event/type] :as ev}]
  (-> (case type
        :session/started          (assoc view :session (:session ev))
        :turn/started             (update view :turns conj (:turn ev))
        :turn/put                 (update view :turns replace-by-id :turn/id (:turn ev))
        :step/started             (update view :steps conj (:step ev))
        :step/put                 (update view :steps replace-by-id :step/id (:step ev))
        :message/appended         (update view :messages conj (:message ev))
        :eval/added               (update view :evals conj (:eval ev))
        :head/published           (-> view
                                      (update :heads conj-by-id :head/id (:head ev))
                                      (assoc :current-head (:head/id (:head ev))))
        :lineage/edge-added       (update view :edges conj-by-id :edge/id (:edge ev))
        :session/vars-snapshotted (assoc view :vars-ref (:vars-ref ev))
        :session/compacted        (-> view
                                      (assoc :vars-ref (:vars-ref ev)
                                             :compact-from-event-id (:compact-from-event-id ev))
                                      (update :messages conj (:message ev)))
        :session/stop-requested   (assoc-in view [:session :session/status] :stop-requested)
        :session/stopped          (assoc-in view [:session :session/status] :stopped)
        :session/error            (-> view
                                      (assoc :error (:error ev))
                                      (assoc-in [:session :session/status] :error))
        view)
      (update :events conj ev)
      (bump-counters ev)))

;; ---------------------------------------------------------------------------
;; Phase 4 · Merkle heads + lineage edges
;; ---------------------------------------------------------------------------

(def head-version 1)
(def edge-version 1)

(defn head-by-id
  "The immutable head with `head-id` in a folded view."
  [view head-id]
  (first (filter #(= head-id (:head/id %)) (:heads view))))

(defn current-head
  "The folded current head map, or nil before the first published boundary."
  [view]
  (head-by-id view (:current-head view)))

(defn next-head-range
  "The event range a newly published head should cover, ending at `to-event-id`.
   The first head covers from event 1; later heads start after their basis range."
  [view to-event-id]
  (let [basis (current-head view)
        from  (if basis (inc (second (:head/event-range basis))) 1)]
    [from to-event-id]))

(defn head-with-id
  "Build a content-addressed immutable head from head content. The id hashes the
   canonical head content without :head/id, so it is reproducible."
  [head]
  (let [content (-> head
                    (dissoc :head/id :head/expected-basis :head/to-event-id)
                    (assoc :head/version head-version))]
    (assoc content :head/id (payload/content-id content))))

(defn prepare-head
  "Prepare a head for publication against the current folded view. Validates the
   optimistic basis when :head/expected-basis is present."
  [view sid head]
  (let [basis (:current-head view)
        expected (:head/expected-basis head)]
    (when (and (contains? head :head/expected-basis)
               (not= expected basis))
      (throw (ex-info "current head changed before publish"
                      {:error/type :fractal/head-cas-failed
                       :head/expected-basis expected
                       :head/current basis
                       :session/id sid})))
    (head-with-id
      (-> head
          (dissoc :head/expected-basis :head/to-event-id)
          (assoc :head/session sid
                 :head/basis basis
                 :head/event-range (next-head-range view (:head/to-event-id head)))))))

(defn edge-with-id
  "Content-address a lineage edge. The id hashes the edge content without
   :edge/id, matching the immutable-head convention."
  [edge]
  (let [content (-> edge (dissoc :edge/id) (assoc :edge/version edge-version))]
    (assoc content :edge/id (payload/content-id content))))

(defn kept-messages
  "The messages surviving compaction (a PURE view derivation, shared by
   request-assembly and compaction — 05 §4, 07 §4): the `:message` of every
   message-bearing event (`:message/appended` | `:session/compacted`) in
   `(:events view)` whose `:event/id` >= `:compact-from-event-id` (nil ⇒ all).
   ⛔ Pruned over the LOG, not over `:messages` — message entities carry no
   `:event/id`, and the compact frame's own event-id == the boundary, so it and
   everything after survive."
  [view]
  (let [boundary (:compact-from-event-id view)]
    (->> (:events view)
         (filter (fn [{:keys [event/type]}]
                   (or (= :message/appended type) (= :session/compacted type))))
         (filter (fn [ev] (or (nil? boundary) (>= (:event/id ev) boundary))))
         (mapv :message))))

;; ---------------------------------------------------------------------------
;; The SessionStore protocol (02 §4)
;; ---------------------------------------------------------------------------

(defprotocol SessionStore
  (create-session! [store session-map]
    "Idempotent + atomic. Insert a fresh per-session slot keyed by :session/id
     IF ABSENT; a 2nd call returns the existing handle, never nuking state.
     Returns a SessionHandle (02 §5).")
  (append-event! [store sid event]
    "THE write op. Under the per-session STORE LOCK: stamp :event/id + :event/at
     (always) and any creating-event entity id, PERSIST, then (on success) fold
     via apply-event into the view cache, then schedule a NON-BLOCKING live
     notification out of the lock. Returns the stamped event.")
  (append-events! [store sid events]
    "Batch variant: assign consecutive ids + persist + fold in ONE critical
     section. MemoryStore may delegate to append-event! in a loop.")
  (publish-head! [store sid head]
    "Phase 4: atomically publish an immutable content-addressed head. Under the
     same per-session STORE LOCK as append-event!: validate optional
     :head/expected-basis against the folded :current-head, compute :head/basis,
     :head/event-range, and :head/id, persist a :head/published event, fold it,
     notify live, and return the head.")
  (append-lineage-edge! [store sid edge]
    "Phase 4: content-address and append one invocation/derivation lineage edge
     as a durable :lineage/edge-added event in session sid's log. Returns the
     stamped edge event.")
  (intern-payload! [store value opts]
    "Content-address value into the GLOBAL blob store (id = sha256 over
     canonical-bytes). Returns a tagged opaque payload-ref. IDEMPOTENT (dedup).
     No sid: blobs are global Merkle nodes.")
  (read-payload* [store ref]
    "Dereference a tagged ref to its value via global content lookup.")
  (current-view [store sid]
    "STRONG read-your-writes snapshot of the folded view. MUST NOT delegate to
     read-state. The loop + api read state ONLY through this.")
  (read-state [store sid]
    "RELAXED snapshot for external/cross-process consumers. Non-blocking.")
  (peek-next-id [store sid counter-key]
    "The id append-event! WILL assign next for counter-key. VALID ONLY on the
     single writer thread.")
  (notify-transient [store sid item]
    "Publish a TRANSIENT live signal (no :event/id; never persisted/folded).")
  (subscribe! [store sid callback]
    "→ unsubscribe fn. Delivers each event + transient delta (09).")
  (events-since [store sid event-id]
    "→ ordered events with :event/id > event-id (backlog catch-up, 09)."))

;; ---------------------------------------------------------------------------
;; Ref auditing (uses ONLY the pure payload-ref? — keeps store off payload-io)
;; ---------------------------------------------------------------------------

(defn collect-refs
  "Every payload-ref reachable in `data` (a view, an event, an entity)."
  [data]
  (let [acc (volatile! [])]
    (walk/postwalk (fn [x] (when (payload/payload-ref? x) (vswap! acc conj x)) x) data)
    @acc))

(defn verify-no-dangling-refs
  "The (hopefully empty) vector of payload-refs in the session view that do not
   resolve in the store. A non-empty result violates the storage invariant — a
   blob write must precede the event that references it (02 §3, §8)."
  [store sid]
  (->> (collect-refs (current-view store sid))
       distinct
       (remove (fn [ref] (some? (read-payload* store ref))))
       vec))
