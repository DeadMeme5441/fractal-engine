(ns fractal-engine.process
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provider :as provider]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.time :as time])
  (:import [java.util.concurrent Callable Executors ThreadFactory TimeUnit]))

(declare run-process!)

(defn config
  ([] (config {}))
  ([m]
   (let [base {:runs-dir "runs"
               :max-turns 25
               :models provider/default-models
               :retry nil}
         models (merge-with merge provider/default-models (:models m))]
     (assoc (merge (dissoc base :models) (dissoc m :models))
            :models models))))

(defn provider-shape [cfg]
  {:root (get-in cfg [:models :root])
   :leaf (get-in cfg [:models :leaf])
   :child (get-in cfg [:models :child])})

(defn wire-message [message]
  (let [role (:message/role message)]
    {:message/role (if (= :observation role) :user role)
     :message/content (if (= :observation role)
                        (str "Observation:\n" (:message/content message))
                        (:message/content message))}))

(defn provider-request [messages cache-request]
  {:request/messages (mapv wire-message messages)
   :request/cache cache-request})

(def leaf-system-prompt
  "Return only the answer requested by the user. For EDN mode, return exactly one EDN value.")

(defn leaf-request [input query cache-request]
  {:request/messages [{:message/role :system
                       :message/content leaf-system-prompt}
                      {:message/role :user
                       :message/content (str "Input EDN:\n" (pr-str input)
                                             "\n\nQuery:\n" query)}]
   :request/cache cache-request})

(defn request-system-hash [request]
  (when-let [content (some (fn [message]
                             (when (= :system (:message/role message))
                               (:message/content message)))
                           (:request/messages request))]
    (cache/sha256-string content)))

