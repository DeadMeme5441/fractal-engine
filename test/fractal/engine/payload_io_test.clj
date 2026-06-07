(ns fractal.engine.payload-io-test
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.payload :as payload]
            [fractal.engine.payload-io :as pio]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]))

(deftest maybe-intern-inline-vs-content-addressed
  (let [s (mem/memory-store)]
    (testing "scalars and small values inline (returned unchanged)"
      (is (= 42 (pio/maybe-intern s 42 {:payload/kind :eval-result})))
      (is (= :kw (pio/maybe-intern s :kw {:payload/kind :eval-result})))
      (is (= nil (pio/maybe-intern s nil {:payload/kind :eval-result})))
      (is (= true (pio/maybe-intern s true {:payload/kind :eval-result})))
      (is (= "short" (pio/maybe-intern s "short" {:payload/kind :message})))
      (is (= {:a 1 :b 2} (pio/maybe-intern s {:a 1 :b 2} {:payload/kind :eval-result}))))
    (testing "a large value is content-addressed into a tagged ref"
      (let [big (vec (range 1000))
            r   (pio/maybe-intern s big {:payload/kind :eval-result})]
        (is (payload/payload-ref? r))
        (is (= :eval-result (:payload/kind r)))
        (is (= big (store/read-payload* s r)) "round-trips")))
    (testing "a string longer than the inline limit is interned"
      (let [big (apply str (repeat 600 "x"))
            r   (pio/maybe-intern s big {:payload/kind :message})]
        (is (payload/payload-ref? r))
        (is (= big (store/read-payload* s r)))))))

(deftest dedup-identical-values
  (let [s (mem/memory-store)
        v (vec (range 1000))
        r1 (pio/maybe-intern s v {:payload/kind :eval-result})
        r2 (pio/maybe-intern s v {:payload/kind :eval-result})]
    (is (= r1 r2) "identical values intern to the identical ref")
    (is (= 1 (count @(:blobs s))) "deduplicated to one blob")))

(deftest read-payload-passthrough-and-deref
  (let [s (mem/memory-store)
        r (pio/maybe-intern s (vec (range 1000)) {:payload/kind :eval-result})]
    (is (= "plain" (pio/read-payload s "plain")) "non-ref passes through")
    (is (= 42 (pio/read-payload s 42)))
    (is (= (vec (range 1000)) (pio/read-payload s r)) "ref dereferences")))

(deftest hydrate-message-renames-and-derefs
  (let [s (mem/memory-store)
        big (apply str (repeat 600 "y"))
        ref (pio/maybe-intern s big {:payload/kind :message})]
    (testing "inline content"
      (is (= {:message/role :user :message/content "hi"}
             (pio/hydrate-message s {:message/role :user :message/content-or-ref "hi"}))))
    (testing "ref content is dereferenced and renamed"
      (let [h (pio/hydrate-message s {:message/role :assistant :message/content-or-ref ref})]
        (is (= big (:message/content h)))
        (is (not (contains? h :message/content-or-ref)))))))

(deftest edn-safe-coerces-unrestorable
  (let [coerced (pio/edn-safe {:n 1 :f inc :v [1 2 inc]})]
    (is (= 1 (:n coerced)))
    (is (contains? (:f coerced) :fractal/unrestorable))
    (is (= 1 (get-in coerced [:v 0])))
    (is (contains? (nth (:v coerced) 2) :fractal/unrestorable))))
