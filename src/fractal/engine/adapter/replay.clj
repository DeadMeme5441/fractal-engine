(ns fractal.engine.adapter.replay
  "L3 · the RECORDED-replay responder (jz3a). Serves provider calls from a
   prior run's DURABLE log instead of a live provider — the deterministic
   re-execution seam both the 2026-06-03 storage A/B post-mortem and the
   artifact-optimization design independently demanded. Same family as the
   fake adapter (it IS a respond-fn for the fake adapter) and
   restore-from-snapshot: policy-free substrate.

   STEP routing: a replay run's sessions get FRESH ids, so recorded cache
   scope-ids can never match — instead each recorded session is keyed by the
   content of its FIRST :user message (the turn message for a root; the
   child-invocation-frame for a child). A deterministic replay re-issues the
   same first user message per session, so root and children route themselves.
   Same-key recorded sessions are served whole-session-in-recording-order: the
   per-key records are ONE flat vector (each session's steps concatenated) with
   ONE cursor — interchangeable-by-construction children replay cleanly, and
   there is no rotation state to get wrong.

   LEAF routing: leaf calls are durable :leaf/called events — a request whose
   cache :purpose is :leaf routes by the content hash of its user message to
   the recorded leaf responses, FIFO among identical requests. A leaf call
   recorded as :error (the provider call itself failed) re-throws the recorded
   error.

   Limits (documented, typed):
   - an unknown step-routing key throws :fractal/replay-unknown-session; an
     exhausted key throws :fractal/replay-exhausted;
   - a leaf request with no recorded leaf call throws
     :fractal/replay-leaf-unsupported (recording predates leaf events, or the
     fan-out diverged)."
  (:require [fractal.engine.adapter :as adapter]
            [fractal.engine.payload :as payload]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.store :as store]))

;; ---------------------------------------------------------------------------
;; Record extraction (per recorded session, in step order)
;; ---------------------------------------------------------------------------

(defn- step-records
  "The recorded call records of one session, in step order: each :step/put's
   :step/response (text stripped at persist time) re-joined with its assistant
   message's hydrated text."
  [st view]
  (vec
    (for [step (sort-by :step/id (:steps view))
          :let [resp (:step/response step)
                amsg (store/message-by-id view (:step/assistant-message-id step))]
          :when (and resp amsg)]
      (assoc resp :text (:message/content (payload-io/hydrate-message st amsg))))))

(defn- first-user-key
  "The step-routing key of a recorded session: the content hash of its first
   :user message."
  [st view]
  (some->> (:messages view)
           (filter #(= :user (:message/role %)))
           first
           (payload-io/hydrate-message st)
           :message/content
           payload/content-id))

;; ---------------------------------------------------------------------------
;; The take-next cursor (ONE idiom, retry-safe by construction)
;; ---------------------------------------------------------------------------

(defn- cursors-for
  "{key (atom {:items [...] :at 0})} from a {key [items...]} map."
  [m]
  (update-vals m (fn [items] (atom {:items (vec items) :at 0}))))

(defn- take-next!
  "Atomically take the next item from a cursor atom, or nil when exhausted.
   swap-vals! + derive-from-old: the swap fn is pure, so retries are safe."
  [cursor]
  (let [[{:keys [items at]} _] (swap-vals! cursor
                                           (fn [{:keys [items at] :as s}]
                                             (if (< at (count items)) (assoc s :at (inc at)) s)))]
    (when (< at (count items)) (nth items at))))

;; ---------------------------------------------------------------------------
;; The responder
;; ---------------------------------------------------------------------------

(defn- leaf-request? [request]
  (= :leaf (get-in request [:cache :purpose])))

(defn- leaf-record
  "A recorded :leaf/called entry → the call record to serve (text re-joined),
   or a typed throw for a recorded call-failure."
  [leaf]
  (if (= :error (:leaf/status leaf))
    (throw (ex-info "replay: the recorded leaf call itself failed"
                    (assoc (or (:leaf/error leaf) {})
                           :error/type (or (:error/type (:leaf/error leaf)) :fractal/replay-recorded-error)
                           :leaf/replayed? true)))
    (assoc (:leaf/response leaf) :text (:leaf/text leaf))))

(defn- leaf-items
  "{user-content-hash [recorded-leaf …]} across all source sessions, hydrated,
   in recording order."
  [st views]
  (reduce
    (fn [m leaf]
      (let [content (payload-io/read-payload st (:leaf/user-content-or-ref leaf))]
        (update m (payload/content-id content) (fnil conj [])
                (assoc leaf :leaf/text
                       (payload-io/read-payload st (:leaf/text-or-ref leaf))))))
    {}
    (mapcat :leaf-calls views)))

(defn- step-items
  "{first-user-hash [record …]} — same-key sessions CONCATENATED in
   registration order (whole-session-then-next; FIFO among equals)."
  [st views]
  (reduce
    (fn [m view]
      (update m (first-user-key st view) (fnil into []) (step-records st view)))
    {}
    views))

(defn replay-responder
  "Build a respond-fn (for :adapter :fake / :fake/respond) that serves the
   recorded step responses AND recorded leaf calls of `sids` from `st`, routed
   by message content. Reads through the port (read-state), so any
   SessionStore — incl. a store opened on another run's dir — works as the
   source."
  [st sids]
  (let [views (mapv (fn [sid]
                      (let [view (store/read-state st sid)]
                        (when-not (:session view)
                          (throw (ex-info (str "replay: unknown recorded session: " sid)
                                          {:error/type :fractal/replay-unknown-session
                                           :session/id sid})))
                        view))
                    sids)
        leafq (cursors-for (leaf-items st views))
        stepq (cursors-for (step-items st views))]
    (fn [request]
      (let [k (payload/content-id (adapter/first-user-content request))]
        (if (leaf-request? request)
          (if-let [leaf (some-> (get leafq k) take-next!)]
            (leaf-record leaf)
            (throw (ex-info "replay: no recorded leaf call for this request (recording predates leaf events, or the leaf fan-out diverged)"
                            {:error/type :fractal/replay-leaf-unsupported})))
          (if-let [rec (some-> (get stepq k) take-next!)]
            rec
            (throw (ex-info (str "replay: no recorded response available for this request "
                                 "(unknown session key or recording exhausted)")
                            {:error/type (if (contains? stepq k)
                                           :fractal/replay-exhausted
                                           :fractal/replay-unknown-session)
                             :request/first-user (let [s (str (adapter/first-user-content request))]
                                                   (subs s 0 (min 160 (count s))))}))))))))
