(ns fractal-engine.session-db
  "Canonical durable store for sessions.

  SQLite stores hot operational rows, refs, and transaction batches. BlobStore
  stores payloads: message text, code, requests/responses, finals, snapshots,
  vars, and raw errors. Datahike is a rebuildable Datalog index derived from
  SQLite transaction batches plus blob identity facts.

  The local filesystem is only the physical backend for SQLite, Datahike index
  files, and content-addressed blob bytes. Per-session filesystem homes are not
  part of the domain model."
  (:require [clojure.string :as str]
            [fractal-engine.event :as event]
            [fractal-engine.session-sqlite :as sqlite]
            [fractal-engine.session-model :as session-model]
            [fractal-engine.store.index :as store-index]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.store.payload :as store-payload]
            [fractal-engine.store.schema :as store-schema]
            [fractal-engine.store.value :as store-value]
            [fractal-engine.time :as time]
            [taoensso.timbre :as timbre])
  (:import [java.nio.file Files Path]))

(def store-version 3)

(timbre/set-min-level! :warn)

(defn- store-path
  [dir & parts]
  (apply store-io/path dir parts))

(defn locator
  ([root session-id] (locator root session-id nil))
  ([root session-id head-id]
   (cond-> {:store/root (str (store-path root))
            :session/id session-id}
     head-id (assoc :head/id head-id))))

(defn locator?
  [x]
  (and (map? x) (:store/root x) (:session/id x)))

(defn locator-store-root
  ([x] (locator-store-root x nil))
  ([x default-root]
   (store-path (or (:store/root x)
             (:store-root x)
             default-root
             "."))))

(defn locator-session-id
  [x]
  (or (:session/id x)
      (:session-id x)
      (:session/logical-id x)
      (:ref/session x)
      (:head/session x)))

(defn store-root
  [session]
  (some-> (get-in session [:session/storage :storage/root]) store-path))

(defn- index-current!
  [root]
  (store-index/ensure-current! root store-schema/schema))

(defn- index-q
  [root query & args]
  (apply store-index/q root store-schema/schema query args))

(defn- index-pull
  [root pattern lookup-ref]
  (store-index/pull root store-schema/schema pattern lookup-ref))

(defn transact-facts!
  [root blob-refs facts]
  (let [root (store-path root)
        blob-refs (vec (filter store-payload/blob-ref? blob-refs))
        rows (mapv (fn [ref]
                     {:kind :blob
                      :id (:blob/id ref)
                      :value ref})
                   blob-refs)
        tx (vec (concat (keep store-payload/blob-entity blob-refs) (remove nil? facts)))]
    (sqlite/commit! root rows tx)
    tx))

(defn transact-rows-and-facts!
  [root rows blob-refs facts]
  (let [root (store-path root)
        blob-refs (vec (filter store-payload/blob-ref? blob-refs))
        blob-rows (mapv (fn [ref]
                          {:kind :blob
                           :id (:blob/id ref)
                           :value ref})
                        blob-refs)
        tx (vec (concat (keep store-payload/blob-entity blob-refs) (remove nil? facts)))]
    (sqlite/commit! root (vec (concat rows blob-rows)) tx)
    tx))

(defn put-payload-fact!
  [root value fact-fn opts]
  (let [ref (store-payload/write! root value opts)]
    (transact-facts! root [ref] [(fact-fn ref)])
    ref))

(defn- head-blob-refs
  [_root head]
  (filter store-payload/blob-ref?
          [(:head/state-ref head)
           (:head/final-ref head)
           (:head/snapshot-ref head)]))

(defn event-session-id
  [fallback event]
  (or (get-in event [:session :session/id])
      (:ref/session event)
      (get-in event [:message :message/session])
      (get-in event [:turn :turn/session])
      fallback))

(defn session-entity
  [session]
  (let [sid (session-model/logical-session-id session)
        now (time/now-str)]
    (store-value/assoc-some {:session/id sid
                 :session/updated-at now}
                :session/local-id (:session/local-id session)
                :session/cache-id (:session/cache-id session sid)
                :session/title (:session/title session)
                :session/status (:session/status session)
                :session/kind (:session/kind session)
                :session/origin (:session/origin session)
                :session/created-at (:session/created-at session)
                :session/ended-at (:session/ended-at session))))

(defn alias-entity
  [alias session-id]
  (when alias
    {:alias/name (str alias)
     :alias/session (store-value/session-ref session-id)
     :alias/created-at (time/now-str)
     :alias/updated-at (time/now-str)}))

(defn derivation-entity
  [target-session-id source kind created-at]
  (let [source-session-id (:source/session-id source)
        source-head-id (:source/head-id source)]
    (when (and target-session-id source-session-id source-head-id)
      {:derivation/id (store-value/derivation-id target-session-id source-session-id source-head-id kind)
       :derivation/version session-model/derivation-version
       :derivation/type (or kind :restore)
       :derivation/source-session (store-value/session-ref source-session-id)
       :derivation/source-head (store-value/head-ref source-head-id)
       :derivation/target-session (store-value/session-ref target-session-id)
       :derivation/created-at (or created-at (time/now-str))})))

