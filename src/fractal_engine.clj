(ns fractal-engine
  (:gen-class)
  (:require [fractal-engine.cli :as cli]))

(defn -main
  [& args]
  (apply cli/-main args))
