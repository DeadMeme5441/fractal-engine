(ns fractal-engine.session
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.process :as process]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.time :as time]))

(defn latest-complete-snapshot [state]
  (last (filter snapshot/completed-turn-snapshot? (:snapshots @state))))

(defn session-handle [state cfg ns-sym ops]
  {:state state
   :cfg cfg
   :ns-sym ns-sym
   :ops ops
   :dir (:dir @state)})

(defn- install! [state cfg ns-sym]
  (let [ops (process/make-ops state cfg ns-sym)]
    (runtime/ensure-ns! ns-sym ops)
    ops))

(defn start-session!
  "Start a fresh session. Opts may carry an `:overlay` — an extra, session-level
  system instruction appended to the base behavior in the single system message.
  It is a standing specialization for the whole session (e.g. codebrain's brain
  role), stated once at birth and carried across every turn and resume via the
  message history; it is NOT the per-turn task. Kept to one combined system
  message so it is provider-agnostic (some adapters honor only the first system)."
  ([cfg] (start-session! cfg {}))
  ([cfg {:keys [dir id kind parent cache-id overlay]}]
   (let [cfg (process/config cfg)
         effective-cfg (process/child-root-config cfg kind)
         sid (or id (artifacts/session-id))
         dir (or dir (artifacts/path (:runs-dir cfg) sid))
         state (artifacts/new-state! {:dir dir
                                      :id sid
                                      :cache-id cache-id
                                      :kind (or kind :root)
                                      :provider (process/provider-shape effective-cfg)
                                      :parent parent})
         ns-sym (runtime/session-ns-symbol sid)
         ops (install! state effective-cfg ns-sym)
         base (if (= :child kind) prompt/child-prompt prompt/system-prompt)]
     (artifacts/add-message! state :system (if (str/blank? (str overlay))
                                             base
                                             (str base "\n\n" overlay)))
     (session-handle state effective-cfg ns-sym ops))))

(defn run-turn! [session user-message]
  (let [{:keys [state cfg ns-sym]} session
        status (get-in @state [:session :session/status])]
    (when-not (= :running status)
      (throw (ex-info "Session is not running"
                      {:error/type :fractal/session-not-running
                       :session/status status})))
    (process/run-turn-on-state! state cfg ns-sym user-message)))

(defn stop-session! [session]
  (let [state (:state session)]
    (artifacts/update-status! state :stopped)
    (artifacts/add-event! state {:event/type :session-stopped
                                 :session/id (get-in @state [:session :session/id])})
    (:session @state)))

(defn- restored-session!
  [cfg source-dir {:keys [dir id kind cache-id lineage-kind lineage-parents turn parent]}]
  (let [cfg (process/config cfg)
        source-dir (artifacts/path source-dir)
        snapshot-row (snapshot/require-snapshot source-dir {:turn turn})
        snapshot-blob (snapshot/require-snapshot-blob source-dir snapshot-row)
        source-fingerprint (snapshot/session-fingerprint source-dir)
        sid (or id (artifacts/session-id))
        target-dir (or dir (artifacts/path (:runs-dir cfg) sid))
        kind' (or kind :root)
        effective-cfg (process/child-root-config cfg kind')
        state (artifacts/new-state! {:dir target-dir
                                     :id sid
                                     :cache-id cache-id
                                     :kind kind'
                                     :provider (process/provider-shape effective-cfg)
                                     :parent parent})
        ns-sym (runtime/session-ns-symbol sid)
        ops (install! state effective-cfg ns-sym)]
    (process/restore-state-from-snapshot!
     state
     source-dir
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
                                 :restore/strategy :snapshot-vars
                                 :snapshot/id (:snapshot/id snapshot-row)
                                 :turn/id (:snapshot/turn-id snapshot-row)})
    (artifacts/flush! state)
    (session-handle state effective-cfg ns-sym ops)))

(defn resume-session!
  ([cfg source-dir] (resume-session! cfg source-dir {}))
  ([cfg source-dir opts]
   (let [cfg (process/config cfg)
         sid (or (:id opts) (:session opts) (artifacts/session-id))
         target-dir (or (:dir opts) (artifacts/path (:runs-dir cfg) sid))]
     (restored-session! cfg source-dir
                        (merge opts
                               {:id sid
                                :dir target-dir
                                :kind :root
                                :lineage-kind :resume
                                :lineage-parents [{:parent/kind :resumed-from
                                                   :parent/path (str (artifacts/path source-dir))}]})))))

(defn fork-session!
  ([cfg old-dir new-dir] (fork-session! cfg old-dir new-dir {}))
  ([cfg old-dir new-dir opts]
   (restored-session! cfg old-dir
                      (merge opts
                             {:dir new-dir
                              :kind :root
                              :lineage-kind :fork
                              :lineage-parents [{:parent/kind :forked-from
                                                 :parent/path (str (artifacts/path old-dir))}]}))))
