(ns fractal.engine.payload-test
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.payload :as p]))

(deftest canonical-bytes-deterministic
  (testing "map key order does not affect canonical bytes"
    (is (= (seq (p/canonical-bytes {:a 1 :b 2 :c 3}))
           (seq (p/canonical-bytes (array-map :c 3 :b 2 :a 1)))))
    (is (= (seq (p/canonical-bytes {:x {:b 2 :a 1}}))
           (seq (p/canonical-bytes {:x {:a 1 :b 2}})))))
  (testing "set member order does not affect canonical bytes"
    (is (= (seq (p/canonical-bytes #{3 1 2}))
           (seq (p/canonical-bytes #{1 2 3})))))
  (testing "print-length/level never truncates the hashing basis"
    (binding [*print-length* 3 *print-level* 1]
      (is (= (seq (p/canonical-bytes (vec (range 100))))
             (seq (p/canonical-bytes (vec (range 100)))))))))

(deftest content-addressing-dedup
  (testing "identical values produce the identical sha256: ref (dedup)"
    (let [v {:answer 42 :items (vec (range 50))}]
      (is (= (p/ref-for v :final) (p/ref-for v :final)))
      (is (= (p/content-id v) (p/content-id (into {} v))))))
  (testing "the ref id is sha256: over canonical bytes"
    (let [v [1 2 3]
          r (p/ref-for v :eval-result)]
      (is (= :payload (:fractal/ref r)))
      (is (= (str "sha256:" (p/sha256-hex (p/canonical-bytes v))) (:payload/id r)))
      (is (= :eval-result (:payload/kind r)))
      (is (= (alength (p/canonical-bytes v)) (:payload/size r)))))
  (testing "distinct values differ"
    (is (not= (p/content-id [1 2 3]) (p/content-id [1 2 4])))))

(deftest payload-ref-discriminates
  (is (p/payload-ref? (p/ref-for {:a 1} :message)))
  (is (p/payload-ref? {:fractal/ref :payload :payload/id "sha256:abc"}))
  (is (not (p/payload-ref? {:a 1})))
  (is (not (p/payload-ref? "a string")))
  (is (not (p/payload-ref? nil)))
  (is (not (p/payload-ref? [1 2 3]))))
