(ns fractal.engine.live
  "L1 · the per-session live dispatch as a PURE MECHANISM — no store dependency
   (09, GD8). `store.memory` implements the `subscribe!`/`events-since`/
   `notify-transient` protocol methods by delegating here; the loop and api only
   ever call the port methods. Delivery happens on a per-session daemon
   dispatcher thread (lazily started on the first subscriber) so a slow or
   throwing subscriber can neither stall nor corrupt a write. Durable events and
   transient deltas share one ordered queue; on overflow transient deltas drop
   first, a durable skip is a last resort, and either way one coalesced
   `:subscribe/gap` is emitted so a subscriber recovers via `events-since`."
  (:import [clojure.lang PersistentQueue]))

;; ---------------------------------------------------------------------------
;; Reentrancy guard (09 §2)
;; ---------------------------------------------------------------------------

(def ^:dynamic *in-dispatch*
  "Bound to the session id while a callback runs on the dispatch thread. The
   store's append-event! and session's run-turn!/compact-session! check it and
   throw :subscribe/reentrant — a callback must not drive the session it
   observes."
  nil)

(defn check-not-reentrant!
  [sid]
  (when (= sid *in-dispatch*)
    (throw (ex-info "reentrant store write from a subscriber callback"
                    {:error/type :subscribe/reentrant :session/id sid}))))

;; ---------------------------------------------------------------------------
;; Dispatch construction
;; ---------------------------------------------------------------------------

(defn make-dispatch
  "Create a per-session dispatch. The dispatcher thread is NOT started until the
   first subscriber registers (most sessions never subscribe, so they pay for no
   thread). `opts`: `:bound` (queue bound, default 1024), `:drop` (policy)."
  [sid {:keys [bound drop] :or {bound 1024 drop :drop-transient}}]
  {:sid    sid
   :bound  bound
   :drop   drop
   ;; state: q = queue of {:kind :durable|:transient :item m}; gap? = a coalesced
   ;; gap is pending; last-delivered = highest durable :event/id delivered.
   :state  (atom {:q (PersistentQueue/EMPTY) :gap? false :last-delivered 0})
   :subs   (atom {})
   :sub-ctr (atom 0)
   :alive  (atom true)
   :thread (atom nil)
   :lock   (Object.)})

;; ---------------------------------------------------------------------------
;; Delivery (dispatch thread only)
;; ---------------------------------------------------------------------------

(defn- deliver-one!
  [{:keys [sid subs state] :as d} item]
  (when-let [eid (:event/id item)]
    (swap! state assoc :last-delivered eid))
  (binding [*in-dispatch* sid]
    (doseq [[sub-id cb] @subs]
      (try
        (cb item)
        (catch Throwable _
          ;; a throwing subscriber can't break delivery — drop it (auto-unsubscribe)
          (swap! subs dissoc sub-id))))))

(defn- take-batch!
  "Block until there is work (or the dispatch is stopped); return
   `{:items [...] :gap? bool}` and atomically clear the queue + gap flag.
   ⛔ DRAINS pending work BEFORE honoring a stop, so a durable event enqueued
   just before stop-dispatch (e.g. the terminal :session/stopped) is still
   delivered — never silently dropped (09 §4)."
  [{:keys [state lock alive]}]
  (locking lock
    (loop []
      (let [{:keys [q gap?] :as st} @state]
        (cond
          (or (seq q) gap?)
          (do (reset! state (assoc st :q (PersistentQueue/EMPTY) :gap? false))
              {:items (vec q) :gap? gap?})
          (not @alive) nil
          :else (do (.wait ^Object lock 1000) (recur)))))))

(defn- dispatch-loop
  [d]
  (loop []
    (when-let [{:keys [items gap?]} (take-batch! d)]
      (doseq [tagged items] (deliver-one! d (:item tagged)))
      (when gap?
        (deliver-one! d {:event/type :subscribe/gap
                         :last-delivered-event-id (:last-delivered @(:state d))}))
      (recur))))

