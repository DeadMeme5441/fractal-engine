(ns fractal.engine.api
  "L5 · THE SDK surface (06). Thin — delegates to the internals and exposes
   nothing else. Phase 1 ships the clojure harness; the Phase-3/4 rlm harness
   EXTENDS this same surface (recursion is internal), so these signatures do not
   change."
  (:require [fractal.engine.config :as config]
            [fractal.engine.session :as session]
            [fractal.engine.store :as store]
            [fractal.engine.live :as live]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.adapter.fake :as fake]))

;; --- Config ---------------------------------------------------------------

(defn make-config [opts] (config/make-config opts))

;; --- Lifecycle ------------------------------------------------------------

(defn start-session!
  ([cfg]      (session/start-session! cfg))
  ([cfg opts] (session/start-session! cfg opts)))

(defn run-turn!       [handle msg] (session/run-turn! handle msg))
(defn run-turn-async! [handle msg] (session/run-turn-async! handle msg))

(defn stop-session!
  ([handle]      (session/stop-session! handle))
  ([handle opts] (session/stop-session! handle opts)))

(defn compact-session! [handle] (session/compact-session! handle))

;; --- Reads (pure projections; no provider calls) --------------------------

(defn view         [handle] (store/current-view (:store handle) (:session-id handle)))
(defn progress     [handle] (live/progress (view handle)))
(defn event-stream [handle] (:events (view handle)))
(defn events-since [handle ev-id] (store/events-since (:store handle) (:session-id handle) ev-id))

(defn read-payload
  "PUBLIC + load-bearing (06 §3): hydrate any payload-ref the read/live surface
   returns; a non-ref passes through unchanged."
  [handle ref-or-value]
  (payload-io/read-payload (:store handle) ref-or-value))

;; --- Live query -----------------------------------------------------------

(defn subscribe! [handle callback] (store/subscribe! (:store handle) (:session-id handle) callback))

;; --- Test helper (re-exported) --------------------------------------------

(def responder
  "Build a fake responder from `[[match reply] …]` clauses (10 §1)."
  fake/responder)
