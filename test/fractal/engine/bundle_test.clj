(ns fractal.engine.bundle-test
  "yjy · bundle identity: the stamped content-addressed {harness, doctrine,
   surfaces, capability} world. Recorded on the session row + every published
   head; resume verifies surfaces+doctrine; attach verifies the SOURCE's
   surfaces (explicit opt-out only); capability stays clamp-only."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.bundle :as bundle]
            [fractal.engine.capability :as capability]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-bundle" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(def ^:private final-ok "```clojure\n(FINAL :ok)\n```")

(defn- cfg-of [over]
  (fe/make-config (merge {:adapter :fake :model "fake-model" :harness :rlm
                          :fake/respond (constantly final-ok)}
                         over)))

(def ^:private demo-surface
  {:surface/id :demo
   :surface/version 1
   :surface/namespaces {'demo {'hello {:doc "say hi" :fn (fn [x] (str "hi " x))}}}})

(def ^:private surface-profile
  (assoc (capability/default-profile)
         :capability/name :with-demo
         :surface/fns '#{demo/hello}))

;; ---------------------------------------------------------------------------
;; Stamp determinism + sensitivity
;; ---------------------------------------------------------------------------

(deftest bundle-stamp-determinism-and-sensitivity
  (let [cfg (cfg-of {})
        prof (:capability cfg)
        b1 (bundle/stamp cfg prof)
        b2 (bundle/stamp cfg prof)]
    (testing "same world ⇒ same hash"
      (is (= (:bundle/hash b1) (:bundle/hash b2)))
      (is (.startsWith ^String (:bundle/hash b1) "sha256:")))
    (testing "doctrine change ⇒ different hash"
      (let [cfg' (cfg-of {:doctrine {:doctrine/name :t :doctrine/version 1
                                     :doctrine/text "other words"}})]
        (is (not= (:bundle/hash b1) (:bundle/hash (bundle/stamp cfg' prof))))))
    (testing "harness change ⇒ different hash (different built-in doctrine)"
      (let [cfg' (cfg-of {:harness :clojure})]
        (is (not= (:bundle/hash b1) (:bundle/hash (bundle/stamp cfg' prof))))))
    (testing "surface change ⇒ different hash"
      (let [cfg' (cfg-of {:surfaces [demo-surface] :capability surface-profile})]
        (is (not= (:bundle/hash b1) (:bundle/hash (bundle/stamp cfg' (:capability cfg')))))))
    (testing "capability change ⇒ different hash"
      (is (not= (:bundle/hash b1)
                (:bundle/hash (bundle/stamp cfg (capability/locked-down))))))
    (testing "live Class values vs persisted symbols hash IDENTICALLY (key-set semantics)"
      (let [live      (assoc prof :capability/name :x :cap/java-classes {'java.lang.String String})
            persisted (assoc prof :capability/name :x :cap/java-classes {'java.lang.String 'java.lang.String})]
        (is (= (:bundle/hash (bundle/stamp cfg live))
               (:bundle/hash (bundle/stamp cfg persisted))))))))

(deftest legacy-nil-bundle-skips-verification
  (let [cfg (cfg-of {})
        b (bundle/stamp cfg (:capability cfg))]
    (is (= b (bundle/assert-resume-compatible! nil b)))
    (is (= b (bundle/assert-attach-compatible! nil b false)))))

;; ---------------------------------------------------------------------------
;; Recording: session row + heads
;; ---------------------------------------------------------------------------

(deftest session-row-and-heads-carry-the-bundle
  (let [s (fe/start-session! (cfg-of {}))]
    (is (= :final (:status (fe/run-turn! s "go"))))
    (let [v (fe/view s)
          recorded (get-in v [:session :session/bundle])
          head (first (:heads v))]
      (is (some? recorded) "session row records the full bundle")
      (is (= (:bundle/hash recorded) (:head/bundle-hash head))
          "the published head carries the bundle hash"))
    (fe/stop-session! s)))

;; ---------------------------------------------------------------------------
;; Resume verification (sqlite)
;; ---------------------------------------------------------------------------

(deftest resume-verifies-doctrine
  (let [dir (temp-dir!) sid "bundle-resume"]
    (try
      (let [cfg (cfg-of {:store :sqlite :store/dir dir})
            h1  (fe/start-session! cfg {:id sid})]
        (is (= :final (:status (fe/run-turn! h1 "go"))))
        (fe/close-session! h1)
        (testing "same world resumes"
          (let [h2 (fe/resume-session! cfg sid)]
            (is (= :final (:status (fe/run-turn! h2 "again"))))
            (fe/close-session! h2)))
        (testing "different doctrine is rejected with a typed mismatch"
          (let [cfg' (cfg-of {:store :sqlite :store/dir dir
                              :doctrine {:doctrine/name :other :doctrine/version 1
                                         :doctrine/text "different doctrine"}})]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bundle"
                  (fe/resume-session! cfg' sid)))
            (testing "…with an EXPLICIT escape for intentional upgrades (review regression)"
              (let [h (fe/resume-session! cfg' sid {:bundle/allow-mismatch? true})]
                (is (= :final (:status (fe/run-turn! h "after upgrade"))))
                (fe/close-session! h))))))
      (finally (rm-rf! dir)))))

;; ---------------------------------------------------------------------------
;; Attach verification: source surfaces must be re-presented
;; ---------------------------------------------------------------------------

(defn- run-source-with-surface!
  "Create + finish a session WITH the demo surface in `dir` under id `sid`,
   publishing a head whose world includes the surface."
  [dir sid]
  (let [cfg (fe/make-config
              {:adapter :fake :model "fake-model" :harness :rlm
               :store :sqlite :store/dir dir
               :surfaces [demo-surface]
               :capability surface-profile
               :fake/respond (constantly "```clojure\n(def greeting (demo/hello \"world\"))\n(FINAL greeting)\n```")})
        h (fe/start-session! cfg {:id sid})]
    (is (= "hi world" (:turn/final-value (fe/run-turn! h "greet"))))
    (fe/close-session! h)))

(defn- attach-driver-cfg
  "An rlm cfg over the same sqlite dir whose model attaches to `source-sid`.
   The driver's CAPABILITY grants demo/hello (so the pre-existing privilege
   gate admits the source) but the surface is NOT mounted — the bundle's
   surface set is the thing under test (optionally :bundle/allow-mismatch?)."
  [dir source-sid allow?]
  (fe/make-config
    {:adapter :fake :model "fake-model" :harness :rlm
     :store :sqlite :store/dir dir :max-steps 4
     :capability surface-profile
     :fake/respond
     (fn [req]
       (if (str/includes? (or (fake/last-user req) "") "Assigned task:")
         "```clojure\n(FINAL greeting)\n```"
         (str "```clojure\n(FINAL (:rlm/value (attach-rlm {:session/id \"" source-sid "\"} \"return greeting\""
              (when allow? " {:bundle/allow-mismatch? true}")
              ")))\n```")))}))

(deftest attach-rejects-missing-source-surfaces
  (let [dir (temp-dir!) sid "bundle-src"]
    (try
      (run-source-with-surface! dir sid)
      (let [s (fe/start-session! (attach-driver-cfg dir sid false))
            res (fe/run-turn! s "attach")]
        ;; the attach throws inside the eval; the turn surfaces it as an error
        ;; observation and the loop continues until max-steps ⇒ non-final.
        (is (not= :final (:status res))
            "attaching without the source's surfaces must not silently succeed")
        (is (some #(= :bundle/surface-mismatch (get-in % [:eval/error :error/type]))
                  (:evals (fe/view s)))
            "the eval error is the TYPED bundle mismatch, not a generic failure")
        (fe/stop-session! s))
      (finally (rm-rf! dir)))))

(deftest attach-allow-mismatch-is-an-explicit-choice
  (let [dir (temp-dir!) sid "bundle-src-allow"]
    (try
      (run-source-with-surface! dir sid)
      (let [s (fe/start-session! (attach-driver-cfg dir sid true))
            res (fe/run-turn! s "attach")]
        (is (= :final (:status res)) (pr-str (:error res)))
        (is (= "hi world" (:turn/final-value res))
            "vars restore fine; the embedder explicitly accepted the divergent world")
        (fe/stop-session! s))
      (finally (rm-rf! dir)))))
