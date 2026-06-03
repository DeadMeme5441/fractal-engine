(ns fractal-engine.session-sqlite
  "SQLite hot store for canonical operational session facts.

  BlobStore remains the byte authority. This namespace stores the small
  operational rows, append-only transaction batches for the rebuildable Datahike
  projection, and current-head refs under one local SQLite database."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [fractal-engine.time :as time])
  (:import [java.io PushbackReader StringReader]
           [java.nio.file Files Path]
           [java.sql Connection DriverManager PreparedStatement ResultSet]
           [java.util.concurrent ConcurrentHashMap]))

(defonce connections (ConcurrentHashMap.))
(defonce tx-locks (ConcurrentHashMap.))

(def ^:private busy-retry-delays-ms [25 50 100 200 400 800 1200 1600])

(defn path
  [dir & parts]
  (let [base (cond
               (instance? Path dir) dir
               (instance? java.io.File dir) (.toPath ^java.io.File dir)
               :else (.toPath (io/file dir)))]
    (reduce (fn [^Path p part] (.resolve p (str part))) base parts)))

(defn db-path [root] (path root "store.sqlite"))

(defn formatted-edn
  [value]
  (binding [*print-dup* false
            *print-readably* true]
    (with-out-str
      (pp/write value :stream *out* :pretty true)
      (newline))))

(defn read-edn
  [s]
  (when s
    (with-open [r (PushbackReader. (StringReader. s))]
      (edn/read {:eof nil} r))))

(defn root-key [root]
  (str (-> (path root) .toAbsolutePath .normalize)))

(defn tx-lock
  [root]
  (.computeIfAbsent tx-locks (root-key root)
                    (reify java.util.function.Function
                      (apply [_ _] (Object.)))))

