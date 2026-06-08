(ns fractal.engine.store.sqlite
  "L1.5 · SqliteStore — the Phase-2 DURABLE SessionStore (02). Slots under the
   SAME `SessionStore` port as MemoryStore with ZERO loop/kernel/adapter/api
   changes; the only composition-root change is `start-session!` choosing it off
   `cfg :store`. Mirrors MemoryStore's slot machinery (per-session `:view`/`:lock`/
   `:dispatch`/`:sci-ctx`/`:busy`, the `stamp-ids+ts` counters, the `live`
   delegation) and adds the durable layer:

   • The canonical EVENT LOG + a minimal sessions table live in SQLite, PER SESSION,
     keyed by (`session_id`, the monotonic `event_id`) — the future head event-range
     boundary (02 §9). Raw JDBC, no wrapper dep.
   • PAYLOADS live in a GLOBAL, file-based, content-addressed blob store
     (`blobstore`) shared across sessions — no `sid`, content hash is globally
     sufficient (02 §3).

   ⛔ PERSIST-BEFORE-FOLD (02 §8.2): under the per-session slot lock → stamp id+ts →
   durably INSERT the event (committed) → ONLY on success swap! the in-process view
   cache → schedule a NON-BLOCKING live notification. A failed persist throws BEFORE
   the swap, so it never advances the view. `create-session!` reopens a persisted
   session by FOLDING its durable log with the pure `apply-event` (deterministic
   re-fold ⇒ the same ids, 02 §9) — the recovery path resume builds on."
  (:require [clojure.edn :as edn]
            [clojure.instant :as instant]
            [clojure.java.io :as io]
            [fractal.engine.live :as live]
            [fractal.engine.payload :as payload]
            [fractal.engine.store :as store]
            [fractal.engine.store.blobstore :as blob]
            [fractal.engine.time :as time])
  (:import [java.nio.charset StandardCharsets]
           [java.sql Connection DriverManager]))

;; ---------------------------------------------------------------------------
;; Event (de)serialization — canonical EDN, the inverse round-trip
;; ---------------------------------------------------------------------------

