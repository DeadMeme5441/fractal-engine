(ns fractal-engine.api-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal-engine.api :as fe]
            [fractal-engine.prompt :as prompt]))

(defn- req-last-user [request]
  (->> (:request/messages request)
       (filter #(= :user (:message/role %)))
       last :message/content str))

(defn- leaf-input [request]
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

(defn- wait-until [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 25) (recur))))))

(deftest public-api-drives-session-and-reads-canonical-store
  (let [runs-dir (tmp-dir "smoke")
        cfg (scripted-cfg runs-dir
                          ["```clojure\n(def x 42)\n(FINAL {:answer x})\n```"])
        s (fe/start-session! cfg {:id "demo" :store-root runs-dir})
        result (fe/run-turn! s "Define x and return it.")
        stopped (fe/stop-session! s)
        locator (:locator result)
        node (fe/load-node locator)
        loaded (fe/load-at locator "root")
        tree (fe/tree locator)
        provenance (fe/node-provenance locator "root")
        events (fe/event-stream locator)]
    (is (= :final (:status result)))
    (is (= {:answer 42} (:final-value result)))
    (is (= :stopped (:session/status stopped)))
    (is (= {:answer 42} (:final node)))
    (is (= (:final node) (:final loaded)))
    (is (= (:final node) (:final provenance)))
    (is (= "root" (:address tree)))
    (is (pos? (count events)))
    (is (= locator (fe/node-locator locator "root")))
    (is (= :ok (:status (fe/check-consistency runs-dir))))))

(deftest public-api-preserves-session-overlay-in-one-system-message
  (let [runs-dir (tmp-dir "overlay")
        marker "PUBLIC-API-OVERLAY-MARKER"
        cfg (fe/config {:runs-dir runs-dir})
        s (fe/start-session! cfg {:id "overlay"
                                  :store-root runs-dir
                                  :overlay marker})
        systems (filter #(= :system (:message/role %))
                        (:messages (fe/view (:locator s))))]
    (fe/stop-session! s)
    (is (= 1 (count systems)) "overlay is combined with the base prompt")
    (let [content (:message/content (first systems))]
      (is (str/starts-with? content prompt/system-prompt))
      (is (str/includes? content marker)))))

(deftest public-api-resumes-forks-and-runs-one-shot-tasks
  (let [runs-dir (tmp-dir "lifecycle")
        source (fe/start-session!
                (scripted-cfg runs-dir
                              ["```clojure\n(def saved 99)\n(FINAL {:saved saved})\n```"])
                {:id "source" :store-root runs-dir})
        source-result (fe/run-turn! source "save a var")]
    (fe/stop-session! source)
    (is (= {:saved 99} (:final-value source-result)))

    (let [resumed (fe/resume-session!
                   (scripted-cfg runs-dir
                                 ["```clojure\n(FINAL {:restored saved})\n```"])
                   "source")
          resume-result (fe/run-turn! resumed "restore saved")]
      (fe/stop-session! resumed)
      (is (= {:restored 99} (:final-value resume-result)))
      (is (= "source" (:session-id resume-result))))

	    (let [forked (fe/fork-session!
	                  (scripted-cfg runs-dir
	                                ["```clojure\n(FINAL {:forked saved})\n```"])
	                  "source"
	                  nil
	                  {:id "forked"})
	          fork-result (fe/run-turn! forked "use forked state")]
	      (fe/stop-session! forked)
	      (is (= {:forked 99} (:final-value fork-result)))
	      (is (= "forked" (:session-id fork-result)))
	      (is (some #(and (= :fork (:derivation/type %))
	                      (= "source" (:derivation/source-session %))
	                      (= "forked" (:derivation/target-session %)))
	                (fe/session-derivations runs-dir "forked"))))

    (let [one-shot (fe/run-task!
                    (scripted-cfg runs-dir
                                  ["```clojure\n(FINAL {:one-shot true})\n```"])
                    "one shot"
                    {:id "one-shot" :store-root runs-dir})]
      (is (= {:one-shot true} (:final-value one-shot)))
      (is (= :stopped (get-in (fe/view (:locator one-shot))
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
        response-fn (fe/scripted-responder
                     [["leaf echo"
                       (fn [req]
                         (let [{:keys [id]} (leaf-input req)
                               edn (pr-str {:id id :doubled (* 2 id)})]
                           (if (even? id) (str "```edn\n" edn "\n```") edn)))]
                      [:default
                       (str "```clojure\n"
                            "(FINAL (map-lm (mapv (fn [i] {:id i}) (range 4)) \"leaf echo\" :edn))\n"
                            "```")]])
        cfg (fe/config {:runs-dir runs-dir :scripted/response-fn response-fn})
        s (fe/start-session! cfg {:id "responder" :store-root runs-dir})
        result (fe/run-turn! s "fan out")
        progress (fe/progress (:locator s))]
    (is (= :final (:status result)))
    (is (= (mapv (fn [i] {:id i :doubled (* 2 i)}) (range 4))
           (:final-value result)))
    (testing "live progress reflects the settled run from canonical events"
      (is (= 4 (:leaves progress)))
      (is (true? (:final? progress)))
      (is (pos? (:steps progress)))
      (is (= 0 (get-in progress [:calls :running]))))
    (fe/stop-session! s)))

(deftest async-turn-live-progress-and-overlap_guard_use_canonical_events
  (let [gate (promise)
        runs-dir (tmp-dir "async")
        cfg (fe/config {:runs-dir runs-dir
                        :scripted/response-fn (fn [_]
                                                @gate
                                                "```clojure\n(FINAL :done)\n```")})
        s (fe/start-session! cfg {:id "async" :store-root runs-dir})
        result-p (fe/run-turn-async! s "go")]
    (is (wait-until #(pos? (get-in (fe/progress (:locator s)) [:calls :running] 0))
                    5000)
        "progress reads in-flight root calls from Datahike events")
    (is (thrown-with-msg? Exception #"already running"
                          (fe/run-turn! s "overlap")))
    (deliver gate :release)
    (is (= :done (:final-value (deref result-p 10000 :timeout))))
    (is (= :done (:final-value (fe/run-turn! s "again"))))
    (fe/stop-session! s)))

(deftest public-api-does-not-expand-model-facing-surface
  (let [runs-dir (tmp-dir "surface")
        s (fe/start-session! (fe/config {:runs-dir runs-dir})
                             {:id "surface" :store-root runs-dir})
        model-symbols ['FINAL 'lm 'map-lm 'rlm 'map-rlm 'attach-rlm]
        public-vars (set (keys (ns-publics (the-ns (:ns-sym s)))))]
    (fe/stop-session! s)
    (is (= (set model-symbols) public-vars))))
