(ns fractal-engine.session
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.lineage :as lineage]
            [fractal-engine.process :as process]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.rehydrate :as rehydrate]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.session-store :as store]
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
  ([cfg {:keys [dir id kind parent cache-id]}]
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
         ops (install! state effective-cfg ns-sym)]
     (lineage/write-lineage! dir (lineage/root-lineage dir sid (or kind :root)))
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

(defn- source-info [source-dir source]
  {:source/path (str source-dir)
   :source/session-id (get-in source [:session :session/id])
   :source/artifact-version (get-in source [:session :session/artifact-version])
   :source/fingerprint (:fingerprint source)})

(defn- derived-lineage [kind sid source-dir source parent-kind]
  {:lineage/version 1
   :lineage/session-id sid
   :lineage/kind kind
   :lineage/source (source-info source-dir source)
   :lineage/parents [{:parent/kind parent-kind
                      :parent/path (str source-dir)
                      :parent/fingerprint (:fingerprint source)}]
   :lineage/created-at (time/now-str)
   :lineage/events []})

(defn derived-session!
  [cfg source-dir {:keys [kind parent-kind dir id cache-id parent]}]
  (let [cfg (process/config cfg)
        source (store/load-session source-dir)]
    (when (empty? (:session source))
      (throw (ex-info "Source session is invalid"
                      {:error/type :resume/source-invalid
                       :source/path (str source-dir)})))
    (let [sid (or id (artifacts/session-id))
          target-dir (or dir (artifacts/path (:runs-dir cfg) sid))
          effective-cfg (process/child-root-config cfg parent-kind)
          state (artifacts/new-state! {:dir target-dir
                                     :id sid
                                     :cache-id cache-id
                                     :kind (or parent-kind :root)
                                     :provider (process/provider-shape effective-cfg)
                                     :parent parent})
          ns-sym (runtime/session-ns-symbol sid)
          ops (install! state effective-cfg ns-sym)
          session (session-handle state effective-cfg ns-sym ops)
          lineage (derived-lineage kind sid source-dir source parent-kind)]
      (rehydrate/reset-state-from-source! state source)
      (swap! state update :session assoc
             :session/id sid
             :session/kind (or parent-kind :root)
             :session/status :running
             :session/ended-at nil
             :session/cache-id (or cache-id sid)
             :session/cache (cache/session-cache (or cache-id sid))
             :session/provider (process/provider-shape effective-cfg)
             :session/parent parent
             :session/derived-from (source-info source-dir source)
             :session/lineage-kind kind)
      (lineage/write-lineage! target-dir lineage)
      (artifacts/flush! state)
      (let [report (rehydrate/rehydrate! effective-cfg source-dir session {})]
        (lineage/append-lineage-event! target-dir {:event/type :rehydration-end
                                                   :event/status (:rehydration/status report)
                                                   :event/source (str source-dir)})
        (artifacts/add-event! state {:event/type (case kind
                                                   :resume :session-resumed
                                                   :fork :session-forked
                                                   :attached-child :session-attached
                                                   :session-derived)
                                     :session/id sid
                                     :source/path (str source-dir)
                                     :source/fingerprint (:fingerprint source)})
        (artifacts/flush! state)
        session))))

(defn resume-session!
  ([cfg source-dir] (resume-session! cfg source-dir {}))
  ([cfg source-dir opts]
   (derived-session! cfg source-dir (merge {:kind :resume
                                            :parent-kind :root}
                                           opts))))

(defn fork-session! [cfg old-dir new-dir]
  (derived-session! cfg old-dir {:kind :fork
                                 :parent-kind :root
                                 :dir new-dir
                                 :id (some-> new-dir java.io.File. .getName)}))
