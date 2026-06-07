(ns fractal.engine.concurrent
  "L0 · concurrency primitives (engine-free). `with-deadline` runs a body under
   a single wall-clock budget on a DAEMON thread, so a stuck provider call can
   neither block JVM exit nor be relied on to stop (an orphaned call may still
   run — and cost — after the deadline fires; documented, 07 §3).")

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
