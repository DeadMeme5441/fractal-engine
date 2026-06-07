(ns fractal.engine.concurrent-test
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.concurrent :as c]))

(deftest returns-value-when-in-time
  (is (= 42 (c/with-deadline 5000 (+ 40 2)))))

(deftest fires-on-timeout
  (testing "a slow body trips the deadline with a namespaced error"
    (let [ex (try (c/with-deadline 50 (Thread/sleep 5000) :never)
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo ex))
      (is (= :fractal/deadline (:error/type (ex-data ex)))))))

(deftest propagates-body-exceptions
  (testing "an exception thrown by the body surfaces on the caller thread"
    (is (thrown-with-msg? RuntimeException #"boom"
          (c/with-deadline 5000 (throw (RuntimeException. "boom")))))))

(deftest worker-is-daemon
  (testing "the deadline worker is a daemon thread (cannot pin JVM exit)"
    ;; If with-deadline used a non-daemon thread, a still-running orphaned
    ;; worker would keep the JVM alive. We assert the thread is a daemon by
    ;; capturing it from inside the body.
    (let [captured (promise)]
      (c/with-deadline 5000 (deliver captured (Thread/currentThread)))
      (is (.isDaemon ^Thread @captured)))))

;; ---------------------------------------------------------------------------
;; bounded-fanout (Phase 3)
;; ---------------------------------------------------------------------------

(deftest bounded-fanout-preserves-order
  (testing "results are index-aligned to inputs regardless of completion order"
    (let [out (c/bounded-fanout "t" 8 [10 20 30 40 50]
                (fn [_i x] (Thread/sleep (- 50 x)) (* x 2)))]  ; smaller x sleeps longer
      (is (= [{:ok true :index 0 :value 20}
              {:ok true :index 1 :value 40}
              {:ok true :index 2 :value 60}
              {:ok true :index 3 :value 80}
              {:ok true :index 4 :value 100}]
             out)))))

(deftest bounded-fanout-empty
  (is (= [] (c/bounded-fanout "t" 8 [] (fn [_ _] :nope)))))

(deftest bounded-fanout-partial-failure-never-throws
  (testing "a throwing slot is captured as {:ok false …}; others still succeed"
    (let [out (c/bounded-fanout "t" 4 [1 2 3]
                (fn [_i x] (if (= x 2)
                             (throw (ex-info "bad" {:error/type :boom}))
                             (* x 100))))]
      (is (= 100 (:value (nth out 0))))
      (is (false? (:ok (nth out 1))))
      (is (= :boom (:error/type (:error (nth out 1)))))
      (is (= 300 (:value (nth out 2)))))))

(def ^:dynamic *probe* :unbound)

(deftest bounded-fanout-propagates-dynamic-bindings
  (testing "bound-fn carries the caller thread's dynvar bindings onto workers"
    (binding [*probe* :carried]
      (let [out (c/bounded-fanout "t" 4 [0 1 2 3]
                  (fn [_i _x] *probe*))]
        (is (every? #(= :carried (:value %)) out)
            "every worker saw the caller's dynamic binding")))))

(deftest bounded-fanout-respects-pool-bound
  (testing "no more than pool-size workers run concurrently"
    (let [live (atom 0) peak (atom 0)]
      (c/bounded-fanout "t" 3 (range 12)
        (fn [_i _x]
          (let [n (swap! live inc)]
            (swap! peak max n)
            (Thread/sleep 20)
            (swap! live dec))))
      (is (<= @peak 3) (str "peak concurrency " @peak " must be <= pool bound 3")))))

(deftest with-permit-bounds-concurrency
  (testing "a semaphore bounds concurrent work inside the permit"
    (let [sem (c/semaphore 2) live (atom 0) peak (atom 0)]
      (c/bounded-fanout "t" 8 (range 10)
        (fn [_i _x]
          (c/with-permit sem
            (fn [] (let [n (swap! live inc)] (swap! peak max n) (Thread/sleep 15) (swap! live dec))))))
      (is (<= @peak 2) (str "peak " @peak " must be <= permit count 2")))
    (testing "nil semaphore means unbounded passthrough"
      (is (= :ok (c/with-permit nil (fn [] :ok)))))))
