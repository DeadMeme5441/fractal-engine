(ns fractal.engine.store.slots
  "L1.5 · store-impl-shared slot machinery. Both MemoryStore and SqliteStore key
   their live in-process state by `:session/id` in a `:sessions` atom of per-session
   *slots* (`{:view :lock :dispatch :sci-ctx :busy}`). `stop-dispatch!` is the one
   genuinely store-agnostic operation the composition root needs across impls: it
   stops a session's live dispatcher thread without the caller knowing which store
   it holds (the SessionStore protocol is fixed — 02 §4 — so this is a plain helper,
   not a protocol method). Depends only on `live` (no store dep, no cycle)."
  (:require [fractal.engine.live :as live]))

(defn stop-dispatch!
  "Stop a session's live dispatcher thread (idempotent; daemon threads are
   JVM-exit-safe regardless). Works on any store whose per-session slots carry a
   `:dispatch` under a `:sessions` atom — MemoryStore and SqliteStore both do."
  [store sid]
  (when-let [slot (get @(:sessions store) sid)]
    (live/stop-dispatch (:dispatch slot))))
