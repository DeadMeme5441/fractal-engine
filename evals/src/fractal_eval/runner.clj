(ns fractal-eval.runner
  "Drives examples through the engine (recursive) or a flat single-window baseline,
  and captures exact per-example metrics: correctness (deterministic scorer), USD
  cost, tokens, wall-clock, and the run dir for the engine path.

  Budget enforcement lives HERE, not in the engine — the engine has no governor yet.
  The runner totals cost as it goes (engine cost from the run's usage.edn; flat cost
  from the provider response) and refuses to start an example once the cap is hit."
  (:require [clojure.java.io :as io]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cli :as cli]
            [fractal-engine.process :as process]
            [fractal-engine.provider :as provider]
            [fractal-eval.fanoutqa :as fanoutqa]
            [fractal-eval.oolong :as oolong]))

(def adapters
  {"oolong"   {:engine-task oolong/engine-task   :flat-prompt oolong/flat-prompt   :score oolong/score-one}
   "fanoutqa" {:engine-task fanoutqa/engine-task :flat-prompt fanoutqa/flat-prompt :score fanoutqa/score-one}})

(defn build-cfg
  "Build an engine config from harness opts (a flag map with string values, matching
  cli/cfg-from-opts' vocabulary). Optionally inject a scripted response-fn for
  offline tests."
  [opts & {:keys [response-fn]}]
  (cond-> (cli/cfg-from-opts opts)
    response-fn (assoc :scripted/response-fn response-fn)))

;; ── cost/token extraction ─────────────────────────────────────────────────────

(defn- known [m] (when (= :known (:status m)) (:known m)))

(defn- usage-from-dir
  "Pull {:cost-usd :tokens-in :tokens-out :tokens-total} from a run's usage.edn.
  Values are nil when the provider reported them as unknown (e.g. scripted).

  Cost lives one level deeper than the token totals: the engine writes
  `:cost/total-tree {:cost/usd {:status :known :known <usd>} :cost/status :known}`,
  so the dollar amount is under `[:cost/total-tree :cost/usd]`, not directly under
  `:cost/total-tree` (which carries `:cost/status`, not `:status`)."
  [dir]
  (let [u (artifacts/read-edn-file (artifacts/path dir "usage.edn") nil)
        tree (:usage/total-tree u)]
    {:cost-usd (some-> (known (get-in u [:cost/total-tree :cost/usd])) double)
     :tokens-in (known (:tokens/input tree))
     :tokens-out (known (:tokens/output tree))
     :tokens-total (known (:tokens/total tree))}))

(defn- num-or-nil [x] (when (number? x) x))

(defn- usage-from-response
  "Cost/tokens from a single provider response (flat baseline)."
  [resp]
  (let [usage (:response/usage resp)
        cost (:response/cost resp)]
    {:cost-usd (num-or-nil (:cost/usd cost))
     :tokens-in (num-or-nil (:tokens/input usage))
     :tokens-out (num-or-nil (:tokens/output usage))
     :tokens-total (num-or-nil (:tokens/total usage))}))

;; ── running one example ───────────────────────────────────────────────────────

(defn- write-context-file!
  "Materialize the surface to a temp file the engine can slurp. Returns the absolute
  path. (The flat baseline inlines the surface instead.)"
  [id context]
  (let [f (java.io.File/createTempFile (str "fractal-eval-" id "-") ".txt")]
    (.deleteOnExit f)
    (spit f (str context))
    (.getAbsolutePath f)))

(def ablation-banner
  "The forceful no-recursion constraint prefixed onto the engine task for the
  :engine-norec ablation. We cannot un-intern rlm/map-rlm/attach-rlm without
  touching the core engine (forbidden), so the ablation is a prompt constraint
  enforced by a post-hoc audit (see `count-children`)."
  (str "ABLATION CONSTRAINT (no recursion): You may use ordinary Clojure and "
       "lm / map-lm leaf calls, but you MUST NOT call rlm, map-rlm, or attach-rlm. "
       "Solve the whole task within this single session without spawning any child "
       "sessions.\n\n"))

