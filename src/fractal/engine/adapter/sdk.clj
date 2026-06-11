(ns fractal.engine.adapter.sdk
  "L2 · SdkAdapter (05 §2, §6) — the ONLY impl that crosses the network. Wraps
   `llm.sdk/complete`. NO internal deadline (run-step! applies with-deadline
   around the whole call). Honest-:unknown normalization; maps the SDK Response
   onto the engine's bare-:cache call record."
  (:require [fractal.engine.adapter :as adapter]
            [llm.sdk :as sdk]))

;; ---------------------------------------------------------------------------
;; Request: narrowed engine wire request → canonical SDK request (verified shape)
;; ---------------------------------------------------------------------------

(def ^:private sdk-cache-keys
  "The SDK Request's CLOSED cache-opts contract (its schema rejects unknown
   keys — verified live: an extra key fails the whole request as 'Invalid
   llm.sdk chat request'). The engine's cache map may carry richer data
   (e.g. :purpose, which replay routes by); THIS seam narrows to the contract."
  [:enabled? :ttl :scope-id :breakpoints])

(defn ->sdk-request
  "The §1 narrowed wire request ({:model :messages [{:role :content}] :cache}) →
   the SDK's canonical Request. NB: the SDK Message uses NAMESPACED keys
   (:message/role / :message/content — verified against the 0.2.3 schema)."
  [request]
  (cond-> {:request/model    (:model request)
           :request/messages (mapv (fn [m] {:message/role    (:role m)
                                            :message/content (:content m)})
                                   (:messages request))}
    (:cache request) (assoc :request/cache (select-keys (:cache request) sdk-cache-keys))))

;; ---------------------------------------------------------------------------
;; Response: SDK Response → the §1 call record (honest :unknown)
;; ---------------------------------------------------------------------------

(defn- parts->text [parts]
  (->> parts (filter #(= :text (:part/type %))) (map :text) (apply str)))

(defn- norm-usage [u]
  (if u
    (merge {:usage/cached-input-tokens :unknown :usage/cache-write-tokens :unknown}
           u
           {:usage/status :known})
    adapter/unknown-usage))

(defn- norm-cost [c]
  (cond
    (nil? c)                   adapter/unknown-cost
    (= :unknown (:cost/usd c)) (assoc c :cost/status :unknown)
    :else                      (assoc c :cost/status :known)))

(defn- norm-cache [c] (or c adapter/unknown-cache))

(defn sdk-response->call-record
  "Map a Response onto the call record: text from :response/parts, usage/cost
   normalized (absent ⇒ :unknown), :response/cache → the BARE :cache (GD30)."
  [resp request]
  {:text          (parts->text (:response/parts resp))
   :finish-reason (or (:response/finish-reason resp) :unknown)
   :usage         (norm-usage (:response/usage resp))
   :cost          (norm-cost (:response/cost resp))
   :model         (or (:response/model resp) (:model request))
   :provider      (:response/provider resp)
   :cache         (norm-cache (:response/cache resp))})

(defn- content-delta [ev]
  (when (= :stream/content-delta (:event/type ev)) (:event/delta ev)))

(defn- sdk-config
  "The SDK :config for this provider — the caller's credential override when
   present, else nil/empty ⇒ the SDK's own env-var defaults (D9)."
  [provider-config]
  (when (seq provider-config) provider-config))

(defrecord SdkAdapter [provider-id provider-config]
  adapter/LlmAdapter
  (-complete [_ request {:keys [retry stream? on-delta]}]
    (let [resp (sdk/complete
                 provider-id
                 (->sdk-request request)
                 :stream?  stream?
                 :on-event (when stream? (fn [ev] (when-let [d (content-delta ev)] (on-delta d))))
                 :retry    (when-not stream? retry)   ; ⛔ streaming ⇒ NO retry (§3)
                 :config   (sdk-config provider-config))]
      (sdk-response->call-record resp request))))

(defn sdk-adapter
  "Construct an SdkAdapter for a resolved provider-id + optional credential
   override (provider-config; nil/empty ⇒ SDK env defaults)."
  [provider-id provider-config]
  (->SdkAdapter provider-id provider-config))
