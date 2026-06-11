(ns fractal.engine.api
  "L5 · THE SDK surface (06). Thin — delegates to the internals and exposes
   nothing else. Phase 1 ships the clojure harness; the Phase-3/4 rlm harness
   EXTENDS this same surface (recursion is internal), so these signatures do not
   change."
  (:require [fractal.engine.config :as config]
            [fractal.engine.session :as session]
            [fractal.engine.store :as store]
            [fractal.engine.store.sqlite :as sqlite]
            [fractal.engine.live :as live]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.adapter.replay :as replay]
            [fractal.engine.projection :as projection]))

;; --- Config ---------------------------------------------------------------

(defn make-config [opts] (config/make-config opts))

;; --- Lifecycle ------------------------------------------------------------

(defn start-session!
  ([cfg]      (session/start-session! cfg))
  ([cfg opts] (session/start-session! cfg opts)))

(defn run-turn!       [handle msg] (session/run-turn! handle msg))
(defn run-turn-async! [handle msg] (session/run-turn-async! handle msg))

(defn run-turn-with-contract!
  "run-turn! + the validate→correct→retry loop every downstream consumer has
   hand-rolled independently. The engine interprets NOTHING — the contract is
   yours:

     :validate     (fn [turn-result]) → nil/false = accept; any truthy value is
                   the REJECTION (string or data) fed to the correction message.
                   Called only on :final results.
     :max-attempts total turns spent before giving up (default 3).
     :correction   (fn [rejection turn-result]) → the next turn's user message
                   (default: a plain restatement of the rejection).

   Returns the accepted TurnResult; on exhaustion, the last result with
   :contract/rejected and :contract/attempts attached. Non-:final terminal
   results (timeout/budget/error) return immediately — a correction message
   cannot fix a dead turn."
  [handle msg {:keys [validate max-attempts correction]
               :or   {max-attempts 3}}]
  {:pre [(fn? validate)]}
  (let [correction (or correction
                       (fn [rejection _result]
                         (str "Your FINAL was rejected by the caller's contract:\n"
                              (if (string? rejection) rejection (pr-str rejection))
                              "\nCorrect the value and call FINAL again.")))]
    (loop [msg msg attempt 1]
      (let [result (session/run-turn! handle msg)]
        (if-not (= :final (:status result))
          result
          (let [rejection (validate result)]
            (cond
              (not rejection)            result
              (>= attempt max-attempts)  (assoc result
                                                :contract/rejected rejection
                                                :contract/attempts attempt)
              :else (recur (correction rejection result) (inc attempt)))))))))

(defn ^:alpha resume-session!
  "^:alpha / Phase 2 (06 §2) — durably reopen a persisted `:store :sqlite` session by
   folding its event log + restoring its REPL vars; returns a fresh handle. Throws if
   cfg's store is not :sqlite or the session id is unknown."
  ([cfg sid]      (session/resume-session! cfg sid))
  ([cfg sid opts] (session/resume-session! cfg sid opts)))

(defn ^:alpha fork-session!
  "^:alpha / U1 (hermes-fractal upstream) — HOST-side fork: a fresh session
   materialized from a selected immutable head of a persisted `:store :sqlite`
   session, REPL vars restored at zero token cost, the SOURCE NEVER ADVANCED.
   opts {:head/id …} reaches a specific head (including a :turn-aborted
   wreckage head — the only way); default is the latest non-aborted head.
   Capability clamps to the narrower of cfg and the source; the fork must
   re-present the source's surfaces (:bundle/allow-mismatch? to override)."
  ([cfg source-sid]      (session/fork-session! cfg source-sid))
  ([cfg source-sid opts] (session/fork-session! cfg source-sid opts)))

(defn stop-session!
  ([handle]      (session/stop-session! handle))
  ([handle opts] (session/stop-session! handle opts)))

(defn close-session!
  "Release a handle's store resources (stop live dispatch; close the sqlite
   connection). A sqlite session can afterwards be reopened with resume-session!."
  [handle]
  (session/close-session! handle))

(defn compact-session! [handle] (session/compact-session! handle))

;; --- Reads (pure projections; no provider calls) --------------------------

