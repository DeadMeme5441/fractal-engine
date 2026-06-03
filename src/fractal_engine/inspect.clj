(ns fractal-engine.inspect
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.snapshot :as snapshot]))

(defn read-artifacts [locator]
  (let [root (artifacts/store-root-for-locator locator)
        sid (artifacts/session-id-for-locator locator)
        v (session-db/view root sid)
        latest-turn (last (:turns v))
        final-ref (or (:turn/final-ref latest-turn) (:final-ref v))]
    (merge v
           {:refs (session-db/read-ref root sid)
            :final (when final-ref
                     {:final/session-id sid
                      :final/latest-turn-id (:turn/id latest-turn)
                      :final/latest-head-id (:ref/current-head (session-db/read-ref root sid))
                      :final/latest-value-preview (:turn/final-preview latest-turn)
                      :final/value-ref final-ref})
            :usage (artifacts/derive-usage locator (:calls v))
            :tree (artifacts/derive-tree locator (:session v) (:calls v))})))

(defn handle [kind locator]
  (str (name kind) ":" (:session/id locator)))

(defn snapshot-handle [locator row]
  (format "snapshot:%s#snapshot-turn-%04d" (:session/id locator) (long (:snapshot/turn-id row 0))))

(defn child-locators [locator]
  (->> (:calls (session-db/view (artifacts/store-root-for-locator locator)
                                (artifacts/session-id-for-locator locator)))
       (filter #(artifacts/child-call-types (:call/type %)))
       (keep #(artifacts/child-locator-for-call locator %))
       vec))

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

(defn child-summary [child-locator opts]
  (let [s (structured child-locator opts)]
    (select-keys s [:session :snapshots :children :lineage :open-problems :handles])))

(defn structured
  ([dir] (structured dir {}))
  ([dir opts]
   (let [{:keys [session messages turns evals calls snapshots heads refs invocations final lineage restore] :as arts} (read-artifacts dir)
         completed (filterv snapshot/completed-turn-snapshot? snapshots)
         children (when (:tree opts)
                    (mapv #(child-summary % opts) (child-locators dir)))
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
                :head-count (count heads)
                :invocation-count (count invocations)
                :current-head (:ref/current-head refs)
                :completed-snapshot-count (count completed)
                :child-count (count (child-locators dir))
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
  (let [{:keys [session messages turns evals calls snapshots heads invocations final usage tree lineage]} (read-artifacts dir)
        data (structured dir (assoc opts :tree true))
        snapshot-rows (:snapshots data)
        latest-turn (last turns)]
    (with-out-str
      (println "session" (:session/id session)
               (str "status=" (:session/status session))
               (str "turns=" (count turns))
               (str "snapshots=" (count snapshots))
               (str "heads=" (count heads))
               (str "invocations=" (count invocations))
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
