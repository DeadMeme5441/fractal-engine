(ns fractal.engine.store-contract-test
  "The SHARED SessionStore contract (02 §8): the SAME assertions run against BOTH
   MemoryStore and SqliteStore, because they must behave identically through the
   port. Store-impl-specific behaviour (persist-before-fold, durable recovery,
   on-disk blobs, resume) lives in store_sqlite_test; THIS file is the port-level
   equivalence proof — it never reaches past the protocol into impl internals."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]
            [fractal.engine.store.sqlite :as sqlite]))

;; ---------------------------------------------------------------------------
;; Temp-dir plumbing (sqlite factory) + a seeded session
;; ---------------------------------------------------------------------------

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-contract" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defn- seed! [s sid]
  (store/create-session! s {:session/id sid :session/status :running})
  (store/append-event! s sid {:event/type :session/started
                              :session {:session/id sid :session/status :running}}))

;; ---------------------------------------------------------------------------
;; The contract invariants — each a fn of a `new-store` thunk (02 §8)
;; ---------------------------------------------------------------------------

(defn- check-store-assigns-all-ids [new-store]
  (let [s (new-store) sid "c-ids"]
    (seed! s sid)
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
      (testing "assigned ids == folded counter maxima (02 §8.1)"
        (is (= {:event 5 :message 1 :turn 1 :step 1 :eval 1}
               (:counters (store/current-view s sid))))))))

(defn- check-fold-reproduces-view [new-store]
  (let [s (new-store) sid "c-fold"]
    (seed! s sid)
    (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})
    (store/append-event! s sid {:event/type :message/appended
                                :message {:message/role :user :message/content-or-ref "hi"}})
    (let [v (store/current-view s sid)
          refolded (reduce store/apply-event (store/empty-view) (:events v))]
      (is (= (dissoc v :events) (dissoc refolded :events))
          "re-folding the log reproduces the view structure"))))

(defn- check-dedup-content-addressing [new-store]
  (let [s (new-store)
        v (vec (range 1000))
        r1 (store/intern-payload! s v {:payload/kind :final})
        r2 (store/intern-payload! s v {:payload/kind :final})]
    (is (= r1 r2) "same value ⇒ same content ref (dedup)")
    (is (.startsWith ^String (:payload/id r1) "sha256:") "content-addressed id")
    (is (= v (store/read-payload* s r1)) "ref dereferences to the original value")))

(defn- check-idempotent-create [new-store]
  (let [s (new-store) sid "c-idem"
        h1 (store/create-session! s {:session/id sid :session/status :running})]
    (store/append-event! s sid {:event/type :session/started
                                :session {:session/id sid :session/status :running}})
    (reset! (:sci-ctx h1) ::ctx)
    (let [h2 (store/create-session! s {:session/id sid :session/status :running})]
      (testing "a 2nd create returns the existing slot, never nuking state"
        (is (= 1 (count (:events (store/current-view s sid)))))
        (is (identical? (:sci-ctx h1) (:sci-ctx h2)) "sci-ctx atom is stable")
        (is (= ::ctx @(:sci-ctx h2)) "ctx value preserved")
        (is (identical? (:busy h1) (:busy h2)) "busy atom is stable")))))

(defn- check-strong-current-view [new-store]
  (let [s (new-store) sid "c-strong"]
    (seed! s sid)
    (let [ev (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})]
      (is (= (:event/id ev) (get-in (store/current-view s sid) [:counters :event]))
          "read-your-writes — no async lag"))))

(defn- check-events-since [new-store]
  (let [s (new-store) sid "c-since"]
    (seed! s sid)
    (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})
    (store/append-event! s sid {:event/type :step/started :step {:step/status :running}})
    (is (= [2 3] (map :event/id (store/events-since s sid 1))))
    (is (= [3] (map :event/id (store/events-since s sid 2))))
    (is (empty? (store/events-since s sid 99)))))

(defn- check-peek-next-id [new-store]
  (let [s (new-store) sid "c-peek"]
    (seed! s sid)
    (let [peeked (store/peek-next-id s sid :turn)
          ev (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})]
      (is (= peeked (get-in ev [:turn :turn/id]))))))

(defn- check-verify-no-dangling-refs [new-store]
  (let [s (new-store) sid "c-refs"]
    (seed! s sid)
    (let [ref (store/intern-payload! s (vec (range 1000)) {:payload/kind :message})]
      (store/append-event! s sid {:event/type :message/appended
                                  :message {:message/role :user :message/content-or-ref ref}})
      (is (empty? (store/verify-no-dangling-refs s sid))))))

(defn- check-append-events-batch [new-store]
  (let [s (new-store) sid "c-batch"]
    (seed! s sid)
    (let [evs (store/append-events! s sid
                [{:event/type :turn/started :turn {:turn/status :running}}
                 {:event/type :step/started :step {:step/status :running}}
                 {:event/type :eval/added   :eval {:eval/status :ok}}])]
      (testing "one critical section assigns consecutive event + entity ids"
        (is (= [2 3 4] (map :event/id evs)))
        (is (= 1 (get-in (nth evs 0) [:turn :turn/id])))
        (is (= 1 (get-in (nth evs 1) [:step :step/id])))
        (is (= 1 (get-in (nth evs 2) [:eval :eval/id]))))
      (testing "the whole batch folded atomically into the view"
        (let [v (store/current-view s sid)]
          (is (= {:event 4 :message 0 :turn 1 :step 1 :eval 1} (:counters v)))
          (is (= 1 (count (:turns v))))
          (is (= 1 (count (:steps v))))
          (is (= 1 (count (:evals v)))))))))

(def ^:private invariants
  [["store-assigns-all-ids"    check-store-assigns-all-ids]
   ["fold-reproduces-view"     check-fold-reproduces-view]
   ["dedup-content-addressing" check-dedup-content-addressing]
   ["idempotent-create"        check-idempotent-create]
   ["strong-current-view"      check-strong-current-view]
   ["events-since"             check-events-since]
   ["peek-next-id"             check-peek-next-id]
   ["verify-no-dangling-refs"  check-verify-no-dangling-refs]
   ["append-events-batch"      check-append-events-batch]])

;; ---------------------------------------------------------------------------
;; Run the SAME invariants against each impl
;; ---------------------------------------------------------------------------

(deftest memory-store-contract
  (doseq [[nm f] invariants]
    (testing (str "MemoryStore · " nm)
      (f #(mem/memory-store)))))

(deftest sqlite-store-contract
  (let [root (temp-dir!) stores (atom [])]
    (try
      (doseq [[nm f] invariants]
        (testing (str "SqliteStore · " nm)
          (f (fn []
               (let [s (sqlite/sqlite-store {:dir (io/file root (str (gensym "s")))})]
                 (swap! stores conj s)
                 s)))))
      (finally
        (doseq [s @stores] (try (sqlite/close! s) (catch Throwable _ nil)))
        (rm-rf! root)))))
