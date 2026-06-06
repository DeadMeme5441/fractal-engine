(ns fractal-engine.provider
  "The provider boundary. A provider is a *value*: a descriptor that says how it
  authenticates, not a scatter of hardcoded branches and prose gotchas. The engine
  only needs two things from a provider — how to obtain its auth (so it can pass an
  api-key to the SDK when required) and how to reach it (the offline scripted fake
  vs the real SDK). Everything the engine knows about a provider lives in
  `providers` below, as data."
  (:require [clojure.string :as str]
            [llm.sdk :as sdk]))

(def default-models
  {:root {:provider :scripted :model "scripted"}
   :leaf {:provider :scripted :model "scripted"}
   :child {:provider :scripted :model "scripted"}})

(def providers
  "Provider descriptors. `:auth` is the source of credentials:
     :api-key          read the named env var (or .env) and hand it to the SDK
     :oauth-file       the SDK reads a credentials file itself; the engine supplies nothing
     :adc              Application Default Credentials; the SDK gets the token, but the
                       listed env vars must be exported into the JVM (the .env loader does
                       NOT push them to System/getenv)
     :none             the offline scripted fake; no network, no credentials
     :custom-endpoint  any OpenAI-compatible HTTP endpoint. Requires :env-base-url; the
                       API key is optional (:env-api-key) for endpoints that need one.
                       Both are forwarded to the SDK as runtime config.
  This table is the single source of truth — it replaces the old hardcoded env map
  and the Codex/Vertex auth notes that used to live only in prose docs."
  {:scripted        {:auth :none}
   :openai          {:auth :api-key :env "OPENAI_API_KEY"}
   :anthropic       {:auth :api-key :env "ANTHROPIC_API_KEY"}
   :openrouter      {:auth :api-key :env "OPENROUTER_API_KEY"}
   :deepseek        {:auth :api-key :env "DEEPSEEK_API_KEY"}
   :kimi-code       {:auth :api-key :env "KIMI_API_KEY"}
   :cohere          {:auth :api-key :env "COHERE_API_KEY"}
   :codex           {:auth :api-key :env "OPENAI_API_KEY"
                     :notes "API-key path; :codex-backend is the OAuth path."}
   :codex-backend   {:auth :oauth-file :file "~/.codex/auth.json"
                     :notes "OAuth via Codex; the SDK reads the file. No api-key needed."}
   :vertex-gemini   {:auth :adc
                     :env-required ["GOOGLE_CLOUD_PROJECT" "GOOGLE_CLOUD_LOCATION"]
                     :notes "ADC supplies the token; the listed env vars must be exported into the JVM env."}
   :custom-endpoint {:auth :custom-endpoint
                     :env-base-url "CUSTOM_ENDPOINT_BASE_URL"
                     :env-api-key  "CUSTOM_ENDPOINT_API_KEY"
                     :notes "OpenAI-compatible endpoint. Set CUSTOM_ENDPOINT_BASE_URL; CUSTOM_ENDPOINT_API_KEY is optional."}})

(defn descriptor
  "The descriptor for a provider id. Unknown providers default to :sdk-default —
  the SDK is left to authenticate however it sees fit."
  [provider-id]
  (get providers provider-id {:auth :sdk-default}))

(defn dotenv []
  (let [f (java.io.File. ".env")]
    (if (.exists f)
      (into {}
            (keep (fn [line]
                    (when-let [[_ k v] (re-matches #"^\s*([A-Za-z_][A-Za-z0-9_]*)=(.*)\s*$" line)]
                      [k (str/replace v #"^['\"]|['\"]$" "")])))
            (str/split-lines (slurp f)))
      {})))

(defn env-value [k]
  (or (System/getenv k) (get (dotenv) k)))

(defn api-key-config
  "The runtime config map the SDK needs for a provider.
   :api-key providers return {:api-key ...}.
   :custom-endpoint returns {:base-url ...} plus {:api-key ...} when a key is set.
   Other auth sources (:oauth-file, :adc, :none, :sdk-default) return nil — the SDK
   handles them itself."
  [provider-id]
  (let [d (descriptor provider-id)]
    (case (:auth d)
      :api-key
      (when-let [v (env-value (:env d))]
        {:api-key v})

      :custom-endpoint
      (let [base-url (env-value (:env-base-url d))
            api-key  (env-value (:env-api-key d))]
        (cond-> {:base-url (or base-url "")}
          api-key (assoc :api-key api-key)))

      nil)))

(defn auth-status
  "Introspect whether a provider's auth is satisfied — provider-as-value pays off:
  you can ask a provider what it needs and whether it's available, as data."
  [provider-id]
  (let [d (descriptor provider-id)]
    (case (:auth d)
      :none {:provider provider-id :auth :none :satisfied? true}
      :api-key {:provider provider-id :auth :api-key :env (:env d)
                :satisfied? (some? (env-value (:env d)))}
      :oauth-file {:provider provider-id :auth :oauth-file :file (:file d)
                   :satisfied? (.exists (java.io.File. (str/replace (:file d) #"^~" (System/getProperty "user.home"))))}
      :adc {:provider provider-id :auth :adc
            :env-required (:env-required d)
            :satisfied? (every? #(some? (System/getenv %)) (:env-required d))}
      :custom-endpoint {:provider provider-id :auth :custom-endpoint
                        :env-base-url (:env-base-url d)
                        :env-api-key  (:env-api-key d)
                        :satisfied? (some? (env-value (:env-base-url d)))}
      {:provider provider-id :auth (:auth d) :satisfied? :unknown})))

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

(defn last-user-text
  "The text of the last user message in a request — the content that distinguishes
  one scripted call from another (root step vs leaf input vs child task)."
  [request]
  (->> (:request/messages request)
       (filter #(= :user (:message/role %)))
       last
       :message/content
       str))

(defn responder
  "Build a content-addressed scripted responder suitable for `:scripted/response-fn`.

  `clauses` is a sequence of [match reply] pairs; the first whose `match` applies
  wins. `match` may be:
    - a string  — matches when it is a substring of the last user message
    - a fn      — called with the request; truthy means match
    - :default  — always matches (use as the final clause)
  `reply` may be a string, a full response map, or a fn of the request returning
  either.

  Because the returned fn is pure in the request, it is race-free under the
  concurrent fanout of `map-lm`/`map-rlm` — unlike a shared `:scripted/responses`
  queue, whose order is undefined once leaves run in parallel. Throws
  `:scripted/no-match` if no clause matches, so an unscripted path fails loudly."
  [clauses]
  (let [clauses (vec clauses)]
    (fn [request]
      (let [text (last-user-text request)
            hit  (some (fn [[match reply]]
                         (when (or (= :default match)
                                   (and (string? match) (str/includes? text match))
                                   (and (fn? match) (match request)))
                           [reply]))
                       clauses)]
        (if hit
          (let [reply (first hit)]
            (if (fn? reply) (reply request) reply))
          (throw (ex-info "No scripted clause matched the request"
                          {:error/type :scripted/no-match
                           :request/last-user text})))))))

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
