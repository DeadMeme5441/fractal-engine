(ns fractal.engine.live-surface-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [fractal.engine.api :as fe]
            [fractal.engine.capability :as cap]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-live-surface" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- sh! [& args]
  (let [{:keys [exit out err] :as result} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (ex-info (str "command failed: " (str/join " " args))
                      {:error/type :test/command-failed
                       :args args :result result :stderr err})))
    out))

(defn- write-file! [root rel text]
  (let [f (io/file root rel)]
    (.mkdirs (.getParentFile f))
    (spit f text)
    f))

(defn- init-git-world! []
  (let [root (temp-dir!)]
    (write-file! root "README.md" "# Demo\n\nThis repo demonstrates SDK surface injection.\n")
    (write-file! root "src/feature.clj" "(ns demo.feature)\n\n(defn feature [] :surface-ready)\n")
    (sh! "git" "-C" (.getPath root) "init" "-q")
    (sh! "git" "-C" (.getPath root) "add" ".")
    root))

(defn- safe-file [root rel]
  (let [base (.getCanonicalFile (io/file root))
        f (.getCanonicalFile (io/file root rel))]
    (when-not (or (= base f)
                  (str/starts-with? (.getPath f)
                                    (str (.getPath base) java.io.File/separator)))
      (throw (ex-info "path escapes git live world"
                      {:error/type :test/path-escape :path rel})))
    f))

(defn- git-surface [root]
  {:surface/id :git
   :surface/version 1
   :surface/prompt "Use git/tracked-files to enumerate files and git/read-file to hydrate bounded content."
   :surface/prompts
   {:request "For this smoke, prefer git/tracked-files and git/read-file over shell or filesystem reads."
    :leaf "When classifying git file snippets, return only the requested compact EDN."}
   :surface/namespaces
   {'git {'tracked-files {:doc "Return tracked repository files."
                          :arglists '([])
                          :fn (fn []
                                (->> (str/split-lines
                                       (sh! "git" "-C" (.getPath root) "ls-files"))
                                     (remove str/blank?)
                                     vec))}
          'read-file     {:doc "Read one tracked file by relative path."
                          :arglists '([path])
                          :fn (fn [path]
                                (slurp (safe-file root path)))}}}})

(def ^:private vertex-project-env (str "GOOGLE_" "CLOUD_PROJECT"))
(def ^:private vertex-location-env (str "GOOGLE_" "CLOUD_LOCATION"))

(defn- provider-ready? []
  (and (seq (System/getenv vertex-project-env))
       (seq (System/getenv vertex-location-env))))

(deftest ^:live vertex-gemini-surface-root-leaf-child-smoke
  (is (provider-ready?)
      "Vertex Gemini live smoke requires exported Vertex project and location environment variables")
  (when (provider-ready?)
    (let [root (init-git-world!)
          cfg (fe/make-config
                {:adapter :sdk
                 :provider :vertex-gemini
                 :model "gemini-3.5-flash"
                 :child-provider :vertex-gemini
                 :child-model "gemini-3.5-flash"
                 :leaf-provider :vertex-gemini
                 :leaf-model "gemini-3.1-flash-lite-preview"
                 :harness :rlm
                 :capability (assoc (cap/default-profile)
                                    :capability/name :live-surface
                                    :surface/fns '#{git/tracked-files git/read-file})
                 :surfaces [(git-surface root)]
                 :system-overlay
                 (str "For this live smoke, answer by emitting fenced Clojure. "
                      "Use git/tracked-files, git/read-file, lm, rlm, and FINAL. "
                      "Do not use shell or filesystem APIs directly.")
                 :max-steps 8
                 :max-fanout 4
                 :fanout-pool 2
                 :leaf-concurrency 2
                 :call-timeout-ms 300000})
          h (fe/start-session! cfg)]
      (try
        (let [task (str "Use the injected git surface. Emit Clojure that: "
                        "defines files with (git/tracked-files), reads README.md with git/read-file, "
                        "calls (lm {:file \"README.md\" :text readme} "
                        "\"Return EDN {:summary string :mentions-surface boolean}.\" :edn), "
                        "calls (rlm \"Use git/tracked-files and git/read-file to confirm src/feature.clj defines feature. FINAL {:child-read? true :file-count N}.\"), "
                        "then FINAL {:files files :leaf leaf-result :child (:rlm/value child-result)}.")
              result (fe/run-turn! h task)
              value (:turn/final-value result)]
          (is (= :final (:status result)) (pr-str (:error result)))
          (is (some #{"README.md"} (:files value)))
          (is (some #{"src/feature.clj"} (:files value)))
          (is (map? (:leaf value)))
          (is (true? (get-in value [:child :child-read?])))
          (is (some #(= :invocation (:edge/type %)) (:edges (fe/view h)))))
        (finally
          (fe/stop-session! h)
          (rm-rf! root))))))
