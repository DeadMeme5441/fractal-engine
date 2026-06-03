(ns fractal-engine.resume
  (:require [fractal-engine.session :as session]))

(def latest-complete-snapshot session/latest-complete-snapshot)

(defn resume!
  ([cfg source-handle user-message] (resume! cfg source-handle user-message {}))
  ([cfg source-handle user-message opts]
   (let [s (session/resume-session! cfg source-handle opts)]
     (session/run-turn! s user-message))))

(defn fork!
  ([cfg source-handle target-store-root user-message]
   (fork! cfg source-handle target-store-root user-message {}))
  ([cfg source-handle target-store-root user-message opts]
   (let [s (session/fork-session! cfg source-handle target-store-root opts)]
     (session/run-turn! s user-message))))
