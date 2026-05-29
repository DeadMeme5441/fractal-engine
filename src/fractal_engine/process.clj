(ns fractal-engine.process
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provider :as provider]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.time :as time])
  (:import [java.util.concurrent Callable Executors ThreadFactory TimeUnit]))

(declare run-process! run-turn-on-state! child-root-config add-observation!)

(defn config
  ([] (config {}))
  ([m]
   (let [base {:runs-dir "runs"
               :max-turns 25
               :max-fanout 50
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
  (str "You are a leaf: a single probabilistic transformation. One bounded input and "
       "one query turned into one output. You are a pure function whose body happens "
       "to be a language model. You have no tools, no REPL, no memory, and no way to "
       "fetch anything, so do not try to discover the world; work only from the "
       "bounded input you are given, and always read the whole bounded input before "
       "answering. Return only what the query asks for, in the requested shape. If the "
       "input carries identity fields such as :id, :index, :path, :handle, or :lane, "
       "echo that identity in your output so the caller can merge results. When you "
       "classify, use only the supplied label set, and include calibrated uncertainty "
       "when the evidence is ambiguous instead of guessing. Do not invent counts, "
       "totals, or facts the input does not support; if the bounded input is "
       "insufficient, report that inside the requested shape. For EDN mode, return "
       "exactly one EDN value with no prose, no Markdown, and no code fence."))

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

(defn call-blob-path [call-id part]
  (format "blobs/calls/call-%06d-%s.edn" (long call-id) (name part)))

(defn call-value-ref! [state call-id part value]
  (artifacts/value-ref! (:dir @state)
                        value
                        {:path (call-blob-path call-id part)}))

(defn enrich-call [state cfg role call call-id]
  (let [request (:request call)
        cache-request (:request/cache request)
        model-cfg (get-in cfg [:models role])
        cache-id (or (get-in @state [:session :session/cache-id])
                     (get-in @state [:session :session/id]))
        cache-purpose (if (= role :leaf) :leaf :agent)]
    (cond-> (assoc call
                   :call/id call-id
                   :call/provider (:provider model-cfg)
                   :call/model (:model model-cfg)
                   :call/turn-id (or (:call/turn-id call) runtime/*current-turn-id*)
                   :call/request-ref (call-value-ref! state call-id :request request)
                   :call/request-message-count (count (:request/messages request))
                   :call/request-system-hash (request-system-hash request)
                   :call/cache-scope (:scope-id cache-request)
                   :call/cache-label (case cache-purpose
                                       :leaf (cache/leaf-scope cache-id)
                                       :agent (cache/agent-scope cache-id))
                   :call/cache-request cache-request)
      (:call/message-ids call) (assoc :call/request-message-ids (:call/message-ids call)))))

(defn call-provider! [state cfg role call]
  (let [reserved-call-id (artifacts/next-counter! state :call)
        call-row (artifacts/add-call! state (enrich-call state cfg role call reserved-call-id))
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
                            :call/response-ref (call-value-ref! state call-id :response response)
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

(defn bounded-fanout-inputs [kind cfg inputs]
  (let [max-fanout (:max-fanout cfg)
        xs (vec (take (inc max-fanout) inputs))]
    (if (> (count xs) max-fanout)
      (throw (ex-info "Fanout limit exceeded"
                      {:error/type :fractal/fanout-exceeded
                       :fanout/kind kind
                       :fanout/max max-fanout
                       :fanout/count-at-least (count xs)
                       :error/retryable? false}))
      xs)))

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
  [state source-dir ns-sym snapshot-row snapshot-blob lineage-kind lineage-parents source-fingerprint]
  (let [source-dir (artifacts/path source-dir)
        message-through-id (:snapshot/message-through-id snapshot-row)
        source-session-id (snapshot/source-session-id source-dir)
        messages (snapshot/messages-through source-dir message-through-id)
        max-message-id (apply max 0 (map :message/id messages))
        source-turn-id (:snapshot/turn-id snapshot-row)
        started-at (time/now-str)
        restore-result (snapshot/restore-vars! source-dir ns-sym snapshot-blob)
        ended-at (time/now-str)
        restore-report {:restore/version 1
                        :restore/strategy :snapshot-vars
                        :restore/source (str source-dir)
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
                 :lineage/source {:source/path (str source-dir)
                                  :source/session-id source-session-id
                                  :source/fingerprint source-fingerprint
                                  :source/turn-id source-turn-id
                                  :source/snapshot-id (:snapshot/id snapshot-row)}
                 :lineage/parents lineage-parents
                 :lineage/created-at ended-at}]
    (swap! state assoc :messages messages)
    (swap! state update :counters merge {:message max-message-id
                                         :turn (long source-turn-id)})
    (swap! state update :session assoc
           :session/restored-from (:lineage/source lineage)
           :session/lineage-kind lineage-kind
           :session/latest-turn-id source-turn-id)
    (snapshot/write-restore-report! (:dir @state) restore-report)
    (snapshot/write-lineage! (:dir @state) lineage)
    (artifacts/add-event! state {:event/type :restore-end
                                 :restore/strategy :snapshot-vars
                                 :restore/status (:restore/status restore-report)
                                 :snapshot/id (:snapshot/id snapshot-row)
                                 :turn/id source-turn-id})
    (artifacts/flush! state)
    {:restore-report restore-report
     :lineage lineage}))

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
             (let [inputs' (bounded-fanout-inputs :leaf cfg inputs)
                   batch-id (str "leaf-batch-" (java.util.UUID/randomUUID))
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
                            inputs')]
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
                                                :task (child-task-prompt task)})
                      value (:final-value result)]
                  (artifacts/update-call! state (:call/id call-row) assoc
                                          :call/status (:status result)
                                          :call/final-ref (artifacts/value-ref! (:dir @state) value)
                                          :call/error (:error result)
                                          :call/ended-at (time/now-str))
                  (cond
                    (= :error (:status result))
                    (throw (ex-info "Child process failed"
                                    {:error/type :fractal/child-failed
                                     :error/message "Child process returned error"
                                     :error/data (:error result)
                                     :child/session-id cid
                                     :child/dir child-rel
                                     :error/retryable? false}))
                    (not (contains? result :final-value))
                    (throw (ex-info "Child process did not return FINAL"
                                    {:error/type :fractal/child-no-final
                                     :error/message "Child process returned without a FINAL value"
                                     :child/session-id cid
                                     :child/dir child-rel
                                     :error/retryable? false}))
                    :else
                    value))
                (catch Throwable t
                  (let [data (ex-data t)
                        err (merge {:error/type :fractal/child-failed
                                    :error/message (.getMessage t)
                                    :error/data data
                                    :child/session-id cid
                                    :child/dir child-rel
                                    :error/retryable? false}
                                   (select-keys data [:error/type :child/session-id :child/dir :error/retryable?]))]
                    (artifacts/update-call! state (:call/id call-row) assoc
                                            :call/status :error
                                            :call/error err
                                            :call/ended-at (time/now-str))
                    (throw (ex-info "Child process failed" err t)))))))
          (attach-call [source-path task opts]
            (let [source-dir (artifacts/path source-path)
                  source-fingerprint (snapshot/session-fingerprint source-dir)
                  snapshot-row (try
                                 (snapshot/require-snapshot source-dir opts)
                                 (catch Throwable t
                                   (throw (ex-info "Attach source has no completed turn snapshot"
                                                   {:error/type :attach/snapshot-not-found
                                                    :error/data (ex-data t)
                                                    :source/path (str source-dir)}
                                                   t))))
                  snapshot-blob (try
                                  (snapshot/require-snapshot-blob source-dir snapshot-row)
                                  (catch Throwable t
                                    (throw (ex-info "Attach snapshot restore failed"
                                                    {:error/type :attach/restore-failed
                                                     :error/data (ex-data t)
                                                     :source/path (str source-dir)
                                                     :snapshot/id (:snapshot/id snapshot-row)}
                                                    t))))
                  child-num (artifacts/next-counter! state :child)
                  cid (attached-child-id child-num)
                  child-rel (str "children/" cid)
                  child-dir (artifacts/path (:dir @state) child-rel)
                  parent-cache-id (session-cache-id state)
                  child-cache-id (str parent-cache-id "/" child-rel)
                  call-row (artifacts/add-call! state {:call/type :attached-child
                                                       :call/turn-id runtime/*current-turn-id*
                                                       :edge/type :attached
                                                       :call/parent-eval-id runtime/*current-eval-id*
                                                       :attach/source-path (str source-dir)
                                                       :attach/source-fingerprint source-fingerprint
                                                       :attach/source-turn-id (:snapshot/turn-id snapshot-row)
                                                       :attach/source-snapshot-id (:snapshot/id snapshot-row)
                                                       :child/session-id cid
                                                       :child/cache-id child-cache-id
                                                       :child/dir child-rel})
                  parent {:parent/session-id (:session/id (:session @state))
                          :parent/cache-id parent-cache-id
                          :parent/call-id (:call/id call-row)
                          :parent/eval-id runtime/*current-eval-id*
                          :parent/relative-dir ".."}
                  child-state (artifacts/new-state! {:dir child-dir
                                                     :id cid
                                                     :cache-id child-cache-id
                                                     :kind :child
                                                     :provider (provider-shape (child-root-config cfg :child))
                                                     :parent parent})
                  child-ns (runtime/session-ns-symbol cid)
                  child-cfg (child-root-config cfg :child)
                  child-ops (make-ops child-state child-cfg child-ns)]
              (try
                (artifacts/add-event! state {:event/type :attach-rlm-start
                                             :call/id (:call/id call-row)
                                             :snapshot/id (:snapshot/id snapshot-row)})
                (runtime/ensure-ns! child-ns child-ops)
                (restore-state-from-snapshot!
                 child-state
                 source-dir
                 child-ns
                 snapshot-row
                 snapshot-blob
                 :attached-child
                 [{:parent/kind :child-of
                   :parent/path (str (:dir @state))
                   :parent/call-id (:call/id call-row)
                   :parent/eval-id runtime/*current-eval-id*}
                  {:parent/kind :attached-from
                   :parent/path (str source-dir)
                   :parent/turn-id (:snapshot/turn-id snapshot-row)
                   :parent/snapshot-id (:snapshot/id snapshot-row)}]
                 source-fingerprint)
                (let [result (run-turn-on-state! child-state child-cfg child-ns task)
                      value (:final-value result)]
                  (artifacts/update-status! child-state (if (= :error (:status result)) :error :stopped))
                  (artifacts/add-event! child-state {:event/type :session-stopped
                                                     :session/id (:session/id (:session @child-state))})
                  (artifacts/update-call! state (:call/id call-row) assoc
                                          :call/status (:status result)
                                          :call/final-ref (artifacts/value-ref! (:dir @state) value)
                                          :call/error (:error result)
                                          :call/ended-at (time/now-str))
                  (artifacts/add-event! state {:event/type :attach-rlm-end
                                               :call/id (:call/id call-row)
                                               :call/status (:status result)})
                  (cond
                    (= :error (:status result))
                    (throw (ex-info "Attached child returned error"
                                    {:error/type :attach/child-error
                                     :error/data (:error result)
                                     :child/session-id cid
                                     :child/dir child-rel}))
                    (not (contains? result :final-value))
                    (throw (ex-info "Attached child did not return FINAL"
                                    {:error/type :attach/no-final
                                     :child/session-id cid
                                     :child/dir child-rel}))
                    :else value))
                (catch Throwable t
                  (let [err (merge {:error/type :attach/child-error
                                    :error/message (.getMessage t)
                                    :child/session-id cid
                                    :child/dir child-rel}
                                   (select-keys (ex-data t) [:error/type :error/data]))]
                    (artifacts/update-call! state (:call/id call-row) assoc
                                            :call/status :error
                                            :call/error err
                                            :call/ended-at (time/now-str))
                    (artifacts/add-event! state {:event/type :attach-rlm-error
                                                 :call/id (:call/id call-row)
                                                 :error err})
                    (throw (ex-info "Attach RLM failed" err t)))))))
          (rlm [task]
            (child-call :child task {}))
          (map-rlm
            ([tasks] (map-rlm tasks nil))
            ([tasks shared-instruction]
             (let [tasks' (bounded-fanout-inputs :child cfg tasks)
                   batch-id (str "child-batch-" (java.util.UUID/randomUUID))
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
                            tasks')]
               (if (every? :ok results)
                 (mapv :value (sort-by :index results))
                 (throw (ex-info "Child batch failed"
                                 {:error/type :fractal/child-batch-failed
                                  :results (vec (sort-by :index results))}))))))
          (attach-rlm
            ([path task] (attach-rlm path task {}))
            ([path task opts] (attach-call path task opts)))]
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
        (do
          (maybe-add-child-finalization-warning! state cfg turn-id step-n)
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
              (snapshot/write-turn-snapshot! state
                                             ns-sym
                                             (artifacts/current-turn state turn-id)
                                             (:eval-row step))
              (artifacts/flush! state)
              {:status :final
               :final-value (:value step)
               :dir (:dir @state)
               :session-id (:session/id (:session @state))
               :turn-id turn-id})

            :else
            (recur (inc step-n)))))))))

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
