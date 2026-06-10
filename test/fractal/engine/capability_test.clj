(ns fractal.engine.capability-test
  (:require [clojure.test :refer [deftest testing is]]
            [sci.core :as sci]
            [fractal.engine.capability :as cap]))

(def ^:private engine-fns
  {:FINAL   (fn [v] (throw (ex-info "FINAL" {:fractal/final v})))
   :inspect (fn [x] x)
   :lm      (fn [& _] :lm) :map-lm (fn [& _] :map-lm)
   :rlm     (fn [& _] :rlm) :map-rlm (fn [& _] :map-rlm)
   :attach-rlm (fn [& _] :attach-rlm)})

(defn- ctx-for
  ([profile] (ctx-for profile {}))
  ([profile surface-namespaces]
   (sci/init (cap/sci-opts profile engine-fns surface-namespaces))))

(defn- run [ctx code]
  (try {:val (sci/eval-string* ctx code)}
       (catch Throwable e
         {:thrown? true
          :err (some #(:error/type (ex-data %))
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
      (is (= #{:FINAL :inspect} (:engine-fns c)))
      (is (= #{} (:surface/fns c)))))
  (testing "clamp(trusted, default) tightens trusted down to default's gates"
    (let [c (cap/clamp (cap/trusted) (cap/default-profile))]
      (is (map? (:cap/fs-read c)) ":allow ∧ {:paths} = {:paths}")
      (is (= :deny (:cap/fs-write c)))
      (is (= :deny (:cap/network c)))
      (is (= (:commands (:cap/shell (cap/default-profile))) (:commands (:cap/shell c))))))
  (testing "path meet keeps only the mutually-contained prefix"
    (let [a {:capability/name :a :cap/fs-read {:paths ["/work"]} :cap/fs-write :deny
             :cap/shell :deny :cap/network :deny :ns/granted #{} :cap/java-classes {}
             :engine-fns #{} :surface/fns '#{jira/search}}
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

(deftest surface-function-capabilities-are-finite-and-inherited
  (testing ":surface/fns must be a finite set of qualified symbols"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"finite set"
          (cap/validate-profile! (assoc (cap/default-profile) :surface/fns :all))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"qualified symbols"
          (cap/validate-profile! (assoc (cap/default-profile) :surface/fns '#{search})))))
  (testing "child clamps intersect surface function grants"
    (let [parent (assoc (cap/default-profile) :surface/fns '#{jira/search jira/issue})
          child  (assoc (cap/default-profile) :surface/fns '#{jira/search git/status})
          c (cap/clamp parent child)]
      (is (= '#{jira/search} (:surface/fns c)))))
  (testing "override loosening is rejected"
    (let [base (assoc (cap/default-profile) :surface/fns '#{jira/search})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"loosens"
            (cap/resolve-override base
                                  (assoc (cap/default-profile)
                                         :capability/name :surface-widen
                                         :surface/fns '#{jira/search jira/issue})))))))

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
;; the :all class sentinel (explicit unsafe-marked interop opt-out)
;; ---------------------------------------------------------------------------

(def ^:private all-classes-profile
  {:capability/name   :custom-unsafe-all
   :capability/unsafe true
   :cap/java-classes  :all
   :cap/fs-read :deny :cap/fs-write :deny :cap/shell :deny :cap/network :deny
   :ns/granted '#{clojure.core} :engine-fns #{:FINAL :inspect} :surface/fns #{}})

(deftest all-classes-sentinel-validation
  (testing ":all without :capability/unsafe is rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsafe"
          (cap/validate-profile! (dissoc all-classes-profile :capability/unsafe)))))
  (testing "the raw SCI {:allow :all} MAP directive stays rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"class-name symbols"
          (cap/validate-profile! (assoc all-classes-profile :cap/java-classes {:allow :all})))))
  (testing ":all cannot ride on the built-in profile names (unsafe is rejected there)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsafe"
          (cap/validate-profile! (assoc (cap/default-profile)
                                        :cap/java-classes :all
                                        :capability/unsafe true)))))
  (testing "a well-formed :all profile validates"
    (is (= :all (:cap/java-classes (cap/validate-profile! all-classes-profile))))))

(deftest all-classes-sentinel-lattice
  (testing ":all is the top of the class lattice"
    (is (cap/profile<=? (cap/default-profile)
                        (assoc (cap/default-profile) :cap/java-classes :all)))
    (is (not (cap/profile<=? (assoc (cap/default-profile) :cap/java-classes :all)
                             (cap/default-profile)))))
  (testing ":all ∧ finite = finite (a's live values kept when a is finite)"
    (let [finite (assoc all-classes-profile
                        :capability/name :finite
                        :cap/java-classes {'java.lang.Math java.lang.Math})
          c (cap/clamp all-classes-profile finite)]
      (is (= {'java.lang.Math java.lang.Math} (:cap/java-classes c)))
      (is (not (:capability/unsafe c)) "no dangerous classes left ⇒ marker dropped")))
  (testing ":all ∧ :all = :all, and the unsafe marker survives (clamp revalidates)"
    (let [c (cap/clamp all-classes-profile all-classes-profile)]
      (is (= :all (:cap/java-classes c)))
      (is (true? (:capability/unsafe c)))
      (is (map? (cap/validate-profile! c)))))
  (testing "unsafe marker survives a finite clamp that still carries a dangerous class"
    (let [p (assoc all-classes-profile
                   :capability/name :u-thread
                   :cap/java-classes {'java.lang.Thread java.lang.Thread})
          c (cap/clamp p p)]
      (is (true? (:capability/unsafe c)))
      (is (map? (cap/validate-profile! c)) "clamped unsafe profile revalidates"))))

(deftest all-classes-sentinel-runtime
  (testing "an :all profile resolves and uses real JDK classes"
    (let [ctx (ctx-for (cap/validate-profile! all-classes-profile))]
      (is (number? (:val (run ctx "(java.lang.System/currentTimeMillis)"))))
      (is (number? (:val (run ctx "(System/currentTimeMillis)")))
          "java.lang simple names are imported, like real Clojure")
      (is (inst? (:val (run ctx "(java.time.Instant/now)"))))
      (is (= "HI" (:val (run ctx "(.toUpperCase \"hi\")"))))))
  (testing "finite-class profiles still deny class-symbol RESOLUTION (the actual
            SCI gate — instance reflection on values already in hand is open in
            this pinned SCI version regardless of :classes)"
    (let [ctx (ctx-for (cap/default-profile))]
      (is (:thrown? (run ctx "(java.lang.System/currentTimeMillis)")))
      (is (:thrown? (run ctx "(System/currentTimeMillis)")))
      (is (:thrown? (run ctx "(java.time.Instant/now)")))
      (is (:thrown? (run ctx "(java.lang.Runtime/getRuntime)"))))))

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

(deftest surface-functions-are-denied-by-default-and-capability-gated
  (let [surface-ns {'jira {'search (fn [q] {:query q})}}
        denied (ctx-for (cap/default-profile) surface-ns)
        allowed (ctx-for (assoc (cap/default-profile) :surface/fns '#{jira/search})
                         surface-ns)]
    (is (:thrown? (run denied "(jira/search \"auth\")")))
    (is (= {:query "auth"} (:val (run allowed "(jira/search \"auth\")"))))))
