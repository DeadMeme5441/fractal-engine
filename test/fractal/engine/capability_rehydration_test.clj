(ns fractal.engine.capability-rehydration-test
  "Custom (non-built-in) capability profiles must survive the durable round-trip:
   the full validated profile VALUE is persisted on the session row
   (:session/capability-profile), resume clamps the cfg-resolved profile against
   it (no silent widening), and attach-rlm resolves the SOURCE profile from the
   persisted value instead of throwing :capability/unknown-profile on a custom
   name — the wall two downstream consumers hit."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-cap-rehydrate" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(def ^:private custom-profile
  "A custom profile strictly narrower than :default (all IO denied), with the
   full rlm engine-fn surface — the shape an embedder's bespoke profile takes."
  {:capability/name  :custom-narrow
   :cap/fs-read      :deny
   :cap/fs-write     :deny
   :cap/shell        :deny
   :cap/network      :deny
   :ns/granted       '#{clojure.core clojure.string clojure.edn clojure.set clojure.walk}
   :cap/java-classes {}
   :engine-fns       #{:FINAL :inspect :lm :map-lm :rlm :map-rlm :attach-rlm}
   :surface/fns      #{}})

(deftest custom-profile-persists-and-resume-rehydrates
  (let [dir (temp-dir!) sid "cap-rehydrate"
        responder (fe/responder
                    [["define" "```clojure\n(def x 7)\n(FINAL :defined)\n```"]
                     ["use"    "```clojure\n(FINAL (* x 6))\n```"]])
        cfg (fe/make-config {:adapter :fake :fake/respond responder :model "fake-model"
                             :harness :rlm :capability custom-profile
                             :store :sqlite :store/dir dir})]
    (try
      (let [h1 (fe/start-session! cfg {:id sid})]
        (is (= :final (:status (fe/run-turn! h1 "define"))))
        (testing "the full profile value is persisted on the session row"
          (let [persisted (get-in (fe/view h1) [:session :session/capability-profile])]
            (is (= :custom-narrow (:capability/name persisted)))
            (is (= :deny (:cap/shell persisted)))
            (is (= #{:FINAL :inspect :lm :map-lm :rlm :map-rlm :attach-rlm}
                   (:engine-fns persisted)))))
        (fe/close-session! h1))
      (testing "resume with the same cfg rehydrates the custom profile + vars"
        (let [h2 (fe/resume-session! cfg sid)]
          (is (= :custom-narrow (:capability/name (:capability h2))))
          (is (= :deny (:cap/shell (:capability h2))))
          (is (= 42 (:turn/final-value (fe/run-turn! h2 "use"))))
          (testing "the persisted profile survived the EDN round-trip as data"
            (let [persisted (get-in (fe/view h2) [:session :session/capability-profile])]
              (is (= custom-profile persisted))))
          (fe/close-session! h2)))
      (testing "resume with a WIDER cfg is clamped to the persisted profile"
        (let [wide-cfg (fe/make-config {:adapter :fake :fake/respond responder
                                        :model "fake-model" :harness :rlm
                                        :capability :trusted
                                        :store :sqlite :store/dir dir})
              h3 (fe/resume-session! wide-cfg sid)
              prof (:capability h3)]
          (is (= :custom-narrow (:capability/name prof))
              "the resumed identity is the session's persisted capability")
          (is (= :deny (:cap/shell prof)))
          (is (= :deny (:cap/network prof)))
          (is (= :deny (:cap/fs-write prof))
              "a :trusted cfg cannot widen what the session ran with")
          (fe/close-session! h3)))
      (finally (rm-rf! dir)))))

(deftest attach-rlm-works-from-a-custom-profile-source
  ;; Pre-fix this threw :capability/unknown-profile from source-profile name
  ;; resolution, because rlm children record the parent's custom profile NAME.
  (let [child-req?  (fn [req] (str/includes? (or (fake/last-user req) "") "Assigned task:"))
        attach-req? (fn [req] (str/includes? (or (fake/last-user req) "") "Use existing var base"))
        resp (fn [req]
               (let [lu (or (fake/last-user req) "")]
                 (cond
                   (attach-req? req) "```clojure\n(FINAL {:sum (+ base 2)})\n```"
                   (child-req? req)  "```clojure\n(def base 40)\n(FINAL {:base base})\n```"
                   (str/includes? lu "make child")
                   "```clojure\n(def prior (rlm \"make base\"))\n(FINAL (:rlm/value prior))\n```"
                   :else
                   "```clojure\n(FINAL (:rlm/value (attach-rlm (:rlm/head prior) \"Use existing var base and FINAL {:sum (+ base 2)}\")))\n```")))
        s (fe/start-session!
            (fe/make-config {:adapter :fake :fake/respond resp :model "fake-model"
                             :harness :rlm :capability custom-profile :max-steps 5}))]
    (is (= {:base 40} (:turn/final-value (fe/run-turn! s "make child"))))
    (let [res (fe/run-turn! s "attach to prior")]
      (is (= :final (:status res))
          (str "attach from a custom-profile source must not fail: " (:error res)))
      (is (= {:sum 42} (:turn/final-value res))
          "the attached child restored the source head's vars under the custom profile"))
    (fe/stop-session! s)))
