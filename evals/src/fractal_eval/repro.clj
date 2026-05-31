(ns fractal-eval.repro
  "Reproducibility manifest. Every results file carries enough to re-run it and to
  know what produced it: the engine's git commit, the behavior-prompt version, the
  exact model split, the dataset fingerprint, the seed, the command line, and a
  timestamp. Numbers without this provenance are unreproducible folklore; with it
  they are a claim someone else can check."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [fractal-engine.prompt :as prompt]))

(defn- git [& args]
  (try
    (let [{:keys [exit out]} (apply shell/sh "git" args)]
      (when (zero? exit) (str/trim out)))
    (catch Throwable _ nil)))

(defn git-sha []
  (or (git "rev-parse" "HEAD") "unknown"))

(defn git-dirty? []
  (boolean (some-> (git "status" "--porcelain") (str/blank?) (not))))

(defn manifest
  "Build the provenance block for a results file. `opts` carries the run-time choices
  the harness already knows (models, benchmark, dataset fingerprint, seed, limits,
  command). `now` is injected (not read from the clock here) so callers control it."
  [{:keys [now command benchmark split mode models dataset seed limits notes]}]
  {:repro/version 1
   :repro/created-at now
   :repro/command command
   :repro/benchmark benchmark
   :repro/split split
   :repro/mode mode
   :engine/git-sha (git-sha)
   :engine/git-dirty? (git-dirty?)
   :engine/prompt-name (name prompt/prompt-name)
   :engine/prompt-version prompt/prompt-version
   :engine/prompt-hash (:prompt/hash prompt/prompt-metadata)
   :models models
   :dataset dataset
   :seed seed
   :limits limits
   :notes notes})
