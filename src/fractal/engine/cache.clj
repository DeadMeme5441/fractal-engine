(ns fractal.engine.cache
  "L1 · the prompt-cache contract (08). The SDK owns ALL per-provider marker
   placement; the engine owns a stable cache identity and forwards an opaque
   passthrough map `{:enabled? :ttl :scope-id}` on every request."
  (:require [fractal.engine.payload :as payload])
  (:import [java.nio.charset StandardCharsets]))

(def policy-version 1)

(defn cache-id
  "A STABLE session identity, separate from :session/id. Defaults to the logical
   session id; preserved across resume/fork (cache affinity); fresh per child."
  [session]
  (or (:session/cache-id session) (:session/id session)))

(defn scope-id
  "Deterministic, purpose-scoped digest derived from cache-id (purpose ∈
   #{:agent :leaf}). Phase 1 uses only :agent (root) scope (08 §4)."
  [cid purpose]
  (let [basis (str policy-version ":" (name purpose) ":" cid)
        hex   (payload/sha256-hex (.getBytes basis StandardCharsets/UTF_8))]
    (str "fr:" (name purpose) ":" (subs hex 0 32))))

(defn build-cache-opts
  "The opaque passthrough attached to every adapter request (05 §4). Phase 1
   scopes to the root :agent purpose."
  [view cfg]
  {:enabled? true
   :ttl      (:cache-ttl cfg)
   :scope-id (scope-id (cache-id (:session view)) :agent)})
