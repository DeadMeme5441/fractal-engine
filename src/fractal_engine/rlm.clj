(ns fractal-engine.rlm
  "RLM invocation values, handles, envelopes, and attach target resolution.

  A session is durable identity; a head is immutable state; invocation facts are
  edges between session heads. This namespace owns those values, not the provider
  loop that executes work."
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.session-model :as session-model]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.store.value :as store-value]
            [fractal-engine.time :as time]))

(defn child-final-value
  "Validate a sub-session result and return its FINAL value, or throw a typed error.
  `errs` supplies the error types for the spawning kind: {:failed .. :no-final ..}
  (child vs attach). A child's returned value is a claim until it carries a FINAL."
  [result cid errs]
  (cond
    (= :error (:status result))
    (throw (ex-info "Child process failed"
                    {:error/type (:failed errs)
                     :error/message "Child process returned error"
                     :error/data (:error result)
                     :child/session-id cid
                     :error/retryable? false}))
    (not (contains? result :final-value))
    (throw (ex-info "Child process did not return FINAL"
                    {:error/type (:no-final errs)
                     :error/message "Child process returned without a FINAL value"
                     :child/session-id cid
                     :error/retryable? false}))
    :else (:final-value result)))

(defn assemble-batch-results
  "Fold per-item fan-out results into one vector aligned to input order. A success
  contributes its value; a failure contributes a `:fractal/failed` sentinel in its
  slot. Partial failure never throws -- the successes return for sure, and the
  failures stay legible so the model can record missingness instead of silently
  undercounting. `:fractal/` namespaces the sentinel so it cannot collide with a
  leaf's or child's own domain value."
  [results]
  (mapv (fn [{:keys [ok index value error]}]
          (if ok
            value
            {:fractal/failed true :index index :error error}))
        (sort-by :index results)))

(defn invocation-payload-ref! [state part value]
  (artifacts/value-ref! (:locator @state)
                        value
                        {:payload/kind (keyword "invocation" (name part))}))

