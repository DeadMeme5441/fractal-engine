(ns fractal-eval.report
  "Aggregate per-example results into headline metrics and write them out three ways:
  results.edn (full fidelity), results.json (for plotting/sharing), and a Markdown
  summary table (for a human / a README). Every file embeds the repro manifest."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn- mean [xs]
  (let [xs (keep identity xs)]
    (when (seq xs) (/ (double (reduce + xs)) (count xs)))))

(defn aggregate [results]
  (let [n (count results)
        scored (remove #(= :error (:status %)) results)
        correct (reduce + (keep :correct? results))
        costs (keep :cost-usd results)]
    {:n n
     :n-scored (count scored)
     :n-errors (count (filter #(= :error (:status %)) results))
     :n-correct correct
     :accuracy (when (pos? n) (/ (double correct) n))
     :numeric-accuracy-mean (mean (keep :numeric-accuracy results))
     :loose-accuracy-mean (mean (keep :loose-accuracy results))
     :total-cost-usd (when (seq costs) (reduce + costs))
     :tokens-total (let [t (keep :tokens-total results)] (when (seq t) (reduce + t)))
     :wall-ms-mean (mean (keep :wall-ms results))
     ;; ablation audit (only meaningful for :engine-norec; nil-safe otherwise)
     :children-spawned-total (let [c (keep :children-spawned results)] (when (seq c) (reduce + c)))
     :ablation-honored-n (count (filter #(true? (:ablation-honored? %)) results))
     :ablation-violated-n (count (filter #(true? (:ablation-violated? %)) results))}))

(defn- fmt [x] (cond (nil? x) "—" (float? x) (format "%.3f" (double x))
                     (double? x) (format "%.3f" x) :else (str x)))
(defn- fmt$ [x] (if (nil? x) "—" (format "$%.4f" (double x))))

(defn markdown-table
  "A compact Markdown summary for one or more (label, aggregate) pairs — e.g.
  engine vs flat baseline side by side."
  [rows]
  (str/join
   "\n"
   (concat
    ["| run | n | correct | accuracy | num-acc | loose-acc | cost | tokens | mean ms |"
     "|---|--:|--:|--:|--:|--:|--:|--:|--:|"]
    (for [{:keys [label agg]} rows]
      (format "| %s | %d | %d | %s | %s | %s | %s | %s | %s |"
              label (:n agg) (:n-correct agg) (fmt (:accuracy agg))
              (fmt (:numeric-accuracy-mean agg)) (fmt (:loose-accuracy-mean agg))
              (fmt$ (:total-cost-usd agg)) (fmt (:tokens-total agg))
              (fmt (:wall-ms-mean agg)))))))

(defn build-report
  "Assemble the full results map: manifest + per-mode aggregates + raw per-example
  results. `runs` is a map of mode-label -> {:results [...] :spent ... :stopped-early?}."
  [manifest runs]
  {:manifest manifest
   :summary (into {} (map (fn [[label run]]
                            [label (assoc (aggregate (:results run))
                                          :spent-usd (:spent run)
                                          :stopped-early? (:stopped-early? run))])
                          runs))
   :runs (into {} (map (fn [[label run]] [label (:results run)]) runs))})

(defn write!
  "Write results.edn, results.json, and results.md under out-dir. Returns out-dir."
  [out-dir report]
  (io/make-parents (io/file out-dir "results.edn"))
  (spit (io/file out-dir "results.edn") (with-out-str (pprint/pprint report)))
  (spit (io/file out-dir "results.json") (json/generate-string report {:pretty true}))
  (let [rows (for [[label agg] (:summary report)] {:label (name label) :agg agg})]
    (spit (io/file out-dir "results.md")
          (str "# Eval results\n\n"
               "Benchmark: **" (get-in report [:manifest :repro/benchmark]) "**  ·  "
               "split: " (get-in report [:manifest :repro/split]) "  ·  "
               "engine " (get-in report [:manifest :engine/git-sha]) " (prompt v"
               (get-in report [:manifest :engine/prompt-version]) ")\n\n"
               (markdown-table rows)
               "\n\nReproduce: `" (get-in report [:manifest :repro/command]) "`\n")))
  out-dir)
