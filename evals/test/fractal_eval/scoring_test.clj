(ns fractal-eval.scoring-test
  "Pure, deterministic scorer tests — no engine, no provider, no spend. These pin the
  exactness of the ground-truth scoring the whole harness rests on."
  (:require [clojure.test :refer [deftest is testing]]
            [fractal-eval.fanoutqa :as fanoutqa]
            [fractal-eval.oolong :as oolong]
            [fractal-eval.scoring :as score]))

(deftest extract-answer-shapes
  (is (= 3 (score/extract-answer {:answer 3 :method "x"})))
  (is (= 3 (score/extract-answer "{:answer 3}")))          ; EDN string from a model
  (is (= "Paris" (score/extract-answer {:answer "Paris"})))
  (is (= [1 2] (score/extract-answer {:answer [1 2]})))
  (is (= "just text" (score/extract-answer "just text"))))

(deftest parse-number-prose
  (is (= 12 (score/parse-number "there are 12 spam emails")))
  (is (= 3 (score/parse-number 3)))
  (is (nil? (score/parse-number "no digits here"))))

(deftest oolong-count-scoring
  (testing "exact count is correct"
    (let [r (oolong/score-one {:answer_type "count" :gold 2} {:answer 2})]
      (is (= 1 (:correct? r)))
      (is (= 1.0 (:numeric-accuracy r)))))
  (testing "wrong count is incorrect, with graded numeric-accuracy"
    (let [r (oolong/score-one {:answer_type "count" :gold 10} {:answer 8})]
      (is (= 0 (:correct? r)))
      (is (< 0.7 (:numeric-accuracy r) 0.9))))           ; 1 - 2/10 = 0.8
  (testing "count parsed out of prose answer"
    (is (= 1 (:correct? (oolong/score-one {:answer_type "count" :gold 2}
                                          "I count 2 spam messages"))))))

(deftest oolong-label-scoring
  (is (= 1 (:correct? (oolong/score-one {:answer_type "label" :gold "active"} {:answer "active"}))))
  (is (= 1 (:correct? (oolong/score-one {:answer_type "label" :gold "active"} {:answer :active}))))
  (is (= 0 (:correct? (oolong/score-one {:answer_type "label" :gold "active"} {:answer "suspended"})))))

(deftest oolong-label-answer-format
  (testing "OOLONG asks for 'Label: X' — the lead-in must not break the match"
    (is (= 1 (:correct? (oolong/score-one {:answer_type "label" :gold "negative"}
                                          {:answer "Label: negative"}))))
    (is (= 1 (:correct? (oolong/score-one {:answer_type "label" :gold "negative"}
                                          "Label: negative"))))
    (is (= 0 (:correct? (oolong/score-one {:answer_type "label" :gold "negative"}
                                          {:answer "Label: positive"}))))))

(deftest oolong-tie-gold
  (testing "a tie gold (list) matches ANY of the listed labels"
    (is (= 1 (:correct? (oolong/score-one {:answer_type "label" :gold ["positive" "negative"]}
                                          {:answer "Label: positive"}))))
    (is (= 1 (:correct? (oolong/score-one {:answer_type "label" :gold ["positive" "negative"]}
                                          {:answer "negative"}))))
    (is (= 0 (:correct? (oolong/score-one {:answer_type "label" :gold ["positive" "negative"]}
                                          {:answer "neutral"})))))
  (testing "count gold may also be a tie list"
    (is (= 1 (:correct? (oolong/score-one {:answer_type "count" :gold [5 6]} {:answer 6}))))))

(deftest oolong-count-answer-format
  (testing "a 'User: N' / 'Answer: N' lead-in still parses the number"
    (is (= 1 (:correct? (oolong/score-one {:answer_type "count" :gold 84614}
                                          {:answer "User: 84614"}))))))

(deftest fanoutqa-loose-strict
  (testing "all gold strings present -> loose 1.0 and strict-correct"
    (let [r (fanoutqa/score-one {:gold ["Paris" "Tokyo" "Cairo"]}
                                {:answer ["Cairo" "Paris" "Tokyo"]})]
      (is (= 1.0 (:loose-accuracy r)))
      (is (= 1 (:correct? r)))))
  (testing "partial -> loose is the recall fraction, strict fails, missed listed"
    (let [r (fanoutqa/score-one {:gold ["Apple" "Microsoft"]} {:answer ["Apple"]})]
      (is (= 0.5 (:loose-accuracy r)))
      (is (= 0 (:correct? r)))
      (is (= ["Microsoft"] (:missed r)))))
  (testing "answer as ONE free-text blob with several values (old set-EM failed this)"
    (let [r (fanoutqa/score-one {:gold ["Paris" "Tokyo"]}
                                {:answer "The capitals are Paris and Tokyo."})]
      (is (= 1.0 (:loose-accuracy r)))
      (is (= 1 (:correct? r)))))
  (testing "SQuAD normalization: articles/case/punctuation don't block a match"
    (is (= 1.0 (:loose-accuracy (fanoutqa/score-one {:gold ["Paris"]}
                                                    {:answer "the capital is Paris!"})))))
  (testing "repeated gold values are all credited via containment (no set-dedup loss)"
    (let [r (fanoutqa/score-one {:gold ["Various" "Various" "Various"]} {:answer "Various"})]
      (is (= 1.0 (:loose-accuracy r)))
      (is (= 1 (:correct? r))))))
