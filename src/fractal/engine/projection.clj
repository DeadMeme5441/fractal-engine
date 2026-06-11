(ns fractal.engine.projection
  "L3 · 88j read projections — the queries every consumer hand-rolled by
   scraping lineage edges and mining invocation facts. Pure reads over the
   durable log through the port (read-state, so they are cross-process honest
   and need no live slot): a delegation report per turn, and a claim verifier
   for refs/heads/edges a model (or anything else) asserts exist.

   COST: read-state on sqlite is a full log re-fold; delegation-report does one
   per DISTINCT child session plus one for the parent — O(subtree log). Built
   for one-shot inspection/audit; poll politely, like read-state itself."
  (:require [fractal.engine.payload :as payload]
            [fractal.engine.store :as store]))

(defn- sum-or-unknown [xs]
  (if (and (seq xs) (every? number? xs)) (reduce + 0 xs) :unknown))

(defn- cost-rollup
  "The ONE :unknown-aware cost fold (mirrors session-loop's roll-cost policy:
   any non-number contribution ⇒ :unknown, never a fabricated total)."
  [usds]
  (let [s (sum-or-unknown usds)]
    (if (number? s)
      {:cost/status :known :cost/usd s}
      {:cost/status :unknown :cost/usd :unknown})))

(defn- safe-read-state
  "read-state, with an unknown session yielding nil instead of an impl-specific
   throw (MemoryStore has no slot to deref; SqliteStore folds an empty log) —
   a claim about a session that does not exist is FALSE, not an error."
  [st sid]
  (try
    (let [v (store/read-state st sid)]
      (when (:session v) v))
    (catch Throwable _ nil)))

(defn- child-summary [st csid]
  (let [cv     (or (safe-read-state st csid) {})
        turns  (:turns cv)
        usages (keep :turn/usage turns)]
    {:child/session-id csid
     :child/status     (get-in cv [:session :session/status])
     :child/turns      (count turns)
     :child/usage      {:usage/input-tokens  (sum-or-unknown (map :usage/input-tokens usages))
                        :usage/output-tokens (sum-or-unknown (map :usage/output-tokens usages))}
     :child/cost       (cost-rollup (map (comp :cost/usd :turn/cost) turns))}))

(defn delegation-report
  "What did turn `turn-id` of session `sid` actually delegate, and what did the
   subtree cost? Edges (invocation + derivation) recorded during the turn, one
   summary per child (status, turns, usage/cost rolled :unknown-aware from the
   child's own turns — summarized ONCE per distinct child), plus the turn's
   self-only usage/cost for contrast."
  [st sid turn-id]
  (let [view      (store/read-state st sid)
        turn      (store/turn-by-id view turn-id)
        edges     (filterv #(= turn-id (:edge/turn-id %)) (:edges view))
        summaries (into {} (map (juxt identity #(child-summary st %)))
                        (distinct (map :edge/to-session edges)))
        kids      (mapv (fn [e]
                          (merge (select-keys e [:edge/id :edge/type :edge/kind :edge/task-preview])
                                 (summaries (:edge/to-session e))))
                        edges)]
    {:session/id   sid
     :turn/id      turn-id
     :turn/status  (:turn/status turn)
     :turn/self    {:usage (:turn/usage turn) :cost (:turn/cost turn)}
     :delegation/edges    edges
     :delegation/children kids
     :delegation/children-cost
     (if (empty? summaries)
       {:cost/status :known :cost/usd 0}
       (cost-rollup (map (comp :cost/usd :child/cost) (vals summaries))))}))

(defn verify-claim
  "Mechanical existence check for a claimed engine fact. Accepts:
   - a payload-ref            → does the blob resolve?
   - {:session/id s :head/id h} → is that head published in s's log?
   - {:session/id s :edge/id e} → is that edge in s's log?
   - {:session/id s}            → does the session exist?
   Returns {:claim/kind k :verified? bool}."
  [st claim]
  (cond
    (payload/payload-ref? claim)
    {:claim/kind :payload
     :verified?  (some? (store/read-payload* st claim))}

    (and (map? claim) (:head/id claim))
    (let [sid (or (:session/id claim) (:head/session claim))]
      {:claim/kind :head
       :verified?  (boolean (when-let [v (and sid (safe-read-state st sid))]
                              (store/head-by-id v (:head/id claim))))})

    (and (map? claim) (:edge/id claim) (:session/id claim))
    {:claim/kind :edge
     :verified?  (boolean (some #(= (:edge/id claim) (:edge/id %))
                                (:edges (safe-read-state st (:session/id claim)))))}

    (and (map? claim) (:session/id claim))
    {:claim/kind :session
     :verified?  (some? (safe-read-state st (:session/id claim)))}

    :else
    {:claim/kind :unknown :verified? false}))
