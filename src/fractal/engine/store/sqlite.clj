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
            [fractal.engine.payload-io :as payload-io]
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
                   "PRIMARY KEY (session_id, event_id))"))
  ;; bj1 · advisory writer leases: one writer per scope (a session id, or
  ;; store/store-lease-scope for facts/pins). heartbeat_ms is epoch millis.
  (exec! conn (str "CREATE TABLE IF NOT EXISTS leases ("
                   "scope TEXT PRIMARY KEY, owner TEXT NOT NULL, "
                   "acquired_at TEXT NOT NULL, heartbeat_ms INTEGER NOT NULL)"))
  ;; 88j · the store-scoped embedder fact stream + named pins. Opaque to the
  ;; engine: ordered, persisted, served — never interpreted.
  (exec! conn (str "CREATE TABLE IF NOT EXISTS facts ("
                   "fact_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                   "fact_tag TEXT NOT NULL, fact_at TEXT NOT NULL, "
                   "fact_edn TEXT NOT NULL)"))
  ;; ONE authority per pin: the edn (version/at live inside it; no shadow columns).
  (exec! conn (str "CREATE TABLE IF NOT EXISTS pins ("
                   "pin_name TEXT PRIMARY KEY, pin_edn TEXT NOT NULL)"))
  ;; qbu · head-id → view-snapshot blob ref. An INDEX, deliberately outside the
  ;; head content (head ids stay impl-agnostic semantic identities); orphan
  ;; rows are harmless, reopen falls back to the full fold when absent.
  (exec! conn (str "CREATE TABLE IF NOT EXISTS snapshots ("
                   "head_id TEXT PRIMARY KEY, ref_edn TEXT NOT NULL)")))

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

