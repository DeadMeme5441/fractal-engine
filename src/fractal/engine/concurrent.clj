(ns fractal.engine.concurrent
  "L0 · concurrency primitives (engine-free). `with-deadline` runs a body under
   a single wall-clock budget on a DAEMON thread, so a stuck provider call can
   neither block JVM exit nor be relied on to stop (an orphaned call may still
   run — and cost — after the deadline fires; documented, 07 §3).

   Phase 3 adds the bounded fan-out primitive `bounded-fanout` (≤cap workers, a
   daemon pool, dynvar propagation via `bound-fn`, partial-failure-tolerant) +
   a `semaphore`/`with-permit` pair (the GLOBAL leaf governor). The recursion ns
   composes these; the engine semantics live there, not here."
  (:import [java.util.concurrent Callable Executors Semaphore ThreadFactory TimeUnit]))

(defn with-deadline*
  "Run `thunk` on a daemon thread under a `timeout-ms` wall-clock budget.
   Returns the thunk's value; re-throws its exception on the caller thread; on
   timeout throws `ex-info {:error/type :fractal/deadline}`. The worker is a
   daemon (JVM-exit-safe) and is interrupted as a courtesy when the deadline
   fires — but is never awaited (the call is orphaned)."
  [timeout-ms thunk]
  (let [result (promise)
        worker (Thread.
                 ^Runnable
                 (fn []
                   (deliver result
                            (try {:value (thunk)}
                                 (catch Throwable e {:error e}))))
                 "fractal-deadline")]
    (.setDaemon worker true)
    (.start worker)
    (let [outcome (deref result timeout-ms ::timeout)]
      (if (identical? outcome ::timeout)
        (do (.interrupt worker)
            (throw (ex-info (str "deadline exceeded after " timeout-ms "ms")
                            {:error/type :fractal/deadline :timeout-ms timeout-ms})))
        (if-let [e (:error outcome)]
          (throw e)
          (:value outcome))))))

(defmacro with-deadline
  "Macro sugar: `(with-deadline ms expr...)` ≡ `(with-deadline* ms (fn [] expr...))`."
  [timeout-ms & body]
  `(with-deadline* ~timeout-ms (fn [] ~@body)))

;; ---------------------------------------------------------------------------
;; Bounded fan-out (Phase 3) — order-preserved, partial-failure-tolerant
;; ---------------------------------------------------------------------------

(defn- daemon-pool [prefix n]
  (let [counter (atom 0)]
    (Executors/newFixedThreadPool
      (int (max 1 n))
      (reify ThreadFactory
        (newThread [_ r]
          (doto (Thread. ^Runnable r (str prefix "-" (swap! counter inc)))
            (.setDaemon true)))))))

(defn bounded-fanout
  "Run `(f idx x)` over `xs` on a bounded daemon thread pool of ≤`pool-size`
   threads, PRESERVING input order. Partial failure NEVER throws: each slot is
   captured independently as a result map

     {:ok true  :index i :value v}   | {:ok false :index i :error <ex-data-or-map>}

   and the returned vector is sorted by `:index` (index-aligned to `xs`).
   `bound-fn` carries the caller thread's dynamic bindings (the host
   *current-turn-id*/*current-step-id*/*current-eval-id*, 03 §7) onto each
   worker so a fanned-out leaf/child stamps the invoking eval. The pool is
   daemon (JVM-exit-safe) and always shut down."
  [prefix pool-size xs f]
  (let [items (vec xs)]
    (if (empty? items)
      []
      (let [pool (daemon-pool prefix (min pool-size (count items)))
            f'   (bound-fn [idx x] (f idx x))]
        (try
          (let [tasks (mapv (fn [idx x]
                              (.submit pool ^Callable
                                       (reify Callable
                                         (call [_]
                                           (try {:ok true :index idx :value (f' idx x)}
                                                (catch Throwable t
                                                  {:ok false :index idx
                                                   :error (merge {:error/message (.getMessage t)
                                                                  :error/data (ex-data t)}
                                                                 (select-keys (ex-data t) [:error/type]))}))))))
                            (range) items)]
            (mapv #(.get ^java.util.concurrent.Future %) tasks))
          (finally
            (.shutdown pool)
            (.awaitTermination pool 1 TimeUnit/SECONDS)
            (.shutdownNow pool)))))))

;; ---------------------------------------------------------------------------
;; Semaphore governor (the GLOBAL leaf permit pool)
;; ---------------------------------------------------------------------------

(defn semaphore
  "A fair counting Semaphore of `n` permits, or nil (⇒ unbounded) for a
   nil/non-positive `n`."
  ^Semaphore [n]
  (when (and n (pos? n)) (Semaphore. (int n) true)))

(defn with-permit
  "Run `thunk` holding one permit from `sem` (nil ⇒ unbounded). Bounds the work
   INSIDE the thunk; the permit is always released."
  [^Semaphore sem thunk]
  (if sem
    (do (.acquire sem)
        (try (thunk) (finally (.release sem))))
    (thunk)))
