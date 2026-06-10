(ns fractal.engine.session-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.store :as store]))

(defn- cfg [respond & {:as extra}]
  (fe/make-config (merge {:adapter :fake :model "fake-model" :capability :default
                          :fake/respond respond}
                         extra)))

(defn- obs? [request substr]
  (str/includes? (or (fake/last-user request) "") substr))

;; ---------------------------------------------------------------------------
;; The happy paths
;; ---------------------------------------------------------------------------

(deftest single-step-final
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (FINAL {:answer 42})```"]])))
        res (fe/run-turn! s "What is 6 times 7?")]
    (is (= :final (:status res)))
    (is (= {:answer 42} (:turn/final-value res)))
    (is (= 1 (:step-count res)))
    (is (= (:session-id s) (:session/id res)))))

(deftest multi-step-turn-uses-the-repl
  (let [respond (fe/responder
                  [["multiply" "```clojure\n(def a 6)\n(def b 7)\n(* a b)\n```"]
                   [#(obs? % "Observation") "```clojure (FINAL {:product (* a b)})```"]])
        s (fe/start-session! (cfg respond))
        res (fe/run-turn! s "please multiply them")]
    (is (= :final (:status res)))
    (is (= {:product 42} (:turn/final-value res)))
    (is (= 2 (:step-count res)) "one work step + one FINAL step")))

(deftest vars-persist-across-turns
  (let [respond (fe/responder
                  [["define" "```clojure (def carry 100) (FINAL :defined)```"]
                   ["use"    "```clojure (FINAL {:carry carry})```"]])
        s (fe/start-session! (cfg respond))]
    (is (= :defined (:turn/final-value (fe/run-turn! s "please define it"))))
    (is (= {:carry 100} (:turn/final-value (fe/run-turn! s "now use it"))))))

(deftest honest-unknown-accounting
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (FINAL :ok)```"]])))
        res (fe/run-turn! s "go")]
    (is (= :unknown (:usage/status (:turn/usage res))))
    (is (= :unknown (:cost/usd (:turn/cost res))))
    (is (= :unknown (:cache/status (:turn/cache res))))))

(deftest no-fence-nudge-then-recovery
  (let [respond (fe/responder
                  [["start" "just chatter, no code"]
                   [#(obs? % "No clojure block") "```clojure (FINAL :recovered)```"]])
        s (fe/start-session! (cfg respond))
        res (fe/run-turn! s "start now")]
    (is (= :recovered (:turn/final-value res)))
    (is (= 2 (:step-count res)))))

;; ---------------------------------------------------------------------------
;; Budgets + errors
;; ---------------------------------------------------------------------------

(deftest max-steps-budget-exceeded
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (def loop-var 1)```"]])
                                  :max-steps 3))
        res (fe/run-turn! s "never finals")]
    (is (= :budget-exceeded (:status res)))
    (is (= :fractal/max-steps (:error/type (:error res))))
    (is (= 3 (:step-count res)))))

(deftest nil-max-steps-means-unbounded
  ;; :max-steps nil = no per-turn step cap (the same nil convention as
  ;; :max-turns). Pre-fix this NPE'd at (>= step-n nil) on the first non-final
  ;; step. The loop must run PAST the default (25) and settle on FINAL.
  (let [calls (atom 0)
        respond (fn [_req]
                  (if (< (swap! calls inc) 30)
                    "```clojure\n(def keep-going 1)\n```"
                    "```clojure\n(FINAL :eventually)\n```"))
        s (fe/start-session! (cfg respond :max-steps nil))
        res (fe/run-turn! s "loop far past the default cap")]
    (is (= :final (:status res)))
    (is (= :eventually (:turn/final-value res)))
    (is (= 30 (:step-count res)) "29 non-final steps + the FINAL step")))

(deftest hard-abort-on-context-window
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (FINAL :ok)```"]])
                                  :context {:compact-at 0.80 :hard-at 0.95 :unknown-window-chars 100}))
        res (fe/run-turn! s "x")]
    (is (= :budget-exceeded (:status res)))
    (is (= :fractal/context-window (:error/type (:error res))))))

