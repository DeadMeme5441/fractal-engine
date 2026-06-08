(ns fractal.engine.store-memory-test
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]))

(defn- start! [s sid]
  (store/create-session! s {:session/id sid :session/status :running}))

(defn- seed-session! [s sid]
  (start! s sid)
  (store/append-event! s sid {:event/type :session/started
                              :session {:session/id sid :session/status :running}}))

(deftest store-assigns-all-ids
  (let [s (mem/memory-store) sid "s-ids"]
    (seed-session! s sid)
    (let [t  (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})
          st (store/append-event! s sid {:event/type :step/started :step {:step/status :running}})
          m  (store/append-event! s sid {:event/type :message/appended
                                         :message {:message/role :assistant :message/content-or-ref "x"}})
          e  (store/append-event! s sid {:event/type :eval/added :eval {:eval/status :ok}})]
      (testing "creating events mint fresh entity ids from the counters"
        (is (= 1 (get-in t [:turn :turn/id])))
        (is (= 1 (get-in st [:step :step/id])))
        (is (= 1 (get-in m [:message :message/id])))
        (is (= 1 (get-in e [:eval :eval/id]))))
      (testing "every event got a monotonic :event/id + an :event/at"
        (is (= [2 3 4 5] (map :event/id [t st m e])))
        (is (every? :event/at [t st m e])))
      (testing "assigned ids == folded counter maxima (invariant 02 §8.1)"
        (let [v (store/current-view s sid)]
          (is (= {:event 5 :message 1 :turn 1 :step 1 :eval 1} (:counters v))))))))

(deftest fold-reproduces-the-view
  (let [s (mem/memory-store) sid "s-fold"]
    (seed-session! s sid)
    (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})
    (store/append-event! s sid {:event/type :message/appended
                                :message {:message/role :user :message/content-or-ref "hi"}})
    (let [v (store/current-view s sid)
          refolded (reduce store/apply-event (store/empty-view) (:events v))]
      (is (= (dissoc v :events) (dissoc refolded :events))
          "re-folding the log reproduces the view structure"))))

(deftest idempotent-create-preserves-slot
  (let [s (mem/memory-store) sid "s-idem"]
    (let [h1 (start! s sid)]
      (store/append-event! s sid {:event/type :session/started
                                  :session {:session/id sid :session/status :running}})
      (reset! (:sci-ctx h1) ::ctx)
      (let [h2 (start! s sid)]
        (testing "a 2nd create returns the existing slot, never nuking state"
          (is (= 1 (count (:events (store/current-view s sid)))))
          (is (identical? (:sci-ctx h1) (:sci-ctx h2)) "sci-ctx atom is stable")
          (is (= ::ctx @(:sci-ctx h2)) "ctx value preserved")
          (is (identical? (:busy h1) (:busy h2)) "busy atom is stable"))))))

(deftest dedup-and-content-addressing
  (let [s (mem/memory-store)
        v (vec (range 1000))
        r1 (store/intern-payload! s v {:payload/kind :final})
        r2 (store/intern-payload! s v {:payload/kind :final})]
    (is (= r1 r2))
    (is (.startsWith ^String (:payload/id r1) "sha256:"))
    (is (= 1 (count @(:blobs s))))
    (is (= v (store/read-payload* s r1)))))

(deftest current-view-is-read-your-writes
  (let [s (mem/memory-store) sid "s-strong"]
    (seed-session! s sid)
    (let [ev (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})]
      ;; immediately visible — no async lag
      (is (= (:event/id ev) (get-in (store/current-view s sid) [:counters :event]))))))

(deftest events-since-recovers-backlog
  (let [s (mem/memory-store) sid "s-since"]
    (seed-session! s sid)
    (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})
    (store/append-event! s sid {:event/type :step/started :step {:step/status :running}})
    (is (= [2 3] (map :event/id (store/events-since s sid 1))))
    (is (= [3] (map :event/id (store/events-since s sid 2))))
    (is (empty? (store/events-since s sid 99)))))

(deftest peek-next-id-predicts-assignment
  (let [s (mem/memory-store) sid "s-peek"]
    (seed-session! s sid)
    (let [peeked (store/peek-next-id s sid :turn)
          ev (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})]
      (is (= peeked (get-in ev [:turn :turn/id]))))))

(deftest verify-no-dangling-refs-passes
  (let [s (mem/memory-store) sid "s-refs"]
    (seed-session! s sid)
    (let [ref (store/intern-payload! s (vec (range 1000)) {:payload/kind :message})]
      (store/append-event! s sid {:event/type :message/appended
                                  :message {:message/role :user :message/content-or-ref ref}})
      (is (empty? (store/verify-no-dangling-refs s sid))))))
