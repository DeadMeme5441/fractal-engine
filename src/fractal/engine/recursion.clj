(ns fractal.engine.recursion
  "L4 · the RLM recursion layer (Phase 3). The four model-calling host fns
   injected into a session's SCI ctx when the harness is :rlm:

     (lm input query [mode])      LEAF  — ONE bounded provider call (no session,
     (map-lm inputs query [mode]) LEAF    no loop, no lineage); the leaf prompt;
                                          mode :string/:edn. map-lm = the leaf
                                          fanned out over ≤50 inputs, order-kept.
     (rlm task)                   CHILD — a FRESH child session (its own SCI ctx
     (map-rlm tasks [shared])     CHILD   + SessionStore session, inherit-and-
                                          clamped capability) running the WHOLE
                                          loop to FINAL; returns an ENVELOPE.
     (attach-rlm handle task [opts])
                                  REUSE — a FRESH derived child restored from a
                                          selected immutable source head.

   Recursion happens BETWEEN interpreters, in the HOST (03 recursion note): a
   child is a normal session in the SAME store. This ns NEVER requires `session`
   (that would cycle: session→recursion); instead session injects an `env` of
   spawn/run/stop closures (dependency inversion, mirroring the v1 reference).

   Leaf ≠ child (architecture invariant): a leaf is one adapter call; a child is
   a full session. Partial fan-out NEVER throws — failed slots become
   {:fractal/failed true …} sentinels in the :fractal/ ns. Accounting stays
   honest: a child's usage/cost/cache rides its envelope's :rlm/meta; the root
   turn's :turn/usage/:turn/cost remain SELF-ONLY (06 §6).

   Phase 4 adds the durable recursion data model here: invocation/derivation
   edges, cross-session lineage, immutable heads, and attach-rlm. The envelope's
   :rlm/head is the immutable current head plus legacy :vars-ref/session aliases."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fractal.engine.adapter :as adapter]
            [fractal.engine.cache :as cache]
            [fractal.engine.concurrent :as concurrent]
            [fractal.engine.kernel :as kernel]
            [fractal.engine.payload :as payload]
            [fractal.engine.prompt :as prompt]
            [fractal.engine.store :as store]
            [fractal.engine.surface :as surface]))

;; ---------------------------------------------------------------------------
;; The GLOBAL leaf semaphore (a process-wide rate-limit guard). Sized once from
;; the first session's :leaf-concurrency (compare-and-set; never reset, so an
;; in-flight permit is never invalidated). Distinct from the per-fan-out pool
;; bound (which only caps ONE map-lm/map-rlm) — this caps TOTAL concurrent leaf
;; provider calls across every fan-out and nested call, so a deep tree cannot
;; storm the provider.
;; ---------------------------------------------------------------------------

(defonce ^:private leaf-sem* (atom nil))

(defn- leaf-sem [n]
  (or @leaf-sem*
      (do (compare-and-set! leaf-sem* nil (concurrent/semaphore (or n 8)))
          @leaf-sem*)))

;; ---------------------------------------------------------------------------
;; Small pure helpers (recognition data for envelopes)
;; ---------------------------------------------------------------------------