(defn count-children
  "Post-hoc audit: how many child sessions the run actually spawned, by counting
  directories under <run-dir>/children/. Children are named child-XXXX (rlm/map-rlm)
  or attached-XXXX (attach-rlm); both live directly under children/. Returns 0 when
  the dir is absent (no children spawned)."
  [run-dir]
  (let [children (when run-dir (io/file (str run-dir) "children"))]
    (if (and children (.isDirectory children))
      (count (filter #(.isDirectory ^java.io.File %) (.listFiles children)))
      0)))

(defn run-engine-example
  "Run one example through the recursive engine. `mode` is :engine (full recursion)
  or :engine-norec (no-recursion ablation: same path, but the task is prefixed with
  the ablation banner and the result is audited for any children that slipped through)."
  ([cfg adapter example] (run-engine-example cfg adapter example :engine))
  ([cfg adapter example mode]
   (let [norec? (= mode :engine-norec)
         base-task ((:engine-task adapter) example (write-context-file! (:id example) (:context example)))
         task (if norec? (str ablation-banner base-task) base-task)
         t0 (System/nanoTime)
         result (process/run-task! cfg task)
         ms (quot (- (System/nanoTime) t0) 1000000)
         final (when (contains? result :final-value) (:final-value result))
         scored ((:score adapter) example final)
         usage (when (:dir result) (usage-from-dir (:dir result)))
         children (count-children (:dir result))]
     (merge {:id (:id example) :mode mode
             :status (:status result)
             :final final
             :run-dir (str (:dir result))
             :children-spawned children
             :wall-ms ms}
            (when norec?
              {:ablation-honored? (zero? children)
               :ablation-violated? (pos? children)})
            ;; Surface WHY an engine run errored (max-turns, provider-failed, …) so an
            ;; :error row is self-explanatory instead of an opaque "error".
            (when (= :error (:status result))
              {:error (:error result)
               :error-type (:error/type (:error result))})
            scored
            usage))))

(defn run-flat-example [cfg adapter example]
  (let [prompt ((:flat-prompt adapter) example)
        request {:request/messages [{:message/role :user :message/content prompt}]}
        t0 (System/nanoTime)
        resp (try (provider/complete cfg :root request)
                  (catch Throwable t {::error (.getMessage t)}))
        ms (quot (- (System/nanoTime) t0) 1000000)]
    (if (::error resp)
      {:id (:id example) :mode :flat :status :error :error (::error resp)
       :correct? 0 :wall-ms ms}
      (let [text (provider/response-text resp)
            scored ((:score adapter) example text)
            usage (usage-from-response resp)]
        (merge {:id (:id example) :mode :flat :status :final :final text :wall-ms ms}
               scored usage)))))

(defn run-example [cfg adapter mode example]
  (case mode
    :engine        (run-engine-example cfg adapter example :engine)
    :engine-norec  (run-engine-example cfg adapter example :engine-norec)
    :flat          (run-flat-example cfg adapter example)))

;; ── budgeted batch ────────────────────────────────────────────────────────────

(defn run-batch
  "Run `examples` through `mode`, stopping before any example that would risk
  exceeding `budget-usd` (nil = no cap). `on-result` is called with each result map
  as it completes (for live progress). Returns {:results [...] :spent <usd>
  :stopped-early? bool} where :spent is what THIS batch spent.

  `:spent-so-far` (default 0.0) seeds the budget check with spend from earlier modes
  in the same invocation, so `budget-usd` acts as ONE shared cumulative cap across
  modes: a later mode is refused once the COMBINED spend reaches the cap. This
  batch's reported :spent excludes that seed (it is only this batch's own cost)."
  [{:keys [cfg benchmark mode budget-usd on-result spent-so-far]} examples]
  (let [adapter (get adapters benchmark)
        spent-so-far (or spent-so-far 0.0)]
    (when-not adapter (throw (ex-info "Unknown benchmark" {:benchmark benchmark})))
    (loop [todo examples results [] spent 0.0]
      (if (empty? todo)
        {:results results :spent spent :stopped-early? false}
        (if (and budget-usd (>= (+ spent-so-far spent) budget-usd))
          {:results results :spent spent :stopped-early? true}
          (let [ex (first todo)
                r (run-example cfg adapter mode ex)
                spent' (+ spent (or (:cost-usd r) 0.0))]
            (when on-result (on-result (assoc r :cumulative-usd (+ spent-so-far spent'))))
            (recur (rest todo) (conj results r) spent')))))))
