(ns fractal.engine.session
  "L4 · session lifecycle + the turn-lock (07 §2/§5). start-session! is the SOLE
   composition root that constructs the adapter and stashes cfg+adapter on the
   handle. run-turn!/run-turn-async! gate (stop/max-turns) before the CAS, then
   drive the step loop. commit-turn!/finalize-turn! live in session-loop (GD4)."
  (:require [fractal.engine.adapter.fake :as fake]
            [fractal.engine.adapter.sdk :as sdk]
            [fractal.engine.capability :as capability]
            [fractal.engine.catalog :as catalog]
            [fractal.engine.compaction :as compaction]
            [fractal.engine.kernel :as kernel]
            [fractal.engine.live :as live]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.recursion :as recursion]
            [fractal.engine.session-loop :as sloop]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]
            [fractal.engine.store.slots :as slots]
            [fractal.engine.store.sqlite :as sqlite]
            [fractal.engine.time :as time]))

;; Forward declarations: the rlm host fns spawn child sessions + run their loop,
;; but they are injected DURING start-session!/spawn-child!, before those fns are
;; textually defined. recursion never requires session (no cycle); session
;; injects these as an env of closures (dependency inversion, 03/Phase-3 gotcha).
(declare run-turn! spawn-child! spawn-attached!)

;; ---------------------------------------------------------------------------
;; start-session! (the composition root)
;; ---------------------------------------------------------------------------

(defn- gen-id [] (str "s-" (java.util.UUID/randomUUID)))

(defn- resolve-profile-for-session [cfg opts]
  (let [base (:capability cfg)]                ; the validated default PROFILE VALUE
    (if-let [ov (:capability opts)]
      (capability/resolve-override base ov)    ; per-session override, clamped + loosening-rejected
      base)))

(defn- resolve-provider
  "The session's provider: `:fake` for the fake adapter; an EXPLICIT cfg
   `:provider` override when set (it WINS over catalog model-id resolution —
   required for codex OAuth, whose model ids catalog-resolve to :openai); else
   the catalog-resolved provider for cfg's model (throws on an unknown model)."
  [cfg]
  (cond
    (= :fake (:adapter cfg)) :fake
    (:provider cfg)          (:provider cfg)
    :else
    (let [p (:provider (catalog/provider-from-model-id (:model cfg)))]
      (when-not p
        (throw (ex-info (str "could not resolve a provider for model: " (:model cfg))
                        {:error/type :config/unknown-model :model (:model cfg)})))
      p)))

(defn- build-adapter [cfg provider]
  (if (= :fake (:adapter cfg))
    (fake/fake-adapter (:fake/respond cfg))
    (sdk/sdk-adapter provider (:provider/config cfg))))

(defn- build-leaf
  "Resolve the leaf adapter + model for a session (Phase 3). Leaves default to
   the root adapter/model; an explicit :leaf-provider or a differing :leaf-model
   builds a DEDICATED leaf adapter so leaves can target a cheaper model/provider.
   Returns {:leaf-adapter … :leaf-model …} for the handle."
  [cfg root-provider root-adapter]
  (let [leaf-model    (or (:leaf-model cfg) (:model cfg))
        leaf-provider (or (:leaf-provider cfg) root-provider)
        leaf-adapter  (if (and (= leaf-provider root-provider) (= leaf-model (:model cfg)))
                        root-adapter
                        (build-adapter (assoc cfg :model leaf-model :provider leaf-provider)
                                       leaf-provider))]
    {:leaf-adapter leaf-adapter :leaf-model leaf-model}))

;; ---------------------------------------------------------------------------
;; SCI ctx assembly — harness-gated host fns (the hot-swap)
;; ---------------------------------------------------------------------------

(defn- live-meta [cfg]
  {:fractal.engine.store.memory/live {:bound (:live/queue-bound cfg) :drop (:live/drop cfg)}})