(defn enrich-call [state cfg role call]
  (let [request (:request call)
        cache-request (:request/cache request)
        model-cfg (get-in cfg [:models role])]
    (cond-> (assoc call
                   :call/provider (:provider model-cfg)
                   :call/model (:model model-cfg)
                   :call/turn-id (or (:call/turn-id call) runtime/*current-turn-id*)
                   :call/request-ref (artifacts/value-ref! (:dir @state) request)
                   :call/request-message-count (count (:request/messages request))
                   :call/request-system-hash (request-system-hash request)
                   :call/cache-scope (:scope-id cache-request)
                   :call/cache-request cache-request)
      (:call/message-ids call) (assoc :call/request-message-ids (:call/message-ids call)))))

(defn call-provider! [state cfg role call]
  (let [call-row (artifacts/add-call! state (enrich-call state cfg role call))
        call-id (:call/id call-row)
        request (:request call)
        response (try
                   (provider/complete cfg role request)
                   (catch Throwable t
                     (let [model-cfg (get-in cfg [:models role])
                           err {:error/type :provider/failed
                                :error/message (.getMessage t)
                                :error/data (ex-data t)
                                :error/provider (:provider model-cfg)
                                :error/model (:model model-cfg)
                                :error/role role
                                :error/retryable? false}]
                       (artifacts/update-call! state call-id assoc
                                               :call/status :error
                                               :call/error err
                                               :call/usage {:usage/status :unknown}
                                               :call/cost {:cost/status :unknown}
                                               :call/cache {:cache/status :unknown}
                                               :call/ended-at (time/now-str))
                       (artifacts/add-event! state {:event/type :provider-failed
                                                    :call/id call-id
                                                    :error err})
                       (throw (ex-info "Provider call failed" err t)))))]
    (artifacts/update-call! state call-id assoc
                            :call/status :ok
                            :call/response-ref (artifacts/value-ref! (:dir @state) response)
                            :call/usage (:response/usage response {:usage/status :unknown})
                            :call/cost (:response/cost response {:cost/usd :unknown})
                            :call/cache (:response/cache response {:cache/status :unknown})
                            :call/ended-at (time/now-str))
    {:call-id call-id :response response}))

(defn parse-leaf [text mode]
  (case mode
    :string text
    :edn (edn/read-string text)
    text))

(defn daemon-executor [prefix]
  (let [counter (atom 0)]
    (Executors/newCachedThreadPool
     (reify ThreadFactory
       (newThread [_ r]
         (doto (Thread. r (str prefix "-" (swap! counter inc)))
           (.setDaemon true)))))))

(defn parallel-map-indexed [prefix f xs]
  (let [executor (daemon-executor prefix)
        f' (bound-fn [idx x] (f idx x))]
    (try
      (let [tasks (mapv (fn [idx x]
                          (.submit executor
                                   ^Callable
                                   (reify Callable
                                     (call [_] (f' idx x)))))
                        (range) xs)]
        (mapv #(.get %) tasks))
      (finally
        (.shutdownNow executor)
        (.awaitTermination executor 5 TimeUnit/SECONDS)))))

(defn session-cache-id [state]
  (or (get-in @state [:session :session/cache-id])
      (get-in @state [:session :session/id])))

(defn make-ops [state cfg ns-sym]
  (letfn [(leaf-call [call-type input query mode extra]
            (let [call (merge {:call/type call-type
                               :call/turn-id runtime/*current-turn-id*
                               :call/parent-eval-id runtime/*current-eval-id*
                               :call/status :running
                               :call/input-ref (artifacts/value-ref! (:dir @state) input)
                               :call/query query
                               :call/mode mode
                               :request (leaf-request input query
                                                      (cache/request-cache (session-cache-id state) :leaf))}
                              extra)
                  {:keys [call-id response]} (call-provider! state cfg :leaf call)
                  text (provider/response-text response)
                  value (parse-leaf text mode)]
              (artifacts/update-call! state call-id assoc
                                      :call/result-ref (artifacts/value-ref! (:dir @state) value))
              value))
          (lm
            ([input query] (lm input query :string))
            ([input query mode]
             (leaf-call :leaf input query mode {})))
          (map-lm
            ([inputs query] (map-lm inputs query :string))
            ([inputs query mode]
             (let [batch-id (str "leaf-batch-" (java.util.UUID/randomUUID))
                   results (parallel-map-indexed
                            "fractal-map-lm"
                            (fn [idx input]
                              (try
                                {:ok true
                                 :index idx
                                 :value (leaf-call :leaf-batch-item input query mode
                                                   {:batch/id batch-id :batch/index idx})}
                                (catch Throwable t
                                  {:ok false :index idx
                                   :error {:error/message (.getMessage t)
                                           :error/data (ex-data t)}})))
                            inputs)]
               (if (every? :ok results)
                 (mapv :value (sort-by :index results))
                 (throw (ex-info "Leaf batch failed"
                                 {:error/type :fractal/leaf-batch-failed
                                  :batch/id batch-id
                                  :results (vec (sort-by :index results))}))))))
          (child-call [call-type task extra]
            (let [child-num (artifacts/next-counter! state :child)
                  cid (artifacts/child-id child-num)
                  child-rel (str "children/" cid)
                  child-dir (artifacts/path (:dir @state) child-rel)
                  parent-cache-id (session-cache-id state)
                  child-cache-id (str parent-cache-id "/" child-rel)
                  call-row (artifacts/add-call! state (merge {:call/type call-type
                                                              :call/turn-id runtime/*current-turn-id*
                                                              :edge/type :spawned
                                                              :call/parent-eval-id runtime/*current-eval-id*
                                                              :child/session-id cid
                                                              :child/cache-id child-cache-id
                                                              :child/dir child-rel}
                                                             extra))
                  parent {:parent/session-id (:session/id (:session @state))
                          :parent/cache-id parent-cache-id
                          :parent/call-id (:call/id call-row)
                          :parent/eval-id runtime/*current-eval-id*
                          :parent/relative-dir ".."}]
              (try
                (let [result (run-process! cfg {:dir child-dir
                                                :id cid
                                                :cache-id child-cache-id
                                                :kind :child
                                                :parent parent
                                                :task task})
                      value (:final-value result)]
                  (artifacts/update-call! state (:call/id call-row) assoc
                                          :call/status (:status result)
                                          :call/final-ref (artifacts/value-ref! (:dir @state) value)
                                          :call/error (:error result)
                                          :call/ended-at (time/now-str))
                  (if (= :error (:status result))
                    (throw (ex-info "Child process failed"
                                    {:error/type :fractal/child-failed
                                     :error/message "Child process returned error"
                                     :error/data (:error result)
                                     :child/session-id cid
                                     :child/dir child-rel
                                     :error/retryable? false}))
                    value))
                (catch Throwable t
                  (let [err {:error/type :fractal/child-failed
                             :error/message (.getMessage t)
                             :error/data (ex-data t)
                             :child/session-id cid
                             :child/dir child-rel
                             :error/retryable? false}]
                    (artifacts/update-call! state (:call/id call-row) assoc
                                            :call/status :error
                                            :call/error err
                                            :call/ended-at (time/now-str))
                    (throw (ex-info "Child process failed" err t)))))))
          (rlm [task]
            (child-call :child task {}))
          (map-rlm
            ([tasks] (map-rlm tasks nil))
            ([tasks shared-instruction]
             (let [batch-id (str "child-batch-" (java.util.UUID/randomUUID))
                   results (parallel-map-indexed
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
                                  {:ok false :index idx
                                   :error {:error/message (.getMessage t)
                                           :error/data (ex-data t)}})))
                            tasks)]
               (if (every? :ok results)
                 (mapv :value (sort-by :index results))
                 (throw (ex-info "Child batch failed"
                                 {:error/type :fractal/child-batch-failed
                                  :results (vec (sort-by :index results))}))))))]
    {:lm lm :map-lm map-lm :rlm rlm :map-rlm map-rlm}))

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
                            (artifacts/value-ref! (:dir @state) (:eval/raw-final-value result)))
                row (artifacts/add-eval!
                     state
                     (merge (dissoc result :eval/raw-value :eval/raw-final-value)
                            (when final-ref
                              {:eval/final-ref final-ref})
                            {:eval/message-id (:message/id assistant-message)
                             :eval/call-id (:message/call-id assistant-message)
                             :eval/turn-id turn-id
                             :eval/block-index idx
                             :eval/code code
                             :eval/started-at (:eval/started-at result started)}))
                rows' (conj rows row)]
            (artifacts/add-snapshot! state (runtime/snapshot-vars state ns-sym))
            (if (= :final (:eval/status result))
              (do
                (artifacts/mark-final! state (:eval/raw-final-value result))
                (add-observation! state turn-id (runtime/observation rows'))
                {:status :final :value (:eval/raw-final-value result) :final-ref final-ref})
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
                          :turn/usage (artifacts/derive-usage (:dir @state) (:calls @state)))
  (artifacts/add-event! state {:event/type :turn-error :turn/id turn-id :error err})
  (artifacts/mark-error! state err))

(defn run-loop! [state cfg ns-sym turn-id]
  (binding [runtime/*current-turn-id* turn-id]
    (loop [step-n 0]
      (if (>= step-n (:max-turns cfg))
        (let [err {:error/type :fractal/max-turns :max-turns (:max-turns cfg)}]
          (finish-turn-error! state turn-id err)
          {:status :error :error err :dir (:dir @state) :turn-id turn-id})
        (let [request (provider-request (:messages @state)
                                        (cache/request-cache (session-cache-id state) :agent))
              step (try
                     (let [{:keys [call-id response]} (call-provider!
                                                       state cfg :root
                                                       {:call/type :root
                                                        :call/turn-id turn-id
                                                        :call/message-ids (mapv :message/id (:messages @state))
                                                        :request request})
                           content (provider/response-text response)
                           assistant (artifacts/add-message! state :assistant content turn-id)]
                       (swap! state update :messages
                              (fn [messages]
                                (mapv (fn [m]
                                        (if (= (:message/id m) (:message/id assistant))
                                          (assoc m :message/call-id call-id)
                                          m))
                                      messages)))
                       (artifacts/flush! state)
                       (artifacts/add-turn-id! state turn-id :turn/assistant-message-ids (:message/id assistant))
                       (eval-assistant! state cfg ns-sym turn-id (assoc assistant :message/call-id call-id)))
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
               :dir (:dir @state)
               :session-id (:session/id (:session @state))
               :turn-id turn-id})

            (= :final (:status step))
            (let [usage (artifacts/derive-usage (:dir @state) (:calls @state))
                  final-ref (or (:final-ref step)
                                (artifacts/value-ref! (:dir @state) (:value step)))]
              (artifacts/update-turn! state turn-id assoc
                                      :turn/status :final
                                      :turn/ended-at (time/now-str)
                                      :turn/final-ref final-ref
                                      :turn/final-preview (artifacts/project-value (:value step))
                                      :turn/usage usage)
              (artifacts/add-event! state {:event/type :turn-final :turn/id turn-id})
              (artifacts/flush! state)
              {:status :final
               :final-value (:value step)
               :dir (:dir @state)
               :session-id (:session/id (:session @state))
               :turn-id turn-id})

            :else
            (recur (inc step-n))))))))

(defn prepare-turn! [state user-message]
  (let [turn (artifacts/add-turn! state {})
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
  [cfg {:keys [dir id kind parent task messages resume-state ns-sym cache-id] :as opts}]
  (let [cfg (config cfg)
        effective-cfg (child-root-config cfg kind)
        state (or resume-state
                  (artifacts/new-state! {:dir dir
                                         :id id
                                         :cache-id cache-id
                                         :kind (or kind :root)
                                         :provider (provider-shape effective-cfg)
                                         :parent parent}))
        session-id (:session/id (:session @state))
        ns-sym (or ns-sym (runtime/session-ns-symbol session-id))
        ops (make-ops state effective-cfg ns-sym)]
    (runtime/ensure-ns! ns-sym ops)
    (when-not resume-state
      (artifacts/add-message! state :system (if (= :child kind) prompt/child-prompt prompt/system-prompt)))
    (doseq [m messages]
      (artifacts/add-message! state (:role m) (:content m) (:turn-id m)))
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
         dir (artifacts/path (:runs-dir cfg) sid)]
     (run-process! cfg {:dir dir :id sid :kind :root :task task}))))
