(ns fractal-engine.journal
  "The append-only event log — the durable source of truth for a session. One EDN
  form per line, so appending an event is O(1) and never rewrites the file, and the
  log stays scannable with ordinary line tools. Everything else in a run directory
  (session.edn, the table projections, usage, tree) is a *projection* of this
  stream, materialized at boundaries.

  This namespace is deliberately tiny and dependency-free: it only knows how to
  append an event value and read the stream back. What an event *means* lives in
  `event.clj` (the pure reducer); how a session emits events lives in
  `artifacts.clj`."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.io BufferedReader StringReader Writer]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardOpenOption]))

(def filename "events.ednl")

(defn- ->path ^Path [dir]
  (cond
    (instance? Path dir) dir
    (instance? java.io.File dir) (.toPath ^java.io.File dir)
    :else (.toPath (io/file dir))))

(defn journal-path ^Path [dir]
  (.resolve (->path dir) filename))

(defn- line-bytes [event]
  ;; pr-str gives one readable form; readably so EDN round-trips. One line per
  ;; event means the newline is the record separator, so guard against any stray
  ;; newline inside the printed form by printing without pretty-printing.
  (let [s (binding [*print-dup* false *print-readably* true *print-length* nil *print-level* nil]
            (pr-str event))]
    (.getBytes (str s "\n") StandardCharsets/UTF_8)))

(defn append!
  "Append one event value as a single line. O(1), durable on return. Thread-safety
  is the caller's responsibility — `artifacts/emit!` serializes appends per session."
  [dir event]
  (let [p (journal-path dir)]
    (Files/createDirectories (.getParent p) (make-array java.nio.file.attribute.FileAttribute 0))
    (Files/write p
                 (line-bytes event)
                 (into-array OpenOption [StandardOpenOption/CREATE StandardOpenOption/APPEND]))
    event))

(defn exists? [dir]
  (Files/isRegularFile (journal-path dir) (make-array java.nio.file.LinkOption 0)))

(defn read-events
  "Read the full event stream back as a vector of values, in append order. Blank
  lines are skipped. A truncated final line (crash mid-append) is dropped rather
  than throwing — the recorded prefix is still authoritative."
  [dir]
  (let [p (journal-path dir)]
    (if-not (Files/isRegularFile p (make-array java.nio.file.LinkOption 0))
      []
      (with-open [r (BufferedReader. (io/reader (.toFile p)))]
        (loop [acc (transient [])]
          (if-let [line (.readLine r)]
            (let [line (.trim line)]
              (if (.isEmpty line)
                (recur acc)
                (let [ev (try (edn/read-string line)
                              (catch Throwable _ ::truncated))]
                  (if (= ev ::truncated)
                    (persistent! acc)          ; stop at the first unreadable line
                    (recur (conj! acc ev))))))
            (persistent! acc)))))))