(defn- event-row-index
  [session-id ev]
  (case (:event/type ev)
    :session/started
    {:event/row-kind :session
     :event/row-id session-id}

    :session/status
    {:event/row-kind :session
     :event/row-id session-id
     :event/status (:status ev)}

    :session/error
    {:event/row-kind :session
     :event/row-id session-id
     :event/status :error}

    :message/added
    (let [m (:message ev)]
      {:event/row-kind :message
       :event/row-id (str (:message/id m))
       :event/message-id (some-> (:message/id m) long)
       :event/turn-id (some-> (:message/turn-id m) long)
       :event/call-id (some-> (:message/call-id m) long)})

    :turn/started
    (let [t (:turn ev)]
      {:event/row-kind :turn
       :event/row-id (str (:turn/id t))
       :event/turn-id (some-> (:turn/id t) long)
       :event/status (:turn/status t)})

    :turn/put
    (let [t (:turn ev)]
      {:event/row-kind :turn
       :event/row-id (str (:turn/id t))
       :event/turn-id (some-> (:turn/id t) long)
       :event/status (:turn/status t)})

    :eval/added
    (let [e (:eval ev)]
      {:event/row-kind :eval
       :event/row-id (str (:eval/id e))
       :event/eval-id (some-> (:eval/id e) long)
       :event/turn-id (some-> (:eval/turn-id e) long)
       :event/message-id (some-> (:eval/message-id e) long)
       :event/call-id (some-> (:eval/call-id e) long)
       :event/status (:eval/status e)})

    :call/started
    (let [c (:call ev)]
      {:event/row-kind :call
       :event/row-id (str (:call/id c))
       :event/call-id (some-> (:call/id c) long)
       :event/turn-id (some-> (:call/turn-id c) long)
       :event/status (:call/status c)})

    :call/put
    (let [c (:call ev)]
      {:event/row-kind :call
       :event/row-id (str (:call/id c))
       :event/call-id (some-> (:call/id c) long)
       :event/turn-id (some-> (:call/turn-id c) long)
       :event/status (:call/status c)})

    :snapshot/added
    (let [s (:snapshot ev)]
      {:event/row-kind :snapshot
       :event/row-id (str (:snapshot/id s))
       :event/snapshot-id (some-> (:snapshot/id s) long)
       :event/turn-id (some-> (:snapshot/turn-id s) long)})

    :head/created
    (let [h (:head ev)]
      {:event/row-kind :head
       :event/row-id (:head/id h)
       :event/head-id (:head/id h)
       :event/turn-id (some-> (:head/turn-id h) long)
       :event/snapshot-id (some-> (:head/snapshot-id h) long)
       :event/status (:head/status h)})

    :session/ref-updated
    {:event/row-kind :session-ref
     :event/row-id (:ref/current-head ev)
     :event/head-id (:ref/current-head ev)}

    :session/restored
    (let [source (or (get-in ev [:session-patch :session/restored-from])
                     (get-in ev [:state :session :session/restored-from]))]
      {:event/row-kind :restore
       :event/row-id (or (:source/head-id source) (:source/session-id source) session-id)
       :event/source-session-id (:source/session-id source)
       :event/source-head-id (:source/head-id source)
       :event/turn-id (some-> (:source/turn-id source) long)
       :event/snapshot-id (some-> (:source/snapshot-id source) long)})

    :invocation/started
    (let [i (:invocation ev)]
      {:event/row-kind :invocation
       :event/row-id (:invocation/id i)
       :event/invocation-id (:invocation/id i)
       :event/call-id (some-> (:invocation/call-id i) long)
       :event/status (:invocation/status i)})

    :invocation/completed
    (let [i (:invocation ev)]
      {:event/row-kind :invocation
       :event/row-id (:invocation/id i)
       :event/invocation-id (:invocation/id i)
       :event/status :final})

    :invocation/failed
    (let [i (:invocation ev)]
      {:event/row-kind :invocation
       :event/row-id (:invocation/id i)
       :event/invocation-id (:invocation/id i)
       :event/status :error})

    :invocation/caller-head-recorded
    (let [i (:invocation ev)]
      {:event/row-kind :invocation
       :event/row-id (:invocation/id i)
       :event/invocation-id (:invocation/id i)
       :event/head-id (:caller/head-after i)})

    {:event/row-kind :annotation
     :event/row-id (str (or (:call/id ev)
                            (:turn/id ev)
                            (:snapshot/id ev)
                            (:head/id ev)
                            (:session/id ev)
                            (:event/id ev)))
     :event/call-id (some-> (:call/id ev) long)
     :event/turn-id (some-> (:turn/id ev) long)
     :event/snapshot-id (some-> (:snapshot/id ev) long)
     :event/head-id (:head/id ev)
     :event/status (or (:call/status ev) (:restore/status ev))}))

(defn- small-scalar?
  [v]
  (or (nil? v)
      (keyword? v)
      (symbol? v)
      (number? v)
      (boolean? v)
      (and (string? v) (<= (count v) 512))))