(def ^:private edn-readers
  {'inst instant/read-instant-date
   'uuid (fn [s] (java.util.UUID/fromString s))})

(defn- ser ^String [v]
  ;; Reuse the PURE canonical encoder so event bytes are deterministic; payload
  ;; refs inside an event survive as plain maps (payload-ref? still matches).
  (String. (payload/canonical-bytes v) StandardCharsets/UTF_8))

(defn- deser [^String s]
  (edn/read-string {:readers edn-readers} s))

;; ---------------------------------------------------------------------------
;; Raw JDBC (every fn here runs under the store's :db-lock)
;; ---------------------------------------------------------------------------

(defn- exec! [^Connection conn ^String sql]
  (with-open [st (.createStatement conn)] (.execute st sql)))

(defn- init-schema! [^Connection conn]
  (exec! conn "PRAGMA journal_mode=WAL")
  (exec! conn "PRAGMA synchronous=NORMAL")
  (exec! conn "PRAGMA busy_timeout=5000")
  (exec! conn (str "CREATE TABLE IF NOT EXISTS sessions ("
                   "session_id TEXT PRIMARY KEY, created_at TEXT NOT NULL)"))
  (exec! conn (str "CREATE TABLE IF NOT EXISTS events ("
                   "session_id TEXT NOT NULL, event_id INTEGER NOT NULL, "
                   "event_type TEXT NOT NULL, event_at TEXT NOT NULL, "
                   "event_edn TEXT NOT NULL, "
                   "PRIMARY KEY (session_id, event_id))")))

(def ^:private insert-event-sql
  "INSERT INTO events(session_id,event_id,event_type,event_at,event_edn) VALUES (?,?,?,?,?)")

(defn- bind-event! [ps sid ev]
  (doto ps
    (.setString 1 sid)
    (.setLong   2 (long (:event/id ev)))
    (.setString 3 (str (:event/type ev)))
    (.setString 4 (str (:event/at ev)))
    (.setString 5 (ser ev))))

(defn- insert-event!* [^Connection conn sid ev]
  ;; autocommit (the default) ⇒ a successful executeUpdate is durably COMMITTED
  ;; before we return; a constraint/IO failure throws here, before any fold.
  (with-open [ps (.prepareStatement conn insert-event-sql)]
    (bind-event! ps sid ev)
    (.executeUpdate ps)))

(defn- insert-events!* [^Connection conn sid evs]
  ;; ONE atomic transaction for a batch commit (append-events!).
  (let [auto (.getAutoCommit conn)]
    (.setAutoCommit conn false)
    (try
      (with-open [ps (.prepareStatement conn insert-event-sql)]
        (doseq [ev evs] (bind-event! ps sid ev) (.addBatch ps))
        (.executeBatch ps))
      (.commit conn)
      (catch Throwable t (.rollback conn) (throw t))
      (finally (.setAutoCommit conn auto)))))

(defn- load-events* [^Connection conn sid]
  (with-open [ps (.prepareStatement conn
                   "SELECT event_edn FROM events WHERE session_id=? ORDER BY event_id ASC")]
    (.setString ps 1 sid)
    (with-open [rs (.executeQuery ps)]
      (loop [acc (transient [])]
        (if (.next rs)
          (recur (conj! acc (deser (.getString rs 1))))
          (persistent! acc))))))

(defn- insert-session-row!* [^Connection conn sid]
  (with-open [ps (.prepareStatement conn
                   "INSERT OR IGNORE INTO sessions(session_id,created_at) VALUES (?,?)")]
    (.setString ps 1 sid)
    (.setString ps 2 (time/now-str))
    (.executeUpdate ps)))

;; ---------------------------------------------------------------------------
;; Slots + handles + id/ts stamping (mirror store.memory — 02 §7)
;; ---------------------------------------------------------------------------

(defn- new-slot [sid live-opts view-value]
  {:view     (atom view-value)         ; the write-synchronous in-process cache (02 §5)
   :lock     (Object.)                 ; the per-session STORE LOCK (serializes appends)
   :dispatch (live/make-dispatch sid live-opts)
   :sci-ctx  (atom nil)                ; set by start-session!/resume-session! after new-ctx
   :busy     (atom false)})            ; the turn-lock (07)

(defn- handle-for [store sid slot]
  {:store      store
   :session-id sid
   :sci-ctx    (:sci-ctx slot)
   :busy       (:busy slot)})

(defn- stamp-ids+ts [view ev]
  (let [{:keys [event message turn step eval]} (:counters view)
        ev (assoc ev :event/id (inc event) :event/at (time/now-str))]
    (case (:event/type ev)
      :turn/started      (assoc-in ev [:turn :turn/id]       (inc turn))
      :step/started      (assoc-in ev [:step :step/id]       (inc step))
      :message/appended  (assoc-in ev [:message :message/id] (inc message))
      :eval/added        (assoc-in ev [:eval :eval/id]       (inc eval))
      :session/compacted (assoc-in ev [:message :message/id] (inc message))
      ev)))

(defn- stamp-batch
  "Stamp a batch off a TEMP fold so the events get consecutive ids WITHOUT
   advancing the real view cache (persist-before-fold for the whole batch)."
  [view0 evs]
  (loop [view view0 evs (seq evs) acc []]
    (if-let [ev (first evs)]
      (let [s (stamp-ids+ts view ev)]
        (recur (store/apply-event view s) (next evs) (conj acc s)))
      acc)))

;; ---------------------------------------------------------------------------
;; The store
;; ---------------------------------------------------------------------------

(defrecord SqliteStore [conn db-lock sessions blobs live-opts create-lock]
  store/SessionStore

  (create-session! [this session-map]
    (let [sid (:session/id session-map)]
      (locking create-lock                                   ; idempotent + atomic (02 §8.7)
        (if-let [slot (get @sessions sid)]
          (handle-for this sid slot)                         ; in-process re-create ⇒ existing slot
          (let [events (locking db-lock (load-events* conn sid))]
            (if (seq events)
              ;; REOPEN (recovery): fold the durable log into a fresh in-process
              ;; view cache — deterministic re-fold reproduces the same ids (02 §9).
              (let [slot (new-slot sid live-opts
                                   (reduce store/apply-event (store/empty-view) events))]
                (swap! sessions assoc sid slot)
                (handle-for this sid slot))
              ;; FRESH: record the session row + an empty-view slot.
              (do
                (locking db-lock (insert-session-row!* conn sid))
                (let [slot (new-slot sid live-opts (store/empty-view))]
                  (swap! sessions assoc sid slot)
                  (handle-for this sid slot)))))))))

  (append-event! [_ sid ev]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (let [stamped (stamp-ids+ts @view ev)]
          (locking db-lock (insert-event!* conn sid stamped))  ; ⛔ PERSIST first (committed)
          (swap! view store/apply-event stamped)               ; …fold ONLY on success
          (live/schedule-notify dispatch stamped)              ; non-blocking; delivered off-lock
          stamped))))

  (append-events! [_ sid evs]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (let [stamped (stamp-batch @view evs)]
          (locking db-lock (insert-events!* conn sid stamped)) ; ⛔ ONE durable txn, atomic
          (swap! view (fn [v] (reduce store/apply-event v stamped)))
          (doseq [s stamped] (live/schedule-notify dispatch s))
          stamped))))

  (publish-head! [_ sid head]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (let [head*   (store/prepare-head @view sid head)
              stamped (stamp-ids+ts @view {:event/type :head/published :head head*})]
          (locking db-lock (insert-event!* conn sid stamped)) ; ⛔ PERSIST first
          (swap! view store/apply-event stamped)
          (live/schedule-notify dispatch stamped)
          head*))))

  (append-lineage-edge! [this sid edge]
    (store/append-event! this sid
                         {:event/type :lineage/edge-added
                          :edge (store/edge-with-id edge)}))

  (intern-payload! [_ value opts]
    (blob/put! blobs value opts))                  ; GLOBAL, content-addressed, idempotent

  (read-payload* [_ ref]
    (blob/get* blobs ref))                          ; global content lookup, no sid

  (current-view [_ sid]
    @(:view (get @sessions sid)))                   ; STRONG read-your-writes (the cache atom)

  (read-state [_ sid]
    @(:view (get @sessions sid)))                   ; RELAXED contract (the cache satisfies it)

  (peek-next-id [_ sid k]
    (inc (get-in @(:view (get @sessions sid)) [:counters k])))

  (notify-transient [_ sid item]
    (live/notify-transient (:dispatch (get @sessions sid)) item))

  (subscribe! [_ sid callback]
    (live/subscribe (:dispatch (get @sessions sid)) callback))

  (events-since [_ sid event-id]
    (->> (:events @(:view (get @sessions sid)))     ; served from the in-process cache (09)
         (filterv (fn [ev] (> (:event/id ev) event-id))))))