(defn current-turn-head-before [state]
  (or (some->> runtime/*current-turn-id*
               (artifacts/current-turn state)
               :turn/head-before)
      (artifacts/current-head-id state)))

(defn read-session-row [locator]
  (or (session-db/read-session (artifacts/store-root-for-locator locator)
                               (artifacts/session-id-for-locator locator))
      {}))

(defn logical-session-id-for-locator [locator fallback]
  (let [session-row (read-session-row locator)]
    (or (:session/logical-id session-row)
        (:session/id session-row)
        fallback)))

(defn cache-id-for-locator [locator fallback]
  (let [session-row (read-session-row locator)]
    (or (:session/cache-id session-row)
        (:session/logical-id session-row)
        (:session/id session-row)
        fallback)))

(defn store-root-for-locator [locator fallback-root]
  (let [session-row (read-session-row locator)]
    (or (get-in session-row [:session/storage :storage/root])
        fallback-root
        (str (artifacts/store-root-for-locator locator)))))

(defn current-head-id-for-locator [locator]
  (:ref/current-head (session-db/read-ref (artifacts/store-root-for-locator locator)
                                          (artifacts/session-id-for-locator locator))))

(defn invocation-handle-for [session-id head-id cache-id store-root]
  (session-model/invocation-handle session-id head-id cache-id store-root))

(defn invocation-session-handle [inv]
  (session-model/session-handle (:callee/session inv)
                                (:callee/cache-id inv)
                                (get-in inv [:invocation/handle :store/root])))

(defn invocation-head-handle [inv]
  (or (:invocation/handle inv)
      (when (and (:callee/session inv) (:callee/head-after inv))
        (invocation-handle-for (:callee/session inv)
                               (:callee/head-after inv)
                               (:callee/cache-id inv)
                               nil))))

(defn compact-preview [value n]
  (let [s (-> (pr-str value)
              (str/replace #"\s+" " ")
              str/trim)]
    (if (> (count s) n)
      (str (subs s 0 (max 0 (dec n))) "…")
      s)))

(defn task-label [input]
  (let [candidate (cond
                    (string? input) input
                    (map? input) (some input [:label :name :id :lane :handle :task/id :subsystem])
                    (keyword? input) input
                    (symbol? input) input
                    :else nil)]
    (when candidate
      (compact-preview candidate 80))))

(defn invocation-kind [inv]
  (case (:invocation/type inv)
    :rlm :child
    :map-rlm :child-batch-item
    :attach (case (:edge/type inv)
              :continued :continued
              :attached :attached-child
              :attached-child)
    (:invocation/type inv)))

(defn rlm-meta [value inv input]
  (cond-> {:kind (invocation-kind inv)
           :invocation/type (:invocation/type inv)
           :label (or (:invocation/label inv)
                      (task-label input)
                      (some-> (:invocation/type inv) name))
           :task/hash (cache/sha256-string (pr-str input))
           :task/preview (compact-preview input 240)
           :value/kind (store-value/value-kind value)
           :value/preview (compact-preview value 240)}
    (:edge/type inv) (assoc :edge/type (:edge/type inv))
    (:batch/id inv) (assoc :batch/id (:batch/id inv))
    (:batch/index inv) (assoc :batch/index (:batch/index inv))
    (map? value) (assoc :value/keys (vec (take 12 (keys value))))))

(defn envelope [value inv input]
  (session-model/rlm-result
   {:status (:invocation/status inv :final)
    :value value
    :session (invocation-session-handle inv)
    :head (invocation-head-handle inv)
    :invocation (select-keys inv [:invocation/id :invocation/type])
    :meta (rlm-meta value inv input)}))

(defn record-invocation!
  [state call-id invocation-type input result status error extra]
  (let [callee-locator (:locator result)
        callee-session (or (:logical-session-id result)
                           (some-> callee-locator (logical-session-id-for-locator (:session-id result)))
                           (:session-id result))
        callee-cache-id (or (:cache-id result)
                            (some-> callee-locator (cache-id-for-locator callee-session))
                            callee-session)
        callee-head-before (:head-before result)
        callee-head-after (or (:head-id result)
                              (some-> callee-locator current-head-id-for-locator))
        callee-store-root (or (:store-root result)
                              (some-> callee-locator (store-root-for-locator nil))
                              (some-> (get-in @state [:session :session/storage :storage/root]) str))
        handle (when (and callee-session callee-head-after)
                 (invocation-handle-for callee-session
                                        callee-head-after
                                        callee-cache-id
                                        callee-store-root))
        final-value (:final-value result)
        input-ref (invocation-payload-ref! state :input input)
        final-ref (when (contains? result :final-value)
                    (invocation-payload-ref! state :final final-value))
        edge-type (or (:edge/type extra)
                      (if (= invocation-type :attach) :attached :spawned))
        label (or (:invocation/label extra)
                  (:label extra)
                  (:batch/label extra)
                  (task-label input)
                  (some-> invocation-type name))
        started (artifacts/start-invocation!
                 state
                 (merge {:invocation/type invocation-type
                         :invocation/label label
                         :invocation/call-id call-id
                         :invocation/input-ref input-ref
                         :edge/type edge-type
                         :caller/session (artifacts/logical-session-id state)
                         :caller/storage-session (get-in @state [:session :session/id])
                         :caller/head-before (current-turn-head-before state)
                         :caller/turn-id runtime/*current-turn-id*
                         :caller/eval-id runtime/*current-eval-id*
                         :callee/session callee-session
                         :callee/cache-id callee-cache-id
                         :callee/storage-session (:session-id result)
                         :callee/head-before callee-head-before}
                        extra))
        completion {:callee/head-after callee-head-after
                    :invocation/handle handle
                    :invocation/ended-at (time/now-str)}
        completed (if (= :error status)
                    (artifacts/fail-invocation! state (:invocation/id started)
                                                (assoc completion :invocation/error error))
                    (artifacts/complete-invocation! state (:invocation/id started)
                                                    (cond-> completion
                                                      final-ref (assoc :invocation/final-ref final-ref))))]
    (merge started completed {:invocation/status status})))

(defn handle-head-id [handle opts]
  (or (:head opts)
      (when (map? handle)
        (or (:head/id handle)
            (:session/head handle)
            (:ref/current-head handle)))
      (when (string? handle)
        (second (str/split (str/replace-first handle #"^session:" "") #"#")))))

(defn explicit-head-handle? [handle opts]
  (boolean (handle-head-id handle opts)))

(defn state-store-root [state]
  (store-io/path (or (get-in @state [:session :session/storage :storage/root])
                     (:store-root @state)
                     ".")))

(defn handle-store-root [state handle]
  (or (when (map? handle) (:store/root handle))
      (str (state-store-root state))))

(defn stable-handle-target [state handle opts]
  (when-let [root (handle-store-root state handle)]
    (session-db/resolve-handle root handle opts)))

(defn resolve-attach-target [state handle opts]
  (let [target (or (stable-handle-target state handle opts)
                   (throw (ex-info "Attach handle does not name a resolvable session"
                                   {:error/type :attach/invalid-handle
                                    :handle handle})))]
    (assoc target
           :snapshot-opts (cond-> opts (:head-id target) (assoc :head (:head-id target))))))
