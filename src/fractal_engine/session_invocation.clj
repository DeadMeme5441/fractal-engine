(ns fractal-engine.session-invocation
  "Recursive session invocation execution.

  `rlm`, `map-rlm`, and `attach-rlm` create or continue sessions and record
  invocation edges. They are distinct from leaves: leaves are call rows only;
  these functions create normal sessions with heads."
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.call :as call]
            [fractal-engine.concurrent :as concurrent]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provider-call :as provider-call]
            [fractal-engine.rlm :as rlm]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.store.io :as store-io]))

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

(defn maybe-add-finalization-warning!
  [add-observation! state cfg turn-id step-n]
  (when (finalization-warning-step? state cfg step-n)
    (add-observation! state turn-id (child-finalization-warning (:max-turns cfg)))))

(defn attached-child-id [n]
  (format "attached-%04d" (long n)))

(defn continue-attached-session-call
  [env state cfg source-handle task opts]
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
          child-cfg ((:child-root-config env) cfg :child)
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
                      child-ops ((:make-ops env) child-state child-cfg child-ns)]
                  (runtime/ensure-ns! child-ns child-ops {:clear? true})
                  ((:restore-state! env)
                   child-state source-locator child-ns snapshot-row snapshot-blob
                   :continue
                   [{:parent/kind :continued-by
                     :parent/session-id (:session/id (:session @state))
                     :parent/call-id call-id
                     :parent/eval-id runtime/*current-eval-id*}]
                   source-fingerprint)
                  (let [result ((:run-input! env) child-state child-cfg child-ns
                                (prompt/attach-invocation-frame task :continue))]
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

(defn child-call
  [env state cfg call-type task extra]
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
                    result ((:run-process! env) cfg {:id cid
                                                     :logical-id child-logical-id
                                                     :cache-id child-cache-id
                                                     :kind :child
                                                     :parent parent
                                                     :store-root (rlm/state-store-root state)
                                                     :task (prompt/child-invocation-frame task)})]
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

(defn attach-call
  [env state cfg source-handle task opts]
  (if-not (rlm/explicit-head-handle? source-handle opts)
    (continue-attached-session-call env state cfg source-handle task opts)
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
          child-cfg ((:child-root-config env) cfg :child)
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
                      child-ops ((:make-ops env) child-state child-cfg child-ns)]
                  (runtime/ensure-ns! child-ns child-ops {:clear? true})
                  ((:restore-state! env)
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
                  (let [result ((:run-input! env) child-state child-cfg child-ns
                                (prompt/attach-invocation-frame task :branch))]
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
                   :ex (ex-info "Attach RLM failed" err t)}))}))))

(defn ops
  [env state cfg]
  (letfn [(rlm [task]
            (child-call env state cfg :child task {}))
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
                                   :value (child-call env state cfg :child-batch-item task'
                                                      {:batch/id batch-id
                                                       :batch/index idx})})
                                (catch Throwable t
                                  {:ok false :index idx
                                   :error (merge {:error/message (.getMessage t)
                                                  :error/data (ex-data t)}
                                                 (select-keys (ex-data t) [:error/type]))})))
                            tasks')]
               (rlm/assemble-batch-results results))))
          (attach-rlm
            ([handle task] (attach-rlm handle task {}))
            ([handle task opts] (attach-call env state cfg handle task opts)))]
    {:rlm rlm
     :map-rlm map-rlm
     :attach-rlm attach-rlm}))
