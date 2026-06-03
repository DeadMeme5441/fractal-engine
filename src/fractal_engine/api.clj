(ns fractal-engine.api
  "Stable public Clojure API for library consumers.

  This namespace is the supported facade for starting sessions, running turns,
  reading canonical session state, and checking claim provenance. It intentionally stays
  generic: the model-facing runtime surface remains the six functions installed
  inside a session namespace (`FINAL`, `lm`, `map-lm`, `rlm`, `map-rlm`,
  `attach-rlm`)."
  (:require [fractal-engine.process :as process]
            [fractal-engine.projection :as projection]
            [fractal-engine.provenance :as provenance]
            [fractal-engine.provider :as provider]
            [fractal-engine.session :as session]
            [fractal-engine.session-db :as session-db]))

;; Configuration

(defn config
  "Normalize engine configuration.

  Accepts the same generic keys used by the engine, including `:runs-dir`,
  `:models`, `:max-turns`, `:max-fanout`, `:max-leaf-concurrency`,
  `:call-timeout-ms`, `:retry`, and provider-specific test hooks such as
  `:scripted/responses` or `:scripted/response-fn`."
  ([] (process/config))
  ([opts] (process/config opts)))

;; Session / drive

(defn start-session!
  "Start a fresh live session and return a session handle.

  `opts` may include:
  - `:id` stable session id
  - `:alias` optional canonical alias
  - `:store-root` canonical store root for SQLite facts and blobs
  - `:overlay` extra session-level system instructions appended to the base
    behavior prompt in the single system message

  The overlay specializes the session transcript; it does not add model-facing
  functions or change engine behavior."
  ([cfg] (session/start-session! cfg))
  ([cfg opts] (session/start-session! cfg opts)))

(defn run-turn!
  "Run one user message on a live session handle. Returns the turn result map.
  Blocks until the turn settles. Throws `:fractal/turn-in-flight` if another turn is
  already running on this handle."
  [session user-message]
  (session/run-turn! session user-message))

(defn run-turn-async!
  "Run one user message on a background daemon thread. Returns a promise delivering
  the turn result map (a turn that throws delivers an `{:status :error ...}` map, not
  an escaping exception). Poll `progress` (or `load-node`) on the session locator to
  watch the turn in flight, then deref the promise for the result. Throws
  `:fractal/turn-in-flight` if another turn is already running on this handle."
  [session user-message]
  (session/run-turn-async! session user-message))

(defn stop-session!
  "Mark a live session stopped and flush the session artifacts."
  [session]
  (session/stop-session! session))

(defn resume-session!
  "Restore a session from a canonical session id, alias, or handle and return a live session handle.

  `opts` may include `:turn` or `:id`."
  ([cfg source-handle] (session/resume-session! cfg source-handle))
  ([cfg source-handle opts] (session/resume-session! cfg source-handle opts)))

(defn fork-session!
  "Fork a session from a canonical session id, alias, or handle and return a live handle.

  `target-store-root`, when present, is the canonical store root for the fork.

  `opts` may include `:turn`, `:id`, or `:alias`."
  ([cfg source-handle target-store-root] (session/fork-session! cfg source-handle target-store-root))
  ([cfg source-handle target-store-root opts] (session/fork-session! cfg source-handle target-store-root opts)))

(defn run-task!
  "One-shot helper: start a session, run one task, stop the session, and return
  the turn result map.

  `opts` is passed to `start-session!`, so callers can set `:id`, `:alias`,
  `:store-root`, and `:overlay`."
  ([task] (run-task! (config) task {}))
  ([cfg task] (run-task! cfg task {}))
  ([cfg task opts]
   (let [s (start-session! cfg opts)]
     (try
       (run-turn! s task)
       (finally
         (stop-session! s))))))

;; Read / inspect

(defn view
  "Load a session locator's canonical DB/blob view. Pure read; no provider calls."
  [locator]
  (projection/view locator))

(defn load-node
  "Load one node from a session locator. Defaults to the root node."
  ([locator] (projection/load-node locator))
  ([locator address] (projection/load-node locator address)))

(defn load-at
  "Load the node at `address` within a root session locator."
  [root-locator address]
  (projection/load-at root-locator address))

(defn tree
  "Load the full addressable summary tree for a session locator."
  ([locator] (projection/tree locator))
  ([locator address label] (projection/tree locator address label)))

