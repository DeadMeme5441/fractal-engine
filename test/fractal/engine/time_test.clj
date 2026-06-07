(ns fractal.engine.time-test
  (:require [clojure.test :refer [deftest is]]
            [fractal.engine.time :as t])
  (:import [java.time Instant]))

(deftest now-str-is-iso-8601
  (let [s (t/now-str)]
    (is (string? s))
    ;; round-trips through java.time.Instant ⇒ valid ISO-8601 UTC
    (is (instance? Instant (Instant/parse s)))))
