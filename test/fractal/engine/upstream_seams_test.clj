(ns fractal.engine.upstream-seams-test
  "The hermes-fractal upstream seams (spec: ../hermes-fractal/spec/07):
   U1 host-side fork-session!, U2 aborted-turn wreckage heads, and the hygiene
   riders R1 (compaction failure skips instead of killing) and R2 (byte-accurate
   string inlining)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.api :as fe]
            [fractal.engine.payload :as payload]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-upstream" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- ex-type [e] (:error/type (ex-data e)))

;; ===========================================================================
;; U2 · aborted-turn wreckage heads
;; ===========================================================================

(deftest aborted-turn-publishes-wreckage-head
  (let [dir (temp-dir!) sid "u2-wreck"
        responder (fe/responder
                    [["finish" "```clojure\n(def x 7)\n(FINAL :ok)\n```"]
                     ["wander" "```clojure\n(def y 41)\n:still-going\n```"]
                     ;; after step 1 the last user message is the OBSERVATION,
                     ;; so a :default no-op keeps the turn alive to max-steps
                     [:default "```clojure\n:noop\n```"]])
        cfg (fe/make-config {:adapter :fake :fake/respond responder :model "fake-model"
                             :capability :default :store :sqlite :store/dir dir
                             :max-steps 2})]
    (try
      (let [h  (fe/start-session! cfg {:id sid})
            r1 (fe/run-turn! h "finish")
            r2 (fe/run-turn! h "wander")]
        (is (= :final (:status r1)))
        (is (= :budget-exceeded (:status r2)))
        (testing "the aborted TurnResult carries the wreckage head id"
          (is (string? (:turn/aborted-head r2))))
        (let [view  (fe/view h)
              wreck (store/head-by-id view (:turn/aborted-head r2))]
          (is (= :turn-aborted (:head/kind wreck)))
          (is (nil? (:head/final-ref wreck)))
          (testing "the wreckage snapshot holds the dead turn's vars"
            (let [snap (fe/read-payload h (:head/vars-ref wreck))]
              (is (= 41 (get-in snap [:vars "y" :value])))
              (is (= 7  (get-in snap [:vars "x" :value])))))
          (testing "current-resume-head still names the FINAL head, not the wreckage"
            (is (= :turn-final (:head/kind (store/current-resume-head view))))
            (is (= :turn-aborted (:head/kind (store/current-head view))))))
        (fe/close-session! h))
      (finally (rm-rf! dir)))))

(deftest resume-restores-from-last-final-head-not-wreckage
  (let [dir (temp-dir!) sid "u2-resume"
        responder (fe/responder
                    [["finish" "```clojure\n(def x 7)\n(FINAL :ok)\n```"]
                     ["wander" "```clojure\n(def y 41)\n:still-going\n```"]
                     ["use-x"  "```clojure\n(FINAL (* x 6))\n```"]
                     ["use-y"  "```clojure\n(FINAL y)\n```"]
                     [:default "```clojure\n:noop\n```"]])
        cfg (fe/make-config {:adapter :fake :fake/respond responder :model "fake-model"
                             :capability :default :store :sqlite :store/dir dir
                             :max-steps 2})]
    (try
      (let [h (fe/start-session! cfg {:id sid})]
        (is (= :final (:status (fe/run-turn! h "finish"))))
        (is (= :budget-exceeded (:status (fe/run-turn! h "wander"))))
        (fe/close-session! h))
      (let [h2 (fe/resume-session! cfg sid)
            rx (fe/run-turn! h2 "use-x")
            ry (fe/run-turn! h2 "use-y")]
        (testing "x (from the final head) is restored"
          (is (= :final (:status rx)))
          (is (= 42 (:turn/final-value rx))))
        (testing "y (only in the wreckage) is NOT restored"
          (is (not= :final (:status ry))))
        (fe/close-session! h2))
      (finally (rm-rf! dir)))))

(deftest provider-failure-also-publishes-wreckage
  (let [dir (temp-dir!) sid "u2-provider"
        base (fe/responder [["finish" "```clojure\n(def kept 5)\n(FINAL :ok)\n```"]])
        respond (fn [req]
                  (if (str/includes? (or (fake/last-user req) "") "boom")
                    (throw (ex-info "provider down" {:error/type :provider/down}))
                    (base req)))
        cfg (fe/make-config {:adapter :fake :fake/respond respond :model "fake-model"
                             :capability :default :store :sqlite :store/dir dir
                             :max-steps 3})]
    (try
      (let [h  (fe/start-session! cfg {:id sid})
            r1 (fe/run-turn! h "finish")
            r2 (fe/run-turn! h "boom")]
        (is (= :final (:status r1)))
        (is (= :error (:status r2)))
        (testing "the provider-failure abort still snapshots the live vars"
          (is (string? (:turn/aborted-head r2)))
          (let [wreck (store/head-by-id (fe/view h) (:turn/aborted-head r2))
                snap  (fe/read-payload h (:head/vars-ref wreck))]
            (is (= 5 (get-in snap [:vars "kept" :value])))))
        (fe/close-session! h))
      (finally (rm-rf! dir)))))

