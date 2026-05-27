(ns fractal-engine.resume
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.process :as process]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.runtime :as runtime]))

(defn latest-complete-snapshot [state]
  (last (filter #(= :complete (:snapshot/status %)) (:snapshots @state))))

(defn resume!
  [cfg dir user-message]
  (let [state (artifacts/load-state! dir)
        session-id (:session/id (:session @state))
        ns-sym (runtime/session-ns-symbol session-id)
        ops (process/make-ops state (process/config cfg) ns-sym)
        snapshot (latest-complete-snapshot state)
        report (when snapshot
                 (runtime/restore-snapshot! state ns-sym ops snapshot))]
    (swap! state update :session assoc
           :session/status :running
           :session/resumed-from {:resume/dir (str dir)
                                  :resume/snapshot-id (:snapshot/id snapshot)})
    (artifacts/flush! state)
    (artifacts/add-message! state :user user-message)
    (artifacts/add-message! state :observation (str "Resume report: " (pr-str report)))
    (process/run-process! (process/config cfg) {:dir dir
                                                :resume-state state
                                                :ns-sym ns-sym})))

(defn fork!
  [cfg old-dir new-dir user-message]
  (let [old (artifacts/load-state! old-dir)
        snapshot (latest-complete-snapshot old)
        sid (artifacts/session-id)
        state (artifacts/new-state! {:dir new-dir
                                     :id sid
                                     :kind :root
                                     :provider (process/provider-shape (process/config cfg))
                                     :forked-from {:fork/source-dir (str old-dir)
                                                   :fork/snapshot-id (:snapshot/id snapshot)}})
        ns-sym (runtime/session-ns-symbol sid)
        ops (process/make-ops state (process/config cfg) ns-sym)]
    (artifacts/add-message! state :system prompt/system-prompt)
    (doseq [m (:messages @old)]
      (when (not= :system (:message/role m))
        (artifacts/add-message! state (:message/role m) (:message/content m))))
    (when snapshot
      (runtime/restore-snapshot! old ns-sym ops snapshot)
      (doseq [[sym ref] (:snapshot/vars snapshot)]
        (intern (the-ns ns-sym) sym (artifacts/read-ref (:dir @old) ref))))
    (artifacts/add-message! state :user user-message)
    (process/run-process! (process/config cfg) {:dir new-dir
                                                :resume-state state
                                                :ns-sym ns-sym})))

