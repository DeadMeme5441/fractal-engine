(ns fractal.engine.facts-pins-test
  "88j · the store-scoped embedder fact layer: opaque tagged facts (ordered,
   persisted, NEVER interpreted), named pins with CAS + ref validation, and the
   read projections (delegation-report, verify-claim). The same assertions run
   against BOTH store impls (port-level equivalence, like store_contract_test)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.projection :as projection]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]
            [fractal.engine.store.sqlite :as sqlite]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-facts" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

;; ---------------------------------------------------------------------------
;; The shared invariants
;; ---------------------------------------------------------------------------

(defn- check-facts-append-and-since [s]
  (let [f1 (store/append-fact! s {:fact/tag :memo/created :fact/value {:text "alpha"}})
        f2 (store/append-fact! s {:fact/tag :run/status :fact/value :pending})]
    (testing "facts stamp monotonic ids + timestamps"
      (is (= [1 2] (map :fact/id [f1 f2])))
      (is (every? :fact/at [f1 f2])))
    (testing "facts-since serves the ordered tail"
      (is (= [:memo/created :run/status] (map :fact/tag (store/facts-since s 0))))
      (is (= [:run/status] (map :fact/tag (store/facts-since s 1))))
      (is (empty? (store/facts-since s 99))))
    (testing "the engine never interprets — arbitrary value shapes round-trip"
      (is (= {:text "alpha"} (:fact/value (first (store/facts-since s 0))))))))

(defn- check-fact-validation [s]
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fact"
        (store/append-fact! s {:fact/tag "not-kw" :fact/value 1})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"fact"
        (store/append-fact! s {:fact/tag :no-value})))
  (testing "a fact referencing an un-persisted payload is rejected (dangling)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"payload"
          (store/append-fact! s {:fact/tag :bad
                                 :fact/value {:fractal/ref :payload
                                              :payload/id "sha256:nope"
                                              :payload/kind :x :payload/size 1}}))))
  (testing "a fact referencing a persisted payload is accepted"
    (let [r (store/intern-payload! s (vec (range 500)) {:payload/kind :memo})]
      (is (some? (store/append-fact! s {:fact/tag :good :fact/value {:body r}}))))))

(defn- check-pins-cas-and-validation [s]
  (let [sid "pin-sess"]
    (store/create-session! s {:session/id sid})
    (store/append-event! s sid {:event/type :session/started
                                :session {:session/id sid :session/status :running}})
    (let [head (store/publish-head! s sid {:head/kind :turn-final :head/to-event-id 1
                                           :head/turn-id 1 :head/vars-ref nil :head/final-ref :v})]
      (testing "create + read + list"
        (let [p (store/pin! s {:pin/name :incumbent
                               :pin/ref {:session/id sid :head/id (:head/id head)}
                               :pin/meta {:why "test"}})]
          (is (= 1 (:pin/version p)))
          (is (= p (store/read-pin s :incumbent)))
          (is (= ["incumbent"] (map store/pin-key (store/list-pins s))))))
      (testing "CAS: expected-version nil means MUST NOT EXIST"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pin version"
              (store/pin! s {:pin/name :incumbent :pin/ref :x :pin/expected-version nil}))))
      (testing "CAS: matching expected-version updates; stale rejected"
        (let [p2 (store/pin! s {:pin/name :incumbent :pin/ref :updated :pin/expected-version 1})]
          (is (= 2 (:pin/version p2))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pin version"
              (store/pin! s {:pin/name :incumbent :pin/ref :again :pin/expected-version 1}))))
      (testing "unconditional upsert (no expected key) always lands"
        (is (= 3 (:pin/version (store/pin! s {:pin/name :incumbent :pin/ref :forced})))))
      (testing "a pin to an unpublished head is rejected"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unpublished head"
              (store/pin! s {:pin/name :bogus
                             :pin/ref {:session/id sid :head/id "sha256:never"}}))))
      (testing "pin name normalization: kw and string address the same pin"
        (store/pin! s {:pin/name "stringly" :pin/ref 1})
        (is (= 1 (:pin/ref (store/read-pin s :stringly)))))
      (testing "list-pins survives MIXED kw/string names (review regression)"
        (is (vector? (store/list-pins s))
            "sorting normalizes names — no ClassCastException on kw vs string"))
      (testing "a pin to an UNKNOWN session fails typed, never NPEs (review regression)"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unpublished head"
              (store/pin! s {:pin/name :ghost
                             :pin/ref {:session/id "no-such-session" :head/id "sha256:x"}})))))))

