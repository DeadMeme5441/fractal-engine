(ns fractal.engine.cli-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal.engine.cli :as cli]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-cli" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f)
    (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- write-config! [dir content]
  (let [f (io/file dir "fractal.edn")]
    (spit f (cli/format-edn content))
    (.getPath f)))

(defn- cli-edn [args]
  (let [out (java.io.StringWriter.)
        err (java.io.StringWriter.)
        code (binding [*out* out *err* err]
               (cli/run-command (concat args ["--edn"])))]
    {:code code
     :out (some-> (str out) str/trim not-empty edn/read-string)
     :raw-out (str out)
     :raw-err (str err)}))

(deftest init-and-config-command
  (let [dir (temp-dir!)
        config-path (.getPath (io/file dir "fractal.edn"))
        store-dir (.getPath (io/file dir "store"))]
    (try
      (let [res (cli-edn ["init" "--config" config-path "--store-dir" store-dir])]
        (is (zero? (:code res)))
        (is (= :init (get-in res [:out :command])))
        (is (.exists (io/file config-path)))
        (is (.isDirectory (io/file store-dir)))
        (let [config-text (slurp config-path)]
          (is (str/includes? config-text "\n"))
          (is (= :fake (:default-profile (edn/read-string config-text))))))
      (let [res (cli-edn ["config" "--config" config-path])]
        (is (zero? (:code res)))
        (is (= :configured (get-in res [:out :config :fake/respond])))
        (is (= :sqlite (get-in res [:out :config :store]))))
      (finally (rm-rf! dir)))))

(deftest usage-and-inspection-commands-round-trip
  (let [dir (temp-dir!)
        store-dir (.getPath (io/file dir "store"))
        config-path (write-config!
                      dir
                      {:default-profile :test
                       :profiles
                       {:test {:adapter :fake
                               :model "fake-model"
                               :harness :rlm
                               :capability :default
                               :store :sqlite
                               :store/dir store-dir
                               :fake/respond
                               [["define" "```clojure\n(def x 7)\n(FINAL :defined)\n```"]
                                ["use" "```clojure\n(FINAL (* x 6))\n```"]
                                [:default "```clojure\n(FINAL :ok)\n```"]]}}})]
    (try
      (is (zero? (:code (cli-edn ["start" "--config" config-path "--session" "demo"]))))
      (let [turn1 (cli-edn ["turn" "--config" config-path "--session" "demo" "--message" "define"])
            turn2 (cli-edn ["turn" "--config" config-path "--session" "demo" "--message" "use"])]
        (is (= :defined (get-in turn1 [:out :result :turn/final-value])))
        (is (= 42 (get-in turn2 [:out :result :turn/final-value]))))
      (let [status (cli-edn ["status" "--config" config-path "--session" "demo"])
            heads (cli-edn ["heads" "--config" config-path "--session" "demo"])
            events (cli-edn ["events" "--config" config-path "--session" "demo" "--since" "0"])
            report (cli-edn ["report" "--config" config-path "--session" "demo"])
            check (cli-edn ["check" "--config" config-path "--session" "demo"])]
        (is (= 2 (get-in status [:out :turn-count])))
        (is (= 2 (count (get-in heads [:out :heads]))))
        (is (pos? (count (get-in events [:out :events]))))
        (is (= :final (get-in report [:out :last-turn/status])))
        (is (= 2 (get-in report [:out :turn-count])))
        (is (true? (get-in check [:out :ok]))))
      (finally (rm-rf! dir)))))

(deftest run-starts-or-resumes-and_tree_shows_recursion_edges
  (let [dir (temp-dir!)
        store-dir (.getPath (io/file dir "store"))
        config-path (write-config!
                      dir
                      {:adapter :fake
                       :model "fake-model"
                       :harness :rlm
                       :capability :default
                       :store :sqlite
                       :store/dir store-dir
                       :fake/respond
                       [["Assigned task:" "```clojure\n(FINAL {:child 5})\n```"]
                        [:default "```clojure\n(FINAL (rlm \"child task\"))\n```"]]})]
    (try
      (let [run (cli-edn ["run" "--config" config-path "--session" "tree" "--message" "spawn"])
            edges (cli-edn ["edges" "--config" config-path "--session" "tree"])
            tree (cli-edn ["tree" "--config" config-path "--session" "tree"])]
        (is (= {:child 5} (get-in run [:out :result :turn/final-value :rlm/value])))
        (is (= :invocation (get-in edges [:out :edges 0 :edge/type])))
        (is (= 2 (count (get-in tree [:out :sessions])))))
      (finally (rm-rf! dir)))))

(deftest payload-command-hydrates-large-refs
  (let [dir (temp-dir!)
        store-dir (.getPath (io/file dir "store"))
        config-path (write-config!
                      dir
                      {:adapter :fake
                       :model "fake-model"
                       :harness :clojure
                       :capability :default
                       :store :sqlite
                       :store/dir store-dir
                       :fake/respond
                       [[:default "```clojure\n(FINAL (vec (range 2000)))\n```"]]})]
    (try
      (is (zero? (:code (cli-edn ["run" "--config" config-path "--session" "payload" "--message" "big"]))))
      (let [turns (cli-edn ["turns" "--config" config-path "--session" "payload"])
            ref (get-in turns [:out :turns 0 :turn/final-ref])
            payload (cli-edn ["payload" "--config" config-path "--session" "payload" (pr-str ref)])]
        (is (= :payload (:fractal/ref ref)))
        (is (= 2000 (count (get-in payload [:out :value])))))
      (finally (rm-rf! dir)))))

(deftest non-final-turn-status-is-not-a-cli-preflight-failure
  (let [dir (temp-dir!)
        config-path (write-config!
                      dir
                      {:adapter :fake
                       :model "fake-model"
                       :store :sqlite
                       :store/dir (.getPath (io/file dir "store"))
                       :max-steps 1
                       :fake/respond [[:default "```clojure\n(def x 1)\n```"]]})]
    (try
      (let [res (cli-edn ["run" "--config" config-path "--session" "budgeted" "--message" "no final"])]
        (is (zero? (:code res)))
        (is (true? (get-in res [:out :ok])))
        (is (= :budget-exceeded
               (get-in res [:out :result :status]))))
      (finally (rm-rf! dir)))))

(deftest missing-session-is-a_structured_cli_error
  (let [dir (temp-dir!)
        config-path (write-config!
                      dir
                      {:adapter :fake
                       :model "fake-model"
                       :store :sqlite
                       :store/dir (.getPath (io/file dir "store"))
                       :fake/respond [[:default "```clojure\n(FINAL :ok)\n```"]]})]
    (try
      (let [res (cli-edn ["status" "--config" config-path "--session" "missing"])]
        (is (= 1 (:code res)))
        (is (= :cli/session-not-found
               (get-in res [:out :error/data :error/type]))))
      (finally (rm-rf! dir)))))
