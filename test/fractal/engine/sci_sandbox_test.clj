(ns fractal.engine.sci-sandbox-test
  "The PINNED SCI regression test (04 §7). These facts are version-dependent and
   load-bearing — CI must block any org.babashka/sci bump on this file."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [sci.core :as sci]
            [fractal.engine.capability :as cap])
  (:import [java.io File]))

(def ^:private engine-fns
  {:FINAL   (fn [v] (throw (ex-info "FINAL" {:fractal/final v})))
   :inspect (fn [x] x)})

(defn- ctx-for [profile]
  (sci/init (cap/sci-opts (cap/validate-profile! profile) engine-fns)))

(defn- error-of
  "Run code; return the deepest informative throwable so we can inspect message
   + ex-data across SCI's wrapping (the original is the cause)."
  [ctx code]
  (try (sci/eval-string* ctx code) ::no-throw
       (catch Throwable e e)))

(defn- causes [^Throwable e]
  (take-while some? (iterate #(some-> ^Throwable % .getCause) e)))

(defn- any-error-type [e]
  (some #(:error/type (ex-data %)) (causes e)))

(defn- any-msg-match [e re]
  (boolean (some #(when-let [m (.getMessage ^Throwable %)] (re-find re m)) (causes e))))

(deftest interop-denied
  (let [ctx (ctx-for (cap/locked-down))]
    (testing "instance interop on a host-leaked File is denied (:classes {})"
      (sci/intern ctx (sci/find-ns ctx 'clojure.core) 'leaked-file (File. "x.txt"))
      (let [e (error-of ctx "(.getName leaked-file)")]
        (is (instance? Throwable e))
        (is (any-msg-match e #"(?i)not allowed"))
        (is (any-msg-match e #"File"))))
    (testing "static interop on System/Runtime is unresolvable"
      (is (any-msg-match (error-of ctx "(System/getProperty \"user.home\")")
                         #"(?i)resolve|not allowed"))
      (is (any-msg-match (error-of ctx "(Runtime/getRuntime)")
                         #"(?i)resolve|not allowed")))))

(deftest reader-eval-blocked
  (let [ctx (ctx-for (cap/locked-down))]
    (testing "#= reader-eval throws (*read-eval* stays false)"
      (is (any-msg-match (error-of ctx "#=(+ 1 2)") #"(?i)EvalReader|read-eval")))
    (testing "read-string of a #= form does not execute"
      (is (any-msg-match (error-of ctx "(read-string \"#=(+ 1 2)\")") #"(?i)EvalReader|read-eval")))))

(deftest deny-set-blocks-escapes
  (let [ctx (ctx-for (cap/locked-down))]
    (is (not= ::no-throw (error-of ctx "(eval '(+ 1 2))")))
    (is (not= ::no-throw (error-of ctx "(load-string \"(+ 1 2)\")")))
    (testing "requiring-resolve cannot reach an un-injected namespace"
      (is (not= ::no-throw (error-of ctx "(requiring-resolve 'clojure.java.shell/sh)"))))))

(deftest gated-slurp-shadow-survives-in-ns
  (let [ctx (ctx-for (cap/locked-down))]
    (testing "slurp resolves to the GATED fn (denied), not an unresolved symbol — even after in-ns"
      (let [e (error-of ctx "(in-ns 'model.scratch) (slurp \"/etc/passwd\")")]
        ;; the gated slurp RAN (capability/denied), proving the shadow survived in-ns
        (is (= :capability/denied (any-error-type e)))
        (is (not (any-msg-match e #"(?i)could not resolve")))))))

(deftest eval-string-repl-interleaving
  (let [ctx (ctx-for (cap/default-profile))]
    (testing "a multi-form block reads-then-evals one form at a time"
      ;; require in form 1 is visible to form 2; the block value is the LAST form
      (is (= "HI" (sci/eval-string* ctx "(require '[clojure.string :as s]) (s/upper-case \"hi\")"))))
    (testing "a def in form 1 is visible to form 2, value = last form"
      (is (= 3 (sci/eval-string* ctx "(def a 1) (def b 2) (+ a b)"))))))

(deftest binding-of-a-dynvar-works
  (let [ctx (ctx-for (cap/default-profile))]
    (testing "binding is NOT denied — the model legitimately rebinds print vars"
      (is (= "(0 1 2 ...)"
             (sci/eval-string* ctx "(binding [*print-length* 3] (pr-str (range 100)))"))))))

(deftest copy-ns-catalog-granted-vs-withheld
  (testing ":default grants pprint/data; an injectable ns not granted is unreachable"
    (let [ctx (ctx-for (cap/default-profile))]
      ;; reachable when granted (cl-format returns a string — no *out* plumbing)
      (is (= "{:a 1}" (sci/eval-string* ctx "(require '[clojure.pprint :as pp]) (pp/cl-format nil \"~s\" {:a 1})")))
      (is (= '({:b 2} {:b 3} {:a 1})
             (sci/eval-string* ctx "(require '[clojure.data :as d]) (d/diff {:a 1 :b 2} {:a 1 :b 3})"))))
    (let [ctx (ctx-for (cap/locked-down))]
      ;; locked-down does NOT grant clojure.zip → require fails
      (is (not= ::no-throw (error-of ctx "(require '[clojure.zip :as z])"))))))
