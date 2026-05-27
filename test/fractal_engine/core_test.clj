(ns fractal-engine.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.cli :as cli]
            [fractal-engine.process :as process]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.resume :as resume]
            [fractal-engine.session :as session]))

(defn tmp-dir [name]
  (let [dir (java.nio.file.Files/createTempDirectory
             (str "fractal-" name "-")
             (make-array java.nio.file.attribute.FileAttribute 0))]
    (str dir)))

(defn scripted-cfg [responses]
  (process/config {:scripted/responses (atom (vec responses))}))

(deftest prompt-contract
  (doseq [sym ["FINAL" "lm" "map-lm" "rlm" "map-rlm"]]
    (is (clojure.string/includes? prompt/system-prompt sym)))
  (is (not (clojure.string/includes? prompt/system-prompt "context")))
  (doseq [forbidden ["product" "storage" "workflow"]]
    (is (not (clojure.string/includes? (clojure.string/lower-case prompt/system-prompt) forbidden))))
  (is (clojure.string/includes? prompt/system-prompt "observations"))
  (is (clojure.string/includes? prompt/system-prompt "Call FINAL only after enough observations have been inspected"))
  (is (clojure.string/includes? prompt/system-prompt "Use map-lm when the same semantic operation applies independently"))
  (is (clojure.string/includes? prompt/system-prompt "Use map-rlm when several independent lanes can run in parallel"))
  (is (clojure.string/includes? prompt/system-prompt "Children should use lm and map-lm aggressively"))
  (is (clojure.string/includes? prompt/system-prompt "Bind large values with def"))
  (doseq [example ["- lm:" "- map-lm:" "- rlm:" "- map-rlm:"]]
    (is (clojure.string/includes? prompt/system-prompt example)))
  (is (clojure.string/includes? prompt/child-prompt "Child session boundary"))
  (is (clojure.string/includes? prompt/child-prompt "Do not solve the parent task globally"))
  (is (= prompt/prompt-metadata (prompt/metadata-for prompt/system-prompt)))
  (is (= (:prompt/hash prompt/prompt-metadata) (cache/sha256-string prompt/system-prompt)))
  (is (= 3 (:prompt/version prompt/prompt-metadata))))

(deftest fenced-block-extraction
  (is (= ["(+ 1 2)\n"]
         (runtime/extract-clojure-blocks "x\n```clojure\n(+ 1 2)\n```\ny")))
  (is (= ["(def x 1)\n" "x\n"]
         (runtime/extract-clojure-blocks "```clj\n(def x 1)\n```\n```clojure\nx\n```")))
  (is (empty? (runtime/extract-clojure-blocks "```python\n1\n```"))))

(deftest artifact-skeleton-round-trips
  (let [dir (tmp-dir "artifacts")
        state (artifacts/new-state! {:dir dir
                                     :id "root"
                                     :kind :root
                                     :provider {}})]
    (artifacts/add-message! state :user "hello")
    (artifacts/add-eval! state {:eval/message-id 1
                                :eval/block-index 0
                                :eval/code "42"
                                :eval/status :ok
                                :eval/value 42})
    (doseq [file ["session.edn" "messages.edn" "turns.edn" "evals.edn" "calls.edn"
                  "events.edn" "snapshots.edn" "usage.edn" "tree.edn"]]
      (is (some? (artifacts/read-edn-file (artifacts/path dir file) nil)) file))
    (let [session-text (slurp (str (artifacts/path dir "session.edn")))]
      (is (clojure.string/includes? session-text "\n "))
      (is (> (count (clojure.string/split-lines session-text)) 3)))))

