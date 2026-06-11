(ns fractal.engine.surface-replay-test
  "jz3b · surface record + replay: opt-in recording of every surface call as a
   durable :surface/called event; replay serves recorded results without ever
   invoking the live fn; divergence/exhaustion fail typed."
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.capability :as capability]))

(def ^:private world-state (atom 0))

(defn- world-surface
  "A surface whose fn reads MUTABLE world state — the thing replay must freeze."
  []
  {:surface/id :world
   :surface/version 1
   :surface/namespaces
   {'world {'read! {:doc "read the live world counter"
                    :fn (fn [tag] {:tag tag :value (swap! world-state inc)})}
            'boom  {:doc "always throws"
                    :fn (fn [] (throw (ex-info "world exploded" {})))}}}})

(def ^:private world-profile
  (assoc (capability/default-profile)
         :capability/name :world-reader
         :surface/fns '#{world/read! world/boom}))

(def ^:private record-script
  "Two surface reads + one recorded failure, then FINAL from the reads."
  (str "```clojure\n"
       "(def a (world/read! :first))\n"
       "(def b (world/read! :second))\n"
       "(def boom-result (try (world/boom) (catch Exception e :caught)))\n"
       "(FINAL {:a (:value a) :b (:value b) :boom boom-result})\n"
       "```"))

(deftest record-then-replay-freezes-the-world
  (reset! world-state 0)
  (let [rec-cfg (fe/make-config {:adapter :fake :model "fake-model" :harness :rlm
                                 :surfaces [(world-surface)]
                                 :capability world-profile
                                 :surface/record? true
                                 :fake/respond (constantly record-script)})
        r (fe/start-session! rec-cfg {:id "world-rec"})
        res (fe/run-turn! r "go")]
    (is (= {:a 1 :b 2 :boom :caught} (:turn/final-value res)))
    (let [calls (fe/surface-calls r "world-rec")]
      (testing "every call recorded in order, including the failure"
        (is (= 3 (count calls)))
        (is (= ['world/read! 'world/read! 'world/boom] (map :surface/function calls)))
        (is (= [:ok :ok :error] (map :call/status calls)))
        (is (= {:tag :first :value 1} (:call/result (first calls)))))
      (fe/stop-session! r)

      ;; --- REPLAY: the world has MOVED ON (counter keeps counting), and the
      ;; live fns must never run — replace them with landmines to prove it.
      (testing "replay serves recorded results; live fns never invoked"
        (let [landmine {:surface/id :world :surface/version 1
                        :surface/namespaces
                        {'world {'read! {:fn (fn [_] (throw (ex-info "LIVE FN INVOKED" {})))}
                                 'boom  {:fn (fn [] (throw (ex-info "LIVE FN INVOKED" {})))}}}}
              rep-cfg (fe/make-config {:adapter :fake :model "fake-model" :harness :rlm
                                       :surfaces [landmine]
                                       :capability world-profile
                                       :surface/replay-calls calls
                                       :fake/respond (constantly record-script)})
              p (fe/start-session! rep-cfg)
              pres (fe/run-turn! p "go")]
          (is (= {:a 1 :b 2 :boom :caught} (:turn/final-value pres))
              "recorded world reads replayed exactly; recorded error re-thrown")
          (fe/stop-session! p))))))

(deftest replay-divergence-and-exhaustion-fail-typed
  (reset! world-state 0)
  (let [rec-cfg (fe/make-config {:adapter :fake :model "fake-model" :harness :rlm
                                 :surfaces [(world-surface)]
                                 :capability world-profile
                                 :surface/record? true
                                 :fake/respond (constantly "```clojure\n(FINAL (:value (world/read! :only)))\n```")})
        r (fe/start-session! rec-cfg {:id "world-div"})]
    (fe/run-turn! r "go")
    (let [calls (fe/surface-calls r "world-div")]
      (fe/stop-session! r)
      (testing "different args than recorded ⇒ :surface/replay-divergence"
        (let [p (fe/start-session!
                  (fe/make-config {:adapter :fake :model "fake-model" :harness :rlm
                                   :surfaces [(world-surface)]
                                   :capability world-profile
                                   :surface/replay-calls calls
                                   :max-steps 2
                                   :fake/respond (constantly "```clojure\n(FINAL (world/read! :DIFFERENT))\n```")}))
              res (fe/run-turn! p "go")]
          (is (not= :final (:status res)))
          (is (some #(= :surface/replay-divergence (get-in % [:eval/error :error/type]))
                    (:evals (fe/view p))))
          (fe/stop-session! p)))
      (testing "more calls than recorded ⇒ :surface/replay-exhausted"
        (let [p (fe/start-session!
                  (fe/make-config {:adapter :fake :model "fake-model" :harness :rlm
                                   :surfaces [(world-surface)]
                                   :capability world-profile
                                   :surface/replay-calls calls
                                   :max-steps 2
                                   :fake/respond (constantly "```clojure\n(world/read! :only)\n(FINAL (world/read! :only))\n```")}))
              res (fe/run-turn! p "go")]
          (is (not= :final (:status res)))
          (is (some #(= :surface/replay-exhausted (get-in % [:eval/error :error/type]))
                    (:evals (fe/view p))))
          (fe/stop-session! p))))))

(deftest record-and-replay-are-mutually-exclusive
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"mutually exclusive"
        (fe/make-config {:adapter :fake :model "fake-model"
                         :fake/respond (constantly "x")
                         :surface/record? true
                         :surface/replay-calls [{:surface/function 'a/b}]}))))
