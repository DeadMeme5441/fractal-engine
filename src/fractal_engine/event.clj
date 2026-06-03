(ns fractal-engine.event
  "The pure core. An event is a value recording something that happened;
  `apply-event` folds one event into the materialized session view. It is a pure
  projector — no IO, no provider/leaf/child calls. Events carry *results*, never
  recipes (SPEC invariant), so folding the event stream reconstructs state without ever
  re-running expensive work.

  The view mirrors the shape the rest of the engine already reads:
  `:session` (map), `:messages`/`:turns`/`:evals`/`:calls`/`:snapshots`/`:events`
  (vectors), plus `:final-ref` and `:counters`. `artifacts.clj` keeps these keys at
  the top of its state atom alongside infra keys; `apply-event` only ever touches
  the view keys, so infra survives untouched.")

(def empty-view
  {:session nil
   :messages []
   :turns []
   :evals []
   :calls []
   :snapshots []
   :heads []
   :invocations []
   :invocation-events []
   :refs {}
   :events []
   :final-ref nil
   :error nil
   :counters {:message 0 :turn 0 :eval 0 :call 0 :event 0 :snapshot 0
              :head 0 :invocation 0 :child 0}})

(defn- bump [counters k id]
  (if (number? id) (update counters k max id) counters))

(defn- put-by [coll id-key row]
  (mapv #(if (= (id-key %) (id-key row)) row %) coll))

(defn- merge-by [coll id-key id patch]
  (let [matched? (atom false)
        coll' (mapv (fn [row]
                      (if (= id (id-key row))
                        (do
                          (reset! matched? true)
                          (merge row patch))
                        row))
                    coll)]
    (if @matched?
      coll'
      (conj coll' patch))))

(defn apply-event
  "view' = (apply-event view event). Pure. Every event is recorded in `:events`
  (the in-memory mirror of canonical event facts); state-changing events additionally fold
  into the relevant view key. Unknown/annotation events change nothing but the log."
  [view event]
  (let [view (-> view
                 (update :events conj event)
                 (update :counters bump :event (:event/id event)))]
    (case (:event/type event)
      :session/started
      (assoc view :session (:session event))

      :session/status
      (update view :session assoc
              :session/status (:status event)
              :session/ended-at (:ended-at event))

      :session/final
      (assoc view :final-ref (:final/value-ref event))

      ;; Resume/fork/attach inherit prior state from a snapshot. Carrying it as one
      ;; event keeps the new session's event stream self-contained: a fold reproduces the
      ;; inherited messages and counters without reading the source session.
      :session/restored
      (let [restored-state (:state event)
            restored-view (select-keys restored-state
                                       [:messages :turns :evals :calls :snapshots
                                        :heads :invocations :refs :final-ref :error])
            restored-counters (or (:counters restored-state)
                                  (:counters event))]
        (-> view
            (merge restored-view)
            (update :counters #(merge-with max (or % {}) (or restored-counters {})))
            (update :session merge
                    (:session restored-state)
                    (:session-patch event))))

      :session/error
      (-> view
          (assoc :error (:error event))
          (update :session assoc
                  :session/status :error
                  :session/ended-at (:ended-at event)))

      :message/added
      (let [m (:message event)]
        (-> view
            (update :messages conj m)
            (update :counters bump :message (:message/id m))))

      :turn/started
      (let [t (:turn event)
            n (count (:turns view))]
        (-> view
            (update :turns conj t)
            (update :counters bump :turn (:turn/id t))
            (update :session assoc
                    :session/turn-count (inc n)
                    :session/latest-turn-id (:turn/id t))))

      :turn/put
      (update view :turns put-by :turn/id (:turn event))

      :eval/added
      (let [e (:eval event)]
        (-> view
            (update :evals conj e)
            (update :counters bump :eval (:eval/id e))))

      :call/started
      (let [c (:call event)]
        (-> view
            (update :calls conj c)
            (update :counters bump :call (:call/id c))))

      :call/put
      (update view :calls put-by :call/id (:call event))

      :snapshot/added
      (let [s (:snapshot event)]
        (-> view
            (update :snapshots conj s)
            (update :counters bump :snapshot (:snapshot/id s))))

      :head/created
      (let [h (:head event)]
        (-> view
            (update :heads conj h)
            (update :counters bump :head (:head/n h))))

      :session/ref-updated
      (assoc view :refs {:ref/session (:ref/session event)
                         :ref/current-head (:ref/current-head event)
                         :ref/updated-at (:event/at event)})

      :invocation/started
      (let [i (:invocation event)]
        (-> view
            (update :invocation-events conj event)
            (update :invocations conj i)
            (update :counters bump :invocation (:invocation/n i))))

      :invocation/completed
      (let [i (:invocation event)]
        (-> view
            (update :invocation-events conj event)
            (update :invocations merge-by :invocation/id (:invocation/id i)
                    (assoc i :invocation/status :final))))

      :invocation/failed
      (let [i (:invocation event)]
        (-> view
            (update :invocation-events conj event)
            (update :invocations merge-by :invocation/id (:invocation/id i)
                    (assoc i :invocation/status :error))))

      :invocation/caller-head-recorded
      (let [i (:invocation event)]
        (-> view
            (update :invocation-events conj event)
            (update :invocations merge-by :invocation/id (:invocation/id i) i)))

      ;; annotation / lifecycle-note events (restore, attach lifecycle, etc.):
      ;; recorded in the log above, no row change.
      view)))

(defn fold
  "Reconstruct a view from an event stream. Pure: zero provider/leaf/child calls."
  [events]
  (reduce apply-event empty-view events))

(defn- trailing-number [s]
  (when s
    (when-let [m (re-find #"(\d+)$" (str s))]
      (parse-long (first m)))))

(defn recover-child-counter
  "The `:child` counter is encoded only in child/attached session ids (child-0007,
  attached-0003); recover its high-water mark from a folded view so a resumed
  session keeps numbering children correctly."
  [view]
  (->> (:calls view)
       (keep :child/session-id)
       (keep trailing-number)
       (reduce max 0)))
