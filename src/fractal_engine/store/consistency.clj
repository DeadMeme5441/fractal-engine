(ns fractal-engine.store.consistency
  "Consistency checking for the canonical session store.

  This is a read/integrity surface over SQLite rows, BlobStore bytes, and the
  derived Datahike index. It does not participate in runtime writes."
  (:require [fractal-engine.blob-store :as blob]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.session-sqlite :as sqlite]
            [fractal-engine.store.index :as store-index]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.store.schema :as store-schema]
            [fractal-engine.time :as time]))

(defn- blob-store [root]
  (blob/file-store root))

(defn- blob-ref? [x]
  (and (map? x) (:blob/id x) (:blob/sha256 x) (:blob/key x)))

(defn- compact-state-blob-refs
  [root state-ref]
  (when (blob-ref? state-ref)
    (let [state (blob/read-edn (blob-store root) state-ref)]
      (when (= 2 (:state/version state))
        (filter blob-ref?
                (concat
                 [(:vars-ref state)]
                 (map :delta-ref (vals (:state/collections state)))))))))

(defn- index-q
  [root query & args]
  (apply store-index/q root store-schema/schema query args))

(defn- index-pull
  [root pattern lookup-ref]
  (store-index/pull root store-schema/schema pattern lookup-ref))

(defn- issue [type data]
  (merge {:issue/type type :issue/severity :error} data))

(defn- all-blob-refs
  [root]
  (sqlite/all-rows root :blob))

