(ns fractal-engine.artifacts
  "The artifact layer. Holds the live session state, emits events into the
  append-only journal (the source of truth), and materializes projection files at
  boundaries. State changes go through `emit!` -> `event/apply-event`; the table
  files (session/messages/turns/evals/calls/snapshots) and derived views
  (final/usage/tree) are rebuilt by `checkpoint!`, not on every event."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [fractal-engine.cache :as cache]
            [fractal-engine.event :as event]
            [fractal-engine.journal :as journal]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.time :as time])
  (:import [java.io PushbackReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.util UUID]))

(def artifact-version 1)
(def surface-version 2)
(def surface '[FINAL lm map-lm rlm map-rlm attach-rlm])
(def inline-byte-threshold 4096)

(defn session-id []
  (str "session-" (UUID/randomUUID)))

(defn child-id [n]
  (format "child-%04d" (long n)))

(defn path [dir & parts]
  (let [base (cond
               (instance? Path dir) dir
               (instance? java.io.File dir) (.toPath ^java.io.File dir)
               :else (.toPath (io/file dir)))]
    (reduce (fn [^Path p part] (.resolve p (str part)))
            base
            parts)))

(defn ensure-dir! [p]
  (Files/createDirectories (path p) (make-array java.nio.file.attribute.FileAttribute 0))
  p)

(defn formatted-edn [value]
  (binding [*print-dup* false
            *print-readably* true]
    (with-out-str
      (pp/write value :stream *out* :pretty true)
      (newline))))

(defn- atomic-spit! [file value]
  (let [p (path file)
        parent (.getParent p)
        tmp (.resolve parent (str "." (.getFileName p) "." (UUID/randomUUID) ".tmp"))
        bytes (.getBytes (formatted-edn value) StandardCharsets/UTF_8)]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/write tmp bytes (make-array java.nio.file.OpenOption 0))
    (Files/move tmp p
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))
    value))

(defn write-edn! [file value]
  (atomic-spit! file value))

(defn read-edn-file [file default]
  (let [f (if (instance? Path file)
            (.toFile ^Path file)
            (io/file file))]
    (if (.exists f)
      (with-open [r (PushbackReader. (io/reader f))]
        (edn/read {:eof default} r))
      default)))

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

(defn read-ref [dir ref]
  (case (:value/kind ref)
    :inline (:value ref)
    :blob (read-edn-file (path dir (:path ref)) ::missing)
    ::missing))

(defn value-bytes [value]
  (alength (.getBytes (formatted-edn value) StandardCharsets/UTF_8)))

(defn blob-ref! [dir rel-path value]
  (let [text (formatted-edn value)
        bytes (.getBytes text StandardCharsets/UTF_8)]
    (write-edn! (path dir rel-path) value)
    {:value/kind :blob
     :path rel-path
     :sha256 (cache/sha256-string text)
     :bytes (alength bytes)}))

(defn value-ref!
  ([dir value]
   {:value/kind :inline :value value})
  ([dir value {:keys [path threshold]
               :or {threshold inline-byte-threshold}}]
   (if (and path (> (value-bytes value) threshold))
     (blob-ref! dir path value)
     (value-ref! dir value))))

