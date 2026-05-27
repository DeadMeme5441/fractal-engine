(ns fractal-engine.inspect
  (:require [clojure.pprint :as pp]
            [fractal-engine.artifacts :as artifacts]))

(defn read-artifacts [dir]
  {:session (artifacts/read-edn-file (artifacts/path dir "session.edn") {})
   :messages (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
   :turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])
   :evals (artifacts/read-edn-file (artifacts/path dir "evals.edn") [])
   :calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
   :snapshots (artifacts/read-edn-file (artifacts/path dir "snapshots.edn") [])
   :final (artifacts/read-edn-file (artifacts/path dir "final.edn") nil)
   :usage (artifacts/read-edn-file (artifacts/path dir "usage.edn") nil)
   :tree (artifacts/read-edn-file (artifacts/path dir "tree.edn") nil)})

(defn tree-lines
  ([tree] (tree-lines tree 0))
  ([tree depth]
   (let [indent (apply str (repeat depth "  "))
         line (format "%s%s %s calls=%s evals=%s"
                      indent
                      (:tree/session-id tree)
                      (:tree/status tree)
                      (or (:call/count tree) "?")
                      (or (:eval/count tree) "?"))]
     (cons line (mapcat #(tree-lines % (inc depth)) (:tree/children tree))))))

(defn summary-string [dir]
  (let [{:keys [session messages turns evals calls snapshots final usage tree]} (read-artifacts dir)
        latest-turn (last turns)]
    (with-out-str
      (println "Session" (:session/id session) (:session/status session))
      (println "Turns:" (count turns) "Latest:" (:turn/id latest-turn) (:turn/status latest-turn))
      (println "Messages:" (count messages) "Evals:" (count evals) "Calls:" (count calls) "Snapshots:" (count snapshots))
      (when final
        (println "Latest final:")
        (pp/pprint (:final/latest-value-preview final)))
      (when usage
        (println "Usage:")
        (pp/pprint (select-keys usage [:usage/status :usage/root :usage/leaf :usage/total-tree])))
      (println "Tree:")
      (doseq [line (tree-lines tree)]
        (println line)))))
