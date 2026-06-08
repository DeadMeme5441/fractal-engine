(ns fractal.engine.phase4-test
  "Phase 4 proofs: immutable Merkle heads, current-head resume preference,
   invocation/derivation lineage edges, and attach-rlm restoring from a selected
   prior head into a fresh derived child."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.store :as store]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-phase4" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- session-with [responder & {:as extra}]
  (fe/start-session!
    (fe/make-config (merge {:adapter :fake :fake/respond responder
                            :model "fake-model" :harness :rlm
                            :capability :default :max-steps 5}
                           extra))))

(defn- child-req? [req] (str/includes? (or (fake/last-user req) "") "Assigned task:"))
(defn- attach-req? [req] (str/includes? (or (fake/last-user req) "") "Use existing var base"))

(deftest turn-final-publishes-current-head-and-resume-prefers-it
  (let [dir (temp-dir!) sid "phase4-resume"
        responder (fe/responder
                    [["define" "```clojure (def x 7) (FINAL :defined)```"]
                     ["use"    "```clojure (FINAL (* x 6))```"]])
        cfg (fe/make-config {:adapter :fake :fake/respond responder :model "fake-model"
                             :harness :rlm :capability :default
                             :store :sqlite :store/dir dir})]
    (try
      (let [h1 (fe/start-session! cfg {:id sid})
            r1 (fe/run-turn! h1 "define")]
        (is (= :final (:status r1)))
        (let [v (fe/view h1)
              head-id (:current-head v)
              head (first (filter #(= head-id (:head/id %)) (:heads v)))
              turn-put-id (:event/id (first (filter #(= :turn/put (:event/type %)) (:events v))))]
          (testing "FINAL publishes a content-addressed current head"
            (is (string? head-id))
            (is (str/starts-with? head-id "sha256:"))
            (is (= :turn-final (:head/kind head)))
            (is (= sid (:head/session head)))
            (is (= (:vars-ref v) (:head/vars-ref head)))
            (is (= (:turn/final-ref (first (:turns v))) (:head/final-ref head)))
            (is (= [1 turn-put-id] (:head/event-range head)))))
        ;; Deliberately make the legacy :vars-ref stale after the head is published.
        ;; resume-session! must prefer current-head vars over this projection fallback.
        (let [stale (store/intern-payload! (:store h1)
                                           {:vars/version 1
                                            :vars {"x" {:status :ok :value 100}}}
                                           {:payload/kind :vars})]
          (store/append-event! (:store h1) sid
                               {:event/type :session/vars-snapshotted :vars-ref stale}))
        (fe/close-session! h1))
      (let [h2 (fe/resume-session! cfg sid)
            r2 (fe/run-turn! h2 "use")]
        (is (= :final (:status r2)))
        (is (= 42 (:turn/final-value r2))
            "resume restored vars from current-head, not the stale projection vars-ref")
        (fe/close-session! h2))
      (finally (rm-rf! dir)))))

(deftest rlm-records-invocation-edge-to-child-head
  (let [resp (fn [req]
               (let [lu (fake/last-user req)]
                 (cond
                   (child-req? req) "```clojure (def child-value 5) (FINAL {:n child-value})```"
                   (str/includes? lu "seed") "```clojure (def root-seed 1) (FINAL :seeded)```"
                   :else "```clojure (def child-env (rlm \"child NUM=5\")) (FINAL child-env)```")))
        s (session-with resp)]
    (is (= :seeded (:turn/final-value (fe/run-turn! s "seed"))))
    (let [parent-head (:current-head (fe/view s))
          res (fe/run-turn! s "spawn child")
          v (fe/view s)
          edge (first (filter #(= :invocation (:edge/type %)) (:edges v)))
          child-head (:edge/to-head edge)]
      (is (= {:n 5} (get-in res [:turn/final-value :rlm/value])))
      (testing "the parent view records a durable invocation edge"
        (is (str/starts-with? (:edge/id edge) "sha256:"))
        (is (= parent-head (:edge/from-head edge))))
      (testing "the edge points at the immutable child current head"
        (let [child-sid (:edge/child-session edge)
              child-view (store/current-view (:store s) child-sid)]
          (is (= child-head (:current-head child-view)))
          (is (= child-head (get-in (:turn/final-value res) [:rlm/head :head/id]))))))
    (fe/stop-session! s)))

(deftest attach-rlm-restores-selected-head-and-records-derivation
  (let [resp (fn [req]
               (let [lu (fake/last-user req)]
                 (cond
                   (attach-req? req) "```clojure (FINAL {:sum (+ base 2)})```"
                   (child-req? req)  "```clojure (def base 40) (FINAL {:base base})```"
                   (str/includes? lu "make child")
                   "```clojure (def prior (rlm \"make base\")) (FINAL prior)```"
                   :else
                   "```clojure (FINAL (:rlm/value (attach-rlm (:rlm/head prior) \"Use existing var base and FINAL {:sum (+ base 2)}\")))```")))
        s (session-with resp)]
    (is (= {:base 40} (get-in (fe/run-turn! s "make child") [:turn/final-value :rlm/value])))
    (let [res (fe/run-turn! s "attach to prior")
          v (fe/view s)
          edge (first (filter #(= :derivation (:edge/type %)) (:edges v)))]
      (is (= {:sum 42} (:turn/final-value res))
          "attached child restored the source head's base var")
      (is (some? edge) "the parent records a derivation edge")
      (is (= (get-in edge [:edge/source :head/id]) (:edge/from-head edge)))
      (is (= (:edge/from-head edge) (get-in edge [:edge/source :head/id])))
      (is (= (:edge/to-head edge) (get-in edge [:edge/target :head/id]))))
    (fe/stop-session! s)))
