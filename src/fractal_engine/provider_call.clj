(ns fractal-engine.provider-call
  "Provider request construction and traced completion calls.

  This namespace owns the mechanics of turning session messages into provider
  requests, recording request/response payload refs, and parsing leaf results.
  It does not own the recursive loop or session/head semantics."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.call :as call]
            [fractal-engine.concurrent :as concurrent]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provider :as provider]
            [fractal-engine.runtime :as runtime]))

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

(defn leaf-request [input query cache-request]
  {:request/messages [{:message/role :system
                       :message/content prompt/leaf-prompt}
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

(defn root-request-descriptor [model-cfg call request cache-request]
  (cond-> {:request/version 2
           :request/kind (or (:call/request-kind call) :root-agent)
           :request/rendered? false
           :request/message-ids (:call/message-ids call)
           :request/message-count (count (:request/messages request))
           :request/system-hash (request-system-hash request)
           :request/cache cache-request
           :request/provider (:provider model-cfg)
           :request/model (:model model-cfg)}
    (:call/purpose call) (assoc :request/purpose (:call/purpose call))
    (:call/source-head-id call) (assoc :request/source-head-id (:call/source-head-id call))
    (:call/source-message-count call) (assoc :request/source-message-count (:call/source-message-count call))))

(defn call-payload-ref! [state part value]
  (artifacts/value-ref! (:locator @state)
                        value
                        {:payload/kind (keyword "call" (name part))}))

(defn enrich-call [state cfg role call call-id]
  (let [request (:request call)
        cache-request (:request/cache request)
        model-cfg (get-in cfg [:models role])
        request-payload (if (= :root role)
                          (root-request-descriptor model-cfg call request cache-request)
                          request)
        cache-id (or (get-in @state [:session :session/cache-id])
                     (get-in @state [:session :session/id]))
        cache-purpose (if (= role :leaf) :leaf :agent)]
    ;; The provider still receives the rendered request above; durable root calls
    ;; store a descriptor keyed by message ids so completed heads do not duplicate
    ;; the full growing transcript.
    (cond-> (assoc (dissoc call :request)
                   :call/id call-id
                   :call/provider (:provider model-cfg)
                   :call/model (:model model-cfg)
                   :call/turn-id (or (:call/turn-id call) runtime/*current-turn-id*)
                   :call/request-ref (call-payload-ref! state :request request-payload)
                   :call/request-storage (if (= :root role) :descriptor :rendered)
                   :call/request-message-count (count (:request/messages request))
                   :call/request-system-hash (request-system-hash request)
                   :call/cache-scope (:scope-id cache-request)
                   :call/cache-label (case cache-purpose
                                       :leaf (cache/leaf-scope cache-id)
                                       :agent (cache/agent-scope cache-id))
                   :call/cache-request cache-request)
      (:call/message-ids call) (assoc :call/request-message-ids (:call/message-ids call)))))

(defn call-provider!
  "A provider completion as a traced call. Returns {:call-id .. :response ..}."
  [state cfg role call]
  (call/traced!
   state
   {:build-call (fn [call-id] (enrich-call state cfg role call call-id))
    :work (fn [_call-id]
            (let [complete! #(concurrent/with-deadline (:call-timeout-ms cfg)
                               (fn [] (provider/complete cfg role (:request call))))]
              (if (= :leaf role)
                (concurrent/with-permit (:leaf-concurrency/limiter cfg) complete!)
                (complete!))))
    :succeed (fn [call-id response]
               {:value {:call-id call-id :response response}
                :patch {:call/status :ok
                        :call/response-ref (call-payload-ref! state :response response)
                        :call/usage (:response/usage response {:usage/status :unknown})
                        :call/cost (:response/cost response {:cost/usd :unknown})
                        :call/cache (:response/cache response {:cache/status :unknown})}})
    :fail (fn [call-id t]
            (let [model-cfg (get-in cfg [:models role])
                  err {:error/type :provider/failed
                       :error/message (.getMessage t)
                       :error/data (ex-data t)
                       :error/provider (:provider model-cfg)
                       :error/model (:model model-cfg)
                       :error/role role
                       :error/retryable? false}]
              (artifacts/add-event! state {:event/type :provider-failed
                                           :call/id call-id
                                           :error err})
              {:patch {:call/error err
                       :call/usage {:usage/status :unknown}
                       :call/cost {:cost/status :unknown}
                       :call/cache {:cache/status :unknown}}
               :ex (ex-info "Provider call failed" err t)}))}))

(defn strip-edn-fence
  "Drop an enclosing ```edn|clojure|clj fence if the model wrapped its EDN output
  despite the leaf prompt's instruction not to. Unfenced text is returned untouched,
  so genuinely malformed output still fails to read (and surfaces to the model as a
  batch failure) — this only forgives the common, harmless fence wrapper."
  [text]
  (-> (str text)
      (str/replace #"(?s)\A\s*```(?:edn|clojure|clj)?[ \t]*\r?\n?" "")
      (str/replace #"(?s)\r?\n?```\s*\z" "")
      str/trim))

(defn parse-leaf [text mode]
  (case mode
    :string text
    :edn (edn/read-string (strip-edn-fence text))
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
                       :fanout/strategy "Partition inputs into batches of 40-50, call map-lm/map-rlm once per batch, reduce chunk results locally, then reduce globally."
                       :error/retryable? true}))
      xs)))

(defn session-cache-id [state]
  (or (get-in @state [:session :session/cache-id])
      (get-in @state [:session :session/id])))
