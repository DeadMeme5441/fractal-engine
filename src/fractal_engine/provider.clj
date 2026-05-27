(ns fractal-engine.provider
  (:require [clojure.string :as str]
            [llm.sdk :as sdk]))

(def default-models
  {:root {:provider :scripted :model "scripted"}
   :leaf {:provider :scripted :model "scripted"}
   :child {:provider :scripted :model "scripted"}})

(defn dotenv []
  (let [f (java.io.File. ".env")]
    (if (.exists f)
      (into {}
            (keep (fn [line]
                    (when-let [[_ k v] (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)\s*$" line)]
                      [k (str/replace v #"^['\"]|['\"]$" "")])))
            (str/split-lines (slurp f)))
      {})))

(def provider-env
  {:openai "OPENAI_API_KEY"
   :anthropic "ANTHROPIC_API_KEY"
   :openrouter "OPENROUTER_API_KEY"
   :deepseek "DEEPSEEK_API_KEY"
   :kimi-code "KIMI_API_KEY"
   :cohere "COHERE_API_KEY"})

(defn api-key-config [provider-id]
  (when-let [env-name (provider-env provider-id)]
    (when-let [v (or (System/getenv env-name) (get (dotenv) env-name))]
      {:api-key v})))

(defn response-text [resp]
  (->> (:response/parts resp)
       (keep (fn [part]
               (or (:text part)
                   (:part/text part))))
       (str/join "\n")))

(defn scripted-response [config request]
  (let [response-fn (:scripted/response-fn config)
        script (:scripted/responses config)
        value (cond
                response-fn (response-fn request)
                (instance? clojure.lang.Atom script) (let [xs @script]
                                                       (when (empty? xs)
                                                         (throw (ex-info "Scripted provider exhausted"
                                                                         {:error/type :scripted/exhausted})))
                                                       (let [x (first xs)]
                                                         (swap! script subvec 1)
                                                         x))
                (sequential? script) (first script)
                :else "```clojure\n(FINAL :ok)\n```")
        base {:response/provider :scripted
              :response/model (:request/model request "scripted")
              :response/finish-reason :stop
              :response/usage {:usage/status :unknown}
              :response/cost {:cost/usd :unknown}
              :response/cache {:cache/status :unknown}
              :response/raw {:scripted? true}}]
    (if (map? value)
      (merge base value)
      (assoc base :response/parts [{:part/type :text :text value}]))))

(defn complete
  [config role request]
  (let [model-cfg (get-in config [:models role] (get default-models role))
        provider-id (:provider model-cfg)
        request' (assoc request :request/model (:model model-cfg))]
    (if (= :scripted provider-id)
      (scripted-response config request')
      (sdk/complete provider-id
                    request'
                    :retry (:retry config)
                    :config (merge (api-key-config provider-id)
                                   (:provider/config config))))))
