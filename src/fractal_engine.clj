(ns fractal-engine
  (:gen-class)
  (:require [fractal-engine.agentcli :as agentcli]
            [fractal-engine.cli :as cli]))

(defn -main
  "One agent use surface, beads-style: `fractal <verb> …` drives the engine
  (run/resume/fork) and reads it (show/tree/verify/cost/…) in one grammar, with
  --json and meaningful exit codes. Interactive `chat` and the legacy `inspect`
  stay in `cli`."
  [& args]
  (let [[cmd & _] args]
    (if (or (nil? cmd) (agentcli/handles? cmd))
      (apply agentcli/-main args)
      (apply cli/-main args))))