(defn- sqlite-busy? [^Throwable t]
  (boolean
   (some-> (or (.getMessage t) (some-> (.getCause t) .getMessage))
           (re-find #"SQLITE_BUSY|SQLITE_BUSY_SNAPSHOT|database is locked"))))

(defn- with-busy-retry [f]
  (letfn [(attempt [delays]
            (try
              (f)
              (catch Throwable t
                (if (and (seq delays) (sqlite-busy? t))
                  (do
                    (Thread/sleep (long (first delays)))
                    (attempt (rest delays)))
                  (throw t)))))]
    (attempt busy-retry-delays-ms)))

(defn- execute!
  [^Connection conn sql params]
  (with-busy-retry
    (fn []
      (with-open [stmt (.prepareStatement conn sql)]
        (doseq [[idx value] (map-indexed vector params)]
          (.setObject stmt (inc idx) value))
        (.execute stmt)))))

(defn- query!
  [^Connection conn sql params row-fn]
  (with-busy-retry
    (fn []
      (with-open [stmt (.prepareStatement conn sql)]
        (doseq [[idx value] (map-indexed vector params)]
          (.setObject stmt (inc idx) value))
        (with-open [rs (.executeQuery stmt)]
          (loop [rows []]
            (if (.next rs)
              (recur (conj rows (row-fn rs)))
              rows)))))))

(defn init!
  [^Connection conn]
  (doseq [sql ["PRAGMA journal_mode=WAL"
               "PRAGMA synchronous=NORMAL"
               "PRAGMA foreign_keys=ON"
               "PRAGMA busy_timeout=5000"]]
    (execute! conn sql []))
  (doseq [sql ["CREATE TABLE IF NOT EXISTS rows (
                  kind TEXT NOT NULL,
                  id TEXT NOT NULL,
                  session_id TEXT,
                  sort_id INTEGER,
                  value_edn TEXT NOT NULL,
                  updated_at TEXT NOT NULL,
                  PRIMARY KEY (kind, id)
                )"
               "CREATE INDEX IF NOT EXISTS rows_kind_session_sort
                  ON rows(kind, session_id, sort_id)"
               "CREATE TABLE IF NOT EXISTS txs (
                  tx_id INTEGER PRIMARY KEY AUTOINCREMENT,
                  created_at TEXT NOT NULL,
                  facts_edn TEXT NOT NULL
                )"
               "CREATE TABLE IF NOT EXISTS meta (
                  key TEXT PRIMARY KEY,
                  value_edn TEXT NOT NULL
                )"]]
    (execute! conn sql []))
  conn)

(defn connect!
  [root]
  (let [root (path root)
        k (root-key root)]
    (.computeIfAbsent connections k
                      (reify java.util.function.Function
                        (apply [_ _]
                          (Files/createDirectories root
                                                   (make-array java.nio.file.attribute.FileAttribute 0))
                          (let [conn (DriverManager/getConnection
                                      (str "jdbc:sqlite:" (db-path root)))]
                            (init! conn)))))))

(defn close!
  ([] (doseq [conn (.values connections)]
        (try (.close ^Connection conn) (catch Throwable _ nil)))
      (.clear connections))
  ([root]
   (when-let [conn (.remove connections (root-key root))]
     (try (.close ^Connection conn) (catch Throwable _ nil)))))

(defn with-tx*
  [root f]
  (with-busy-retry
    (fn []
      (locking (tx-lock root)
        (let [conn (connect! root)
              old-auto (.getAutoCommit conn)]
          (try
            (.setAutoCommit conn false)
            (let [ret (f conn)]
              (.commit conn)
              ret)
            (catch Throwable t
              (.rollback conn)
              (throw t))
            (finally
              (.setAutoCommit conn old-auto))))))))

(defmacro with-tx
  [[conn root] & body]
  `(with-tx* ~root (fn [~conn] ~@body)))

(defn put-row!
  ([root kind id value] (put-row! root kind id value {}))
  ([root kind id value {:keys [session-id sort-id]}]
   (with-tx [conn root]
     (execute! conn
               "INSERT INTO rows(kind,id,session_id,sort_id,value_edn,updated_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(kind,id) DO UPDATE SET
                  session_id=excluded.session_id,
                  sort_id=excluded.sort_id,
                  value_edn=excluded.value_edn,
                  updated_at=excluded.updated_at"
               [(name kind) (str id) (some-> session-id str) sort-id
                (formatted-edn value) (time/now-str)]))
   value))

(defn put-rows!
  [root rows]
  (with-tx [conn root]
    (doseq [{:keys [kind id value session-id sort-id]} rows]
      (execute! conn
                "INSERT INTO rows(kind,id,session_id,sort_id,value_edn,updated_at)
                 VALUES(?,?,?,?,?,?)
                 ON CONFLICT(kind,id) DO UPDATE SET
                   session_id=excluded.session_id,
                   sort_id=excluded.sort_id,
                   value_edn=excluded.value_edn,
                   updated_at=excluded.updated_at"
                [(name kind) (str id) (some-> session-id str) sort-id
                 (formatted-edn value) (time/now-str)])))
  rows)

(defn commit!
  "Atomically upsert typed rows and append one projection transaction batch."
  [root rows facts]
  (with-tx [conn root]
    (doseq [{:keys [kind id value session-id sort-id]} rows]
      (execute! conn
                "INSERT INTO rows(kind,id,session_id,sort_id,value_edn,updated_at)
                 VALUES(?,?,?,?,?,?)
                 ON CONFLICT(kind,id) DO UPDATE SET
                   session_id=excluded.session_id,
                   sort_id=excluded.sort_id,
                   value_edn=excluded.value_edn,
                   updated_at=excluded.updated_at"
                [(name kind) (str id) (some-> session-id str) sort-id
                 (formatted-edn value) (time/now-str)]))
    (when (seq facts)
      (execute! conn
                "INSERT INTO txs(created_at,facts_edn) VALUES(?,?)"
                [(time/now-str) (formatted-edn (vec facts))])))
  {:rows (count rows) :facts (count facts)})

(defn get-row
  [root kind id]
  (first
   (query! (connect! root)
           "SELECT value_edn FROM rows WHERE kind=? AND id=?"
           [(name kind) (str id)]
           (fn [^ResultSet rs] (read-edn (.getString rs "value_edn"))))))

(defn all-rows
  [root kind]
  (query! (connect! root)
          "SELECT value_edn FROM rows WHERE kind=? ORDER BY session_id, sort_id, id"
          [(name kind)]
          (fn [^ResultSet rs] (read-edn (.getString rs "value_edn")))))

(defn rows-for-session
  [root kind session-id]
  (query! (connect! root)
          "SELECT value_edn FROM rows WHERE kind=? AND session_id=? ORDER BY sort_id, id"
          [(name kind) (str session-id)]
          (fn [^ResultSet rs] (read-edn (.getString rs "value_edn")))))

(defn put-tx!
  [root facts]
  (when (seq facts)
    (with-tx [conn root]
      (execute! conn
                "INSERT INTO txs(created_at,facts_edn) VALUES(?,?)"
                [(time/now-str) (formatted-edn (vec facts))]))))

(defn tx-count
  [root]
  (or (ffirst
       (query! (connect! root)
               "SELECT COALESCE(MAX(tx_id), 0) AS max_tx FROM txs"
               []
               (fn [^ResultSet rs] [(.getLong rs "max_tx")])))
      0))

(defn txs-after
  [root tx-id]
  (query! (connect! root)
          "SELECT tx_id, facts_edn FROM txs WHERE tx_id > ? ORDER BY tx_id"
          [(long (or tx-id 0))]
          (fn [^ResultSet rs]
            {:tx/id (.getLong rs "tx_id")
             :tx/facts (read-edn (.getString rs "facts_edn"))})))

(defn get-meta
  [root k]
  (first
   (query! (connect! root)
           "SELECT value_edn FROM meta WHERE key=?"
           [(name k)]
           (fn [^ResultSet rs] (read-edn (.getString rs "value_edn"))))))

(defn put-meta!
  [root k value]
  (with-tx [conn root]
    (execute! conn
              "INSERT INTO meta(key,value_edn) VALUES(?,?)
               ON CONFLICT(key) DO UPDATE SET value_edn=excluded.value_edn"
              [(name k) (formatted-edn value)]))
  value)
