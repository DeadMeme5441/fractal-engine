(ns fractal-eval.harness-smoke-test
  "End-to-end harness plumbing through the offline scripted provider: load fixtures →
  format the engine task / flat prompt → drive the engine and the flat baseline →
  score deterministically → aggregate → write reports → enforce the budget cap. No
  API keys, no network, no spend. This is what proves the wiring before a live run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal-eval.dataset :as dataset]
            [fractal-eval.report :as report]
            [fractal-eval.runner :as runner])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- tmp-dir [] (str (Files/createTempDirectory "fractal-eval-test" (make-array FileAttribute 0))))

(def oolong-fixture "evals/resources/fixtures/oolong-sample.jsonl")

(defn- scripted-answer-fn
  "A content-sensitive fake model: returns the right answer for each fixture question,
  shaped for whichever caller asked (engine task wants a (FINAL ...) block; the flat
  baseline wants bare EDN)."
  [request]
  (let [content (str (:message/content (last (:request/messages request))))
        flat? (str/includes? content "Reply with EDN")
        ans (cond
              (str/includes? content "How many of these emails are spam") "{:answer 2}"
              (str/includes? content "Which account status is most common") "{:answer \"active\"}"
              :else "{:answer :unknown}")]
    (if flat?
      ans
      (str "```clojure\n(FINAL " ans ")\n```"))))