(defn- compact-preview [value n]
  (let [s (-> (pr-str value) (str/replace #"\s+" " ") str/trim)]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn- value-kind [v]
  (cond (nil? v) :nil (map? v) :map (vector? v) :vector (set? v) :set
        (sequential? v) :seq (string? v) :string (keyword? v) :keyword
        (number? v) :number (boolean? v) :boolean :else (keyword (.getName (class v)))))

(defn- task-label [task]
  (let [candidate (cond (string? task) task
                        (map? task)    (some task [:label :name :id :lane :handle :task/id])
                        (keyword? task) task
                        :else nil)]
    (when candidate (compact-preview candidate 80))))

(defn- task-text [task] (if (string? task) task (pr-str task)))

;; ---------------------------------------------------------------------------
;; Fan-out cap (≤50) + sentinel assembly (partial failure never throws)
;; ---------------------------------------------------------------------------

(defn bounded-fanout-inputs
  "Enforce the ≤:max-fanout cap. Over the cap THROWS a recoverable
   :fractal/fanout-exceeded (the model chunks into 40-50 item batches and calls
   once per chunk — it does not raise the cap)."
  [kind cfg inputs]
  (let [max-fanout (:max-fanout cfg)
        xs (vec (take (inc max-fanout) inputs))]
    (if (> (count xs) max-fanout)
      (throw (ex-info (str "fan-out limit exceeded (max " max-fanout
                           ") — partition into 40-50 item chunks and call once per chunk")
                      {:error/type :fractal/fanout-exceeded
                       :fanout/kind kind :fanout/max max-fanout
                       :fanout/count-at-least (count xs)
                       :error/retryable? true}))
      xs)))

(defn assemble-batch-results
  "Fold per-slot fan-out results (index-aligned, from concurrent/bounded-fanout)
   into one input-ordered vector: a success contributes its value; a failure
   contributes a {:fractal/failed true :index i :error …} sentinel in its slot."
  [results]
  (mapv (fn [{:keys [ok index value error]}]
          (if ok value {:fractal/failed true :index index :error error}))
        results))

;; ---------------------------------------------------------------------------
;; LEAF — one bounded adapter call (lm / map-lm)
;; ---------------------------------------------------------------------------

(defn- strip-edn-fence
  "Drop an enclosing ```edn|clojure|clj fence if the leaf wrapped its EDN despite
   the prompt. Unfenced text is returned untouched (so genuinely malformed output
   still fails to read and surfaces as a slot failure)."
  [text]
  (-> (str text)
      (str/replace #"(?s)\A\s*```(?:edn|clojure|clj)?[ \t]*\r?\n?" "")
      (str/replace #"(?s)\r?\n?```\s*\z" "")
      str/trim))

(defn- parse-leaf [text mode]
  (case mode
    :string text
    :edn     (edn/read-string (strip-edn-fence text))
    text))

(defn- leaf-system-prompt [handle input query mode]
  (let [cfg (:cfg handle)
        profile (:capability handle)]
    (->> [(prompt/leaf-prompt)
          (surface/leaf-prompt-card
            (:surfaces cfg)
            profile
            {:handle handle
             :session/id (:session-id handle)
             :cfg cfg
             :input input
             :query query
             :mode mode})]
         (remove str/blank?)
         (str/join "\n\n"))))

(defn- leaf-request [handle input query mode]
  (let [cfg (:cfg handle)]
    {:model    (:leaf-model handle)
     :messages [{:role :system :content (leaf-system-prompt handle input query mode)}
                {:role :user
                 :content (str "Input EDN:\n" (pr-str input)
                               "\n\nQuery:\n" query
                               (when (= :edn mode)
                                 "\n\nReturn ONLY one schema-shaped EDN value: no prose, no Markdown, no code fence."))}]
     :cache    (cache/build-leaf-cache-opts (:cache-id handle) cfg)}))

(defn- leaf-call
  "ONE bounded provider call with the leaf prompt, under the call deadline and
   the GLOBAL leaf semaphore. Parses per mode; a parse failure throws
   :fractal/leaf-parse-failed (→ a sentinel inside map-lm; the error observation
   for a bare lm)."
  [handle input query mode]
  (let [cfg        (:cfg handle)
        adpt       (:leaf-adapter handle)
        req        (leaf-request handle input query mode)
        rec        (concurrent/with-permit (leaf-sem (:leaf-concurrency cfg))
                     (fn []
                       (concurrent/with-deadline (:call-timeout-ms cfg)
                         (adapter/-complete adpt req
                                            {:retry    (when-not (:stream? cfg) (:retry cfg))
                                             :stream?  false
                                             :on-delta nil}))))
        text       (:text rec)]
    (try
      (parse-leaf text mode)
      (catch Throwable t
        (throw (ex-info "leaf response did not parse into the requested shape"
                        {:error/type :fractal/leaf-parse-failed
                         :leaf/mode mode
                         :leaf/text-preview (compact-preview text 240)}
                        t))))))

(defn- map-leaf [handle inputs query mode]
  (let [cfg     (:cfg handle)
        inputs' (bounded-fanout-inputs :leaf cfg inputs)
        pool    (min (count inputs') (:fanout-pool cfg))
        results (concurrent/bounded-fanout "fractal-map-lm" pool inputs'
                  (fn [_idx input] (leaf-call handle input query mode)))]
    (assemble-batch-results results)))

;; ---------------------------------------------------------------------------
;; CHILD — a fresh child session running the whole loop (rlm / map-rlm)
;; ---------------------------------------------------------------------------

(defn- child-final-value
  "A child's returned value is a CLAIM until it carries a FINAL: validate the
   child TurnResult, else throw a typed error (→ a sentinel in map-rlm; an error
   observation for a bare rlm)."
  [result cid]
  (if (= :final (:status result))
    (:turn/final-value result)
    (throw (ex-info "child session did not return FINAL"
                    {:error/type      :fractal/child-failed
                     :child/session-id cid
                     :child/status    (:status result)
                     :child/error     (:error result)
                     :error/retryable? false}))))

(defn- envelope
  "The model-visible result of a child invocation: a compact map carrying the
   child's settled value plus continuation/branch proxies and recognition data.
   :rlm/head is the child's immutable Merkle current head plus the legacy
   :vars-ref/session aliases for easy model handling."
  [child-handle result final-value task kind]
  (let [cid  (:session-id child-handle)
        view (store/current-view (:store child-handle) cid)
        sess (:session view)
        head (store/current-head view)]
    {:rlm/result  true
     :rlm/status  :final
     :rlm/value   final-value
     :rlm/session {:session/id cid :session/cache-id (:session/cache-id sess)}
     :rlm/head    (cond-> (select-keys head [:head/id :head/session :head/basis
                                             :head/event-range :head/vars-ref
                                             :head/final-ref :head/kind])
                    true (assoc :session/id cid :vars-ref (:vars-ref view)))
     :rlm/meta    (cond-> {:kind          kind
                           :label         (task-label task)
                           :task/hash     (payload/content-id task)
                           :task/preview  (compact-preview task 240)
                           :value/kind    (value-kind final-value)
                           :value/preview (compact-preview final-value 240)
                           :child/session-id cid
                           :step-count    (:step-count result)
                           ;; SELF-ONLY accounting lives here for the subtree
                           ;; (06 §6): the child's own honest usage/cost/cache.
                           :usage         (:turn/usage result)
                           :cost          (:turn/cost result)
                           :cache         (:turn/cache result)}
                    (map? final-value) (assoc :value/keys (vec (take 12 (keys final-value)))))}))

(defn- parent-head-id [handle]
  (:current-head (store/current-view (:store handle) (:session-id handle))))

(defn- invocation-context []
  {:edge/turn-id kernel/*current-turn-id*
   :edge/step-id kernel/*current-step-id*
   :edge/eval-id kernel/*current-eval-id*})

(defn- record-invocation-edge! [parent child env task kind from-head]
  (let [store (:store parent)
        psid  (:session-id parent)
        csid  (:session-id child)
        to-head (get-in env [:rlm/head :head/id])]
    (when to-head
      (store/append-lineage-edge!
        store psid
        (merge {:edge/type :invocation
                :edge/kind kind
                :edge/parent-session psid
                :edge/child-session csid
                :edge/from-session psid
                :edge/to-session csid
                :edge/from-head from-head
                :edge/to-head to-head
                :edge/task-hash (payload/content-id task)
                :edge/task-preview (compact-preview task 240)}
               (invocation-context))))))

(defn- record-derivation-edge! [parent child env task source]
  (let [store (:store parent)
        psid  (:session-id parent)
        target-head (get-in env [:rlm/head :head/id])
        source-head (:head source)]
    (when target-head
      (store/append-lineage-edge!
        store psid
        (merge {:edge/type :derivation
                :edge/kind :attach-rlm
                :edge/caller-session psid
                :edge/source-session (:session/id source)
                :edge/target-session (:session-id child)
                :edge/from-session (:session/id source)
                :edge/to-session (:session-id child)
                :edge/from-head (:head/id source-head)
                :edge/to-head target-head
                :edge/source {:session/id (:session/id source)
                              :head/id (:head/id source-head)}
                :edge/target {:session/id (:session-id child)
                              :head/id target-head}
                :edge/task-hash (payload/content-id task)
                :edge/task-preview (compact-preview task 240)}
               (invocation-context))))))

(defn- child-call
  "Spawn a fresh inherit-and-clamped child session (reusing the parent store),
   run its WHOLE loop to FINAL on this thread, and return the envelope. Always
   stops the child's live dispatch (the child's events stay durable in the
   store). A non-FINAL child throws (→ sentinel/observation per the caller)."
  [handle env kind task]
  (let [spawn! (:spawn-child! env)
        run!   (:run-turn! env)
        stop!  (:stop-child! env)
        from-head (parent-head-id handle)
        child  (spawn! handle {})]
    (try
      (let [result      (run! child (prompt/child-invocation-frame task))
            final-value (child-final-value result (:session-id child))
            envl        (envelope child result final-value task kind)]
        (record-invocation-edge! handle child envl task kind from-head)
        envl)
      (finally (stop! child)))))

(defn- attach-call
  "Restore a selected source head into a fresh derived child, run one task, and
   return the same envelope shape as rlm. The source session is never advanced."
  [handle env source-handle task opts]
  (let [spawn! (:spawn-attached! env)
        run!   (:run-turn! env)
        stop!  (:stop-child! env)
        child  (spawn! handle source-handle (or opts {}))]
    (try
      (let [result      (run! child (prompt/child-invocation-frame task))
            final-value (child-final-value result (:session-id child))
            envl        (envelope child result final-value task :attach-rlm)]
        (record-derivation-edge! handle child envl task (:attach/source child))
        envl)
      (finally (stop! child)))))

(defn- map-child [handle env tasks shared]
  (let [cfg    (:cfg handle)
        tasks' (bounded-fanout-inputs :child cfg tasks)
        pool   (min (count tasks') (:fanout-pool cfg))
        results (concurrent/bounded-fanout "fractal-map-rlm" pool tasks'
                  (fn [_idx task]
                    (let [task' (if shared (str shared "\n\nTask:\n" (task-text task)) task)]
                      (child-call handle env :child-batch-item task'))))]
    (assemble-batch-results results)))

;; ---------------------------------------------------------------------------
;; The host-fn impl map (assembled by session/start-session! in :rlm harness)
;; ---------------------------------------------------------------------------

(defn engine-fns
  "Build the rlm host-fn impls closing over the (parent) session `handle` and the
   spawn/run/stop `env` injected by `session` (dependency inversion — recursion
   never requires session). Profile gating happens later in capability/sci-opts
   (the profile's :engine-fns selects which of these inject; :locked-down drops
   lm/rlm). The fns read cfg/adapter/store off the handle at CALL time."
  [handle env]
   {:lm      (fn ([input query]       (leaf-call handle input query :string))
                ([input query mode]  (leaf-call handle input query mode)))
   :map-lm  (fn ([inputs query]      (map-leaf handle inputs query :string))
                ([inputs query mode] (map-leaf handle inputs query mode)))
   :rlm     (fn [task]               (child-call handle env :child task))
   :map-rlm (fn ([tasks]             (map-child handle env tasks nil))
                ([tasks shared]      (map-child handle env tasks shared)))
   :attach-rlm (fn ([source task]      (attach-call handle env source task {}))
                   ([source task opts] (attach-call handle env source task opts)))})
