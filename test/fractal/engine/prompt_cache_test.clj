(ns fractal.engine.prompt-cache-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fractal.engine.prompt :as prompt]
            [fractal.engine.payload :as payload]
            [fractal.engine.cache :as cache]))

(deftest prompt-stamped-and-reproducible
  (testing "the Phase-1 prompt carries name/version/hash matching its text"
    (is (= :fractal-engine/repl-p1 (:prompt/name prompt/repl-p1)))
    (is (= 1 (:prompt/version prompt/repl-p1)))
    (is (= (payload/content-id (:prompt/text prompt/repl-p1)) (:prompt/hash prompt/repl-p1))))
  (testing "the doctrine names FINAL + inspect and forbids tool-calls"
    (let [t (prompt/system-prompt)]
      (is (str/includes? t "FINAL"))
      (is (str/includes? t "inspect"))
      (is (str/includes? t "fenced"))))
  (testing "the compaction prompt forbids code fences"
    (is (str/includes? (prompt/compaction-prompt) "Do not include code fences"))
    (is (= (payload/content-id (:prompt/text prompt/compaction)) (:prompt/hash prompt/compaction)))))

(deftest scope-id-deterministic
  (testing "stable digest with the fr:<purpose>: prefix"
    (let [a (cache/scope-id "sess-1" :agent)
          b (cache/scope-id "sess-1" :agent)]
      (is (= a b))
      (is (str/starts-with? a "fr:agent:"))
      (is (= 32 (count (subs a (count "fr:agent:")))))))
  (testing "different cache-ids ⇒ different scope-ids"
    (is (not= (cache/scope-id "sess-1" :agent) (cache/scope-id "sess-2" :agent)))))

(deftest cache-id-defaults-to-session-id
  (is (= "s-1" (cache/cache-id {:session/id "s-1"})))
  (is (= "logical" (cache/cache-id {:session/id "s-1" :session/cache-id "logical"}))))

(deftest build-cache-opts-shape
  (let [view {:session {:session/id "s-1"}}
        opts (cache/build-cache-opts view {:cache-ttl "1h"})]
    (is (= true (:enabled? opts)))
    (is (= "1h" (:ttl opts)))
    (is (str/starts-with? (:scope-id opts) "fr:agent:"))))
