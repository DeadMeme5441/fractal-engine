(ns fractal.engine.lazy-force-test
  "The eval guard must FORCE lazy values (downstream bug report, 2026-06-11).

   Before the fix: an eval block whose value is an unrealized lazy seq reports
   :ok, the value exits the guarded region, and realization happens later in
   the observation/persistence path (observe/value-stub's bounded-count,
   payload-io/edn-safe) — OUTSIDE the eval guard. A poisoned lazy then escapes
   run-turn! and kills the whole turn; inside a child session it propagates up
   and surfaces as the PARENT's eval error with no child eval error recorded.

   After the fix: realization happens (bounded) INSIDE eval-block's try/catch,
   so a poisoned lazy degrades into the normal recoverable :eval/error and the
   turn continues. Unbounded/infinite seqs stay bounded (never fully realized)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fractal.engine.api :as fe]
            [fractal.engine.capability :as cap]
            [fractal.engine.kernel :as k]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]))

;; ---------------------------------------------------------------------------
;; Helpers (mirroring kernel_test / recursion_test conventions)
;; ---------------------------------------------------------------------------

(defn- mk-handle []
  (let [s   (mem/memory-store)
        sid "lazy-1"
        h   (store/create-session! s {:session/id sid :session/status :running})]
    (store/append-event! s sid {:event/type :session/started
                                :session {:session/id sid :session/status :running}})
    (reset! (:sci-ctx h) (k/new-ctx sid (cap/default-profile) (k/engine-fn-impls)))
    h))

(defn- run1 [h code] (first (:eval-records (k/eval-batch h 1 [code]))))

(def ^:private lazy-bomb-block
  "```clojure\n(map (fn [x] (throw (ex-info \"lazy bomb\" {}))) [1 2 3])\n```")

(defn- session-with [responder & {:as extra}]
  (fe/start-session!
    (fe/make-config (merge {:adapter :fake :fake/respond responder
                            :model "fake-model" :harness :rlm :capability :default
                            :store :memory :max-steps 3}
                           extra))))

