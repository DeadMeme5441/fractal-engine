(ns fractal-engine.artifacts
  "The artifact layer. Holds live session state, records canonical facts/payloads
  through session-db, and keeps an in-memory folded view for the active process."
  (:require [clojure.string :as str]
            [fractal-engine.cache :as cache]
            [fractal-engine.event :as event]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.session-model :as session-model]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.store.payload :as store-payload]
            [fractal-engine.time :as time])
  (:import [java.util UUID]))

(def artifact-version 1)
(def surface-version 2)
(def surface '[FINAL lm map-lm rlm map-rlm attach-rlm])

(def path
  "Filesystem path helper for artifact-adjacent readers.

  Storage authority lives in session-db/store namespaces; this remains a small
  file helper for read-side compatibility such as eval result readers."
  store-io/path)

(def read-edn-file
  "Read an EDN file, returning default when absent.

  This delegates to store.io and does not participate in session storage."
  store-io/read-edn-file)

(defn session-id []
  (str "session-" (UUID/randomUUID)))

(defn child-id [n]
  (format "child-%04d" (long n)))

(defn head-id []
  (session-model/head-id))

(defn invocation-id []
  (session-model/invocation-id))

(defn value-summary [value]
  (cond
    (nil? value) {:kind :nil}
    (string? value) {:kind :string :count (count value)}
    (map? value) {:kind :map :count (count value) :keys (vec (take 20 (keys value)))}
    (vector? value) {:kind :vector :count (count value)}
    (set? value) {:kind :set :count (count value)}
    (seq? value) {:kind :seq :count (count value)}
    :else {:kind (keyword (-> value class .getSimpleName str/lower-case))}))

(defn project-value
  ([value] (project-value value 0))
  ([value depth]
   (cond
     (> depth 4) {:truncated? true :reason :max-depth}
     (nil? value) nil
     (or (boolean? value) (number? value) (keyword? value) (symbol? value)) value
     (string? value) (if (> (count value) 2000)
                       {:value/type :string
                        :value/preview (subs value 0 2000)
                        :value/truncated? true
                        :value/count (count value)}
                       value)
     (map? value) (into {}
                        (map (fn [[k v]] [(project-value k (inc depth))
                                          (project-value v (inc depth))]))
                        (take 50 value))
     (vector? value) (cond-> (vec (map #(project-value % (inc depth)) (take 50 value)))
                       (> (count value) 50)
                       (conj {:truncated? true :remaining (- (count value) 50)}))
     (set? value) {:value/type :set
                   :value/count (count value)
                   :value/items (vec (map #(project-value % (inc depth)) (take 50 value)))}
     (seq? value) {:value/type :seq
                   :value/items (vec (map #(project-value % (inc depth)) (take 50 value)))}
     :else {:value/type :object
            :class (.getName (class value))
            :preview (try (pr-str value)
                          (catch Throwable _ (str value)))})))

(defn store-root-for-locator [locator]
  (session-db/locator-store-root locator))

(defn session-id-for-locator [locator]
  (or (session-db/locator-session-id locator)
      (throw (ex-info "Session locator is missing :session/id"
                      {:error/type :fractal/session-locator-missing
                       :locator locator}))))

(defn session-locator
  [root session-id]
  (session-db/locator root session-id))

(defn read-ref [locator ref]
  (cond
    (nil? ref) nil
    (:blob/id ref) (or (store-payload/read-edn (store-root-for-locator locator) ref) ::missing)
    :else ::missing))

(defn value-bytes [value]
  (alength (.getBytes (store-io/formatted-edn value) java.nio.charset.StandardCharsets/UTF_8)))

(defn payload-ref! [locator value]
  (store-payload/write! (store-root-for-locator locator) value {:payload/kind :value}))

(defn value-ref!
  ([locator value]
   (payload-ref! locator value))
  ([locator value opts]
   (store-payload/write! (or (:store-root opts) (store-root-for-locator locator))
                         value
                         (merge {:payload/kind :value} (dissoc opts :store-root)))))

(def root-call-types #{:root})
(def leaf-call-types #{:leaf :leaf-batch-item})
(def child-call-types #{:child :child-batch-item :attached-child :attached-session})
(def provider-call-types (into root-call-types leaf-call-types))

(declare logical-session-id set-current-head! store-root)

(defn- first-number [m ks]
  (some (fn [k]
          (let [v (get m k)]
            (when (number? v) v)))
        ks))

(defn- numeric-rollup [calls field-fn]
  (let [values (map field-fn calls)
        known (filter number? values)
        known-count (count known)
        unknown-count (- (count calls) known-count)]
    (cond
      (zero? (count calls)) {:status :unknown :call/count 0}
      (zero? known-count) {:status :unknown :call/count (count calls)}
      (zero? unknown-count) {:status :known :known (reduce + known) :call/count (count calls)}
      :else {:status :partial
             :known (reduce + known)
             :unknown-calls unknown-count
             :call/count (count calls)})))

(defn- aggregate-status [& statuses]
  (let [statuses (set (remove nil? statuses))]
    (cond
      (empty? statuses) :unknown
      (= statuses #{:known}) :known
      (= statuses #{:unknown}) :unknown
      :else :partial)))

(defn- call-input-tokens [call]
  (first-number (:call/usage call) [:usage/input-tokens :usage/prompt-tokens :input-tokens :prompt-tokens]))

(defn- call-output-tokens [call]
  (first-number (:call/usage call) [:usage/output-tokens :usage/completion-tokens :output-tokens :completion-tokens]))

(defn- call-total-tokens
  "A call's total tokens: the provider's reported total if present, otherwise
  input+output when BOTH are present (a derivable number the provider left implicit).
  Stays nil when only one side is known, so the rollup honestly reports it unknown
  rather than undercounting."
  [call]
  (or (first-number (:call/usage call) [:usage/total-tokens :total-tokens])
      (let [i (call-input-tokens call)
            o (call-output-tokens call)]
        (when (and i o) (+ i o)))))

(defn- usage-rollup [calls]
  (let [summary {:call/count (count calls)
                 :tokens/input (numeric-rollup calls call-input-tokens)
                 :tokens/output (numeric-rollup calls call-output-tokens)
                 :tokens/total (numeric-rollup calls call-total-tokens)
                 :tokens/cached (numeric-rollup calls #(first-number (:call/usage %) [:usage/cached-tokens :usage/cached-input-tokens :cached-tokens :cached-input-tokens]))}]
    (assoc summary :usage/status (aggregate-status (get-in summary [:tokens/input :status])
                                                   (get-in summary [:tokens/output :status])
                                                   (get-in summary [:tokens/total :status])))))

(defn- cost-rollup [calls]
  (let [summary {:call/count (count calls)
                 :cost/usd (numeric-rollup calls #(first-number (:call/cost %) [:cost/usd :usd]))}]
    (assoc summary :cost/status (get-in summary [:cost/usd :status]))))

(defn- cache-rollup [calls]
  (let [cacheable (filterv #(or (contains? % :call/cache-request)
                                (contains? % :call/cache))
                           calls)
        statuses (map #(get-in % [:call/cache :cache/status] :unknown) cacheable)
        status-counts (frequencies statuses)
        cached-tokens (numeric-rollup cacheable #(or (first-number (:call/cache %) [:cache/cached-tokens :cache/cached-input-tokens :cached-tokens :cached-input-tokens])
                                                     (first-number (:call/usage %) [:usage/cached-tokens :usage/cached-input-tokens :cached-tokens :cached-input-tokens])))]
    {:call/count (count cacheable)
     :cache/status (cond
                     (zero? (count cacheable)) :unknown
                     (and (seq statuses) (every? #(not= :unknown %) statuses)) :known
                     (some #(not= :unknown %) statuses) :partial
                     :else :unknown)
     :cache/hit-count (get status-counts :hit 0)
     :cache/miss-count (get status-counts :miss 0)
     :cache/unknown-count (+ (get status-counts :unknown 0)
                             (get status-counts nil 0))
     :tokens/cached cached-tokens}))

(defn- call-group [calls pred]
  (filterv pred calls))

(defn- child-call? [call]
  (contains? child-call-types (:call/type call)))

(defn child-locator-for-call [parent-locator call]
  (when-let [sid (or (:child/logical-session-id call)
                    (:child/session-id call))]
    (session-locator (store-root-for-locator parent-locator) sid)))

(defn- locator-key [locator]
  [(str (store-root-for-locator locator)) (session-id-for-locator locator)])

(defn- read-child-calls
  ([dir call] (read-child-calls dir call #{(locator-key dir)}))
  ([dir call seen]
   (when-let [child-locator (child-locator-for-call dir call)]
     (let [k (locator-key child-locator)]
       (when-not (contains? seen k)
         (let [child-calls (:calls (session-db/view (store-root-for-locator child-locator)
                                                    (session-id-for-locator child-locator)))
               seen' (conj seen k)]
           (mapcat (fn [c]
                     (cons c (read-child-calls child-locator c seen')))
                   child-calls)))))))

(defn- tree-calls [dir calls]
  (let [seen #{(locator-key dir)}]
    (vec (concat calls (mapcat #(read-child-calls dir % seen) calls)))))

(defn derive-usage [dir calls]
  (let [root-calls (call-group calls #(contains? root-call-types (:call/type %)))
        leaf-calls (call-group calls #(contains? leaf-call-types (:call/type %)))
        child-calls (call-group calls child-call?)
        all-tree-calls (tree-calls dir calls)
        provider-tree-calls (call-group all-tree-calls #(contains? provider-call-types (:call/type %)))
        child-summaries (mapv (fn [call]
                                (let [child-locator (child-locator-for-call dir call)
                                      child-view (when child-locator
                                                   (session-db/view (store-root-for-locator child-locator)
                                                                    (session-id-for-locator child-locator)))
                                      child-usage (if child-view
                                                    (derive-usage child-locator (:calls child-view))
                                                    {:usage/status :unknown})]
                                  {:call/id (:call/id call)
                                   :child/session-id (:child/session-id call)
                                   :child/locator child-locator
                                   :child/status (:call/status call)
                                   :child/usage child-usage}))
                              child-calls)]
    {:usage/status (aggregate-status (:usage/status (usage-rollup provider-tree-calls)))
     :usage/root (usage-rollup root-calls)
     :usage/leaf (usage-rollup leaf-calls)
     :usage/child-calls {:call/count (count child-calls)}
     :usage/children {:child/count (count child-summaries)
                      :children child-summaries}
     :usage/total-tree (assoc (usage-rollup provider-tree-calls)
                              :call/total-tree-count (count all-tree-calls))
     :cost/root (cost-rollup root-calls)
     :cost/leaf (cost-rollup leaf-calls)
     :cost/total-tree (cost-rollup provider-tree-calls)
     :cache/root (cache-rollup root-calls)
     :cache/leaf (cache-rollup leaf-calls)
     :cache/total-tree (cache-rollup provider-tree-calls)}))

(defn derive-usage-report
  "Usage at two scopes from the same canonical call facts:

  - `:usage/turn` is the completed turn only, including any child sessions that
    were invoked by calls in that turn.
  - `:usage/cumulative` is the whole current session tree up to the selected
    head.

  This is intentionally a read-side projection. It does not change accounting
  or create a second cost model; it only slices the existing call rows by
  `:call/turn-id` before applying `derive-usage`."
  ([locator calls] (derive-usage-report locator calls {}))
  ([locator calls {:keys [turn-id]}]
   (let [calls (vec calls)
         turn-calls (when turn-id
                      (filterv #(= turn-id (:call/turn-id %)) calls))]
     (cond-> {:usage/cumulative (derive-usage locator calls)}
       turn-id (assoc :turn/id turn-id
                      :usage/turn (derive-usage locator turn-calls))))))

(defn- child-tree
  ([child-locator] (child-tree child-locator #{}))
  ([child-locator seen]
   (let [root (store-root-for-locator child-locator)
         sid (session-id-for-locator child-locator)
         k (locator-key child-locator)]
     (if (contains? seen k)
       {:tree/session-id sid
        :tree/status :referenced
        :tree/ref? true
        :call/count 0
        :eval/count 0
        :snapshot/count 0
        :tree/children []}
       (let [v (session-db/view root sid)
             session (:session v)
             calls (:calls v)
             evals (:evals v)
             usage (derive-usage child-locator calls)
             latest-turn (last (:turns v))
             final {:final/latest-value-preview (:turn/final-preview latest-turn)}
             snapshots (:snapshots v)
             seen' (conj seen k)]
         {:tree/session-id (:session/id session)
          :tree/status (:session/status session)
          :call/count (count calls)
          :eval/count (count evals)
          :snapshot/count (count snapshots)
          :tree/usage usage
          :tree/final-preview (:final/latest-value-preview final)
          :tree/children
          (->> calls
               (keep (fn [call]
                       (when (contains? child-call-types (:call/type call))
                         (let [locator (child-locator-for-call child-locator call)
                               ct (child-tree locator seen')]
                           (assoc ct
                                  :call/id (:call/id call)
                                  :edge/type (:edge/type call :spawned)
                                  :child/session-id (:child/session-id call)
                                  :child/locator locator
                                  :child/status (:tree/status ct))))))
               vec)})))))

(defn derive-tree [dir session calls]
  (let [seen #{(locator-key dir)}]
    {:tree/session-id (:session/id session)
     :tree/status (:session/status session)
     :call/count (count calls)
     :tree/children
     (->> calls
          (keep (fn [call]
                  (when (contains? child-call-types (:call/type call))
                    (let [child-locator (child-locator-for-call dir call)
                          ct (child-tree child-locator seen)]
                      (assoc ct
                             :call/id (:call/id call)
                             :edge/type (:edge/type call :spawned)
                             :child/session-id (:child/session-id call)
                             :child/locator child-locator
                             :child/status (:tree/status ct))))))
          vec)}))

(defn next-counter! [state k]
  (get-in (swap! state update-in [:counters k] (fnil inc 0)) [:counters k]))

(defn emit!
  "Record one event: assign its id and timestamp, transact canonical Datahike
  facts plus BlobStore payload refs, then fold it into the in-memory view.
  Serialized per session so DB event order and the live view stay consistent
  under the parallel fanout of map-lm/map-rlm. The reentrant monitor lets
  `update-turn!`/`update-call!` read-then-emit atomically."
  [state event]
  (locking (:emit-lock @state)
    (let [id (next-counter! state :event)
          ev (assoc event :event/id id :event/at (time/now-str))]
      (session-db/record-event! (store-root state) (logical-session-id state) ev)
      (swap! state event/apply-event ev)
      ev)))

(defn checkpoint!
  "Boundary hook retained inside the runtime. Canonical persistence already
  happened event-by-event in `emit!`, so there is no automatic filesystem output."
  [state]
  state)

;; Kept name for the many boundary call sites: a flush is a checkpoint.
(def flush! checkpoint!)

(defn add-event!
  "Emit an annotation/lifecycle event (no row change of its own). Returns the
  event id for boundary call sites."
  [state event]
  (:event/id (emit! state event)))

(defn add-message!
  ([state role content] (add-message! state role content nil nil))
  ([state role content turn-id] (add-message! state role content turn-id nil))
  ([state role content turn-id extra]
   (let [id (next-counter! state :message)
         msg (cond-> (merge {:message/id id :message/role role :message/content content} extra)
               turn-id (assoc :message/turn-id turn-id))]
     (emit! state {:event/type :message/added :message msg})
     msg)))

(defn add-turn! [state turn]
  (let [id (next-counter! state :turn)
        row (merge {:turn/id id
                    :turn/status :running
                    :turn/assistant-message-ids []
                    :turn/observation-message-ids []
                    :turn/eval-ids []
                    :turn/call-ids []
                    :turn/started-at (time/now-str)
                    :turn/ended-at nil
                    :turn/error nil
                    :turn/usage nil}
                   turn)]
    (emit! state {:event/type :turn/started :turn row})
    row))

(defn current-turn [state turn-id]
  (first (filter #(= turn-id (:turn/id %)) (:turns @state))))

(defn update-turn! [state turn-id f & args]
  (locking (:emit-lock @state)
    (let [cur (current-turn state turn-id)
          nxt (apply f cur args)]
      (emit! state {:event/type :turn/put :turn nxt})
      nxt)))

(defn add-turn-id! [state turn-id k value]
  (when turn-id
    (update-turn! state turn-id update k (fnil conj []) value)))

(defn add-turn-ids! [state turn-id k values]
  (when turn-id
    (update-turn! state turn-id update k #(vec (concat (or % []) values)))))

(defn add-call! [state call]
  (locking (:emit-lock @state)
    (let [id (or (:call/id call)
                 (next-counter! state :call))
          call' (merge {:call/id id
                        :call/status :running
                        :call/started-at (time/now-str)}
                       call)]
      (emit! state {:event/type :call/started :call call'})
      (add-turn-id! state (:call/turn-id call') :turn/call-ids id)
      call')))

(defn update-call! [state call-id f & args]
  (locking (:emit-lock @state)
    (let [cur (first (filter #(= call-id (:call/id %)) (:calls @state)))
          nxt (apply f cur args)]
      (emit! state {:event/type :call/put :call nxt})
      nxt)))

(defn add-eval! [state eval-row]
  (let [id (next-counter! state :eval)
        row (assoc eval-row :eval/id id)]
    (emit! state {:event/type :eval/added :eval row})
    (add-turn-id! state (:eval/turn-id row) :turn/eval-ids id)
    row))

(defn add-snapshot! [state snapshot]
  (let [id (next-counter! state :snapshot)
        row (assoc snapshot :snapshot/id id :snapshot/created-at (time/now-str))]
    (emit! state {:event/type :snapshot/added :snapshot row})
    row))

(defn current-head-id [state]
  (get-in @state [:refs :ref/current-head]))

(defn logical-session-id [state]
  (session-model/logical-session-id (:session @state)))

(defn store-root [state]
  (or (some-> (:store-root @state) store-io/path)
      (some-> (:session @state) session-db/store-root)))

(defn- invocation-row [state invocation-id]
  (first (filter #(= invocation-id (:invocation/id %)) (:invocations @state))))

(defn- write-invocation-projection! [state invocation-id]
  (when-let [row (invocation-row state invocation-id)]
    (session-db/write-invocation! (store-root state) row)))

(defn- replace-row
  [rows key-fn id f]
  (mapv #(if (= id (key-fn %)) (f %) %) rows))

(defn- max-counters
  [& counters]
  (apply merge-with max (map #(or % {}) counters)))

(defn- rows-by-id
  [id-key rows]
  (into {}
        (keep (fn [row]
                (when-let [id (id-key row)]
                  [id row])))
        rows))

(defn- collection-delta
  [id-key basis rows]
  (let [basis-by-id (rows-by-id id-key basis)
        rows-by-id' (rows-by-id id-key rows)
        appended (->> rows
                      (remove #(contains? basis-by-id (id-key %)))
                      vec)
        replaced (->> rows
                      (filter (fn [row]
                                (let [id (id-key row)]
                                  (and (contains? basis-by-id id)
                                       (not= row (get basis-by-id id))))))
                      vec)
        removed (->> basis
                     (keep (fn [row]
                             (let [id (id-key row)]
                               (when-not (contains? rows-by-id' id) id))))
                     vec)]
    (cond-> {:append appended
             :replace replaced}
      (seq removed) (assoc :remove removed))))

(defn- empty-delta?
  [delta]
  (and (empty? (:append delta))
       (empty? (:replace delta))
       (empty? (:remove delta))))

(defn- collection-state-ref!
  [state head collection-key delta]
  (when-not (empty-delta? delta)
    (value-ref! (:locator @state)
                (assoc delta
                       :state-collection/version 1
                       :state/session-id (:head/session head)
                       :state/head-id (:head/id head)
                       :state/basis-head (:head/basis head)
                       :collection/key collection-key)
                {:payload/kind :head-state-collection})))

(defn- compact-collections
  [state head view basis-view]
  (into {}
        (map (fn [[collection-key id-key]]
               (let [rows (vec (get view collection-key []))
                     basis (vec (get basis-view collection-key []))
                     delta (collection-delta id-key basis rows)
                     delta-ref (collection-state-ref! state head collection-key delta)]
                 [collection-key
                  (cond-> {:collection/key collection-key
                           :collection/count (count rows)
                           :basis/head (:head/basis head)}
                    delta-ref (assoc :delta-ref delta-ref))])))
        session-model/state-collection-id-keys))

(defn- branch-view-for-head
  [state head]
  (let [view (select-keys @state session-model/active-state-keys)
        hid (:head/id head)
        turn-id (:head/turn-id head)
        invocation-ids (set (:head/invocations head))]
    (-> view
        (update :turns replace-row :turn/id turn-id
                #(cond-> (assoc % :turn/head-after hid)
                   (:head/final-ref head) (assoc :turn/final-ref (:head/final-ref head))))
        (update :invocations
                (fn [rows]
                  (mapv (fn [row]
                          (if (contains? invocation-ids (:invocation/id row))
                            (assoc row :caller/head-after hid)
                            row))
                        rows)))
        (update :heads conj head)
        (assoc :refs {:ref/session (:head/session head)
                      :ref/current-head hid
                      :ref/updated-at (:head/created-at head)})
        (update :counters max-counters {:head (:head/n head)}))))

(defn- head-state-value
  [state head]
  (let [view (branch-view-for-head state head)
        basis-view (if-let [basis-head (:head/basis head)]
                     (or (session-db/read-head-state (store-root state)
                                                     (:head/session head)
                                                     basis-head)
                         event/empty-view)
                     event/empty-view)]
    {:state/version session-model/head-state-version
     :state/session-id (:head/session head)
     :state/head-id (:head/id head)
     :state/basis-head (:head/basis head)
     :state/created-at (:head/created-at head)
     :state/head (select-keys head (remove #{:head/state-ref :head/fingerprint}
                                           session-model/canonical-head-keys))
     :session (:session view)
     :refs (:refs view)
     :final-ref (:final-ref view)
     :error (:error view)
     :counters (:counters view)
     :vars-ref (:head/snapshot-ref head)
     :state/collections (compact-collections state head view basis-view)}))

(defn create-head!
  "Append one immutable completed-state boundary for the session. The current ref
  advances in Datahike only after the immutable head fact has been written."
  [state head]
  (let [previous-head (current-head-id state)
        n (next-counter! state :head)
        hid (head-id)
        base0 (merge {:head/id hid
                      :head/n n
                      :head/version session-model/head-version
                      :head/session (logical-session-id state)
                      :head/basis previous-head
                      :head/cache-id (get-in @state [:session :session/cache-id])
                      :head/cache (select-keys (get-in @state [:session :session/cache])
                                               [:agent-scope
                                                :leaf-scope
                                                :agent-provider-scope
                                                :leaf-provider-scope])
                      :head/created-at (time/now-str)}
                     head)
        state-value (head-state-value state base0)
        state-ref (value-ref! (:locator @state)
                              state-value
                              {:payload/kind :head-state})
        base (assoc base0
                    :head/state-version session-model/head-state-version
                    :head/state-ref state-ref)
        row (assoc base :head/fingerprint (session-model/head-fingerprint base))]
    (emit! state {:event/type :head/created :head row})
    (session-db/advance-current-head! (store-root state) row previous-head)
    (set-current-head! state (:head/id row))
    row))

(defn set-current-head!
  "Move the session's current ref to an existing head without creating a new head.
  Used by same-session restore when a continuation starts from an existing
  same-session head."
  [state head-id]
  (when head-id
    (emit! state {:event/type :session/ref-updated
                  :ref/session (logical-session-id state)
                  :ref/current-head head-id}))
  head-id)

(defn start-invocation!
  "Append the start fact for one RLM session invocation. Leaves never use this;
  leaf work remains represented only as call rows."
  [state invocation]
  (locking (:emit-lock @state)
    (let [n (next-counter! state :invocation)
          row (merge {:invocation/id (invocation-id)
                      :invocation/n n
                      :invocation/version session-model/invocation-version}
                     invocation)]
      (emit! state {:event/type :invocation/started :invocation row})
      (add-turn-id! state (:caller/turn-id row) :turn/invocation-ids (:invocation/id row))
      (write-invocation-projection! state (:invocation/id row))
      row)))

(defn complete-invocation! [state invocation-id completion]
  (let [row (assoc completion :invocation/id invocation-id)]
    (emit! state {:event/type :invocation/completed :invocation row})
    (write-invocation-projection! state invocation-id)
    row))

(defn fail-invocation! [state invocation-id failure]
  (let [row (assoc failure :invocation/id invocation-id)]
    (emit! state {:event/type :invocation/failed :invocation row})
    (write-invocation-projection! state invocation-id)
    row))

(defn complete-turn-invocations! [state turn-id caller-head-after]
  (doseq [inv (:invocations @state)
          :when (and (= turn-id (:caller/turn-id inv))
                     (nil? (:caller/head-after inv)))]
    (emit! state {:event/type :invocation/caller-head-recorded
                  :invocation {:invocation/id (:invocation/id inv)
                               :caller/head-after caller-head-after}})
    (write-invocation-projection! state (:invocation/id inv))))

(defn update-status! [state status]
  (emit! state {:event/type :session/status
                :status status
                :ended-at (when (not= status :running) (time/now-str))}))

(defn mark-final! [state value]
  (let [ref (value-ref! (:locator @state) value {:payload/kind :final})]
    (emit! state {:event/type :session/final :final/value-ref ref})))

(defn mark-error! [state error]
  (emit! state {:event/type :session/error :error error :ended-at (time/now-str)}))

(defn load-state!
  [{:keys [store-root session-id head-id]}]
  (let [store-root' (store-io/path store-root)
        view (session-db/view store-root' session-id (cond-> {} head-id (assoc :head-id head-id)))
        session (:session view)
        store-root' (store-io/path (or store-root
                                        (get-in session [:session/storage :storage/root])
                                        store-root'))
        locator (session-locator store-root' session-id)
        high-water (session-db/high-water-counters store-root' session-id)
        child-counter (event/recover-child-counter view)
        state (atom (merge view
                           {:locator locator
                            :store-root store-root'
                            :emit-lock (Object.)}))]
    (swap! state update :counters max-counters high-water {:child child-counter})
    state))

(defn new-state!
  [{:keys [id logical-id kind provider parent resumed-from forked-from cache-id
           initial-head? alias title origin]
    store-root-dir :store-root}]
  (let [created (time/now-str)
        local-id (or id (session-id))
        logical-id' (or logical-id local-id)
        cache-id' (or cache-id logical-id')
        kind' (or kind :root)
        store-root-path (store-io/path store-root-dir)
        locator (session-locator store-root-path logical-id')
        prompt-metadata (if (= :child kind') prompt/child-prompt-metadata prompt/prompt-metadata)
        session {:session/id logical-id'
                 :session/version session-db/store-version
                 :session/logical-id logical-id'
                 :session/local-id local-id
                 :session/kind kind'
                 :session/status :running
                 :session/created-at created
                 :session/ended-at nil
                 :session/alias alias
                 :session/title title
                 :session/origin (or origin (if parent :invocation :entry))
                 :session/storage {:storage/kind :sqlite+blobstore
                                   :storage/root (str store-root-path)
                                   :blob/store :file}
                 :session/artifact-version artifact-version
                 :session/surface-version surface-version
                 :session/surface surface
                 :session/prompt prompt-metadata
                 :session/provider provider
                 :session/cache-id cache-id'
                 :session/cache (cache/session-cache cache-id')
                 :session/turn-count 0
                 :session/latest-turn-id nil
                 :session/parent parent
                 :session/resumed-from resumed-from
                 :session/forked-from forked-from}
        state (atom (merge event/empty-view
                           {:locator locator
                            :store-root store-root-path
                            :emit-lock (Object.)}))]
    (emit! state {:event/type :session/started :session session})
    (session-db/register-session! (store-root state) session)
    (when (true? initial-head?)
      (create-head! state {:head/kind :initial
                           :head/message-through-id (apply max 0 (map :message/id (:messages @state)))
                           :head/event-range {:from-event 1
                                                :to-event (get-in @state [:counters :event])}}))
    state))
