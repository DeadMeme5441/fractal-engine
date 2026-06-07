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
