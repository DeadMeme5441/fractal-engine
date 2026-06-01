(ns fractal-engine.cliopts
  "Shared CLI option plumbing: turning `--flag value` maps into an engine config,
  resolving the default runs dir, and the always-visible spend line. Used by the
  `agentcli` command surface and the `codebrain` product surface. Deliberately
  dependency-light (no command/dispatch code, no `session`/`resume`/`inspect`) so it
  can sit underneath both without a cycle."
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.process :as process]
            [fractal-engine.scripts :as scripts]))

(defn parse-long-opt [v]
  (when (and v (not= true v))
    (Long/parseLong (str v))))

(defn default-runs-dir
  "Where runs live when neither `--runs-dir` nor an explicit dir is given. Mirrors
  how git and `bd` find their data dir: if a `.fractal/` already exists in the
  current directory or any ancestor, reuse it; otherwise default to `.fractal` in
  the current directory (created on first write). So `fractal` works wherever you
  invoke it, and from a subdirectory it still finds the project's runs. Override the
  location per-invocation with `--runs-dir DIR`."
  []
  (let [cwd (java.io.File. (System/getProperty "user.dir"))]
    (loop [d (.getCanonicalFile cwd)]
      (cond
        (nil? d)                                       (str (java.io.File. cwd ".fractal"))
        (.isDirectory (java.io.File. d ".fractal"))    (str (java.io.File. d ".fractal"))
        :else                                          (recur (.getParentFile d))))))

(defn cfg-from-opts [opts]
  (let [provider (keyword (or (:provider opts) "scripted"))
        model (or (:model opts) "scripted")
        leaf-provider (keyword (or (:leaf-provider opts) (name provider)))
        leaf-model (or (:leaf-model opts) model)
        child-provider (keyword (or (:child-provider opts) (name provider)))
        child-model (or (:child-model opts) model)
        script-name (:fake-script opts)
        response-fn (scripts/response-fn-for script-name)
        script (when (and script-name (nil? response-fn))
                 (atom (vec (scripts/script-for script-name))))]
    (process/config
     (cond-> {:runs-dir (or (:runs-dir opts) (default-runs-dir))
              :models {:root {:provider provider :model model}
                       :leaf {:provider leaf-provider :model leaf-model}
                       :child {:provider child-provider :model child-model}}}
       (:max-turns opts) (assoc :max-turns (parse-long-opt (:max-turns opts)))
       (:max-fanout opts) (assoc :max-fanout (parse-long-opt (:max-fanout opts)))
       (:max-leaf-concurrency opts) (assoc :max-leaf-concurrency (parse-long-opt (:max-leaf-concurrency opts)))
       (:call-timeout-ms opts) (assoc :call-timeout-ms (parse-long-opt (:call-timeout-ms opts)))
       response-fn (assoc :scripted/response-fn response-fn)
       script (assoc :scripted/responses script)))))

(defn session-start-opts [cfg opts]
  (if-let [sid (:session opts)]
    {:id sid
     :dir (artifacts/path (:runs-dir cfg) sid)}
    {}))

(defn usage-line
  "A compact, always-visible spend summary read from the materialized usage.edn —
  the answer to runaway worry is seeing the numbers, not capping them."
  [dir]
  (when dir
    (let [u (artifacts/read-edn-file (artifacts/path dir "usage.edn") nil)
          tree (:usage/total-tree u)
          cost (get-in u [:cost/total-tree :cost/usd])
          kn (fn [m] (if (= :known (:status m)) (:known m) "?"))]
      (when tree
        (format "Usage: %s calls  tokens in=%s out=%s total=%s  cost=%s"
                (str (:call/total-tree-count tree (:call/count tree)))
                (str (kn (:tokens/input tree)))
                (str (kn (:tokens/output tree)))
                (str (kn (:tokens/total tree)))
                (if (= :known (:status cost))
                  (format "$%.4f" (double (:known cost)))
                  (str "(" (name (get-in u [:cost/total-tree :cost/status] :unknown)) ")")))))))
