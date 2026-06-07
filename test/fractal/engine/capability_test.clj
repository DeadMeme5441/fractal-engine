(ns fractal.engine.capability-test
  (:require [clojure.test :refer [deftest testing is]]
            [sci.core :as sci]
            [fractal.engine.capability :as cap]))

(def ^:private engine-fns
  {:FINAL   (fn [v] (throw (ex-info "FINAL" {:fractal/final v})))
   :inspect (fn [x] x)
   :lm      (fn [& _] :lm) :map-lm (fn [& _] :map-lm)
   :rlm     (fn [& _] :rlm) :map-rlm (fn [& _] :map-rlm)})

(defn- ctx-for [profile] (sci/init (cap/sci-opts profile engine-fns)))

(defn- run [ctx code]
  (try {:val (sci/eval-string* ctx code)}
       (catch Throwable e
         {:err (some #(:error/type (ex-data %))
                     (take-while some? (iterate #(some-> ^Throwable % .getCause) e)))})))

;; ---------------------------------------------------------------------------
;; clamp = the meet (04 §3)
;; ---------------------------------------------------------------------------

(deftest clamp-is-the-meet
  (testing "clamp(default, locked-down) collapses each gate to the restrictive one"
    (let [c (cap/clamp (cap/default-profile) (cap/locked-down))]
      (is (= :deny (:cap/fs-read c)))
      (is (= :deny (:cap/shell c)))
      (is (= :deny (:cap/network c)))
      (is (= #{:FINAL :inspect} (:engine-fns c)))))
  (testing "clamp(trusted, default) tightens trusted down to default's gates"
    (let [c (cap/clamp (cap/trusted) (cap/default-profile))]
      (is (map? (:cap/fs-read c)) ":allow ∧ {:paths} = {:paths}")
      (is (= :deny (:cap/fs-write c)))
      (is (= :deny (:cap/network c)))
      (is (= (:commands (:cap/shell (cap/default-profile))) (:commands (:cap/shell c))))))
  (testing "path meet keeps only the mutually-contained prefix"
    (let [a {:capability/name :a :cap/fs-read {:paths ["/work"]} :cap/fs-write :deny
             :cap/shell :deny :cap/network :deny :ns/granted #{} :cap/java-classes {} :engine-fns #{}}
          b (assoc a :cap/fs-read {:paths ["/work/sub"]})
          c (cap/clamp a b)]
      (is (= ["/work/sub"] (:paths (:cap/fs-read c))) "the narrower prefix /work/sub wins")
      (is (cap/profile<=? c a)))))

;; ---------------------------------------------------------------------------
;; override validation (04 §3, §4)
;; ---------------------------------------------------------------------------

(deftest override-loosening-is-rejected
  (testing "a :trusted override of a :default base loosens fs/shell/network ⇒ throw"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"loosens"
          (cap/resolve-override (cap/default-profile) :trusted))))
  (testing "a :locked-down override of a :default base is accepted (inherit-and-clamp)"
    (let [c (cap/resolve-override (cap/default-profile) :locked-down)]
      (is (= :deny (:cap/fs-read c)))
      (is (cap/profile<=? c (cap/default-profile))))))

(deftest profile-ordering
  (is (cap/profile<=? (cap/locked-down) (cap/default-profile)))
  (is (cap/profile<=? (cap/default-profile) (cap/trusted)))
  (is (not (cap/profile<=? (cap/trusted) (cap/default-profile))))
  (is (not (cap/profile<=? (cap/default-profile) (cap/locked-down)))))

;; ---------------------------------------------------------------------------
;; dangerous classes (04 §2)
;; ---------------------------------------------------------------------------

(deftest dangerous-classes-throw
  (testing "a dangerous class without :capability/unsafe is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dangerous"
          (cap/validate-profile! {:capability/name :custom
                                  :cap/java-classes {'java.lang.Runtime java.lang.Runtime}
                                  :cap/fs-read :deny :cap/fs-write :deny :cap/shell :deny
                                  :cap/network :deny :ns/granted #{} :engine-fns #{}}))))
  (testing ":default/:locked-down reject the :capability/unsafe marker outright"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsafe"
          (cap/validate-profile! (assoc (cap/default-profile) :capability/unsafe true))))))

;; ---------------------------------------------------------------------------
;; the gated runtime: :default reads a local file, refuses URL + git (04 §2, 10 §3)
;; ---------------------------------------------------------------------------

(deftest default-gates-at-runtime
  (let [ctx (ctx-for (cap/default-profile))]
    (testing "reads a file within the work area"
      (is (string? (:val (run ctx "(slurp \"deps.edn\")")))))
    (testing "refuses a URL slurp (network :deny)"
      (is (= :capability/denied (:err (run ctx "(slurp \"http://example.com/x\")")))))
    (testing "refuses a file outside the work area (path boundary)"
      (is (= :capability/denied (:err (run ctx "(slurp \"/etc/passwd\")")))))
    (testing "shell: an allowlisted command runs; git / python3 are refused"
      (is (map? (:val (run ctx "(sh \"echo\" \"hi\")"))))
      (is (= :capability/denied (:err (run ctx "(sh \"git\" \"status\")"))))
      (is (= :capability/denied (:err (run ctx "(sh \"python3\" \"-c\" \"print(1)\")")))))))

(deftest locked-down-denies-all-io
  (let [ctx (ctx-for (cap/locked-down))]
    (is (= :capability/denied (:err (run ctx "(slurp \"deps.edn\")"))))
    (is (= :capability/denied (:err (run ctx "(spit \"x.txt\" \"y\")"))))
    (is (= :capability/denied (:err (run ctx "(sh \"echo\" \"hi\")"))))))
