(ns fractal-engine.inspect
  (:require [clojure.pprint :as pp]
            [fractal-engine.artifacts :as artifacts]))

(defn read-artifacts [dir]
  {:session (artifacts/read-edn-file (artifacts/path dir "session.edn") {})
   :messages (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
   :evals (artifacts/read-edn-file (artifacts/path dir "evals.edn") [])
   :calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
   :snapshots (artifacts/read-edn-file (artifacts/path dir "snapshots.edn") [])
   :final (artifacts/read-edn-file (artifacts/path dir "final.edn") nil)
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
  (let [{:keys [session messages evals calls snapshots final tree]} (read-artifacts dir)]
    (with-out-str
      (println "Session" (:session/id session) (:session/status session))
      (println "Messages:" (count messages) "Evals:" (count evals) "Calls:" (count calls) "Snapshots:" (count snapshots))
      (when final
        (println "Final:")
        (pp/pprint (:final/value-preview final)))
      (println "Tree:")
      (doseq [line (tree-lines tree)]
        (println line)))))

