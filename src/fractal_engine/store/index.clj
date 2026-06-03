(ns fractal-engine.store.index
  "Derived Datalog index over canonical SQLite transaction batches.

  Runtime correctness does not depend on this namespace. It is the query index:
  useful, rebuildable, and intentionally downstream of SQLite rows plus blob
  identities."
  (:require [datahike.api :as d]
            [fractal-engine.session-sqlite :as sqlite]
            [fractal-engine.store.io :as store-io])
  (:import [java.nio.file Files]
           [java.util.concurrent ConcurrentHashMap]))

(defonce ^ConcurrentHashMap tx-locks (ConcurrentHashMap.))
(defonce ^ConcurrentHashMap connections (ConcurrentHashMap.))

(defn db-id-file [root] (store-io/path root "datahike-id.edn"))

(defn db-id!
  [root]
  (let [file (db-id-file root)]
    (or (store-io/read-edn-file file nil)
        (store-io/write-edn! file (java.util.UUID/randomUUID)))))

(defn db-config
  [root schema]
  {:store {:backend :file
           :path (str (store-io/path root "datahike"))
           :id (db-id! root)}
   :schema-flexibility :write
   :keep-history? false
   :initial-tx schema})

(defn ensure-db!
  [root schema]
  (let [cfg (db-config root schema)]
    (try
      (d/create-database cfg)
      (catch Throwable _ nil))
    cfg))

(defn tx-lock
  [root]
  (let [k (str (-> (store-io/path root) .toAbsolutePath .normalize))]
    (.computeIfAbsent tx-locks k (reify java.util.function.Function
                                   (apply [_ _] (Object.))))))

(defn- root-key
  [root]
  (str (-> (store-io/path root) .toAbsolutePath .normalize)))

(defn connect!
  [root schema]
  (let [root (store-io/path root)
        k (root-key root)]
    (.computeIfAbsent connections k
                      (reify java.util.function.Function
                        (apply [_ _]
                          (d/connect (ensure-db! root schema)))))))

(defn release!
  [_conn]
  nil)

(defn close-connection!
  [root]
  (when-let [conn (.remove connections (root-key (store-io/path root)))]
    (try
      (d/release conn)
      (catch Throwable _ nil))))

(defn close-connections!
  []
  (doseq [conn (.values connections)]
    (try
      (d/release conn)
      (catch Throwable _ nil)))
  (.clear connections))

(defn datahike-transact!
  [root schema tx-data]
  (when (seq tx-data)
    (locking (tx-lock root)
      (let [conn (connect! root schema)]
        (try
          (d/transact conn {:tx-data (vec tx-data)})
          (finally
            (release! conn)))))))

(defn datahike-index-applied-tx
  [root]
  (long (or (sqlite/get-meta root :datahike-applied-tx) 0)))

(defn mark-datahike-index-applied!
  [root tx-id]
  (sqlite/put-meta! root :datahike-applied-tx (long tx-id)))

(defn ensure-current!
  "Apply pending SQLite transaction batches to the rebuildable Datahike index."
  [root schema]
  (let [root (store-io/path root)
        index-dir (store-io/path root "datahike")
        latest (sqlite/tx-count root)
        applied (if (Files/isDirectory index-dir (make-array java.nio.file.LinkOption 0))
                  (datahike-index-applied-tx root)
                  (do
                    (close-connection! root)
                    (mark-datahike-index-applied! root 0)
                    0))]
    (when (< applied latest)
      (doseq [batch (partition-all 64 (sqlite/txs-after root applied))]
        (let [tx-data (mapcat :tx/facts batch)
              max-tx (reduce max applied (map :tx/id batch))]
          (datahike-transact! root schema tx-data)
          (mark-datahike-index-applied! root max-tx))))
    root))

(defn rebuild!
  "Rebuild the derived Datahike index from canonical SQLite transaction batches."
  [root schema]
  (let [root (store-io/path root)
        cfg (db-config root schema)]
    (close-connection! root)
    (try
      (d/delete-database cfg)
      (catch Throwable _ nil))
    (d/create-database cfg)
    (mark-datahike-index-applied! root 0)
    (ensure-current! root schema)))

(defn transact!
  [root schema tx-data]
  (datahike-transact! root schema tx-data))

(defn q
  [root schema query & args]
  (ensure-current! root schema)
  (let [conn (connect! root schema)]
    (try
      (apply d/q query @conn args)
      (finally
        (release! conn)))))

(defn pull
  [root schema pattern lookup-ref]
  (ensure-current! root schema)
  (let [conn (connect! root schema)]
    (try
      (d/pull @conn pattern lookup-ref)
      (finally
        (release! conn)))))
