(ns fractal-engine.resume
  (:require [fractal-engine.session :as session]))

(def latest-complete-snapshot session/latest-complete-snapshot)

(defn resume! [cfg dir user-message]
  (let [s (session/resume-session! cfg dir)]
    (session/run-turn! s user-message)))

(defn fork! [cfg old-dir new-dir user-message]
  (let [s (session/fork-session! cfg old-dir new-dir)]
    (session/run-turn! s user-message)))

