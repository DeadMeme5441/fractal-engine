(ns fractal.engine.store.blobstore
  "L1.5 · the GLOBAL, file-based, content-addressed blob store (02 §3, §9) — the
   Phase-2 payload substrate that SqliteStore's intern-payload!/read-payload*
   delegate to. A blob's id is `sha256:<hex>` over `payload/canonical-bytes`,
   computed BYTE-FOR-BYTE the same way MemoryStore addresses its in-memory blobs —
   so the same value dedups to the same content node across sessions AND across
   processes (the shared Merkle leaf, 02 §9). No `sid`: blobs are global.

   On disk: the canonical EDN bytes are written at `<root>/<aa>/<sha256hex>.blob`
   (sharded by the first two hex chars), read back via `clojure.edn`. Writes are
   atomic (temp file + ATOMIC_MOVE) so a concurrent reader never observes a partial
   blob and an idempotent re-write of identical content is a harmless no-op. No
   extra dependency — just the JDK filesystem + the pure `payload` ns."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.instant :as instant]
            [fractal.engine.payload :as payload])
  (:import [java.io File]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; EDN read-back — the inverse of payload/canonical-bytes
;; ---------------------------------------------------------------------------

(def ^:private edn-readers
  "Readers for the two built-in EDN tagged elements a canonical value may print
   (`#inst`/`#uuid`) — passed explicitly so read-back never depends on ambient
   *data-readers*. Everything else `payload/edn-safe` produces is plain EDN."
  {'inst instant/read-instant-date
   'uuid (fn [s] (java.util.UUID/fromString s))})

(defn- read-edn [^String s]
  (edn/read-string {:readers edn-readers} s))

;; ---------------------------------------------------------------------------
;; Paths
;; ---------------------------------------------------------------------------

(defn- hex-of
  "The bare hex of a content id (`sha256:<hex>` → `<hex>`). Sharding/filenames use
   the hex only — never the `sha256:` prefix (a `:` is not portable in a filename)."
  [^String content-id]
  (let [i (.indexOf content-id ":")]
    (if (neg? i) content-id (subs content-id (inc i)))))

(defn- blob-file ^File [^File root ^String content-id]
  (let [hex (hex-of content-id)
        shard (subs hex 0 2)]
    (io/file root shard (str hex ".blob"))))

;; ---------------------------------------------------------------------------
;; Open / put / get
;; ---------------------------------------------------------------------------

(defn open
  "Open (creating if absent) a blob store rooted at `dir`. Returns an opaque
   handle `{:root <File>}`."
  [dir]
  (let [root (io/file dir)]
    (.mkdirs root)
    {:root root}))

(defn put!
  "Content-address `value` into the GLOBAL store and return a tagged payload-ref
   (02 §3). id = `sha256:` over `payload/canonical-bytes` (the SAME basis as
   MemoryStore, so refs are uniform across impls). IDEMPOTENT: identical values
   resolve to one on-disk node (dedup / Merkle identity); a re-write of existing
   content is skipped. The blob file is committed BEFORE the caller appends the
   event that references it (orphan blobs are fine; dangling refs are forbidden)."
  [{:keys [^File root]} value opts]
  (let [bytes (payload/canonical-bytes value)
        id    (str "sha256:" (payload/sha256-hex bytes))
        f     (blob-file root id)]
    (when-not (.exists f)
      (.mkdirs (.getParentFile f))
      ;; write to a unique temp file in the shard dir, then atomically move into
      ;; place — readers see all-or-nothing, and a racing identical write is a no-op.
      (let [tmp (Files/createTempFile (.toPath (.getParentFile f)) "blob-" ".tmp"
                                      (make-array FileAttribute 0))]
        (try
          (Files/write tmp ^bytes bytes (make-array java.nio.file.OpenOption 0))
          (try
            (Files/move tmp (.toPath f)
                        (into-array java.nio.file.CopyOption
                                    [StandardCopyOption/ATOMIC_MOVE
                                     StandardCopyOption/REPLACE_EXISTING]))
            (catch java.nio.file.AtomicMoveNotSupportedException _
              (Files/move tmp (.toPath f)
                          (into-array java.nio.file.CopyOption
                                      [StandardCopyOption/REPLACE_EXISTING]))))
          (finally
            (Files/deleteIfExists tmp)))))
    (payload/make-ref id (:payload/kind opts) (alength bytes))))

(defn get*
  "Dereference a tagged ref to its value via global content lookup; nil if the
   blob is absent (so `store/verify-no-dangling-refs` can detect a dangling ref)."
  [{:keys [^File root]} ref]
  (let [f (blob-file root (:payload/id ref))]
    (when (.exists f)
      (read-edn (slurp f :encoding "UTF-8")))))

(defn has?
  "True iff the blob for `ref` is present on disk."
  [{:keys [^File root]} ref]
  (.exists (blob-file root (:payload/id ref))))

(defn count-blobs
  "Number of on-disk blob nodes (test/diagnostic — reachability GC is a later
   concern, 02 §7)."
  [{:keys [^File root]}]
  (->> (file-seq root)
       (filter #(and (.isFile ^File %) (.endsWith (.getName ^File %) ".blob")))
       count))
