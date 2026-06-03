(ns fractal-engine.process
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.call :as call]
            [fractal-engine.concurrent :as concurrent]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provider :as provider]
            [fractal-engine.provider-call :as provider-call]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.rlm :as rlm]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.session-model :as session-model]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.time :as time]))

(declare run-process! run-turn-on-state! child-root-config add-observation! make-ops)

(defn config
  ([] (config {}))
  ([m]
   (let [base {:runs-dir "runs"
               :max-turns 25
               :max-fanout 50
               :max-leaf-concurrency 50
               :call-timeout-ms 120000
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
         cfg (dissoc (assoc (merge (dissoc base :models) (dissoc m :models))
                            :models models)
                     :leaf-concurrency/limiter
                     :leaf-concurrency/max)
         max-leaf-concurrency (:max-leaf-concurrency cfg)
         limiter (when (and max-leaf-concurrency (pos? max-leaf-concurrency))
                   (if (and (:leaf-concurrency/limiter m)
                            (= (:leaf-concurrency/max m) max-leaf-concurrency))
                     (:leaf-concurrency/limiter m)
                     (concurrent/semaphore max-leaf-concurrency)))]
     (cond-> (assoc cfg :leaf-concurrency/max max-leaf-concurrency)
       limiter (assoc :leaf-concurrency/limiter limiter)))))

(defn child-task-prompt [task]
  (str "Child RLM protocol:\n"
       "- Work only on the assigned child task below.\n"
       "- You are an investigator for one bounded uncertainty surface, not the author of the whole parent answer.\n"
       "- Use ordinary Clojure for deterministic inspection.\n"
       "- For any large uncertainty surface, do reconnaissance before solving: identify structure, partitions, validation checks, useful leaf batches, and missingness.\n"
       "- Represent assigned material before solving it. For raw text, tables, logs, transcripts, code, search results, or mixed artifacts, separate data from instructions/headings/metadata, validate counts and required fields when present, inspect edge cases, and repair bad representation before semantic calls or FINAL.\n"
       "- Use lm/map-lm aggressively for bounded semantic extraction, classification, or summarization when useful.\n"
       "- Track answer-sensitive uncertainty and resolve or report it before FINAL.\n"
       "- Keep durable vars for material, leaf results, ledgers, checks, and missingness.\n"
       "- For exact tasks, compute aggregates with Clojure and verify the FINAL value against the ledger.\n"
       "- When the child result is ready, you MUST call (FINAL value).\n"
       "- If the host warns that this is the final child step, stop inspecting and call (FINAL value) from the evidence already gathered. Include missingness rather than continuing.\n"
       "- A bare EDN map/vector/string is only an observation and is NOT returned to the parent.\n\n"
       "Assigned child task:\n"
       (if (string? task) task (pr-str task))))

(defn child-finalization-warning [max-turns]
  (str "CHILD FINALIZATION REQUIRED:\n"
       "This child session is close to exhausting its " max-turns
       "-step turn budget. Do not start broad new searches, spawn more work, or emit progress."
       " Compose a compact result from the vars and observations already available."
       " If the assignment is incomplete, include explicit :missing or :unknowns."
       " Your next Clojure block must call (FINAL value)."))

(defn child-session? [state]
  (= :child (get-in @state [:session :session/kind])))

(defn finalization-warning-step? [state cfg step-n]
  (let [max-turns (:max-turns cfg)]
    (and (child-session? state)
         (integer? max-turns)
         (pos? max-turns)
         (>= step-n (max 0 (- max-turns 3))))))

(defn maybe-add-child-finalization-warning! [state cfg turn-id step-n]
  (when (finalization-warning-step? state cfg step-n)
    (add-observation! state turn-id (child-finalization-warning (:max-turns cfg)))))

(defn attached-child-id [n]
  (format "attached-%04d" (long n)))

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

(defn continue-attached-session-call [state cfg source-handle task opts]
  (let [target (rlm/resolve-attach-target state source-handle opts)
        source-session-id (:session-id target)
        caller-session-id (artifacts/logical-session-id state)]
    (when (= source-session-id caller-session-id)
      (throw (ex-info "attach-rlm cannot continue the current session from inside itself; pass an explicit :head/id to branch"
                      {:error/type :attach/self-current-session
                       :session/id source-session-id})))
    (let [source-locator (select-keys target [:store/root :session/id :head/id])
          source-head-id (:head-id target)
          source-store-root (store-io/path (:store/root target))
          source-session (session-db/read-session source-store-root source-session-id)
          source-cache-id (or (:session/cache-id source-session)
                              (:cache-id target)
                              source-session-id)
          source-kind (:session/kind source-session)
          source-fingerprint (snapshot/session-fingerprint source-locator)
          snapshot-row (try
                         (snapshot/require-snapshot source-locator (:snapshot-opts target))
                         (catch Throwable t
                           (throw (ex-info "Attach source has no completed turn snapshot"
                                           {:error/type :attach/snapshot-not-found
                                            :error/data (ex-data t)
                                            :source/session-id source-session-id
                                            :source/head-id source-head-id
                                            :source/locator source-locator}
                                           t))))
          snapshot-blob (try
                          (snapshot/require-snapshot-blob source-locator snapshot-row)
                          (catch Throwable t
                            (throw (ex-info "Attach snapshot restore failed"
                                            {:error/type :attach/restore-failed
                                             :error/data (ex-data t)
                                             :source/session-id source-session-id
                                             :source/head-id source-head-id
                                             :source/locator source-locator
                                             :snapshot/id (:snapshot/id snapshot-row)}
                                            t))))
          child-cfg (child-root-config cfg source-kind)
          child-ns (runtime/session-ns-symbol source-session-id)
          attach-meta {:edge/type :continued
                       :attach/source-session-id source-session-id
                       :attach/source-head-id source-head-id
                       :attach/source-locator source-locator
                       :attach/source-fingerprint source-fingerprint
                       :attach/source-turn-id (:snapshot/turn-id snapshot-row)
                       :attach/source-snapshot-id (:snapshot/id snapshot-row)
                       :attach/source-snapshot-ref (:snapshot/ref snapshot-row)}]
      (call/traced!
       state
       {:build-call (fn [_id]
                      (merge {:call/type :attached-session
                              :call/turn-id runtime/*current-turn-id*
                              :call/parent-eval-id runtime/*current-eval-id*
                              :call/input-ref (artifacts/value-ref! (:locator @state)
                                                                    {:handle source-handle
                                                                     :task task
                                                                     :opts opts}
                                                                    {:payload/kind :call-input})
                              :child/session-id source-session-id
                              :child/logical-session-id source-session-id
                              :child/cache-id source-cache-id}
                             attach-meta))
        :work (fn [call-id]
                (artifacts/add-event! state {:event/type :attach-rlm-start
                                             :call/id call-id
                                             :snapshot/id (:snapshot/id snapshot-row)})
                (let [child-state (artifacts/load-state! {:store-root source-store-root
                                                          :session-id source-session-id
                                                          :head-id source-head-id})
                      child-ops (make-ops child-state child-cfg child-ns)]
                  (runtime/ensure-ns! child-ns child-ops {:clear? true})
                  (restore-state-from-snapshot!
                   child-state source-locator child-ns snapshot-row snapshot-blob
                   :continue
                   [{:parent/kind :continued-by
                     :parent/session-id (:session/id (:session @state))
                     :parent/call-id call-id
                     :parent/eval-id runtime/*current-eval-id*}]
                   source-fingerprint)
                  (let [result (run-turn-on-state! child-state child-cfg child-ns task)]
                    (artifacts/update-status! child-state (if (= :error (:status result)) :error :stopped))
                    (artifacts/add-event! child-state {:event/type :session-stopped
                                                       :session/id (:session/id (:session @child-state))})
                    result)))
        :succeed (fn [call-id result]
                   (let [final-value (rlm/child-final-value result source-session-id
                                                        {:failed :attach/child-error
                                                         :no-final :attach/no-final})
                         result' (assoc result :final-value final-value)
                         inv (rlm/record-invocation! state call-id
                                                     :attach
                                                     {:handle source-handle
                                                      :task task
                                                      :opts opts}
                                                     result' :final nil
                                                     attach-meta)
                         value (rlm/envelope final-value inv
                                             {:handle source-handle
                                              :task task
                                              :opts opts})]
                     (artifacts/add-event! state {:event/type :attach-rlm-end
                                                  :call/id call-id
                                                  :call/status :final})
                     {:value value
                      :patch {:call/status :final
                              :call/final-ref (artifacts/value-ref! (:locator @state) value)
                              :invocation/id (:invocation/id inv)
                              :child/session-handle (:invocation/handle inv)
                              :child/head-before (:callee/head-before inv)
                              :child/head-after (:callee/head-after inv)}}))
        :fail (fn [call-id t]
                (let [err (merge {:error/type :attach/child-error
                                  :error/message (.getMessage t)
                                  :error/data (ex-data t)
                                  :child/session-id source-session-id}
                                 (select-keys (ex-data t) [:error/type :error/data]))
                      result {:status :error
                              :error err
                              :locator source-locator
                              :session-id source-session-id
                              :logical-session-id source-session-id
                              :cache-id source-cache-id
                              :head-before source-head-id
                              :head-id (rlm/current-head-id-for-locator source-locator)}
                      inv (rlm/record-invocation! state call-id
                                                  :attach
                                                  {:handle source-handle
                                                   :task task
                                                   :opts opts}
                                                  result :error err
                                                  attach-meta)]
                  (artifacts/add-event! state {:event/type :attach-rlm-error
                                               :call/id call-id
                                               :error err})
                  {:patch {:call/error err
                           :invocation/id (:invocation/id inv)
                           :child/session-handle (:invocation/handle inv)
                           :child/head-before (:callee/head-before inv)
                           :child/head-after (:callee/head-after inv)}
                   :ex (ex-info "Attach RLM failed" err t)}))}))))

(defn make-ops [state cfg ns-sym]
  (letfn [(leaf-call [call-type input query mode extra]
            (let [call (merge {:call/type call-type
                               :call/turn-id runtime/*current-turn-id*
                               :call/parent-eval-id runtime/*current-eval-id*
                               :call/status :running
                               :call/input-ref (artifacts/value-ref! (:locator @state) input)
                               :call/query query
                               :call/mode mode
                               :request (provider-call/leaf-request input query
                                                      (cache/request-cache (provider-call/session-cache-id state) :leaf))}
                              extra)
                  {:keys [call-id response]} (provider-call/call-provider! state cfg :leaf call)
                  text (provider/response-text response)
                  value (try
                          (provider-call/parse-leaf text mode)
                          (catch Throwable t
                            ;; The provider call succeeded but its text did not parse
                            ;; into the requested shape. Mark the row so `leaves`/inspect
                            ;; surface the item failure instead of a fake-ok leaf with no
                            ;; result; then rethrow a typed error so map-lm folds it into
                            ;; a typed sentinel (singular lm surfaces it as an eval error
                            ;; / catch). :error/type lifts the same way as a child failure.
                            (let [err {:error/type :fractal/leaf-parse-failed
                                       :error/message (.getMessage t)
                                       :error/data (ex-data t)}]
                              (artifacts/update-call! state call-id assoc
                                                      :call/status :item-failed
                                                      :call/error err)
                              (throw (ex-info "Leaf response did not parse into the requested shape"
                                              err t)))))]
              (artifacts/update-call! state call-id assoc
                                      :call/result-ref (artifacts/value-ref! (:locator @state) value))
              value))
          (lm
            ([input query] (lm input query :string))
            ([input query mode]
             (leaf-call :leaf input query mode {})))
          (map-lm
            ([inputs query] (map-lm inputs query :string))
            ([inputs query mode]
             (let [inputs' (provider-call/bounded-fanout-inputs :leaf cfg inputs)
                   batch-id (str "leaf-batch-" (java.util.UUID/randomUUID))
                   results (concurrent/parallel-map-indexed
                            "fractal-map-lm"
                            (fn [idx input]
                              (try
                                {:ok true
                                 :index idx
                                 :value (leaf-call :leaf-batch-item input query mode
                                                   {:batch/id batch-id :batch/index idx})}
                                (catch Throwable t
                                  ;; Lift :error/type from the cause's ex-data so the
                                  ;; sentinel is typed: leaf parse -> :fractal/leaf-parse-failed,
                                  ;; child -> :fractal/child-failed / :fractal/child-no-final.
                                  {:ok false :index idx
                                   :error (merge {:error/message (.getMessage t)
                                                  :error/data (ex-data t)}
                                                 (select-keys (ex-data t) [:error/type]))})))
                            inputs')]
               ;; Partial failure returns successes for sure: an input-aligned vector
               ;; with `:fractal/failed` sentinels in the failed slots. The model splits
               ;; them out and records missingness. (The pre-flight fanout cap throws
               ;; earlier in provider-call/bounded-fanout-inputs and never reaches here.)
               (rlm/assemble-batch-results results))))
          (child-call [call-type task extra]
            (let [child-num (artifacts/next-counter! state :child)
                  cid (artifacts/child-id child-num)
                  parent-cache-id (provider-call/session-cache-id state)
                  child-logical-id (artifacts/session-id)
                  child-cache-id child-logical-id
                  child-locator (session-db/locator (rlm/state-store-root state) child-logical-id)]
              (call/traced!
               state
               {:build-call (fn [_id]
                              (merge {:call/type call-type
                                      :call/turn-id runtime/*current-turn-id*
                                      :edge/type :spawned
                                      :call/parent-eval-id runtime/*current-eval-id*
                                      :call/input-ref (artifacts/value-ref! (:locator @state) task
                                                                            {:payload/kind :call-input})
                                      :child/session-id cid
                                      :child/logical-session-id child-logical-id
                                      :child/cache-id child-cache-id}
                                     extra))
                :work (fn [call-id]
                        (let [parent {:parent/session-id (:session/id (:session @state))
                                      :parent/cache-id parent-cache-id
                                      :parent/call-id call-id
                                      :parent/eval-id runtime/*current-eval-id*}
                              result (run-process! cfg {:id cid
                                                        :logical-id child-logical-id
                                                        :cache-id child-cache-id
                                                        :kind :child
                                                        :parent parent
                                                        :store-root (rlm/state-store-root state)
                                                        :task (child-task-prompt task)})]
                          (assoc result
                                 :final-value (rlm/child-final-value result cid
                                                                 {:failed :fractal/child-failed
                                                                  :no-final :fractal/child-no-final}))))
                :succeed (fn [call-id result]
                           (let [final-value (:final-value result)
                                 inv (rlm/record-invocation! state call-id
                                                             (if (= :child-batch-item call-type)
                                                               :map-rlm
                                                               :rlm)
                                                             task result :final nil extra)
                                 value (rlm/envelope final-value inv task)
                                 handle (:invocation/handle inv)]
                             {:value value
                              :patch {:call/status :final
                                      :call/final-ref (artifacts/value-ref! (:locator @state) value)
                                      :invocation/id (:invocation/id inv)
                                      :child/session-handle handle
                                      :child/head-before (:callee/head-before inv)
                                      :child/head-after (:callee/head-after inv)}}))
                :fail (fn [call-id t]
                        (let [data (ex-data t)
                              err (merge {:error/type :fractal/child-failed
                                          :error/message (.getMessage t)
                                          :error/data data
                                          :child/session-id cid
                                          :error/retryable? false}
                                         (select-keys data [:error/type :child/session-id :error/retryable?]))
                              result {:status :error
                                      :error err
                                      :locator child-locator
                                      :session-id cid
                                      :logical-session-id (rlm/logical-session-id-for-locator child-locator cid)
                                      :cache-id (rlm/cache-id-for-locator child-locator cid)
                                      :head-before (rlm/current-head-id-for-locator child-locator)
                                      :head-id (rlm/current-head-id-for-locator child-locator)}
                              inv (rlm/record-invocation! state call-id
                                                          (if (= :child-batch-item call-type)
                                                            :map-rlm
                                                            :rlm)
                                                          task result :error err extra)]
                          {:patch {:call/error err
                                   :invocation/id (:invocation/id inv)
                                   :child/session-handle (:invocation/handle inv)
                                   :child/head-before (:callee/head-before inv)
                                   :child/head-after (:callee/head-after inv)}
                           :ex (ex-info "Child process failed" err t)}))})))
          (attach-call [source-handle task opts]
            (if-not (rlm/explicit-head-handle? source-handle opts)
              (continue-attached-session-call state cfg source-handle task opts)
              (let [target (rlm/resolve-attach-target state source-handle opts)
                  source-locator (select-keys target [:store/root :session/id :head/id])
                  source-head-id (:head-id target)
                  source-session-id (:session-id target)
                  source-fingerprint (snapshot/session-fingerprint source-locator)
                  snapshot-row (try
                                 (snapshot/require-snapshot source-locator (:snapshot-opts target))
                                 (catch Throwable t
                                   (throw (ex-info "Attach source has no completed turn snapshot"
                                                   {:error/type :attach/snapshot-not-found
                                                    :error/data (ex-data t)
                                                    :source/session-id source-session-id
                                                    :source/head-id source-head-id
                                                    :source/locator source-locator}
                                                   t))))
                  snapshot-blob (try
                                  (snapshot/require-snapshot-blob source-locator snapshot-row)
                                  (catch Throwable t
                                    (throw (ex-info "Attach snapshot restore failed"
                                                    {:error/type :attach/restore-failed
                                                     :error/data (ex-data t)
                                                     :source/session-id source-session-id
                                                     :source/head-id source-head-id
                                                     :source/locator source-locator
                                                     :snapshot/id (:snapshot/id snapshot-row)}
                                                    t))))
                  child-num (artifacts/next-counter! state :child)
                  cid (attached-child-id child-num)
                  child-logical-id (artifacts/session-id)
                  parent-cache-id (provider-call/session-cache-id state)
                  child-cache-id child-logical-id
                  child-locator (session-db/locator (rlm/state-store-root state) child-logical-id)
                  child-cfg (child-root-config cfg :child)
                  attach-meta {:edge/type :attached
                               :attach/source-session-id source-session-id
                               :attach/source-head-id source-head-id
                               :attach/source-locator source-locator
                               :attach/source-fingerprint source-fingerprint
                               :attach/source-turn-id (:snapshot/turn-id snapshot-row)
                               :attach/source-snapshot-id (:snapshot/id snapshot-row)
                               :attach/source-snapshot-ref (:snapshot/ref snapshot-row)}]
              (call/traced!
               state
               {:build-call (fn [_id]
                              (merge {:call/type :attached-child
                                      :call/turn-id runtime/*current-turn-id*
                                      :call/parent-eval-id runtime/*current-eval-id*
                                      :call/input-ref (artifacts/value-ref! (:locator @state)
                                                                            {:handle source-handle
                                                                             :task task
                                                                             :opts opts}
                                                                            {:payload/kind :call-input})
                                      :child/session-id cid
                                      :child/logical-session-id child-logical-id
                                      :child/cache-id child-cache-id}
                                     attach-meta))
                :work (fn [call-id]
                        (artifacts/add-event! state {:event/type :attach-rlm-start
                                                     :call/id call-id
                                                     :snapshot/id (:snapshot/id snapshot-row)})
                        (let [parent {:parent/session-id (:session/id (:session @state))
                                      :parent/cache-id parent-cache-id
                                      :parent/call-id call-id
                                      :parent/eval-id runtime/*current-eval-id*}
                              child-state (artifacts/new-state! {:id cid
                                                                 :logical-id child-logical-id
                                                                 :cache-id child-cache-id
                                                                 :kind :child
                                                                 :provider (provider-call/provider-shape child-cfg)
                                                                 :parent parent
                                                                 :store-root (rlm/state-store-root state)
                                                                 :initial-head? false})
                              child-ns (runtime/session-ns-symbol child-logical-id)
                              child-ops (make-ops child-state child-cfg child-ns)]
                          (runtime/ensure-ns! child-ns child-ops {:clear? true})
                          (restore-state-from-snapshot!
                           child-state source-locator child-ns snapshot-row snapshot-blob
                           :attached-child
                           [{:parent/kind :child-of
                             :parent/session-id (:session/id (:session @state))
                             :parent/call-id call-id
                             :parent/eval-id runtime/*current-eval-id*}
                            {:parent/kind :attached-from
                             :parent/session-id source-session-id
                             :parent/head-id source-head-id
                             :parent/turn-id (:snapshot/turn-id snapshot-row)
                             :parent/snapshot-id (:snapshot/id snapshot-row)}]
                           source-fingerprint)
                          (let [result (run-turn-on-state! child-state child-cfg child-ns task)]
                            (artifacts/update-status! child-state (if (= :error (:status result)) :error :stopped))
                            (artifacts/add-event! child-state {:event/type :session-stopped
                                                               :session/id (:session/id (:session @child-state))})
                            (assoc result
                                   :final-value (rlm/child-final-value result cid
                                                                   {:failed :attach/child-error
                                                                    :no-final :attach/no-final})))))
                :succeed (fn [call-id result]
                           (let [final-value (:final-value result)
                                 inv (rlm/record-invocation! state call-id
                                                             :attach
                                                             {:handle source-handle
                                                              :task task
                                                              :opts opts}
                                                             result :final nil
                                                             attach-meta)
                                 value (rlm/envelope final-value inv
                                                     {:handle source-handle
                                                      :task task
                                                      :opts opts})]
                             (artifacts/add-event! state {:event/type :attach-rlm-end
                                                          :call/id call-id
                                                          :call/status :final})
                             {:value value
                              :patch {:call/status :final
                                      :call/final-ref (artifacts/value-ref! (:locator @state) value)
                                      :invocation/id (:invocation/id inv)
                                      :child/session-handle (:invocation/handle inv)
                                      :child/head-before (:callee/head-before inv)
                                      :child/head-after (:callee/head-after inv)}}))
                :fail (fn [call-id t]
                        (let [err (merge {:error/type :attach/child-error
                                          :error/message (.getMessage t)
                                          :error/data (ex-data t)
                                          :child/session-id cid}
                                         (select-keys (ex-data t) [:error/type :error/data]))
                              result {:status :error
                                      :error err
                                      :locator child-locator
                                      :session-id cid
                                      :logical-session-id child-logical-id
                                      :cache-id child-cache-id
                                      :head-before nil
                                      :head-id (rlm/current-head-id-for-locator child-locator)}
                              inv (rlm/record-invocation! state call-id
                                                          :attach
                                                          {:handle source-handle
                                                           :task task
                                                           :opts opts}
                                                          result :error err
                                                          attach-meta)]
                          (artifacts/add-event! state {:event/type :attach-rlm-error
                                                       :call/id call-id
                                                       :error err})
                          {:patch {:call/error err
                                   :invocation/id (:invocation/id inv)
                                   :child/session-handle (:invocation/handle inv)
                                   :child/head-before (:callee/head-before inv)
                                   :child/head-after (:callee/head-after inv)}
                           :ex (ex-info "Attach RLM failed" err t)}))})))
              )
          (rlm [task]
            (child-call :child task {}))
          (map-rlm
            ([tasks] (map-rlm tasks nil))
            ([tasks shared-instruction]
             (let [tasks' (provider-call/bounded-fanout-inputs :child cfg tasks)
                   batch-id (str "child-batch-" (java.util.UUID/randomUUID))
                   results (concurrent/parallel-map-indexed
                            "fractal-map-rlm"
                            (fn [idx task]
                              (try
                                (let [task' (if shared-instruction
                                              (str shared-instruction "\n\nTask:\n" (pr-str task))
                                              task)]
                                  {:ok true
                                   :index idx
                                   :value (child-call :child-batch-item task'
                                                      {:batch/id batch-id
                                                       :batch/index idx})})
                                (catch Throwable t
                                  ;; Lift :error/type from the cause's ex-data so the
                                  ;; sentinel is typed: leaf parse -> :fractal/leaf-parse-failed,
                                  ;; child -> :fractal/child-failed / :fractal/child-no-final.
                                  {:ok false :index idx
                                   :error (merge {:error/message (.getMessage t)
                                                  :error/data (ex-data t)}
                                                 (select-keys (ex-data t) [:error/type]))})))
                            tasks')]
               ;; Symmetric with map-lm: a failed child becomes a `:fractal/failed`
               ;; sentinel in its slot; the successful children return for sure.
               (rlm/assemble-batch-results results))))
          (attach-rlm
            ([handle task] (attach-rlm handle task {}))
            ([handle task opts] (attach-call handle task opts)))]
    {:lm lm :map-lm map-lm :rlm rlm :map-rlm map-rlm :attach-rlm attach-rlm}))

(defn add-observation! [state turn-id content]
  (let [message (artifacts/add-message! state :observation content turn-id)]
    (artifacts/add-turn-id! state turn-id :turn/observation-message-ids (:message/id message))
    message))

(defn eval-assistant! [state cfg ns-sym turn-id assistant-message]
  (let [blocks (runtime/extract-clojure-blocks (:message/content assistant-message))]
    (if (empty? blocks)
      (do
        (add-observation! state turn-id
                          "No fenced Clojure block found. Please respond with a fenced ```clojure block.")
        {:status :continue})
      (loop [idx 0 rows []]
        (if-let [code (nth blocks idx nil)]
          (let [started (time/now-str)
                placeholder-id (inc (get-in @state [:counters :eval]))
                result (binding [runtime/*current-eval-id* placeholder-id]
                         (runtime/eval-code ns-sym code))
                final-ref (when (= :final (:eval/status result))
                            (artifacts/value-ref! (:locator @state) (:eval/raw-final-value result)))
                result-ref (when (= :ok (:eval/status result))
                             (artifacts/value-ref! (:locator @state)
                                                   {:eval/raw-value (:eval/raw-value result)}))
                error-ref (when (= :error (:eval/status result))
                            (artifacts/value-ref! (:locator @state)
                                                  {:eval/error (:eval/error result)}))
                row (artifacts/add-eval!
                     state
                     (merge (dissoc result :eval/raw-value :eval/raw-final-value)
                            (when final-ref
                              {:eval/final-ref final-ref})
                            (when result-ref
                              {:eval/result-ref result-ref})
                            (when error-ref
                              {:eval/error-ref error-ref})
                            {:eval/message-id (:message/id assistant-message)
                             :eval/call-id (:message/call-id assistant-message)
                             :eval/turn-id turn-id
                             :eval/block-index idx
                             :eval/code code
                             :eval/started-at (:eval/started-at result started)}))
                rows' (conj rows row)]
            (if (= :final (:eval/status result))
              (do
                (artifacts/mark-final! state (:eval/raw-final-value result))
                (add-observation! state turn-id (runtime/observation rows'))
                {:status :final
                 :value (:eval/raw-final-value result)
                 :final-ref final-ref
                 :eval-row row})
              (if (= :error (:eval/status result))
                (do
                  (add-observation! state turn-id (runtime/observation rows'))
                  {:status :continue})
                (recur (inc idx) rows'))))
          (do
            (add-observation! state turn-id (runtime/observation rows))
            {:status :continue}))))))

(defn finish-turn-error! [state turn-id err]
  (artifacts/update-turn! state turn-id assoc
                          :turn/status :error
                          :turn/ended-at (time/now-str)
                          :turn/error err
                          :turn/usage (artifacts/derive-usage (:locator @state) (:calls @state)))
  (artifacts/add-event! state {:event/type :turn-error :turn/id turn-id :error err})
  (artifacts/mark-error! state err))

(defn run-loop! [state cfg ns-sym turn-id]
  (binding [runtime/*current-turn-id* turn-id]
    (loop [step-n 0]
      (if (>= step-n (:max-turns cfg))
        (let [err {:error/type :fractal/max-turns :max-turns (:max-turns cfg)}]
          (finish-turn-error! state turn-id err)
          {:status :error
           :error err
           :locator (:locator @state)
           :session-id (:session/id (:session @state))
           :logical-session-id (artifacts/logical-session-id state)
           :cache-id (provider-call/session-cache-id state)
           :store-root (str (rlm/state-store-root state))
           :turn-id turn-id})
        (do
          (maybe-add-child-finalization-warning! state cfg turn-id step-n)
          (let [request (provider-call/provider-request (:messages @state)
                                        (cache/request-cache (provider-call/session-cache-id state) :agent))
              step (try
                     (let [{:keys [call-id response]} (provider-call/call-provider!
                                                       state cfg :root
                                                       {:call/type :root
                                                        :call/turn-id turn-id
                                                        :call/message-ids (mapv :message/id (:messages @state))
                                                        :request request})
                           content (provider/response-text response)
                           assistant (artifacts/add-message! state :assistant content turn-id
                                                             {:message/call-id call-id})]
                       (artifacts/add-turn-id! state turn-id :turn/assistant-message-ids (:message/id assistant))
                       (eval-assistant! state cfg ns-sym turn-id assistant))
                     (catch clojure.lang.ExceptionInfo e
                       (if (= :provider/failed (:error/type (ex-data e)))
                         {:status :provider-error :error (ex-data e)}
                         (throw e))))]
          (cond
            (= :provider-error (:status step))
            (let [err (:error step)]
              (finish-turn-error! state turn-id err)
              {:status :error
               :error err
               :locator (:locator @state)
               :session-id (:session/id (:session @state))
               :logical-session-id (artifacts/logical-session-id state)
               :cache-id (provider-call/session-cache-id state)
               :store-root (str (rlm/state-store-root state))
               :turn-id turn-id})

            (= :final (:status step))
            (let [usage (artifacts/derive-usage (:locator @state) (:calls @state))
                  final-ref (or (:final-ref step)
                                (artifacts/value-ref! (:locator @state) (:value step)))]
              (artifacts/update-turn! state turn-id assoc
                                      :turn/status :final
                                      :turn/ended-at (time/now-str)
                                      :turn/final-ref final-ref
                                      :turn/final-preview (artifacts/project-value (:value step))
                                      :turn/usage usage)
              (artifacts/add-event! state {:event/type :turn-final :turn/id turn-id})
              (let [turn-row (artifacts/current-turn state turn-id)
                    snapshot-row (snapshot/write-turn-snapshot! state
                                                                ns-sym
                                                                turn-row
                                                                (:eval-row step))
                    turn-row' (artifacts/current-turn state turn-id)
                    head-row (artifacts/create-head!
                              state
                              {:head/kind :turn-final
                               :head/turn-id turn-id
                               :head/message-through-id (:snapshot/message-through-id snapshot-row)
                               :head/snapshot-id (:snapshot/id snapshot-row)
                               :head/snapshot-ref (:snapshot/ref snapshot-row)
                               :head/final-ref final-ref
                               :head/final-preview (artifacts/project-value (:value step))
                               :head/invocations (vec (:turn/invocation-ids turn-row'))
                               :head/event-range {:to-event (get-in @state [:counters :event])}})
                    head-id (:head/id head-row)]
                (artifacts/update-turn! state turn-id assoc :turn/head-after head-id)
                (artifacts/complete-turn-invocations! state turn-id head-id)
                (artifacts/flush! state)
                {:status :final
                 :final-value (:value step)
                 :locator (:locator @state)
                 :session-id (:session/id (:session @state))
                 :logical-session-id (artifacts/logical-session-id state)
                 :cache-id (provider-call/session-cache-id state)
                 :store-root (str (rlm/state-store-root state))
                 :head-before (:turn/head-before turn-row')
                 :head-id head-id
                 :head head-row
                 :turn-id turn-id}))

            :else
            (recur (inc step-n)))))))))

(defn prepare-turn! [state user-message]
  (let [turn (artifacts/add-turn! state {:turn/head-before (artifacts/current-head-id state)})
        message (artifacts/add-message! state :user user-message (:turn/id turn))]
    (artifacts/update-turn! state (:turn/id turn) assoc
                            :turn/user-message-id (:message/id message))
    (:turn/id turn)))

(defn run-turn-on-state! [state cfg ns-sym user-message]
  (let [turn-id (prepare-turn! state user-message)]
    (run-loop! state cfg ns-sym turn-id)))

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
      (artifacts/add-message! state :system (if (= :child kind) prompt/child-prompt prompt/system-prompt)))
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
