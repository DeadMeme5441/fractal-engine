(ns fractal.engine.replay-test
  "jz3a · recorded replay: a prior run's durable step responses re-execute a
   fresh session deterministically — root and rlm children route by first user
   message; leaves and exhaustion fail typed."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-replay" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- recording-responder
  "Turn 1: two steps (def, then FINAL after the observation). Turn 2: spawn a
   child, return its value. Matches discriminate on the LAST user message
   (which is the observation on continuation steps) plus turn context."
  [req]
  (let [lu  (or (fake/last-user req) "")
        all (str (:messages req))]
    (cond
      (str/includes? lu "Assigned task:") "```clojure\n(FINAL {:child :done})\n```"
      (str/includes? lu "first turn")     "```clojure\n(def base 40)\nbase\n```"
      (str/includes? lu "second turn")    "```clojure\n(FINAL (:rlm/value (rlm \"sub-task\")))\n```"
      ;; the continuation step of turn 1: an observation follows the def block
      (and (str/includes? all "first turn")
           (not (str/includes? all "second turn")))
      "```clojure\n(FINAL (+ base 2))\n```"
      :else (throw (ex-info "unexpected request in recording script" {:last-user lu})))))

(deftest recorded-run-replays-deterministically
  (let [dir (temp-dir!)]
    (try
      ;; --- RECORD ---------------------------------------------------------
      (let [rec-cfg (fe/make-config {:adapter :fake :fake/respond recording-responder
                                     :model "fake-model" :harness :rlm
                                     :store :sqlite :store/dir dir})
            r (fe/start-session! rec-cfg {:id "recorded-root"})
            t1 (fe/run-turn! r "first turn")
            t2 (fe/run-turn! r "second turn")
            child-sid (-> (fe/view r) :edges first :edge/to-session)]
        (is (= 42 (:turn/final-value t1)))
        (is (= {:child :done} (:turn/final-value t2)))
        (is (string? child-sid))

        ;; --- REPLAY -------------------------------------------------------
        (let [respond (fe/replay-responder r ["recorded-root" child-sid])
              rep-cfg (fe/make-config {:adapter :fake :fake/respond respond
                                       :model "fake-model" :harness :rlm})
              p (fe/start-session! rep-cfg)
              p1 (fe/run-turn! p "first turn")
              p2 (fe/run-turn! p "second turn")]
          (testing "the replayed run reproduces the recorded finals"
            (is (= 42 (:turn/final-value p1)))
            (is (= 2 (:step-count p1)) "multi-step turn replays step by step")
            (is (= {:child :done} (:turn/final-value p2))
                "the child session routed to ITS recorded responses"))
          (testing "a turn beyond the recording fails typed, not silently"
            (let [extra (fe/run-turn! p "third turn never recorded")]
              (is (= :error (:status extra)))))
          (fe/stop-session! p))
        (fe/close-session! r))
      (finally (rm-rf! dir)))))

(deftest same-key-multi-step-sessions-replay-whole-not-interleaved
  ;; review regression: two SEQUENTIAL children with byte-identical task text
  ;; (⇒ identical routing keys) but different multi-step recordings must each
  ;; replay their OWN steps in order — the old rotation served child A's step 2
  ;; from child B's recording.
  (let [dir (temp-dir!)
        n   (atom 0)
        rec-responder
        (fn [req]
          (let [lu (or (fake/last-user req) "")]
            (cond
              (str/includes? lu "Assigned task:")        ; a child's FIRST step
              (if (= 1 (swap! n inc))
                "```clojure\n(def tag :a)\ntag\n```"
                "```clojure\n(def tag :b)\ntag\n```")
              (str/includes? lu "turn one") "```clojure\n(FINAL (:rlm/value (rlm \"same work\")))\n```"
              (str/includes? lu "turn two") "```clojure\n(FINAL (:rlm/value (rlm \"same work\")))\n```"
              :else "```clojure\n(FINAL tag)\n```")))]   ; a child's SECOND step
    (try
      (let [cfg (fe/make-config {:adapter :fake :fake/respond rec-responder
                                 :model "fake-model" :harness :rlm
                                 :store :sqlite :store/dir dir})
            r (fe/start-session! cfg {:id "same-key-root"})
            t1 (fe/run-turn! r "turn one")
            t2 (fe/run-turn! r "turn two")
            child-sids (mapv :edge/to-session (:edges (fe/view r)))]
        (is (= :a (:turn/final-value t1)))
        (is (= :b (:turn/final-value t2)))
        (let [respond (fe/replay-responder r (into ["same-key-root"] child-sids))
              p (fe/start-session! (fe/make-config {:adapter :fake :fake/respond respond
                                                    :model "fake-model" :harness :rlm}))]
          (is (= :a (:turn/final-value (fe/run-turn! p "turn one")))
              "child A replays A's WHOLE recording (def :a then FINAL tag)")
          (is (= :b (:turn/final-value (fe/run-turn! p "turn two")))
              "child B then replays B's recording — never interleaved")
          (fe/stop-session! p))
        (fe/close-session! r))
      (finally (rm-rf! dir)))))

(deftest replay-source-can-be-a-reopened-store
  (let [dir (temp-dir!)]
    (try
      (let [rec-cfg (fe/make-config {:adapter :fake
                                     :fake/respond (fe/responder [[:default "```clojure\n(FINAL :v)\n```"]])
                                     :model "fake-model"
                                     :store :sqlite :store/dir dir})
            r (fe/start-session! rec-cfg {:id "rr"})]
        (fe/run-turn! r "the question")
        (fe/close-session! r))
      ;; a separate open on the same dir (the cross-run shape)
      (let [store (fe/open-sqlite-store dir)
            respond (fe/replay-responder store ["rr"])
            p (fe/start-session! (fe/make-config {:adapter :fake :fake/respond respond
                                                  :model "fake-model"}))]
        (is (= :v (:turn/final-value (fe/run-turn! p "the question"))))
        (fe/stop-session! p)
        (fe/close-store! store))
      (finally (rm-rf! dir)))))

(deftest replay-rejects-leaf-calls-typed
  (let [dir (temp-dir!)]
    (try
      (let [rec-cfg (fe/make-config {:adapter :fake
                                     :fake/respond (fe/responder [[:default "```clojure\n(FINAL :x)\n```"]])
                                     :model "fake-model"
                                     :store :sqlite :store/dir dir})
            r (fe/start-session! rec-cfg {:id "leafy"})]
        (fe/run-turn! r "go")
        (let [respond (fe/replay-responder r ["leafy"])]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"leaf"
                (respond {:messages [{:role :user :content "go"}]
                          :cache {:purpose :leaf :scope-id "fr:leaf:abc"}}))))
        (fe/close-session! r))
      (finally (rm-rf! dir)))))

(deftest replay-unknown-session-key-fails-typed
  (let [dir (temp-dir!)]
    (try
      (let [rec-cfg (fe/make-config {:adapter :fake
                                     :fake/respond (fe/responder [[:default "```clojure\n(FINAL :x)\n```"]])
                                     :model "fake-model"
                                     :store :sqlite :store/dir dir})
            r (fe/start-session! rec-cfg {:id "known"})]
        (fe/run-turn! r "recorded message")
        (let [respond (fe/replay-responder r ["known"])]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"replay"
                (respond {:messages [{:role :user :content "NEVER recorded"}]
                          :cache {:scope-id "fr:agent:zzz"}}))))
        (fe/close-session! r))
      (finally (rm-rf! dir)))))