(defn node-locator
  "Resolve a node address to its canonical locator, starting at `root-locator`."
  [root-locator address]
  (projection/node-locator root-locator address))

(defn event-stream
  "Return the canonical event stream for a session locator."
  [locator]
  (projection/event-stream locator))

(defn event-trace
  "Return an operator-facing audit trace for a session locator.

  Trace rows are derived from compact canonical event rows plus typed row
  metadata. They include row refs, summaries, and causal refs without resolving
  large payload blobs."
  [locator]
  (projection/event-trace locator))

(defn event-chain
  "Return causal ancestors plus `event-id` for a session locator."
  [locator event-id]
  (projection/event-chain locator event-id))

(defn progress
  "A lightweight, ref-free live snapshot of a session locator. Pairs with
  `run-turn-async!`."
  [locator]
  (projection/progress locator))

(defn check-consistency
  "Validate the canonical SQLite + BlobStore root and derived Datahike index.

  Returns a report map with typed issue maps in `:issues`; ordinary consistency
  failures are reported, not thrown."
  ([store-root]
   ((requiring-resolve 'fractal-engine.store.consistency/check-consistency) store-root))
  ([store-root opts]
   ((requiring-resolve 'fractal-engine.store.consistency/check-consistency) store-root opts)))

(defn rebuild-index!
  "Rebuild the derived Datahike index from canonical SQLite rows and BlobStore refs."
  [store-root]
  ((requiring-resolve 'fractal-engine.store.index/rebuild!)
   store-root
   (var-get (requiring-resolve 'fractal-engine.store.schema/schema))))

(defn list-sessions
  "List canonical session records under `store-root`."
  [store-root]
  (session-db/list-session-records store-root))

(defn resolve-session
  "Resolve `id-or-alias` to a canonical session locator/ref map."
  [store-root id-or-alias]
  (session-db/resolve-handle store-root id-or-alias {}))

(defn session-locator
  "Return the canonical locator for a session id."
  [store-root session-id]
  (session-db/locator store-root session-id))

(defn session-ref
  "Read the canonical current-head ref for a session."
  [store-root session-id]
  (session-db/read-ref store-root session-id))

(defn session-heads
  "Read immutable head records for a session."
  [store-root session-id]
  (filterv #(= session-id (:head/session %))
           (session-db/list-head-records store-root)))

(defn session-invocations
  "Read caller-owned outgoing invocation records for a session."
  [store-root session-id]
  (filterv #(= session-id (:caller/session %))
           (session-db/list-invocation-records store-root)))

(defn session-derivations
  "Read derivation/provenance edges where `session-id` is the source or target."
  [store-root session-id]
  (filterv #(or (= session-id (:derivation/source-session %))
                (= session-id (:derivation/target-session %)))
           (session-db/list-derivation-records store-root)))

(defn alias-session!
  "Create an alias fact pointing `alias` to `session-id`."
  [store-root alias session-id]
  (session-db/write-alias! store-root alias session-id))

;; Trust / provenance

(defn extract-claims
  "Extract evidenced claims from a final value."
  [final]
  (provenance/extract-claims final))

(defn check-claims
  "Check evidenced claims against the filesystem.

  `base` resolves relative evidence paths."
  ([final] (provenance/check-claims final))
  ([final base] (provenance/check-claims final base)))

(defn summarize-claims
  "Summarize claim check verdicts into totals and an overall trust call."
  [checks]
  (provenance/summarize checks))

(defn node-provenance
  "Return the final value, claims, child refs, and leaf refs for a node address."
  [root-dir address]
  (provenance/node-provenance root-dir address))

;; Provider helpers

(defn provider-descriptor
  "Return the generic provider descriptor for `provider-id`."
  [provider-id]
  (provider/descriptor provider-id))

(defn auth-status
  "Return auth availability data for `provider-id`."
  [provider-id]
  (provider/auth-status provider-id))

(defn scripted-responder
  "Build a content-addressed value for `:scripted/response-fn`. `clauses` is a
  sequence of [match reply] pairs; the first whose `match` applies wins. `match` is
  a substring of the last user message (string), a predicate on the request (fn), or
  `:default`. `reply` is a string, a response map, or a fn of the request. Race-free
  under concurrent `map-lm`/`map-rlm` fanout, so it is the recommended way to script
  fanned-out offline runs (a shared response queue has undefined order once leaves
  run in parallel)."
  [clauses]
  (provider/responder clauses))