(defn- recursion-env
  "The spawn/run/stop closures recursion needs (dependency inversion — recursion
   never requires session). A child's events stay durable in the store; only its
   live dispatch is stopped on return."
  []
  {:spawn-child! spawn-child!
   :spawn-attached! spawn-attached!
   :run-turn!    run-turn!
   :stop-child!  (fn [child] (slots/stop-dispatch! (:store child) (:session-id child)))})

(defn- assemble-engine-fns
  "The host-fn impl map for a handle's harness mode (the hot-swap): :clojure ⇒
   {:FINAL :inspect} (Phase-1 behavior); :rlm ⇒ those + the six recursion fns.
   The capability profile's :engine-fns then GATES which actually inject
   (capability/sci-opts select-keys; :locked-down drops lm/rlm)."
  [handle]
  (let [base (kernel/engine-fn-impls)]
    (if (= :rlm (:harness (:cfg handle)))
      (merge base (recursion/engine-fns handle (recursion-env)))
      base)))

(defn- init-session-ctx!
  "Build + install the session SCI ctx from its resolved profile + the
   harness-selected host fns, AFTER the handle (cfg/adapter/capability/cache-id)
   exists. The rlm fns close over the handle and read cfg/adapter/store at CALL
   time, so the :sci-ctx atom may still be nil here (the wiring chicken-and-egg)."
  [handle profile]
  (reset! (:sci-ctx handle)
          (kernel/new-ctx (:session-id handle) profile (assemble-engine-fns handle))))

(defn- build-store
  "Pick + construct the SessionStore off cfg :store (GD5 — the composition root,
   not a loop change). :memory (default) is the in-memory store; :sqlite is the
   durable store rooted at cfg :store/dir (events.db + blobs/). Both slot under the
   same port (02 §4)."
  [cfg]
  (case (:store cfg)
    :sqlite (sqlite/sqlite-store {:dir (:store/dir cfg)
                                  :live-opts {:bound (:live/queue-bound cfg)
                                              :drop  (:live/drop cfg)}})
    (mem/memory-store)))

(defn- close-store-quietly!
  "Release a store opened by build-store when composition fails before a handle is
   returned — otherwise the sqlite JDBC connection would leak (the caller never got
   a handle to close). No-op for the in-memory store (GC reclaims it)."
  [store]
  (when (instance? fractal.engine.store.sqlite.SqliteStore store)
    (try (sqlite/close! store) (catch Throwable _ nil))))

(defn start-session!
  ([cfg] (start-session! cfg {}))
  ([cfg opts]
   (let [store (build-store cfg)]
     (try
       (let [sid      (or (:id opts) (gen-id))
             profile  (resolve-profile-for-session cfg opts)
             provider (resolve-provider cfg)
             adapter  (build-adapter cfg provider)
             session-map (with-meta
                           {:session/id             sid
                            :session/status         :running
                            :session/created-at     (time/now-str)
                            :session/provider       provider
                            :session/model          (:model cfg)
                            :session/capability     (:capability/name profile)
                            :session/cache-id       sid
                            :session/system-overlay (:system-overlay opts)}
                           (live-meta cfg))
             handle0 (store/create-session! store session-map)
             handle  (merge handle0
                            {:cfg cfg :adapter adapter :capability profile :cache-id sid}
                            (build-leaf cfg provider adapter))]
         (init-session-ctx! handle profile)
         (store/append-event! store sid {:event/type :session/started :session session-map})
         handle)
       (catch Throwable e
         (close-store-quietly! store)
         (throw e))))))

