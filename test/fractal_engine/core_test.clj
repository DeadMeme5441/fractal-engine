(ns fractal-engine.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.process :as process]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.resume :as resume]))

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
  (is (clojure.string/includes? prompt/system-prompt "bounded")))

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
    (doseq [file ["session.edn" "messages.edn" "evals.edn" "calls.edn"
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
    (is (= 2 (count (artifacts/read-edn-file (artifacts/path dir "evals.edn") []))))
    (is (= :final (:final/status (artifacts/read-edn-file (artifacts/path dir "final.edn") {}))))))

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
      (is (= 2 (count (filter #(= :leaf-batch-item (:call/type %)) calls)))))))

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
    (is (.exists (java.io.File. dir "children/child-0002/session.edn")))
    (is (.exists (java.io.File. dir "children/child-0003/session.edn")))
    (let [calls (artifacts/read-edn-file (artifacts/path dir "calls.edn") [])]
      (is (= 1 (count (filter #(= :child (:call/type %)) calls))))
      (is (= 2 (count (filter #(= :child-batch-item (:call/type %)) calls)))))))

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
