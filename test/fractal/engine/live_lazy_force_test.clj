(ns fractal.engine.live-lazy-force-test
  "OPTIONAL live proof of the lazy-force fix (^:live — excluded from
   `clojure -M:test`; runs via `clojure -M:live-test` with the Vertex env
   exported; makes paid calls).

   Replays the incident that found the bug: a Vertex Gemini child is told to
   evaluate the exact poisoned idiom a live child once wrote — (def x *1) then
   (take 5 x) — whose realization throws OUTSIDE the old eval guard and killed
   the child turn (surfacing as the PARENT's eval error). Post-fix the child
   sees a recoverable ERROR observation, recovers, FINALs, and the parent turn
   completes."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal.engine.api :as fe]
            [fractal.engine.store :as store]))

(def ^:private vertex-project-env (str "GOOGLE_" "CLOUD_PROJECT"))
(def ^:private vertex-location-env (str "GOOGLE_" "CLOUD_LOCATION"))

(defn- provider-ready? []
  (and (seq (System/getenv vertex-project-env))
       (seq (System/getenv vertex-location-env))))

(def ^:private poisoned-task
  ;; NO fence inside the task text: it rides inside the PARENT's fenced block,
  ;; and a nested ``` would terminate that block early (lazy fence regex).
  (str "You are testing this REPL's error recovery. Step 1: emit one fenced "
       "clojure block containing exactly these two forms, without fixing or "
       "improving them: (def search-results *1) (take 5 search-results) "
       "Step 2: you will receive an ERROR observation — that is expected. "
       "After you see it, emit a new block calling exactly "
       "(FINAL {:saw-error true}). Never retry the broken forms."))

(deftest ^:live vertex-child-survives-the-lazy-bomb
  (is (provider-ready?)
      "Vertex Gemini live smoke requires exported Vertex project and location environment variables")
  (when (provider-ready?)
    (let [cfg (fe/make-config
                {:adapter :sdk
                 :provider :vertex-gemini
                 :model "gemini-3.5-flash"
                 :child-provider :vertex-gemini
                 :child-model "gemini-3.5-flash"
                 :harness :rlm
                 :capability :default
                 :store :memory
                 :max-steps 8
                 :max-fanout 4
                 :fanout-pool 2
                 :leaf-concurrency 2
                 :call-timeout-ms 300000})
          h (fe/start-session! cfg)
          psid (:session-id h)]
      (try
        (let [task (str "In your very first reply, emit exactly ONE fenced clojure block "
                        "of exactly this shape and nothing else:\n"
                        "(FINAL {:child (:rlm/value (rlm <TASK>))})\n"
                        "where <TASK> is this Clojure string literal, reproduced verbatim "
                        "including its quotes:\n" (pr-str poisoned-task))
              result (fe/run-turn! h task)
              value (:turn/final-value result)]
          (println "live lazy-force result:"
                   (pr-str (select-keys result [:status :turn/final-value :step-count :turn/cost])))
          (testing "the parent turn completes — the child's lazy bomb no longer kills the handoff"
            (is (= :final (:status result)) (pr-str (:error result)))
            (is (true? (get-in value [:child :saw-error]))
                "the child observed the realization error and recovered to FINAL"))
          (testing "the child store durably records the realization failure as ITS eval error"
            (let [csid (first (remove #{psid} (keys @(:sessions (:store h)))))
                  cevals (:evals (store/current-view (:store h) csid))]
              (is (some? csid))
              (is (some #(and (= :error (:eval/status %))
                              (str/includes? (str (get-in % [:eval/error :error/message]))
                                             "Var$Unbound"))
                        cevals)
                  "the exact live failure message is the child's recoverable eval error"))))
        (finally (fe/stop-session! h))))))
