(ns fractal.engine.sdk-polish-test
  "The three SDK gaps closed after the consumer-evidence review: the observation
   window as config (:observe), run-turn-with-contract! (the validate→correct→
   retry loop every downstream consumer hand-rolled), and tree-shaped surface
   replay ((fn,args)-keyed routing)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.capability :as capability]
            [fractal.engine.observe :as observe]))

;; ---------------------------------------------------------------------------
;; :observe — the fit-or-stub window is config
;; ---------------------------------------------------------------------------

(deftest observation-window-is-config
  (let [big-rec {:eval/block-index 0 :eval/status :ok :eval/stdout ""
                 :eval/raw-value (vec (range 200))}]
    (testing "default window stubs a value over 400 chars"
      (is (str/includes? (observe/render-observation [big-rec] {:final? true})
                         "«vector, 200 items»")))
    (testing "a widened window shows the whole value"
      (is (str/includes? (observe/render-observation [big-rec] {:final? true :ok-fit 5000})
                         "[0 1 2")))
    (testing "a NARROWED window stubs values the default would show"
      (is (str/includes? (observe/render-observation
                           [{:eval/block-index 0 :eval/status :ok :eval/stdout ""
                             :eval/raw-value "a string of moderate length, well under 400"}]
                           {:final? true :ok-fit 10})
                         "«string,"))))
  (testing "cfg validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"observe"
          (fe/make-config {:adapter :fake :fake/respond (constantly "x")
                           :model "fake-model" :observe {:ok-fit 0}})))
    (is (= 50 (get-in (fe/make-config {:adapter :fake :fake/respond (constantly "x")
                                       :model "fake-model" :observe {:ok-fit 50}})
                      [:observe :ok-fit])))
    (is (= 1200 (get-in (fe/make-config {:adapter :fake :fake/respond (constantly "x")
                                         :model "fake-model" :observe {:ok-fit 50}})
                        [:observe :final-fit]))
        "unspecified caps keep their defaults"))
  (testing "end to end: the model SEES per the configured window"
    (let [seen (atom nil)
          resp (fn [req]
                 (let [lu (or (fake/last-user req) "")]
                   (if (str/starts-with? lu "Observation:")
                     (do (reset! seen lu) "```clojure\n(FINAL :done)\n```")
                     "```clojure\n(vec (range 200))\n```")))
          s (fe/start-session! (fe/make-config {:adapter :fake :fake/respond resp
                                                :model "fake-model" :harness :rlm
                                                :observe {:ok-fit 5000}}))]
      (is (= :done (:turn/final-value (fe/run-turn! s "go"))))
      (is (str/includes? @seen "[0 1 2") "the widened window reached the live loop")
      (fe/stop-session! s))))

;; ---------------------------------------------------------------------------
;; run-turn-with-contract!
;; ---------------------------------------------------------------------------

(deftest contract-rejects-then-accepts
  (let [resp (fn [req]
               (if (str/includes? (or (fake/last-user req) "") "rejected by the caller's contract")
                 "```clojure\n(FINAL {:answer 42})\n```"
                 "```clojure\n(FINAL {:answer 41})\n```"))
        s (fe/start-session! (fe/make-config {:adapter :fake :fake/respond resp
                                              :model "fake-model" :harness :rlm}))
        res (fe/run-turn-with-contract! s "compute"
              {:validate (fn [r] (when (not= 42 (:answer (:turn/final-value r)))
                                   "answer must be 42"))})]
    (is (= {:answer 42} (:turn/final-value res)) "the correction turn fixed it")
    (is (nil? (:contract/rejected res)))
    (is (= 2 (count (:turns (fe/view s)))) "exactly one correction turn was spent")
    (fe/stop-session! s)))

(deftest contract-exhaustion-is-explicit
  (let [s (fe/start-session! (fe/make-config {:adapter :fake
                                              :fake/respond (constantly "```clojure\n(FINAL :wrong)\n```")
                                              :model "fake-model" :harness :rlm}))
        res (fe/run-turn-with-contract! s "compute"
              {:validate (constantly "never good enough")
               :max-attempts 2})]
    (is (= :final (:status res)) "the last result is returned, not swallowed")
    (is (= "never good enough" (:contract/rejected res)))
    (is (= 2 (:contract/attempts res)))
    (fe/stop-session! s)))

;; ---------------------------------------------------------------------------
;; Tree-shaped surface replay
;; ---------------------------------------------------------------------------

(def ^:private tree-profile
  (assoc (capability/default-profile)
         :capability/name :treeworld
         :surface/fns '#{world/get}))

(defn- tree-surface [f]
  {:surface/id :world :surface/version 1
   :surface/namespaces {'world {'get {:doc "world read" :fn f}}}})

(deftest recursive-tree-surface-replay
  (let [counter (atom 0)
        resp (fn [req]
               (if (str/includes? (or (fake/last-user req) "") "Assigned task:")
                 "```clojure\n(FINAL (world/get :child))\n```"
                 "```clojure\n(def mine (world/get :root))\n(FINAL [mine (:rlm/value (rlm \"read the child slice\"))])\n```"))
        rec-cfg (fe/make-config {:adapter :fake :fake/respond resp
                                 :model "fake-model" :harness :rlm
                                 :surfaces [(tree-surface (fn [k] {:k k :v (swap! counter inc)}))]
                                 :capability tree-profile
                                 :surface/record? true})
        r (fe/start-session! rec-cfg {:id "tree-root"})
        res (fe/run-turn! r "go")
        child-sid (-> (fe/view r) :edges first :edge/to-session)
        all-calls (into (fe/surface-calls r "tree-root")
                        (fe/surface-calls r child-sid))]
    (is (= [{:k :root :v 1} {:k :child :v 2}] (:turn/final-value res)))
    (is (= 2 (count all-calls)) "root and child each recorded their own call")
    (testing "the whole TREE replays — child surface calls route by (fn,args)"
      (let [landmine (tree-surface (fn [_] (throw (ex-info "LIVE FN INVOKED" {}))))
            p (fe/start-session!
                (fe/make-config {:adapter :fake :fake/respond resp
                                 :model "fake-model" :harness :rlm
                                 :surfaces [landmine]
                                 :capability tree-profile
                                 :surface/replay-calls all-calls}))
            pres (fe/run-turn! p "go")]
        (is (= [{:k :root :v 1} {:k :child :v 2}] (:turn/final-value pres))
            "both sessions served from the recording; live world never touched")
        (fe/stop-session! p)))
    (fe/stop-session! r)))
