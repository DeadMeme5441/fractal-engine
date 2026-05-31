(ns fractal-engine.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.cli :as cli]
            [fractal-engine.agentcli :as agentcli]
            [fractal-engine.event :as event]
            [fractal-engine.inspect :as inspect]
            [fractal-engine.journal :as journal]
            [fractal-engine.process :as process]
            [fractal-engine.projection :as projection]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.provenance :as provenance]
            [fractal-engine.provider :as provider]
            [fractal-engine.render :as render]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.resume :as resume]
            [fractal-engine.session :as session]
            [fractal-engine.snapshot :as snapshot]
            [llm.sdk :as sdk]
            [llm.sdk.http :as sdk-http]))

(defn tmp-dir [name]
  (let [dir (java.nio.file.Files/createTempDirectory
             (str "fractal-" name "-")
             (make-array java.nio.file.attribute.FileAttribute 0))]
    (str dir)))

(defn scripted-cfg [responses]
  (process/config {:scripted/responses (atom (vec responses))}))

(deftest prompt-contract
  (is (= 17 prompt/prompt-version))
  ;; Most prose can change, but these phrases are part of the model-facing contract:
  ;; the six-function surface, the bounded fan-out rule, and the recovery strategy.
  (doseq [phrase ["map-lm and map-rlm are capped at 50 parallel inputs per call."
                  "For more than 50 items, sequence batches of 40-50 with partition-all, run each chunk as its own map-lm or map-rlm, reduce each chunk locally, then reduce those partials globally."
                  "The host will return a recoverable fanout error for a single oversized fan-out; retry by chunking, not by raising the cap."
                  "Chunk-and-reduce -- map-lm/map-rlm are capped at 50 parallel inputs per call."]]
    (is (clojure.string/includes? prompt/system-prompt phrase)))
  (is (clojure.string/includes?
       prompt/child-prompt
       "If your assigned material has more than 50 items, partition it and run a sequence of 40-50 item batches; compose partials before FINAL."))
  ;; The architectural boundary holds for every behavior prompt, not just the root.
  (doseq [p [prompt/system-prompt prompt/child-prompt prompt/leaf-prompt]]
    (is (not (clojure.string/includes? p "context")))
    (doseq [forbidden ["product" "storage" "workflow"]]
      (is (not (clojure.string/includes? (clojure.string/lower-case p) forbidden)))))
  ;; The six-function surface must be documented to the root, by name.
  (doseq [sym ["FINAL" "lm" "map-lm" "rlm" "map-rlm" "attach-rlm"]]
    (is (clojure.string/includes? prompt/system-prompt sym)))
  ;; Prompt metadata stays internally consistent (hash matches content).
  (is (= (:prompt/hash prompt/prompt-metadata) (cache/sha256-string prompt/system-prompt))))

(deftest fenced-block-extraction
  (is (= ["(+ 1 2)\n"]
         (runtime/extract-clojure-blocks "x\n```clojure\n(+ 1 2)\n```\ny")))
  (is (= ["(def x 1)\n" "x\n"]
         (runtime/extract-clojure-blocks "```clj\n(def x 1)\n```\n```clojure\nx\n```")))
  (is (empty? (runtime/extract-clojure-blocks "```python\n1\n```"))))

(deftest leaf-concurrency-config-is-defaulted-configurable-and-preserved
  (is (= 50 (:max-leaf-concurrency (process/config))))
  (is (= 7 (:max-leaf-concurrency (cli/cfg-from-opts {:max-leaf-concurrency "7"}))))
  (let [cfg (process/config {:max-leaf-concurrency 3})
        normalized-again (process/config cfg)]
    (is (some? (:leaf-concurrency/limiter cfg)))
    (is (identical? (:leaf-concurrency/limiter cfg)
                    (:leaf-concurrency/limiter normalized-again)))))

(deftest eval-observations-are-compact-control-surface
  (let [ns-sym (symbol (str "fractal.test.obs." (java.util.UUID/randomUUID)))
        ops {:lm (fn [& _] nil)
             :map-lm (fn [& _] nil)
             :rlm (fn [& _] nil)
             :map-rlm (fn [& _] nil)}
        _ (runtime/ensure-ns! ns-sym ops)
        ok-row (assoc (runtime/eval-code ns-sym "(def data (vec (range 100)))\n(take 3 data)")
                      :eval/id 1)
        final-row (assoc (runtime/eval-code ns-sym "(FINAL {:count (count data)})")
                         :eval/id 2)
        obs (runtime/observation [ok-row final-row])]
    (is (= :ok (:eval/status ok-row)))
    (is (= 2 (:eval/forms-count ok-row)))
    (is (= :final (:eval/status final-row)))
    (is (= 1 (:eval/forms-count final-row)))
    (is (clojure.string/includes? obs "compact projections"))
    (is (clojure.string/includes? obs "(eval 1 id=1"))
    (is (clojure.string/includes? obs "forms=2 status=ok"))
    (is (clojure.string/includes? obs "=>"))
    (is (clojure.string/includes? (runtime/observation [ok-row]) "No FINAL was called"))
    (is (clojure.string/includes? obs "FINAL=> {:count 100}"))))

(deftest eval-output-is-bounded-and-read-eval-disabled
  (let [ns-sym (symbol (str "fractal.test.safe." (java.util.UUID/randomUUID)))
        ops {:lm (fn [& _] nil)
             :map-lm (fn [& _] nil)
             :rlm (fn [& _] nil)
             :map-rlm (fn [& _] nil)}
        _ (runtime/ensure-ns! ns-sym ops)
        noisy (runtime/eval-code ns-sym "(print (apply str (repeat 4100 \"x\")))\n:done")
        unsafe (runtime/eval-code ns-sym "#=(+ 1 2)")]
    (is (= :ok (:eval/status noisy)))
    (is (<= (count (:eval/stdout noisy))
            (+ runtime/observation-string-limit 64)))
    (is (clojure.string/includes? (:eval/stdout noisy) "truncated"))
    (is (= :error (:eval/status unsafe)))))

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
    (is (not (.exists (java.io.File. dir "blobs"))))
    (let [session-text (slurp (str (artifacts/path dir "session.edn")))]
      (is (clojure.string/includes? session-text "\n "))
      (is (> (count (clojure.string/split-lines session-text)) 3)))))