(defn resume-session!
  "^:alpha (06 §2) — durably REOPEN a persisted session (`:store :sqlite` only) —
   the cross-process payoff of persistence. Builds the store on cfg's :store/dir,
   FOLDS the durable event log to rebuild the view cache (create-session!'s recovery
   path — deterministic re-fold reproduces the same ids, 02 §9), rebuilds the SCI
   ctx via kernel/new-ctx, and RESTORES the live REPL vars from the latest :vars-ref
   (kernel/restore-vars!) so a new turn can use a var def'd before the reopen. Does
   NOT append :session/started — the session already exists."
  ([cfg sid] (resume-session! cfg sid {}))
  ([cfg sid opts]
   (when-not (= :sqlite (:store cfg))
     (throw (ex-info "resume-session! requires :store :sqlite"
                     {:error/type :config/unsupported-store :store (:store cfg)})))
   (let [store (build-store cfg)]
     (try
       (let [profile  (resolve-profile-for-session cfg opts)
             provider (resolve-provider cfg)
             adapter  (build-adapter cfg provider)
             handle0  (store/create-session! store {:session/id sid})   ; folds the durable log
             view     (store/current-view store sid)
             cache-id (or (:session/cache-id (:session view)) sid)      ; cache affinity (08)
             handle   (merge handle0
                             {:cfg cfg :adapter adapter :capability profile :cache-id cache-id}
                             (build-leaf cfg provider adapter))]
         (when-not (:session view)
           (throw (ex-info (str "no persisted session to resume: " sid)
                           {:error/type :fractal/unknown-session :session/id sid})))
         (init-session-ctx! handle profile)
         (let [head (store/current-head view)
               snap (payload-io/read-payload store (or (:head/vars-ref head) (:vars-ref view)))]
           (when (and (map? snap) (:vars snap))
             (kernel/restore-vars! @(:sci-ctx handle) sid snap)))
         handle)
       (catch Throwable e
         (close-store-quietly! store)
         (throw e))))))

;; ---------------------------------------------------------------------------
;; Turn gates + lifecycle
;; ---------------------------------------------------------------------------

(defn- session-status [handle]
  (get-in (store/current-view (:store handle) (:session-id handle)) [:session :session/status]))

