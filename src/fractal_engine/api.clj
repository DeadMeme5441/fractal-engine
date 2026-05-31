(ns fractal-engine.api
  "Stable public Clojure API for library consumers.

  This namespace is the supported facade for starting sessions, running turns,
  reading run artifacts, and checking claim provenance. It intentionally stays
  generic: the model-facing runtime surface remains the six functions installed
  inside a session namespace (`FINAL`, `lm`, `map-lm`, `rlm`, `map-rlm`,
  `attach-rlm`)."
  (:require [fractal-engine.process :as process]
            [fractal-engine.projection :as projection]
            [fractal-engine.provenance :as provenance]
            [fractal-engine.provider :as provider]
            [fractal-engine.session :as session]))

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
  - `:dir` explicit run directory
  - `:overlay` extra session-level system instructions appended to the base
    behavior prompt in the single system message

  The overlay specializes the session transcript; it does not add model-facing
  functions or change engine behavior."
  ([cfg] (session/start-session! cfg))
  ([cfg opts] (session/start-session! cfg opts)))

(defn run-turn!
  "Run one user message on a live session handle. Returns the turn result map."
  [session user-message]
  (session/run-turn! session user-message))

(defn stop-session!
  "Mark a live session stopped and flush the session artifacts."
  [session]
  (session/stop-session! session))

(defn resume-session!
  "Restore a session from `source-dir` and return a live session handle.

  `opts` may include `:turn`, `:id`, or `:dir`."
  ([cfg source-dir] (session/resume-session! cfg source-dir))
  ([cfg source-dir opts] (session/resume-session! cfg source-dir opts)))

(defn fork-session!
  "Fork a session from `source-dir` into `target-dir` and return a live handle.

  `opts` may include `:turn`."
  ([cfg source-dir target-dir] (session/fork-session! cfg source-dir target-dir))
  ([cfg source-dir target-dir opts] (session/fork-session! cfg source-dir target-dir opts)))

(defn run-task!
  "One-shot helper: start a session, run one task, stop the session, and return
  the turn result map.

  `opts` is passed to `start-session!`, so callers can set `:id`, `:dir`, and
  `:overlay`."
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
  "Fold a run directory's journal into the materialized view. Pure read; no
  provider calls."
  [dir]
  (projection/view dir))

(defn load-node
  "Load one node from a run directory. Defaults to the root node."
  ([dir] (projection/load-node dir))
  ([dir address] (projection/load-node dir address)))

(defn load-at
  "Load the node at `address` within a root run directory."
  [root-dir address]
  (projection/load-at root-dir address))

(defn tree
  "Load the full addressable summary tree for a run directory."
  ([dir] (projection/tree dir))
  ([dir address label] (projection/tree dir address label)))

(defn node-dir
  "Resolve a node address to its on-disk directory, starting at `root-dir`."
  [root-dir address]
  (projection/node-dir root-dir address))

(defn journal-events
  "Return the raw append-only journal events for a run directory."
  [dir]
  (projection/journal-events dir))

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
