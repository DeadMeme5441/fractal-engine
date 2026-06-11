(ns fractal.engine.session-loop
  "L3 · the STEP loop (07 §3). `run-loop!` iterates `run-step!` until
   final/error/timeout/budget/stop. The loop owns the turn/step/message/
   observation appends + the deadline; the kernel owns the per-block :eval/added.
   `commit-turn!`/`finalize-turn!` live HERE (not in session) so the loop never
   depends on session — that breaks the session↔session-loop cycle (GD4)."
  (:require [fractal.engine.adapter :as adapter]
            [fractal.engine.adapter.request :as request]
            [fractal.engine.compaction :as compaction]
            [fractal.engine.concurrent :as concurrent]
            [fractal.engine.kernel :as kernel]
            [fractal.engine.observe :as observe]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.store :as store]
            [fractal.engine.time :as time]))

;; ---------------------------------------------------------------------------
;; Honest :unknown-aware roll-ups (08)
;; ---------------------------------------------------------------------------

(defn- sum-field
  "Sum a seq of token counts; any non-number (`:unknown`/absent) ⇒ :unknown
   (never a fabricated total)."
  [vals]
  (if (every? number? vals) (reduce + 0 vals) :unknown))

(defn- roll-usage [usages]
  (if (empty? usages)
    adapter/unknown-usage
    {:usage/status (if (every? #(= :known (:usage/status %)) usages) :known :unknown)
     :usage/input-tokens        (sum-field (map :usage/input-tokens usages))
     :usage/output-tokens       (sum-field (map :usage/output-tokens usages))
     :usage/cached-input-tokens (sum-field (map :usage/cached-input-tokens usages))
     :usage/cache-write-tokens  (sum-field (map :usage/cache-write-tokens usages))}))

(defn- roll-cost [costs]
  (if (empty? costs)
    adapter/unknown-cost
    (let [usds (map :cost/usd costs)]
      (if (every? number? usds)
        {:cost/status :known :cost/usd (reduce + 0 usds)}
        {:cost/status :unknown :cost/usd :unknown}))))

(defn- roll-cache [caches]
  (if (empty? caches)
    adapter/unknown-cache
    (let [statuses (map :cache/status caches)]
      {:cache/status (cond (some #(= :unknown %) statuses) :unknown
                           (some #(= :hit %) statuses)     :hit
                           :else                           :miss)
       :cache/cached-tokens      (sum-field (map :cache/cached-tokens caches))
       :cache/cache-write-tokens (sum-field (map :cache/cache-write-tokens caches))})))

(defn- roll-up
  "Self-only turn totals — where 'self' includes the turn's OWN leaf calls
   (their spend is this session's spend; only CHILD subtrees ride envelopes).
   Cache rollup stays root-steps-only: leaf prompts sit below provider cache
   minimums and would only blur the hit/miss signal."
  [steps leaf-calls]
  (let [responses      (keep :step/response steps)
        leaf-responses (keep :leaf/response leaf-calls)]
    {:usage (roll-usage (map :usage (concat responses leaf-responses)))
     :cost  (roll-cost  (map :cost  (concat responses leaf-responses)))
     :cache (roll-cache (map :cache responses))}))

;; ---------------------------------------------------------------------------
;; TurnResult projection (06 §5)
;; ---------------------------------------------------------------------------

(defn- step-count [view turn-id]
  (count (filter #(= turn-id (:step/turn-id %)) (:steps view))))

(defn- turn-result [handle turn-id status turn final-value]
  (let [view (store/current-view (:store handle) (:session-id handle))]
    (cond-> {:status     status
             :session/id (:session-id handle)
             :turn/id    turn-id
             :turn/usage (:turn/usage turn)
             :turn/cost  (:turn/cost turn)
             :turn/cache (:turn/cache turn)
             :step-count (step-count view turn-id)
             :error      (:turn/error turn)}
      (= :final status) (assoc :turn/final-value final-value))))

(defn- turn-by-id [view turn-id]
  (first (filter #(= turn-id (:turn/id %)) (:turns view))))

(defn- this-turn-steps [view turn-id]
  (filter #(= turn-id (:step/turn-id %)) (:steps view)))

(defn- this-turn-leaf-calls [view turn-id]
  (filter #(= turn-id (:leaf/turn-id %)) (:leaf-calls view)))

;; ---------------------------------------------------------------------------
;; commit-turn! / finalize-turn! (07 §3)
;; ---------------------------------------------------------------------------

(defn commit-turn!
  "The :final terminal path (GD4): intern the FINAL value + preview, snapshot
   vars (a DISTINCT :session/vars-snapshotted just before the final :turn/put),
   roll up usage/cost/cache :unknown-aware, append :turn/put, hydrate + return."
  [handle turn-id final _eval-records]
  (let [store (:store handle) sid (:session-id handle)
        final-value (:value final)
        final-ref (payload-io/maybe-intern store final-value {:payload/kind :final})
        snap (kernel/snapshot-vars @(:sci-ctx handle) sid)
        vars-ref (payload-io/maybe-intern store snap {:payload/kind :vars})]
    (store/append-event! store sid {:event/type :session/vars-snapshotted :vars-ref vars-ref})
    (let [view (store/current-view store sid)
          rolled (roll-up (this-turn-steps view turn-id) (this-turn-leaf-calls view turn-id))
          updated (assoc (turn-by-id view turn-id)
                         :turn/status :final
                         :turn/ended-at (time/now-str)
                         :turn/final-ref final-ref
                         :turn/final-preview (observe/value-display final-value observe/final-fit)
                         :turn/usage (:usage rolled)
                         :turn/cost  (:cost rolled)
                         :turn/cache (:cache rolled))
          turn-ev (store/append-event! store sid {:event/type :turn/put :turn updated})]
      (store/publish-head! store sid
                           {:head/kind :turn-final
                            :head/to-event-id (:event/id turn-ev)
                            :head/turn-id turn-id
                            :head/vars-ref vars-ref
                            :head/final-ref final-ref
                            ;; yjy · the world this head was produced under —
                            ;; attach/resume verify against it via the session
                            ;; row's full bundle; the head carries the hash.
                            :head/bundle-hash (get-in handle [:bundle :bundle/hash])})
      (turn-result handle turn-id :final updated (payload-io/read-payload store final-ref)))))

(defn finalize-turn!
  "Every NON-:final terminal outcome (timeout/budget-exceeded/error). Appends a
   finalizing :turn/put (status + the same :unknown-aware rollups, no final-ref /
   no vars snapshot) BEFORE building the TurnResult — so the live view never
   shows a terminated turn still :running."
  [handle turn-id status error]
  (let [store (:store handle) sid (:session-id handle)
        view (store/current-view store sid)
        rolled (roll-up (this-turn-steps view turn-id) (this-turn-leaf-calls view turn-id))
        updated (assoc (turn-by-id view turn-id)
                       :turn/status status
                       :turn/ended-at (time/now-str)
                       :turn/error error
                       :turn/usage (:usage rolled)
                       :turn/cost  (:cost rolled)
                       :turn/cache (:cache rolled))]
    (store/append-event! store sid {:event/type :turn/put :turn updated})
    (turn-result handle turn-id status updated nil)))

;; ---------------------------------------------------------------------------
;; Step internals
;; ---------------------------------------------------------------------------

(defn- finalize-step!
  "Append the :assistant message (content interned if large) then :step/put — the
   call record sans :text (the bare-:cache key, 02 §1 / 08)."
  [handle turn-id step-id rec]
  (let [store (:store handle) sid (:session-id handle)
        amsg (store/append-event! store sid
               {:event/type :message/appended
                :message {:message/role :assistant
                          :message/turn-id turn-id
                          :message/step-id step-id
                          :message/content-or-ref (payload-io/maybe-intern store (:text rec) {:payload/kind :message})}})
        amsg-id (get-in amsg [:message :message/id])
        step (first (filter #(= step-id (:step/id %)) (:steps (store/current-view store sid))))]
    (store/append-event! store sid
      {:event/type :step/put
       :step (assoc step
                    :step/status :done
                    :step/ended-at (time/now-str)
                    :step/assistant-message-id amsg-id
                    :step/response (dissoc rec :text))})))

(defn- append-observation! [handle turn-id step-id text]
  (let [store (:store handle) sid (:session-id handle)]
    (store/append-event! store sid
      {:event/type :message/appended
       :message {:message/role :observation
                 :message/turn-id turn-id
                 :message/step-id step-id
                 :message/content-or-ref (payload-io/maybe-intern store text {:payload/kind :message})}})))

(defn- adapter-call
  "Wrap the single -complete in with-deadline :call-timeout-ms (covers fake +
   sdk). Returns {:ok rec} | {:timeout e} | {:error e}."
  [handle step-id req]
  (let [cfg (:cfg handle) store (:store handle) sid (:session-id handle)
        on-delta (fn [frag] (store/notify-transient store sid
                              {:event/type :delta/token :text frag :step/id step-id}))]
    (try
      {:ok (concurrent/with-deadline (:call-timeout-ms cfg)
             (adapter/-complete (:adapter handle) req
                                {:retry    (when-not (:stream? cfg) (:retry cfg))
                                 :stream?  (:stream? cfg)
                                 :on-delta on-delta}))}
      (catch clojure.lang.ExceptionInfo e
        (if (= :fractal/deadline (:error/type (ex-data e))) {:timeout e} {:error e}))
      (catch Throwable e {:error e}))))

(defn run-step!
  "One iteration of the spine (01 §'One step'). Returns a discriminated outcome:
   {:control :continue} | {:control :final :final … :eval-records …}
   | {:control :terminal :result <TurnResult>}."
  [handle turn-id]
  (let [store (:store handle) sid (:session-id handle) cfg (:cfg handle)]
    (if (= :stop-requested (get-in (store/current-view store sid) [:session :session/status]))
      ;; before-step stop: append :session/stopped, end the turn as :error (GD27c)
      (do (store/append-event! store sid {:event/type :session/stopped})
          {:control :terminal
           :result (finalize-turn! handle turn-id :error
                                   {:error/type :fractal/session-stopped
                                    :error/message "session stopped"})})
      (let [step-ev (store/append-event! store sid
                      {:event/type :step/started
                       :step {:step/status :running
                              :step/turn-id turn-id
                              :step/started-at (time/now-str)}})
            step-id (get-in step-ev [:step :step/id])]
        (binding [kernel/*current-step-id* step-id]
          (let [req (request/build-request store (store/current-view store sid) cfg (:capability handle) handle)]
            (if (:hard? (compaction/assess req cfg))
              {:control :terminal
               :result (finalize-turn! handle turn-id :budget-exceeded
                                       {:error/type :fractal/context-window
                                        :error/message "context window hard limit exceeded"})}
              (let [call (adapter-call handle step-id req)]
                (cond
                  (:timeout call)
                  {:control :terminal
                   :result (finalize-turn! handle turn-id :timeout
                                           {:error/type :fractal/deadline
                                            :error/message "adapter call timed out"})}
                  (:error call)
                  {:control :terminal
                   :result (finalize-turn! handle turn-id :error
                                           (assoc (kernel/err->map (:error call))
                                                  :error/type :provider/failed))}
                  :else
                  (let [rec (:ok call)]
                    (finalize-step! handle turn-id step-id rec)
                    (let [blocks (kernel/extract-blocks (:text rec))]
                      (if (empty? blocks)
                        (do (append-observation! handle turn-id step-id observe/no-fence-nudge)
                            {:control :continue})
                        (let [batch (kernel/eval-batch handle turn-id blocks)
                              final? (= :final (:status batch))]
                          (append-observation! handle turn-id step-id
                            (observe/render-observation (:eval-records batch)
                                                        (assoc (:observe cfg) :final? final?)))
                          (if final?
                            {:control :final :final (:final batch) :eval-records (:eval-records batch)}
                            {:control :continue}))))))))))))))

(defn run-loop!
  "Iterate run-step! until a terminal outcome; enforce :max-steps (07 §3)."
  [handle turn-id]
  (let [max-steps (:max-steps (:cfg handle))]
    (loop [step-n 1]
      (let [outcome (run-step! handle turn-id)]
        (case (:control outcome)
          :final    (commit-turn! handle turn-id (:final outcome) (:eval-records outcome))
          :terminal (:result outcome)
          :continue (if (and max-steps (>= step-n max-steps))
                      (finalize-turn! handle turn-id :budget-exceeded
                                      {:error/type :fractal/max-steps
                                       :error/message (str "max steps (" max-steps ") reached")})
                      (recur (inc step-n))))))))
