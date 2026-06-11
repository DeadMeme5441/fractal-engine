(ns fractal.engine.snapshot-reopen-test
  "qbu · snapshot-at-head fold: reopen restores the latest head's view snapshot
   and folds only the tail, with view semantics preserved exactly — counters,
   kept-messages, entity vectors, ref integrity — while pre-head non-message
   events stay in SQL instead of RAM."
  (:require [clojure.string]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake]
            [fractal.engine.store :as store]
            [fractal.engine.store.sqlite :as sqlite]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-snap" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- responder
  "Turn 1 step 1: def. Turn 1 step 2 (last user = the OBSERVATION): FINAL.
   Turn 2 ('use'): FINAL from the restored var."
  [req]
  (let [lu (or (fractal.engine.adapter.fake/last-user req) "")]
    (cond
      (clojure.string/includes? lu "define then finish") "```clojure\n(def acc 40)\nacc\n```"
      (clojure.string/includes? lu "use")                "```clojure\n(FINAL (+ acc 2))\n```"
      :else                                              "```clojure\n(FINAL acc)\n```")))

(deftest snapshot-reopen-preserves-view-semantics
  (let [dir (temp-dir!) sid "snap-sess"]
    (try
      (let [cfg (fe/make-config {:adapter :fake :fake/respond responder
                                 :model "fake-model" :harness :rlm
                                 :store :sqlite :store/dir dir})
            h (fe/start-session! cfg {:id sid})]
        ;; turn 1: two steps (define → observation → finish ⇒ FINAL ⇒ head)
        (is (= 40 (:turn/final-value (fe/run-turn! h "define then finish"))))
        (fe/close-session! h)

        (let [s2 (sqlite/sqlite-store {:dir dir})]
          (try
            (store/create-session! s2 {:session/id sid})
            (let [snap-view (store/current-view s2 sid)     ; snapshot + tail fold
                  full-view (store/read-state s2 sid)]      ; always the full fold
              (testing "everything except the pruned :events window is identical"
                (is (= (dissoc full-view :events) (dissoc snap-view :events))))
              (testing "kept-messages — the request-assembly input — is identical"
                (is (= (store/kept-messages full-view) (store/kept-messages snap-view))))
              (testing "counters survive (deterministic id stamping continues)"
                (is (= (:counters full-view) (:counters snap-view))))
              (testing "the reopened :events window is BOUNDED (message-bearing only…)"
                (is (< (count (:events snap-view)) (count (:events full-view))))
                (is (every? #(#{:message/appended :session/compacted :head/published}
                              (:event/type %))
                            (:events snap-view))
                    "only message-bearing snapshot events + tail events in RAM"))
              (testing "no dangling refs through the snapshot path"
                (is (empty? (store/verify-no-dangling-refs s2 sid))))
              (testing "the durable log stays the truth: events-since serves everything"
                (is (= (count (:events full-view))
                       (count (store/events-since s2 sid 0))))))
            (finally (sqlite/close! s2))))

        (testing "a real resume on the snapshot path continues correctly"
          (let [h2 (fe/resume-session! cfg sid)]
            (is (= 42 (:turn/final-value (fe/run-turn! h2 "use")))
                "vars restored, ids continue, a second FINAL publishes a second head")
            (is (= 2 (count (:heads (fe/view h2)))))
            (fe/close-session! h2))))
      (finally (rm-rf! dir)))))

(deftest snapshot-view-prunes-exactly-the-derivation-window
  (let [base (reduce store/apply-event (store/empty-view)
                     [{:event/type :session/started :event/id 1 :session {:session/id "x"}}
                      {:event/type :message/appended :event/id 2
                       :message {:message/id 1 :message/role :user :message/content-or-ref "a"}}
                      {:event/type :step/started :event/id 3 :step {:step/id 1}}
                      {:event/type :message/appended :event/id 4
                       :message {:message/id 2 :message/role :assistant :message/content-or-ref "b"}}])]
    (testing "nil boundary keeps ALL message-bearing events, drops the rest"
      (let [s (store/snapshot-view base)]
        (is (= [2 4] (map :event/id (:events s))))
        (is (= (store/kept-messages base) (store/kept-messages s)))))
    (testing "a compact boundary prunes the pre-boundary window too"
      (let [v (assoc base :compact-from-event-id 4)
            s (store/snapshot-view v)]
        (is (= [4] (map :event/id (:events s))))
        (is (= (store/kept-messages v) (store/kept-messages s)))))))

(deftest legacy-log-without-snapshot-still-reopens
  ;; a session with events but NO published head (no snapshot row) must fall
  ;; back to the full fold — the pre-qbu recovery path, byte-for-byte.
  (let [dir (temp-dir!) sid "no-head"]
    (try
      (let [s (sqlite/sqlite-store {:dir dir})]
        (store/create-session! s {:session/id sid})
        (store/append-event! s sid {:event/type :session/started
                                    :session {:session/id sid :session/status :running}})
        (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})
        (sqlite/close! s))
      (let [s2 (sqlite/sqlite-store {:dir dir})]
        (try
          (store/create-session! s2 {:session/id sid})
          (let [v (store/current-view s2 sid)]
            (is (= 2 (count (:events v))) "full fold — nothing pruned without a snapshot")
            (is (= 1 (count (:turns v)))))
          (finally (sqlite/close! s2))))
      (finally (rm-rf! dir)))))
