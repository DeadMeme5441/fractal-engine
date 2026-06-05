(ns fractal-engine.process
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.concurrent :as concurrent]
            [fractal-engine.context :as context]
            [fractal-engine.leaf :as leaf]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provider :as provider]
            [fractal-engine.provider-call :as provider-call]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.session-invocation :as session-invocation]
            [fractal-engine.session-loop :as session-loop]
            [fractal-engine.session-model :as session-model]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.time :as time]))

(declare run-process! run-turn-on-state! child-root-config make-ops)

(defn config
  ([] (config {}))
  ([m]
   (let [base {:runs-dir "runs"
               :max-turns 25
               :max-fanout 50
               :max-leaf-concurrency 50
               :call-timeout-ms 120000
               ;; Explicit prompt-cache ttl. "1h" keeps the root transcript cached
               ;; across long inter-step gaps (leaf fan-out, child recursion) instead
               ;; of relying on Anthropic's implicit ephemeral default, which dropped
               ;; from 1h to 5m on 2026-03-06. Override with --cache-ttl 5m for
               ;; cost-sensitive short jobs. See fractal-engine.cache/default-ttl.
               :cache-ttl cache/default-ttl
               :context context/default-policy
               :models provider/default-models
               ;; Retry transient transport failures by default. The SDK classifies
               ;; errors before retrying (`:retry true` -> llm.sdk.retry/default-policy):
               ;; it retries network/timeout/server/overloaded/rate-limit/unknown with
               ;; jittered backoff and refuses auth/invalid-request/quota, so it never
               ;; burns calls re-failing a broken request. This is what survives the
               ;; vertex-gemini first-call EOF. Note: `:call-timeout-ms` wraps the whole
               ;; retry loop (provider-call/call-provider!), so the deadline is total wall-clock
               ;; including backoff, not per attempt. Set :retry false for one-shot.
               :retry true}
         models (merge-with merge provider/default-models (:models m))
         context-policy (context/normalize-policy
                         (merge (:context base) (:context m)))
         cfg (dissoc (assoc (merge (dissoc base :models) (dissoc m :models))
                            :models models
                            :context context-policy)
                     :leaf-concurrency/limiter
                     :leaf-concurrency/max)
         max-leaf-concurrency (:max-leaf-concurrency cfg)
         limiter (when (and max-leaf-concurrency (pos? max-leaf-concurrency))
                   (if (and (:leaf-concurrency/limiter m)
                            (= (:leaf-concurrency/max m) max-leaf-concurrency))
                     (:leaf-concurrency/limiter m)
                     (concurrent/semaphore max-leaf-concurrency)))]
     (cond-> (assoc cfg
                    :leaf-concurrency/max max-leaf-concurrency
                    :cache-ttl (cache/normalize-ttl (:cache-ttl cfg)))
       limiter (assoc :leaf-concurrency/limiter limiter)))))

(defn maybe-add-child-finalization-warning! [state cfg turn-id step-n]
  (session-invocation/maybe-add-finalization-warning!
   session-loop/add-observation! state cfg turn-id step-n))