(defn- reject-if-stopped!
  "Pre-CAS: a stop-requested/stopped/error session ⇒ an :error TurnResult
   (never a throw, GD27c)."
  [handle]
  (when (#{:stop-requested :stopped :error} (session-status handle))
    {:status     :error
     :session/id (:session-id handle)
     :turn/id    nil
     :error      {:error/type :fractal/session-stopped
                  :error/message (str "session is " (name (session-status handle)))}}))

(defn- check-turn-limit!
  "Pre-CAS: :max-turns reached ⇒ throw :fractal/session-turn-limit (GD17)."
  [handle]
  (let [cfg (:cfg handle)
        max-turns (:max-turns cfg)]
    (when (and max-turns
               (>= (count (:turns (store/current-view (:store handle) (:session-id handle)))) max-turns))
      (throw (ex-info "session turn limit reached"
                      {:error/type :fractal/session-turn-limit :max-turns max-turns})))))

(defn open-turn!
  "Append the :user message (with :message/turn-id), then :turn/started carrying
   the same turn id + :turn/user-message-id; return the turn id (GD26)."
  [handle msg]
  (let [store (:store handle) sid (:session-id handle)
        turn-id (store/peek-next-id store sid :turn)
        umsg (store/append-event! store sid
               {:event/type :message/appended
                :message {:message/role :user
                          :message/turn-id turn-id
                          :message/content-or-ref (payload-io/maybe-intern store msg {:payload/kind :message})}})]
    (store/append-event! store sid
      {:event/type :turn/started
       :turn {:turn/status :running
              :turn/started-at (time/now-str)
              :turn/user-message-id (get-in umsg [:message :message/id])}})
    turn-id))

(defn- maybe-compact! [handle]
  (when (compaction/should-compact? (store/current-view (:store handle) (:session-id handle)) (:cfg handle))
    (compaction/compact-session! handle)))

(defn run-turn!
  "BLOCKING. Gate (stop/max-turns) → CAS the turn-lock → compact if flagged →
   open the turn → run the loop → release → return a TurnResult (06 §5)."
  [handle msg]
  (live/check-not-reentrant! (:session-id handle))
  (or (reject-if-stopped! handle)
      (do
        (check-turn-limit! handle)
        (when-not (compare-and-set! (:busy handle) false true)
          (throw (ex-info "turn in flight" {:error/type :fractal/turn-in-flight})))
        (try
          (maybe-compact! handle)
          (sloop/run-loop! handle (open-turn! handle msg))
          (finally (reset! (:busy handle) false))))))

(defn- error-result [handle turn-id e]
  (let [em (kernel/err->map e)
        status (case (:error/type em)
                 :fractal/deadline  :timeout
                 :fractal/max-steps :budget-exceeded
                 :error)]
    (sloop/finalize-turn! handle turn-id status em)))

(defn run-turn-async!
  "Background. Same pre-CAS gate on the caller thread; open the turn
   SYNCHRONOUSLY (real store-assigned id), run the loop on a daemon future.
   Releases busy BEFORE delivering the promise (the re-invoke race, GD27b)."
  [handle msg]
  (live/check-not-reentrant! (:session-id handle))
  (if-let [stopped (reject-if-stopped! handle)]
    {:turn/id nil :promise (doto (promise) (deliver stopped))}
    (do
      (check-turn-limit! handle)
      (when-not (compare-and-set! (:busy handle) false true)
        (throw (ex-info "turn in flight" {:error/type :fractal/turn-in-flight})))
      (let [tid (try
                  (maybe-compact! handle)
                  (open-turn! handle msg)
                  (catch Throwable e (reset! (:busy handle) false) (throw e)))
            p (promise)]
        (future
          (try
            (let [res (try (sloop/run-loop! handle tid)
                           (catch Throwable e (error-result handle tid e)))]
              (reset! (:busy handle) false)            ; ⛔ release BEFORE delivering
              (deliver p res))
            (finally
              (reset! (:busy handle) false)            ; idempotent backstop
              (when-not (realized? p)
                (deliver p {:status :error :session/id (:session-id handle) :turn/id tid
                            :error {:error/type :fractal/internal
                                    :error/message "async turn failed to settle"}})))))
        {:turn/id tid :promise p}))))

;; ---------------------------------------------------------------------------
;; stop / compact
;; ---------------------------------------------------------------------------

(defn- await-idle! [handle]
  (loop [] (when @(:busy handle) (Thread/sleep 5) (recur))))

(defn stop-session!
  "Request stop (idempotent, safe from a finally/shutdown hook). Idle ⇒ also
   append :session/stopped now; in-flight ⇒ the loop appends it at the next step
   boundary; :wait? blocks on the turn-lock then appends :session/stopped."
  ([handle] (stop-session! handle {}))
  ([handle {:keys [wait?]}]
   (let [store (:store handle) sid (:session-id handle)]
     (store/append-event! store sid {:event/type :session/stop-requested})
     (when wait? (await-idle! handle))
     (when (and (not @(:busy handle))
                (not= :stopped (session-status handle)))
       (store/append-event! store sid {:event/type :session/stopped}))
     ;; Only stop the dispatcher once the session is actually idle — stopping it
     ;; mid-turn would drop the loop's still-pending durable deliveries (incl.
     ;; the terminal :session/stopped it appends at the next step boundary). An
     ;; in-flight-no-wait stop leaves the daemon running (JVM-exit-safe).
     (when (or wait? (not @(:busy handle)))
       (slots/stop-dispatch! store sid))
     handle)))

(defn close-session!
  "Release a handle's store resources: stop its live dispatch and, for the sqlite
   store, CLOSE the JDBC connection — so the session can later be durably reopened
   with resume-session! (as a process restart would). Memory: just stops the
   dispatch. Safe from a finally/shutdown hook."
  [handle]
  (let [store (:store handle) sid (:session-id handle)]
    (if (instance? fractal.engine.store.sqlite.SqliteStore store)
      (sqlite/close! store)
      (slots/stop-dispatch! store sid)))
  handle)

(defn compact-session!
  "Force compaction now: CAS the turn-lock (throw :fractal/turn-in-flight if a
   turn is in flight), compact under the held lock, release in a finally."
  [handle]
  (live/check-not-reentrant! (:session-id handle))
  (when-not (compare-and-set! (:busy handle) false true)
    (throw (ex-info "turn in flight" {:error/type :fractal/turn-in-flight})))
  (try
    (compaction/compact-session! handle)
    handle
    (finally (reset! (:busy handle) false))))

