(ns fractal.engine.surface-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal.engine.capability :as cap]
            [fractal.engine.surface :as surface]))

(defn- jira-surface
  ([] (jira-surface {}))
  ([overrides]
   (merge
     {:surface/id :jira
      :surface/version 1
      :surface/prompt "Use jira/search when you do not know the issue key."
      :surface/namespaces
      {'jira {'search {:doc "Search issues."
                       :arglists '([query opts])
                       :fn (fn [query opts] {:query query :opts opts})}
              'issue {:doc "Fetch one issue."
                      :arglists '([key opts])
                      :fn (fn [key opts] {:key key :opts opts})}}}}
     overrides)))

(deftest validates-surface-descriptors
  (testing "normalization stamps public shape without retaining function objects"
    (let [s (first (surface/normalize-surfaces [(jira-surface)]))]
      (is (= :jira (:surface/id s)))
      (is (= 1 (:surface/version s)))
      (is (string? (get-in s [:surface/stamp :surface/hash])))
      (is (= {:surface/id :jira
              :surface/version 1
              :surface/hash (get-in s [:surface/stamp :surface/hash])}
             (:surface/stamp s)))))
  (testing "reserved namespaces are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reserved"
          (surface/normalize-surfaces
            [(jira-surface {:surface/namespaces {'clojure.core {'x {:fn identity}}}})]))))
  (testing "namespaced function names are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unqualified"
          (surface/normalize-surfaces
            [(jira-surface {:surface/namespaces {'jira {'bad/name {:fn identity}}}})]))))
  (testing "non-functions are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"function"
          (surface/normalize-surfaces
            [(jira-surface {:surface/namespaces {'jira {'search {:fn :not-a-fn}}}})]))))
  (testing "duplicate qualified function symbols across surfaces are rejected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unique"
          (surface/normalize-surfaces
            [(jira-surface)
             {:surface/id :jira-copy
              :surface/version 1
              :surface/namespaces {'jira {'search {:fn identity}}}}]))))
  (testing "surface prompt maps are validated"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported"
          (surface/normalize-surfaces
            [(jira-surface {:surface/prompts {:unknown "nope"}})])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"strings or functions"
          (surface/normalize-surfaces
            [(jira-surface {:surface/prompts {:request 42}})])))))

(deftest prompt-card-lists-only-capability-exposed-functions
  (let [surfaces (surface/normalize-surfaces [(jira-surface)])
        profile (assoc (cap/default-profile) :surface/fns '#{jira/search})
        card (surface/prompt-card surfaces profile)]
    (is (str/includes? card "Additional SDK surfaces"))
    (is (str/includes? card "jira/search"))
    (is (str/includes? card "Search issues."))
    (is (str/includes? card "Use jira/search"))
    (is (not (str/includes? card "jira/issue")))))

(deftest renders-stable-and-dynamic-prompt-fragments
  (let [surfaces (surface/normalize-surfaces
                   [(jira-surface
                      {:surface/prompts
                       {:system "Prefer narrow issue lookups."
                        :request (fn [ctx]
                                   (str "request-turn=" (:turn/id ctx)
                                        " functions=" (pr-str (:surface/functions ctx))))
                        :leaf (fn [ctx]
                                (str "leaf-mode=" (:mode ctx)
                                     " input=" (pr-str (:input ctx))))}})])
        allowed (assoc (cap/default-profile) :surface/fns '#{jira/search})
        denied (assoc (cap/default-profile) :surface/fns #{})
        system-card (surface/prompt-card surfaces allowed)
        request-card (surface/request-prompt-card surfaces allowed {:turn/id :t1})
        leaf-card (surface/leaf-prompt-card surfaces allowed {:mode :edn :input {:n 1}})]
    (is (str/includes? system-card "Prefer narrow issue lookups."))
    (is (str/includes? request-card "SDK surface request context:"))
    (is (str/includes? request-card "request-turn=:t1"))
    (is (str/includes? request-card "jira/search"))
    (is (str/includes? leaf-card "SDK surface leaf context:"))
    (is (str/includes? leaf-card "leaf-mode=:edn"))
    (is (nil? (surface/request-prompt-card surfaces denied {:turn/id :t1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must return nil or string"
          (surface/request-prompt-card
            (surface/normalize-surfaces
              [(jira-surface {:surface/prompts {:request (constantly {:bad true})}})])
            allowed
            {})))))

(deftest factories-receive-session-context-when-exposed
  (let [surfaces (surface/normalize-surfaces
                   [{:surface/id :ctx
                     :surface/version 1
                     :surface/namespaces
                     {'ctx {'session-id
                            {:doc "Return the session id."
                             :factory (fn [ctx]
                                        (fn [] (:session/id ctx)))}}}}])
        profile (assoc (cap/default-profile) :surface/fns '#{ctx/session-id})
        namespaces (surface/sci-namespaces {:session-id "s-test"
                                            :cfg {}
                                            :capability profile}
                                           surfaces
                                           profile)]
    (is (= "s-test" ((get-in namespaces ['ctx 'session-id]))))))

(deftest stamp-compatibility-is-order-independent-and-strict
  (let [surfaces (surface/normalize-surfaces [(jira-surface)
                                              {:surface/id :git
                                               :surface/version 1
                                               :surface/namespaces
                                               {'git {'status {:fn (constantly :ok)}}}}])
        stamps (surface/stamps surfaces)]
    (is (nil? (surface/assert-compatible! (reverse stamps) surfaces)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"configured SDK surfaces"
          (surface/assert-compatible! stamps
                                      (surface/normalize-surfaces
                                        [(jira-surface {:surface/version 2})]))))))