(defn- check-non-edn-values-coerced [s]
  ;; review regression: a non-EDN member must be COERCED at write (the opaque
  ;; edn-safe marker), never written raw — one raw #object would permanently
  ;; poison every later facts-since/list-pins read on sqlite.
  (let [f (store/append-fact! s {:fact/tag :weird :fact/value {:obj (Object.)}})]
    (is (contains? (get-in f [:fact/value :obj]) :fractal/unrestorable))
    (is (= (:fact/value f) (:fact/value (last (store/facts-since s 0))))
        "the coerced value round-trips through the store"))
  (let [p (store/pin! s {:pin/name :weird :pin/ref (Object.)})]
    (is (contains? (:pin/ref p) :fractal/unrestorable))
    (is (= (:pin/ref p) (:pin/ref (store/read-pin s :weird))))))

(deftest memory-store-facts-pins
  (doseq [[nm f] [["facts" check-facts-append-and-since]
                  ["fact-validation" check-fact-validation]
                  ["pins" check-pins-cas-and-validation]
                  ["non-edn-coercion" check-non-edn-values-coerced]]]
    (testing (str "MemoryStore · " nm)
      (f (mem/memory-store)))))

(deftest sqlite-store-facts-pins
  (let [root (temp-dir!) stores (atom [])]
    (try
      (doseq [[nm f] [["facts" check-facts-append-and-since]
                      ["fact-validation" check-fact-validation]
                      ["pins" check-pins-cas-and-validation]
                      ["non-edn-coercion" check-non-edn-values-coerced]]]
        (testing (str "SqliteStore · " nm)
          (let [s (sqlite/sqlite-store {:dir (io/file root (str (gensym "s")))})]
            (swap! stores conj s)
            (f s))))
      (testing "facts + pins survive close + reopen (durability)"
        (let [dir (io/file root "durable")
              a (sqlite/sqlite-store {:dir dir})]
          (store/append-fact! a {:fact/tag :survives :fact/value 1})
          (store/pin! a {:pin/name :kept :pin/ref :v})
          (sqlite/close! a)
          (let [b (sqlite/sqlite-store {:dir dir})]
            (swap! stores conj b)
            (is (= [:survives] (map :fact/tag (store/facts-since b 0))))
            (is (= :v (:pin/ref (store/read-pin b :kept)))))))
      (finally
        (doseq [s @stores] (try (sqlite/close! s) (catch Throwable _ nil)))
        (rm-rf! root)))))

;; ---------------------------------------------------------------------------
;; Projections over a real recursive run
;; ---------------------------------------------------------------------------

(deftest delegation-report-and-verify-claim
  (let [resp (fn [req]
               (if (str/includes? (or (fake/last-user req) "") "Assigned task:")
                 "```clojure\n(FINAL {:lane :done})\n```"
                 "```clojure\n(FINAL (mapv :rlm/value (map-rlm [\"lane a\" \"lane b\"])))\n```"))
        s (fe/start-session! (fe/make-config {:adapter :fake :fake/respond resp
                                              :model "fake-model" :harness :rlm}))
        res (fe/run-turn! s "fan out")]
    (is (= [{:lane :done} {:lane :done}] (:turn/final-value res)))
    (let [report (fe/delegation-report s (:turn/id res))]
      (testing "the report sees both children with status + per-child rollups"
        (is (= 2 (count (:delegation/children report))))
        (is (every? #(= 1 (:child/turns %)) (:delegation/children report)))
        (is (= :unknown (get-in report [:delegation/children-cost :cost/usd]))
            "fake adapter reports no cost ⇒ honest :unknown, never 0"))
      (testing "verify-claim confirms real facts and rejects invented ones"
        (let [edge (first (:delegation/edges report))
              csid (:edge/to-session edge)]
          (is (:verified? (fe/verify-claim s {:session/id (:session-id s) :edge/id (:edge/id edge)})))
          (is (:verified? (fe/verify-claim s {:session/id csid})))
          (is (:verified? (fe/verify-claim s {:session/id csid :head/id (:edge/to-head edge)})))
          (is (not (:verified? (fe/verify-claim s {:session/id csid :head/id "sha256:invented"}))))
          (is (not (:verified? (fe/verify-claim s {:session/id "no-such-session"}))))
          (is (not (:verified? (fe/verify-claim s "Foo.java:12"))) "a bare string is NOT evidence"))))
    (fe/stop-session! s)))