;; ---------------------------------------------------------------------------
;; Construction / teardown
;; ---------------------------------------------------------------------------

(defn sqlite-store
  "Open (creating if absent) a SqliteStore under `dir`: `<dir>/events.db` holds the
   per-session event log + sessions table; `<dir>/blobs/` is the GLOBAL blob store.
   `:live-opts` (`{:bound :drop}`) seed each session's live dispatch. Multiple stores
   may open the same `dir` (e.g. a process restart reopening for resume)."
  [{:keys [dir live-opts]}]
  (let [root (io/file dir)]
    (.mkdirs root)
    (let [conn (DriverManager/getConnection
                 (str "jdbc:sqlite:" (.getAbsolutePath (io/file root "events.db"))))]
      ;; If schema init / blob-store open fails the Connection is not yet on the
      ;; record, so close! can never reach it — close it here or it leaks.
      (try
        (init-schema! conn)
        (->SqliteStore conn (Object.) (atom {})
                       (blob/open (io/file root "blobs"))
                       (or live-opts {}) (Object.))
        (catch Throwable e
          (try (.close conn) (catch Throwable _ nil))
          (throw e))))))

(defn close!
  "Release the store: stop every session's live dispatcher (idempotent; daemon
   threads are JVM-exit-safe regardless) and close the JDBC connection. After this
   a session can be durably reopened by constructing a fresh store on the same
   `dir` (the recovery path) — this is what makes close+reopen behave like a
   process restart in tests and resume."
  [store]
  (doseq [[_ slot] @(:sessions store)]
    (live/stop-dispatch (:dispatch slot)))
  (locking (:db-lock store)
    (try (exec! (:conn store) "PRAGMA wal_checkpoint(TRUNCATE)") (catch Throwable _ nil))
    (.close ^Connection (:conn store)))
  nil)