(defn- eval-errors [handle]
  (->> (:evals (fe/view handle))
       (filter #(= :error (:eval/status %)))))

;; ===========================================================================
;; KERNEL — eval-block forces lazy values inside the guard
;; ===========================================================================

(deftest lazy-bomb-degrades-to-eval-error
  (let [h (mk-handle)
        r (run1 h "(map (fn [x] (throw (ex-info \"lazy bomb\" {}))) [1 2 3])")]
    (is (= :error (:eval/status r)) "a poisoned lazy is a recoverable eval error, not an :ok")
    (is (str/includes? (str (get-in r [:eval/error :error/message])) "lazy bomb")
        "the realization exception's message is the eval error's message")))

(deftest nested-lazy-bomb-degrades-to-eval-error
  (let [h (mk-handle)
        r (run1 h "{:results (map (fn [x] (throw (ex-info \"nested bomb\" {}))) [1])}")]
    (is (= :error (:eval/status r)) "a lazy nested inside an eager collection is also forced")
    (is (str/includes? (str (get-in r [:eval/error :error/message])) "nested bomb"))))

(deftest lazy-bomb-inside-FINAL-degrades-to-eval-error
  (let [h (mk-handle)
        r (k/eval-batch h 1 ["(FINAL (map (fn [x] (throw (ex-info \"final bomb\" {}))) [1 2 3]))"])]
    (is (= :error (:status r))
        "a poisoned lazy FINAL value degrades to an eval error, not a commit-time escape")
    (is (str/includes? (str (get-in (first (:eval-records r)) [:eval/error :error/message]))
                       "final bomb"))))

(deftest the-live-trigger-var-unbound-degrades-to-eval-error
  ;; The trigger that found the bug: *1 resolves in SCI to an UNBOUND var
  ;; (REPL vars exist but nothing sets them), so (def x *1) interns the
  ;; Var$Unbound sentinel and a later lazy (take 5 x) detonates on realization.
  (let [h (mk-handle)]
    (is (= :ok (:eval/status (run1 h "(def search-results *1)")))
        "*1 evals :ok — it is an unbound var, not an unresolved symbol")
    (let [r (run1 h "(take 5 search-results)")]
      (is (= :error (:eval/status r)) "the poisoned take degrades to an eval error")
      (is (str/includes? (str (get-in r [:eval/error :error/message])) "Var$Unbound")
          "the exact live failure message, now recoverable"))))

(deftest healthy-lazy-values-still-work
  (let [h (mk-handle)]
    (testing "a finite lazy seq evals :ok and its value survives forcing"
      (let [r (run1 h "(map inc [1 2 3])")]
        (is (= :ok (:eval/status r)))
        (is (= '(2 3 4) (:eval/raw-value r)))))
    (testing "an INFINITE lazy seq stays bounded — forcing must not fully realize it"
      (let [r (run1 h "(range)")]
        (is (= :ok (:eval/status r)) "(range) at top level must not hang the kernel")))))

;; ===========================================================================
;; ROOT session — run-turn! survives the bomb (the deterministic repro)
;; ===========================================================================

(deftest root-lazy-bomb-does-not-escape-run-turn
  (let [resp (fe/responder [[:default lazy-bomb-block]])
        s (session-with resp)]
    (try
      (let [r (fe/run-turn! s "trigger")]
        (is (map? r) "run-turn! RETURNS a TurnResult — the bomb must not throw out of it")
        (is (= :budget-exceeded (:status r))
            "the model keeps bombing, so the turn ends at max-steps — not by an escaped exception")
        (is (seq (eval-errors s)) "each bomb is recorded durably as an :eval/error")
        (is (every? #(str/includes? (str (get-in % [:eval/error :error/message])) "lazy bomb")
                    (eval-errors s))))
      (finally (fe/stop-session! s)))))

(deftest root-lazy-bomb-is-recoverable
  (let [resp (fe/responder
               [["ERROR: lazy bomb" "```clojure\n(FINAL :recovered)\n```"]
                [:default lazy-bomb-block]])
        s (session-with resp)]
    (try
      (let [r (fe/run-turn! s "trigger")]
        (is (= :final (:status r)) "the model sees the eval error observation and recovers")
        (is (= :recovered (:turn/final-value r))))
      (finally (fe/stop-session! s)))))

;; ===========================================================================
;; CHILD session — the bomb stays the CHILD's eval error; the parent gets a
;; recoverable :fractal/child-failed observation, never an escaped exception
;; ===========================================================================

(deftest child-lazy-bomb-stays-recoverable-at-the-parent
  (let [resp (fe/responder
               [["Assigned task"  lazy-bomb-block]                              ; child step 1
                ["ERROR: lazy bomb" "```clojure\n:child-flailing\n```"]         ; child steps 2+
                ["child session did not return FINAL"
                 "```clojure\n(FINAL :parent-recovered)\n```"]                  ; parent step 2
                ["go-parent" "```clojure\n(rlm \"subtask\")\n```"]])            ; parent step 1
        s (session-with resp :max-steps 2)
        psid (:session-id s)]
    (try
      (let [r (fe/run-turn! s "go-parent")]
        (testing "the parent turn survives and recovers"
          (is (map? r) "run-turn! returns — the child's bomb must not escape through rlm")
          (is (= :final (:status r)))
          (is (= :parent-recovered (:turn/final-value r))))
        (testing "the parent records the child failure as ITS OWN recoverable eval error"
          (is (some #(str/includes? (str (get-in % [:eval/error :error/message]))
                                    "child session did not return FINAL")
                    (eval-errors s))))
        (testing "the CHILD store durably records the lazy bomb as a child eval error"
          (let [csid (first (remove #{psid} (keys @(:sessions (:store s)))))
                cevals (:evals (store/current-view (:store s) csid))]
            (is (some? csid) "the child session is durable in the shared store")
            (is (some #(and (= :error (:eval/status %))
                            (str/includes? (str (get-in % [:eval/error :error/message]))
                                           "lazy bomb"))
                      cevals)
                "the bomb is the child's recorded eval error — not a silent mid-step stop"))))
      (finally (fe/stop-session! s)))))
