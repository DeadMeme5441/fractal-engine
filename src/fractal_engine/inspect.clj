(ns fractal-engine.inspect
  (:require [clojure.pprint :as pp]
            [fractal-engine.session-store :as store]))

(defn read-artifacts [dir]
  (select-keys (store/load-session dir)
               [:session :messages :turns :evals :calls :snapshots :final :usage :tree :lineage :rehydration]))

(defn- preview-str [value]
  (let [s (binding [*print-length* 20 *print-level* 4 *print-namespace-maps* false]
            (pr-str value))]
    (if (> (count s) 220)
      (str (subs s 0 220) " ...")
      s)))

(defn- tree-lines
  ([summary] (tree-lines summary 0))
  ([summary depth]
   (let [indent (apply str (repeat depth "  "))
         final-preview (some-> summary :latest-final :preview preview-str)
         line (format "%s%s %s turns=%s children=%s%s"
                      indent
                      (:id summary)
                      (:status summary)
                      (:turn-count summary)
                      (:child-count summary)
                      (if final-preview
                        (str " final=" final-preview)
                        ""))]
     (cons line
           (mapcat #(tree-lines % (inc depth)) (:children summary))))))

(defn- open-problems* [summary]
  (let [self (when (contains? #{:running :error :malformed :rehydration-error} (:status summary))
               {:kind (case (:status summary)
                        :running :running-session
                        :malformed :malformed-session
                        :rehydration-error :rehydration-error
                        :error :error-session
                        :open-session)
                :handle (:handle summary)
                :path (:path summary)
                :reason (name (:status summary))})]
    (vec (concat (when self [self])
                 (mapcat open-problems* (:children summary))
                 (map (fn [w]
                        {:kind :inspect-warning
                         :handle (:handle summary)
                         :path (:path summary)
                         :reason (:warning/type w)
                         :message (:message w)})
                      (:warnings summary))))))

(defn structured [dir opts]
  (let [summary (store/session-summary dir opts)]
    {:inspect/version 1
     :session (select-keys summary [:handle :path :id :status :fingerprint :turn-count :child-count :eval-count :call-count :message-count])
     :latest-final (:latest-final summary)
     :tree (mapv (fn [s]
                   (select-keys s [:handle :id :path :status :turn-count :child-count :latest-final]))
                 (tree-seq #(seq (:children %)) :children summary))
     :children (:children summary)
     :turns (:turns summary)
     :handles {:session (:handle summary)
               :turns (mapv :handle (:turns summary))
               :evals (mapv :handle (:evals summary))
               :calls (mapv :handle (:calls summary))
               :children (mapv #(or (:child/handle %) (:handle %)) (:children summary))
               :final (get-in summary [:latest-final :handle])}
     :lineage (:lineage summary)
     :rehydration (:rehydration summary)
     :open-problems (open-problems* summary)
     :files (:files summary)
     :warnings (:warnings summary)}))

(defn human-string [dir opts]
  (let [summary (store/session-summary dir opts)
        problems (open-problems* summary)]
    (with-out-str
      (println "session" (:id summary)
               (str "status=" (name (:status summary)))
               (str "turns=" (:turn-count summary))
               (str "children=" (:child-count summary)))
      (println "handle" (:handle summary))
      (println "path" (:path summary))
      (when-let [latest (:latest-final summary)]
        (println "latest final:" (preview-str (:preview latest))))
      (when (or (:lineage opts) (:all opts))
        (println)
        (println "lineage")
        (println "  kind:" (or (get-in summary [:lineage :lineage/kind]) :missing))
        (println "  source:" (or (get-in summary [:lineage :lineage/source :source/path]) "none")))
      (when (or (:handles opts) (:all opts))
        (println)
        (println "handles")
        (println "  session:" (:handle summary))
        (doseq [h (take 12 (map :handle (:turns summary)))]
          (println "  " h))
        (doseq [h (take 12 (map #(or (:child/handle %) (:handle %)) (:children summary)))]
          (println "  " h)))
      (when (or (:tree opts) (:all opts) (nil? (some opts [:tree :lineage :handles :turns :children])))
        (println)
        (println "tree")
        (doseq [line (tree-lines summary)]
          (println line)))
      (when (or (:turns opts) (:all opts))
        (println)
        (println "turns")
        (doseq [turn (:turns summary)]
          (println " " (:handle turn) (:status turn) "evals=" (:eval-count turn) "calls=" (:call-count turn))))
      (when (or (:children opts) (:all opts))
        (println)
        (println "children")
        (doseq [child (:children summary)]
          (println " " (:handle child) (:status child) (:path child))))
      (when (seq problems)
        (println)
        (println "open/stuck")
        (doseq [problem problems]
          (println " " (:kind problem) (:handle problem) (:reason problem))))
      (when (seq (:warnings summary))
        (println)
        (println "warnings")
        (doseq [warning (:warnings summary)]
          (println " " (:file warning) (:message warning)))))))

(defn summary-string
  ([dir] (summary-string dir {}))
  ([dir opts]
   (human-string dir (assoc opts :all true))))

(defn structured-string [dir opts]
  (with-out-str
    (pp/pprint (structured dir opts))))
