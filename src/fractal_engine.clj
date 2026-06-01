(ns fractal-engine
  (:gen-class)
  (:require [fractal-engine.agentcli :as agentcli]))

(defn -main
  "One agent use surface, beads-style: `fractal <verb> …` drives the engine
  (run/resume/fork/chat), reads it (show/tree/verify/cost/inspect/…), and runs the
  codebrain product surface — all in one grammar, with --json and meaningful exit
  codes."
  [& args]
  (apply agentcli/-main args))
