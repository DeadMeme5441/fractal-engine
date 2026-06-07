(ns fractal.engine.kernel-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fractal.engine.kernel :as k]
            [fractal.engine.capability :as cap]
            [fractal.engine.payload :as payload]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]))

(defn- mk-handle
  ([] (mk-handle (cap/default-profile)))
  ([profile]
   (let [s   (mem/memory-store)
         sid "k-1"
         h   (store/create-session! s {:session/id sid :session/status :running})]
     (store/append-event! s sid {:event/type :session/started
                                 :session {:session/id sid :session/status :running}})
     (reset! (:sci-ctx h) (k/new-ctx sid profile (k/engine-fn-impls)))
     h)))

(defn- run1
  "Run one block, return the first eval record."
  [h code] (first (:eval-records (k/eval-batch h 1 [code]))))

;; ---------------------------------------------------------------------------
;; extract-blocks
;; ---------------------------------------------------------------------------

(deftest extract-blocks-test
  (is (= ["(+ 1 2)"] (k/extract-blocks "text ```clojure\n(+ 1 2)\n``` more")))
  (is (= ["(FINAL :x)"] (k/extract-blocks "```clojure (FINAL :x)```")))
  (is (= ["a" "b"] (k/extract-blocks "```clojure\na\n``` mid ```clj\nb\n```")))
  (is (= [] (k/extract-blocks "no fence here")))
  (is (= [] (k/extract-blocks nil))))

;; ---------------------------------------------------------------------------
;; REPL persistence + semantics
;; ---------------------------------------------------------------------------

(deftest vars-persist-across-evals-and-turns
  (let [h (mk-handle)]
    (k/eval-batch h 1 ["(def x 41)"])
    (is (= 42 (:eval/raw-value (run1 h "(inc x)"))) "var visible in a later eval")
    (k/eval-batch h 2 ["(def y (+ x 1))"])           ; a DIFFERENT turn, same ctx
    (is (= 42 (:eval/raw-value (run1 h "y"))) "var persists across turns")))

(deftest multi-form-block-returns-last-value
  (let [h (mk-handle)]
    (is (= 3 (:eval/raw-value (run1 h "(def p 1) (def q 2) (+ p q)"))))
    (is (= "HI" (:eval/raw-value (run1 h "(require '[clojure.string :as s]) (s/upper-case \"hi\")"))))))

(deftest batch-error-stops-the-batch
  (let [h (mk-handle)
        r (k/eval-batch h 1 ["(def a 1)" "(/ 1 0)" "(def b 2)"])]
    (is (= :error (:status r)))
    (is (= 2 (count (:eval-records r))) "the 3rd block never ran")
    (is (= 1 (:eval/raw-value (run1 h "a"))) "the 1st block's def survives")
    (is (= :error (:eval/status (run1 h "b"))) "b was never defined")))

(deftest final-ends-the-batch
  (let [h (mk-handle)
        r (k/eval-batch h 1 ["(def w 5)" "(FINAL {:answer w})" "(def never 9)"])]
    (is (= :final (:status r)))
    (is (= {:answer 5} (:value (:final r))))
    (is (= 2 (count (:eval-records r))) "code after FINAL in the batch never ran")
    (is (= :error (:eval/status (run1 h "never"))))))

(deftest model-in-ns-does-not-strand-later-evals
  (let [h (mk-handle)]
    (k/eval-batch h 1 ["(in-ns 'model.scratch) (def leaked 1)"])
    (k/eval-batch h 1 ["(def back 2)"])
    (is (= 2 (:eval/raw-value (run1 h "back"))) "later eval landed back in the session ns")))

;; ---------------------------------------------------------------------------
;; stdout / inspect capture
;; ---------------------------------------------------------------------------

