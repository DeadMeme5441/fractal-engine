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
            [fractal.engine.session-loop :as sloop]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]
            [fractal.engine.time :as time]))

;; ---------------------------------------------------------------------------
;; start-session! (the composition root)
;; ---------------------------------------------------------------------------

(defn- gen-id [] (str "s-" (java.util.UUID/randomUUID)))

(defn- resolve-profile-for-session [cfg opts]
  (let [base (:capability cfg)]                ; the validated default PROFILE VALUE
    (if-let [ov (:capability opts)]
      (capability/resolve-override base ov)    ; per-session override, clamped + loosening-rejected
      base)))

(defn start-session!
  ([cfg] (start-session! cfg {}))
  ([cfg opts]
   (let [sid     (or (:id opts) (gen-id))
         store   (mem/memory-store)
         profile (resolve-profile-for-session cfg opts)
         provider (if (= :fake (:adapter cfg))
                    :fake
                    (let [p (:provider (catalog/provider-from-model-id (:model cfg)))]
                      (when-not p
                        (throw (ex-info (str "could not resolve a provider for model: " (:model cfg))
                                        {:error/type :config/unknown-model :model (:model cfg)})))
                      p))
         session-map (with-meta
                       {:session/id             sid
                        :session/status         :running
                        :session/created-at     (time/now-str)
                        :session/provider       provider
                        :session/model          (:model cfg)
                        :session/capability     (:capability/name profile)
                        :session/cache-id       sid
                        :session/system-overlay (:system-overlay opts)}
                       {:fractal.engine.store.memory/live
                        {:bound (:live/queue-bound cfg) :drop (:live/drop cfg)}})
         handle0 (store/create-session! store session-map)
         adapter (if (= :fake (:adapter cfg))
                   (fake/fake-adapter (:fake/respond cfg))
                   (sdk/sdk-adapter provider (:provider/config cfg)))
         handle  (assoc handle0 :cfg cfg :adapter adapter)]
     (reset! (:sci-ctx handle) (kernel/new-ctx sid profile (kernel/engine-fn-impls)))
     (store/append-event! store sid {:event/type :session/started :session session-map})
     handle)))

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
       (mem/stop-dispatch! store sid))
     handle)))

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