(defn- ensure-thread!
  "Start the dispatcher daemon thread once, on the first subscriber."
  [{:keys [thread sid] :as d}]
  (when (nil? @thread)
    (let [t (Thread. ^Runnable (fn [] (dispatch-loop d)) (str "fractal-live-" sid))]
      (when (compare-and-set! thread nil t)
        (.setDaemon t true)
        (.start t)))))

;; ---------------------------------------------------------------------------
;; Enqueue (writer thread for durable; adapter daemon thread for transient)
;; ---------------------------------------------------------------------------

(defn- evict-first-transient
  "Drop the oldest transient-tagged item from q (order otherwise preserved)."
  [q]
  (let [items (vec q)
        idx   (first (keep-indexed (fn [i it] (when (= :transient (:kind it)) i)) items))]
    (if idx
      (into (PersistentQueue/EMPTY)
            (concat (subvec items 0 idx) (subvec items (inc idx))))
      q)))

(defn- enqueue!
  [{:keys [state bound lock subs] :as d} kind item]
  ;; Fast no-op when nobody is listening: a late subscriber catches up via
  ;; events-since off the durable log (the queue is a delivery channel only).
  (when (seq @subs)
    (locking lock
      (swap! state
             (fn [{:keys [q] :as st}]
               (cond
                 (< (count q) bound)
                 (update st :q conj {:kind kind :item item})

                 ;; full but a queued transient can be evicted (transient drops first)
                 (some #(= :transient (:kind %)) q)
                 (-> st (update :q evict-first-transient)
                        (update :q conj {:kind kind :item item})
                        (assoc :gap? true))

                 ;; full, nothing transient to shed: drop this item from DELIVERY
                 ;; (a durable event stays in the log — only its push is skipped)
                 :else
                 (assoc st :gap? true))))
      (.notifyAll ^Object lock))))

;; ---------------------------------------------------------------------------
;; Public mechanism (called by store.memory's protocol methods)
;; ---------------------------------------------------------------------------

(defn schedule-notify
  "Non-blocking enqueue of a DURABLE event for off-lock delivery (called inside
   append-event!'s store lock; never blocks the writer)."
  [d event]
  (enqueue! d :durable event))

(defn notify-transient
  "Non-blocking enqueue of a TRANSIENT signal (no :event/id; droppable)."
  [d item]
  (enqueue! d :transient item))

(defn subscribe
  "Register `callback`; start the dispatcher if needed. Returns an unsubscribe fn."
  [d callback]
  (let [id (swap! (:sub-ctr d) inc)]
    (swap! (:subs d) assoc id callback)
    (ensure-thread! d)
    (fn unsubscribe [] (swap! (:subs d) dissoc id) nil)))

(defn stop-dispatch
  "Stop the dispatcher thread (idempotent). Daemon threads are JVM-exit-safe
   regardless; this avoids accumulating idle threads across many sessions."
  [{:keys [alive lock]}]
  (reset! alive false)
  (locking lock (.notifyAll ^Object lock))
  nil)

;; ---------------------------------------------------------------------------
;; progress — a cheap, PURE derivation over a view VALUE (09 §1)
;; ---------------------------------------------------------------------------

(defn progress
  "A ref-free live snapshot derived from a view value — no store dep, no payload
   hydration, safe to poll at high frequency."
  [view]
  (let [session  (:session view)
        turns    (:turns view)
        steps    (:steps view)
        cur-turn (last turns)]
    {:session/id     (:session/id session)
     :session/status (:session/status session)
     :running?       (= :running (:session/status session))
     :turn-count     (count turns)
     :current-turn   (:turn/id cur-turn)
     :step-count     (count steps)
     :in-flight      (boolean (and cur-turn (= :running (:turn/status cur-turn))))
     :last-event-id  (get-in view [:counters :event] 0)}))
