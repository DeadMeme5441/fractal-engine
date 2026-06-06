(ns fractal-engine.custom-endpoint-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal-engine.cliopts :as cliopts]
            [fractal-engine.config :as config]
            [fractal-engine.provider :as provider])
  (:import [java.nio.file Files]
           [java.io ByteArrayInputStream InputStreamReader BufferedReader]
           [java.nio.charset StandardCharsets]))

(defn- tmp-dir [label]
  (str (Files/createTempDirectory
        (str "fractal-custom-endpoint-" label "-")
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- with-stdin [lines f]
  (let [text  (str (str/join "\n" lines) "\n")
        bytes (.getBytes text StandardCharsets/UTF_8)
        in    (BufferedReader. (InputStreamReader. (ByteArrayInputStream. bytes)))]
    (with-redefs [read-line #(.readLine in)]
      (f))))

(defn- write-edn! [path m]
  (let [f (java.io.File. path)]
    (.mkdirs (.getParentFile f))
    (spit f (pr-str m))))

;; ── provider/api-key-config ───────────────────────────────────────────────────

(deftest api-key-config-returns-base-url-for-custom-endpoint
  (with-redefs [provider/env-value (fn [k]
                                     (case k
                                       "CUSTOM_ENDPOINT_BASE_URL" "http://localhost:11434/v1"
                                       "CUSTOM_ENDPOINT_API_KEY"  nil
                                       nil))]
    (let [cfg (provider/api-key-config :custom-endpoint)]
      (is (= "http://localhost:11434/v1" (:base-url cfg)))
      (is (nil? (:api-key cfg))
          "api-key should be absent when the env var is not set"))))

(deftest api-key-config-includes-api-key-when-env-var-set
  (with-redefs [provider/env-value (fn [k]
                                     (case k
                                       "CUSTOM_ENDPOINT_BASE_URL" "http://my-server/v1"
                                       "CUSTOM_ENDPOINT_API_KEY"  "secret-token"
                                       nil))]
    (let [cfg (provider/api-key-config :custom-endpoint)]
      (is (= "http://my-server/v1" (:base-url cfg)))
      (is (= "secret-token" (:api-key cfg))))))

(deftest api-key-config-unaffected-for-other-providers
  ;; Existing providers must not be affected by the custom-endpoint changes.
  (with-redefs [provider/env-value (fn [k]
                                     (case k
                                       "ANTHROPIC_API_KEY" "ant-key"
                                       nil))]
    (is (= {:api-key "ant-key"} (provider/api-key-config :anthropic)))
    (is (nil? (provider/api-key-config :scripted)))))

;; ── provider/auth-status ──────────────────────────────────────────────────────

(deftest auth-status-satisfied-when-base-url-set
  (with-redefs [provider/env-value (fn [k]
                                     (when (= k "CUSTOM_ENDPOINT_BASE_URL")
                                       "http://localhost:11434/v1"))]
    (let [s (provider/auth-status :custom-endpoint)]
      (is (= :custom-endpoint (:auth s)))
      (is (true? (:satisfied? s))))))

(deftest auth-status-not-satisfied-when-base-url-missing
  (with-redefs [provider/env-value (constantly nil)]
    (let [s (provider/auth-status :custom-endpoint)]
      (is (false? (:satisfied? s))))))

(deftest auth-status-reports-env-var-names
  (with-redefs [provider/env-value (constantly nil)]
    (let [s (provider/auth-status :custom-endpoint)]
      (is (= "CUSTOM_ENDPOINT_BASE_URL" (:env-base-url s)))
      (is (= "CUSTOM_ENDPOINT_API_KEY" (:env-api-key s))))))

;; ── cliopts/custom-endpoint-config ───────────────────────────────────────────

(deftest custom-endpoint-config-reads-base-url-from-opts
  ;; When --base-url is passed as a flag it does not need the env var.
  (let [cfg (cliopts/custom-endpoint-config {:base-url "http://localhost:11434/v1"})]
    (is (= "http://localhost:11434/v1" (:base-url cfg)))))

(deftest custom-endpoint-config-empty-base-url-when-nothing-set
  ;; No flag, env var not set in the test environment — base-url is "".
  (when (nil? (System/getenv "CUSTOM_ENDPOINT_BASE_URL"))
    (let [cfg (cliopts/custom-endpoint-config {})]
      (is (= "" (:base-url cfg))))))

(deftest custom-endpoint-config-no-api-key-when-env-var-absent
  ;; CUSTOM_ENDPOINT_API_KEY is almost certainly not set in CI/dev.
  (when (nil? (System/getenv "CUSTOM_ENDPOINT_API_KEY"))
    (let [cfg (cliopts/custom-endpoint-config {:base-url "http://localhost/v1"})]
      (is (nil? (:api-key cfg))))))

;; ── cliopts/cfg-from-opts integration ────────────────────────────────────────

(deftest cfg-from-opts-injects-provider-config-for-custom-endpoint
  (let [root (tmp-dir "cfg-custom")
        cfg  (cliopts/cfg-from-opts {:runs-dir  root
                                     :provider  "custom-endpoint"
                                     :model     "llama3.2"
                                     :base-url  "http://localhost:11434/v1"})]
    (is (= :custom-endpoint (get-in cfg [:models :root :provider])))
    (is (= "llama3.2" (get-in cfg [:models :root :model])))
    (is (= "http://localhost:11434/v1" (get-in cfg [:provider/config :base-url])))))

(deftest cfg-from-opts-no-provider-config-for-standard-providers
  ;; :provider/config must not be injected for providers that don't need it.
  (let [root (tmp-dir "cfg-standard")]
    (doseq [prov ["anthropic" "openai" "scripted"]]
      (let [cfg (cliopts/cfg-from-opts {:runs-dir root :provider prov :model "some-model"})]
        (is (nil? (:provider/config cfg))
            (str ":provider/config should be absent for " prov))))))

;; ── config/run-setup! wizard ──────────────────────────────────────────────────

(deftest run-setup-saves-base-url-for-custom-endpoint
  (let [root (tmp-dir "wizard-custom")]
    ;; Pick option 7 (custom-endpoint), enter base URL, no api key, model, scope 1
    (with-stdin ["7" "http://localhost:11434/v1" "" "llama3.2" "1"]
      (fn []
        (let [result (config/run-setup! root)]
          (is (= "custom-endpoint" (:provider result)))
          (is (= "http://localhost:11434/v1" (:base-url result)))
          (is (= "llama3.2" (:model result)))
          (is (nil? (:api-key-env result)))
          (let [on-disk (edn/read-string (slurp (config/project-config-path root)))]
            (is (= "custom-endpoint" (:provider on-disk)))
            (is (= "http://localhost:11434/v1" (:base-url on-disk)))))))))

(deftest run-setup-saves-api-key-env-when-provided
  (let [root (tmp-dir "wizard-custom-key")]
    ;; Pick option 7, base URL, supply an api key env var name, model, scope 1
    (with-stdin ["7" "http://my-server/v1" "MY_SERVER_KEY" "my-model" "1"]
      (fn []
        (let [result (config/run-setup! root)]
          (is (= "MY_SERVER_KEY" (:api-key-env result)))
          (let [on-disk (edn/read-string (slurp (config/project-config-path root)))]
            (is (= "MY_SERVER_KEY" (:api-key-env on-disk)))))))))

(deftest run-setup-wizard-loops-until-base-url-given
  (let [root (tmp-dir "wizard-custom-loop")]
    ;; First base URL attempt is blank — wizard should re-prompt, then accept second
    (with-stdin ["7" "" "http://localhost:11434/v1" "" "llama3.2" "1"]
      (fn []
        (let [result (config/run-setup! root)]
          (is (= "http://localhost:11434/v1" (:base-url result))))))))
