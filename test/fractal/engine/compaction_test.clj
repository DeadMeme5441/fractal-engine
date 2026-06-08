(ns fractal.engine.compaction-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fractal.engine.compaction :as compaction]))

(defn- req [chars] {:messages [{:role :user :content (apply str (repeat chars "x"))}]})

(deftest assess-known-window
  (let [cfg {:context-window 1000 :context {:compact-at 0.80 :hard-at 0.95 :unknown-window-chars 400000}}]
    (testing "ceil(chars/4) tokens vs the window"
      ;; 1000 chars → 250 tokens; window 1000 → ratio 0.25 → neither
      (let [a (compaction/assess (req 1000) cfg)]
        (is (= 250 (:tokens a)))
        (is (not (:compact? a)))
        (is (not (:hard? a))))
      ;; 3400 chars → 850 tokens → over compact (800) under hard (950)
      (let [a (compaction/assess (req 3400) cfg)]
        (is (:compact? a))
        (is (not (:hard? a))))
      ;; 4000 chars → 1000 tokens → over hard (950)
      (is (:hard? (compaction/assess (req 4000) cfg))))))

(deftest assess-unknown-window-falls-back-to-char-cap
  (let [cfg {:context-window :unknown
             :context {:compact-at 0.80 :hard-at 0.95 :unknown-window-chars 1000}}]
    (is (not (:compact? (compaction/assess (req 500) cfg))))
    (is (:compact? (compaction/assess (req 850) cfg)))   ; > 800 chars
    (is (:hard? (compaction/assess (req 970) cfg)))))    ; > 950 chars

(deftest should-compact-over-a-view
  (let [cfg {:context-window :unknown
             :context {:compact-at 0.50 :hard-at 0.95 :unknown-window-chars 100}}
        big (apply str (repeat 80 "y"))
        view {:compact-from-event-id nil
              :events [{:event/id 1 :event/type :message/appended
                        :message {:message/role :user :message/content-or-ref big}}]}]
    (is (compaction/should-compact? view cfg))
    (is (not (compaction/should-compact?
               {:compact-from-event-id nil
                :events [{:event/id 1 :event/type :message/appended
                          :message {:message/role :user :message/content-or-ref "tiny"}}]}
               cfg)))))

(deftest format-transcript-is-role-labeled
  (let [t (compaction/format-transcript
            [{:message/role :user :message/content "do it"}
             {:message/role :assistant :message/content "(code)"}
             {:message/role :observation :message/content "=> 42"}])]
    (is (str/includes? t "[user] do it"))
    (is (str/includes? t "[assistant] (code)"))
    (is (str/includes? t "[observation] => 42"))))