(defn restore-state-from-snapshot!
  [state source-locator ns-sym snapshot-row snapshot-blob lineage-kind lineage-parents source-fingerprint]
  (let [source-locator source-locator
        source-root (artifacts/store-root-for-locator source-locator)
        source-session-id (snapshot/source-session-id source-locator)
        source-turn-id (:snapshot/turn-id snapshot-row)
        source-head-id (or (:head/id source-locator)
                           (:head-id source-locator)
                           (:head/id (first (filter #(= (:snapshot/id snapshot-row)
                                                        (:head/snapshot-id %))
                                                    (snapshot/heads source-locator))))
                           (snapshot/current-head-id source-locator))
        source-head (session-db/read-head source-root source-session-id source-head-id)
        source-head-state (or (session-db/read-head-state source-root source-session-id source-head-id)
                              (throw (ex-info "Source head has no readable state"
                                              {:error/type :restore/head-state-missing
                                               :source/session-id source-session-id
                                               :source/head-id source-head-id})))
        source-view (session-model/head-state->view source-head-state)
        target-session (:session @state)
        target-session-id (artifacts/logical-session-id state)
        same-session? (= source-session-id target-session-id)
        restored-counters (apply merge-with max
                                 (map #(or % {})
                                      [(:counters source-view)
                                       (:counters @state)
                                       (when same-session?
                                         (session-db/high-water-counters source-root source-session-id))]))
        restored-state (-> source-view
                           (assoc :session (merge (:session source-view)
                                                  target-session
                                                  {:session/status :running
                                                   :session/ended-at nil
                                                   :session/restored-from {:source/session-id source-session-id
                                                                           :source/head-id source-head-id
                                                                           :source/fingerprint source-fingerprint
                                                                           :source/turn-id source-turn-id
                                                                           :source/snapshot-id (:snapshot/id snapshot-row)}
                                                   :session/lineage-kind lineage-kind
                                                   :session/latest-turn-id source-turn-id})
	                                  :refs {:ref/session target-session-id
	                                         :ref/current-head (when same-session? source-head-id)
	                                         :ref/updated-at (time/now-str)}
                                  :counters restored-counters))
        started-at (time/now-str)
        restore-result (snapshot/restore-vars! source-locator ns-sym snapshot-blob)
        ended-at (time/now-str)
        restore-report {:restore/version 1
                        :restore/strategy :head-state+snapshot-vars
                        :restore/source source-locator
                        :restore/source-head-id source-head-id
                        :restore/source-turn-id source-turn-id
                        :restore/source-snapshot-id (:snapshot/id snapshot-row)
                        :restore/status (if (seq (:skipped-vars restore-result)) :partial :ok)
                        :restore/started-at started-at
                        :restore/ended-at ended-at
                        :restore/restored-vars (:restored-count restore-result)
                        :restore/unrestorable-vars (:skipped-vars restore-result)
                        :restore/missing-vars (filterv #(= :missing-value (:reason %))
                                                       (:skipped-vars restore-result))
                        :restore/current-ns (str ns-sym)
                        :restore/error nil}
        lineage {:lineage/version 1
                 :lineage/session-id (get-in @state [:session :session/id])
                 :lineage/kind lineage-kind
                 :lineage/source {:source/session-id source-session-id
                                  :source/head-id source-head-id
                                  :source/fingerprint source-fingerprint
                                  :source/turn-id source-turn-id
                                  :source/snapshot-id (:snapshot/id snapshot-row)}
                 :lineage/parents lineage-parents
                 :lineage/created-at ended-at}]
    (artifacts/emit! state
                     {:event/type :session/restored
                      :state restored-state
                      :session-patch {:session/restored-from (:lineage/source lineage)
                                      :session/lineage-kind lineage-kind
                                      :session/latest-turn-id source-turn-id}})
    (when same-session?
      (artifacts/set-current-head! state source-head-id))
    (snapshot/write-restore-report! (:locator @state) restore-report)
    (snapshot/write-lineage! (:locator @state) lineage)
    (artifacts/add-event! state {:event/type :restore-end
                                 :restore/strategy :head-state+snapshot-vars
                                 :restore/status (:restore/status restore-report)
                                 :head/id source-head-id
                                 :snapshot/id (:snapshot/id snapshot-row)
                                 :turn/id source-turn-id})
    (artifacts/flush! state)
    {:restore-report restore-report
     :lineage lineage}))

(defn make-ops [state cfg _ns-sym]
  (merge (leaf/ops state cfg)
         (session-invocation/ops
          {:run-process! run-process!
           :run-input! run-turn-on-state!
           :make-ops make-ops
           :restore-state! restore-state-from-snapshot!
           :child-root-config child-root-config}
          state cfg)))

(defn run-turn-on-state! [state cfg ns-sym user-message]
  (session-loop/run-input! state cfg ns-sym user-message
                           {:before-step maybe-add-child-finalization-warning!}))

(defn child-root-config [cfg kind]
  (if (= :child kind)
    (assoc-in cfg [:models :root] (get-in cfg [:models :child]))
    cfg))

(defn run-process!
  [cfg {:keys [id logical-id kind parent task messages resume-state ns-sym cache-id
               store-root initial-head?] :as opts}]
  (let [cfg (config cfg)
        effective-cfg (child-root-config cfg kind)
        store-root' (or store-root (:runs-dir cfg))
        state (or resume-state
                  (artifacts/new-state! {:id id
                                         :logical-id logical-id
                                         :cache-id cache-id
                                         :kind (or kind :root)
                                         :provider (provider-call/provider-shape effective-cfg)
                                         :parent parent
                                         :store-root store-root'
                                         :initial-head? initial-head?}))
        session-id (:session/id (:session @state))
        ns-sym (or ns-sym (runtime/session-ns-symbol session-id))
        ops (make-ops state effective-cfg ns-sym)]
    (runtime/ensure-ns! ns-sym ops {:clear? (not resume-state)})
    (when-not resume-state
      (artifacts/add-message! state :system prompt/system-prompt))
    (doseq [m messages]
      (artifacts/add-message! state (:role m) (:content m) (:turn-id m)))
    (when (and (not resume-state)
               (not= false initial-head?))
      (artifacts/create-head! state {:head/kind :initial
                                     :head/message-through-id (apply max 0 (map :message/id (:messages @state)))
                                     :head/event-range {:from-event 1
                                                        :to-event (get-in @state [:counters :event])}}))
    (let [result (if task
                   (run-turn-on-state! state effective-cfg ns-sym task)
                   (throw (ex-info "run-process! requires :task for one-turn execution"
                                   {:error/type :fractal/missing-task})))]
      (when (= :child kind)
        (artifacts/update-status! state (if (= :error (:status result)) :error :stopped))
        (artifacts/add-event! state {:event/type :session-stopped
                                     :session/id (:session/id (:session @state))}))
      result)))

(defn run-task!
  ([task] (run-task! (config) task))
  ([cfg task]
   (let [sid (artifacts/session-id)
         root (:runs-dir cfg)]
     (run-process! cfg {:id sid :kind :root :store-root root :task task}))))
