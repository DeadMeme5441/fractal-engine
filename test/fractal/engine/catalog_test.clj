(ns fractal.engine.catalog-test
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.catalog :as cat]))

(deftest resolves-known-model
  (testing "a known model resolves provider + a positive context window (offline)"
    (let [{:keys [model provider]} (cat/provider-from-model-id "claude-opus-4-1")]
      (is (= "claude-opus-4-1" model))
      (is (= :anthropic provider))
      (is (pos-int? (cat/context-window "claude-opus-4-1"))))))

(deftest tolerates-unknown-model
  (testing "an unknown model yields nil provider + nil window without throwing"
    (is (nil? (:provider (cat/provider-from-model-id "totally-not-a-real-model-zzz"))))
    (is (nil? (cat/context-window "totally-not-a-real-model-zzz")))))