(defn view         [handle] (store/current-view (:store handle) (:session-id handle)))
(defn progress     [handle] (live/progress (view handle)))
(defn event-stream
  "The session's FULL ordered event log, served from the durable store — NOT
   the in-process view's :events, which is a bounded working window after a
   snapshot reopen (qbu). The log is the truth; this reads the log."
  [handle]
  (store/events-since (:store handle) (:session-id handle) 0))
(defn events-since [handle ev-id] (store/events-since (:store handle) (:session-id handle) ev-id))

(defn read-payload
  "PUBLIC + load-bearing (06 §3): hydrate any payload-ref the read/live surface
   returns; a non-ref passes through unchanged."
  [handle ref-or-value]
  (payload-io/read-payload (:store handle) ref-or-value))

;; --- Live query -----------------------------------------------------------

(defn subscribe! [handle callback] (store/subscribe! (:store handle) (:session-id handle) callback))

;; --- 88j · embedder facts, pins, projections --------------------------------

(defn append-fact!
  "Append one store-scoped opaque fact {:fact/tag kw :fact/value edn}. The
   engine orders/persists/serves it and NEVER interprets it. Returns the
   stamped fact (:fact/id monotonic, :fact/at)."
  [handle fact]
  (store/append-fact! (:store handle) fact))

(defn facts-since
  "Ordered store-scoped facts with :fact/id > fact-id — the stream disposable
   app projections fold from."
  [handle fact-id]
  (store/facts-since (:store handle) fact-id))

(defn pin!
  "Upsert a named durable pointer {:pin/name … :pin/ref … (+ :pin/meta,
   optional :pin/expected-version for CAS)}. Refs are validated against the
   log (dangling payload/head refs are rejected). Returns the stamped pin."
  [handle pin]
  (store/pin! (:store handle) pin))

(defn read-pin [handle pin-name] (store/read-pin (:store handle) pin-name))
(defn list-pins [handle] (store/list-pins (:store handle)))

(defn delegation-report
  "What did this turn delegate, and what did the subtree cost? Edges + per-child
   status/turns/usage/cost (:unknown-aware), plus the turn's self-only numbers."
  [handle turn-id]
  (projection/delegation-report (:store handle) (:session-id handle) turn-id))

(defn verify-claim
  "Mechanical existence check for a claimed engine fact: a payload-ref,
   {:session/id+:head/id}, {:session/id+:edge/id}, or {:session/id}.
   → {:claim/kind k :verified? bool}. `source` is a handle or a SessionStore."
  [source claim]
  (projection/verify-claim (or (:store source) source) claim))

;; --- Recorded replay (jz3a) -------------------------------------------------

(defn replay-responder
  "Build a respond-fn (use as `:adapter :fake` + `:fake/respond`) that serves
   the recorded step responses AND recorded leaf (lm/map-lm) calls of `sids`
   from a prior run — deterministic re-execution from the durable log.
   `source` is a session handle or a SessionStore (e.g. `open-sqlite-store` on
   another run's dir). :fractal/replay-leaf-unsupported fires only for leaf
   requests no recording covers (a recording made before leaf events existed,
   or a diverged fan-out)."
  [source sids]
  (replay/replay-responder (or (:store source) source) sids))

(defn surface-calls
  "jz3b · the recorded surface invocations of session `sid` (requires the run
   to have used :surface/record? true), results hydrated — the value to feed a
   replay run's :surface/replay-calls. `source` is a handle or a SessionStore."
  [source sid]
  (let [st (or (:store source) source)]
    (mapv (fn [c]
            (-> c
                (assoc :call/result (payload-io/read-payload st (:call/result-or-ref c)))
                (dissoc :call/result-or-ref)))
          (:surface-calls (store/read-state st sid)))))

(defn open-sqlite-store
  "Open the durable store under `dir` directly — for replay/inspection across
   runs without resuming a session. Pair with `close-store!`."
  [dir]
  (sqlite/sqlite-store {:dir dir}))

(defn close-store!
  "Close a store opened with open-sqlite-store (releases the JDBC connection)."
  [store]
  (sqlite/close! store))

;; --- Test helper (re-exported) --------------------------------------------

(def responder
  "Build a fake responder from `[[match reply] …]` clauses (10 §1)."
  fake/responder)
