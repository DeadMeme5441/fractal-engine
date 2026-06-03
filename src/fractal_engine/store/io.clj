(ns fractal-engine.store.io
  "Small filesystem/EDN helpers used by store backends.

  This namespace is intentionally dumb: it knows nothing about sessions, heads,
  invocations, or indexes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp])
  (:import [java.io PushbackReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.util UUID]))

(defn path
  [dir & parts]
  (let [base (cond
               (instance? Path dir) dir
               (instance? java.io.File dir) (.toPath ^java.io.File dir)
               :else (.toPath (io/file dir)))]
    (reduce (fn [^Path p part] (.resolve p (str part))) base parts)))

(defn ensure-dir!
  [p]
  (Files/createDirectories (path p) (make-array java.nio.file.attribute.FileAttribute 0))
  p)

(defn formatted-edn
  [value]
  (binding [*print-dup* false
            *print-readably* true]
    (with-out-str
      (pp/write value :stream *out* :pretty true)
      (newline))))

(defn write-edn!
  [file value]
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

(defn read-edn-file
  [file default]
  (let [f (if (instance? Path file)
            (.toFile ^Path file)
            (io/file file))]
    (if (.exists f)
      (with-open [r (PushbackReader. (io/reader f))]
        (edn/read {:eof default} r))
      default)))
