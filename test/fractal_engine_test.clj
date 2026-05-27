(ns fractal-engine-test
  (:require [clojure.test :refer [deftest is]]
            [fractal-engine :as fractal-engine]))

(deftest main-namespace-loads
  (is (fn? fractal-engine/-main)))