;; ---------------------------------------------------------------------------
;; spawn-child! — the recursion composition root (Phase 3)
;; ---------------------------------------------------------------------------

(defn spawn-child!
  "Spawn a FRESH child session that REUSES the parent's store (children are just
   more sessions in the SAME memory/sqlite store — the port is unchanged). The
   composition root for a recursion:
   - capability = inherit-and-clamp: clamp(parent-resolved, child-override)
     (engine-default is ROOT-ONLY, 04 §3). With no override, child = parent — so
     a :locked-down parent's clamp keeps lm/rlm dropped, and a :default parent's
     child cannot exceed :default (the escalation is closed).
   - model/provider default to the root's (cfg :child-model / :child-provider).
   - harness = :rlm, so the child gets the six fns and can itself recurse
     (nesting works: independent ctxs, one per session).
   The child's REPL ctx + adapter + leaf + cache-id (FRESH per child, 08) are
   stashed on its handle exactly as start-session! does. Returns the child
   handle; the caller (recursion/child-call) drives run-turn! on it."
  [parent-handle child-opts]
  (let [store        (:store parent-handle)
        parent-cfg   (:cfg parent-handle)
        parent-prof  (:capability parent-handle)
        override     (:capability child-opts)
        child-prof   (if override
                       (capability/clamp parent-prof
                                         (capability/validate-profile!
                                           (capability/resolve-profile override)))
                       parent-prof)
        child-sid    (gen-id)
        child-model  (or (:child-model parent-cfg) (:model parent-cfg))
        ;; Resolve the child provider AGAINST THE CHILD MODEL, not the parent's: an
        ;; explicit :child-provider wins; else inherit the parent's explicit :provider
        ;; ONLY when the child model is the parent's (the codex case — same model, same
        ;; provider); otherwise catalog-resolve from the child model.
        child-prov   (or (:child-provider parent-cfg)
                         (resolve-provider
                           (assoc parent-cfg
                                  :model    child-model
                                  :provider (when (= child-model (:model parent-cfg))
                                              (:provider parent-cfg)))))
        child-cfg    (assoc parent-cfg
                            :model          child-model
                            :provider       child-prov
                            :context-window (or (catalog/context-window child-model) :unknown)
                            :harness        :rlm
                            ;; leaf model/provider re-resolve against THIS child (not the
                            ;; parent's leaf overrides) — children's leaves default to the
                            ;; child's own model/provider (build-leaf below).
                            :leaf-model     child-model
                            :leaf-provider  nil)
        child-adpt   (build-adapter child-cfg child-prov)
        session-map  (with-meta
                       {:session/id             child-sid
                        :session/status         :running
                        :session/created-at     (time/now-str)
                        :session/provider       child-prov
                        :session/model          child-model
                        :session/capability     (:capability/name child-prof)
                        :session/cache-id       child-sid
                        :session/kind           :child
                        :session/system-overlay (:system-overlay child-opts)}
                       (live-meta child-cfg))
        handle0      (store/create-session! store session-map)
        child        (merge handle0
                            {:cfg child-cfg :adapter child-adpt
                             :capability child-prof :cache-id child-sid}
                            (build-leaf child-cfg child-prov child-adpt))]
    (init-session-ctx! child child-prof)
    (store/append-event! store child-sid {:event/type :session/started :session session-map})
    child))

(defn- kw-capability [x]
  (cond
    (keyword? x) x
    (string? x)  (keyword x)
    :else        x))

(defn- source-session-id [handle opts]
  (or (:session/id opts)
      (:session/id handle)
      (:head/session handle)
      (:session-id handle)
      (when (string? handle) handle)))

(defn- source-head-id [view handle opts]
  (or (:head/id opts)
      (:head/id handle)
      (:current-head view)))

