(ns fractal-engine.call
  "The one traced-call seam. Every call the engine makes — a provider completion, a
  spawned child, an attached child — is recorded the same way: reserve an id, write
  a :running call row, run the work, then record success or failure on that row and
  return the produced value. The kinds differ only in their `work` thunk and in how
  they record the outcome; the trace skeleton is shared.

  This is the single place a governor (per-call timeout + total-call cap) will
  decorate — by wrapping `work` — instead of editing four bespoke try/catch blocks.
  Sandboxing decorates the eval kernel, not this seam."
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.time :as time]))

(defn traced!
  "Run one traced unit of work and return its value.

    :build-call  (fn [call-id] -> partial call row)   the :running row to record
    :work        (fn [call-id] -> raw)                the expensive/failable thunk
    :succeed     (fn [call-id raw] -> {:value v :patch m})  record success, extract value
    :fail        (fn [call-id throwable] -> {:patch m :ex e})  record failure, supply throw

  On success the row is merged with `:patch` (plus :call/ended-at) and `:value` is
  returned. On a throw from `work`, the row is merged with the failure `:patch`
  (plus :call/status :error and :call/ended-at) and `:ex` (or the original) is
  rethrown — so a failed call is always recorded before it propagates."
  [state {:keys [build-call work succeed fail]}]
  (let [call-id (artifacts/next-counter! state :call)]
    (artifacts/add-call! state (assoc (build-call call-id) :call/id call-id))
    (let [raw (try
                (work call-id)
                (catch Throwable t
                  (let [{:keys [patch ex]} (fail call-id t)]
                    (artifacts/update-call! state call-id merge
                                            (merge {:call/status :error
                                                    :call/ended-at (time/now-str)}
                                                   patch))
                    (throw (or ex t)))))
          {:keys [value patch]} (succeed call-id raw)]
      (artifacts/update-call! state call-id merge
                              (merge {:call/ended-at (time/now-str)} patch))
      value)))