(defn- compact-metadata-value
  [v depth]
  (cond
    (store-payload/blob-ref? v) (select-keys v [:blob/id :blob/sha256 :blob/size :blob/key])
    (small-scalar? v) v
    (and (map? v) (< depth 2))
    (into {}
          (keep (fn [[k v']]
                  (when (or (small-scalar? v') (store-payload/blob-ref? v'))
                    [k (compact-metadata-value v' (inc depth))])))
          v)
    (and (sequential? v) (< depth 1))
    (vec (keep #(when (or (small-scalar? %) (store-payload/blob-ref? %))
                  (compact-metadata-value % (inc depth)))
               (take 20 v)))
    :else nil))

(defn- compact-event-metadata
  [ev]
  (let [skip #{:event/type :event/id :event/at :session :message :turn :eval
               :call :snapshot :head :invocation :state :session-patch}]
    (not-empty
     (into {}
           (keep (fn [[k v]]
                   (when-not (skip k)
                     (when-let [v' (compact-metadata-value v 0)]
                       [k v']))))
           ev))))

(def metadata-event-types
  #{:session/error :turn-error :provider-failed :attach-rlm-error})

(defn- metadata-payload-ref!
  [root ev]
  (when (or (contains? metadata-event-types (:event/type ev))
            (not (:event/row-kind (event-row-index nil ev))))
    (when-let [metadata (compact-event-metadata ev)]
      (store-payload/write! root {:event/metadata metadata}
                      {:payload/kind :event-metadata}))))

(defn event-entity
  [session-id ev payload-ref]
  (store-value/assoc-some (merge {:event/uid (store-value/uid session-id :event (:event/id ev))
                      :event/id (long (:event/id ev))
                      :event/session (store-value/session-ref session-id)
                      :event/type (:event/type ev)
                      :event/at (:event/at ev)}
                     (into {}
                           (remove (comp nil? val))
                           (event-row-index session-id ev)))
              :event/payload-ref (store-payload/blob-lookup payload-ref)))

(defn event-record
  [root session-id ev payload-ref]
  (store-value/assoc-some (merge {:event/id (long (:event/id ev))
                      :event/type (:event/type ev)
                      :event/session session-id
                      :event/at (:event/at ev)}
                     (into {}
                           (remove (comp nil? val))
                           (event-row-index session-id ev)))
              :event/payload-ref payload-ref
              :event/metadata (when payload-ref
                                (:event/metadata (store-payload/read-edn root payload-ref)))))

(defn message-entity
  [session-id message payload-ref]
  (store-value/assoc-some {:message/uid (store-value/uid session-id :message (:message/id message))
               :message/id (long (:message/id message))
               :message/session (store-value/session-ref session-id)
               :message/role (:message/role message)
               :message/content-ref (store-payload/blob-lookup payload-ref)
               :message/char-count (long (count (str (:message/content message))))
               :message/created-at (time/now-str)}
              :message/call-id (some-> (:message/call-id message) long)
              :message/turn (store-value/turn-ref session-id (:message/turn-id message))))

(defn turn-entity
  [session-id turn]
  (store-value/assoc-some {:turn/uid (store-value/uid session-id :turn (:turn/id turn))
               :turn/id (long (:turn/id turn))
               :turn/session (store-value/session-ref session-id)}
              :turn/status (:turn/status turn)
              :turn/head-before (store-value/head-ref (:turn/head-before turn))
              :turn/head-after (store-value/head-ref (:turn/head-after turn))
              :turn/final-ref (store-payload/blob-lookup (:turn/final-ref turn))
              :turn/final-summary (some-> (:turn/final-preview turn) store-value/preview-string)
              :turn/started-at (:turn/started-at turn)
              :turn/ended-at (:turn/ended-at turn)))

(defn eval-entity
  [root session-id row]
  (let [code-ref (store-payload/write! root {:eval/code (:eval/code row)}
                                 {:payload/kind :eval-code})
        result-ref (or (:eval/result-ref row)
                       (when (contains? row :eval/value)
                         (store-payload/write! root {:eval/result (:eval/value row)}
                                         {:payload/kind :eval-result})))
        error-ref (or (:eval/error-ref row)
                      (when-let [err (:eval/error row)]
                        (store-payload/write! root {:eval/error err}
                                        {:payload/kind :eval-error})))
        refs (keep identity [code-ref result-ref error-ref (:eval/final-ref row)])]
    {:blob-refs refs
     :entity (store-value/assoc-some {:eval/uid (store-value/uid session-id :eval (:eval/id row))
                          :eval/id (long (:eval/id row))
                          :eval/session (store-value/session-ref session-id)}
                         :eval/turn (store-value/turn-ref session-id (:eval/turn-id row))
                         :eval/message (store-value/message-ref session-id (:eval/message-id row))
                         :eval/status (:eval/status row)
                         :eval/code-ref (store-payload/blob-lookup code-ref)
                         :eval/result-ref (store-payload/blob-lookup result-ref)
                         :eval/final-ref (store-payload/blob-lookup (:eval/final-ref row))
                         :eval/error-ref (store-payload/blob-lookup error-ref)
                         :eval/started-at (:eval/started-at row)
                         :eval/ended-at (:eval/ended-at row))
     :row (store-value/assoc-some
           {:eval/id (:eval/id row)
            :eval/session session-id
            :eval/status (:eval/status row)
            :eval/code-ref code-ref
            :eval/result-ref result-ref
            :eval/final-ref (:eval/final-ref row)
            :eval/error-ref error-ref
            :eval/started-at (:eval/started-at row)
            :eval/ended-at (:eval/ended-at row)}
           :eval/turn-id (:eval/turn-id row)
           :eval/message-id (:eval/message-id row))}))

(defn call-entity
  [session-id call]
  (let [usage (:call/usage call)
        cost (:call/cost call)]
    (store-value/assoc-some {:call/uid (store-value/uid session-id :call (:call/id call))
                 :call/id (long (:call/id call))
                 :call/session (store-value/session-ref session-id)}
                :call/turn (store-value/turn-ref session-id (:call/turn-id call))
                :call/type (:call/type call)
                :call/status (:call/status call)
                :call/input-ref (store-payload/blob-lookup (:call/input-ref call))
                :call/request-ref (store-payload/blob-lookup (:call/request-ref call))
                :call/response-ref (store-payload/blob-lookup (:call/response-ref call))
                :call/result-ref (store-payload/blob-lookup (:call/result-ref call))
                :call/final-ref (store-payload/blob-lookup (:call/final-ref call))
                :call/error-ref (store-payload/blob-lookup (:call/error-ref call))
                :call/parent-eval-id (some-> (:call/parent-eval-id call) long)
                :call/request-storage (:call/request-storage call)
                :call/request-message-count (some-> (:call/request-message-count call) long)
                :call/request-system-hash (:call/request-system-hash call)
                :call/model (:call/model call)
                :call/provider (:call/provider call)
                :call/input-tokens (store-value/long-field usage [:usage/input-tokens :usage/prompt-tokens :input-tokens :prompt-tokens])
                :call/output-tokens (store-value/long-field usage [:usage/output-tokens :usage/completion-tokens :output-tokens :completion-tokens])
                :call/cost-usd (some-> (store-value/numeric-field cost [:cost/usd :usd]) double)
                :call/cache-id (:call/cache-scope call)
                :edge/type (:edge/type call)
                :batch/id (:batch/id call)
                :batch/index (some-> (:batch/index call) long)
                :child/session-id (:child/session-id call)
                :child/logical-session-id (:child/logical-session-id call)
                :child/cache-id (:child/cache-id call)
                :child/head-before (:child/head-before call)
                :child/head-after (:child/head-after call)
                :invocation/call-row-id (:invocation/id call)
                :call/created-at (:call/started-at call)
                :call/completed-at (:call/ended-at call))))

(defn snapshot-entity
  [session-id snapshot]
  (store-value/assoc-some {:snapshot/uid (store-value/uid session-id :snapshot (:snapshot/id snapshot))
               :snapshot/id (long (:snapshot/id snapshot))
               :snapshot/session (store-value/session-ref session-id)}
              :snapshot/turn (store-value/turn-ref session-id (:snapshot/turn-id snapshot))
              :snapshot/kind (:snapshot/kind snapshot)
              :snapshot/eval-id (some-> (:snapshot/eval-id snapshot) long)
              :snapshot/current-ns (:snapshot/current-ns snapshot)
              :snapshot/ref (store-payload/blob-lookup (:snapshot/ref snapshot))
              :snapshot/var-count (some-> (get-in snapshot [:snapshot/summary :var/count]) long)
              :snapshot/restorable-count (some-> (get-in snapshot [:snapshot/summary :var/restorable]) long)
              :snapshot/unrestorable-count (some-> (get-in snapshot [:snapshot/summary :var/unrestorable]) long)
              :snapshot/message-through-id (some-> (:snapshot/message-through-id snapshot) long)
              :snapshot/created-at (:snapshot/created-at snapshot)))

(defn head-entity
  [head]
  (store-value/assoc-some {:head/id (:head/id head)
               :head/session (store-value/session-ref (:head/session head))}
              :head/n (some-> (:head/n head) long)
              :head/basis (store-value/head-ref (:head/basis head))
              :head/status (:head/status head :complete)
              :head/kind (:head/kind head)
              :head/fingerprint (:head/fingerprint head)
              :head/state-ref (store-payload/blob-lookup (:head/state-ref head))
              :head/state-version (some-> (:head/state-version head) long)
              :head/final-ref (store-payload/blob-lookup (:head/final-ref head))
              :head/snapshot-ref (store-payload/blob-lookup (:head/snapshot-ref head))
              :head/snapshot-id (some-> (:head/snapshot-id head) long)
              :head/final-summary (some-> (:head/final-preview head) store-value/preview-string)
              :head/final-kind (some-> (:head/final-preview head) store-value/value-kind)
              :head/created-at (:head/created-at head)
              :head/turn-id (some-> (:head/turn-id head) long)
              :head/cache-id (:head/cache-id head)
              :head/message-through-id (some-> (:head/message-through-id head) long)))

(defn invocation-entity
  [session-id inv]
  (store-value/assoc-some {:invocation/id (:invocation/id inv)}
              :invocation/n (some-> (:invocation/n inv) long)
              :invocation/type (:invocation/type inv)
              :invocation/status (:invocation/status inv)
              :invocation/caller-session (store-value/session-ref (:caller/session inv))
              :invocation/caller-head-before (store-value/head-ref (:caller/head-before inv))
              :invocation/caller-head-after (store-value/head-ref (:caller/head-after inv))
              :invocation/callee-session (store-value/session-ref (:callee/session inv))
              :invocation/callee-head-before (store-value/head-ref (:callee/head-before inv))
              :invocation/callee-head-after (store-value/head-ref (:callee/head-after inv))
              :invocation/call-uid (when-let [id (:invocation/call-id inv)]
                                     (store-value/uid session-id :call id))
              :invocation/label (:invocation/label inv)
              :invocation/input-ref (store-payload/blob-lookup (:invocation/input-ref inv))
              :invocation/final-ref (store-payload/blob-lookup (:invocation/final-ref inv))
              :invocation/created-at (or (:invocation/started-at inv) (time/now-str))
              :invocation/completed-at (:invocation/ended-at inv)
              :attach/source-session (store-value/session-ref (:attach/source-session-id inv))
              :attach/source-head (store-value/head-ref (:attach/source-head-id inv))
              :attach/source-snapshot-ref (store-payload/blob-lookup (:attach/source-snapshot-ref inv))
              :attach/source-fingerprint (:attach/source-fingerprint inv)))

(defn session-current-head-entity
  [session-id head-id]
  (when (and session-id head-id)
    {:session/id session-id
     :session/current-head (store-value/head-ref head-id)
     :session/updated-at (time/now-str)}))

(defn- row-id
  [session-id k id]
  (str session-id ":" (name k) ":" id))

(defn- canonical-row
  ([kind id value] (canonical-row kind id value nil nil))
  ([kind id value session-id sort-id]
   {:kind kind
    :id id
    :value value
    :session-id session-id
    :sort-id sort-id}))

(defn- derivation-record
  [target-session-id source kind created-at]
  (let [source-session-id (:source/session-id source)
        source-head-id (:source/head-id source)]
    (when (and target-session-id source-session-id source-head-id)
      {:derivation/id (store-value/derivation-id target-session-id source-session-id source-head-id kind)
       :derivation/version session-model/derivation-version
       :derivation/type (or kind :restore)
       :derivation/source-session source-session-id
       :derivation/source-head source-head-id
       :derivation/target-session target-session-id
       :derivation/created-at (or created-at (time/now-str))})))

(defn- put-event-row!
  [root session-id ev metadata-ref]
  (when session-id
    (sqlite/put-row! root :event (row-id session-id :event (:event/id ev))
                     (event-record root session-id ev metadata-ref)
                     {:session-id session-id
                      :sort-id (:event/id ev)})))

(defn- put-session-row!
  [root session]
  (when-let [sid (some-> session session-model/logical-session-id)]
    (sqlite/put-row! root :session sid session {:session-id sid})))

(defn- update-session-row!
  [root session-id patch]
  (when session-id
    (let [existing (or (sqlite/get-row root :session session-id)
                       {:session/id session-id})]
      (put-session-row! root (merge existing patch)))))

(defn- put-alias-row!
  [root alias session-id]
  (when alias
    (sqlite/put-row! root :alias (str alias)
                     {:alias/name (str alias)
                      :alias/session session-id
                      :alias/authority :canonical
                      :alias/updated-at (time/now-str)}
                     {:session-id session-id})))

(defn- put-ref-row!
  [root session-id head-id previous-head fingerprint]
  (when (and session-id head-id)
    (sqlite/put-row! root :ref session-id
                     (store-value/assoc-some {:ref/version store-version
                                  :ref/authority :canonical
                                  :ref/session session-id
                                  :ref/current-head head-id
                                  :ref/updated-at (time/now-str)}
                                 :ref/previous-head previous-head
                                 :ref/head-fingerprint fingerprint)
                     {:session-id session-id})))

(defn- put-message-row!
  [root session-id message content-ref]
  (sqlite/put-row! root :message (row-id session-id :message (:message/id message))
                   (store-value/assoc-some
                    {:message/id (:message/id message)
                     :message/session session-id
                     :message/role (:message/role message)
                     :message/content-ref content-ref
                     :message/char-count (long (count (str (:message/content message))))
                     :message/created-at (time/now-str)}
                    :message/turn-id (:message/turn-id message)
                    :message/call-id (:message/call-id message))
                   {:session-id session-id
                    :sort-id (:message/id message)}))

(defn- put-turn-row!
  [root session-id turn]
  (sqlite/put-row! root :turn (row-id session-id :turn (:turn/id turn))
                   (assoc turn :turn/session session-id)
                   {:session-id session-id
                    :sort-id (:turn/id turn)}))

(defn- put-eval-row!
  [root session-id row]
  (sqlite/put-row! root :eval (row-id session-id :eval (:eval/id row))
                   row
                   {:session-id session-id
                    :sort-id (:eval/id row)}))

(defn- put-call-row!
  [root session-id call]
  (sqlite/put-row! root :call (row-id session-id :call (:call/id call))
                   (assoc call :call/session session-id)
                   {:session-id session-id
                    :sort-id (:call/id call)}))

(defn- put-snapshot-row!
  [root session-id snapshot]
  (sqlite/put-row! root :snapshot (row-id session-id :snapshot (:snapshot/id snapshot))
                   (assoc snapshot :snapshot/session session-id)
                   {:session-id session-id
                    :sort-id (:snapshot/id snapshot)}))

(defn- put-head-row!
  [root head]
  (sqlite/put-row! root :head (:head/id head) head
                   {:session-id (:head/session head)
                    :sort-id (:head/n head)}))

(defn- put-invocation-row!
  [root invocation]
  (sqlite/put-row! root :invocation (:invocation/id invocation) invocation
                   {:session-id (:caller/session invocation)
                    :sort-id (:invocation/n invocation)}))

(defn- put-derivation-row!
  [root derivation]
  (when derivation
    (sqlite/put-row! root :derivation (:derivation/id derivation) derivation
                     {:session-id (:derivation/target-session derivation)})))

(defn record-event!
  [root fallback-session-id ev]
  (let [root (store-path root)
        session-id (event-session-id fallback-session-id ev)
        metadata-ref (metadata-payload-ref! root ev)
        base-blobs (filter store-payload/blob-ref? [metadata-ref])
        base-facts [(when session-id (event-entity session-id ev metadata-ref))]
        event-row (when session-id
                    (canonical-row :event
                                   (row-id session-id :event (:event/id ev))
                                   (event-record root session-id ev metadata-ref)
                                   session-id
                                   (:event/id ev)))
        {:keys [blob-refs facts rows]}
        (case (:event/type ev)
          :session/started
          (let [session (:session ev)
                sid (session-model/logical-session-id session)]
            {:blob-refs []
             :facts [(session-entity session)
                     (alias-entity (:session/alias session) sid)]
             :rows (cond-> [(canonical-row :session sid session sid nil)]
                     (:session/alias session)
                     (conj (canonical-row :alias (str (:session/alias session))
                                          {:alias/name (str (:session/alias session))
                                           :alias/session sid
                                           :alias/authority :canonical
                                           :alias/updated-at (:event/at ev)}
                                          sid nil)))})

          :session/status
          {:blob-refs []
           :facts [(store-value/assoc-some {:session/id session-id
                                 :session/status (:status ev)
                                 :session/updated-at (:event/at ev)}
                                :session/ended-at (:ended-at ev))]
           :rows [(canonical-row :session session-id
                                 (merge (or (sqlite/get-row root :session session-id)
                                            {:session/id session-id})
                                        (store-value/assoc-some {:session/status (:status ev)
                                                     :session/updated-at (:event/at ev)}
                                                    :session/ended-at (:ended-at ev)))
                                 session-id nil)]}

          :session/error
          (let [error-ref (store-payload/write! root {:error (:error ev)}
                                          {:payload/kind :error})]
            {:blob-refs [error-ref]
             :facts [{:session/id session-id
                      :session/status :error
                      :session/ended-at (:ended-at ev)
                      :session/updated-at (:event/at ev)}]
             :rows [(canonical-row :session session-id
                                   (merge (or (sqlite/get-row root :session session-id)
                                              {:session/id session-id})
                                          {:session/status :error
                                           :session/ended-at (:ended-at ev)
                                           :session/updated-at (:event/at ev)})
                                   session-id nil)]})

          :message/added
          (let [message (:message ev)
                ref (store-payload/write! root (select-keys message [:message/role :message/content])
                                    {:payload/kind :message})]
            {:blob-refs [ref]
             :facts [(message-entity session-id message ref)]
             :rows [(canonical-row :message (row-id session-id :message (:message/id message))
                                   (store-value/assoc-some
                                    {:message/id (:message/id message)
                                     :message/session session-id
                                     :message/role (:message/role message)
                                    :message/content-ref ref
                                     :message/char-count (long (count (str (:message/content message))))
                                     :message/created-at (:event/at ev)}
                                    :message/turn-id (:message/turn-id message)
                                    :message/call-id (:message/call-id message))
                                   session-id (:message/id message))]})

          :turn/started
          {:blob-refs []
           :facts [(turn-entity session-id (:turn ev))]
           :rows [(canonical-row :turn (row-id session-id :turn (get-in ev [:turn :turn/id]))
                                 (assoc (:turn ev) :turn/session session-id)
                                 session-id (get-in ev [:turn :turn/id]))]}

          :turn/put
          {:blob-refs (filter store-payload/blob-ref? [(:turn/final-ref (:turn ev))])
           :facts [(turn-entity session-id (:turn ev))]
           :rows [(canonical-row :turn (row-id session-id :turn (get-in ev [:turn :turn/id]))
                                 (assoc (:turn ev) :turn/session session-id)
                                 session-id (get-in ev [:turn :turn/id]))]}

          :eval/added
          (let [{:keys [blob-refs entity row]} (eval-entity root session-id (:eval ev))]
            {:blob-refs blob-refs
             :facts [entity]
             :rows [(canonical-row :eval (row-id session-id :eval (:eval/id row))
                                   row session-id (:eval/id row))]})

          :call/started
          {:blob-refs (filter store-payload/blob-ref? ((juxt :call/input-ref
                                               :call/request-ref :call/response-ref
                                               :call/result-ref :call/final-ref
                                               :call/error-ref)
                                         (:call ev)))
           :facts [(call-entity session-id (:call ev))]
           :rows [(canonical-row :call (row-id session-id :call (get-in ev [:call :call/id]))
                                 (assoc (:call ev) :call/session session-id)
                                 session-id (get-in ev [:call :call/id]))]}

          :call/put
          {:blob-refs (filter store-payload/blob-ref? ((juxt :call/input-ref
                                               :call/request-ref :call/response-ref
                                               :call/result-ref :call/final-ref
                                               :call/error-ref)
                                         (:call ev)))
           :facts [(call-entity session-id (:call ev))]
           :rows [(canonical-row :call (row-id session-id :call (get-in ev [:call :call/id]))
                                 (assoc (:call ev) :call/session session-id)
                                 session-id (get-in ev [:call :call/id]))]}

          :snapshot/added
          {:blob-refs (filter store-payload/blob-ref? [(:snapshot/ref (:snapshot ev))])
           :facts [(snapshot-entity session-id (:snapshot ev))]
           :rows [(canonical-row :snapshot (row-id session-id :snapshot (get-in ev [:snapshot :snapshot/id]))
                                 (assoc (:snapshot ev) :snapshot/session session-id)
                                 session-id (get-in ev [:snapshot :snapshot/id]))]}

          :head/created
          {:blob-refs (head-blob-refs root (:head ev))
           :facts [(head-entity (:head ev))]
           :rows [(canonical-row :head (get-in ev [:head :head/id])
                                 (:head ev)
                                 (get-in ev [:head :head/session])
                                 (get-in ev [:head :head/n]))]}

          :session/ref-updated
          {:blob-refs []
           :facts [(session-current-head-entity (:ref/session ev) (:ref/current-head ev))]
           :rows [(canonical-row :ref (:ref/session ev)
                                 {:ref/version store-version
                                  :ref/authority :canonical
                                  :ref/session (:ref/session ev)
                                  :ref/current-head (:ref/current-head ev)
                                  :ref/updated-at (:event/at ev)}
                                 (:ref/session ev) nil)]}

          :session/restored
          (let [source (or (get-in ev [:session-patch :session/restored-from])
                           (get-in ev [:state :session :session/restored-from]))
                kind (or (get-in ev [:session-patch :session/lineage-kind])
                         (get-in ev [:state :session :session/lineage-kind]))]
            {:blob-refs []
             :facts (filterv identity
                             [{:session/id session-id
                               :session/updated-at (:event/at ev)}
                              (derivation-entity session-id source kind (:event/at ev))])
             :rows (cond-> [(canonical-row :session session-id
                                           (merge (or (sqlite/get-row root :session session-id)
                                                      {:session/id session-id})
                                                  {:session/updated-at (:event/at ev)})
                                           session-id nil)]
                     (derivation-record session-id source kind (:event/at ev))
                     (conj (let [d (derivation-record session-id source kind (:event/at ev))]
                             (canonical-row :derivation (:derivation/id d) d session-id nil))))})

          :invocation/started
          {:blob-refs (filter store-payload/blob-ref? [(:invocation/input-ref (:invocation ev))
                                         (:invocation/final-ref (:invocation ev))])
           :facts [(invocation-entity session-id (:invocation ev))]}

          :invocation/completed
          {:blob-refs (filter store-payload/blob-ref? [(:invocation/final-ref (:invocation ev))])
           :facts [(invocation-entity session-id (assoc (:invocation ev) :invocation/status :final))]}

          :invocation/failed
          (let [error-ref (when-let [err (:invocation/error (:invocation ev))]
                            (store-payload/write! root {:error err} {:payload/kind :error}))]
            {:blob-refs (filter store-payload/blob-ref? [(:invocation/final-ref (:invocation ev)) error-ref])
             :facts [(invocation-entity session-id (assoc (:invocation ev) :invocation/status :error))]})

          :invocation/caller-head-recorded
          {:blob-refs []
           :facts [(invocation-entity session-id (:invocation ev))]}

          {:blob-refs []
           :facts []})]
    (transact-rows-and-facts! root
                              (cond-> (vec rows) event-row (conj event-row))
                              (concat base-blobs blob-refs)
                              (if (= :session/started (:event/type ev))
                                (concat facts base-facts)
                                (concat base-facts facts)))))

(defn load-events
  [root session-id]
  (sqlite/rows-for-session root :event session-id))

(defn read-ref
  [root session-id]
  (sqlite/get-row root :ref session-id))

(defn- turn-id-from-ref
  [row k]
  (get-in row [k :turn/id]))

(defn- head-id-from-ref
  [row k]
  (get-in row [k :head/id]))

(defn- blob-ref-from-row
  [row k]
  (get row k))

(defn message-records
  ([root session-id] (message-records root session-id {}))
  ([root session-id {:keys [include-content?]}]
   (mapv (fn [row]
           (let [content-ref (:message/content-ref row)
                 content (when (and include-content? content-ref)
                           (:message/content (store-payload/read-edn root content-ref)))]
             (cond-> row content (assoc :message/content content))))
         (sqlite/rows-for-session root :message session-id))))

(defn turn-records
  [root session-id]
  (sqlite/rows-for-session root :turn session-id))

(defn eval-records
  [root session-id]
  (sqlite/rows-for-session root :eval session-id))

(defn call-records
  [root session-id]
  (sqlite/rows-for-session root :call session-id))

(defn snapshot-records
  [root session-id]
  (sqlite/rows-for-session root :snapshot session-id))

(declare direct-session-status)

(defn event-view
  "Compact audit/history view from SQLite rows. This is not a replay source
  for current state; current reads dereference head-state roots."
  [root session-id]
  (let [session (direct-session-status root session-id)
        turns (turn-records root session-id)]
    (merge event/empty-view
           {:session (assoc session :session/id session-id)
            :messages (message-records root session-id)
            :turns turns
            :evals (eval-records root session-id)
            :calls (call-records root session-id)
            :snapshots (snapshot-records root session-id)
            :refs (or (read-ref root session-id) {})
            :events (load-events root session-id)
            :final-ref (some :turn/final-ref (reverse turns))})))

(defn- normalize-head
  [head]
  (when (:head/id head)
    (-> head
        (update :head/session #(or (:session/id %) %))
        (update :head/basis #(or (:head/id %) %)))))

(declare read-head-state*)

(defn read-head
  ([root head-id]
   (sqlite/get-row root :head head-id))
  ([root session-id head-id]
   (let [head (read-head root head-id)]
     (when (= session-id (:head/session head))
       head))))

(defn- upsert-row
  [rows id-key row]
  (let [id (id-key row)
        matched? (atom false)
        rows' (mapv (fn [existing]
                      (if (= id (id-key existing))
                        (do
                          (reset! matched? true)
                          row)
                        existing))
                    rows)]
    (if @matched?
      rows'
      (conj rows' row))))

(defn- apply-collection-delta
  [rows id-key delta]
  (let [remove-ids (set (:remove delta))]
    (-> (reduce #(upsert-row %1 id-key %2)
                (vec (remove #(contains? remove-ids (id-key %)) (or rows [])))
                (:replace delta))
        (into (:append delta)))))

(defn- read-state-delta
  [root delta-ref]
  (when delta-ref
    (store-payload/read-edn root delta-ref)))

(defn- snapshot-vars-from-ref
  [root snapshot-ref]
  (when snapshot-ref
    (some-> (store-payload/read-edn root snapshot-ref)
            :snapshot/vars
            vec)))

(defn- materialize-compact-head-state
  [root session-id state cache]
  (let [basis-head (:state/basis-head state)
        basis-state (when basis-head
                      (read-head-state* root session-id basis-head cache))
        basis-view (or (some-> basis-state session-model/head-state->view)
                       event/empty-view)
        view (reduce-kv
              (fn [view collection-key {:keys [delta-ref]}]
                (if-let [delta (read-state-delta root delta-ref)]
                  (update view collection-key
                          apply-collection-delta
                          (get session-model/state-collection-id-keys collection-key)
                          delta)
                  view))
              basis-view
              (:state/collections state))]
    (merge {:state/version (:state/version state)
            :state/session-id (:state/session-id state)
            :state/head-id (:state/head-id state)
            :state/basis-head basis-head
            :state/created-at (:state/created-at state)
            :state/head (:state/head state)
            :vars (vec (or (snapshot-vars-from-ref root (:vars-ref state)) []))}
           view
           {:session (:session state)
            :refs (:refs state)
            :final-ref (:final-ref state)
            :error (:error state)
            :counters (:counters state)})))

(defn- materialize-head-state
  [root session-id state cache]
  (case (:state/version state)
    2 (materialize-compact-head-state root session-id state cache)
    state))

(defn- read-head-state*
  [root session-id head-id cache]
  (let [cache-key [session-id head-id]]
    (if (contains? @cache cache-key)
      (get @cache cache-key)
	      (when-let [state-ref (:head/state-ref (read-head root session-id head-id))]
	        (let [materialized (try
	                             (when-let [state (store-payload/read-edn root state-ref)]
	                               (materialize-head-state root session-id state cache))
	                             (catch Throwable _ nil))]
	          (swap! cache assoc cache-key materialized)
	          materialized)))))

(defn read-head-state
  ([root head-id]
   (when-let [head (read-head root head-id)]
     (read-head-state root (:head/session head) head-id)))
  ([root session-id head-id]
   (read-head-state* root session-id head-id (atom {}))))

(defn current-head-id
  [root session-id]
  (:ref/current-head (read-ref root session-id)))

(declare list-derivation-records)

(defn direct-session-status
  [root session-id]
  (let [s (sqlite/get-row root :session session-id)]
    (select-keys s [:session/status :session/updated-at :session/ended-at
                    :session/cache-id :session/local-id :session/kind :session/origin])))

(defn view
  "Current/head view for a session. If a head is selected, dereference that
  immutable head state. Otherwise dereference session/current-head. The event
  fold remains available through history-view/event-view."
  ([root session-id] (view root session-id {}))
  ([root session-id opts]
   (let [hid (or (:head-id opts) (:head/id opts) (current-head-id root session-id))]
     (or (when hid
           (some-> (session-model/head-state->view (read-head-state root session-id hid))
                   (update :session merge (direct-session-status root session-id))))
         (event-view root session-id)))))

(defn history-view
  "Audit/history view. Events are the complete event log; heads/invocations are
  read directly from SQLite rows so abandoned branches remain visible."
  [root session-id]
  (let [folded (event-view root session-id)]
    (assoc folded
           :heads (sqlite/rows-for-session root :head session-id)
	           :derivations (filterv #(or (= session-id (:derivation/source-session %))
	                                      (= session-id (:derivation/target-session %)))
	                                  (list-derivation-records root))
	           :history/ref (read-ref root session-id)
	           :history/events (:events folded))))

(defn- max-long-for-session
  [root session-id kind id-key]
  (reduce max 0 (keep id-key (sqlite/rows-for-session root kind session-id))))

(defn- max-event-id
  [root session-id]
  (max-long-for-session root session-id :event :event/id))

(defn- head-count
  [root session-id]
  (count (sqlite/rows-for-session root :head session-id)))

(defn high-water-counters
  "Session-local high-water counters from canonical facts. Restoring from an older
  head must continue above abandoned historical ids, not from that head's local
  counter value."
  [root session-id]
  (let [calls (call-records root session-id)]
    {:message (max-long-for-session root session-id :message :message/id)
     :turn (max-long-for-session root session-id :turn :turn/id)
     :eval (max-long-for-session root session-id :eval :eval/id)
     :call (max-long-for-session root session-id :call :call/id)
     :event (max-event-id root session-id)
     :snapshot (max-long-for-session root session-id :snapshot :snapshot/id)
     :head (head-count root session-id)
     :invocation (max-long-for-session root session-id :invocation :invocation/n)
     :child (event/recover-child-counter {:calls calls})}))

(defn read-session
  [root session-id]
  (try
    (let [s (sqlite/get-row root :session session-id)]
      (when (:session/id s)
        (or (:session (view root session-id))
            (:session (event-view root session-id)))))
    (catch Throwable _
      nil)))

(defn list-session-records
  [root]
  (->> (sqlite/all-rows root :session)
       (sort-by :session/id)
       vec))

(defn list-head-records
  [root]
  (->> (sqlite/all-rows root :head)
       (sort-by :head/id)
       vec))

(defn- trailing-long
  [s]
  (when-let [m (re-find #"(\d+)$" (str s))]
    (parse-long (first m))))

(defn- normalize-invocation
  [inv]
  (when (:invocation/id inv)
    (store-value/assoc-some
     {:invocation/id (:invocation/id inv)}
     :invocation/type (:invocation/type inv)
     :invocation/status (:invocation/status inv)
     :invocation/call-id (trailing-long (:invocation/call-uid inv))
     :invocation/label (:invocation/label inv)
     :invocation/input-ref (:invocation/input-ref inv)
     :invocation/final-ref (:invocation/final-ref inv)
     :invocation/started-at (:invocation/created-at inv)
     :invocation/ended-at (:invocation/completed-at inv)
     :caller/session (get-in inv [:invocation/caller-session :session/id])
     :caller/head-before (get-in inv [:invocation/caller-head-before :head/id])
     :caller/head-after (get-in inv [:invocation/caller-head-after :head/id])
     :callee/session (get-in inv [:invocation/callee-session :session/id])
     :callee/head-before (get-in inv [:invocation/callee-head-before :head/id])
     :callee/head-after (get-in inv [:invocation/callee-head-after :head/id])
     :attach/source-session-id (get-in inv [:attach/source-session :session/id])
     :attach/source-head-id (get-in inv [:attach/source-head :head/id])
     :attach/source-snapshot-ref (:attach/source-snapshot-ref inv)
     :attach/source-fingerprint (:attach/source-fingerprint inv))))

(defn list-invocation-records
  [root]
  (->> (sqlite/all-rows root :invocation)
       (sort-by :invocation/id)
       vec))

(defn- normalize-derivation
  [d]
  (when (:derivation/id d)
    {:derivation/id (:derivation/id d)
     :derivation/version (:derivation/version d)
     :derivation/type (:derivation/type d)
     :derivation/source-session (get-in d [:derivation/source-session :session/id])
     :derivation/source-head (get-in d [:derivation/source-head :head/id])
     :derivation/target-session (get-in d [:derivation/target-session :session/id])
     :derivation/created-at (:derivation/created-at d)}))

(defn list-derivation-records
  [root]
  (->> (sqlite/all-rows root :derivation)
       (sort-by :derivation/id)
       vec))

(defn list-alias-records
  [root]
  (->> (sqlite/all-rows root :alias)
       (sort-by :alias/name)
       vec))

(defn read-alias
  [root alias]
  (first (filter #(= (str alias) (:alias/name %)) (list-alias-records root))))

(defn resolve-session-id
  [root token]
  (let [token (some-> token str)]
    (or (when (and token (read-session root token)) token)
        (:alias/session (read-alias root token)))))

(defn write-alias!
  ([root alias session-id] (write-alias! root alias session-id {}))
  ([root alias session-id _extra]
   (let [root (store-path root)
         alias (str alias)
         existing (read-alias root alias)]
     (when (and existing (not= session-id (:alias/session existing)))
       (throw (ex-info "Alias already points to another session"
                       {:error/type :session-db/alias-conflict
                        :alias/name alias
                        :alias/session (:alias/session existing)
                        :alias/requested-session session-id})))
     (when-not (read-session root session-id)
       (throw (ex-info "Alias target session does not exist"
                       {:error/type :session-db/alias-target-missing
                        :alias/name alias
                        :alias/session session-id})))
     (let [row {:alias/name alias
                :alias/session session-id
                :alias/authority :canonical
                :alias/created-at (or (:alias/created-at existing) (time/now-str))
                :alias/updated-at (time/now-str)}]
       (transact-rows-and-facts! root
                                 [(canonical-row :alias alias row session-id nil)]
                                 []
                                 [(alias-entity alias session-id)])
       row))))

(defn register-session!
  [root session]
  (let [root (store-path root)
        sid (session-model/logical-session-id session)]
    (transact-rows-and-facts! root
                              (cond-> [(canonical-row :session sid session sid nil)]
                                (:session/alias session)
                                (conj (canonical-row :alias (str (:session/alias session))
                                                     {:alias/name (str (:session/alias session))
                                                      :alias/session sid
                                                      :alias/authority :canonical
                                                      :alias/updated-at (time/now-str)}
                                                     sid nil)))
                              []
                              [(session-entity session)
                               (alias-entity (:session/alias session) sid)])
    session))

(defn write-head!
  [root head]
  (transact-rows-and-facts! root
                            [(canonical-row :head (:head/id head) head
                                            (:head/session head) (:head/n head))]
                            (head-blob-refs root head)
                            [(head-entity head)])
  head)

(defn advance-current-head!
  [root head expected-head]
  (let [root (store-path root)
        sid (:head/session head)
        current (:ref/current-head (read-ref root sid))]
    (when (not= expected-head current)
      (throw (ex-info "Current head ref conflict"
                      {:error/type :session-db/ref-conflict
                       :ref/session sid
                       :ref/expected-head expected-head
                       :ref/current-head current
                       :ref/next-head (:head/id head)})))
    (transact-rows-and-facts! root
                              [(canonical-row :head (:head/id head) head sid (:head/n head))
                               (canonical-row :ref sid
                                              {:ref/version store-version
                                               :ref/authority :canonical
                                               :ref/session sid
                                               :ref/current-head (:head/id head)
                                               :ref/previous-head expected-head
                                               :ref/head-fingerprint (:head/fingerprint head)
                                               :ref/updated-at (time/now-str)}
                                              sid nil)]
                              (head-blob-refs root head)
                              [(head-entity head)
                               (session-current-head-entity sid (:head/id head))])
    {:ref/version store-version
     :ref/authority :canonical
     :ref/session sid
     :ref/current-head (:head/id head)
     :ref/previous-head expected-head
     :ref/head-fingerprint (:head/fingerprint head)
     :ref/updated-at (time/now-str)}))

(defn write-invocation!
  [root invocation]
  (let [sid (:caller/session invocation)
        row (assoc invocation
                   :invocation/authority :canonical
                   :invocation/store-version store-version)]
    (transact-rows-and-facts! root
                              [(canonical-row :invocation (:invocation/id row) row
                                              sid (:invocation/n row))]
                              (filter store-payload/blob-ref? [(:invocation/input-ref row)
                                                 (:invocation/final-ref row)
                                                 (:attach/source-snapshot-ref row)])
                              [(invocation-entity sid row)])
    row))

(defn handle-session-id
  [handle]
  (cond
    (map? handle)
    (locator-session-id handle)

    (string? handle)
    (let [s (str handle)]
      (when-not (or (str/includes? s "/")
                    (str/includes? s "\\"))
        (first (str/split (str/replace-first s #"^session:" "") #"#"))))

    :else nil))

(defn handle-head-id
  [handle opts]
  (or (:head opts)
      (when (map? handle)
        (or (:head/id handle) (:session/head handle) (:ref/current-head handle)))
      (when (and (string? handle) (.contains (str handle) "#"))
        (second (str/split (str/replace-first (str handle) #"^session:" "") #"#")))))

(defn resolve-handle
  [root handle opts]
  (when-let [token (handle-session-id handle)]
    (let [root (store-path root)
          session-id (resolve-session-id root token)
          requested-head (handle-head-id handle opts)
          ref (when session-id (read-ref root session-id))
          head-id (or requested-head (:ref/current-head ref))
          head (when (and session-id head-id) (read-head root session-id head-id))
          session (when session-id (read-session root session-id))]
      (when session
        (assoc (locator root session-id head-id)
               :head-id head-id
         :head head
         :ref ref
         :session-id session-id
         :cache-id (:session/cache-id session)
               :store-root root
               :source (if (= token session-id) :session-id :alias))))))

(defn outgoing-invocations
  [root session-id]
  (->> (list-invocation-records root)
       (keep (fn [inv]
               (when (= session-id (:caller/session inv))
                 [(:invocation/id inv)
                  (:callee/session inv)
                  (:callee/head-after inv)])))
       set))

(defn incoming-invocations
  [root session-id]
  (->> (list-invocation-records root)
       (keep (fn [inv]
               (when (= session-id (:callee/session inv))
                 [(:invocation/id inv)
                  (:caller/session inv)
                  (:caller/head-after inv)])))
       set))

(defn attached-from
  [root source-session-id]
  (->> (list-invocation-records root)
       (keep (fn [inv]
               (when (= source-session-id (:attach/source-session-id inv))
                 [(:invocation/id inv)
                  (:callee/session inv)
                  (:attach/source-head-id inv)])))
       set))