(deftest engine-mode-scores-correctly
  (let [cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn scripted-answer-fn)
        examples (dataset/load-examples oolong-fixture)
        {:keys [results stopped-early?]}
        (runner/run-batch {:cfg cfg :benchmark "oolong" :mode :engine} examples)]
    (is (= 2 (count results)))
    (is (not stopped-early?))
    (is (every? #(= :final (:status %)) results))
    (is (every? #(= 1 (:correct? %)) results) "scripted answers match the gold")
    (is (= 1.0 (:accuracy (report/aggregate results))))))

(deftest flat-mode-scores-correctly
  (let [cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn scripted-answer-fn)
        examples (dataset/load-examples oolong-fixture)
        {:keys [results]}
        (runner/run-batch {:cfg cfg :benchmark "oolong" :mode :flat} examples)]
    (is (= 2 (count results)))
    (is (every? #(= 1 (:correct? %)) results))))

(deftest budget-cap-stops-early
  (testing "the harness halts before an example once cumulative cost crosses the cap"
    (let [cost-fn (fn [request]
                    {:response/parts [{:part/type :text :text "{:answer 2}"}]
                     :response/cost {:cost/usd 10.0}})
          cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn cost-fn)
          examples (dataset/load-examples oolong-fixture)
          {:keys [results spent stopped-early?]}
          (runner/run-batch {:cfg cfg :benchmark "oolong" :mode :flat :budget-usd 5.0} examples)]
      (is (= 1 (count results)) "second example is refused: $10 spent >= $5 cap")
      (is stopped-early?)
      (is (= 10.0 spent)))))

(deftest report-files-written
  (let [cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn scripted-answer-fn)
        examples (dataset/load-examples oolong-fixture)
        runs {:engine (runner/run-batch {:cfg cfg :benchmark "oolong" :mode :engine} examples)}
        manifest {:repro/benchmark "oolong" :repro/split "fixture"
                  :engine/git-sha "test" :engine/prompt-version 0
                  :repro/command "test"}
        rpt (report/build-report manifest runs)
        out (tmp-dir)]
    (report/write! out rpt)
    (is (.exists (io/file out "results.edn")))
    (is (.exists (io/file out "results.json")))
    (is (.exists (io/file out "results.md")))
    (is (= 1.0 (get-in rpt [:summary :engine :accuracy])))))

;; ── no-recursion ablation (:engine-norec) ────────────────────────────────────

(deftest engine-norec-honors-ablation
  (testing "engine-norec scores correctly and audits zero children when none spawned"
    (let [cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn scripted-answer-fn)
          examples (dataset/load-examples oolong-fixture)
          {:keys [results]}
          (runner/run-batch {:cfg cfg :benchmark "oolong" :mode :engine-norec} examples)]
      (is (= 2 (count results)))
      (is (every? #(= :engine-norec (:mode %)) results))
      (is (every? #(= 1 (:correct? %)) results) "scripted answers match the gold")
      (is (every? #(= 0 (:children-spawned %)) results) "scripted run spawns no children")
      (is (every? #(true? (:ablation-honored? %)) results))
      (is (every? #(false? (:ablation-violated? %)) results)))))

(deftest engine-norec-task-carries-the-banner
  (testing "the ablation banner is prefixed onto the engine task"
    (let [seen (atom nil)
          ;; capture the task the engine is asked to run, then answer it cleanly
          capture-fn (fn [request]
                       (let [content (str (:message/content (last (:request/messages request))))]
                         (reset! seen content)
                         "```clojure\n(FINAL {:answer 2})\n```"))
          cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn capture-fn)
          examples (take 1 (dataset/load-examples oolong-fixture))
          _ (runner/run-batch {:cfg cfg :benchmark "oolong" :mode :engine-norec} examples)]
      (is (str/includes? @seen "ABLATION CONSTRAINT (no recursion)"))
      (is (str/includes? @seen "MUST NOT call rlm, map-rlm, or attach-rlm")))))

(deftest mode-all-produces-three-labeled-groups
  (testing "--mode all runs flat + engine-norec + engine over the same examples"
    (let [cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn scripted-answer-fn)
          examples (dataset/load-examples oolong-fixture)
          base {:cfg cfg :benchmark "oolong"}
          runs (into {} (for [m [:flat :engine-norec :engine]]
                          [m (runner/run-batch (assoc base :mode m) examples)]))]
      (is (= #{:flat :engine-norec :engine} (set (keys runs))))
      (doseq [[m run] runs]
        (is (= 2 (count (:results run))) (str (name m) " ran both examples"))
        (is (every? #(= 1 (:correct? %)) (:results run))
            (str (name m) " scored both correct"))))))

(deftest shared-budget-across-modes-respects-one-cap
  (testing "two modes share ONE cumulative cap, seeded by :spent-so-far"
    (let [;; every call costs $4; cap is $6. Mode 1 (flat, 2 examples) spends $4 on
          ;; example 1, runs example 2 too (4 < 6), reaching $8. Mode 2 (engine-norec)
          ;; is seeded with that $8 and refused before its FIRST example (8 >= 6).
          cost-fn (fn [_request]
                    {:response/parts [{:part/type :text :text "{:answer 2}"}]
                     :response/cost {:cost/usd 4.0}})
          cfg (runner/build-cfg {:runs-dir (tmp-dir)} :response-fn cost-fn)
          examples (dataset/load-examples oolong-fixture)
          budget 6.0
          ;; replicate cmd-run's shared-budget threading: same cap, seed with prior spend.
          runs (loop [todo [:flat :engine-norec] acc {} spent 0.0]
                 (if (empty? todo)
                   acc
                   (let [m (first todo)
                         run (runner/run-batch
                              {:cfg cfg :benchmark "oolong" :mode m
                               :budget-usd budget :spent-so-far spent}
                              examples)]
                     (recur (rest todo) (assoc acc m run) (+ spent (:spent run))))))
          combined (reduce + 0.0 (map (comp :spent val) runs))]
      ;; flat runs both examples (each $4 < running cap during its batch)
      (is (= 2 (count (:results (:flat runs)))))
      (is (= 8.0 (:spent (:flat runs))))
      ;; the SHARED cap now stops the second mode entirely (seeded with $8 >= $6 cap)
      (is (= 0 (count (:results (:engine-norec runs))))
          "second mode is starved: combined spend already at/over cap")
      (is (true? (:stopped-early? (:engine-norec runs))))
      (is (= 0.0 (:spent (:engine-norec runs))))
      ;; combined spend = only what the modes actually ran (no double-count of the seed)
      (is (= 8.0 combined)))))