(defn- missing-ref-issues
  [root]
  (let [session-current (index-q root
                           '[:find ?sid ?h
                             :where
                             [?s :session/id ?sid]
                             [?s :session/current-head ?h]])
        head-sessions (index-q root
                         '[:find ?hid ?s
                           :where
                           [?h :head/id ?hid]
                           [?h :head/session ?s]])
        head-bases (index-q root
	                  '[:find ?hid ?b
	                    :where
	                    [?h :head/id ?hid]
	                    [?h :head/basis ?b]])
        entity-pull (fn [eid pattern] (index-pull root pattern eid))]
    (vec
     (concat
      (keep (fn [[sid h]]
              (let [head (entity-pull h '[:head/id])]
                (when-not (:head/id head)
                  (issue :session-db/current-head-missing {:session/id sid :db/id h}))))
            session-current)
      (keep (fn [[hid s]]
              (let [session (entity-pull s '[:session/id])]
                (when-not (:session/id session)
                  (issue :session-db/head-session-missing {:head/id hid :db/id s}))))
            head-sessions)
	      (keep (fn [[hid b]]
	              (let [basis (entity-pull b '[:head/id])]
	                (when-not (:head/id basis)
	                  (issue :session-db/head-basis-missing {:head/id hid :db/id b}))))
	            head-bases)))))

(defn- blob-issues
  [root]
  (let [store (blob-store root)]
    (->> (all-blob-refs root)
         (keep (fn [ref]
                 (let [v (blob/verify store ref)]
                   (when-not (= :ok (:status v))
                     (issue (:issue/type v)
                            (select-keys v [:blob/id :blob/key :expected :actual]))))))
         vec)))

(defn- compact-head-state-component-issues
  [root head]
  (let [store (blob-store root)]
    (->> (compact-state-blob-refs root (:head/state-ref head))
         (keep (fn [ref]
                 (let [v (blob/verify store ref)]
                   (when-not (= :ok (:status v))
                     (issue (keyword "session-db" (str "head-state-component-" (name (:issue/type v))))
                            (merge {:head/id (:head/id head)
                                    :blob/id (:blob/id ref)
                                    :blob/key (:blob/key ref)}
                                   (select-keys v [:expected :actual])))))))
         vec)))

(defn- leaf-invocation-issues
  [root]
  (->> (index-q root
          '[:find ?iid ?ctype ?uid
            :where
            [?i :invocation/id ?iid]
            [?i :invocation/call-uid ?uid]
            [?c :call/uid ?uid]
            [?c :call/type ?ctype]])
       (filter (fn [[_ ctype _]] (contains? #{:leaf :leaf-batch-item} ctype)))
       (mapv (fn [[iid ctype uid]]
               (issue :session-db/leaf-call-has-invocation
                      {:invocation/id iid
                       :call/uid uid
                       :call/type ctype})))))

(def final-invocation-types #{:rlm :map-rlm :attach})

(defn- invocation-type-issues
  [root]
  (->> (index-q root
          '[:find ?iid ?type
            :where
            [?i :invocation/id ?iid]
            [?i :invocation/type ?type]])
       (keep (fn [[iid type]]
               (when-not (contains? final-invocation-types type)
                 (issue :session-db/invalid-invocation-type
                        {:invocation/id iid
                         :invocation/type type}))))
       vec))

(defn- direct-head-records
  [root]
  (session-db/list-head-records root))

(defn- snapshot-record-for-head
  [root session-id snapshot-id]
  (when (some? snapshot-id)
    (first (filter #(= (long snapshot-id) (long (:snapshot/id %)))
                   (sqlite/rows-for-session root :snapshot session-id)))))

(defn- turn-final-head?
  [head]
  (or (= :turn-final (:head/kind head))
      (some? (:head/turn-id head))
      (some? (:head/final-ref head))))

(defn- head-snapshot-linkage-issues
  [root]
  (->> (direct-head-records root)
       (mapcat
        (fn [head]
          (let [session-id (:head/session head)
                snapshot-id (:head/snapshot-id head)
                head-snapshot-blob-id (get-in head [:head/snapshot-ref :blob/id])
                snapshot (snapshot-record-for-head root session-id snapshot-id)
                snapshot-blob-id (get-in snapshot [:snapshot/ref :blob/id])]
            (when (turn-final-head? head)
              (concat
               (when-not snapshot-id
                 [(issue :session-db/head-snapshot-id-missing
                         {:head/id (:head/id head)
                          :session/id session-id})])
               (when-not head-snapshot-blob-id
                 [(issue :session-db/head-snapshot-ref-missing
                         {:head/id (:head/id head)
                          :session/id session-id
                          :head/snapshot-id snapshot-id})])
               (when (and snapshot-id (nil? snapshot))
                 [(issue :session-db/head-snapshot-missing
                         {:head/id (:head/id head)
                          :session/id session-id
                          :head/snapshot-id snapshot-id})])
               (when (and head-snapshot-blob-id snapshot-blob-id
                          (not= head-snapshot-blob-id snapshot-blob-id))
                 [(issue :session-db/head-snapshot-ref-mismatch
                         {:head/id (:head/id head)
                          :session/id session-id
                          :head/snapshot-id snapshot-id
                          :head/snapshot-ref head-snapshot-blob-id
                          :snapshot/ref snapshot-blob-id})]))))))
       vec))

(defn- ref-blob-id
  [ref]
  (:blob/id ref))

(defn- head-state-issues
  [root]
  (->> (direct-head-records root)
       (mapcat
        (fn [head]
          (let [head-id (:head/id head)
                session-id (:head/session head)
                state-ref (:head/state-ref head)
                state (when state-ref
                        (session-db/read-head-state root session-id head-id))
                state-snapshot (when (and state (:head/snapshot-id head))
                                 (first (filter #(= (long (:head/snapshot-id head))
                                                    (long (:snapshot/id %)))
                                                (:snapshots state))))]
            (concat
             (compact-head-state-component-issues root head)
             (when-not state-ref
               [(issue :session-db/head-state-ref-missing
                       {:head/id head-id
                        :session/id session-id})])
             (when (and state-ref (nil? state))
               [(issue :session-db/head-state-unreadable
                       {:head/id head-id
                        :session/id session-id
                        :blob/id (:blob/id state-ref)})])
             (when (and state (not= session-id (:state/session-id state)))
               [(issue :session-db/head-state-session-mismatch
                       {:head/id head-id
                        :head/session session-id
                        :state/session-id (:state/session-id state)})])
	             (when (and state (not= (:head/basis head) (:state/basis-head state)))
	               [(issue :session-db/head-state-basis-mismatch
	                       {:head/id head-id
	                        :head/basis (:head/basis head)
	                        :state/basis-head (:state/basis-head state)})])
             (when (and state (:head/final-ref head)
                        (not= (ref-blob-id (:head/final-ref head))
                              (ref-blob-id (:final-ref state))))
               [(issue :session-db/head-state-final-ref-mismatch
                       {:head/id head-id
                        :head/final-ref (ref-blob-id (:head/final-ref head))
                        :state/final-ref (ref-blob-id (:final-ref state))})])
             (when (and state (:head/snapshot-ref head)
                        (not= (ref-blob-id (:head/snapshot-ref head))
                              (ref-blob-id (:snapshot/ref state-snapshot))))
               [(issue :session-db/head-state-snapshot-ref-mismatch
                       {:head/id head-id
                        :head/snapshot-id (:head/snapshot-id head)
                        :head/snapshot-ref (ref-blob-id (:head/snapshot-ref head))
                        :state/snapshot-ref (ref-blob-id (:snapshot/ref state-snapshot))})])))))
       vec))

(defn- current-head-state-issues
  [root]
  (->> (index-q root
          '[:find ?sid ?hid
            :where
            [?s :session/id ?sid]
            [?s :session/current-head ?h]
            [?h :head/id ?hid]])
       (keep (fn [[sid hid]]
               (when-not (session-db/read-head-state root sid hid)
                 (issue :session-db/current-head-state-unreadable
                        {:session/id sid
                         :head/id hid}))))
       vec))

(def ^:private invocation-head-ref-attrs
  [:invocation/caller-head-before
   :invocation/caller-head-after
   :invocation/callee-head-before
   :invocation/callee-head-after
   :attach/source-head])

(defn- invocation-head-ref-issues
  [root]
  (->> invocation-head-ref-attrs
       (mapcat
        (fn [attr]
          (index-q root
             '[:find ?iid ?h
               :in $ ?attr
               :where
               [?i :invocation/id ?iid]
               [?i ?attr ?h]]
             attr)))
       (keep (fn [[iid h]]
               (when-not (:head/id (index-pull root '[:head/id] h))
                 (issue :session-db/invocation-head-missing
                        {:invocation/id iid
                         :db/id h}))))
       vec))

(defn- ids-for-attr
  [root attr]
  (set (map first
            (index-q root
               '[:find ?v
                 :in $ ?attr
                 :where [_ ?attr ?v]]
               attr))))

(defn- entity-exists?
  [root lookup-ref id-key]
  (and (some? (second lookup-ref))
       (try
         (some? (id-key (index-pull root [id-key] lookup-ref)))
         (catch Throwable _ false))))

(defn- session-exists?
  [root session-id]
  (entity-exists? root [:session/id session-id] :session/id))

(defn- head-exists?
  [root head-id]
  (entity-exists? root [:head/id head-id] :head/id))

(defn check-consistency
  ([root] (check-consistency root {:mode :deep}))
  ([root opts]
   (let [root (store-io/path root)
        mode (or (:mode opts) :deep)
        deep? (= :deep mode)
	        sessions (ids-for-attr root :session/id)
	        heads (ids-for-attr root :head/id)
	        invocations (session-db/list-invocation-records root)
	        derivations (session-db/list-derivation-records root)
	        aliases (session-db/list-alias-records root)
        db-aliases (index-q root '[:find ?alias ?s
                             :where [?a :alias/name ?alias]
                                    [?a :alias/session ?s]])
        db-invocations (index-q root '[:find ?iid ?caller ?callee
                                 :where [?i :invocation/id ?iid]
                                        [?i :invocation/caller-session ?caller]
                                        [?i :invocation/callee-session ?callee]])
	        db-attaches (index-q root '[:find ?iid ?source ?head
	                              :where [?i :invocation/id ?iid]
	                                     [?i :attach/source-session ?source]
	                                     [?i :attach/source-head ?head]])
	        db-derivations (index-q root '[:find ?did ?source ?head ?target
	                                  :where [?d :derivation/id ?did]
	                                         [?d :derivation/source-session ?source]
	                                         [?d :derivation/source-head ?head]
	                                         [?d :derivation/target-session ?target]])
        issues (vec (concat
                     (missing-ref-issues root)
                     (leaf-invocation-issues root)
                     (invocation-type-issues root)
                     (head-snapshot-linkage-issues root)
                     (invocation-head-ref-issues root)
                     (when deep? (blob-issues root))
                     (when deep? (head-state-issues root))
                     (when deep? (current-head-state-issues root))
                     (keep (fn [[alias s]]
                             (when-not (:session/id (index-pull root '[:session/id] s))
                               (issue :session-db/alias-target-missing
                                      {:alias/name alias :db/id s})))
                           db-aliases)
                     (mapcat (fn [[iid caller callee]]
                               (concat
                                (when-not (:session/id (index-pull root '[:session/id] caller))
                                  [(issue :session-db/invocation-caller-missing
                                          {:invocation/id iid :db/id caller})])
                                (when-not (:session/id (index-pull root '[:session/id] callee))
                                  [(issue :session-db/invocation-callee-missing
                                          {:invocation/id iid :db/id callee})])))
                             db-invocations)
	                     (mapcat (fn [[iid source head]]
                               (concat
                                (when-not (:session/id (index-pull root '[:session/id] source))
                                  [(issue :session-db/attach-source-session-missing
                                          {:invocation/id iid :db/id source})])
                                (when-not (:head/id (index-pull root '[:head/id] head))
                                  [(issue :session-db/attach-source-head-missing
                                          {:invocation/id iid :db/id head})])))
	                             db-attaches)
	                     (mapcat (fn [[did source head target]]
	                               (concat
	                                (when-not (:session/id (index-pull root '[:session/id] source))
	                                  [(issue :session-db/derivation-source-session-missing
	                                          {:derivation/id did :db/id source})])
	                                (when-not (:head/id (index-pull root '[:head/id] head))
	                                  [(issue :session-db/derivation-source-head-missing
	                                          {:derivation/id did :db/id head})])
	                                (when-not (:session/id (index-pull root '[:session/id] target))
	                                  [(issue :session-db/derivation-target-session-missing
	                                          {:derivation/id did :db/id target})])))
	                             db-derivations)
                     (mapcat (fn [a]
                               (when-not (session-exists? root (:alias/session a))
                                 [(issue :session-db/alias-target-missing a)]))
                             aliases)
                     (mapcat (fn [inv]
                               (concat
                                (when-not (session-exists? root (:caller/session inv))
                                  [(issue :session-db/invocation-caller-missing
                                          {:invocation/id (:invocation/id inv)
                                           :caller/session (:caller/session inv)})])
                                (when-not (session-exists? root (:callee/session inv))
                                  [(issue :session-db/invocation-callee-missing
                                          {:invocation/id (:invocation/id inv)
                                           :callee/session (:callee/session inv)})])
                                (when (and (= :attach (:invocation/type inv))
                                           (not (session-exists? root (:attach/source-session-id inv))))
                                  [(issue :session-db/attach-source-session-missing
                                          {:invocation/id (:invocation/id inv)
                                           :attach/source-session-id (:attach/source-session-id inv)})])
                                (when (and (= :attach (:invocation/type inv))
                                           (not (head-exists? root (:attach/source-head-id inv))))
                                  [(issue :session-db/attach-source-head-missing
                                          {:invocation/id (:invocation/id inv)
                                           :attach/source-head-id (:attach/source-head-id inv)})])))
                             invocations)))]
    {:check/version session-db/store-version
     :check/kind :session-db/consistency
     :check/mode mode
     :storage/root (str root)
     :checked-at (time/now-str)
     :status (if (empty? issues) :ok :issues)
	     :counts {:sessions (count sessions)
	              :heads (count heads)
	              :invocations (count invocations)
	              :derivations (count derivations)
	              :aliases (count aliases)
              :blobs (count (all-blob-refs root))}
     :issue-count (count issues)
     :issues issues})))
