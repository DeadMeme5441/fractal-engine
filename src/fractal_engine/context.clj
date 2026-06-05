(ns fractal-engine.context
  "Provider-visible transcript accounting and compact-frame rendering.

  This namespace is deliberately value-only: it does not mutate sessions, create
  heads, or restore vars. Session code owns those state transitions."
  (:require [clojure.string :as str]
            [fractal-engine.cache :as cache]
            [llm.sdk :as sdk]))

(def default-policy
  {:auto-compact? true
   :compact-at-ratio 0.80
   :hard-ratio 0.95
   :output-reserve-tokens 8192
   :compact-frame-target-tokens 2048
   :state-facts-max-chars 8000
   ;; A conservative estimator for mixed code/prose when no provider tokenizer is
   ;; available through the SDK.
   :chars-per-token 3
   :message-overhead-tokens 8
   :request-overhead-tokens 16})

(defn normalize-policy [policy]
  (merge default-policy (or policy {})))

(defn estimate-text-tokens
  ([text] (estimate-text-tokens default-policy text))
  ([policy text]
   (let [chars-per-token (double (max 1 (:chars-per-token (normalize-policy policy))))]
     (long (Math/ceil (/ (count (str text)) chars-per-token))))))

(defn estimate-message-tokens
  ([message] (estimate-message-tokens default-policy message))
  ([policy message]
   (+ (long (:message-overhead-tokens (normalize-policy policy)))
      (estimate-text-tokens policy (:message/content message)))))

