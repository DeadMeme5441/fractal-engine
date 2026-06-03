(ns fractal-engine.blob-store
  "Canonical byte storage for durable payloads.

  The protocol is intentionally backend-shaped: the local implementation stores
  content-addressed bytes under a store root, while future implementations can use
  another physical backend without changing the domain refs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
  (:import [java.io PushbackReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.util UUID]))

(def default-media-type "application/edn")

(defprotocol BlobStore
  (put-bytes! [store bytes opts])
  (put-edn! [store value opts])
  (read-bytes [store blob-ref])
  (read-edn [store blob-ref])
  (exists? [store blob-ref])
  (verify [store blob-ref]))

(defn path
  [dir & parts]
  (let [base (cond
               (instance? Path dir) dir
               (instance? java.io.File dir) (.toPath ^java.io.File dir)
               :else (.toPath (io/file dir)))]
    (reduce (fn [^Path p part] (.resolve p (str part))) base parts)))

(defn formatted-edn
  [value]
  (binding [*print-dup* false
            *print-readably* true]
    (with-out-str
      (pp/write value :stream *out* :pretty true)
      (newline))))

(defn edn-bytes
  [value]
  (.getBytes (formatted-edn value) StandardCharsets/UTF_8))

(defn sha256-bytes
  [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn blob-key
  [sha extension]
  (str "sha256/"
       (subs sha 0 2) "/"
       (subs sha 2 4) "/"
       sha
       (or extension "")))

(defn- extension-for
  [{:keys [media-type extension]}]
  (or extension
      (case media-type
        "application/edn" ".edn"
        nil ".edn"
        "" "")))

(defn- ref-for
  [sha size key opts]
  {:blob/id (str "sha256:" sha)
   :blob/sha256 sha
   :blob/size (long size)
   :blob/media-type (or (:media-type opts) default-media-type)
   :blob/encoding (or (:encoding opts) :utf-8)
   :blob/compression (:compression opts)
   :blob/store :file
   :blob/key key})

(defn file-store
  [root]
  {:store/kind :file
   :store/root (path root)})

(defn- blob-path
  [store blob-ref]
  (path (:store/root store) "blobs" (:blob/key blob-ref)))

(defn- write-atomic-bytes!
  [file bytes]
  (let [p (path file)
        parent (.getParent p)
        tmp (.resolve parent (str "." (.getFileName p) "." (UUID/randomUUID) ".tmp"))]
    (Files/createDirectories parent (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/write tmp bytes (make-array java.nio.file.OpenOption 0))
    (Files/move tmp p
                (into-array StandardCopyOption
                            [StandardCopyOption/REPLACE_EXISTING
                             StandardCopyOption/ATOMIC_MOVE]))))

(extend-type clojure.lang.PersistentArrayMap
  BlobStore
  (put-bytes! [store bytes opts]
    (let [bytes (byte-array bytes)
          sha (sha256-bytes bytes)
          key (blob-key sha (extension-for opts))
          ref (ref-for sha (alength bytes) key opts)
          file (blob-path store ref)]
      (when-not (Files/isRegularFile file (make-array java.nio.file.LinkOption 0))
        (write-atomic-bytes! file bytes))
      (when-not (= :ok (:status (verify store ref)))
        (throw (ex-info "Blob hash verification failed after write"
                        {:error/type :blob/hash-mismatch
                         :blob/id (:blob/id ref)
                         :blob/key (:blob/key ref)})))
      ref))

  (put-edn! [store value opts]
    (put-bytes! store (edn-bytes value)
                (merge {:media-type default-media-type
                        :encoding :utf-8
                        :extension ".edn"}
                       opts)))

  (read-bytes [store blob-ref]
    (let [p (blob-path store blob-ref)]
      (when (Files/isRegularFile p (make-array java.nio.file.LinkOption 0))
        (Files/readAllBytes p))))

  (read-edn [store blob-ref]
    (when-let [bytes (read-bytes store blob-ref)]
      (with-open [r (PushbackReader.
                     (io/reader
                      (java.io.ByteArrayInputStream. bytes)
                      :encoding "UTF-8"))]
        (edn/read {:eof nil} r))))

  (exists? [store blob-ref]
    (Files/isRegularFile (blob-path store blob-ref)
                         (make-array java.nio.file.LinkOption 0)))

  (verify [store blob-ref]
    (if-let [bytes (read-bytes store blob-ref)]
      (let [actual (sha256-bytes bytes)]
        (if (= actual (:blob/sha256 blob-ref))
          {:status :ok
           :blob/id (:blob/id blob-ref)
           :blob/size (alength bytes)}
          {:status :issues
           :issue/type :blob/hash-mismatch
           :blob/id (:blob/id blob-ref)
           :expected (:blob/sha256 blob-ref)
           :actual actual}))
      {:status :issues
       :issue/type :blob/missing
       :blob/id (:blob/id blob-ref)
       :blob/key (:blob/key blob-ref)})))
