(ns fractal-engine.config
  "Project- and user-level config files.

  Resolution order (first wins):
    1. CLI flags             — handled in cliopts, not here
    2. Project config        — <store-root>/config.edn  (inside .fractal/)
    3. User config           — ~/.fractal/config.edn

  Config keys mirror CLI flag names so cliopts can merge them as a base layer:
    :provider       string — default provider id
    :model          string — default root model
    :leaf-provider  string — overrides :provider for leaf calls
    :leaf-model     string — overrides :model for leaf calls
    :child-provider string
    :child-model    string

  Minimal example:
    {:provider  \"anthropic\"
     :model     \"claude-opus-4-8-20251101\"
     :leaf-model \"claude-haiku-4-5-20251001\"}"
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]))

;; ── file paths ────────────────────────────────────────────────────────────────

(defn user-config-path []
  (str (System/getProperty "user.home") "/.fractal/config.edn"))

(defn project-config-path [store-root]
  (str store-root "/config.edn"))

;; ── read ──────────────────────────────────────────────────────────────────────

(defn- read-file [path]
  (let [f (java.io.File. path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn load-config
  "Merged config from project + user layers. Project wins over user.
  Returns {} when neither file exists."
  [store-root]
  (let [user    (or (read-file (user-config-path)) {})
        project (or (read-file (project-config-path store-root)) {})]
    (merge user project)))

(defn config-exists?
  "True when at least one config file (project or user) is present."
  [store-root]
  (or (.exists (java.io.File. (project-config-path store-root)))
      (.exists (java.io.File. (user-config-path)))))

;; ── write ─────────────────────────────────────────────────────────────────────

(defn- ensure-parent! [path]
  (.mkdirs (.getParentFile (java.io.File. path))))

(defn- write-config! [path cfg-map]
  (ensure-parent! path)
  (spit path (str ";; fractal-engine config — keys mirror CLI flags\n"
                  (with-out-str (pp/pprint cfg-map)))))

(defn save-project-config! [store-root cfg-map]
  (write-config! (project-config-path store-root) cfg-map))

(defn save-user-config! [cfg-map]
  (write-config! (user-config-path) cfg-map))

;; ── interactive first-run setup ───────────────────────────────────────────────

;; [display-label provider-id suggested-model]
(def ^:private provider-menu
  [["Anthropic"                   "anthropic"      "claude-opus-4-8-20251101"]
   ["OpenAI"                      "openai"         "gpt-4o"]
   ["OpenRouter"                  "openrouter"     "openai/gpt-4o"]
   ["DeepSeek"                    "deepseek"       "deepseek-chat"]
   ["Vertex Gemini"               "vertex-gemini"  "gemini-2.5-pro"]
   ["Cohere"                      "cohere"         "command-r-plus"]
   ["Scripted (offline/testing)"  "scripted"       "scripted"]])

(defn- ask [prompt]
  (print prompt) (flush) (or (read-line) ""))

(defn- pick-provider []
  (println "\n  No provider configured. Pick one to use as the default:\n")
  (dorun (map-indexed (fn [i [label _ _]]
                        (println (format "    %d) %s" (inc i) label)))
                      provider-menu))
  (println)
  (loop []
    (let [raw (ask "  Choice [1]: ")
          n   (if (str/blank? raw) 1 (parse-long raw))]
      (if (and n (>= n 1) (<= n (count provider-menu)))
        (nth provider-menu (dec n))
        (do (println (str "  Please enter a number between 1 and " (count provider-menu)))
            (recur))))))

(defn- pick-model [suggested]
  (let [raw (ask (format "  Model [%s]: " suggested))]
    (if (str/blank? raw) suggested (str/trim raw))))

(defn- pick-scope []
  (println "\n  Save to:\n")
  (println "    1) This project  (.fractal/config.edn)  — just this directory tree")
  (println "    2) User default  (~/.fractal/config.edn) — applies to all projects\n")
  (loop []
    (let [raw (ask "  Scope [1]: ")
          n   (if (str/blank? raw) 1 (parse-long raw))]
      (if (#{1 2} n) n
          (do (println "  Enter 1 or 2") (recur))))))

(defn run-setup!
  "Interactive first-run setup wizard. Writes a config file and returns
  the chosen config map so the current run proceeds without a restart."
  [store-root]
  (println "\n┌─ fractal-engine: first-run setup ──────────────────────────────────────┐")
  (let [[_label prov suggested] (pick-provider)
        model  (pick-model suggested)
        scope  (pick-scope)
        cfg    {:provider prov :model model}]
    (if (= 2 scope)
      (do (save-user-config! cfg)
          (println (format "\n  Saved → %s" (user-config-path))))
      (do (save-project-config! store-root cfg)
          (println (format "\n  Saved → %s" (project-config-path store-root)))))
    (println "└────────────────────────────────────────────────────────────────────────┘\n")
    cfg))