(deftest simple-final-loop
  (let [dir (tmp-dir "simple")
        result (process/run-process!
                (scripted-cfg ["```clojure\n(def x 42)\nx\n```"
                               "```clojure\n(FINAL {:answer x})\n```"])
                {:dir dir :id "root" :kind :root :task "define x then final"})]
    (is (= :final (:status result)))
    (is (= {:answer 42} (:final-value result)))
    (is (= 1 (count (artifacts/read-edn-file (artifacts/path dir "turns.edn") []))))
    (is (= :running (:session/status (artifacts/read-edn-file (artifacts/path dir "session.edn") {}))))
    (is (= 2 (count (artifacts/read-edn-file (artifacts/path dir "evals.edn") []))))
    (is (= :running (:final/status (artifacts/read-edn-file (artifacts/path dir "final.edn") {}))))))

(deftest session-turn-chat-semantics
  (let [dir (tmp-dir "session")
        requests (atom [])
        root-count (atom 0)
        cfg (process/config
             {:scripted/response-fn
              (fn [request]
                (swap! requests conj request)
                (case (swap! root-count inc)
                  1 "```clojure\n(def x 42)\n(FINAL {:saved x})\n```"
                  2 "```clojure\n(FINAL {:restored x})\n```"))})
        s (session/start-session! cfg {:dir dir :id "root"})
        turn1 (session/run-turn! s "save x")
        turn2 (session/run-turn! s "use x")
        state @(:state s)
        messages (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
        turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])
        evals (artifacts/read-edn-file (artifacts/path dir "evals.edn") [])
        calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
        final (artifacts/read-edn-file (artifacts/path dir "final.edn") {})
        usage (artifacts/read-edn-file (artifacts/path dir "usage.edn") {})]
    (is (= {:saved 42} (:final-value turn1)))
    (is (= {:restored 42} (:final-value turn2)))
    (is (= :running (get-in state [:session :session/status])))
    (is (= 1 (count (filter #(= :system (:message/role %)) messages))))
    (is (= 2 (count turns)))
    (is (= [:final :final] (mapv :turn/status turns)))
    (is (= [nil 1 1 1 2 2 2] (mapv :message/turn-id messages)))
    (is (every? :eval/turn-id evals))
    (is (every? :call/turn-id calls))
    (is (= :running (:final/status final)))
    (is (= 2 (:final/latest-turn-id final)))
    (is (= {:restored 42} (:final/latest-value-preview final)))
    (is (= usage (:final/usage final)))
    (is (= #{"fractal:root:agent"} (set (map :call/cache-scope calls))))
    (is (= [1 2 3 4 5] (:call/request-message-ids (second calls))))
    (is (= 2 (count @requests)))
    (is (= (:request/messages (first @requests))
           (take 2 (:request/messages (second @requests)))))
    (session/stop-session! s)
    (is (= :stopped (:session/status (artifacts/read-edn-file (artifacts/path dir "session.edn") {}))))))

(deftest no-fence-repair-and-eval-error-recovery
  (let [dir (tmp-dir "repair")
        result (process/run-process!
                (scripted-cfg ["plain prose"
                               "```clojure\n(/ 1 0)\n```"
                               "```clojure\n(FINAL :recovered)\n```"])
                {:dir dir :id "root" :kind :root :task "recover"})]
    (is (= :final (:status result)))
    (is (= :recovered (:final-value result)))
    (let [messages (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
          evals (artifacts/read-edn-file (artifacts/path dir "evals.edn") [])]
      (is (some #(clojure.string/includes? (:message/content %) "No fenced Clojure") messages))
      (is (some #(= :error (:eval/status %)) evals)))))

(deftest leaf-and-map-leaf
  (let [dir (tmp-dir "leaf")
        response-fn (fn [request]
                      (let [content (:message/content (last (:request/messages request)))]
                        (cond
                          (clojure.string/includes? content "leaf")
                          "```clojure\n(def a (lm {:x 1} \"return label\"))\n(def b (map-lm [{:x 1} {:x 2}] \"return EDN x\" :edn))\n(FINAL {:a a :b b})\n```"
                          (clojure.string/includes? content "return label")
                          "label"
                          (clojure.string/includes? content "{:x 1}")
                          "{:x 1}"
                          (clojure.string/includes? content "{:x 2}")
                          "{:x 2}"
                          :else "label")))
        result (process/run-process!
                (process/config {:scripted/response-fn response-fn})
                {:dir dir :id "root" :kind :root :task "leaf"})]
    (is (= {:a "label" :b [{:x 1} {:x 2}]} (:final-value result)))
    (let [calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])]
      (is (= 1 (count (filter #(= :leaf (:call/type %)) calls))))
      (is (= 2 (count (filter #(= :leaf-batch-item (:call/type %)) calls))))
      (is (every? #(= 1 (:call/turn-id %))
                  (filter #(#{:leaf :leaf-batch-item} (:call/type %)) calls)))
      (is (every? #(= 1 (:call/parent-eval-id %))
                  (filter #(#{:leaf :leaf-batch-item} (:call/type %)) calls))))))

(deftest prompt-cache-and-request-artifacts
  (let [dir (tmp-dir "cache")
        result (process/run-process!
                (scripted-cfg ["```clojure\n(def x 1)\nx\n```"
                               "```clojure\n(def y (lm {:x x} \"return label\"))\n(FINAL y)\n```"
                               "label"])
                {:dir dir :id "root" :kind :root :task "cache"})
        session (artifacts/read-edn-file (artifacts/path dir "session.edn") {})
        calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
        root-calls (filter #(= :root (:call/type %)) calls)
        leaf-calls (filter #(= :leaf (:call/type %)) calls)
        first-root (first root-calls)]
    (is (= :final (:status result)))
    (is (= prompt/prompt-metadata (:session/prompt session)))
    (is (= "fractal:root:agent" (get-in session [:session/cache :agent-scope])))
    (is (= "fractal:root:leaf" (get-in session [:session/cache :leaf-scope])))
    (is (= #{"fractal:root:agent"} (set (map :call/cache-scope root-calls))))
    (is (= #{"fractal:root:leaf"} (set (map :call/cache-scope leaf-calls))))
    (is (= {:enabled? true :scope-id "fractal:root:agent"} (:call/cache-request first-root)))
    (is (= [1 2 3 4] (:call/request-message-ids (second root-calls))))
    (is (= 4 (:call/request-message-count (second root-calls))))
    (is (= (:prompt/hash (:session/prompt session)) (:call/request-system-hash first-root)))
    (is (= (:request (first root-calls))
           (artifacts/read-ref dir (:call/request-ref (first root-calls)))))
    (is (= :scripted (:response/provider (artifacts/read-ref dir (:call/response-ref first-root)))))))

(deftest child-and-map-child
  (let [dir (tmp-dir "child")
        response-fn (fn [request]
                      (let [content (:message/content (last (:request/messages request)))]
                        (cond
                          (clojure.string/includes? content "children")
                          "```clojure\n(def one (rlm \"child one\"))\n(def many (map-rlm [\"a\" \"b\"]))\n(FINAL {:one one :many many})\n```"
                          (clojure.string/includes? content "child one")
                          "```clojure\n(FINAL {:child 1})\n```"
                          (= "a" content)
                          "```clojure\n(FINAL :a)\n```"
                          (= "b" content)
                          "```clojure\n(FINAL :b)\n```"
                          :else
                          "```clojure\n(FINAL :unknown)\n```")))
        result (process/run-process!
                (process/config {:scripted/response-fn response-fn})
                {:dir dir :id "root" :kind :root :task "children"})]
    (is (= {:one {:child 1} :many [:a :b]} (:final-value result)))
    (is (.exists (java.io.File. dir "children/child-0001/session.edn")))
    (is (.exists (java.io.File. dir "children/child-0001/turns.edn")))
    (is (.exists (java.io.File. dir "children/child-0002/session.edn")))
    (is (.exists (java.io.File. dir "children/child-0002/turns.edn")))
    (is (.exists (java.io.File. dir "children/child-0003/session.edn")))
    (is (.exists (java.io.File. dir "children/child-0003/turns.edn")))
    (let [calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
          child-calls (filter #(#{:child :child-batch-item} (:call/type %)) calls)
          child-session (artifacts/read-edn-file (artifacts/path dir "children/child-0001/session.edn") {})
          child-root-calls (artifacts/read-edn-file (artifacts/path dir "children/child-0001/calls.edn") [])]
      (is (= 1 (count (filter #(= :child (:call/type %)) calls))))
      (is (= 2 (count (filter #(= :child-batch-item (:call/type %)) calls))))
      (is (every? #(= 1 (:call/turn-id %)) child-calls))
      (is (every? #(= 1 (:call/parent-eval-id %)) child-calls))
      (is (= #{"root/children/child-0001"
               "root/children/child-0002"
               "root/children/child-0003"}
             (set (map :child/cache-id child-calls))))
      (is (= "child-0001" (:session/id child-session)))
      (is (= "root/children/child-0001" (:session/cache-id child-session)))
      (is (= "fractal:root/children/child-0001:agent"
             (get-in child-session [:session/cache :agent-scope])))
      (is (= #{"fractal:root/children/child-0001:agent"}
             (set (map :call/cache-scope child-root-calls)))))))

(deftest child-cache-scopes-do-not-collide-across-parent-sessions
  (let [response-fn (fn [request]
                      (let [content (:message/content (last (:request/messages request)))]
                        (if (clojure.string/includes? content "spawn child")
                          "```clojure\n(FINAL (rlm \"child task\"))\n```"
                          "```clojure\n(FINAL :child)\n```")))
        run (fn [root-id]
              (let [dir (tmp-dir root-id)]
                (process/run-process!
                 (process/config {:scripted/response-fn response-fn})
                 {:dir dir :id root-id :kind :root :task "spawn child"})
                {:dir dir
                 :child-session (artifacts/read-edn-file
                                 (artifacts/path dir "children/child-0001/session.edn")
                                 {})
                 :child-call (first (filter #(= :child (:call/type %))
                                            (artifacts/read-edn-file
                                             (artifacts/path dir "calls.edn")
                                             [])))}))
        a (run "parent-a")
        b (run "parent-b")]
    (is (= "child-0001" (get-in a [:child-session :session/id])))
    (is (= "child-0001" (get-in b [:child-session :session/id])))
    (is (= "parent-a/children/child-0001"
           (get-in a [:child-session :session/cache-id])
           (get-in a [:child-call :child/cache-id])))
    (is (= "parent-b/children/child-0001"
           (get-in b [:child-session :session/cache-id])
           (get-in b [:child-call :child/cache-id])))
    (is (not= (get-in a [:child-session :session/cache])
              (get-in b [:child-session :session/cache])))))

(deftest usage-rollup-known-unknown-partial-and-children
  (let [dir (tmp-dir "usage")
        child-dir (str (artifacts/path dir "children" "child-0001"))
        child-state (artifacts/new-state! {:dir child-dir
                                           :id "child-0001"
                                           :kind :child
                                           :provider {}})
        parent-state (artifacts/new-state! {:dir dir
                                            :id "root"
                                            :kind :root
                                            :provider {}})]
    (artifacts/add-call! child-state {:call/type :root
                                      :call/status :ok
                                      :call/usage {:usage/input-tokens 7
                                                   :usage/output-tokens 3
                                                   :usage/total-tokens 10}
                                      :call/cost {:cost/usd 0.02}
                                      :call/cache {:cache/status :hit
                                                   :cache/cached-tokens 5}})
    (artifacts/add-call! parent-state {:call/type :root
                                       :call/status :ok
                                       :call/usage {:usage/input-tokens 10
                                                    :usage/output-tokens 5
                                                    :usage/total-tokens 15}
                                       :call/cost {:cost/usd 0.03}
                                       :call/cache {:cache/status :miss}})
    (artifacts/add-call! parent-state {:call/type :leaf
                                       :call/status :ok
                                       :call/usage {:usage/input-tokens 4}
                                       :call/cost {:cost/usd :unknown}
                                       :call/cache {:cache/status :unknown}})
    (artifacts/add-call! parent-state {:call/type :leaf-batch-item
                                       :call/status :ok
                                       :call/usage {:usage/status :unknown}
                                       :call/cache {:cache/status :hit
                                                    :cache/cached-tokens 2}})
    (artifacts/add-call! parent-state {:call/type :child
                                       :call/status :final
                                       :child/session-id "child-0001"
                                       :child/dir "children/child-0001"})
    (let [usage (artifacts/read-edn-file (artifacts/path dir "usage.edn") {})
          final (artifacts/read-edn-file (artifacts/path dir "final.edn") {})]
      (is (= :known (get-in usage [:usage/root :usage/status])))
      (is (= 15 (get-in usage [:usage/root :tokens/total :known])))
      (is (= :partial (get-in usage [:usage/leaf :usage/status])))
      (is (= 1 (get-in usage [:usage/leaf :tokens/input :unknown-calls])))
      (is (= 4 (get-in usage [:usage/total-tree :call/count])))
      (is (= 5 (get-in usage [:usage/total-tree :call/total-tree-count])))
      (is (= 1 (get-in usage [:usage/children :child/count])))
      (is (= 2 (get-in usage [:cache/total-tree :cache/hit-count])))
      (is (= 1 (get-in usage [:cache/total-tree :cache/miss-count])))
      (is (= 1 (get-in usage [:cache/total-tree :cache/unknown-count])))
      (is (= usage (:final/usage final))))))

(deftest provider-failure-finalizes-root-and-leaf-failures-remain-model-visible
  (testing "root provider failure returns structured error artifacts"
    (let [dir (tmp-dir "root-provider-failure")
          result (process/run-process!
                  (process/config {:scripted/response-fn (fn [_]
                                                           (throw (ex-info "boom" {:why :test})))})
                  {:dir dir :id "root" :kind :root :task "fail"})
          calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
          turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])
          events (artifacts/read-edn-file (artifacts/path dir "events.edn") [])
          final (artifacts/read-edn-file (artifacts/path dir "final.edn") {})]
      (is (= :error (:status result)))
      (is (= :provider/failed (get-in result [:error :error/type])))
      (is (= :error (:final/status final)))
      (is (= :provider/failed (get-in final [:final/error :error/type])))
      (is (= :error (:turn/status (first turns))))
      (is (= :provider/failed (get-in (first turns) [:turn/error :error/type])))
      (is (= :error (:call/status (first calls))))
      (is (some #(= :provider-failed (:event/type %)) events))))
  (testing "leaf provider failure can be caught by model code"
    (let [dir (tmp-dir "leaf-provider-caught")
          response-fn (fn [request]
                        (let [content (:message/content (last (:request/messages request)))]
                          (cond
                            (clojure.string/includes? content "catch leaf")
                            "```clojure\n(FINAL (try (lm {:x 1} \"fail\") (catch Exception e (select-keys (ex-data e) [:error/type :error/provider :error/model]))))\n```"
                            (clojure.string/includes? content "fail")
                            (throw (ex-info "leaf boom" {:why :leaf})))))
          result (process/run-process!
                  (process/config {:scripted/response-fn response-fn})
                  {:dir dir :id "root" :kind :root :task "catch leaf"})]
      (is (= {:error/type :provider/failed
              :error/provider :scripted
              :error/model "scripted"}
             (:final-value result)))))
  (testing "uncaught leaf provider failure becomes eval error and allows repair"
    (let [dir (tmp-dir "leaf-provider-uncaught")
          root-count (atom 0)
          response-fn (fn [request]
                        (let [content (:message/content (last (:request/messages request)))
                              scope (get-in request [:request/cache :scope-id])]
                          (cond
                            (clojure.string/ends-with? scope ":agent")
                            (case (swap! root-count inc)
                              1 "```clojure\n(lm {:x 1} \"fail\")\n```"
                              "```clojure\n(FINAL :repaired)\n```")
                            (and (clojure.string/ends-with? scope ":leaf")
                                 (clojure.string/includes? content "fail"))
                            (throw (ex-info "leaf boom" {:why :leaf}))
                            :else
                            "```clojure\n(FINAL :repaired)\n```")))
          result (process/run-process!
                  (process/config {:scripted/response-fn response-fn})
                  {:dir dir :id "root" :kind :root :task "uncaught leaf"})
          evals (artifacts/read-edn-file (artifacts/path dir "evals.edn") [])]
      (is (= :repaired (:final-value result)))
      (is (some #(= :error (:eval/status %)) evals)))))

(deftest independent-role-model-config
  (let [root-only (cli/cfg-from-opts {:provider "openai" :model "root-model"})
        full (cli/cfg-from-opts {:provider "openai"
                                 :model "root-model"
                                 :leaf-provider "anthropic"
                                 :leaf-model "leaf-model"
                                 :child-provider "openrouter"
                                 :child-model "child-model"})]
    (is (= {:root {:provider :openai :model "root-model"}
            :leaf {:provider :openai :model "root-model"}
            :child {:provider :openai :model "root-model"}}
           (select-keys (:models root-only) [:root :leaf :child])))
    (is (= {:root {:provider :openai :model "root-model"}
            :leaf {:provider :anthropic :model "leaf-model"}
            :child {:provider :openrouter :model "child-model"}}
           (select-keys (:models full) [:root :leaf :child]))))
  (let [dir (tmp-dir "child-model")
        result (process/run-process!
                (process/config {:models {:root {:provider :scripted :model "root-model"}
                                          :leaf {:provider :scripted :model "leaf-model"}
                                          :child {:provider :scripted :model "child-model"}}
                                 :scripted/response-fn
                                 (fn [request]
                                   (let [content (:message/content (last (:request/messages request)))]
                                     (cond
                                       (clojure.string/includes? content "spawn child")
                                       "```clojure\n(def child (rlm \"child task\"))\n(FINAL child)\n```"
                                       (clojure.string/includes? content "child task")
                                       "```clojure\n(FINAL {:child true})\n```")))})
                {:dir dir :id "root" :kind :root :task "spawn child"})
        child-session (artifacts/read-edn-file (artifacts/path dir "children/child-0001/session.edn") {})
        child-calls (artifacts/read-edn-file (artifacts/path dir "children/child-0001/calls.edn") [])]
    (is (= {:child true} (:final-value result)))
    (is (= "child-model" (get-in child-session [:session/provider :root :model])))
    (is (= "child-model" (:call/model (first child-calls))))
    (is (thrown? clojure.lang.ArityException
                 ((:rlm (process/make-ops (atom {:dir (java.nio.file.Paths/get dir (into-array String []))
                                                 :session {:session/id "root"}})
                                          (process/config)
                                          'fractal.test))
                  "task"
                  {:model "forbidden"})))))

(deftest resume-restores-var
  (let [dir (tmp-dir "resume")
        _ (process/run-process!
           (scripted-cfg ["```clojure\n(def saved 99)\nsaved\n```"
                          "```clojure\n(FINAL {:saved saved})\n```"])
           {:dir dir :id "root" :kind :root :task "save"})
        result (resume/resume!
                (scripted-cfg ["```clojure\n(FINAL {:restored saved})\n```"])
                dir
                "use saved")]
    (is (= :final (:status result)))
    (is (= {:restored 99} (:final-value result)))))

(deftest run-chat-resume-and-fork-use-session-turns
  (testing "run is a stopped one-turn session wrapper"
    (let [out (with-out-str
                (cli/run-command {:fake-script "simple" :question "define x"}))
          dir (second (re-find #"Session: (.+)" out))
          session-row (artifacts/read-edn-file (artifacts/path dir "session.edn") {})
          turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])]
      (is (= :stopped (:session/status session-row)))
      (is (= 1 (count turns)))))
  (testing "chat processes two stdin lines in one session"
    (let [out (with-in-str "first\nsecond\n"
                (with-out-str
                  (cli/chat-command {:fake-script "multi-turn-chat"})))
          dir (second (re-find #"Session: (.+)" out))
          session-row (artifacts/read-edn-file (artifacts/path dir "session.edn") {})
          messages (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
          turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])
          calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])]
      (is (= :stopped (:session/status session-row)))
      (is (= 2 (count turns)))
      (is (= 1 (count (filter #(= :system (:message/role %)) messages))))
      (is (= #{"fractal:"} (set (map #(subs (:call/cache-scope %) 0 8) calls))))))
  (testing "resume adds another turn to existing history"
    (let [dir (tmp-dir "resume-command")
          s (session/start-session! (scripted-cfg ["```clojure\n(def saved 99)\n(FINAL {:saved saved})\n```"])
                                    {:dir dir :id "root"})
          _ (session/run-turn! s "save")
          _ (session/stop-session! s)
          result (resume/resume! (scripted-cfg ["```clojure\n(FINAL {:restored saved})\n```"])
                                 dir
                                 "restore")
          turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])]
      (is (= {:restored 99} (:final-value result)))
      (is (= 2 (count turns)))))
  (testing "fork creates an independent live session with restored vars"
    (let [dir (tmp-dir "fork-source")
          fork-dir (tmp-dir "fork-target")
          s (session/start-session! (scripted-cfg ["```clojure\n(def x 42)\n(FINAL {:saved x})\n```"])
                                    {:dir dir :id "root"})
          _ (session/run-turn! s "save")
          forked (session/fork-session! (scripted-cfg ["```clojure\n(FINAL {:forked x})\n```"])
                                        dir
                                        fork-dir)
          result (session/run-turn! forked "use forked")]
      (is (= {:forked 42} (:final-value result)))
      (is (not= (str (:dir s)) (str (:dir forked)))))))

(deftest map-lm-partial-failure-keeps-successes
  (let [dir (tmp-dir "leaf-failure")
        response-fn (fn [request]
                      (let [content (:message/content (last (:request/messages request)))]
                        (cond
                          (clojure.string/includes? content "partials")
                          "```clojure\n(def partials (try (map-lm [1 2] \"return EDN\" :edn) (catch Exception e (ex-data e))))\n(FINAL partials)\n```"
                          (clojure.string/includes? content "1")
                          "{:ok 1}"
                          :else
                          "{:bad")))
        result (process/run-process!
                (process/config {:scripted/response-fn response-fn})
                {:dir dir :id "root" :kind :root :task "partials"})]
    (is (= :fractal/leaf-batch-failed (get-in result [:final-value :error/type])))
    (is (= {:ok 1} (get-in result [:final-value :results 0 :value])))
    (is (= false (get-in result [:final-value :results 1 :ok])))))

(deftest snapshot-blobs-large-vars-and-marks-unresumable
  (let [dir (tmp-dir "snapshot")
        result (process/run-process!
                (scripted-cfg ["```clojure\n(def big (vec (range 5000)))\n(def proc (Object.))\n(FINAL :done)\n```"])
                {:dir dir :id "root" :kind :root :task "snapshot"})
        snapshots (artifacts/read-edn-file (artifacts/path dir "snapshots.edn") [])
        last-snapshot (last snapshots)]
    (is (= :done (:final-value result)))
    (is (= :blob (get-in last-snapshot [:snapshot/vars 'big :value/kind])))
    (is (= :not-edn (get-in last-snapshot [:snapshot/unresumable 'proc :reason])))))
