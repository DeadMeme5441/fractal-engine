(ns seeing
  "The RUNS / SEES dev harness (10 §2). Drives the REAL session loop and prints,
   per step, what the model RUNS (its :assistant code) beside what the engine
   SEES (the :observation fit-or-stub feedback), then the untruncated FINAL the
   model only ever saw as a stub. The fastest way to tune the observation
   surface (ok-fit, max-coll-size) against realistic code.

   Lives behind the :dev path — never in the build. Run with:
     clojure -M:dev:test -e \"(require 'seeing) (seeing/demo)\""
  (:require [fractal.engine.api :as fe]))

(defn- hydrate [handle m] (fe/read-payload handle (:message/content-or-ref m)))

(defn print-trace
  "Print the RUNS/SEES trace for one turn id, then the untruncated FINAL."
  [handle turn-id result]
  (doseq [m (->> (fe/event-stream handle)
                 (filter #(= :message/appended (:event/type %)))
                 (map :message)
                 (filter #(= turn-id (:message/turn-id %))))]
    (case (:message/role m)
      :assistant   (println "──── fractal RUNS ────  " (hydrate handle m))
      :observation (println "──── fractal SEES ────  " (hydrate handle m))
      nil))
  (when (= :final (:status result))
    (println "──── FINAL (full, harness view) ────  " (pr-str (:turn/final-value result))))
  result)

(defn run-and-see
  "run-turn! + print the RUNS/SEES trace. Returns the TurnResult."
  [handle msg]
  (let [res (fe/run-turn! handle msg)]
    (print-trace handle (:turn/id res) res)))

(defn demo
  "A self-contained offline demo over the FakeAdapter."
  []
  (let [respond (fe/responder
                  [["scan" "```clojure\n(def files (vec (range 412)))\n(count files)\n```"]
                   [#(re-find #"Observation" (str (:content (last (:messages %)))))
                    "```clojure (FINAL {:n (count files)})```"]])
        s (fe/start-session! (fe/make-config {:adapter :fake :model "fake-model"
                                              :fake/respond respond}))]
    (run-and-see s "scan the files and count them")))
