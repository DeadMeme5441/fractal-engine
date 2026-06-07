(ns fractal.engine.adapter-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fractal.engine.adapter :as adapter]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.adapter.sdk :as sdk]))

;; ---------------------------------------------------------------------------
;; FakeAdapter (05 §5)
;; ---------------------------------------------------------------------------

(def ^:private req
  {:model "fake-model"
   :messages [{:role :system :content "sys"}
              {:role :user :content "count the errors"}]})

(deftest fake-returns-well-formed-record-with-honest-unknown
  (let [a (fake/fake-adapter (fn [_] "```clojure (FINAL :ok)```"))
        rec (adapter/-complete a req nil)]
    (is (= "```clojure (FINAL :ok)```" (:text rec)))
    (is (= :stop (:finish-reason rec)))
    (is (= :fake (:provider rec)))
    (is (= "fake-model" (:model rec)))
    (testing "absent usage/cost/cache are honest :unknown, never 0"
      (is (= :unknown (:usage/status (:usage rec))))
      (is (= :unknown (:cost/usd (:cost rec))))
      (is (= :unknown (:cache/status (:cache rec)))))))

(deftest fake-accepts-a-full-call-record
  (let [full {:text "x" :finish-reason :stop :provider :fake :model "m"
              :usage {:usage/status :known :usage/input-tokens 10 :usage/output-tokens 5}
              :cost {:cost/status :known :cost/usd 0.01} :cache adapter/unknown-cache}
        a (fake/fake-adapter (fn [_] full))]
    (is (= full (adapter/-complete a req nil)))))

(deftest responder-matches-and-defaults
  (let [respond (fake/responder
                  [["count the errors" "first-reply"]
                   [#(str/includes? (or (fake/last-user %) "") "Observation") "obs-reply"]
                   [:default "default-reply"]])]
    (is (= "first-reply"   (respond req)))
    (is (= "obs-reply"     (respond {:messages [{:role :user :content "Observation:\n42"}]})))
    (is (= "default-reply" (respond {:messages [{:role :user :content "anything else"}]})))))

(deftest responder-throws-without-a-match
  (let [respond (fake/responder [["never" "x"]])]
    (is (thrown? clojure.lang.ExceptionInfo (respond {:messages [{:role :user :content "hi"}]})))))

;; ---------------------------------------------------------------------------
;; SdkAdapter mapping (05 §6) — no network; synthetic SDK shapes
;; ---------------------------------------------------------------------------

(deftest sdk-request-uses-namespaced-message-keys
  (let [r (sdk/->sdk-request {:model "claude" :cache {:enabled? true :ttl "1h" :scope-id "fr:agent:x"}
                              :messages [{:role :system :content "s"} {:role :user :content "u"}]})]
    (is (= "claude" (:request/model r)))
    (is (= [{:message/role :system :message/content "s"}
            {:message/role :user :message/content "u"}] (:request/messages r)))
    (is (= {:enabled? true :ttl "1h" :scope-id "fr:agent:x"} (:request/cache r)))))

(deftest sdk-request-omits-nil-cache
  (is (not (contains? (sdk/->sdk-request {:model "m" :messages []}) :request/cache))))

(deftest sdk-response-mapping-known
  (let [resp {:response/provider :anthropic
              :response/model "claude-x"
              :response/parts [{:part/type :text :text "hello "} {:part/type :text :text "world"}]
              :response/finish-reason :stop
              :response/usage {:usage/input-tokens 100 :usage/output-tokens 20
                               :usage/cached-input-tokens 80}
              :response/cost {:cost/usd 0.0123 :cost/estimated? false}
              :response/cache {:cache/status :hit :cache/cached-tokens 80 :cache/cache-write-tokens 0}}
        rec (sdk/sdk-response->call-record resp {:model "claude-x"})]
    (is (= "hello world" (:text rec)))
    (is (= :anthropic (:provider rec)))
    (is (= :stop (:finish-reason rec)))
    (is (= :known (:usage/status (:usage rec))))
    (is (= 100 (:usage/input-tokens (:usage rec))))
    (is (= :known (:cost/status (:cost rec))))
    (is (= 0.0123 (:cost/usd (:cost rec))))
    (is (= :hit (:cache/status (:cache rec)))) "bare :cache key (GD30)"))

(deftest sdk-response-mapping-honest-unknown
  (testing "absent usage/cost/cache map to honest :unknown"
    (let [resp {:response/provider :perplexity :response/model "sonar"
                :response/parts [{:part/type :text :text "x"}]
                :response/finish-reason :stop}
          rec (sdk/sdk-response->call-record resp {:model "sonar"})]
      (is (= :unknown (:usage/status (:usage rec))))
      (is (= :unknown (:cost/usd (:cost rec))))
      (is (= :unknown (:cache/status (:cache rec))))))
  (testing "an :unknown cost usd is marked status :unknown"
    (let [resp {:response/provider :x :response/model "m"
                :response/parts [{:part/type :text :text "x"}]
                :response/finish-reason :stop
                :response/cost {:cost/usd :unknown :cost/estimated? true}}
          rec (sdk/sdk-response->call-record resp {:model "m"})]
      (is (= :unknown (:cost/status (:cost rec)))))))
