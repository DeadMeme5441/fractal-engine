(ns fractal-engine.session-model
  "Pure value helpers for the canonical RLM session model.

  This namespace does not call providers, evaluate model code, or perform IO. It
  only names the durable values the runtime records elsewhere: session heads,
  derivation edges, invocation records, and handles that resolve through the
  canonical fact store."
  (:require [fractal-engine.cache :as cache])
  (:import [java.util UUID]))

(def head-version 1)
(def head-state-version 2)
(def derivation-version 1)
(def invocation-version 1)

(defn head-id []
  (str "head-" (UUID/randomUUID)))

(defn invocation-id []
  (str "inv-" (UUID/randomUUID)))

(defn logical-session-id [session]
  (or (:session/logical-id session)
      (:session/id session)))

(def canonical-head-keys
  [:head/id
   :head/version
   :head/session
   :head/basis
   :head/kind
   :head/cache-id
   :head/cache
   :head/turn-id
   :head/message-through-id
   :head/state-version
   :head/state-ref
   :head/snapshot-id
   :head/snapshot-ref
   :head/final-ref
   :head/event-range
   :head/invocations
   :head/final-preview])

(def active-state-keys
  [:session
   :messages
   :turns
   :evals
   :calls
   :snapshots
   :heads
   :invocations
   :refs
   :final-ref
   :error
   :counters])

(def state-collection-id-keys
  {:messages :message/id
   :turns :turn/id
   :evals :eval/id
   :calls :call/id
   :snapshots :snapshot/id
   :heads :head/id
   :invocations :invocation/id})

(def state-collection-keys
  (vec (keys state-collection-id-keys)))

(defn head-fingerprint [head]
  (cache/sha256-string
   (pr-str (select-keys head canonical-head-keys))))

(defn head-state->view [head-state]
  (select-keys head-state active-state-keys))

(defn invocation-handle
  "Stable handle for invoking a session head later. Identity is the session id
  plus, when present, the immutable head id."
  ([session-id head-id]
   (invocation-handle session-id head-id nil))
  ([session-id head-id cache-id]
   (invocation-handle session-id head-id cache-id nil))
  ([session-id head-id cache-id store-root]
   (cond-> {:session/id session-id
            :head/id head-id}
     cache-id (assoc :session/cache-id cache-id)
     store-root (assoc :store/root (str store-root)))))

(defn session-handle
  "Stable handle for continuing a session's current ref. Unlike
  `invocation-handle`, this deliberately omits :head/id."
  ([session-id] (session-handle session-id nil nil))
  ([session-id cache-id] (session-handle session-id cache-id nil))
  ([session-id cache-id store-root]
   (cond-> {:session/id session-id}
     cache-id (assoc :session/cache-id cache-id)
     store-root (assoc :store/root (str store-root)))))

(defn rlm-result
  "Model-visible result of a recursive session invocation. The value is compact,
  but it carries both a session ref for continuation and an immutable head ref for
  branching/provenance."
  [{:keys [status value session head invocation meta]}]
  (cond-> {:rlm/result true
           :rlm/status (or status :final)
           :rlm/value value
           :rlm/session session
           :rlm/head head}
    invocation (assoc :rlm/invocation invocation)
    meta (assoc :rlm/meta meta)))

(defn head-ref [head]
  (select-keys head [:head/id :head/session]))
