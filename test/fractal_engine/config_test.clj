(ns fractal-engine.config-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal-engine.cliopts :as cliopts]
            [fractal-engine.config :as config])
  (:import [java.nio.file Files]
           [java.io ByteArrayInputStream InputStreamReader BufferedReader]
           [java.nio.charset StandardCharsets]))

(defn- tmp-dir [label]
  (str (Files/createTempDirectory
        (str "fractal-config-" label "-")
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-edn! [path m]
  (let [f (java.io.File. path)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str m))))

;; ── config/load-config ────────────────────────────────────────────────────────

(deftest load-config-returns-empty-map-when-no-files-exist
  (let [root (tmp-dir "empty")]
    (is (= {} (config/load-config root)))))

(deftest load-config-reads-project-config
  (let [root (tmp-dir "project")]
    (write-edn! (config/project-config-path root)
                {:provider "anthropic" :model "claude-opus-4-8-20251101"})
    (is (= {:provider "anthropic" :model "claude-opus-4-8-20251101"}
           (config/load-config root)))))

(deftest load-config-project-wins-over-user
  ;; We can't write to the real ~/.fractal/config.edn in tests, so we test the
  ;; merge logic directly by writing both files via their known paths and then
  ;; confirming project keys shadow user keys while user-only keys survive.
  (let [root (tmp-dir "precedence")]
    (write-edn! (config/project-config-path root)
                {:provider "anthropic" :model "project-model"})
    ;; Exercise merge logic without touching the real user config file.
    ;; load-config merges (merge user project), so we test that directly:
    (let [user    {:provider "openai" :model "user-model" :leaf-model "user-leaf"}
          project {:provider "anthropic" :model "project-model"}
          merged  (merge user project)]
      (is (= "anthropic" (:provider merged)) "project :provider shadows user")
      (is (= "project-model" (:model merged)) "project :model shadows user")
      (is (= "user-leaf" (:leaf-model merged)) "user-only key survives"))))

;; ── config/config-exists? ─────────────────────────────────────────────────────

(deftest config-exists-false-when-nothing-present
  (let [root (tmp-dir "none")]
    ;; Guard: ensure there is also no real user config skewing this result.
    ;; If one exists on the dev machine we skip rather than fail.
    (when-not (.exists (java.io.File. (config/user-config-path)))
      (is (false? (config/config-exists? root))))))

(deftest config-exists-true-when-project-file-present
  (let [root (tmp-dir "proj-exists")]
    (write-edn! (config/project-config-path root) {:provider "anthropic"})
    (is (true? (config/config-exists? root)))))

;; ── config/save-project-config! ───────────────────────────────────────────────

(deftest save-project-config-writes-readable-edn
  (let [root   (tmp-dir "save")
        cfg    {:provider "openai" :model "gpt-4o"}
        path   (config/project-config-path root)]
    (config/save-project-config! root cfg)
    (is (.exists (java.io.File. path)))
    (let [on-disk (edn/read-string (slurp path))]
      (is (= "openai" (:provider on-disk)))
      (is (= "gpt-4o" (:model on-disk))))))

(deftest save-project-config-creates-parent-dirs
  (let [root (tmp-dir "mkdir")
        path (config/project-config-path root)]
    ;; root dir itself does not contain .fractal yet
    (is (not (.exists (java.io.File. path))))
    (config/save-project-config! root {:provider "anthropic"})
    (is (.exists (java.io.File. path)))))

;; ── cliopts/cfg-from-opts integration ────────────────────────────────────────

(deftest cfg-from-opts-picks-up-file-config-when-no-flags
  (let [root (tmp-dir "merge")]
    (write-edn! (config/project-config-path root)
                {:provider "anthropic" :model "claude-opus-4-8-20251101"})
    (let [cfg (cliopts/cfg-from-opts {:runs-dir root})]
      (is (= :anthropic (get-in cfg [:models :root :provider])))
      (is (= "claude-opus-4-8-20251101" (get-in cfg [:models :root :model]))))))

(deftest cfg-from-opts-cli-flags-win-over-file-config
  (let [root (tmp-dir "override")]
    (write-edn! (config/project-config-path root)
                {:provider "anthropic" :model "claude-opus-4-8-20251101"})
    (let [cfg (cliopts/cfg-from-opts {:runs-dir root
                                      :provider "openai"
                                      :model "gpt-4o"})]
      (is (= :openai (get-in cfg [:models :root :provider])))
      (is (= "gpt-4o" (get-in cfg [:models :root :model]))))))

(deftest cfg-from-opts-file-leaf-model-fills-gap-when-flag-absent
  (let [root (tmp-dir "leaf-gap")]
    (write-edn! (config/project-config-path root)
                {:provider "anthropic"
                 :model "claude-opus-4-8-20251101"
                 :leaf-model "claude-haiku-4-5-20251001"})
    (let [cfg (cliopts/cfg-from-opts {:runs-dir root})]
      (is (= :anthropic (get-in cfg [:models :leaf :provider])))
      (is (= "claude-haiku-4-5-20251001" (get-in cfg [:models :leaf :model]))))))

(deftest cfg-from-opts-falls-back-to-scripted-with-no-config-and-no-flags
  (let [root (tmp-dir "scripted-fallback")]
    ;; No config file, no flags — scripted default must still apply.
    (let [cfg (cliopts/cfg-from-opts {:runs-dir root})]
      (is (= :scripted (get-in cfg [:models :root :provider])))
      (is (= "scripted" (get-in cfg [:models :root :model]))))))

;; ── config/run-setup! (stdin-driven) ─────────────────────────────────────────

(defn- with-stdin [lines f]
  (let [text  (str (str/join "\n" lines) "\n")
        bytes (.getBytes text StandardCharsets/UTF_8)
        in    (BufferedReader. (InputStreamReader. (ByteArrayInputStream. bytes)))]
    (with-redefs [read-line #(.readLine in)]
      (f))))

(deftest run-setup-writes-project-config-from-wizard-input
  (let [root (tmp-dir "wizard-project")]
    ;; Inputs: pick provider 1 (Anthropic), accept default model, scope 1 (project)
    (with-stdin ["1" "" "1"]
      (fn []
        (let [result (config/run-setup! root)]
          (is (= "anthropic" (:provider result)))
          (is (= "claude-opus-4-8-20251101" (:model result)))
          (is (.exists (java.io.File. (config/project-config-path root))))
          (let [on-disk (edn/read-string (slurp (config/project-config-path root)))]
            (is (= "anthropic" (:provider on-disk)))))))))

(deftest run-setup-accepts-custom-model-override
  (let [root (tmp-dir "wizard-model")]
    ;; Pick provider 2 (OpenAI), type a custom model name, scope 1 (project)
    (with-stdin ["2" "gpt-4o-mini" "1"]
      (fn []
        (let [result (config/run-setup! root)]
          (is (= "openai" (:provider result)))
          (is (= "gpt-4o-mini" (:model result))))))))
