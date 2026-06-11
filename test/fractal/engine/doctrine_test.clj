(ns fractal.engine.doctrine-test
  "37i · embedder-injected base doctrine: validated + stamped at config time,
   REPLACES the harness-selected base prompt in request assembly, inherited by
   children via cfg, absent ⇒ byte-for-byte built-in behavior."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]))

(def ^:private custom-text
  "You are a test frame. GATHER then REASON then FINAL. No scouting doctrine applies.")

(def ^:private custom-doctrine
  {:doctrine/name :test/frame :doctrine/version 3 :doctrine/text custom-text})

(deftest doctrine-config-validation
  (testing "a valid doctrine is stamped name/version/hash + text"
    (let [cfg (fe/make-config {:adapter :fake :fake/respond (constantly "x")
                               :model "fake-model" :doctrine custom-doctrine})
          d   (:doctrine cfg)]
      (is (= :test/frame (:prompt/name d)))
      (is (= 3 (:prompt/version d)))
      (is (.startsWith ^String (:prompt/hash d) "sha256:"))
      (is (= custom-text (:prompt/text d)))))
  (testing "invalid shapes are rejected with :config/invalid-doctrine"
    (doseq [bad [{:doctrine/name "not-kw" :doctrine/version 1 :doctrine/text "t"}
                 {:doctrine/name :a :doctrine/version 0 :doctrine/text "t"}
                 {:doctrine/name :a :doctrine/version 1 :doctrine/text "   "}
                 {:doctrine/name :a :doctrine/version 1}
                 "just-a-string"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doctrine"
            (fe/make-config {:adapter :fake :fake/respond (constantly "x")
                             :model "fake-model" :doctrine bad}))
          (pr-str bad)))))

(defn- capture-system-texts
  "A responder that records each request's system text before answering."
  [sink reply-fn]
  (fn [request]
    (swap! sink conj (->> (:messages request)
                          (filter #(= :system (:role %)))
                          first :content))
    (reply-fn request)))

(deftest injected-doctrine-replaces-the-base-prompt
  (let [seen (atom [])
        cfg  (fe/make-config
               {:adapter :fake :model "fake-model" :harness :rlm
                :doctrine custom-doctrine
                :fake/respond (capture-system-texts
                                seen
                                (constantly "```clojure\n(FINAL :ok)\n```"))})
        s (fe/start-session! cfg)]
    (is (= :final (:status (fe/run-turn! s "go"))))
    (is (= custom-text (first @seen))
        "the system message base IS the injected doctrine, not the built-in")
    (fe/stop-session! s)))

(deftest absent-doctrine-keeps-builtin-behavior
  (let [seen (atom [])
        cfg  (fe/make-config
               {:adapter :fake :model "fake-model" :harness :rlm
                :fake/respond (capture-system-texts
                                seen
                                (constantly "```clojure\n(FINAL :ok)\n```"))})
        s (fe/start-session! cfg)]
    (is (= :final (:status (fe/run-turn! s "go"))))
    (is (str/starts-with? (first @seen) "You are the active RLM")
        "no :doctrine ⇒ the built-in rlm doctrine")
    (fe/stop-session! s)))

(deftest children-inherit-the-injected-doctrine
  (let [seen (atom [])
        resp (capture-system-texts
               seen
               (fn [req]
                 (if (str/includes? (or (fake/last-user req) "") "Assigned task:")
                   "```clojure\n(FINAL :child-done)\n```"
                   "```clojure\n(FINAL (:rlm/value (rlm \"do the sub-task\")))\n```")))
        cfg  (fe/make-config {:adapter :fake :model "fake-model" :harness :rlm
                              :doctrine custom-doctrine :fake/respond resp})
        s (fe/start-session! cfg)]
    (is (= :child-done (:turn/final-value (fe/run-turn! s "delegate"))))
    (is (= 2 (count @seen)) "one root request + one child request")
    (is (every? #(= custom-text %) @seen)
        "the child session runs under the same injected doctrine (cfg inheritance)")
    (fe/stop-session! s)))
