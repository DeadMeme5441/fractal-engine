(ns fractal-engine.rehydrate
  (:require [fractal-engine.artifacts :as artifacts]
            [fractal-engine.runtime :as runtime]
            [fractal-engine.session-store :as store]
            [fractal-engine.time :as time]))

(def provider-symbols '#{lm map-lm rlm map-rlm attach-rlm})

(defn- max-id [rows k]
  (apply max 0 (keep k rows)))

(defn reset-state-from-source! [state source]
  (let [messages (:messages source)
        turns (:turns source)
        evals (:evals source)
        calls (:calls source)
        events (:events source)
        snapshots (:snapshots source)]
    (swap! state assoc
           :messages messages
           :turns turns
           :evals evals
           :calls calls
           :events events
           :snapshots snapshots
           :counters {:message (max-id messages :message/id)
                      :turn (max-id turns :turn/id)
                      :eval (max-id evals :eval/id)
                      :call (max-id calls :call/id)
                      :event (max-id events :event/id)
                      :snapshot (max-id snapshots :snapshot/id)
                      :child (count (filter #(contains? artifacts/child-call-types (:call/type %)) calls))})
    (artifacts/flush! state)))

(defn- symbol-called? [form]
  (boolean
   (some (fn [x]
           (and (symbol? x)
                (contains? provider-symbols (symbol (name x)))))
         (tree-seq coll? seq form))))

(defn- code-calls-provider? [code]
  (try
    (boolean (some symbol-called? (runtime/read-forms (or code ""))))
    (catch Throwable _ true)))

(defn provider-calling-eval? [eval-row calls]
  (or (some #(= (:eval/id eval-row) (:call/parent-eval-id %)) calls)
      (code-calls-provider? (:eval/code eval-row))))

(defn- single-def-symbol [code]
  (try
    (let [forms (runtime/read-forms (or code ""))]
      (when (and (= 1 (count forms))
                 (seq? (first forms))
                 (= 'def (ffirst forms))
                 (symbol? (second (first forms))))
        (second (first forms))))
    (catch Throwable _ nil)))

(defn- restorable-value? [value]
  (and (contains? value :present)
       (runtime/edn-safe? (:present value))
       (not (and (map? (:present value))
                 (or (= :object (:value/type (:present value)))
                     (:truncated? (:present value))
                     (:value/truncated? (:present value)))))))

(defn- eval-value-box [eval-row]
  (if (contains? eval-row :eval/value)
    {:present (:eval/value eval-row)}
    {}))

(defn- replay-row [row status reason extra]
  (merge {:eval/id (:eval/id row)
          :eval/source-status (:eval/status row)
          :replay/status status}
         (when reason {:replay/reason reason})
         extra))

(defn- add-rehydration-event! [state row]
  (artifacts/add-event! state {:event/type (case (:replay/status row)
                                             :ok :rehydration-eval-replayed
                                             :restored-binding :rehydration-binding-restored
                                             :skipped :rehydration-eval-skipped
                                             :error :rehydration-eval-skipped
                                             :rehydration-eval-skipped)
                               :eval/id (:eval/id row)
                               :replay/status (:replay/status row)
                               :replay/reason (:replay/reason row)}))

(defn replay-eval! [session eval-row calls]
  (let [{:keys [state ns-sym]} session
        started (System/nanoTime)]
    (cond
      (= :final (:eval/status eval-row))
      (replay-row eval-row :skipped :final-form {})

      (= :error (:eval/status eval-row))
      (replay-row eval-row :skipped :source-error {})

      (= :read-error (:eval/status eval-row))
      (replay-row eval-row :skipped :source-read-error {})

      (not= :ok (:eval/status eval-row))
      (replay-row eval-row :skipped :source-not-ok {})

      (provider-calling-eval? eval-row calls)
      (if-let [sym (single-def-symbol (:eval/code eval-row))]
        (let [boxed (eval-value-box eval-row)]
          (if (restorable-value? boxed)
            (do
              (intern (the-ns ns-sym) sym (:present boxed))
              (replay-row eval-row :restored-binding :provider-effect
                          {:var sym
                           :replay/elapsed-ms (quot (- (System/nanoTime) started) 1000000)}))
            (replay-row eval-row :skipped :non-replayable-provider-effect {})))
        (replay-row eval-row :skipped :non-replayable-provider-effect {}))

      :else
      (let [result (runtime/eval-code ns-sym (:eval/code eval-row))]
        (if (= :ok (:eval/status result))
          (replay-row eval-row :ok nil
                      {:replay/elapsed-ms (quot (- (System/nanoTime) started) 1000000)})
          (replay-row eval-row :error :replay-error
                      {:replay/error (:eval/error result)}))))))

(defn write-report! [dir report]
  (artifacts/write-edn-file! (artifacts/path dir "rehydration.edn") report))

(defn rehydrate! [_cfg source-dir session opts]
  (let [{:keys [state ns-sym]} session
        source (store/load-session source-dir)
        started-at (time/now-str)
        base {:rehydration/version 1
              :rehydration/source (str source-dir)
              :rehydration/source-fingerprint (:fingerprint source)
              :rehydration/strategy :eval-replay
              :rehydration/status :running
              :rehydration/started-at started-at
              :rehydration/current-ns (str ns-sym)
              :rehydration/touched-namespaces [(str ns-sym)]
              :rehydration/replayed []
              :rehydration/error nil}]
    (artifacts/add-event! state {:event/type :rehydration-start
                                 :event/source (str source-dir)
                                 :event/source-fingerprint (:fingerprint source)})
    (loop [rows (:evals source)
           replayed []]
      (if-let [row (first rows)]
        (let [replay (replay-eval! session row (:calls source))
              replayed' (conj replayed replay)]
          (add-rehydration-event! state replay)
          (if (= :error (:replay/status replay))
            (let [report (assoc base
                                :rehydration/status :error
                                :rehydration/ended-at (time/now-str)
                                :rehydration/replayed replayed'
                                :rehydration/error {:error/type :rehydration/replay-error
                                                    :eval/id (:eval/id row)
                                                    :message (str (:replay/error replay))})]
              (write-report! (:dir @state) report)
              (artifacts/update-status! state :rehydration-error)
              (throw (ex-info "Rehydration replay failed"
                              {:error/type :rehydration/replay-error
                               :rehydration/report report})))
            (recur (rest rows) replayed')))
        (let [report (assoc base
                            :rehydration/status :ok
                            :rehydration/ended-at (time/now-str)
                            :rehydration/replayed replayed
                            :rehydration/replayed-count (count (filter #(= :ok (:replay/status %)) replayed))
                            :rehydration/skipped-count (count (filter #(= :skipped (:replay/status %)) replayed))
                            :rehydration/restored-binding-count (count (filter #(= :restored-binding (:replay/status %)) replayed)))]
          (write-report! (:dir @state) report)
          (artifacts/add-event! state {:event/type :rehydration-end
                                       :event/status :ok
                                       :event/replayed-evals (:rehydration/replayed-count report)
                                       :event/skipped-evals (:rehydration/skipped-count report)
                                       :event/restored-bindings (:rehydration/restored-binding-count report)})
          report)))))
