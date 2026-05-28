(ns fractal-engine.resume
  (:require [fractal-engine.session :as session]))

(def latest-complete-snapshot session/latest-complete-snapshot)

(defn resume!
  ([cfg dir user-message] (resume! cfg dir user-message {}))
  ([cfg dir user-message opts]
   (let [s (session/resume-session! cfg dir opts)]
     (session/run-turn! s user-message))))

(defn fork!
  ([cfg old-dir new-dir user-message]
   (fork! cfg old-dir new-dir user-message {}))
  ([cfg old-dir new-dir user-message opts]
   (let [s (session/fork-session! cfg old-dir new-dir)]
     (session/run-turn! s user-message))))
