(ns fractal.engine.live-test
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.store :as store]
            [fractal.engine.live :as live]
            [fractal.engine.store.memory :as mem])
  (:import [java.util.concurrent CountDownLatch TimeUnit]))

(defn- wait-for
  "Poll pred until true or timeout-ms elapses; returns the final pred value."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (let [v (pred)]
        (if (or v (> (System/currentTimeMillis) deadline))
          v
          (do (Thread/sleep 5) (recur)))))))

(defn- fresh [sid]
  (let [s (mem/memory-store)]
    (store/create-session! s {:session/id sid :session/status :running})
    s))

(defn- fresh-bounded [sid bound]
  (let [s (mem/memory-store)]
    (store/create-session! s (with-meta {:session/id sid :session/status :running}
                               {:fractal.engine.store.memory/live {:bound bound}}))
    s))

(defn- turn-ev [] {:event/type :turn/started :turn {:turn/status :running}})

(deftest ordered-delivery
  (let [sid "live-order" s (fresh sid)
        received (atom [])
        unsub (store/subscribe! s sid (fn [ev] (when (:event/id ev) (swap! received conj (:event/id ev)))))]
    (dotimes [_ 5] (store/append-event! s sid (turn-ev)))
    (wait-for #(= 5 (count @received)) 2000)
    (is (= [1 2 3 4 5] @received) "events delivered in :event/id order")
    (unsub)))

(deftest throwing-subscriber-cannot-break-writes
  (let [sid "live-throw" s (fresh sid)
        good (atom [])]
    (store/subscribe! s sid (fn [_] (throw (RuntimeException. "boom"))))
    (store/subscribe! s sid (fn [ev] (when (:event/id ev) (swap! good conj (:event/id ev)))))
    (testing "appends return normally despite a throwing subscriber"
      (dotimes [_ 3] (is (:event/id (store/append-event! s sid (turn-ev))))))
    (testing "the well-behaved subscriber still receives every event"
      (is (= [1 2 3] (wait-for #(when (= 3 (count @good)) @good) 2000))))))

(deftest slow-subscriber-cannot-stall-writes
  (let [sid "live-slow" s (fresh sid)]
    (store/subscribe! s sid (fn [_] (Thread/sleep 200)))   ; deliberately slow
    (let [t0 (System/nanoTime)
          _  (dotimes [_ 5] (store/append-event! s sid (turn-ev)))
          elapsed-ms (/ (- (System/nanoTime) t0) 1e6)]
      ;; 5 appends would take >=1000ms if delivery were inline; off-lock it is ~0
      (is (< elapsed-ms 150) (str "appends were not stalled by the slow subscriber (" elapsed-ms "ms)")))))

(deftest overflow-drops-transients-keeps-durables-emits-gap
  (let [sid "live-overflow" s (fresh-bounded sid 16)
        latch (CountDownLatch. 1)
        first? (atom true)
        seen (atom {:durable [] :transient 0 :gap 0})]
    (store/subscribe! s sid
      (fn [item]
        (when (compare-and-set! first? true false)
          (.await latch 5 TimeUnit/SECONDS))   ; block the dispatcher so the queue fills
        (case (:event/type item)
          :subscribe/gap (swap! seen update :gap inc)
          :delta/token   (swap! seen update :transient inc)
          (when (:event/id item) (swap! seen update :durable conj (:event/id item))))))
    ;; prime: one transient becomes the first delivered item → dispatcher blocks on it
    (store/notify-transient s sid {:event/type :delta/token :text "prime"})
    (Thread/sleep 80)
    ;; flood while the dispatcher is blocked: transients overflow, durables must survive
    (dotimes [_ 200] (store/notify-transient s sid {:event/type :delta/token :text "x"}))
    (dotimes [_ 5] (store/append-event! s sid (turn-ev)))
    (.countDown latch)
    (wait-for #(= 5 (count (:durable @seen))) 3000)
    (testing "every durable event survived (only its push, never the log, is at risk)"
      (is (= #{1 2 3 4 5} (set (:durable @seen)))))
    (testing "transients were shed and at least one coalesced gap was emitted"
      (is (< (:transient @seen) 201) "some transient deltas were dropped")
      (is (pos? (:gap @seen)) "a :subscribe/gap marker was emitted"))))

(deftest reentrant-append-from-callback-throws
  (let [sid "live-reentrant" s (fresh sid)
        caught (atom nil)]
    (store/subscribe! s sid
      (fn [ev]
        (when (:event/id ev)
          (reset! caught
                  (try (store/append-event! s sid (turn-ev)) :no-throw
                       (catch clojure.lang.ExceptionInfo e (:error/type (ex-data e))))))))
    (store/append-event! s sid (turn-ev))
    (is (= :subscribe/reentrant (wait-for #(deref caught) 2000)))))

(deftest progress-is-pure-over-a-view
  (let [sid "live-progress" s (fresh sid)]
    (store/append-event! s sid {:event/type :session/started
                                :session {:session/id sid :session/status :running}})
    (store/append-event! s sid (turn-ev))
    (let [p (live/progress (store/current-view s sid))]
      (is (= sid (:session/id p)))
      (is (:running? p))
      (is (= 1 (:turn-count p)))
      (is (= 1 (:current-turn p)))
      (is (:in-flight p))
      (is (= 2 (:last-event-id p))))))
