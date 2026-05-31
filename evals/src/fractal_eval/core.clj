(ns fractal-eval.core
  "CLI entry for the eval harness. Build (no engine changes) lives in evals/; run it
  as an external consumer of the engine:

    clojure -M:evals run \\
      --benchmark oolong --data evals/resources/fixtures/oolong-sample.jsonl \\
      --mode both --provider vertex-gemini --model gemini-3.5-flash \\
      --child-model gemini-3.5-flash \\
      --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \\
      --limit 5 --budget-usd 30 --max-turns 15 --call-timeout-ms 120000 \\
      --out evals/results/oolong-live

  --mode is flat | engine | engine-norec | both | all.
    flat          single-window baseline (the paper's vanilla condition)
    engine        full recursion (process/run-task!)
    engine-norec  the no-recursion ablation: engine path, but the task is prefixed
                  with a forceful 'do not call rlm/map-rlm/attach-rlm' banner and
                  each result is audited for children that slipped through.
    both          flat + engine over the same examples
    all           flat + engine-norec + engine over the same examples (live config)

  When multiple modes run in ONE invocation they share ONE cumulative budget cap:
  total spend across all modes never exceeds --budget-usd (the remaining budget is
  threaded into each subsequent mode). The report shows per-mode spend and the
  combined total."
  (:require [clojure.string :as str]
            [fractal-engine.time :as time]
            [fractal-eval.dataset :as dataset]
            [fractal-eval.report :as report]
            [fractal-eval.repro :as repro]
            [fractal-eval.runner :as runner]))

(defn parse-args [args]
  (loop [xs args m {}]
    (if (empty? xs)
      m
      (let [[k v & more] xs]
        (if (str/starts-with? k "--")
          (if (or (nil? v) (str/starts-with? v "--"))
            (recur (rest xs) (assoc m (keyword (subs k 2)) "true"))
            (recur more (assoc m (keyword (subs k 2)) v)))
          (recur (rest xs) m))))))

(defn- parse-double-opt [s] (when (and s (not= "true" s)) (Double/parseDouble (str s))))
(defn- parse-long-opt [s] (when (and s (not= "true" s)) (Long/parseLong (str s))))

(defn- engine-opts
  "The subset of flags cli/cfg-from-opts understands, passed straight through."
  [flags]
  (select-keys flags [:provider :model :leaf-provider :leaf-model
                      :child-provider :child-model :max-turns :max-fanout
                      :call-timeout-ms :runs-dir :fake-script]))

(defn- model-split [flags]
  {:root {:provider (or (:provider flags) "scripted") :model (or (:model flags) "scripted")}
   :leaf {:provider (or (:leaf-provider flags) (:provider flags) "scripted")
          :model (or (:leaf-model flags) (:model flags) "scripted")}
   :child {:provider (or (:child-provider flags) (:provider flags) "scripted")
           :model (or (:child-model flags) (:model flags) "scripted")}})

(defn- progress [r]
  (println (format "  [%s] %-16s %s  cost=%s  cum=%s  %sms"
                   (name (:mode r))
                   (str (:id r))
                   (if (= 1 (:correct? r)) "✓" "✗")
                   (if-let [c (:cost-usd r)] (format "$%.4f" (double c)) "—")
                   (if-let [c (:cumulative-usd r)] (format "$%.4f" (double c)) "—")
                   (:wall-ms r))))

(defn cmd-run [flags]
  (let [benchmark (:benchmark flags)
        data (:data flags)
        mode (keyword (or (:mode flags) "both"))
        budget (parse-double-opt (:budget-usd flags))
        out-dir (or (:out flags) (str "evals/results/" benchmark "-" (System/currentTimeMillis)))
        _ (when-not (and benchmark data)
            (throw (ex-info "missing --benchmark and/or --data" {})))
        examples (-> (dataset/load-examples data)
                     (dataset/limit-examples {:limit (parse-long-opt (:limit flags))}))
        cfg (runner/build-cfg (engine-opts flags))
        command (str "clojure -M:evals run " (str/join " " (map (fn [[k v]] (str "--" (name k) " " v)) flags)))
        base-batch {:cfg cfg :benchmark benchmark :on-result progress}
        ;; Which sub-modes run, in order, for this --mode. Multiple sub-modes share
        ;; ONE cumulative budget cap (threaded below).
        sub-modes (case mode
                    :flat          [[:flat   "— flat baseline —"]]
                    :engine        [[:engine "— engine (recursive) —"]]
                    :engine-norec  [[:engine-norec "— engine (no-recursion ablation) —"]]
                    :both          [[:flat   "— flat baseline —"]
                                    [:engine "— engine (recursive) —"]]
                    :all           [[:flat         "— flat baseline —"]
                                    [:engine-norec "— engine (no-recursion ablation) —"]
                                    [:engine       "— engine (recursive) —"]]
                    (throw (ex-info (str "unknown --mode " (name mode)
                                         " (expected flat | engine | engine-norec | both | all)")
                                    {:mode mode})))
        _ (println (format "Running %s on %d examples (mode=%s, budget=%s, shared across modes)"
                           benchmark (count examples) (name mode)
                           (if budget (format "$%.2f" budget) "none")))
        ;; Thread the SHARED budget: each subsequent mode runs against the SAME cap but
        ;; is seeded with what earlier modes already spent (:spent-so-far), so total
        ;; spend across all modes in this invocation respects one cap.
        runs (loop [todo sub-modes acc {} spent 0.0]
               (if (empty? todo)
                 acc
                 (let [[sub label] (first todo)
                       _ (println label
                                  (if budget (format "(budget left: $%.4f)" (max 0.0 (- budget spent))) ""))
                       run (runner/run-batch
                            (assoc base-batch :mode sub :budget-usd budget :spent-so-far spent)
                            examples)]
                   (recur (rest todo)
                          (assoc acc sub run)
                          (+ spent (or (:spent run) 0.0))))))
        combined-spent (reduce + 0.0 (map (comp #(or % 0.0) :spent val) runs))
        manifest (repro/manifest
                  {:now (time/now-str)
                   :command command
                   :benchmark benchmark
                   :split (:split flags)
                   :mode (name mode)
                   :models (model-split flags)
                   :dataset (dataset/fingerprint data examples)
                   :seed (parse-long-opt (:seed flags))
                   :limits {:limit (parse-long-opt (:limit flags))
                            :budget-usd budget
                            :max-turns (parse-long-opt (:max-turns flags))
                            :max-fanout (parse-long-opt (:max-fanout flags))
                            :call-timeout-ms (parse-long-opt (:call-timeout-ms flags))}
                   :notes (:notes flags)})
        rpt (-> (report/build-report manifest runs)
                (assoc :budget {:combined-spent-usd combined-spent
                                :budget-usd budget}))]
    (report/write! out-dir rpt)
    (println)
    (println (report/markdown-table (for [[label agg] (:summary rpt)]
                                      {:label (name label) :agg agg})))
    (println)
    (doseq [[label run] runs]
      (println (format "  %-14s spent $%.4f%s"
                       (name label) (or (:spent run) 0.0)
                       (if (:stopped-early? run) "  ⚠ stopped early (budget cap hit)" ""))))
    (println (format "  %-14s $%.4f%s"
                     "TOTAL" combined-spent
                     (if budget (format " / $%.2f cap" budget) "")))
    ;; Surface any dishonored ablation so a violated baseline is never silently clean.
    (let [violations (filter :ablation-violated? (mapcat :results (vals runs)))]
      (when (seq violations)
        (println)
        (println (format "⚠ ABLATION VIOLATED: %d engine-norec example(s) spawned children despite the no-recursion constraint:" (count violations)))
        (doseq [v violations]
          (println (format "    %s spawned %d child session(s) — %s"
                           (str (:id v)) (:children-spawned v) (:run-dir v))))))
    (println)
    (println "Wrote" (str out-dir "/results.{edn,json,md}"))))

(defn -main [& args]
  (let [[cmd & rest] args
        flags (parse-args rest)]
    (case cmd
      "run" (cmd-run flags)
      (do (println "fractal-eval — evaluation harness for fractal-engine\n")
          (println "  clojure -M:evals run --benchmark <oolong|fanoutqa> --data <file.jsonl>")
          (println "      [--mode engine|flat|both] [--limit N] [--budget-usd N] [--out DIR]")
          (println "      [--provider P --model M --leaf-provider P --leaf-model M --child-model M]")
          (println "      [--max-turns N --max-fanout N --call-timeout-ms N] [--split NAME] [--notes ...]")
          (println "\n  Offline smoke test (no keys, no spend):  clojure -M:evals-test")))))
