(ns fractal-engine.leaf
  "Leaf LM execution.

  Leaves are bounded semantic calls inside the caller's session state. They write
  call rows and payload refs only; they never create sessions, heads, or
  invocation edges."
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.concurrent :as concurrent]
            [fractal-engine.provider :as provider]
            [fractal-engine.provider-call :as provider-call]
            [fractal-engine.rlm :as rlm]
            [fractal-engine.runtime :as runtime]))

(defn call!
  [state cfg call-type input query mode extra]
  (let [call (merge {:call/type call-type
                     :call/turn-id runtime/*current-turn-id*
                     :call/parent-eval-id runtime/*current-eval-id*
                     :call/status :running
                     :call/input-ref (artifacts/value-ref! (:locator @state) input)
                     :call/query query
                     :call/mode mode
                     :request (provider-call/leaf-request
                               input query
                               (cache/request-cache (provider-call/session-cache-id state) :leaf))}
                    extra)
        {:keys [call-id response]} (provider-call/call-provider! state cfg :leaf call)
        text (provider/response-text response)
        value (try
                (provider-call/parse-leaf text mode)
                (catch Throwable t
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

(defn lm-fn
  [state cfg]
  (fn
    ([input query] ((lm-fn state cfg) input query :string))
    ([input query mode]
     (call! state cfg :leaf input query mode {}))))

(defn map-lm-fn
  [state cfg]
  (fn
    ([inputs query] ((map-lm-fn state cfg) inputs query :string))
    ([inputs query mode]
     (let [inputs' (provider-call/bounded-fanout-inputs :leaf cfg inputs)
           batch-id (str "leaf-batch-" (java.util.UUID/randomUUID))
           results (concurrent/parallel-map-indexed
                    "fractal-map-lm"
                    (fn [idx input]
                      (try
                        {:ok true
                         :index idx
                         :value (call! state cfg :leaf-batch-item input query mode
                                       {:batch/id batch-id :batch/index idx})}
                        (catch Throwable t
                          {:ok false :index idx
                           :error (merge {:error/message (.getMessage t)
                                          :error/data (ex-data t)}
                                         (select-keys (ex-data t) [:error/type]))})))
                    inputs')]
       (rlm/assemble-batch-results results)))))

(defn ops
  [state cfg]
  {:lm (lm-fn state cfg)
   :map-lm (map-lm-fn state cfg)})
