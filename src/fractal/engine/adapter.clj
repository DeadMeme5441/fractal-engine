(ns fractal.engine.adapter
  "L2 · the LLM boundary PORT — just the protocol + the call-record shape, with
   ZERO engine deps (05, GD10). The engine never speaks HTTP; it asks an
   LlmAdapter for the next assistant message. Request assembly lives in
   `adapter.request` (L3); the impls in `.sdk`/`.fake`. The wall-clock deadline
   is applied by run-step! (with-deadline), NOT here — so it covers every impl."
  (:refer-clojure :exclude []))

(defprotocol LlmAdapter
  (-complete [adapter request opts]
    "request → the per-step call record:
       {:text :finish-reason :usage :cost :model :provider :cache}
     opts: {:retry … :stream? bool :on-delta (fn [frag])}. Honest :unknown —
     absent usage/cost/cache are :unknown, never 0 (08)."))

(defn first-user-content
  "The first :user message's content in a wire-shape request — the replay
   ROUTING KEY. Pure wire-shape fn (no engine deps): the leaf RECORDER
   (recursion) and the REPLAYER (adapter.replay) must compute the same key,
   so they both call THIS."
  [request]
  (->> (:messages request) (filter #(= :user (:role %))) first :content))

;; Honest-:unknown defaults (the call-record sub-shapes; 05 §1, 08).
(def unknown-usage
  {:usage/status :unknown
   :usage/input-tokens :unknown :usage/output-tokens :unknown
   :usage/cached-input-tokens :unknown :usage/cache-write-tokens :unknown})

(def unknown-cost  {:cost/status :unknown :cost/usd :unknown})

(def unknown-cache {:cache/status :unknown
                    :cache/cached-tokens :unknown :cache/cache-write-tokens :unknown})