(deftest stdout-and-inspect-captured
  (let [h (mk-handle)]
    (let [r (run1 h "(println \"hello world\") 42")]
      (is (str/includes? (:eval/stdout r) "hello world"))
      (is (= 42 (:eval/raw-value r))))
    (let [r (run1 h "(inspect (vec (range 50)))")]
      (is (str/includes? (:eval/stdout r) "Class:"))
      (is (nil? (:eval/raw-value r)) "inspect returns nil"))))

;; ---------------------------------------------------------------------------
;; the kernel↔store edge: intern + preview + strip before :eval/added (GD15)
;; ---------------------------------------------------------------------------

(deftest appends-stripped-eval-with-ref-and-preview
  (let [h (mk-handle) s (:store h) sid (:session-id h)]
    (k/eval-batch h 1 ["(vec (range 1000))"])
    (let [e (last (:evals (store/current-view s sid)))]
      (is (payload/payload-ref? (:eval/result-ref e)) "large value content-addressed")
      (is (= "«vector, 1000 items»" (:eval/result-preview e)))
      (is (not (contains? e :eval/raw-value)) "transient raw fields stripped")
      (is (not (contains? e :eval/raw-final))))
    (testing "a small value inlines its result-ref"
      (k/eval-batch h 1 ["{:a 1}"])
      (let [e (last (:evals (store/current-view s sid)))]
        (is (= {:a 1} (:eval/result-ref e)))
        (is (= "{:a 1}" (:eval/result-preview e)))))))

(deftest distinct-eval-ids-via-peek-next-id
  (let [h (mk-handle) s (:store h) sid (:session-id h)]
    (k/eval-batch h 1 ["(def m 1)" "(def n 2)"])
    (let [ids (map :eval/id (:evals (store/current-view s sid)))]
      (is (= ids (distinct ids)))
      (is (= 2 (count ids))))))

;; ---------------------------------------------------------------------------
;; snapshot / restore (03 §6) — restore is resume/fork plumbing, tested here
;; ---------------------------------------------------------------------------

(deftest snapshot-round-trips-and-marks-unrestorables
  (let [h (mk-handle)]
    (k/eval-batch h 1 ["(def nums [1 2 3])" "(def lst '(1 2 3))" "(def f inc)" "(def a (atom 0))"])
    (let [snap (k/snapshot-vars @(:sci-ctx h) (:session-id h))]
      (is (= 1 (:vars/version snap)))
      (is (= {:status :ok :value [1 2 3]} (get-in snap [:vars "nums"])))
      (is (= {:status :ok :value '(1 2 3)} (get-in snap [:vars "lst"])))
      (is (= :unrestorable (:status (get-in snap [:vars "f"]))))
      (is (= :unrestorable (:status (get-in snap [:vars "a"]))))
      (testing "the snapshot enumerates only the session ns's own vars (not clojure.core)"
        (is (= #{"nums" "lst" "f" "a"} (set (keys (:vars snap)))))))))

(deftest restore-via-sci-intern-handles-a-list-value
  (let [h (mk-handle) ctx @(:sci-ctx h) sid (:session-id h)]
    ;; ⛔ the eval-string trap: a (def lst (1 2 3)) would try to CALL 1. sci/intern binds data.
    (k/restore-vars! ctx sid {:vars/version 1
                              :vars (sorted-map "lst" {:status :ok :value '(1 2 3)}
                                                "m"   {:status :ok :value {:k [1 2]}})})
    (is (= '(1 2 3) (:eval/raw-value (run1 h "lst"))))
    (is (= {:k [1 2]} (:eval/raw-value (run1 h "m"))))))

;; ---------------------------------------------------------------------------
;; FINAL value can be large (interned) and still surfaced
;; ---------------------------------------------------------------------------

(deftest final-carries-raw-value-even-when-large
  (let [h (mk-handle)
        r (k/eval-batch h 1 ["(FINAL (vec (range 2000)))"])]
    (is (= :final (:status r)))
    (is (= (vec (range 2000)) (:value (:final r))))))
