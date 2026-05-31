(ns fractal-engine.api-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal-engine.api :as fe]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.prompt :as prompt]))

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