;; ===========================================================================
;; U1 · host-side fork-session!
;; ===========================================================================

(def ^:private fork-responder
  (fe/responder
    [["define"       "```clojure\n(def base [1 2 3 4])\n(FINAL :defined)\n```"]
     ["v1"           "```clojure\n(def x 1)\n(FINAL :v1)\n```"]
     ["v2"           "```clojure\n(def x 2)\n(FINAL :v2)\n```"]
     ["sum"          "```clojure\n(FINAL (reduce + base))\n```"]
     ["read-partial" "```clojure\n(FINAL partial-y)\n```"]
     ["read-x"       "```clojure\n(FINAL x)\n```"]
     ["wander"       "```clojure\n(def partial-y 99)\n:no-final\n```"]
     [:default       "```clojure\n:noop\n```"]]))

(defn- fork-cfg [dir & {:as extra}]
  (fe/make-config (merge {:adapter :fake :fake/respond fork-responder
                          :model "fake-model" :capability :default
                          :store :sqlite :store/dir dir :max-steps 2}
                         extra)))

(deftest fork-restores-vars-and-never-advances-source
  (let [dir (temp-dir!) sid "u1-src"
        cfg (fork-cfg dir)]
    (try
      (let [h (fe/start-session! cfg {:id sid})]
        (is (= :final (:status (fe/run-turn! h "define"))))
        (let [src-view-before (fe/view h)
              f  (fe/fork-session! cfg sid)
              rf (fe/run-turn! f "sum")]
          (testing "the fork answers from the restored head vars"
            (is (= :final (:status rf)))
            (is (= 10 (:turn/final-value rf))))
          (testing "the fork is its own session of kind :host-fork"
            (let [fsess (:session (fe/view f))]
              (is (= :host-fork (:session/kind fsess)))
              (is (= sid (:session/source-session fsess)))
              (is (not= sid (:session/id fsess)))))
          (testing "a :host-fork derivation edge lives in the FORK's log"
            (let [edge (first (:edges (fe/view f)))]
              (is (= :derivation (:edge/type edge)))
              (is (= :host-fork (:edge/kind edge)))
              (is (= sid (:edge/from-session edge)))
              (is (= (:current-head src-view-before) (:edge/from-head edge)))))
          (testing "the source never advanced — same heads, same events"
            (let [src-view-after (fe/view h)]
              (is (= (:heads src-view-before) (:heads src-view-after)))
              (is (= (count (fe/event-stream h))
                     (count (:events src-view-before))))))
          (fe/close-session! f))
        (fe/close-session! h))
      (finally (rm-rf! dir)))))

(deftest fork-from-named-older-head
  (let [dir (temp-dir!) sid "u1-named"
        cfg (fork-cfg dir)]
    (try
      (let [h (fe/start-session! cfg {:id sid})]
        (is (= :final (:status (fe/run-turn! h "v1"))))
        (let [head1 (:current-head (fe/view h))]
          (is (= :final (:status (fe/run-turn! h "v2"))))
          (let [f-old (fe/fork-session! cfg sid {:head/id head1})
                f-new (fe/fork-session! cfg sid)]
            (is (= 1 (:turn/final-value (fe/run-turn! f-old "read-x")))
                "an explicit older head restores ITS vars")
            (is (= 2 (:turn/final-value (fe/run-turn! f-new "read-x")))
                "the default fork basis is the latest final head")
            (fe/close-session! f-old)
            (fe/close-session! f-new)))
        (fe/close-session! h))
      (finally (rm-rf! dir)))))

(deftest fork-reaches-wreckage-only-by-explicit-head-id
  (let [dir (temp-dir!) sid "u1-wreck"
        cfg (fork-cfg dir)]
    (try
      (let [h  (fe/start-session! cfg {:id sid})
            _  (fe/run-turn! h "v1")
            r2 (fe/run-turn! h "wander")
            wreck-id (:turn/aborted-head r2)]
        (is (= :budget-exceeded (:status r2)))
        (testing "explicit wreckage fork recovers the dead turn's state"
          ;; partial-y was only defined in the aborted turn — prove it's live
          ;; in the fork's REPL by computing with it.
          (let [f (fe/fork-session! cfg sid {:head/id wreck-id})]
            (is (= 99 (:turn/final-value (fe/run-turn! f "read-partial"))))
            (fe/close-session! f)))
        (testing "the DEFAULT fork basis skips the wreckage"
          (let [f (fe/fork-session! cfg sid)]
            (is (= 1 (:turn/final-value (fe/run-turn! f "read-x")))
                "default fork = last FINAL head (v1), not the wreckage")
            (fe/close-session! f)))
        (fe/close-session! h))
      (finally (rm-rf! dir)))))

