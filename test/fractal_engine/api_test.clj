(ns fractal-engine.api-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal-engine.api :as fe]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.prompt :as prompt]))

(defn- req-last-user [request]
  (->> (:request/messages request)
       (filter #(= :user (:message/role %)))
       last :message/content str))

(defn- leaf-input
  "Pull the input EDN the engine embedded in a leaf request."
  [request]
  (-> (req-last-user request)
      (->> (re-find #"(?s)Input EDN:\n(.*?)\n\nQuery:"))
      second
      edn/read-string))

(defn- tmp-dir [name]
  (str (java.nio.file.Files/createTempDirectory
        (str "fractal-api-" name "-")
        (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- scripted-cfg
  ([responses] (scripted-cfg (tmp-dir "runs") responses))
  ([runs-dir responses]
   (fe/config {:runs-dir runs-dir
               :scripted/responses (atom (vec responses))})))

(deftest public-api-drives-session-and-reads-artifacts
  (let [runs-dir (tmp-dir "smoke")
        run-dir (str runs-dir "/demo")
        cfg (scripted-cfg runs-dir
                          ["```clojure\n(def x 42)\n(FINAL {:answer x})\n```"])
        s (fe/start-session! cfg {:id "demo" :dir run-dir})
        result (fe/run-turn! s "Define x and return it.")
        stopped (fe/stop-session! s)
        node (fe/load-node (:dir result))
        loaded (fe/load-at (:dir result) "root")
        tree (fe/tree (:dir result))
        provenance (fe/node-provenance (:dir result) "root")
        events (fe/journal-events (:dir result))]
    (is (= :final (:status result)))
    (is (= {:answer 42} (:final-value result)))
    (is (= :stopped (:session/status stopped)))
    (is (= {:answer 42} (:final node)))
    (is (= (:final node) (:final loaded)))
    (is (= (:final node) (:final provenance)))
    (is (= "root" (:address tree)))
    (is (pos? (count events)))
    (is (= (str (:dir result)) (str (fe/node-dir (:dir result) "root"))))))

(deftest public-api-preserves-session-overlay-in-one-system-message
  (let [runs-dir (tmp-dir "overlay")
        marker "PUBLIC-API-OVERLAY-MARKER"
        cfg (fe/config {:runs-dir runs-dir})
        s (fe/start-session! cfg {:id "overlay"
                                  :dir (str runs-dir "/overlay")
                                  :overlay marker})
        systems (filter #(= :system (:message/role %))
                        (:messages (fe/view (:dir s))))]
    (fe/stop-session! s)
    (is (= 1 (count systems)) "overlay is combined with the base prompt")
    (let [content (:message/content (first systems))]
      (is (str/starts-with? content prompt/system-prompt))
      (is (str/includes? content marker)))))

(deftest public-api-resumes-forks-and-runs-one-shot-tasks
  (let [runs-dir (tmp-dir "lifecycle")
        source-dir (str runs-dir "/source")
        source (fe/start-session!
                (scripted-cfg runs-dir
                              ["```clojure\n(def saved 99)\n(FINAL {:saved saved})\n```"])
                {:id "source" :dir source-dir})
        source-result (fe/run-turn! source "save a var")]
    (fe/stop-session! source)
    (is (= {:saved 99} (:final-value source-result)))

    (let [resumed (fe/resume-session!
                   (scripted-cfg runs-dir
                                 ["```clojure\n(FINAL {:restored saved})\n```"])
                   source-dir
                   {:id "resumed" :dir (str runs-dir "/resumed")})
          resume-result (fe/run-turn! resumed "restore saved")]
      (fe/stop-session! resumed)
      (is (= {:restored 99} (:final-value resume-result))))

    (let [forked (fe/fork-session!
                  (scripted-cfg runs-dir
                                ["```clojure\n(FINAL {:forked saved})\n```"])
                  source-dir
                  (str runs-dir "/forked"))
          fork-result (fe/run-turn! forked "use forked state")]
      (fe/stop-session! forked)
      (is (= {:forked 99} (:final-value fork-result))))

    (let [one-shot (fe/run-task!
                    (scripted-cfg runs-dir
                                  ["```clojure\n(FINAL {:one-shot true})\n```"])
                    "one shot"
                    {:id "one-shot" :dir (str runs-dir "/one-shot")})]
      (is (= {:one-shot true} (:final-value one-shot)))
      (is (= :stopped (get-in (fe/view (:dir one-shot))
                              [:session :session/status]))))))

(deftest public-api-exposes-trust-and-provider-data
  (let [final {:risk {:description "grounded"
                      :evidence "src/fractal_engine/api.clj: `start-session!` is public"}}
        checks (fe/check-claims final ".")
        summary (fe/summarize-claims checks)]
    (is (= 1 (:total summary)))
    (is (= :supported (:overall summary)))
    (is (= :none (:auth (fe/provider-descriptor :scripted))))
    (is (true? (:satisfied? (fe/auth-status :scripted))))))

(deftest scripted-responder-is-content-addressed-and-race-free
  (let [runs-dir (tmp-dir "responder")
        ;; The root spawns 8 leaves in parallel via map-lm; each leaf reply is
        ;; computed from ITS OWN input, so a correct ordered result proves matches
        ;; are by content, not by a shared queue position (which parallel fanout
        ;; would scramble). One leaf wraps its EDN in a fence to also exercise the
        ;; fence-tolerant parse.
        response-fn (fe/scripted-responder
                     [["leaf echo"
                       (fn [req]
                         (let [{:keys [id]} (leaf-input req)
                               edn (pr-str {:id id :doubled (* 2 id)})]
                           (if (even? id) (str "```edn\n" edn "\n```") edn)))]
                      [:default
                       (str "```clojure\n"
                            "(FINAL (map-lm (mapv (fn [i] {:id i}) (range 8)) \"leaf echo\" :edn))\n"
                            "```")]])
        cfg (fe/config {:runs-dir runs-dir :scripted/response-fn response-fn})
        s (fe/start-session! cfg {:id "responder" :dir (str runs-dir "/responder")})
        result (fe/run-turn! s "fan out")
        progress (fe/progress (:dir s))]
    (is (= :final (:status result)))
    (is (= (mapv (fn [i] {:id i :doubled (* 2 i)}) (range 8))
           (:final-value result))
        "each leaf matched its own input under parallel fanout")
    (testing "live progress reflects the settled run, ref-free"
      (is (= 8 (:leaves progress)))
      (is (true? (:final? progress)))
      (is (pos? (:steps progress)))
      (is (= 0 (get-in progress [:calls :running]))))
    (fe/stop-session! s)))

(deftest async-turn-delivers-result-and-guards-overlap
  (let [gate (promise)
        runs-dir (tmp-dir "async")
        cfg (fe/config {:runs-dir runs-dir
                        :scripted/response-fn (fn [_]
                                                @gate ; block the in-flight turn until released
                                                "```clojure\n(FINAL :done)\n```")})
        s (fe/start-session! cfg {:id "async" :dir (str runs-dir "/async")})
        result-p (fe/run-turn-async! s "go")]
    (testing "a turn in flight refuses an overlapping turn on the same handle"
      ;; run-turn-async! acquires the turn lock synchronously before returning, and
      ;; the gate keeps the turn parked in the provider, so this is deterministic.
      (is (thrown-with-msg? Exception #"already running"
                            (fe/run-turn! s "overlap"))))
    (deliver gate :release)
    (is (= :done (:final-value (deref result-p 10000 :timeout))))
    (testing "the lock is released after the turn settles"
      (is (= :done (:final-value (fe/run-turn! s "again")))))
    (fe/stop-session! s)))

(deftest public-api-does-not-expand-model-facing-surface
  (let [runs-dir (tmp-dir "surface")
        s (fe/start-session! (fe/config {:runs-dir runs-dir})
                             {:id "surface" :dir (str runs-dir "/surface")})
        model-symbols ['FINAL 'lm 'map-lm 'rlm 'map-rlm 'attach-rlm]
        mappings (ns-map (the-ns (:ns-sym s)))]
    (fe/stop-session! s)
    (is (every? #(contains? mappings %) model-symbols))
    (is (not (contains? mappings 'llm)))
    (is (not (contains? mappings 'map-llm)))
    (is (not (contains? mappings 'context)))))
