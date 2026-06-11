(ns fractal.engine.writer-lease-test
  "bj1 · the advisory writer lease: two live writer STORES on one sqlite dir
   collide LOUDLY on the same scope instead of corrupting the event-id sequence;
   reads stay cross-process-free; stale leases are reclaimable; a taken-over
   writer detects the loss on its next write."
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.store :as store]
            [fractal.engine.store.sqlite :as sqlite]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-lease" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- seed! [s sid]
  (store/create-session! s {:session/id sid})
  (store/append-event! s sid {:event/type :session/started
                              :session {:session/id sid :session/status :running}}))

(deftest second-writer-fails-loudly-first-reader-stays-free
  (let [dir (temp-dir!) sid "lease-1"]
    (try
      (let [a (sqlite/sqlite-store {:dir dir})]
        (seed! a sid)
        (let [b (sqlite/sqlite-store {:dir dir})]
          (store/create-session! b {:session/id sid})
          (testing "cross-process READS are free"
            (is (= 1 (count (store/events-since b sid 0))))
            (is (= sid (get-in (store/read-state b sid) [:session :session/id]))))
          (testing "a second WRITER on the same scope fails typed"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lease"
                  (store/append-event! b sid {:event/type :turn/started
                                              :turn {:turn/status :running}}))))
          (testing "the holder keeps writing"
            (is (some? (store/append-event! a sid {:event/type :turn/started
                                                   :turn {:turn/status :running}}))))
          (testing "a DIFFERENT scope is independently leasable by b"
            (seed! b "lease-other"))
          (testing "after the holder closes (releasing leases), b may write"
            (sqlite/close! a)
            ;; b's slot view is stale (a wrote turn/started after b folded) —
            ;; recreate b's slot from the durable log first.
            (let [b2 (sqlite/sqlite-store {:dir dir})]
              (store/create-session! b2 {:session/id sid})
              (is (some? (store/append-event! b2 sid {:event/type :step/started
                                                      :step {:step/status :running}})))
              (sqlite/close! b2)))
          (sqlite/close! b)))
      (finally (rm-rf! dir)))))

(deftest steal-is-the-explicit-crashed-writer-escape
  ;; review regression: a kill-9'd writer never runs close!, leaving a live-
  ;; looking lease for up to ttl — :writer-lease-steal? lets the operator
  ;; retake it NOW, and the dead writer's ghost fails loud if it ever returns.
  (let [dir (temp-dir!) sid "lease-steal"]
    (try
      (let [a (sqlite/sqlite-store {:dir dir})]
        (seed! a sid)                                   ; a holds the lease, "crashes" (no close!)
        (let [b (sqlite/sqlite-store {:dir dir :writer-lease-steal? true})]
          (store/create-session! b {:session/id sid})
          (is (some? (store/append-event! b sid {:event/type :turn/started
                                                 :turn {:turn/status :running}}))
              "the stealing writer takes the scope immediately")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lease lost"
                (store/append-event! a sid {:event/type :step/started
                                            :step {:step/status :running}}))
              "the ghost of the crashed writer detects the theft")
          (sqlite/close! b))
        (sqlite/close! a))
      (finally (rm-rf! dir)))))

(deftest stale-lease-takeover-and-loss-detection
  (let [dir (temp-dir!) sid "lease-stale"]
    (try
      (let [a (sqlite/sqlite-store {:dir dir :writer-lease-ttl-ms 50})]
        (seed! a sid)
        (Thread/sleep 80) ; a's heartbeat goes stale
        (let [b (sqlite/sqlite-store {:dir dir :writer-lease-ttl-ms 50})]
          (store/create-session! b {:session/id sid})
          (testing "a stale lease is taken over by the new writer"
            (is (some? (store/append-event! b sid {:event/type :turn/started
                                                   :turn {:turn/status :running}}))))
          (testing "the original writer detects the takeover on its next write"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"lease lost"
                  (store/append-event! a sid {:event/type :step/started
                                              :step {:step/status :running}}))))
          (sqlite/close! b))
        (sqlite/close! a))
      (finally (rm-rf! dir)))))