(deftest fork-capability-gate-rejects-more-privileged-source
  (let [dir (temp-dir!) sid "u1-priv"
        trusted-cfg (fork-cfg dir :capability :trusted)
        default-cfg (fork-cfg dir :capability :default)]
    (try
      (let [h (fe/start-session! trusted-cfg {:id sid})]
        (is (= :final (:status (fe/run-turn! h "v1"))))
        (fe/close-session! h))
      (testing "a :default caller may not fork a :trusted source"
        (is (= :fractal/fork-capability-rejected
               (try (fe/fork-session! default-cfg sid)
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-type e))))))
      (testing "a :trusted caller forks it fine"
        (let [f (fe/fork-session! trusted-cfg sid)]
          (is (= 1 (:turn/final-value (fe/run-turn! f "read-x"))))
          (fe/close-session! f)))
      (finally (rm-rf! dir)))))

(deftest fork-bundle-gate-requires-source-surfaces
  (let [dir (temp-dir!) sid "u1-bundle"
        surf {:surface/id :probe :surface/version 1
              :surface/namespaces {'probe {'echo {:fn (fn [x] x)}}}}
        with-surf (fork-cfg dir :surfaces [surf])
        without   (fork-cfg dir)]
    (try
      (let [h (fe/start-session! with-surf {:id sid})]
        (is (= :final (:status (fe/run-turn! h "v1"))))
        (fe/close-session! h))
      (testing "a fork that does not re-present the source's surfaces is rejected"
        (is (= :bundle/surface-mismatch
               (try (fe/fork-session! without sid)
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-type e))))))
      (testing ":bundle/allow-mismatch? is the explicit escape"
        (let [f (fe/fork-session! without sid {:bundle/allow-mismatch? true})]
          (is (= 1 (:turn/final-value (fe/run-turn! f "read-x"))))
          (fe/close-session! f)))
      (finally (rm-rf! dir)))))

(deftest fork-requires-sqlite-and-a-published-head
  (testing "memory store is rejected"
    (is (= :config/unsupported-store
           (try (fe/fork-session!
                  (fe/make-config {:adapter :fake :fake/respond fork-responder
                                   :model "fake-model" :capability :default})
                  "nope")
                nil
                (catch clojure.lang.ExceptionInfo e (ex-type e))))))
  (testing "a source with no published head is rejected"
    (let [dir (temp-dir!) sid "u1-headless"
          cfg (fork-cfg dir)]
      (try
        (let [h (fe/start-session! cfg {:id sid})]   ; no turn run, no head
          (fe/close-session! h))
        (is (= :fractal/unknown-head
               (try (fe/fork-session! cfg sid)
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-type e)))))
        (finally (rm-rf! dir))))))

;; ===========================================================================
;; R1 · compaction failure skips instead of killing
;; ===========================================================================

(deftest compaction-call-failure-skips-instead-of-killing
  (let [base (fe/responder
               [["hello" "```clojure\n(def greeted true)\n(FINAL :hi)\n```"]
                ["again" "```clojure\n(FINAL greeted)\n```"]])
        respond (fn [req]
                  (if (some #(and (= :system (:role %))
                                  (str/includes? (str (:content %)) "compacting"))
                            (:messages req))
                    (throw (ex-info "provider down during compaction" {}))
                    (base req)))
        cfg (fe/make-config {:adapter :fake :fake/respond respond
                             :model "fake-model" :capability :default :max-steps 2})
        h (fe/start-session! cfg)]
    (is (= :final (:status (fe/run-turn! h "hello"))))
    (testing "explicit compact-session! survives the provider failure"
      (is (some? (fe/compact-session! h))))
    (testing "a typed :compaction/failed event is on the log; no compact boundary"
      (let [evs (fe/event-stream h)]
        (is (some #(= :compaction/failed (:event/type %)) evs))
        (is (not-any? #(= :session/compacted (:event/type %)) evs))))
    (testing "the session still works afterwards"
      (is (= true (:turn/final-value (fe/run-turn! h "again")))))
    (fe/stop-session! h)))

;; ===========================================================================
;; R2 · byte-accurate string inlining
;; ===========================================================================

(deftest string-inlining-counts-utf8-bytes-not-chars
  (let [st (mem/memory-store)
        cjk   (apply str (repeat 300 "界"))   ; 300 chars ≈ 900 UTF-8 bytes
        ascii (apply str (repeat 300 "x"))]   ; 300 chars = 300 bytes
    (testing "a multibyte string over the byte budget is interned, not inlined"
      (is (payload/payload-ref? (payload-io/maybe-intern st cjk {:payload/kind :t})))
      (is (= cjk (payload-io/read-payload
                   st (payload-io/maybe-intern st cjk {:payload/kind :t})))))
    (testing "a small ascii string stays inline"
      (is (= ascii (payload-io/maybe-intern st ascii {:payload/kind :t}))))))
