(ns fractal.engine.adapter.fake
  "L2 · FakeAdapter (05 §5, 10 §1) — the offline test backbone. A PURE function
   of the request (not a mutable queue), so scripted multi-step runs are
   deterministic and order-independent. Ignores opts (no streaming/retry); the
   loop's with-deadline still wraps it, so timeout behavior is uniform."
  (:require [fractal.engine.adapter :as adapter]
            [clojure.string :as str]))

(defn last-user
  "The content of the last :user message in the (wire-shape) request — what a
   responder clause matches on."
  [request]
  (->> (:messages request) (filter #(= :user (:role %))) last :content))

(defn text->call-record
  "Wrap an assistant-text string as a well-formed honest-:unknown call record."
  [text request]
  {:text          text
   :finish-reason :stop
   :usage         adapter/unknown-usage
   :cost          adapter/unknown-cost
   :cache         adapter/unknown-cache
   :model         (:model request)
   :provider      :fake})

(defn fake-adapter
  "Construct a FakeAdapter from `respond-fn` : request → assistant-string |
   call-record."
  [respond-fn]
  (reify adapter/LlmAdapter
    (-complete [_ request _opts]
      (let [r (respond-fn request)]
        (if (map? r) r (text->call-record r request))))))

(defn responder
  "Build a respond-fn from `[[match reply] …]` clauses. `match` = a substring of
   the last user message | a predicate on the request | :default. `reply` = an
   assistant string | a call record | a fn of the request. First match wins;
   pure (race-free under fan-out)."
  [clauses]
  (fn [request]
    (let [lu (or (last-user request) "")]
      (loop [cs clauses]
        (if-let [[match reply] (first cs)]
          (if (cond
                (= :default match) true
                (string? match)    (str/includes? lu match)
                (fn? match)        (match request)
                :else              false)
            (if (fn? reply) (reply request) reply)
            (recur (rest cs)))
          (throw (ex-info "fake responder: no clause matched (add a :default)"
                          {:error/type :fake/no-match :last-user lu})))))))
