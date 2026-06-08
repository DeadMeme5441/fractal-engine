(ns fractal.engine.store.memory
  "L1.5 · MemoryStore — the Phase-1 in-memory SessionStore (02 §7). A global
   content-addressed `blobs` atom (the Merkle substrate, shared across sessions)
   plus per-session slots. `append-event!` stamps ids+ts from the per-session
   counters, folds via the pure `apply-event`, then schedules a non-blocking
   live notification OUT of the lock. The live methods delegate to `live`
   (store.memory → live, the deliberate L1.5→L1 edge)."
  (:require [fractal.engine.store :as store]
            [fractal.engine.payload :as payload]
            [fractal.engine.live :as live]
            [fractal.engine.time :as time]))

;; ---------------------------------------------------------------------------
;; Slots + handles
;; ---------------------------------------------------------------------------

(defn- new-slot [sid live-opts]
  {:view     (atom (store/empty-view))
   :lock     (Object.)
   :dispatch (live/make-dispatch sid live-opts)
   :sci-ctx  (atom nil)     ; stable across an idempotent re-create; set by start-session!
   :busy     (atom false)}) ; the turn-lock (07)

(defn- handle-for [store sid slot]
  {:store      store
   :session-id sid
   :sci-ctx    (:sci-ctx slot)   ; shared by reference — handle and slot see the same REPL
   :busy       (:busy slot)})    ; …and the same turn-lock

;; ---------------------------------------------------------------------------
;; Id + timestamp stamping (GD34) — reads counters off the CURRENT view, under
;; the store lock, so reads + increments are race-free (single writer).
;; ---------------------------------------------------------------------------

(defn- stamp-ids+ts
  [view ev]
  (let [{:keys [event message turn step eval]} (:counters view)
        ev (assoc ev :event/id (inc event) :event/at (time/now-str))]
    (case (:event/type ev)
      :turn/started      (assoc-in ev [:turn :turn/id]       (inc turn))
      :step/started      (assoc-in ev [:step :step/id]       (inc step))
      :message/appended  (assoc-in ev [:message :message/id] (inc message))
      :eval/added        (assoc-in ev [:eval :eval/id]       (inc eval))
      :session/compacted (assoc-in ev [:message :message/id] (inc message))
      ev)))   ; …/put keeps its entity id; session/* control events have none

;; ---------------------------------------------------------------------------
;; The store
;; ---------------------------------------------------------------------------

(defrecord MemoryStore [sessions blobs]
  store/SessionStore

  (create-session! [this session-map]
    (let [sid       (:session/id session-map)
          live-opts (or (::live (meta session-map)) {})]
      (swap! sessions (fn [m] (if (m sid) m (assoc m sid (new-slot sid live-opts)))))
      (handle-for this sid (get @sessions sid))))

  (append-event! [_ sid ev]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (let [stamped (stamp-ids+ts @view ev)]
          ;; persist! — MemoryStore no-op; Phase 2 writes durably BEFORE the fold
          (swap! view store/apply-event stamped)
          (live/schedule-notify dispatch stamped)   ; non-blocking; delivered off-lock
          stamped))))

  (append-events! [_ sid events]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (mapv (fn [ev]
                (let [stamped (stamp-ids+ts @view ev)]
                  (swap! view store/apply-event stamped)
                  (live/schedule-notify dispatch stamped)
                  stamped))
              events))))

  (publish-head! [_ sid head]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (let [head*   (store/prepare-head @view sid head)
              stamped (stamp-ids+ts @view {:event/type :head/published :head head*})]
          (swap! view store/apply-event stamped)
          (live/schedule-notify dispatch stamped)
          head*))))

  (append-lineage-edge! [this sid edge]
    (store/append-event! this sid
                         {:event/type :lineage/edge-added
                          :edge (store/edge-with-id edge)}))

  (intern-payload! [_ value opts]
    (let [bytes (payload/canonical-bytes value)
          id    (str "sha256:" (payload/sha256-hex bytes))]
      (swap! blobs assoc id value)                  ; same value ⇒ same id ⇒ dedup
      (payload/make-ref id (:payload/kind opts) (alength bytes))))

  (read-payload* [_ ref]
    (get @blobs (:payload/id ref)))                 ; global content lookup, no sid

  (current-view [_ sid]
    @(:view (get @sessions sid)))                   ; STRONG read-your-writes

  (read-state [_ sid]
    @(:view (get @sessions sid)))                   ; RELAXED (coincides in P1)

  (peek-next-id [_ sid k]
    (inc (get-in @(:view (get @sessions sid)) [:counters k])))

  (notify-transient [_ sid item]
    (live/notify-transient (:dispatch (get @sessions sid)) item))

  (subscribe! [_ sid callback]
    (live/subscribe (:dispatch (get @sessions sid)) callback))

  (events-since [_ sid event-id]
    (->> (:events @(:view (get @sessions sid)))
         (filterv (fn [ev] (> (:event/id ev) event-id))))))

(defn memory-store
  "Construct a fresh MemoryStore (empty sessions + global blob store)."
  []
  (->MemoryStore (atom {}) (atom {})))

;; The store-agnostic dispatcher-stop used by stop-session! lives in
;; `fractal.engine.store.slots` (it works on MemoryStore and SqliteStore alike,
;; since both key per-session `:dispatch` slots under a `:sessions` atom).
