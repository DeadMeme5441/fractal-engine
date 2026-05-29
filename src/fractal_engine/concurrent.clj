(ns fractal-engine.concurrent
  "Bounded parallel fanout — the concurrency mechanism, independent of what is
  being fanned out. `map-lm` and `map-rlm` are the only callers today; the engine
  semantics live elsewhere. Threads are daemons so a stuck provider call never
  pins JVM shutdown, and bindings propagate via bound-fn so dynamic vars
  (current turn/eval ids) survive the thread hop."
  (:import [java.util.concurrent Callable ExecutionException Executors ThreadFactory TimeUnit TimeoutException]))

(defn daemon-executor [prefix]
  (let [counter (atom 0)]
    (Executors/newCachedThreadPool
     (reify ThreadFactory
       (newThread [_ r]
         (doto (Thread. r (str prefix "-" (swap! counter inc)))
           (.setDaemon true)))))))

(defn parallel-map-indexed
  "Run (f idx x) over xs in parallel, preserving input order in the result.
  Caller-side exceptions surface from the returned futures; ordering is by index,
  not completion."
  [prefix f xs]
  (let [executor (daemon-executor prefix)
        f' (bound-fn [idx x] (f idx x))]
    (try
      (let [tasks (mapv (fn [idx x]
                          (.submit executor
                                   ^Callable
                                   (reify Callable
                                     (call [_] (f' idx x)))))
                        (range) xs)]
        (mapv #(.get %) tasks))
      (finally
        (.shutdownNow executor)
        (.awaitTermination executor 5 TimeUnit/SECONDS)))))

(defn with-deadline
  "Run `thunk` under a wall-clock bound. If it doesn't finish within `timeout-ms`,
  throw ex-info :provider/timeout and abandon the orphaned work (daemon thread, so
  it never blocks JVM exit — though an orphaned provider call may still incur cost).
  A nil/zero timeout means no bound. Exceptions from the thunk propagate unwrapped."
  [timeout-ms thunk]
  (if (and timeout-ms (pos? timeout-ms))
    (let [executor (daemon-executor "fractal-deadline")
          fut (.submit executor ^Callable (reify Callable (call [_] (thunk))))]
      (try
        (.get fut timeout-ms TimeUnit/MILLISECONDS)
        (catch TimeoutException _
          (.cancel fut true)
          (throw (ex-info "Provider call timed out"
                          {:error/type :provider/timeout
                           :timeout/ms timeout-ms
                           :error/retryable? false})))
        (catch ExecutionException e
          (throw (or (.getCause e) e)))
        (finally
          (.shutdownNow executor))))
    (thunk)))