(deftest chat-reader-accepts-multi-paragraph-message
  (is (= "first paragraph\nstill first message\n\nsecond paragraph"
         (with-in-str "first paragraph\nstill first message\n\nsecond paragraph\n/send\n"
           (cli/read-chat-message))))
  (is (= :quit
         (with-in-str "/exit\n"
           (cli/read-chat-message))))
  (is (= "unterminated but real"
         (with-in-str "unterminated but real\n"
           (cli/read-chat-message)))))

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
    (is (every? #(<= (count %) 64) (map :call/cache-scope calls)))
    (is (= #{"fractal:root:agent"} (set (map :call/cache-label calls))))
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
    (is (= (cache/provider-scope "root" :agent)
           (get-in session [:session/cache :agent-provider-scope])))
    (is (= (cache/provider-scope "root" :leaf)
           (get-in session [:session/cache :leaf-provider-scope])))
    (is (= #{(cache/provider-scope "root" :agent)} (set (map :call/cache-scope root-calls))))
    (is (= #{(cache/provider-scope "root" :leaf)} (set (map :call/cache-scope leaf-calls))))
    (is (= #{"fractal:root:agent"} (set (map :call/cache-label root-calls))))
    (is (= #{"fractal:root:leaf"} (set (map :call/cache-label leaf-calls))))
    (is (= {:enabled? true
            :scope-id (cache/provider-scope "root" :agent)}
           (:call/cache-request first-root)))
    (is (<= (count (get-in first-root [:call/cache-request :scope-id])) 64))
    (is (= [1 2 3 4] (:call/request-message-ids (second root-calls))))
    (is (= 4 (:call/request-message-count (second root-calls))))
    (is (= (:prompt/hash (:session/prompt session)) (:call/request-system-hash first-root)))
    ;; The request lives only under the ref now (not inline on the row), and resolves.
    (is (nil? (:request (first root-calls))))
    (is (contains? (artifacts/read-ref dir (:call/request-ref (first root-calls))) :request/messages))
    (is (= :scripted (:response/provider (artifacts/read-ref dir (:call/response-ref first-root)))))))

(deftest large-call-request-and-response-refs-blob-and-round-trip
  (let [dir (tmp-dir "call-blobs")
        big-request (apply str (repeat 5000 "request-data "))
        big-tail (apply str (repeat 5000 "response-data "))
        result (process/run-process!
                (process/config {:scripted/response-fn
                                 (fn [_]
                                   (str "```clojure\n(FINAL :blobbed)\n```\n" big-tail))})
                {:dir dir :id "root" :kind :root :task big-request})
        calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
        root-call (first calls)
        request-ref (:call/request-ref root-call)
        response-ref (:call/response-ref root-call)]
    (is (= :blobbed (:final-value result)))
    (is (= :blob (:value/kind request-ref)))
    (is (= "blobs/calls/call-000001-request.edn" (:path request-ref)))
    (is (string? (:sha256 request-ref)))
    (is (> (:bytes request-ref) artifacts/inline-byte-threshold))
    (is (= :blob (:value/kind response-ref)))
    (is (= "blobs/calls/call-000001-response.edn" (:path response-ref)))
    (is (.exists (java.io.File. dir "blobs/calls/call-000001-request.edn")))
    (is (.exists (java.io.File. dir "blobs/calls/call-000001-response.edn")))
    (is (nil? (:request root-call)))
    (is (contains? (artifacts/read-ref dir request-ref) :request/messages))
    (is (= :scripted (:response/provider (artifacts/read-ref dir response-ref))))))

(deftest child-and-map-child
  (let [dir (tmp-dir "child")
        response-fn (fn [request]
                      (let [content (:message/content (last (:request/messages request)))]
                        (cond
                          (clojure.string/includes? content "children")
                          "```clojure\n(def one (rlm \"child one\"))\n(def many (map-rlm [\"a\" \"b\"]))\n(FINAL {:one one :many many})\n```"
                          (clojure.string/includes? content "child one")
                          "```clojure\n(FINAL {:child 1})\n```"
                          (clojure.string/includes? content "Assigned child task:\na")
                          "```clojure\n(FINAL :a)\n```"
                          (clojure.string/includes? content "Assigned child task:\nb")
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
          child-messages (artifacts/read-edn-file (artifacts/path dir "children/child-0001/messages.edn") [])
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
      (is (clojure.string/includes? (:message/content (second child-messages))
                                     "Child RLM protocol"))
      (is (clojure.string/includes? (:message/content (second child-messages))
                                     "MUST call (FINAL value)"))
      (is (= "fractal:root/children/child-0001:agent"
             (get-in child-session [:session/cache :agent-scope])))
      (is (= #{(cache/provider-scope "root/children/child-0001" :agent)}
             (set (map :call/cache-scope child-root-calls))))
      (is (= #{"fractal:root/children/child-0001:agent"}
             (set (map :call/cache-label child-root-calls))))
      (is (every? #(<= (count %) 64)
                  (map :call/cache-scope child-root-calls))))))

(defn- unique-ids? [rows k]
  (= (count rows) (count (set (map k rows)))))

(deftest parallel-child-and-leaf-updates-keep-artifacts-parseable
  (let [dir (tmp-dir "parallel-artifacts")
        response-fn (fn [request]
                      (let [content (:message/content (last (:request/messages request)))]
                        (cond
                          (clojure.string/includes? content "fanout stress")
                          "```clojure\n(def children (map-rlm (mapv #(str \"lane-\" %) (range 8))))\n(FINAL children)\n```"

                          (clojure.string/includes? content "Assigned child task:")
                          "```clojure\n(def leaves (map-lm (vec (range 6)) \"leaf echo\" :edn))\n(FINAL {:leaves leaves})\n```"

                          (clojure.string/includes? content "leaf echo")
                          "{:leaf true}"

                          :else
                          "```clojure\n(FINAL :unexpected)\n```")))
        result (process/run-process!
                (process/config {:scripted/response-fn response-fn})
                {:dir dir :id "root" :kind :root :task "fanout stress"})
        parent-calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
        parent-events (artifacts/read-edn-file (artifacts/path dir "events.edn") [])
        child-dirs (map #(artifacts/path dir "children" (format "child-%04d" %))
                        (range 1 9))]
    (is (= :final (:status result)))
    (is (unique-ids? parent-calls :call/id))
    (is (unique-ids? parent-events :event/id))
    (doseq [file ["session.edn" "messages.edn" "turns.edn" "evals.edn" "calls.edn"
                  "events.edn" "snapshots.edn" "final.edn" "usage.edn" "tree.edn"]]
      (is (some? (artifacts/read-edn-file (artifacts/path dir file) nil)) file))
    (doseq [child-dir child-dirs]
      (let [calls (artifacts/read-edn-file (artifacts/path child-dir "calls.edn") [])
            events (artifacts/read-edn-file (artifacts/path child-dir "events.edn") [])]
        (is (= 6 (count (filter #(= :leaf-batch-item (:call/type %)) calls))))
        (is (unique-ids? calls :call/id))
        (is (unique-ids? events :event/id))
        (doseq [file ["session.edn" "messages.edn" "turns.edn" "evals.edn"
                      "calls.edn" "events.edn" "snapshots.edn"]]
          (is (some? (artifacts/read-edn-file (artifacts/path child-dir file) nil))
              (str child-dir "/" file)))))))

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

(deftest child-gets-finalization-warning-before-max-turns
  (let [dir (tmp-dir "child-finalization")
        response-fn (fn [request]
                      (let [content (:message/content (last (:request/messages request)))]
                        (cond
                          (clojure.string/includes? content "spawn stubborn child")
                          "```clojure\n(FINAL (rlm \"stubborn child task\"))\n```"

                          (clojure.string/includes? content "CHILD FINALIZATION REQUIRED")
                          "```clojure\n(FINAL {:forced true :observed observed})\n```"

                          (clojure.string/includes? content "stubborn child task")
                          "```clojure\n(def observed (if (bound? #'observed) (inc observed) 1))\nobserved\n```"

                          (clojure.string/includes? content "No FINAL was called")
                          "```clojure\n(def observed (inc observed))\nobserved\n```"

                          :else
                          "```clojure\n(FINAL :unexpected)\n```")))
        result (process/run-process!
                (process/config {:max-turns 4
                                 :scripted/response-fn response-fn})
                {:dir dir :id "root" :kind :root :task "spawn stubborn child"})
        child-dir (artifacts/path dir "children/child-0001")
        child-messages (artifacts/read-edn-file (artifacts/path child-dir "messages.edn") [])
        child-turns (artifacts/read-edn-file (artifacts/path child-dir "turns.edn") [])
        child-snapshots (artifacts/read-edn-file (artifacts/path child-dir "snapshots.edn") [])]
    (is (= {:forced true :observed 1} (:final-value result)))
    (is (some #(clojure.string/includes? (:message/content %) "CHILD FINALIZATION REQUIRED")
              child-messages))
    (is (= :final (:turn/status (first child-turns))))
    (is (= 1 (count child-snapshots)))))

(deftest map-fanout-is-capped-for-leaves-and-children-with-recoverable-error
  (let [dir (tmp-dir "fanout-cap")
        result (process/run-process!
                (scripted-cfg ["```clojure\n(def leaf-error (try (map-lm (range 51) \"return EDN\" :edn) (catch Exception e (ex-data e))))\n(def child-error (try (map-rlm (range 51)) (catch Exception e (ex-data e))))\n(FINAL {:leaf (select-keys leaf-error [:error/type :fanout/kind :fanout/max :fanout/count-at-least])\n        :child (select-keys child-error [:error/type :fanout/kind :fanout/max :fanout/count-at-least])})\n```"])
                {:dir dir :id "root" :kind :root :task "fanout cap"})
        value (:final-value result)]
    (is (= {:error/type :fractal/fanout-exceeded
            :fanout/kind :leaf
            :fanout/max 50
            :fanout/count-at-least 51}
           (:leaf value)))
    (is (= {:error/type :fractal/fanout-exceeded
            :fanout/kind :child
            :fanout/max 50
            :fanout/count-at-least 51}
           (:child value)))))

(deftest fanout-exceeded-error-tells-the-model-how-to-retry
  (let [dir (tmp-dir "fanout-retry")
        result (process/run-process!
                (scripted-cfg ["```clojure\n(def e (try (map-lm (range 51) \"return EDN\" :edn) (catch Exception e (ex-data e))))\n(FINAL (select-keys e [:error/type :error/retryable? :fanout/strategy]))\n```"])
                {:dir dir :id "root" :kind :root :task "fanout retry"})]
    (is (= {:error/type :fractal/fanout-exceeded
            :error/retryable? true
            :fanout/strategy "Partition inputs into batches of 40-50, call map-lm/map-rlm once per batch, reduce chunk results locally, then reduce globally."}
           (:final-value result)))))

(deftest max-leaf-concurrency-is-global-across-child-leaves
  (let [dir (tmp-dir "leaf-concurrency")
        active-leaves (atom 0)
        max-active-leaves (atom 0)
        response-fn (fn [request]
                      (let [messages (:request/messages request)
                            system-content (:message/content (first messages))
                            content (:message/content (last messages))]
                        (cond
                          (= prompt/leaf-prompt system-content)
                          (let [active (swap! active-leaves inc)]
                            (swap! max-active-leaves max active)
                            (try
                              (Thread/sleep 25)
                              "{:ok true}"
                              (finally
                                (swap! active-leaves dec))))

                          (clojure.string/includes? content "leaf concurrency stress")
                          "```clojure\n(def children (map-rlm (mapv #(str \"lane-\" %) (range 4))))\n(FINAL children)\n```"

                          (clojure.string/includes? content "Assigned child task:")
                          "```clojure\n(def leaves (map-lm (mapv (fn [i] {:id i}) (range 6)) \"leaf echo\" :edn))\n(FINAL leaves)\n```"

                          :else
                          "```clojure\n(FINAL :unexpected)\n```")))
        cfg (process/config {:max-leaf-concurrency 2
                             :scripted/response-fn response-fn})
        result (process/run-process! cfg {:dir dir :id "root" :kind :root :task "leaf concurrency stress"})]
    (is (= :final (:status result)))
    (is (<= @max-active-leaves 2))
    (is (= 0 @active-leaves))))

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
    ;; Projections (usage.edn/final.edn) materialize at checkpoint, not per call.
    ;; Child first: the parent's tree rollup reads the child's usage.edn.
    (artifacts/checkpoint! child-state)
    (artifacts/checkpoint! parent-state)
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
                            (= scope (cache/provider-scope "root" :agent))
                            (case (swap! root-count inc)
                              1 "```clojure\n(lm {:x 1} \"fail\")\n```"
                              "```clojure\n(FINAL :repaired)\n```")
                            (and (= scope (cache/provider-scope "root" :leaf))
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

(deftest provider-complete-uses-current-sdk-chat-contract
  (let [captured (atom nil)
        cfg (process/config {:models {:root {:provider :fake :model "fake-model"}}})
        request {:request/messages [{:message/role :user
                                     :message/content "hello"}]
                 :request/cache {:scope-id "fractal:test"}}]
    (with-redefs [sdk-http/request (fn [req]
                                     (reset! captured req)
                                     {:status 200 :body {}})]
      (let [resp (provider/complete cfg :root request)]
        (is (= "Hello from fake provider."
               (provider/response-text resp)))
        (is (= :fake (:response/provider resp)))
        (is (= "fake-model" (:request/model (:body @captured))))
        (is (= [{:message/role :user :message/content "hello"}]
               (:request/messages (:body @captured))))
        (is (= {:scope-id "fractal:test"}
               (:request/cache (:body @captured))))))))

(deftest retry-is-on-by-default-and-recovers-from-transient-transport-failure
  ;; Retrying transient transport failures is default behavior, not a flag. The
  ;; SDK classifies before retrying, so this never re-fails a broken request --
  ;; it is exactly what survives the vertex-gemini first-call EOF.
  (is (true? (:retry (process/config))) "config defaults :retry on")
  (let [attempts (atom 0)
        cfg (process/config {:models {:root {:provider :fake :model "fake-model"}}})
        request {:request/messages [{:message/role :user :message/content "hi"}]
                 :request/cache {:scope-id "fractal:test"}}]
    ;; Drive retry without real backoff sleeps.
    (binding [sdk/*retry-sleep-fn* (fn [_ms] nil)]
      (with-redefs [sdk-http/request
                    (fn [_req]
                      (if (= 1 (swap! attempts inc))
                        ;; A dropped connection on the first attempt -- the EOF class.
                        (throw (java.io.IOException. "EOF reached while reading"))
                        {:status 200 :body {}}))]
        (let [resp (provider/complete cfg :root request)]
          (is (= 2 @attempts) "failed once, retried, then succeeded")
          (is (= "Hello from fake provider." (provider/response-text resp)))))))
  (testing ":retry false opts back into one-shot (the throw propagates, no retry)"
    (let [attempts (atom 0)
          cfg (process/config {:retry false
                               :models {:root {:provider :fake :model "fake-model"}}})
          request {:request/messages [{:message/role :user :message/content "hi"}]
                   :request/cache {:scope-id "fractal:test"}}]
      (binding [sdk/*retry-sleep-fn* (fn [_ms] nil)]
        (with-redefs [sdk-http/request
                      (fn [_req]
                        (swap! attempts inc)
                        (throw (java.io.IOException. "EOF reached while reading")))]
          (is (thrown? Exception (provider/complete cfg :root request)))
          (is (= 1 @attempts) "one-shot: no retry"))))))

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
  (testing "run honors explicit session names"
    (let [runs-dir (tmp-dir "named-runs")
          out (with-out-str
                (cli/run-command {:fake-script "simple"
                                  :question "define x"
                                  :runs-dir runs-dir
                                  :session "named"}))
          dir (artifacts/path runs-dir "named")
          session-row (artifacts/read-edn-file (artifacts/path dir "session.edn") {})]
      (is (clojure.string/includes? out (str dir)))
      (is (= "named" (:session/id session-row)))
      (is (= :stopped (:session/status session-row)))))
  (testing "chat processes two submitted messages in one session"
    (let [out (with-in-str "first\n/send\nsecond\n/send\n"
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
      (is (every? #(<= (count %) 64) (map :call/cache-scope calls)))
      (is (= #{"fractal:"} (set (map #(subs (:call/cache-label %) 0 8) calls))))))
  (testing "resume adds another turn to existing history"
    (let [dir (tmp-dir "resume-command")
          s (session/start-session! (scripted-cfg ["```clojure\n(def saved 99)\n(FINAL {:saved saved})\n```"])
                                    {:dir dir :id "root"})
          _ (session/run-turn! s "save")
          _ (session/stop-session! s)
          source-fingerprint (snapshot/session-fingerprint dir)
          result (resume/resume! (scripted-cfg ["```clojure\n(FINAL {:restored saved})\n```"])
                                 dir
                                 "restore")
          resume-dir (str (:dir result))
          source-turns (artifacts/read-edn-file (artifacts/path dir "turns.edn") [])
          resume-turns (artifacts/read-edn-file (artifacts/path resume-dir "turns.edn") [])
          restore-report (artifacts/read-edn-file (artifacts/path resume-dir "restore.edn") {})
          lineage (artifacts/read-edn-file (artifacts/path resume-dir "lineage.edn") {})]
      (is (= {:restored 99} (:final-value result)))
      (is (= 1 (count source-turns)))
      (is (= 1 (count resume-turns)))
      (is (= :snapshot-vars (:restore/strategy restore-report)))
      (is (= :resume (:lineage/kind lineage)))
      (is (= source-fingerprint (snapshot/session-fingerprint dir)))))
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

(deftest turn-final-snapshots-capture-vars-and_refs
  (let [dir (tmp-dir "snapshot")
        result (process/run-process!
                (scripted-cfg ["```clojure\n(def small {:a 1})\n(def big (vec (range 5000)))\n(def f (fn [x] x))\n(def proc (Object.))\n(FINAL :done)\n```"])
                {:dir dir :id "root" :kind :root :task "snapshot"})
        snapshots (artifacts/read-edn-file (artifacts/path dir "snapshots.edn") [])
        last-snapshot (last snapshots)
        blob (snapshot/read-snapshot-blob dir last-snapshot)
        vars-by-name (zipmap (map :var/name (:snapshot/vars blob))
                             (:snapshot/vars blob))]
    (is (= :done (:final-value result)))
    (is (= 1 (count snapshots)))
    (is (= :turn-final (:snapshot/kind last-snapshot)))
    (is (= 1 (:snapshot/turn-id last-snapshot)))
    (is (= :blob (get-in last-snapshot [:snapshot/ref :value/kind])))
    (is (.exists (java.io.File. dir "blobs/snapshots/turn-0001.edn")))
    (is (= :inline (get-in vars-by-name ["small" :var/value-ref :value/kind])))
    (is (= :blob (get-in vars-by-name ["big" :var/value-ref :value/kind])))
    (is (= 5000 (count (artifacts/read-ref dir (get-in vars-by-name ["big" :var/value-ref])))))
    (is (= :function (get-in vars-by-name ["f" :var/reason])))
    (is (= :not-data (get-in vars-by-name ["proc" :var/reason])))
    (is (nil? (get vars-by-name "FINAL")))
    (is (nil? (get vars-by-name "lm")))
    (is (nil? (get vars-by-name "attach-rlm")))))

(deftest snapshot-selection-and-missing-snapshot-errors
  (let [dir (tmp-dir "snapshot-select")
        s (session/start-session!
           (scripted-cfg ["```clojure\n(def x 1)\n(FINAL x)\n```"
                          "```clojure\n(def x 2)\n(FINAL x)\n```"])
           {:dir dir :id "root"})
        _ (session/run-turn! s "one")
        _ (session/run-turn! s "two")
        resumed (session/resume-session!
                 (scripted-cfg ["```clojure\n(FINAL {:selected x})\n```"])
                 dir
                 {:turn 1})
        selected (session/run-turn! resumed "selected")
        default-resumed (session/resume-session!
                         (scripted-cfg ["```clojure\n(FINAL {:latest x})\n```"])
                         dir)
        latest (session/run-turn! default-resumed "latest")]
    (is (= 2 (:snapshot/turn-id (snapshot/latest-turn-snapshot dir))))
    (is (= 1 (:snapshot/turn-id (snapshot/snapshot-for-turn dir 1))))
    (is (= {:selected 1} (:final-value selected)))
    (is (= {:latest 2} (:final-value latest))))
  (let [empty-dir (tmp-dir "no-snapshot")]
    (session/start-session! (scripted-cfg []) {:dir empty-dir :id "empty"})
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"No completed turn snapshot"
                          (session/resume-session! (scripted-cfg []) empty-dir)))))

(deftest session-fingerprint-is-canonical-and-lightweight
  (let [dir (tmp-dir "fingerprint")
        s (session/start-session! (scripted-cfg ["```clojure\n(def x 1)\n(FINAL x)\n```"])
                                  {:dir dir :id "root"})
        _ (session/run-turn! s "save")
        before (snapshot/session-fingerprint dir)]
    ;; Non-canonical writes (blobs, and now the derived table projections like
    ;; messages.edn) do not move the fingerprint; only the journal does.
    (artifacts/write-edn! (artifacts/path dir "blobs/arbitrary.edn")
                          {:not :canonical})
    (artifacts/write-edn! (artifacts/path dir "messages.edn")
                          (conj (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
                                {:message/id 999 :message/role :observation :message/content "derived"}))
    (is (= before (snapshot/session-fingerprint dir)))
    ;; Appending to the journal (the source of truth) does move the fingerprint.
    (journal/append! dir {:event/type :message/added :event/id 999
                          :message {:message/id 999
                                    :message/role :observation
                                    :message/content "canonical change"}})
    (let [after-message (snapshot/session-fingerprint dir)]
      (is (not= before after-message))
      (artifacts/new-state! {:dir (artifacts/path dir "children/child-0001")
                             :id "child-0001"
                             :kind :child
                             :provider {}})
      (let [after-child (snapshot/session-fingerprint dir)]
        (is (not= after-message after-child))
        (artifacts/write-edn! (artifacts/path dir "children/child-0001/blobs/ignored.edn")
                              {:large (apply str (repeat 5000 "x"))})
        (is (= after-child (snapshot/session-fingerprint dir)))))))

(deftest provider-descriptors-drive-auth
  ;; provider-as-value: a provider's auth is data in one table, not scattered code
  ;; branches and prose gotchas.
  (is (= :none (:auth (provider/descriptor :scripted))))
  (is (= :api-key (:auth (provider/descriptor :openai))))
  (is (= :oauth-file (:auth (provider/descriptor :codex-backend))))
  (is (= :adc (:auth (provider/descriptor :vertex-gemini))))
  (is (= :sdk-default (:auth (provider/descriptor :nonexistent-provider))))
  ;; api-key-config supplies a key only for :api-key providers; other auth sources
  ;; are the SDK's job, so the engine hands over nothing (env-independent asserts).
  (is (nil? (provider/api-key-config :codex-backend)))
  (is (nil? (provider/api-key-config :scripted)))
  ;; :none auth is always satisfied; the shape is uniform data.
  (is (true? (:satisfied? (provider/auth-status :scripted))))
  (is (= :api-key (:auth (provider/auth-status :openai)))))

(deftest per-call-timeout-finalizes-turn-as-failure
  ;; A hung provider call is invisible until something declares it dead. The
  ;; per-call deadline converts it into a normal provider failure that finalizes
  ;; the turn, with :provider/timeout preserved under :error/data.
  (let [dir (tmp-dir "timeout")
        result (process/run-process!
                (process/config
                 {:call-timeout-ms 50
                  :scripted/response-fn
                  (fn [_]
                    (Thread/sleep 1000)
                    "```clojure\n(FINAL :too-late)\n```")})
                {:dir dir :id "root" :kind :root :task "go"})
        calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])
        final (artifacts/read-edn-file (artifacts/path dir "final.edn") {})]
    (is (= :error (:status result)))
    (is (= :provider/failed (get-in result [:error :error/type])))
    (is (= :provider/timeout (get-in result [:error :error/data :error/type])))
    (is (= :error (:call/status (first calls))))
    (is (= :error (:final/status final)))))

(deftest journal-fold-reconstructs-state-without-replay
  ;; The load-bearing invariant: the journal stores results, not recipes.
  ;; apply-event is a pure projector — folding events.ednl reconstructs the exact
  ;; materialized state, and re-invokes the provider zero times.
  (let [dir (tmp-dir "journal-fold")
        provider-calls (atom 0)
        cfg (process/config
             {:scripted/response-fn
              (fn [_]
                (swap! provider-calls inc)
                "```clojure\n(def y 7)\n(FINAL {:y y})\n```")})
        result (process/run-process! cfg {:dir dir :id "root" :kind :root :task "go"})
        calls-during-run @provider-calls
        ;; reconstruct purely from the journal, nothing else
        view (event/fold (journal/read-events dir))
        read* (fn [f] (artifacts/read-edn-file (artifacts/path dir f) nil))]
    (is (= {:y 7} (:final-value result)))
    (is (pos? calls-during-run))
    ;; the fold reproduces every materialized projection exactly
    (is (= (read* "session.edn") (:session view)))
    (is (= (read* "messages.edn") (:messages view)))
    (is (= (read* "turns.edn") (:turns view)))
    (is (= (read* "evals.edn") (:evals view)))
    (is (= (read* "calls.edn") (:calls view)))
    (is (= (read* "events.edn") (:events view)))
    ;; and it cost nothing: reconstruction never re-ran a single provider call
    (is (= calls-during-run @provider-calls))))

(deftest resume-restores-vars-and_messages_without_replay
  (let [dir (tmp-dir "resume-snapshot")
        calls (atom 0)
        cfg (process/config
             {:scripted/response-fn
              (fn [request]
                (swap! calls inc)
                (let [content (:message/content (last (:request/messages request)))]
                  (cond
                    (clojure.string/includes? content "source expensive")
                    "```clojure\n(def expensive (lm \"input\" \"query\"))\n(FINAL {:done true})\n```"
                    (clojure.string/includes? content "query")
                    "expensive-value"
                    :else
                    "```clojure\n(FINAL {:expensive expensive})\n```")))})
        source (session/start-session! cfg {:dir dir :id "root"})
        _ (session/run-turn! source "source expensive")
        source-call-count @calls
        source-fingerprint (snapshot/session-fingerprint dir)
        resumed (session/resume-session! cfg dir)
        restore-call-count @calls
        result (session/run-turn! resumed "use restored")
        resume-dir (str (:dir resumed))
        messages (artifacts/read-edn-file (artifacts/path resume-dir "messages.edn") [])
        restore-report (artifacts/read-edn-file (artifacts/path resume-dir "restore.edn") {})]
    (is (= 2 source-call-count))
    (is (= source-call-count restore-call-count))
    (is (= {:expensive "expensive-value"} (:final-value result)))
    (is (= :snapshot-vars (:restore/strategy restore-report)))
    (is (= [1 2 3 4] (mapv :message/id (take 4 messages))))
    (is (= source-fingerprint (snapshot/session-fingerprint dir)))))

(deftest fork-writes-lineage-and-restores-vars
  (let [dir (tmp-dir "fork-source")
        fork-dir (tmp-dir "fork-derived")
        s (session/start-session! (scripted-cfg ["```clojure\n(def x 42)\n(FINAL {:x x})\n```"])
                                  {:dir dir :id "root"})
        _ (session/run-turn! s "save")
        forked (session/fork-session! (scripted-cfg ["```clojure\n(FINAL {:forked x})\n```"])
                                      dir
                                      fork-dir)
        result (session/run-turn! forked "fork")
        lineage (artifacts/read-edn-file (artifacts/path fork-dir "lineage.edn") {})]
    (is (= {:forked 42} (:final-value result)))
    (is (= :fork (:lineage/kind lineage)))
    (is (= 1 (get-in lineage [:lineage/source :source/turn-id])))
    (is (= 1 (get-in lineage [:lineage/source :source/snapshot-id])))))

(deftest attach-rlm-restores_source_child_without_replay
  (let [source-dir (tmp-dir "attach-source")
        source (session/start-session! (scripted-cfg ["```clojure\n(def x 42)\n(FINAL {:x x})\n```"])
                                       {:dir source-dir :id "source"})
        _ (session/run-turn! source "save x")
        source-fingerprint (snapshot/session-fingerprint source-dir)
        parent-dir (tmp-dir "attach-parent")
        provider-calls (atom 0)
        cfg (process/config
             {:scripted/response-fn
              (fn [request]
                (swap! provider-calls inc)
                (let [content (:message/content (last (:request/messages request)))]
                  (cond
                    (clojure.string/includes? content "attach source")
                    (str "```clojure\n"
                         "(def child (attach-rlm " (pr-str source-dir)
                         " \"return child-x\"))\n"
                         "(FINAL {:child child})\n"
                         "```")
                    (clojure.string/includes? content "return child-x")
                    "```clojure\n(FINAL {:child-x x})\n```"
                    :else
                    "```clojure\n(FINAL :unexpected)\n```")))})
        result (process/run-process! cfg {:dir parent-dir :id "parent" :kind :root :task "attach source"})
        child-dir (artifacts/path parent-dir "children/attached-0001")
        child-restore (artifacts/read-edn-file (artifacts/path child-dir "restore.edn") {})
        child-lineage (artifacts/read-edn-file (artifacts/path child-dir "lineage.edn") {})
        calls (artifacts/read-edn-file (artifacts/path parent-dir "calls.edn") [])]
    (is (= {:child {:child-x 42}} (:final-value result)))
    (is (.exists (java.io.File. (str child-dir) "session.edn")))
    (is (= :snapshot-vars (:restore/strategy child-restore)))
    (is (= :attached-child (:lineage/kind child-lineage)))
    (is (= 1 (get-in child-lineage [:lineage/source :source/turn-id])))
    (is (= 1 (get-in child-lineage [:lineage/source :source/snapshot-id])))
    (is (some #(= :attached-child (:call/type %)) calls))
    (is (= 2 @provider-calls))
    (is (= source-fingerprint (snapshot/session-fingerprint source-dir)))))

(deftest inspector-shows_snapshots_tree_handles_and_open_problems
  (let [dir (tmp-dir "inspect")
        s (session/start-session! (scripted-cfg ["```clojure\n(def x 42)\n(FINAL x)\n```"])
                                  {:dir dir :id "root"})
        _ (session/run-turn! s "save")
        _ (artifacts/new-state! {:dir (artifacts/path dir "children/child-0001")
                                 :id "child-0001"
                                 :kind :child
                                 :provider {}})
        human (inspect/summary-string dir {:tree true :snapshots true :handles true})
        structured (inspect/structured dir {:tree true :snapshots true :handles true})]
    (is (clojure.string/includes? human "snapshots"))
    (is (clojure.string/includes? human "tree"))
    (is (clojure.string/includes? human "snapshot:"))
    (is (seq (:snapshots structured)))
    (is (seq (get-in structured [:handles :snapshots])))
    (is (some #(= :no-completed-turn-snapshot (:kind %))
              (:open-problems structured)))))

;; ── inspector surface (projection / provenance / render / agent CLI) ──────────
;;
;; One event stream, two renderings (SPEC §5). These tests build a real run with
;; the scripted provider — a root that spawns one child which FINALs an evidenced
;; claim plus a fabricated one citing a temp source file we control — then exercise
;; the read-side substrate end to end. Hermetic: no dependency on gitignored runs/.

(defn- widget-source! []
  ;; A real file the child's evidence will cite. Distinctive code tokens so the
  ;; claim-vs-evidence grep has something true to find — and something false to miss.
  (let [f (java.io.File/createTempFile "widget" ".py")]
    (spit f (str "class WidgetEngine:\n"
                 "    def compute_widget_total(self, items):\n"
                 "        return sum(quantize_widget(i) for i in items)\n"))
    (.getAbsolutePath f)))

(defn- build-inspector-run! []
  (let [dir  (tmp-dir "inspector")
        src  (widget-source!)
        resp (fn [request]
               (let [content (str (:message/content (last (:request/messages request))))]
                 (cond
                   ;; the child investigating the widget file
                   (clojure.string/includes? content "WIDGET-CHILD")
                   (str "```clojure\n(FINAL {:name \"widget\"\n"
                        "  :risks [{:description \"real risk\""
                        "           :evidence \"" src ": compute_widget_total iterates items via WidgetEngine and quantize_widget\"}\n"
                        "          {:description \"hallucinated risk\""
                        "           :evidence \"" src ": nonexistent_quantum_handler invokes flux_capacitor_drainer\"}]})\n```")
                   ;; the root, after the child returns: finalize
                   (clojure.string/includes? content "Observation")
                   "```clojure\n(FINAL {:done true :child kid})\n```"
                   ;; the root's first step: spawn the child
                   :else
                   "```clojure\n(def kid (rlm \"WIDGET-CHILD investigate the widget source file\"))\n{:spawned true}\n```")))
        cfg  (process/config {:scripted/response-fn resp})
        s    (session/start-session! cfg {:dir dir :id "root"})]
    (session/run-turn! s "Analyze the widget service.")
    (session/stop-session! s)
    {:dir dir :src src}))

(deftest projection-folds-journal-into-addressable-tree
  (let [{:keys [dir]} (build-inspector-run!)
        root (projection/load-node dir)]
    (testing "root node folds from the journal (not the .edn projections)"
      (is (= "root" (:address root)))
      (is (= :root (:kind root)))
      (is (pos? (count (:steps root))))
      (is (= 1 (count (:children root))) "one child spawned"))
    (testing "child address resolves and drills"
      (let [caddr (:address (first (:children root)))
            child (projection/load-at dir caddr)]
        (is (= "root/child-0001" caddr))
        (is (= :child (:kind child)))
        (is (map? (:final child)))
        (is (= "widget" (:name (:final child))))))
    (testing "node-dir resolves nested addresses, nil for bad ones"
      (is (some? (projection/node-dir dir "root")))
      (is (some? (projection/node-dir dir "root/child-0001")))
      (is (nil? (projection/node-dir dir "root/child-9999"))))
    (testing "tree is recursive and counts match"
      (let [t (projection/tree dir)]
        (is (= 1 (count (:children t))))
        (is (= "root/child-0001" (:address (first (:children t)))))))))

(deftest provenance-claim-vs-evidence-catches-confabulation
  (let [{:keys [dir src]} (build-inspector-run!)
        final (:final (projection/load-at dir "root/child-0001"))
        checks (provenance/check-claims final)
        by-label (into {} (map (juxt :label identity) checks))]
    (testing "every evidenced claim is extracted and parsed"
      (is (= 2 (count checks)))
      (is (every? #(= src (:file %)) checks)))
    (testing "a grounded claim is supported"
      (is (= :supported (:verdict (get by-label "real risk")))))
    (testing "a fabricated claim is flagged unsupported"
      (is (= :unsupported (:verdict (get by-label "hallucinated risk"))))
      (is (seq (get-in (get by-label "hallucinated risk") [:identifiers :missing]))))
    (testing "summary surfaces the confabulation"
      (let [s (provenance/summarize checks)]
        (is (true? (:confabulation-suspected s)))
        (is (= :suspect (:overall s)))))
    (testing "a missing file is reported, not crashed"
      (let [c (provenance/check-claims {:evidence "/no/such/file.py: anything"})]
        (is (= :file-missing (:verdict (first c))))))))

(deftest render-produces-formatted-recursive-read-surface
  (binding [render/*color* false]
    (let [{:keys [dir]} (build-inspector-run!)]
      (testing "tree renders addresses and counts"
        (let [t (render/tree-str dir)]
          (is (clojure.string/includes? t "root"))
          (is (clojure.string/includes? t "child-0001"))))
      (testing "node view ends with drill commands for children"
        (let [out (render/node-str (projection/load-node dir)
                                   {:exe "fractal" :run "widget"})]
          (is (clojure.string/includes? out "children (1)"))
          (is (clojure.string/includes? out "fractal show widget child-0001"))))
      (testing "verify view names verdicts"
        (let [final (:final (projection/load-at dir "root/child-0001"))
              out (render/verify-str "root/child-0001" final)]
          (is (clojure.string/includes? out "supported"))
          (is (clojure.string/includes? out "UNSUPPORTED")))))))

(deftest agentcli-dispatch-verbs-and-exit-codes
  (let [{:keys [dir]} (build-inspector-run!)]
    (testing "arg parsing splits positionals and flags"
      (is (= {:pos ["show" "run" "child-0001"] :flags {:json true}}
             (agentcli/parse-args ["show" "run" "child-0001" "--json"]))))
    (testing "node-address normalizes the implied root/ prefix"
      (is (= "root" (agentcli/node-address nil)))
      (is (= "root" (agentcli/node-address "root")))
      (is (= "root/child-0001" (agentcli/node-address "child-0001")))
      (is (= "root/child-0001" (agentcli/node-address "root/child-0001"))))
    (testing "show resolves a run by path and exits 0 when a final exists"
      (let [{:keys [out exit]} (agentcli/dispatch "show" [dir])]
        (is (= 0 exit))
        (is (clojure.string/includes? out "node root"))))
    (testing "drilling a child works and exit reflects its final"
      (let [{:keys [exit]} (agentcli/dispatch "show" [dir "child-0001"])]
        (is (= 0 exit))))
    (testing "verify exits 5 when confabulation is suspected"
      (let [{:keys [out exit]} (agentcli/dispatch "verify" [dir "child-0001"])]
        (is (= 5 exit))
        (is (clojure.string/includes? out "UNSUPPORTED"))))
    (testing "--json emits parseable output"
      (let [{:keys [out]} (agentcli/dispatch "show" [dir "child-0001" "--json"])
            parsed (cheshire.core/parse-string out true)]
        (is (= "root/child-0001" (:address parsed)))))
    (testing "stream emits one JSON object per journal event"
      (let [{:keys [out]} (agentcli/dispatch "stream" [dir])
            lines (clojure.string/split-lines out)]
        (is (pos? (count lines)))
        (is (every? #(map? (cheshire.core/parse-string %)) lines))))
    (testing "unknown verb and missing run are usage errors (exit 1)"
      (is (= 1 (:exit (agentcli/dispatch "bogus" []))))
      (is (= 1 (:exit (agentcli/dispatch "show" ["/no/such/run"])))))))

(deftest agentcli-drive-verbs-and-chat-pieces
  (testing "run drives the engine and reports a chainable run name + exit by final"
    (let [dir (tmp-dir "drive")
          {:keys [out exit]} (agentcli/dispatch
                              "run" ["save x" "--fake-script" "simple"
                                     "--runs-dir" dir "--name" "r1"])]
      (is (= 0 exit))
      (is (clojure.string/includes? out "run r1"))
      (is (clojure.string/includes? out "fractal show r1"))
      (testing "the produced run reads back through the same surface"
        (let [{:keys [exit]} (agentcli/dispatch "show" ["r1" "--runs-dir" dir])]
          (is (= 0 exit))))))
  (testing "run --json yields a parseable result with the final value"
    (let [dir (tmp-dir "drive-json")
          {:keys [out]} (agentcli/dispatch
                         "run" ["save x" "--fake-script" "simple"
                                "--runs-dir" dir "--name" "rj" "--json"])
          parsed (cheshire.core/parse-string out true)]
      (is (= "rj" (:run parsed)))
      (is (= 42 (get-in parsed [:final :answer])))))
  (testing "progress + turn summary render from a real run"
    (let [{:keys [dir]} (build-inspector-run!)
          counts (render/progress-counts dir)
          root   (projection/load-node dir)]
      (is (= 1 (:children counts)) "one child spawned")
      (is (pos? (:steps counts)))
      (is (clojure.string/includes? (render/progress-line counts) "thinking"))
      (binding [render/*color* false]
        (let [summary (render/turn-summary-str
                       root {:dir dir :status :final :final-value (:final root)}
                       {:exe "fractal" :run "demo"})]
          (is (clojure.string/includes? summary "●"))
          (is (clojure.string/includes? summary "fractal show demo")))))))

(deftest codebrain-overlay-and-persistent-brain
  ;; codebrain is a session-level overlay + persistent (disk-backed) brain on top
  ;; of the engine. We prove the wiring with the scripted provider: birth builds
  ;; and persists a map, and a later ask — in the SAME way a fresh CLI process
  ;; would — resumes the brain and computes from the WARM `repo-map` var, which
  ;; only exists if the overlay'd session was restored.
  (testing "the overlay is a single combined system message (provider-agnostic)"
    (let [dir (tmp-dir "cb-overlay")
          s   (session/start-session!
               (process/config {:runs-dir dir})
               {:id "ov" :dir (str dir "/ov") :overlay "OVERLAY-MARKER"})
          systems (filter #(= :system (:message/role %)) (:messages @(:state s)))]
      (session/stop-session! s)
      (is (= 1 (count systems)) "exactly one system message, not two")
      (is (clojure.string/includes? (:message/content (first systems)) "OVERLAY-MARKER"))
      (is (clojure.string/includes? (:message/content (first systems)) "FINAL")
          "the base behavior is still present under the overlay")))
  (testing "init builds + persists a map, ask resumes the warm var, status tracks HEAD"
    (let [dir (tmp-dir "cb-brain")
          init (agentcli/dispatch "codebrain"
                                  ["init" "--path" dir "--fake-script" "codebrain" "--runs-dir" dir])]
      (is (= 0 (:exit init)))
      (is (clojure.string/includes? (:out init) "codebrain born"))
      (let [m (fractal-engine.codebrain/load-map (fractal-engine.codebrain/brain-dir dir))]
        (is (= 1 (count (:subsystems m))) "the built map was persisted to disk"))
      ;; a separate dispatch call = a fresh cfg/atom, like a new CLI process
      (let [ask (agentcli/dispatch "codebrain"
                                   ["ask" "how many subsystems?" "--fake-script" "codebrain" "--runs-dir" dir])]
        (is (= 0 (:exit ask)))
        (is (clojure.string/includes? (:out ask) "the map knows 1 subsystem")
            "the answer was computed from the resumed, warm repo-map var"))
      (let [{:keys [out exit]} (agentcli/dispatch "codebrain" ["status" "--runs-dir" dir])]
        (is (= 0 exit))
        (is (clojure.string/includes? out "turns  1"))
        (is (clojure.string/includes? out "t0001")))))
  (testing "ask before init is an actionable error"
    (let [dir (tmp-dir "cb-empty")
          {:keys [exit out]} (agentcli/dispatch "codebrain" ["ask" "x" "--runs-dir" dir])]
      (is (= 1 exit))
      (is (clojure.string/includes? out "build one first")))))
