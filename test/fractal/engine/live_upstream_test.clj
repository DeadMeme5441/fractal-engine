(ns fractal.engine.live-upstream-test
  "^:live validation of the hermes-fractal upstream seams against the real
   codex OAuth provider (provider :codex-backend, model gpt-5.5):
     U1 — a HOST-side fork answers from a restored head, source never advanced;
     U2 — a wreckage head makes a dead turn's state recoverable by explicit fork.
   EXCLUDED from `clojure -M:test`; runs only via `clojure -M:live-test`, and
   every test is creds-guarded. Spend: ≤ ~6 small calls with tight step caps."
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [llm.sdk.providers.codex :as codex]))

(def ^:private model "gpt-5.5")

(defn- live? [] (codex/codex-backend-available?))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-live-upstream" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- live-cfg [dir extra]
  (fe/make-config (merge {:adapter :sdk :provider :codex-backend :model model
                          :harness :rlm :capability :default
                          :store :sqlite :store/dir dir
                          :max-steps 6 :cache-ttl "5m"}
                         extra)))

(deftest ^:live host-fork-answers-from-restored-head
  (if-not (live?)
    (println "live host-fork => [skip] no codex OAuth creds")
    (let [dir (temp-dir!) sid "live-fork-src"
          cfg (live-cfg dir {})]
      (try
        (let [h  (fe/start-session! cfg {:id sid})
              r1 (fe/run-turn! h (str "Define a var named answer-base bound to the "
                                      "vector [3 5 7 11]. Then call (FINAL :defined). "
                                      "Do nothing else."))]
          (is (= :final (:status r1)) (str "source build failed: " (:error r1)))
          (let [heads-before (mapv :head/id (:heads (fe/view h)))
                f  (fe/fork-session! cfg sid)
                rf (fe/run-turn! f (str "A var named answer-base already exists in "
                                        "your REPL — it was restored from a prior "
                                        "session. Return (FINAL {:sum (reduce + "
                                        "answer-base)})."))]
            (println "live host-fork =>"
                     (pr-str (select-keys rf [:status :turn/final-value :step-count :turn/cost])))
            (is (= :final (:status rf)) (str "fork turn failed: " (:error rf)))
            (is (= 26 (:sum (:turn/final-value rf)))
                "the fork computed over vars it never defined — restored from the head")
            (testing "the fork is a :host-fork session with a derivation edge"
              (is (= :host-fork (get-in (fe/view f) [:session :session/kind])))
              (is (= :host-fork (:edge/kind (first (:edges (fe/view f)))))))
            (testing "the source session never advanced"
              (is (= heads-before (mapv :head/id (:heads (fe/view h))))))
            (fe/close-session! f))
          (fe/close-session! h))
        (finally (rm-rf! dir))))))

(deftest ^:live wreckage-head-recovers-dead-turn-state
  (if-not (live?)
    (println "live wreckage => [skip] no codex OAuth creds")
    (let [dir (temp-dir!) sid "live-wreck-src"
          ;; :max-steps 1 guarantees the turn aborts AFTER its first eval batch —
          ;; the prompt makes the model do real work in step 1 and explicitly
          ;; defers FINAL, so the wreckage carries state worth recovering.
          cfg (live-cfg dir {:max-steps 1})]
      (try
        (let [h  (fe/start-session! cfg {:id sid})
              r1 (fe/run-turn! h (str "In your FIRST clojure block do exactly this and "
                                      "nothing else: (def partial-x 42) and print :saved. "
                                      "Do NOT call FINAL yet — you will inspect the "
                                      "observation first and FINAL on a later step."))]
          (println "live wreckage abort =>"
                   (pr-str (select-keys r1 [:status :turn/aborted-head :step-count])))
          (is (= :budget-exceeded (:status r1))
              "max-steps 1 must abort the turn (model was told not to FINAL)")
          (is (string? (:turn/aborted-head r1)) "the abort published a wreckage head")
          ;; step caps are cfg-level (and NOT part of the bundle), so the fork
          ;; gets its own cfg with room to recover.
          (let [f  (fe/fork-session! (live-cfg dir {:max-steps 4}) sid
                                     {:head/id (:turn/aborted-head r1)})
                rf (fe/run-turn! f (str "A var named partial-x already exists in your "
                                        "REPL, recovered from an aborted session. "
                                        "Return (FINAL {:x partial-x})."))]
            (println "live wreckage recovery =>"
                     (pr-str (select-keys rf [:status :turn/final-value :turn/cost])))
            (is (= :final (:status rf)) (str "recovery turn failed: " (:error rf)))
            (is (= 42 (:x (:turn/final-value rf)))
                "the dead turn's var came back through the wreckage head")
            (fe/close-session! f))
          (fe/close-session! h))
        (finally (rm-rf! dir))))))
