(ns fractal-engine.inspect
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.snapshot :as snapshot])
  (:import [java.nio.file Files Path]))

(defn read-artifacts [dir]
  {:session (artifacts/read-edn-file (artifacts/path dir "session.edn") {})
   :messages (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
   :turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])
   :evals (artifacts/read-edn-file (artifacts/path dir "evals.edn") [])
   :calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
   :snapshots (artifacts/read-edn-file (artifacts/path dir "snapshots.edn") [])
   :final (artifacts/read-edn-file (artifacts/path dir "final.edn") nil)
   :usage (artifacts/read-edn-file (artifacts/path dir "usage.edn") nil)
   :tree (artifacts/read-edn-file (artifacts/path dir "tree.edn") nil)
   :lineage (artifacts/read-edn-file (artifacts/path dir "lineage.edn") nil)
   :restore (artifacts/read-edn-file (artifacts/path dir "restore.edn") nil)})

(defn handle [kind dir]
  (str (name kind) ":" dir))

(defn snapshot-handle [dir row]
  (format "snapshot:%s#snapshot-turn-%04d" dir (long (:snapshot/turn-id row 0))))

(defn child-dirs [dir]
  (let [children (artifacts/path dir "children")]
    (if (Files/isDirectory children (make-array java.nio.file.LinkOption 0))
      (with-open [paths (Files/list children)]
        (->> (iterator-seq (.iterator paths))
             (filter #(Files/isDirectory ^Path % (make-array java.nio.file.LinkOption 0)))
             (sort-by str)
             vec))
      [])))

(defn snapshot-summary [dir row]
  {:handle (snapshot-handle dir row)
   :turn-id (:snapshot/turn-id row)
   :snapshot-id (:snapshot/id row)
   :message-through-id (:snapshot/message-through-id row)
   :eval-id (:snapshot/eval-id row)
   :ref (:snapshot/ref row)
   :var-count (get-in row [:snapshot/summary :var/count] 0)
   :restorable-count (get-in row [:snapshot/summary :var/restorable] 0)
   :unrestorable-count (get-in row [:snapshot/summary :var/unrestorable] 0)
   :blob-count (get-in row [:snapshot/summary :blob/count] 0)})

(defn has-completed-snapshot? [artifacts]
  (some snapshot/completed-turn-snapshot? (:snapshots artifacts)))

(declare structured)

(defn child-summary [child-dir opts]
  (let [s (structured (str child-dir) opts)]
    (select-keys s [:session :snapshots :children :lineage :open-problems :handles])))

(defn structured
  ([dir] (structured dir {}))
  ([dir opts]
   (let [{:keys [session messages turns evals calls snapshots final lineage restore] :as arts} (read-artifacts dir)
         completed (filterv snapshot/completed-turn-snapshot? snapshots)
         children (when (:tree opts)
                    (mapv #(child-summary % opts) (child-dirs dir)))
         self-problems (cond-> []
                         (not (has-completed-snapshot? arts))
                         (conj {:kind :no-completed-turn-snapshot
                                :handle (handle :session dir)}))
         child-problems (mapcat :open-problems children)]
     {:inspect/version 1
      :session {:handle (handle :session dir)
                :id (:session/id session)
                :status (:session/status session)
                :turn-count (count turns)
                :message-count (count messages)
                :eval-count (count evals)
                :call-count (count calls)
                :snapshot-count (count snapshots)
                :completed-snapshot-count (count completed)
                :child-count (count (child-dirs dir))
                :latest-final (:final/latest-value-preview final)}
      :snapshots (mapv #(snapshot-summary dir %) completed)
      :children (or children [])
      :lineage lineage
      :restore restore
      :open-problems (vec (concat self-problems child-problems))
      :handles {:session (handle :session dir)
                :latest-final (str "final:" dir "#latest-final")
                :snapshots (mapv #(snapshot-handle dir %) completed)}})))

(defn tree-lines
  ([tree] (tree-lines tree 0))
  ([tree depth]
   (let [indent (apply str (repeat depth "  "))
         line (format "%s%s %s calls=%s evals=%s snapshots=%s"
                      indent
                      (:tree/session-id tree)
                      (:tree/status tree)
                      (or (:call/count tree) "?")
                      (or (:eval/count tree) "?")
                      (or (:snapshot/count tree) "?"))]
     (cons line (mapcat #(tree-lines % (inc depth)) (:tree/children tree))))))

(defn snapshot-lines [rows]
  (for [row rows]
    (format "  turn=%s snapshot=%s vars=%s restorable=%s unrestorable=%s blobs=%s handle=%s"
            (:turn-id row)
            (:snapshot-id row)
            (:var-count row)
            (:restorable-count row)
            (:unrestorable-count row)
            (:blob-count row)
            (:handle row))))

(defn open-problem-lines [problems]
  (for [p problems]
    (str "  " (name (:kind p)) " " (:handle p))))

(defn summary-string
  ([dir] (summary-string dir {}))
  ([dir opts]
  (let [{:keys [session messages turns evals calls snapshots final usage tree lineage]} (read-artifacts dir)
        data (structured dir (assoc opts :tree true))
        snapshot-rows (:snapshots data)
        latest-turn (last turns)]
    (with-out-str
      (println "session" (:session/id session)
               (str "status=" (:session/status session))
               (str "turns=" (count turns))
               (str "snapshots=" (count snapshots))
               (str "children=" (get-in data [:session :child-count])))
      (println "latest turn:" (:turn/id latest-turn) (:turn/status latest-turn))
      (println "messages:" (count messages) "evals:" (count evals) "calls:" (count calls))
      (when final
        (println "latest final:")
        (pp/pprint (:final/latest-value-preview final)))
      (when (seq snapshot-rows)
        (println "snapshots")
        (doseq [line (snapshot-lines snapshot-rows)]
          (println line)))
      (when usage
        (println "usage:")
        (pp/pprint (select-keys usage [:usage/status :usage/root :usage/leaf :usage/total-tree :cost/total-tree])))
      (when lineage
        (println "lineage:")
        (pp/pprint lineage))
      (println "tree")
      (if tree
        (doseq [line (tree-lines tree)]
          (println line))
        (println "  unavailable"))
      (when (seq (:open-problems data))
        (println "open problems")
        (doseq [line (open-problem-lines (:open-problems data))]
          (println line)))))))