(defn estimate-request-tokens
  ([messages] (estimate-request-tokens default-policy messages))
  ([policy messages]
   (+ (long (:request-overhead-tokens (normalize-policy policy)))
      (reduce + 0 (map #(estimate-message-tokens policy %) messages)))))

(defn model-window-tokens [cfg role]
  (let [policy (normalize-policy (:context cfg))
        model-cfg (get-in cfg [:models role])
        provider-id (:provider model-cfg)
        model-id (:model model-cfg)]
    (or (:window-tokens policy)
        (:context-window-tokens policy)
        (:context-window-tokens model-cfg)
        (:context-window model-cfg)
        (when model-id
          (try
            (or (when provider-id
                  (sdk/model-context-length provider-id model-id))
                (sdk/model-context-length model-id))
            (catch Throwable _ nil))))))

(defn- input-budget [window policy ratio-key]
  (let [window (long window)
        ratio (double (get policy ratio-key))
        reserve (long (:output-reserve-tokens policy))]
    (max 1 (- (long (Math/floor (* window ratio))) reserve))))

(defn assess-request
  "Return nil when the model's context window is unknown; otherwise return an
  estimate/budget map for provider input messages."
  [cfg role messages]
  (let [policy (normalize-policy (:context cfg))]
    (when-let [window (model-window-tokens cfg role)]
      (let [estimate (estimate-request-tokens policy messages)
            compact-budget (input-budget window policy :compact-at-ratio)
            hard-budget (input-budget window policy :hard-ratio)]
        {:context/window-tokens window
         :context/estimated-input-tokens estimate
         :context/compact-budget-tokens compact-budget
         :context/hard-budget-tokens hard-budget
         :context/output-reserve-tokens (:output-reserve-tokens policy)
         :context/over-compact? (> estimate compact-budget)
         :context/over-hard? (> estimate hard-budget)}))))

(defn prospective-turn-messages [messages user-message]
  (conj (vec messages)
        {:message/role :user
         :message/content user-message}))

(defn should-compact-before-turn? [cfg role messages user-message]
  (let [policy (normalize-policy (:context cfg))
        prospective (prospective-turn-messages messages user-message)]
    (boolean
     (and (:auto-compact? policy)
          (> (count messages) 2)
          (some-> (assess-request cfg role prospective)
                  :context/over-compact?)))))

(def compaction-system-prompt
  (str "You are rewriting a recursive Clojure agent session's provider-visible "
       "history into one continuation frame.\n"
       "Return only the replacement user message. Do not include code fences, "
       "do not continue the task, and do not call functions.\n"
       "Capture semantic state, not raw transcript bulk: what each prior step "
       "accomplished, which vars/state now matter, important observations, child "
       "or leaf results, final values, unresolved work, and caller constraints.\n"
       "Do not copy old Clojure blocks. Describe their effects and outputs. "
       "The runtime vars are preserved separately; this output is the compact "
       "provider-facing frame for the next turn."))

(defn- one-line [value max-chars]
  (let [s (str/replace (pr-str value) #"\s+" " ")]
    (subs s 0 (min (long max-chars) (count s)))))

(defn- format-message [message]
  (let [content (str (:message/content message))
        header (str "Message " (:message/id message)
                    " role=" (name (:message/role message))
                    (when-let [turn-id (:message/turn-id message)]
                      (str " turn=" turn-id)))]
    (if (= :system (:message/role message))
      (str header
           "\n<System prompt omitted from compaction transcript; it is unchanged "
           "and will be sent separately. sha256="
           (cache/sha256-string content)
           " chars=" (count content) ">")
      (str header "\n" content))))

(defn- turn-fact [turn]
  (select-keys turn [:turn/id
                     :turn/status
                     :turn/head-before
                     :turn/head-after
                     :turn/user-message-id
                     :turn/assistant-message-ids
                     :turn/observation-message-ids
                     :turn/eval-ids
                     :turn/call-ids
                     :turn/final-preview
                     :turn/error]))

(defn- eval-fact [row]
  (select-keys row [:eval/id
                    :eval/turn-id
                    :eval/status
                    :eval/block-index
                    :eval/code
                    :eval/final-ref
                    :eval/result-ref
                    :eval/error]))

(defn- call-fact [row]
  (select-keys row [:call/id
                    :call/type
                    :call/purpose
                    :call/status
                    :call/turn-id
                    :call/provider
                    :call/model
                    :child/session-id
                    :child/head-before
                    :child/head-after
                    :call/error]))

(defn- latest-snapshot-fact [snapshots]
  (when-let [snapshot (last snapshots)]
    (-> (select-keys snapshot [:snapshot/id
                               :snapshot/kind
                               :snapshot/turn-id
                               :snapshot/message-through-id
                               :snapshot/summary])
        (assoc :snapshot/vars
               (mapv #(select-keys % [:var/name
                                       :var/status
                                       :var/class
                                       :var/reason
                                       :var/summary])
                     (:snapshot/vars snapshot))))))

(defn compaction-request-messages
  "Render the model-mediated compaction request from the current completed head
  view. The response to this request becomes the compact synthetic user message."
  [cfg {:keys [session-id head-id messages turns evals calls snapshots latest-snapshot]}]
  (let [policy (normalize-policy (:context cfg))
        target (:compact-frame-target-tokens policy)
        facts-max (:state-facts-max-chars policy)
        transcript (str/join "\n\n---\n\n" (map format-message messages))
        facts {:session/id session-id
               :head/id head-id
               :message/count (count messages)
               :turns (mapv turn-fact turns)
               :evals (mapv eval-fact evals)
               :calls (mapv call-fact calls)
               :latest-snapshot (or (latest-snapshot-fact [latest-snapshot])
                                    (latest-snapshot-fact snapshots))}
        prompt (str "Create a compact continuation user message for this same session.\n"
                    "Target length: under " target " tokens unless essential facts require more.\n"
                    "The next provider request will contain the engine system prompt, then your output as a user message.\n"
                    "Preserve the semantic consequences of prior steps. Do not quote or reproduce Clojure blocks; state what they changed or proved.\n\n"
                    "Structured current-head facts:\n"
                    (one-line facts facts-max)
                    "\n\nProvider-visible transcript through current head:\n"
                    transcript)]
    [{:message/role :system
      :message/content compaction-system-prompt}
     {:message/role :user
      :message/content prompt}]))
