(ns fractal.engine.surface-session-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.adapter.request :as request]
            [fractal.engine.api :as fe]
            [fractal.engine.capability :as cap]
            [fractal.engine.session :as session]
            [fractal.engine.store :as store]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-surface" (make-array java.nio.file.attribute.FileAttribute 0))))

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
    (write-file! root "README.md" "# Demo\n\nThis repository documents the feature.\n")
    (write-file! root "src/feature.clj" "(ns demo.feature)\n\n(defn feature [] :ready)\n")
    (sh! "git" "-C" (.getPath root) "init" "-q")
    (sh! "git" "-C" (.getPath root) "add" ".")
    root))

(defn- safe-file [root rel]
  (let [base (.getCanonicalFile (io/file root))
        f (.getCanonicalFile (io/file root rel))]
    (when-not (or (= base f)
                  (str/starts-with? (.getPath f)
                                    (str (.getPath base) java.io.File/separator)))
      (throw (ex-info "path escapes git test world"
                      {:error/type :test/path-escape :path rel})))
    f))

(defn- jira-surface
  ([] (jira-surface 1 "Search issues."))
  ([version doc]
   {:surface/id :jira
    :surface/version version
    :surface/prompt "Use jira/search when you need issue candidates."
    :surface/namespaces
    {'jira {'search {:doc doc
                     :arglists '([query opts])
                     :fn (fn [query opts] {:surface :jira
                                           :query query
                                           :limit (:limit opts)})}
            'issue  {:doc "Fetch an issue."
                     :arglists '([key opts])
                     :fn (fn [key _opts] {:key key})}}}}))

(defn- git-surface [root]
  {:surface/id :git
   :surface/version 1
   :surface/prompt "Use git/tracked-files to enumerate files and git/read-file to hydrate bounded content."
   :surface/namespaces
   {'git {'tracked-files {:doc "Return tracked repository files."
                          :arglists '([])
                          :fn (fn []
                                (->> (str/split-lines
                                       (sh! "git" "-C" (.getPath root) "ls-files"))
                                     (remove str/blank?)
                                     vec))}
          'status-short  {:doc "Return porcelain status lines."
                          :arglists '([])
                          :fn (fn []
                                (->> (str/split-lines
                                       (sh! "git" "-C" (.getPath root) "status" "--short"))
                                     (remove str/blank?)
                                     vec))}
          'read-file     {:doc "Read one tracked file by relative path."
                          :arglists '([path])
                          :fn (fn [path]
                                (slurp (safe-file root path)))}}}})

(defn- world-surface []
  {:surface/id :world
   :surface/version 1
   :surface/prompt "Use world/value when the task asks for the injected world value."
   :surface/prompts
   {:system "Stable world prompt: world/value returns a deterministic number."
    :request (fn [ctx]
               (str "request-session=" (:session/id ctx)
                    "\nrequest-turn=" (:turn/id ctx)
                    "\nrequest-functions=" (pr-str (:surface/functions ctx))))
    :leaf (fn [ctx]
            (str "leaf-input=" (pr-str (:input ctx))
                 "\nleaf-mode=" (:mode ctx)))}
   :surface/namespaces
   {'world {'value {:doc "Return the deterministic world value."
                    :arglists '([])
                    :fn (fn [] 42)}}}})

(defn- profile [& fns]
  (assoc (cap/default-profile)
         :capability/name :surface-test
         :surface/fns (set fns)))

