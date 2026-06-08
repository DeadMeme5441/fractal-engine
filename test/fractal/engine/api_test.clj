(ns fractal.engine.api-test
  "The end-to-end usage example (06 §7) + the read/live surface."
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]))

(deftest end-to-end-example-06-7
  (let [cfg (fe/make-config {:adapter :fake
                             :fake/respond (fe/responder
                                             [[:default "```clojure (FINAL {:answer 42})```"]])
                             :model "fake-model"
                             :capability :default})
        s (fe/start-session! cfg)]
    (testing "blocking turn"
      (let [res (fe/run-turn! s "What is 6 times 7?")]
        (is (= :final (:status res)))
        (is (= {:answer 42} (:turn/final-value res)))))
    (testing "async + live subscription"
      (let [seen (atom [])
            unsub (fe/subscribe! s (fn [ev] (swap! seen conj (:event/type ev))))
            th (fe/run-turn-async! s "again")]
        (is (= {:answer 42} (:turn/final-value @(:promise th))))
        (Thread/sleep 50)
        (unsub)
        (is (some #{:turn/started} @seen) "subscriber saw live durable events")
        (is (some #{:step/started} @seen))))
    (fe/stop-session! s)))

(deftest progress-reflects-live-state
  (let [s (fe/start-session! (fe/make-config
                               {:adapter :fake :model "fake-model"
                                :fake/respond (fe/responder [[:default "```clojure (FINAL :ok)```"]])}))]
    (fe/run-turn! s "go")
    (let [p (fe/progress s)]
      (is (= (:session-id s) (:session/id p)))
      (is (= :running (:session/status p)))
      (is (= 1 (:turn-count p)))
      (is (pos? (:last-event-id p))))))

(deftest read-payload-hydrates-refs-and-passes-values
  (let [s (fe/start-session! (fe/make-config
                               {:adapter :fake :model "fake-model"
                                :fake/respond (fe/responder
                                                [[:default "```clojure (FINAL (vec (range 2000)))```"]])}))
        res (fe/run-turn! s "make a big value")]
    (testing "a large FINAL value is hydrated for the caller"
      (is (= (vec (range 2000)) (:turn/final-value res))))
    (testing "read-payload passes a non-ref through unchanged"
      (is (= 42 (fe/read-payload s 42))))))

(deftest events-since-tails-the-log
  (let [s (fe/start-session! (fe/make-config
                               {:adapter :fake :model "fake-model"
                                :fake/respond (fe/responder [[:default "```clojure (FINAL :ok)```"]])}))]
    (fe/run-turn! s "go")
    (let [all (fe/event-stream s)
          mid (:event/id (nth all 2))]
      (is (= (drop-while #(<= (:event/id %) mid) all)
             (fe/events-since s mid))))))
