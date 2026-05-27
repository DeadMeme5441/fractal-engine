(ns fractal-engine.session
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.process :as process]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.time :as time]))

(defn latest-complete-snapshot [state]
  (last (filter #(= :complete (:snapshot/status %)) (:snapshots @state))))

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
  ([cfg] (start-session! cfg {}))
  ([cfg {:keys [dir id kind parent]}]
   (let [cfg (process/config cfg)
         effective-cfg (process/child-root-config cfg kind)
         sid (or id (artifacts/session-id))
         dir (or dir (artifacts/path (:runs-dir cfg) sid))
         state (artifacts/new-state! {:dir dir
                                      :id sid
                                      :kind (or kind :root)
                                      :provider (process/provider-shape effective-cfg)
                                      :parent parent})
         ns-sym (runtime/session-ns-symbol sid)
         ops (install! state effective-cfg ns-sym)]
     (artifacts/add-message! state :system (if (= :child kind)
                                             prompt/child-prompt
                                             prompt/system-prompt))
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

(defn resume-session! [cfg dir]
  (let [cfg (process/config cfg)
        state (artifacts/load-state! dir)
        session-id (get-in @state [:session :session/id])
        ns-sym (runtime/session-ns-symbol session-id)
        ops (install! state cfg ns-sym)
        snapshot (latest-complete-snapshot state)
        report (when snapshot
                 (runtime/restore-snapshot! state ns-sym ops snapshot))]
    (swap! state update :session assoc
           :session/status :running
           :session/ended-at nil
           :session/resumed-from {:resume/dir (str dir)
                                  :resume/snapshot-id (:snapshot/id snapshot)
                                  :resume/at (time/now-str)})
    (artifacts/add-event! state {:event/type :session-resumed
                                 :session/id session-id
                                 :resume/report report})
    (artifacts/flush! state)
    (session-handle state cfg ns-sym ops)))

(defn fork-session! [cfg old-dir new-dir]
  (let [cfg (process/config cfg)
        old (artifacts/load-state! old-dir)
        snapshot (latest-complete-snapshot old)
        sid (artifacts/session-id)
        state (artifacts/new-state! {:dir new-dir
                                     :id sid
                                     :kind :root
                                     :provider (process/provider-shape cfg)
                                     :forked-from {:fork/source-dir (str old-dir)
                                                   :fork/snapshot-id (:snapshot/id snapshot)}})
        ns-sym (runtime/session-ns-symbol sid)
        ops (install! state cfg ns-sym)]
    (swap! state assoc
           :messages (:messages @old)
           :turns (:turns @old)
           :counters (merge (:counters @state)
                            {:message (apply max 0 (map :message/id (:messages @old)))
                             :turn (apply max 0 (map :turn/id (:turns @old)))}))
    (when snapshot
      (doseq [[sym ref] (:snapshot/vars snapshot)]
        (let [value (artifacts/read-ref (:dir @old) ref)]
          (when-not (= ::artifacts/missing value)
            (intern (the-ns ns-sym) sym value)))))
    (artifacts/add-event! state {:event/type :session-forked
                                 :session/id sid
                                 :fork/source-dir (str old-dir)})
    (artifacts/flush! state)
    (session-handle state cfg ns-sym ops)))

