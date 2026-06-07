(ns fractal.engine.store-test
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.store :as store]
            [fractal.engine.payload :as payload]))

(defn- p-ref [] (payload/ref-for {:vars/version 1 :vars {}} :vars))

(deftest apply-event-is-pure
  (testing "apply-event does not mutate the input view"
    (let [v0 (store/empty-view)
          v1 (store/apply-event v0 {:event/id 1 :event/type :turn/started
                                    :turn {:turn/id 1 :turn/status :running}})]
      (is (= [] (:turns v0)) "input view unchanged")
      (is (= 1 (count (:turns v1)))))))

(deftest fold-builds-structure
  (let [events [{:event/id 1 :event/type :session/started
                 :session {:session/id "s-1" :session/status :running}}
                {:event/id 2 :event/type :message/appended
                 :message {:message/id 1 :message/role :user :message/content-or-ref "hi"}}
                {:event/id 3 :event/type :turn/started
                 :turn {:turn/id 1 :turn/status :running}}
                {:event/id 4 :event/type :step/started
                 :step {:step/id 1 :step/status :running}}
                {:event/id 5 :event/type :eval/added
                 :eval {:eval/id 1 :eval/status :ok}}
                {:event/id 6 :event/type :step/put
                 :step {:step/id 1 :step/status :done}}
                {:event/id 7 :event/type :turn/put
                 :turn {:turn/id 1 :turn/status :final}}]
        v (reduce store/apply-event (store/empty-view) events)]
    (testing "creating events conj, put events replace-by-id"
      (is (= "s-1" (get-in v [:session :session/id])))
      (is (= 1 (count (:messages v))))
      (is (= 1 (count (:turns v))) "put replaced, did not append a 2nd turn")
      (is (= :final (:turn/status (first (:turns v)))))
      (is (= 1 (count (:steps v))))
      (is (= :done (:step/status (first (:steps v)))))
      (is (= 1 (count (:evals v)))))
    (testing "counters max to the highest assigned ids"
      (is (= {:event 7 :message 1 :turn 1 :step 1 :eval 1} (:counters v))))
    (testing "compaction folds vars-ref + boundary + a message in ONE event"
      (let [r (p-ref)
            v2 (store/apply-event v {:event/id 8 :event/type :session/compacted
                                     :vars-ref r
                                     :compact-from-event-id 8
                                     :message {:message/id 2 :message/role :user
                                               :message/content-or-ref "summary"}})]
        (is (= r (:vars-ref v2)))
        (is (= 8 (:compact-from-event-id v2)))
        (is (= 2 (count (:messages v2))))
        (is (= 2 (get-in v2 [:counters :message])))))))

(deftest status-transitions
  (let [v (reduce store/apply-event (store/empty-view)
                  [{:event/id 1 :event/type :session/started
                    :session {:session/id "s" :session/status :running}}
                   {:event/id 2 :event/type :session/stop-requested}
                   {:event/id 3 :event/type :session/stopped}])]
    (is (= :stopped (get-in v [:session :session/status]))))
  (let [err {:error/type :provider/failed :error/message "x"}
        v (reduce store/apply-event (store/empty-view)
                  [{:event/id 1 :event/type :session/started
                    :session {:session/id "s" :session/status :running}}
                   {:event/id 2 :event/type :session/error :error err}])]
    (is (= :error (get-in v [:session :session/status])))
    (is (= err (:error v)))))