(defn- cfg [respond & {:as extra}]
  (fe/make-config
    (merge {:adapter :fake
            :fake/respond respond
            :model "fake-model"
            :harness :rlm
            :capability (profile 'jira/search)
            :surfaces [(jira-surface)]
            :max-steps 4}
           extra)))

(defn- eval-ok? [handle expr]
  (try (sci/eval-string* @(:sci-ctx handle) expr)
       true
       (catch Throwable _ false)))

(defn- system-prompt [handle]
  (->> (request/build-request (:store handle)
                              (store/current-view (:store handle) (:session-id handle))
                              (:cfg handle)
                              (:capability handle)
                              handle)
       :messages
       (filter #(= :system (:role %)))
       first
       :content))

(defn- leaf-request? [req]
  (str/starts-with? (or (fake/last-user req) "") "Input EDN:"))

(defn- child-request? [req]
  (str/includes? (or (fake/last-user req) "") "Assigned task:"))

(defn- system-message-content [req]
  (->> (:messages req) (filter #(= :system (:role %))) first :content))

(defn- user-contents [req]
  (->> (:messages req) (filter #(= :user (:role %))) (mapv :content)))

(deftest root-session-can-call-capability-allowed-surface-function
  (let [respond (fe/responder
                  [[:default "```clojure (FINAL (jira/search \"auth failures\" {:limit 2}))```"]])
        h (fe/start-session! (cfg respond))]
    (try
      (let [r (fe/run-turn! h "search")]
        (is (= :final (:status r)))
        (is (= {:surface :jira :query "auth failures" :limit 2}
               (:turn/final-value r))))
      (is (seq (get-in (fe/view h) [:session :session/surface-stamps]))
          "session metadata carries public surface stamps")
      (finally (fe/stop-session! h)))))

(deftest configured-but-not-allowed-surface-functions-are-absent
  (let [respond (fe/responder [[:default "```clojure (FINAL :unused)```"]])
        h (fe/start-session! (cfg respond :capability (profile)))]
    (try
      (is (false? (eval-ok? h "(jira/search \"auth\" {})")))
      (finally (fe/stop-session! h)))))

(deftest prompt-card-lists-only-exposed-surface-functions-before-overlays
  (let [respond (fe/responder [[:default "```clojure (FINAL :ok)```"]])
        h (fe/start-session! (cfg respond :system-overlay "CONFIG OVERLAY"))]
    (try
      (let [text (system-prompt h)
            surface-idx (str/index-of text "Additional SDK surfaces")
            overlay-idx (str/index-of text "CONFIG OVERLAY")]
        (is surface-idx)
        (is overlay-idx)
        (is (< surface-idx overlay-idx))
        (is (str/includes? text "jira/search"))
        (is (str/includes? text "Search issues."))
        (is (not (str/includes? text "jira/issue"))))
      (finally (fe/stop-session! h)))))

(deftest child-sessions-inherit-configured-and-capability-allowed-surfaces
  (let [respond (fe/responder [[:default "```clojure (FINAL :unused)```"]])
        parent (fe/start-session! (cfg respond))
        child (session/spawn-child! parent {})]
    (try
      (is (= {:surface :jira :query "child" :limit 1}
             (sci/eval-string* @(:sci-ctx child)
                               "(jira/search \"child\" {:limit 1})")))
      (is (= (get-in (fe/view parent) [:session :session/surface-stamps])
             (get-in (store/current-view (:store child) (:session-id child))
                     [:session :session/surface-stamps])))
      (finally
        (session/stop-session! child)
        (fe/stop-session! parent)))))

(deftest resume-requires-matching-surface-stamps
  (let [dir (temp-dir!)
        sid "surface-resume"
        respond (fe/responder
                  [["define" "```clojure (def remembered (jira/search \"auth\" {:limit 1})) (FINAL remembered)```"]
                   ["again"  "```clojure (FINAL remembered)```"]])
        cfg1 (cfg respond :store :sqlite :store/dir dir)]
    (try
      (let [h1 (fe/start-session! cfg1 {:id sid})
            r1 (fe/run-turn! h1 "define")]
        (is (= :final (:status r1)))
        (fe/close-session! h1))
      (let [h2 (fe/resume-session! cfg1 sid)
            r2 (fe/run-turn! h2 "again")]
        (is (= :final (:status r2)))
        (is (= {:surface :jira :query "auth" :limit 1} (:turn/final-value r2)))
        (fe/close-session! h2))
      (testing "omitting the configured surface fails loudly (the bundle gate, yjy)"
        (let [missing (cfg respond :store :sqlite :store/dir dir :surfaces [])]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"surfaces"
                (fe/resume-session! missing sid)))))
      (testing "changing the public surface stamp fails loudly (the bundle gate, yjy)"
        (let [changed (cfg respond :store :sqlite :store/dir dir
                           :surfaces [(jira-surface 1 "Changed doc.")])]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"surfaces"
                (fe/resume-session! changed sid)))))
      (finally (rm-rf! dir)))))

(deftest dynamic-request-prompt-is-transient-and-cache-aware
  (let [seen (atom [])
        respond (fn [req]
                  (swap! seen conj req)
                  "```clojure (FINAL (world/value))```")
        h (fe/start-session!
            (fe/make-config {:adapter :fake
                             :fake/respond respond
                             :model "fake-model"
                             :harness :rlm
                             :capability (profile 'world/value)
                             :surfaces [(world-surface)]
                             :max-steps 4}))]
    (try
      (is (= 42 (:turn/final-value (fe/run-turn! h "use world"))))
      (let [req (first @seen)
            users (user-contents req)]
        (is (str/includes? (system-message-content req)
                           "Stable world prompt"))
        (is (= "use world" (last users))
            "dynamic prompt is inserted before the task")
        (is (some #(str/includes? % "SDK surface request context:") users))
        (is (some #(str/includes? % "request-functions=[world/value]") users))
        (is (= 1 (get-in req [:cache :breakpoints])))
        (is (str/starts-with? (get-in req [:cache :scope-id]) "fr:agent:"))
        (is (not (str/includes? (pr-str (:messages (fe/view h)))
                                "SDK surface request context:"))))
      (finally (fe/stop-session! h)))))

(deftest dynamic-leaf-prompt-reaches-lm-requests
  (let [leaf-reqs (atom [])
        respond (fn [req]
                  (if (leaf-request? req)
                    (do (swap! leaf-reqs conj req)
                        "leaf-ok")
                    "```clojure (FINAL (lm {:topic \"alpha\"} \"label it\" :string))```"))
        h (fe/start-session!
            (fe/make-config {:adapter :fake
                             :fake/respond respond
                             :model "fake-model"
                             :harness :rlm
                             :capability (profile 'world/value)
                             :surfaces [(world-surface)]
                             :max-steps 4}))]
    (try
      (is (= "leaf-ok" (:turn/final-value (fe/run-turn! h "call leaf"))))
      (let [leaf-req (first @leaf-reqs)
            system (system-message-content leaf-req)]
        (is (str/includes? system "SDK surface leaf context:"))
        (is (str/includes? system "leaf-input={:topic \"alpha\"}"))
        (is (str/includes? (get-in leaf-req [:cache :scope-id]) "fr:leaf:")))
      (finally (fe/stop-session! h)))))

(deftest dynamic-request-prompt-applies-to-child-sessions
  (let [child-req (atom nil)
        respond (fe/responder
                  [[child-request?
                    (fn [req]
                      (reset! child-req req)
                      "```clojure (FINAL (world/value))```")]
                   ["spawn child"
                    "```clojure (FINAL (:rlm/value (rlm \"child should use world/value\")))```"]])
        h (fe/start-session!
            (fe/make-config {:adapter :fake
                             :fake/respond respond
                             :model "fake-model"
                             :harness :rlm
                             :capability (profile 'world/value)
                             :surfaces [(world-surface)]
                             :max-steps 4}))]
    (try
      (is (= 42 (:turn/final-value (fe/run-turn! h "spawn child"))))
      (let [req @child-req
            users (user-contents req)]
        (is req)
        (is (str/includes? (system-message-content req) "Stable world prompt"))
        (is (some #(str/includes? % "SDK surface request context:") users))
        (is (str/includes? (last users) "Assigned task:"))
        (is (= 1 (get-in req [:cache :breakpoints]))))
      (finally (fe/stop-session! h)))))

(deftest concrete-git-surface-works-through-recursion-and-leaves
  (let [root (init-git-world!)
        leaf-response (fn [req]
                        (let [text (fake/last-user req)
                              path (second (re-find #":path \"([^\"]+)\"" text))]
                          (pr-str {:path path
                                   :kind (if (str/ends-with? path ".clj") :code :doc)})))
        respond (fe/responder
                  [[leaf-request? leaf-response]
                   [child-request?
                    "```clojure
                     (require '[clojure.string :as str])
                     (def child-files (git/tracked-files))
                     (def child-content (git/read-file \"src/feature.clj\"))
                     (FINAL {:child-read? (str/includes? child-content \"defn feature\")
                             :file-count (count child-files)})
                     ```"]
                   ["analyze git world"
                    "```clojure
                     (def files (git/tracked-files))
                     (def status (git/status-short))
                     (def docs (mapv (fn [p] {:path p :text (git/read-file p)}) files))
                     (def labels (map-lm docs \"Return EDN {:path path :kind :code-or-doc}.\" :edn))
                     (def child-env (rlm \"Use git surface in the child.\"))
                     (FINAL {:files files
                             :status status
                             :labels labels
                             :child (:rlm/value child-env)})
                     ```"]])
        cfg (fe/make-config {:adapter :fake
                             :fake/respond respond
                             :model "fake-model"
                             :harness :rlm
                             :capability (profile 'git/tracked-files
                                                  'git/status-short
                                                  'git/read-file)
                             :surfaces [(git-surface root)]
                             :max-steps 6})
        h (fe/start-session! cfg)]
    (try
      (let [result (fe/run-turn! h "analyze git world")
            value (:turn/final-value result)]
        (is (= :final (:status result)))
        (is (= ["README.md" "src/feature.clj"] (:files value)))
        (is (some #(str/includes? % "README.md") (:status value)))
        (is (= #{{:path "README.md" :kind :doc}
                 {:path "src/feature.clj" :kind :code}}
               (set (:labels value))))
        (is (= {:child-read? true :file-count 2} (:child value)))
        (is (some #(= :invocation (:edge/type %)) (:edges (fe/view h)))))
      (finally
        (fe/stop-session! h)
        (rm-rf! root)))))