(defn- load-events-since* [^Connection conn sid event-id]
  (with-open [ps (.prepareStatement conn
                   "SELECT event_edn FROM events WHERE session_id=? AND event_id>? ORDER BY event_id ASC")]
    (.setString ps 1 sid)
    (.setLong ps 2 (long event-id))
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
;; qbu · view snapshots at heads (bounded reopen)
;; ---------------------------------------------------------------------------

(defn- put-snapshot-row!* [^Connection conn head-id ref]
  (with-open [ps (.prepareStatement conn
                   (str "INSERT INTO snapshots(head_id,ref_edn) VALUES (?,?) "
                        "ON CONFLICT(head_id) DO UPDATE SET ref_edn=excluded.ref_edn"))]
    (.setString ps 1 head-id)
    (.setString ps 2 (ser ref))
    (.executeUpdate ps)))

(defn- snapshot-ref* [^Connection conn head-id]
  (with-open [ps (.prepareStatement conn "SELECT ref_edn FROM snapshots WHERE head_id=?")]
    (.setString ps 1 head-id)
    (with-open [rs (.executeQuery ps)]
      (when (.next rs) (deser (.getString rs 1))))))

(defn- last-head-event* [^Connection conn sid]
  (with-open [ps (.prepareStatement conn
                   (str "SELECT event_edn FROM events WHERE session_id=? "
                        "AND event_type=':head/published' ORDER BY event_id DESC LIMIT 1"))]
    (.setString ps 1 sid)
    (with-open [rs (.executeQuery ps)]
      (when (.next rs) (deser (.getString rs 1))))))

(defn- reopen-view*
  "qbu · the bounded reopen: restore the latest head's view snapshot and fold
   only the events AFTER its counter watermark, instead of O(total events).
   Any missing piece (no head, no snapshot row, no blob — incl. logs recorded
   before snapshots existed) falls back to the full fold."
  [^Connection conn blobs sid]
  (or (when-let [head-ev (last-head-event* conn sid)]
        (when-let [ref (snapshot-ref* conn (get-in head-ev [:head :head/id]))]
          (when-let [snap (blob/get* blobs ref)]
            (let [watermark (get-in snap [:counters :event] 0)]
              (reduce store/apply-event snap
                      (load-events-since* conn sid watermark))))))
      (when-let [events (seq (load-events* conn sid))]
        (reduce store/apply-event (store/empty-view) events))))

;; ---------------------------------------------------------------------------
;; bj1 · advisory writer lease (every fn here runs under :db-lock)
;;
;; Protocol: insert-or-takeover on the FIRST write to a scope; verify-and-
;; refresh on every later write. Two live writer processes on one scope fail
;; LOUDLY (:fractal/writer-lease-held), and a writer whose lease was taken
;; over after going stale detects it on its next write
;; (:fractal/writer-lease-lost) instead of silently corrupting the log.
;; Readers (read-state / events-since / reopen folds) never touch leases.
;; ---------------------------------------------------------------------------

(defn- lease-row* [^Connection conn scope]
  (with-open [ps (.prepareStatement conn "SELECT owner, heartbeat_ms FROM leases WHERE scope=?")]
    (.setString ps 1 scope)
    (with-open [rs (.executeQuery ps)]
      (when (.next rs)
        {:owner (.getString rs 1) :heartbeat-ms (.getLong rs 2)}))))

(defn- upsert-lease!* [^Connection conn scope owner]
  (with-open [ps (.prepareStatement conn
                   (str "INSERT INTO leases(scope,owner,acquired_at,heartbeat_ms) VALUES (?,?,?,?) "
                        "ON CONFLICT(scope) DO UPDATE SET owner=excluded.owner, "
                        "acquired_at=excluded.acquired_at, heartbeat_ms=excluded.heartbeat_ms"))]
    (.setString ps 1 scope)
    (.setString ps 2 owner)
    (.setString ps 3 (time/now-str))
    (.setLong   ps 4 (System/currentTimeMillis))
    (.executeUpdate ps)))

(defn- refresh-lease!* [^Connection conn scope owner]
  (with-open [ps (.prepareStatement conn "UPDATE leases SET heartbeat_ms=? WHERE scope=? AND owner=?")]
    (.setLong   ps 1 (System/currentTimeMillis))
    (.setString ps 2 scope)
    (.setString ps 3 owner)
    (.executeUpdate ps)))

(defn- delete-owner-leases!* [^Connection conn owner]
  (with-open [ps (.prepareStatement conn "DELETE FROM leases WHERE owner=?")]
    (.setString ps 1 owner)
    (.executeUpdate ps)))

(defn- ensure-lease!*
  "Hold (or acquire) the advisory writer lease on `scope` for `owner`. Throws
   :fractal/writer-lease-held when another live writer holds it (unless
   `steal?` — the explicit crashed-writer escape hatch: take it anyway),
   :fractal/writer-lease-lost when this writer's lease was taken over."
  [^Connection conn leased scope owner ttl-ms steal?]
  (if (contains? @leased scope)
    (when (zero? (long (refresh-lease!* conn scope owner)))
      (swap! leased disj scope)
      (throw (ex-info (str "writer lease lost for scope " scope " (taken over after staleness)")
                      {:error/type :fractal/writer-lease-lost
                       :lease/scope scope :lease/owner owner})))
    (let [row    (lease-row* conn scope)
          holder (:owner row)
          hb     (:heartbeat-ms row)]
      (cond
        (nil? row)
        (upsert-lease!* conn scope owner)

        (and (some? holder)
             (not= holder owner)
             (not steal?)
             (< (- (System/currentTimeMillis) hb) ttl-ms))
        (throw (ex-info (str "another writer holds the lease for scope " scope)
                        {:error/type :fractal/writer-lease-held
                         :lease/scope scope
                         :lease/holder holder
                         :lease/age-ms (- (System/currentTimeMillis) hb)
                         :lease/ttl-ms ttl-ms}))

        :else ; ours already, stale, or stolen explicitly ⇒ (re)take
        (upsert-lease!* conn scope owner))
      (swap! leased conj scope))))

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

(defrecord SqliteStore [conn db-lock sessions blobs live-opts create-lock
                        owner-id lease-ttl-ms lease-steal? leased]
  store/SessionStore

  (create-session! [this session-map]
    (let [sid (:session/id session-map)]
      (locking create-lock                                   ; idempotent + atomic (02 §8.7)
        (if-let [slot (get @sessions sid)]
          (handle-for this sid slot)                         ; in-process re-create ⇒ existing slot
          (let [view (locking db-lock (reopen-view* conn blobs sid))]
            (if view
              ;; REOPEN (recovery): the latest head's snapshot + a tail fold
              ;; (qbu — O(tail)); full deterministic re-fold when no snapshot
              ;; exists (02 §9). Either way the same ids reproduce.
              (let [slot (new-slot sid live-opts view)]
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
          (locking db-lock                                     ; ⛔ lease, then PERSIST (committed)
            (ensure-lease!* conn leased sid owner-id lease-ttl-ms lease-steal?)
            (insert-event!* conn sid stamped))
          (swap! view store/apply-event stamped)               ; …fold ONLY on success
          (live/schedule-notify dispatch stamped)              ; non-blocking; delivered off-lock
          stamped))))

  (append-events! [_ sid evs]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (let [stamped (stamp-batch @view evs)]
          (locking db-lock                                     ; ⛔ lease, then ONE durable txn
            (ensure-lease!* conn leased sid owner-id lease-ttl-ms lease-steal?)
            (insert-events!* conn sid stamped))
          (swap! view (fn [v] (reduce store/apply-event v stamped)))
          (doseq [s stamped] (live/schedule-notify dispatch s))
          stamped))))

  (publish-head! [_ sid head]
    (live/check-not-reentrant! sid)
    (let [{:keys [view lock dispatch]} (get @sessions sid)]
      (locking lock
        (let [head*   (store/prepare-head @view sid head)
              stamped (stamp-ids+ts @view {:event/type :head/published :head head*})
              ;; qbu · the snapshot is a REOPEN ACCELERATOR, never a gate on head
              ;; publication: best-effort blob first (blobs-before-references),
              ;; then its index row; on ANY snapshot failure the head still
              ;; publishes and reopen falls back to the full fold.
              snap-ref (try (blob/put! blobs (store/snapshot-view @view)
                                       {:payload/kind :view-snapshot})
                            (catch Throwable _ nil))]
          (locking db-lock                                    ; ⛔ lease, then PERSIST
            (ensure-lease!* conn leased sid owner-id lease-ttl-ms lease-steal?)
            (when snap-ref
              (try (put-snapshot-row!* conn (:head/id head*) snap-ref)
                   (catch Throwable _ nil)))
            (insert-event!* conn sid stamped))
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
    ;; RELAXED, CROSS-PROCESS-HONEST: re-fold the DURABLE log so a reader
    ;; process observes events committed by a writer process after this store
    ;; opened (WAL permits concurrent readers). O(log) per call — poll politely.
    ;; current-view stays the in-process write-synchronous cache. The fold runs
    ;; OUTSIDE db-lock (the lock only guards the shared Connection).
    (let [events (locking db-lock (load-events* conn sid))]
      (reduce store/apply-event (store/empty-view) events)))

  (peek-next-id [_ sid k]
    (inc (get-in @(:view (get @sessions sid)) [:counters k])))

  (notify-transient [_ sid item]
    (live/notify-transient (:dispatch (get @sessions sid)) item))

  (subscribe! [_ sid callback]
    (live/subscribe (:dispatch (get @sessions sid)) callback))

  (events-since [_ sid event-id]
    ;; Served from the DURABLE log, not the in-process cache — so cross-process
    ;; consumers (CLI wait, external subscribers catching up after a gap) see
    ;; events committed by another process. Persist-before-fold guarantees the
    ;; log is a superset of anything ever delivered live.
    (locking db-lock (load-events-since* conn sid event-id)))

  ;; --- 88j · store-scoped facts + pins (writes lease store/store-lease-scope)

  (append-fact! [this fact]
    (let [fact' (-> (payload-io/edn-safe (store/validate-fact! fact))
                    (assoc :fact/at (time/now-str)))]
      (store/assert-resolvable-refs! this (:fact/value fact'))
      (locking db-lock
        (ensure-lease!* conn leased store/store-lease-scope owner-id lease-ttl-ms lease-steal?)
        (with-open [ps (.prepareStatement conn
                         "INSERT INTO facts(fact_tag,fact_at,fact_edn) VALUES (?,?,?)"
                         java.sql.Statement/RETURN_GENERATED_KEYS)]
          (.setString ps 1 (str (:fact/tag fact')))
          (.setString ps 2 (str (:fact/at fact')))
          (.setString ps 3 (ser fact'))
          (.executeUpdate ps)
          (with-open [rs (.getGeneratedKeys ps)]
            (.next rs)
            (assoc fact' :fact/id (.getLong rs 1)))))))

  (facts-since [_ fact-id]
    (locking db-lock
      (with-open [ps (.prepareStatement conn
                       "SELECT fact_id, fact_edn FROM facts WHERE fact_id>? ORDER BY fact_id ASC")]
        (.setLong ps 1 (long fact-id))
        (with-open [rs (.executeQuery ps)]
          (loop [acc (transient [])]
            (if (.next rs)
              (recur (conj! acc (assoc (deser (.getString rs 2)) :fact/id (.getLong rs 1))))
              (persistent! acc)))))))

  (pin! [this pin]
    (let [pin' (payload-io/edn-safe (store/validate-pin! pin))]
      (store/assert-resolvable-refs! this (:pin/ref pin'))
      (store/assert-pin-head! this pin')
      (locking db-lock
        (ensure-lease!* conn leased store/store-lease-scope owner-id lease-ttl-ms lease-steal?)
        (let [k   (store/pin-key pin')
              cur (with-open [ps (.prepareStatement conn "SELECT pin_edn FROM pins WHERE pin_name=?")]
                    (.setString ps 1 k)
                    (with-open [rs (.executeQuery ps)]
                      (when (.next rs) (deser (.getString rs 1)))))
              p   (store/prepare-pin pin' cur (time/now-str))]
          (with-open [ps (.prepareStatement conn
                           (str "INSERT INTO pins(pin_name,pin_edn) VALUES (?,?) "
                                "ON CONFLICT(pin_name) DO UPDATE SET pin_edn=excluded.pin_edn"))]
            (.setString ps 1 k)
            (.setString ps 2 (ser p))
            (.executeUpdate ps))
          p))))

  (read-pin [_ pin-name]
    (locking db-lock
      (with-open [ps (.prepareStatement conn "SELECT pin_edn FROM pins WHERE pin_name=?")]
        (.setString ps 1 (store/pin-key {:pin/name pin-name}))
        (with-open [rs (.executeQuery ps)]
          (when (.next rs) (deser (.getString rs 1)))))))

  (list-pins [_]
    (locking db-lock
      (with-open [ps (.prepareStatement conn "SELECT pin_edn FROM pins ORDER BY pin_name ASC")]
        (with-open [rs (.executeQuery ps)]
          (loop [acc (transient [])]
            (if (.next rs)
              (recur (conj! acc (deser (.getString rs 1))))
              (persistent! acc))))))))

;; ---------------------------------------------------------------------------
;; Construction / teardown
;; ---------------------------------------------------------------------------

(def default-lease-ttl-ms
  "bj1 · staleness window for the advisory writer lease. The heartbeat only
   refreshes on appends, and appends can sit minutes apart behind a slow
   provider call — so the TTL is deliberately generous. A crashed writer's
   scope is reclaimable after this long."
  600000)

(defn sqlite-store
  "Open (creating if absent) a SqliteStore under `dir`: `<dir>/events.db` holds the
   per-session event log + sessions table; `<dir>/blobs/` is the GLOBAL blob store.
   `:live-opts` (`{:bound :drop}`) seed each session's live dispatch. Multiple stores
   may open the same `dir` (e.g. a process restart reopening for resume) — READS are
   cross-process-safe; WRITES are guarded by the advisory writer lease (bj1):
   the second live writer on a scope fails with :fractal/writer-lease-held.
   `:writer-lease-ttl-ms` overrides the staleness window; `:writer-lease-steal?`
   is the explicit crashed-writer escape hatch (take ANY foreign lease — only
   for an operator who knows the prior writer is dead)."
  [{:keys [dir live-opts writer-lease-ttl-ms writer-lease-steal?]}]
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
                       (or live-opts {}) (Object.)
                       (str (java.util.UUID/randomUUID))
                       (or writer-lease-ttl-ms default-lease-ttl-ms)
                       (boolean writer-lease-steal?)
                       (atom #{}))
        (catch Throwable e
          (try (.close conn) (catch Throwable _ nil))
          (throw e))))))

(defn close!
  "Release the store: stop every session's live dispatcher (idempotent; daemon
   threads are JVM-exit-safe regardless), RELEASE this writer's leases, and close
   the JDBC connection. After this a session can be durably reopened by
   constructing a fresh store on the same `dir` (the recovery path) — this is
   what makes close+reopen behave like a process restart in tests and resume."
  [store]
  (doseq [[_ slot] @(:sessions store)]
    (live/stop-dispatch (:dispatch slot)))
  (locking (:db-lock store)
    (try (delete-owner-leases!* (:conn store) (:owner-id store)) (catch Throwable _ nil))
    (try (exec! (:conn store) "PRAGMA wal_checkpoint(TRUNCATE)") (catch Throwable _ nil))
    (.close ^Connection (:conn store)))
  nil)
