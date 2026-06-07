(ns fractal.engine.live-smoke-test
  "OPTIONAL live smoke check (10 §4) — exercises the real SdkAdapter end-to-end
   against DeepSeek. ^:live, so it is EXCLUDED from `clojure -M:test` and only
   runs via `clojure -M:live-test` with DEEPSEEK_API_KEY in the environment.
   Never on the default/CI path; makes a paid API call."
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]))

(def ^:private model "deepseek-chat")

(deftest ^:live sdk-adapter-drives-a-real-repl-to-final
  (testing "a live model writes Clojure, the host evals it, and it returns via FINAL"
    (let [cfg (fe/make-config {:adapter :sdk
                               :model   model
                               :capability :default
                               :max-steps 8})
          s   (fe/start-session! cfg)
          res (fe/run-turn! s
                (str "Using the Clojure REPL, compute the number of integers in [1,100] "
                     "that are divisible by 3 or 5. Return exactly {:count <n>} via (FINAL …)."))]
      (println "live deepseek result:" (pr-str (select-keys res [:status :turn/final-value :step-count :turn/cost])))
      (is (= :final (:status res)) (str "error: " (:error res)))
      (is (= 47 (:count (:turn/final-value res))) "47 integers in [1,100] divisible by 3 or 5")
      (is (= :deepseek (get-in (fe/view s) [:session :session/provider])))
      (testing "honest cost accounting came back from the provider"
        (is (#{:known :unknown} (:cost/status (:turn/cost res)))))
      (fe/stop-session! s))))
