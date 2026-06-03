(ns fractal-engine.session
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.process :as process]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provider-call :as provider-call]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.time :as time]))

(defn latest-complete-snapshot [state]
  (last (filter snapshot/completed-turn-snapshot? (:snapshots @state))))

(defn session-handle [state cfg ns-sym ops]
  {:state state
   :cfg cfg
   :ns-sym ns-sym
   :ops ops
   :locator (:locator @state)
   ;; Guards against overlapping turns on one handle: a session has a single REPL
   ;; namespace and a single append-only message log, so two turns at once would
   ;; corrupt both. Held by run-turn!/run-turn-async! for the duration of a turn.
   :busy (atom false)})

(defn- install! [state cfg ns-sym]
  (let [ops (process/make-ops state cfg ns-sym)]
    (runtime/ensure-ns! ns-sym ops {:clear? true})
    ops))

(defn start-session!
  "Start a fresh session in the canonical SQLite + BlobStore root.
  Opts may carry an `:overlay` — an extra, session-level system instruction
  appended to the base behavior in the single system message. It is a standing
  specialization for the whole session (e.g. codebrain's brain role), stated once
  at birth and carried across every turn and resume via the message history; it
  is NOT the per-turn task. Kept to one combined system message so it is
  provider-agnostic (some adapters honor only the first system)."
  ([cfg] (start-session! cfg {}))
  ([cfg {:keys [id logical-id kind parent cache-id overlay store-root alias title origin]}]
   (let [cfg (process/config cfg)
         effective-cfg (process/child-root-config cfg kind)
         sid (or id (artifacts/session-id))
         logical-id' (or logical-id sid)
         store-root' (or store-root (:runs-dir cfg))
         state (artifacts/new-state! {:id sid
                                      :logical-id logical-id'
                                      :cache-id (or cache-id logical-id')
                                      :kind (or kind :root)
                                      :provider (provider-call/provider-shape effective-cfg)
                                      :parent parent
                                      :store-root store-root'
                                      :alias alias
                                      :title title
                                      :origin origin})
         session-id (get-in @state [:session :session/id])
         ns-sym (runtime/session-ns-symbol session-id)
         ops (install! state effective-cfg ns-sym)
         base prompt/system-prompt]
     (artifacts/add-message! state :system (if (str/blank? (str overlay))
                                             base
                                             (str base "\n\n" overlay)))
     (artifacts/create-head! state {:head/kind :initial
                                    :head/message-through-id (apply max 0 (map :message/id (:messages @state)))
                                    :head/event-range {:from-event 1
                                                       :to-event (get-in @state [:counters :event])}})
     (session-handle state effective-cfg ns-sym ops))))

(defn- run-turn-checked! [session user-message]
  (let [{:keys [state cfg ns-sym]} session
        status (get-in @state [:session :session/status])]
    (when-not (= :running status)
      (throw (ex-info "Session is not running"
                      {:error/type :fractal/session-not-running
                       :session/status status})))
    (process/run-turn-on-state! state cfg ns-sym user-message)))

(defn- acquire-turn! [session]
  (when-not (compare-and-set! (:busy session) false true)
    (throw (ex-info "A turn is already running on this session"
                    {:error/type :fractal/turn-in-flight
                     :session/id (get-in @(:state session) [:session :session/id])}))))

(defn run-turn! [session user-message]
  (acquire-turn! session)
  (try
    (run-turn-checked! session user-message)
    (finally
      (reset! (:busy session) false))))

(defn run-turn-async!
  "Run one turn on a background daemon thread. Returns a promise that delivers the
  turn result map. A turn that throws delivers {:status :error :error ...} instead
  of escaping, so a deref or poll never surprises the caller. The session event stream
  updates live throughout — poll `projection/progress` (or `load-node`) on the
  session locator to watch the turn settle, then deref the promise for the result.
  Refuses to start (throws :fractal/turn-in-flight) if a turn is already running on
  this handle."
  [session user-message]
  (acquire-turn! session)
  (let [p (promise)
        run (fn []
              (try
                (deliver p (run-turn-checked! session user-message))
                (catch Throwable t
                  (deliver p {:status :error
                              :error {:error/type (or (:error/type (ex-data t)) :fractal/turn-failed)
                                      :error/message (.getMessage t)
                                      :error/data (ex-data t)}}))
                (finally
                  (reset! (:busy session) false))))]
    (doto (Thread. ^Runnable run
                   (str "fractal-turn-" (get-in @(:state session) [:session :session/id])))
      (.setDaemon true)
      (.start))
    p))

(defn stop-session! [session]
  (let [state (:state session)]
    (artifacts/update-status! state :stopped)
    (artifacts/add-event! state {:event/type :session-stopped
                                 :session/id (get-in @state [:session :session/id])})
    (:session @state)))

(defn- restored-session!
  [cfg source-handle {:keys [id logical-id kind cache-id preserve-cache? lineage-kind
                          lineage-parents turn head parent store-root]}]
  (let [cfg (process/config cfg)
        source-store-root (or (when (map? source-handle) (:store/root source-handle))
                              (:runs-dir cfg))
        target (or (session-db/resolve-handle source-store-root source-handle
                                              (cond-> {} head (assoc :head head)))
                   (throw (ex-info "Source session does not exist"
                                   {:error/type :session/source-missing
                                    :source/handle source-handle
                                    :storage/root (str source-store-root)})))
        source-store-root (store-io/path (:store/root target))
        source-session-id (:session-id target)
        target-head-id (:head-id target)
        source-locator (select-keys target [:store/root :session/id :head/id])
        snapshot-opts (cond-> {}
                        turn (assoc :turn turn)
                        (or head target-head-id) (assoc :head (or head target-head-id)))
        snapshot-row (snapshot/require-snapshot source-locator snapshot-opts)
        snapshot-blob (snapshot/require-snapshot-blob source-locator snapshot-row)
        source-session (or (session-db/read-session source-store-root source-session-id)
                           (throw (ex-info "Source session does not exist"
                                           {:error/type :session/source-missing
                                            :session/id source-session-id
                                            :storage/root (str source-store-root)})))
        source-logical-id (or (:session/logical-id source-session)
                              (:session/id source-session))
        source-cache-id (or (:session/cache-id source-session)
                            source-logical-id)
        source-head-id (or head
                           target-head-id
                           (:head/id (first (filter #(= (:snapshot/id snapshot-row)
                                                        (:head/snapshot-id %))
                                                    (snapshot/heads source-locator))))
                           (snapshot/current-head-id source-locator))
        resume? (= :resume lineage-kind)
        source-fingerprint (snapshot/session-fingerprint source-locator)
        sid (or (when resume? source-logical-id) id (artifacts/session-id))
        logical-id' (or logical-id
                        (when resume? source-logical-id)
                        sid)
        cache-id' (or cache-id
                      (when (or resume? preserve-cache?) source-cache-id)
                      logical-id')
        store-root' (or store-root
                        (if resume? source-store-root (:runs-dir cfg)))
        kind' (or kind :root)
        effective-cfg (process/child-root-config cfg kind')
        state (if resume?
                (artifacts/load-state! {:store-root store-root'
                                        :session-id source-logical-id
                                        :head-id source-head-id})
                (artifacts/new-state! {:id sid
                                       :logical-id logical-id'
                                       :cache-id cache-id'
                                       :kind kind'
                                       :provider (provider-call/provider-shape effective-cfg)
                                       :parent parent
                                       :store-root store-root'
                                       :initial-head? false}))
        ns-sym (runtime/session-ns-symbol logical-id')
        ops (install! state effective-cfg ns-sym)]
    (when resume?
      (artifacts/emit! state {:event/type :session/status
                              :status :running
                              :ended-at nil})
      (artifacts/flush! state))
    (process/restore-state-from-snapshot!
     state
     source-locator
     ns-sym
     snapshot-row
     snapshot-blob
     lineage-kind
     lineage-parents
     source-fingerprint)
    (artifacts/add-event! state {:event/type (case lineage-kind
                                               :fork :session-forked
                                               :resume :session-resumed
                                               :session-restored)
                                 :session/id sid
                                 :restore/strategy :head-state+snapshot-vars
                                 :head/id source-head-id
                                 :snapshot/id (:snapshot/id snapshot-row)
                                 :turn/id (:snapshot/turn-id snapshot-row)})
    (artifacts/flush! state)
    (session-handle state effective-cfg ns-sym ops)))

(defn resume-session!
  ([cfg source-handle] (resume-session! cfg source-handle {}))
  ([cfg source-handle opts]
   (let [cfg (process/config cfg)]
     (restored-session! cfg source-handle
                        (merge opts
                               {:kind :root
                                :lineage-kind :resume
                                :lineage-parents [{:parent/kind :resumed-from
                                                   :parent/session-id (session-db/handle-session-id source-handle)}]})))))

(defn fork-session!
  ([cfg source-handle target-store-hint]
   (fork-session! cfg source-handle target-store-hint {}))
  ([cfg source-handle target-store-hint opts]
   (restored-session! cfg source-handle
                      (merge opts
                             {:store-root target-store-hint
                              :kind :root
                              :lineage-kind :fork
                              :lineage-parents [{:parent/kind :forked-from
                                                 :parent/session-id (session-db/handle-session-id source-handle)}]}))))
