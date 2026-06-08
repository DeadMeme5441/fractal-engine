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
  "The opaque passthrough attached to every adapter request (05 §4). The root
   agent loop scopes to the :agent purpose. When a request carries dynamic
   per-turn prompt material, cache only the stable system breakpoint on
   system-and-tail providers; dynamic prompt text must not become a tail cache
   anchor."
  ([view cfg]
   (build-cache-opts view cfg {}))
  ([view cfg {:keys [dynamic-request?]}]
   (cond-> {:enabled? true
            :ttl      (:cache-ttl cfg)
            :scope-id (scope-id (cache-id (:session view)) :agent)}
     dynamic-request? (assoc :breakpoints 1))))

(defn build-leaf-cache-opts
  "The opaque passthrough for a LEAF provider call (Phase 3, 08 §4). Scopes to
   the :leaf purpose under the CALLER session's cache-id (leaves run inside the
   caller's session state) — kept for symmetry; leaf prompts usually sit below
   the provider's cache minimum, so it is near a no-op."
  [caller-cache-id cfg]
  {:enabled? true
   :ttl      (:cache-ttl cfg)
   :scope-id (scope-id caller-cache-id :leaf)})
