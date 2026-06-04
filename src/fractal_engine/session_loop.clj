(ns fractal-engine.session-loop
  "Session input execution.

  A session input is one caller/user handoff into the persistent RLM session. The
  loop alternates provider messages, Clojure eval, and observations until FINAL
  publishes a new immutable head. The persisted row is still named `turn` for the
  public/read surface, but this namespace treats it as the input record for one
  head-boundary transition."
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.provider :as provider]
            [fractal-engine.provider-call :as provider-call]
            [fractal-engine.rlm :as rlm]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.time :as time]))

(defn add-observation!
  [state turn-id content]
  (let [message (artifacts/add-message! state :observation content turn-id)]
    (artifacts/add-turn-id! state turn-id :turn/observation-message-ids (:message/id message))
    message))

(defn eval-assistant!
  [state _cfg ns-sym turn-id assistant-message]
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

(defn finish-input-error!
  [state turn-id err]
  (artifacts/update-turn! state turn-id assoc
                          :turn/status :error
                          :turn/ended-at (time/now-str)
                          :turn/error err
                          :turn/usage (artifacts/derive-usage (:locator @state) (:calls @state)))
  (artifacts/add-event! state {:event/type :turn-error :turn/id turn-id :error err})
  (artifacts/mark-error! state err))

(defn- error-result
  [state turn-id err]
  {:status :error
   :error err
   :locator (:locator @state)
   :session-id (:session/id (:session @state))
   :logical-session-id (artifacts/logical-session-id state)
   :cache-id (provider-call/session-cache-id state)
   :store-root (str (rlm/state-store-root state))
   :turn-id turn-id})

(defn- commit-final-head!
  [state ns-sym turn-id step]
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
       :turn-id turn-id})))

(defn run-loop!
  [state cfg ns-sym turn-id {:keys [before-step]}]
  (binding [runtime/*current-turn-id* turn-id]
    (loop [step-n 0]
      (if (>= step-n (:max-turns cfg))
        (let [err {:error/type :fractal/max-turns :max-turns (:max-turns cfg)}]
          (finish-input-error! state turn-id err)
          (error-result state turn-id err))
        (do
          (when before-step
            (before-step state cfg turn-id step-n))
          (let [request (provider-call/provider-request
                         (:messages @state)
                         (cache/request-cache (provider-call/session-cache-id state)
                                              :agent (:cache-ttl cfg)))
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
                (finish-input-error! state turn-id err)
                (error-result state turn-id err))

              (= :final (:status step))
              (commit-final-head! state ns-sym turn-id step)

              :else
              (recur (inc step-n)))))))))

(defn open-input!
  [state user-message]
  (let [turn (artifacts/add-turn! state {:turn/head-before (artifacts/current-head-id state)})
        message (artifacts/add-message! state :user user-message (:turn/id turn))]
    (artifacts/update-turn! state (:turn/id turn) assoc
                            :turn/user-message-id (:message/id message))
    (:turn/id turn)))

(defn run-input!
  ([state cfg ns-sym user-message]
   (run-input! state cfg ns-sym user-message {}))
  ([state cfg ns-sym user-message opts]
   (let [turn-id (open-input! state user-message)]
     (run-loop! state cfg ns-sym turn-id opts))))