(deftest eval-error-keeps-turn-open
  (let [respond (fe/responder
                  [["divide" "```clojure (/ 1 0)```"]
                   [#(obs? % "Observation") "```clojure (FINAL :handled)```"]])
        s (fe/start-session! (cfg respond))
        res (fe/run-turn! s "divide by zero")]
    (is (= :handled (:turn/final-value res)) "the model saw the error and recovered")
    (let [obs (->> (fe/event-stream s)
                   (filter #(= :message/appended (:event/type %)))
                   (map :message)
                   (filter #(= :observation (:message/role %)))
                   first)]
      (is (str/includes? (fe/read-payload s (:message/content-or-ref obs)) "ERROR")))))

;; ---------------------------------------------------------------------------
;; Concurrency: async, turn-in-flight, release-before-deliver
;; ---------------------------------------------------------------------------

(deftest async-delivers-turn-result
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (FINAL :async-ok)```"]])))
        th (fe/run-turn-async! s "go")]
    (is (some? (:turn/id th)))
    (is (= :async-ok (:turn/final-value @(:promise th))))))

(deftest concurrent-turn-is-rejected
  (let [slow (fn [_] (Thread/sleep 300) "```clojure (FINAL :slow)```")
        s (fe/start-session! (cfg (fe/responder [[:default slow]])))
        th (fe/run-turn-async! s "first")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"turn in flight"
          (fe/run-turn! s "second")))
    (is (= :slow (:turn/final-value @(:promise th))))))

(deftest release-before-deliver-no-spurious-busy
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (FINAL :ok)```"]])))
        r1 @(:promise (fe/run-turn-async! s "one"))]
    (is (= :ok (:turn/final-value r1)))
    ;; deref then immediately re-invoke must not hit a stale busy flag
    (is (= :ok (:turn/final-value @(:promise (fe/run-turn-async! s "two")))))))

(deftest async-error-is-a-turn-result-not-a-throw
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (def x 1)```"]]) :max-steps 2))
        res @(:promise (fe/run-turn-async! s "loops forever"))]
    (is (= :budget-exceeded (:status res)))))

;; ---------------------------------------------------------------------------
;; max-turns (pre-CAS throw)
;; ---------------------------------------------------------------------------

(deftest max-turns-throws-before-cas
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (FINAL :ok)```"]]) :max-turns 1))]
    (is (= :ok (:turn/final-value (fe/run-turn! s "first"))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"turn limit"
          (fe/run-turn! s "second")))))

;; ---------------------------------------------------------------------------
;; stop ordering (no :turn/* after :session/stopped)
;; ---------------------------------------------------------------------------

(deftest stop-idle-then-reject-new-turns
  (let [s (fe/start-session! (cfg (fe/responder [[:default "```clojure (FINAL :ok)```"]])))]
    (fe/run-turn! s "one")
    (fe/stop-session! s)
    (is (= :stopped (get-in (fe/view s) [:session :session/status])))
    (let [res (fe/run-turn! s "after stop")]
      (is (= :error (:status res)))
      (is (= :fractal/session-stopped (:error/type (:error res)))))
    (testing "no :turn/* event appears after :session/stopped"
      (let [evs (fe/event-stream s)
            stop-id (:event/id (first (filter #(= :session/stopped (:event/type %)) evs)))]
        (is (not-any? #(and (> (:event/id %) stop-id)
                            (#{:turn/started :turn/put} (:event/type %)))
                      evs))))))

;; ---------------------------------------------------------------------------
;; compaction: fires + keeps vars
;; ---------------------------------------------------------------------------

(deftest force-compaction-keeps-vars
  (let [respond (fe/responder
                  [[#(obs? % "[assistant]") "COMPACTED BRIEFING"]   ; the compaction call
                   ["remember" "```clojure (def memory 777) (FINAL :stored)```"]
                   ["recall"   "```clojure (FINAL {:memory memory})```"]])
        s (fe/start-session! (cfg respond))]
    (is (= :stored (:turn/final-value (fe/run-turn! s "remember the number"))))
    (fe/compact-session! s)
    (is (some #(= :session/compacted (:event/type %)) (fe/event-stream s)))
    (testing "the var defined before compaction is still live after"
      (is (= {:memory 777} (:turn/final-value (fe/run-turn! s "recall the number")))))
    (testing "compaction prunes the transcript to [compact-frame, …new…]"
      (let [v (fe/view s)
            kept (store/kept-messages v)]
        (is (some #(= "COMPACTED BRIEFING" (fe/read-payload s (:message/content-or-ref %))) kept))))))

(deftest auto-compaction-fires-before-a-turn
  (let [respond (fe/responder
                  [[#(obs? % "[assistant]") "BRIEF"]
                   ["big"    "```clojure (def kept 9) (FINAL :ok)```"]
                   ["next"   "```clojure (FINAL {:kept kept})```"]])
        ;; cap chosen so turn-1 messages cross compact (0.5*20000=10000) but
        ;; system+turn1-user stays under hard (0.95*20000=19000)
        s (fe/start-session! (cfg respond
                                  :context {:compact-at 0.5 :hard-at 0.95 :unknown-window-chars 20000}))
        big-msg (str "big " (apply str (repeat 12000 "x")))]
    (is (= :ok (:turn/final-value (fe/run-turn! s big-msg))))
    (is (not-any? #(= :session/compacted (:event/type %)) (fe/event-stream s)))
    ;; turn 2 should auto-compact at the start (transcript > compact threshold)
    (is (= {:kept 9} (:turn/final-value (fe/run-turn! s "next please"))))
    (is (some #(= :session/compacted (:event/type %)) (fe/event-stream s)))))