(defn- resolve-attach-source
  "Resolve an attach handle to a loaded source session view + immutable source
   head. A session handle/map means 'use its current head'; a head map or
   opts :head/id selects that immutable head."
  [store handle opts]
  (let [sid (source-session-id handle opts)]
    (when-not sid
      (throw (ex-info "attach-rlm requires a session/head handle"
                      {:error/type :fractal/attach-source-missing})))
    ;; SqliteStore: folds the durable source log into an in-process slot when
    ;; needed. MemoryStore: returns an existing slot or an empty unknown session.
    (store/create-session! store {:session/id sid})
    (let [view (store/current-view store sid)
          hid  (source-head-id view handle opts)
          head (store/head-by-id view hid)]
      (when-not (:session view)
        (throw (ex-info (str "unknown attach source session: " sid)
                        {:error/type :fractal/unknown-session :session/id sid})))
      (when-not head
        (throw (ex-info "attach source has no selected head"
                        {:error/type :fractal/unknown-head
                         :session/id sid :head/id hid})))
      {:session/id sid :view view :head head})))

(defn- source-profile [source]
  (capability/validate-profile!
    (capability/resolve-profile
      (kw-capability (get-in source [:view :session :session/capability])))))

(defn spawn-attached!
  "Phase 4 attach composition root. Materializes a FRESH derived child from a
   selected immutable source head (the source session is never advanced). The
   child's SCI ctx is restored from the source head's vars snapshot before its
   task turn runs. Capability is clamped by both caller and source; a caller may
   not drive a more-privileged source session."
  [parent-handle source-handle child-opts]
  (let [store        (:store parent-handle)
        parent-cfg   (:cfg parent-handle)
        parent-prof  (:capability parent-handle)
        source       (resolve-attach-source store source-handle child-opts)
        source-prof  (source-profile source)]
    (when-not (capability/profile<=? source-prof parent-prof)
      (throw (ex-info "attach source is more privileged than caller"
                      {:error/type :fractal/attach-capability-rejected
                       :source/session-id (:session/id source)
                       :source/capability (:capability/name source-prof)
                       :caller/capability (:capability/name parent-prof)})))
    (let [base-prof   (capability/clamp parent-prof source-prof)
          override    (:capability child-opts)
          child-prof  (if override
                        (capability/resolve-override base-prof override)
                        base-prof)
          child-sid   (gen-id)
          child-model (or (:child-model parent-cfg) (:model parent-cfg))
          child-prov  (or (:child-provider parent-cfg)
                          (resolve-provider
                            (assoc parent-cfg
                                   :model child-model
                                   :provider (when (= child-model (:model parent-cfg))
                                               (:provider parent-cfg)))))
          child-cfg   (assoc parent-cfg
                             :model child-model
                             :provider child-prov
                             :context-window (or (catalog/context-window child-model) :unknown)
                             :harness :rlm
                             :leaf-model child-model
                             :leaf-provider nil)
          child-adpt  (build-adapter child-cfg child-prov)
          source-head (:head source)
          session-map (with-meta
                        {:session/id             child-sid
                         :session/status         :running
                         :session/created-at     (time/now-str)
                         :session/provider       child-prov
                         :session/model          child-model
                         :session/capability     (:capability/name child-prof)
                         :session/cache-id       child-sid
                         :session/kind           :attached-child
                         :session/source-session (:session/id source)
                         :session/source-head-id (:head/id source-head)
                         :session/system-overlay (:system-overlay child-opts)}
                        (live-meta child-cfg))
          handle0     (store/create-session! store session-map)
          child       (merge handle0
                             {:cfg child-cfg :adapter child-adpt
                              :capability child-prof :cache-id child-sid
                              :attach/source {:session/id (:session/id source)
                                              :head source-head}}
                             (build-leaf child-cfg child-prov child-adpt))
          snap        (payload-io/read-payload store (:head/vars-ref source-head))]
      (init-session-ctx! child child-prof)
      (when (and (map? snap) (:vars snap))
        (kernel/restore-vars! @(:sci-ctx child) child-sid snap))
      (store/append-event! store child-sid {:event/type :session/started :session session-map})
      child)))