(def root-call-types #{:root})
(def leaf-call-types #{:leaf :leaf-batch-item})
(def child-call-types #{:child :child-batch-item :attached-child})
(def provider-call-types (into root-call-types leaf-call-types))

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

(defn- usage-rollup [calls]
  (let [summary {:call/count (count calls)
                 :tokens/input (numeric-rollup calls #(first-number (:call/usage %) [:usage/input-tokens :usage/prompt-tokens :input-tokens :prompt-tokens]))
                 :tokens/output (numeric-rollup calls #(first-number (:call/usage %) [:usage/output-tokens :usage/completion-tokens :output-tokens :completion-tokens]))
                 :tokens/total (numeric-rollup calls #(first-number (:call/usage %) [:usage/total-tokens :total-tokens]))
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

(defn- read-child-calls [dir call]
  (let [rel (:child/dir call)]
    (when rel
      (let [child-dir (path dir rel)
            child-calls (read-edn-file (path child-dir "calls.edn") [])]
        (mapcat (fn [c]
                  (cons c (read-child-calls child-dir c)))
                child-calls)))))

(defn- tree-calls [dir calls]
  (vec (concat calls (mapcat #(read-child-calls dir %) calls))))

(defn derive-usage [dir calls]
  (let [root-calls (call-group calls #(contains? root-call-types (:call/type %)))
        leaf-calls (call-group calls #(contains? leaf-call-types (:call/type %)))
        child-calls (call-group calls child-call?)
        all-tree-calls (tree-calls dir calls)
        provider-tree-calls (call-group all-tree-calls #(contains? provider-call-types (:call/type %)))
        child-summaries (mapv (fn [call]
                                (let [child-dir (path dir (:child/dir call))
                                      child-usage (read-edn-file (path child-dir "usage.edn") {:usage/status :unknown})]
                                  {:call/id (:call/id call)
                                   :child/session-id (:child/session-id call)
                                   :child/dir (:child/dir call)
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

(defn- child-tree [child-dir]
  (let [session (read-edn-file (path child-dir "session.edn") {})
        calls (read-edn-file (path child-dir "calls.edn") [])
        evals (read-edn-file (path child-dir "evals.edn") [])
        usage (read-edn-file (path child-dir "usage.edn") nil)
        final (read-edn-file (path child-dir "final.edn") nil)
        snapshots (read-edn-file (path child-dir "snapshots.edn") [])]
    {:tree/session-id (:session/id session)
     :tree/status (:session/status session)
     :call/count (count calls)
     :eval/count (count evals)
     :snapshot/count (count snapshots)
     :tree/usage usage
     :tree/final-preview (:final/value-preview final)
     :tree/children
     (->> calls
          (keep (fn [call]
                  (when (contains? child-call-types (:call/type call))
                    (let [dir (path child-dir (:child/dir call))
                          ct (child-tree dir)]
                      (assoc ct
                             :call/id (:call/id call)
                             :edge/type (:edge/type call :spawned)
                             :child/session-id (:child/session-id call)
                             :child/dir (:child/dir call)
                             :child/status (:tree/status ct))))))
          vec)}))

(defn derive-tree [dir session calls]
  {:tree/session-id (:session/id session)
   :tree/status (:session/status session)
   :call/count (count calls)
   :tree/children
   (->> calls
        (keep (fn [call]
                (when (contains? child-call-types (:call/type call))
                  (let [rel (:child/dir call)
                        ct (child-tree (path dir rel))]
                    (assoc ct
                           :call/id (:call/id call)
                           :edge/type (:edge/type call :spawned)
                           :child/session-id (:child/session-id call)
                           :child/dir rel
                           :child/status (:tree/status ct))))))
        vec)})

(defn rebuild-derived! [state]
  (let [{:keys [dir session messages turns evals calls final-ref error]} @state
        latest-turn (last turns)
        latest-final-ref (:turn/final-ref latest-turn)
        latest-final-preview (:turn/final-preview latest-turn)
        final-ref (or latest-final-ref final-ref)
        ;; Read the recorded final value only when no turn preview is available;
        ;; the turn-final path already stores a preview, so this is rare.
        final-value (when (and final-ref (not latest-final-preview))
                      (read-ref dir final-ref))
        preview (or latest-final-preview (when final-ref (project-value final-value)))
        tree (derive-tree dir session calls)
        usage (derive-usage dir calls)]
    (atomic-spit! (path dir "final.edn")
                  (cond-> {:final/session-id (:session/id session)
                           :final/status (:session/status session)
                           :final/turn-count (count turns)
                           :final/latest-turn-id (:turn/id latest-turn)
                           :final/latest-turn-ref (when latest-turn
                                                    {:turn/id (:turn/id latest-turn)})
                           :final/eval-count (count evals)
                           :final/call-count (count calls)
                           :final/child-count (count (filter #(#{:child :child-batch-item} (:call/type %)) calls))
                           :final/usage usage
                           :final/tree-ref "tree.edn"}
                    error (assoc :final/error error)
                    final-ref (assoc :final/value-ref final-ref
                                     :final/latest-value-preview preview
                                     ;; Kept for older inspectors/tests that read this key.
                                     :final/value-preview preview)))
    (atomic-spit! (path dir "usage.edn") usage)
    (atomic-spit! (path dir "tree.edn") tree)))

(defn next-counter! [state k]
  (get-in (swap! state update-in [:counters k] (fnil inc 0)) [:counters k]))

(defn emit!
  "Record one event: assign its id and timestamp, append it to the journal (the
  durable source of truth, O(1)), then fold it into the in-memory view. Serialized
  per session so the journal order and the view stay consistent under the parallel
  fanout of map-lm/map-rlm. The reentrant monitor lets `update-turn!`/`update-call!`
  read-then-emit atomically. Does NOT write projection files — that is `checkpoint!`,
  called at boundaries."
  [state event]
  (locking (:emit-lock @state)
    (let [id (next-counter! state :event)
          ev (assoc event :event/id id :event/at (time/now-str))]
      (journal/append! (:dir @state) ev)
      (swap! state event/apply-event ev)
      ev)))

(defn checkpoint!
  "Materialize the projection files from the current view. Called at boundaries
  (turn end, status change, session stop), not per event. The journal already holds
  every event; these files are derived views for inspection, resume, and fingerprints."
  [state]
  (locking (:emit-lock @state)
    (let [{:keys [dir session messages turns evals calls events snapshots]} @state]
      (ensure-dir! (path dir "children"))
      (atomic-spit! (path dir "session.edn") session)
      (atomic-spit! (path dir "messages.edn") messages)
      (atomic-spit! (path dir "turns.edn") turns)
      (atomic-spit! (path dir "evals.edn") evals)
      (atomic-spit! (path dir "calls.edn") calls)
      (atomic-spit! (path dir "events.edn") events)
      (atomic-spit! (path dir "snapshots.edn") snapshots)
      (rebuild-derived! state)
      state)))

;; Kept name for the many boundary call sites: a flush is a checkpoint.
(def flush! checkpoint!)

(defn add-event!
  "Emit an annotation/lifecycle event (no row change of its own). Returns the
  event id for compatibility with existing call sites."
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
  (let [id (or (:call/id call)
               (next-counter! state :call))
        call' (merge {:call/id id
                      :call/status :running
                      :call/started-at (time/now-str)}
                     call)]
    (emit! state {:event/type :call/started :call call'})
    (add-turn-id! state (:call/turn-id call') :turn/call-ids id)
    call'))

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

(defn update-status! [state status]
  (emit! state {:event/type :session/status
                :status status
                :ended-at (when (not= status :running) (time/now-str))})
  (checkpoint! state))

(defn mark-final! [state value]
  (let [ref (value-ref! (:dir @state) value {:path "blobs/final-value.edn"})]
    (emit! state {:event/type :session/final :final/value-ref ref})))

(defn mark-error! [state error]
  (emit! state {:event/type :session/error :error error :ended-at (time/now-str)})
  (checkpoint! state))

(defn new-state!
  [{:keys [dir id kind provider parent resumed-from forked-from cache-id]}]
  (ensure-dir! dir)
  (ensure-dir! (path dir "children"))
  (let [created (time/now-str)
        sid (or id (session-id))
        cache-id' (or cache-id sid)
        kind' (or kind :root)
        prompt-metadata (if (= :child kind') prompt/child-prompt-metadata prompt/prompt-metadata)
        session {:session/id sid
                 :session/kind kind'
                 :session/status :running
                 :session/created-at created
                 :session/ended-at nil
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
                           {:dir (path dir)
                            :emit-lock (Object.)}))]
    (emit! state {:event/type :session/started :session session})
    (checkpoint! state)
    state))
