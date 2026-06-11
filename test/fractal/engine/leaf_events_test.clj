(ns fractal.engine.leaf-events-test
  "Leaf promotion: every lm/map-lm call is a durable :leaf/called event (spend
   is never invisible), leaf usage/cost rolls into the turn totals, and
   recorded leaf calls REPLAY through the replay responder."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter :as adapter]
            [fractal.engine.adapter.fake :as fake]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-leaf" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- leaf-req? [req]
  (str/starts-with? (str (get-in req [:cache :scope-id])) "fr:leaf:"))

(defn- costed
  "A call record with KNOWN usage/cost, to prove rollup arithmetic."
  [text req in out usd]
  (assoc (fake/text->call-record text req)
         :usage {:usage/status :known :usage/input-tokens in :usage/output-tokens out
                 :usage/cached-input-tokens 0 :usage/cache-write-tokens 0}
         :cost {:cost/status :known :cost/usd usd}
         :cache adapter/unknown-cache))

(defn- scripted
  "Root asks two leaves and FINALs their vector; leaves answer per input."
  [req]
  (cond
    (leaf-req? req)
    (let [lu (or (fake/last-user req) "")]
      (costed (if (str/includes? lu ":alpha") "ALPHA" "BETA") req 100 10 0.002))

    :else
    (costed "```clojure\n(FINAL [(lm :alpha \"shout it\") (lm :beta \"shout it\")])\n```"
            req 1000 50 0.01)))

(deftest leaf-calls-are-durable-events-and-roll-into-turn-cost
  (let [dir (temp-dir!)]
    (try
      (let [cfg (fe/make-config {:adapter :fake :fake/respond scripted
                                 :model "fake-model" :harness :rlm
                                 :store :sqlite :store/dir dir})
            s (fe/start-session! cfg {:id "leafy"})
            res (fe/run-turn! s "go")]
        (is (= ["ALPHA" "BETA"] (:turn/final-value res)))
        (let [v (fe/view s)
              leaves (:leaf-calls v)]
          (testing "one :leaf/called per lm call, stamped with turn context"
            (is (= 2 (count leaves)))
            (is (every? #(= (:turn/id res) (:leaf/turn-id %)) leaves))
            (is (every? #(= :ok (:leaf/status %)) leaves))
            (is (= ["ALPHA" "BETA"] (map #(fe/read-payload s (:leaf/text-or-ref %)) leaves))))
          (testing "turn usage/cost include the leaves (self-only = steps + own leaves)"
            (is (= 0.014 (get-in res [:turn/cost :cost/usd]) )
                "0.01 root step + 2 × 0.002 leaves")
            (is (= 1200 (get-in res [:turn/usage :usage/input-tokens]))
                "1000 root + 2 × 100 leaf input tokens")))

        (testing "recorded leaves REPLAY — full-tree deterministic re-execution"
          (let [respond (fe/replay-responder s ["leafy"])
                p (fe/start-session! (fe/make-config {:adapter :fake :fake/respond respond
                                                      :model "fake-model" :harness :rlm}))
                pres (fe/run-turn! p "go")]
            (is (= ["ALPHA" "BETA"] (:turn/final-value pres)))
            (is (= 0.014 (get-in pres [:turn/cost :cost/usd]))
                "replayed run reproduces the recorded accounting")
            (fe/stop-session! p)))
        (fe/close-session! s))
      (finally (rm-rf! dir)))))

(deftest failed-leaf-calls-are-recorded-too
  (let [boom (fn [req]
               (if (leaf-req? req)
                 (throw (ex-info "provider melted" {:error/type :provider/failed}))
                 (fake/text->call-record
                   "```clojure\n(def r (try (lm :x \"q\") (catch Exception e :leaf-died)))\n(FINAL r)\n```"
                   req)))
        s (fe/start-session! (fe/make-config {:adapter :fake :fake/respond boom
                                              :model "fake-model" :harness :rlm}))
        res (fe/run-turn! s "go")]
    (is (= :leaf-died (:turn/final-value res)))
    (let [leaves (:leaf-calls (fe/view s))]
      (is (= 1 (count leaves)))
      (is (= :error (:leaf/status (first leaves))))
      (is (some? (:leaf/error (first leaves))) "the spend/failure is on the record"))
    (fe/stop-session! s)))
