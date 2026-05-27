(ns fractal-engine.time
  (:import [java.time Instant]))

(defn now-str []
  (str (Instant/now)))

