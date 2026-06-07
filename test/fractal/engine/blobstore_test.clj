(ns fractal.engine.blobstore-test
  "The file-based, content-addressed blob store (02 §3, §9): byte-for-byte the same
   addressing as MemoryStore (sha256 over payload/canonical-bytes), idempotent
   dedup, faithful EDN round-trip (incl. #inst/#uuid and list-vs-vector identity),
   and a missing blob reads back nil (so dangling refs are detectable)."
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.payload :as payload]
            [fractal.engine.store.blobstore :as blob]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-blob" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defmacro with-bs [[bs] & body]
  `(let [d# (temp-dir!)
         ~bs (blob/open d#)]
     (try ~@body (finally (rm-rf! d#)))))

(deftest id-matches-payload-content-addressing
  (with-bs [bs]
    (let [v {:b 2 :a 1 :nested [1 2 3]}
          ref (blob/put! bs v {:payload/kind :final})]
      (is (= :payload (:fractal/ref ref)))
      (is (= :final (:payload/kind ref)))
      (is (= (payload/content-id v) (:payload/id ref))
          "id is sha256: over canonical-bytes — uniform with MemoryStore"))))

(deftest round-trips-equal-value
  (with-bs [bs]
    (doseq [v [42 "hello" :kw 'sym true nil 3.14 1/3 7N
               {:x [1 2 3] :y #{:a :b}} [1 [2 [3]]] '(1 2 3)
               {:status :ok :value '(1 2 3)}]]
      (let [ref (blob/put! bs v {:payload/kind :vars})]
        (is (= v (blob/get* bs ref)) (str "round-trips: " (pr-str v)))))
    (testing "list-vs-vector identity is preserved (restore must not re-evaluate)"
      (is (seq? (blob/get* bs (blob/put! bs '(1 2 3) {:payload/kind :vars}))))
      (is (vector? (blob/get* bs (blob/put! bs [1 2 3] {:payload/kind :vars})))))))

(deftest inst-and-uuid-round-trip
  (with-bs [bs]
    (let [u (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
          d (java.util.Date. 1000000000000)]
      (is (= u (blob/get* bs (blob/put! bs u {:payload/kind :final}))))
      (is (= d (blob/get* bs (blob/put! bs d {:payload/kind :final})))))))

(deftest dedup-single-node
  (with-bs [bs]
    (let [v (vec (range 1000))
          r1 (blob/put! bs v {:payload/kind :final})
          r2 (blob/put! bs v {:payload/kind :final})]
      (is (= r1 r2))
      (is (= 1 (blob/count-blobs bs)) "identical content ⇒ one on-disk node")
      (is (true? (blob/has? bs r1))))))

(deftest missing-blob-reads-nil
  (with-bs [bs]
    (let [ref (payload/ref-for (vec (range 5000)) :final)]   ; never written
      (is (false? (blob/has? bs ref)))
      (is (nil? (blob/get* bs ref)) "absent blob ⇒ nil (dangling-ref detectable)"))))
