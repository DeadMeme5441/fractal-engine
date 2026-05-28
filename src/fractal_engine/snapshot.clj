(ns fractal-engine.snapshot
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.time :as time])
  (:import [java.io InputStream OutputStream Reader Writer]
           [java.net Socket]
           [java.nio.file Files Path]
           [java.util.concurrent Future]))

(def inline-byte-threshold 4096)

(def special-symbols '#{FINAL lm map-lm rlm map-rlm attach-rlm})

(defn turn-token [turn-id]
  (format "turn-%04d" (long turn-id)))

(defn- edn-string [value]
  (artifacts/formatted-edn value))

(defn- blob-ref! [dir rel-path value]
  (let [text (edn-string value)
        bytes (.getBytes text java.nio.charset.StandardCharsets/UTF_8)]
    (artifacts/write-edn! (artifacts/path dir rel-path) value)
    {:value/kind :blob
     :path rel-path
     :sha256 (cache/sha256-string text)
     :bytes (alength bytes)}))

(defn- inline-ref [value]
  {:value/kind :inline :value value})

(defn- value-bytes [value]
  (alength (.getBytes (edn-string value) java.nio.charset.StandardCharsets/UTF_8)))

(defn- safe-var-file [sym]
  (str (str/replace (name sym) #"[^A-Za-z0-9_.-]" "_") ".edn"))

(defn- edn-safe? [value]
  (try
    (= value (edn/read-string (edn-string value)))
    (catch Throwable _ false)))

(defn- unrestorable-reason [value]
  (cond
    (fn? value) :function
    (var? value) :var
    (instance? clojure.lang.Namespace value) :namespace
    (instance? Class value) :class
    (instance? Process value) :process
    (instance? Reader value) :reader
    (instance? Writer value) :writer
    (instance? InputStream value) :input-stream
    (instance? OutputStream value) :output-stream
    (instance? Socket value) :socket
    (instance? Future value) :future
    (instance? clojure.lang.IPending value) :pending
    (instance? clojure.lang.Delay value) :delay
    (instance? clojure.lang.IAtom value) :atom
    (instance? clojure.lang.Ref value) :ref
    (instance? clojure.lang.Agent value) :agent
    (instance? clojure.lang.LazySeq value) :lazy-seq
    (not (edn-safe? value)) :not-data
    :else nil))

(defn- var-summary [value]
  (cond-> (artifacts/value-summary value)
    (some? value) (assoc :class (.getName (class value)))))

(defn- value-ref-for-var! [dir turn-id sym value]
  (let [bytes (value-bytes value)]
    (if (> bytes inline-byte-threshold)
      (blob-ref! dir
                 (str "blobs/vars/" (turn-token turn-id) "/" (safe-var-file sym))
                 value)
      (inline-ref value))))

(defn snapshot-session-vars! [dir ns-sym turn-id]
  (->> (ns-publics ns-sym)
       (reduce-kv
        (fn [rows sym v]
          (if (contains? special-symbols sym)
            rows
            (let [value @v
                  symbol-string (str ns-sym "/" (name sym))]
              (if-let [reason (unrestorable-reason value)]
                (conj rows {:var/name (name sym)
                            :var/symbol symbol-string
                            :var/status :unrestorable
                            :var/class (when (some? value) (.getName (class value)))
                            :var/reason reason
                            :var/summary (var-summary value)})
                (conj rows {:var/name (name sym)
                            :var/symbol symbol-string
                            :var/status :restorable
                            :var/value-ref (value-ref-for-var! dir turn-id sym value)
                            :var/summary (var-summary value)})))))
        [])
       (sort-by :var/name)
       vec))

(defn snapshot-summary [var-rows]
  {:var/count (count var-rows)
   :var/restorable (count (filter #(= :restorable (:var/status %)) var-rows))
   :var/unrestorable (count (filter #(= :unrestorable (:var/status %)) var-rows))
   :blob/count (count (filter #(= :blob (get-in % [:var/value-ref :value/kind])) var-rows))})

(defn write-turn-snapshot! [state ns-sym turn-row eval-row]
  (let [dir (:dir @state)
        turn-id (:turn/id turn-row)
        snapshot-id (artifacts/next-counter! state :snapshot)
        created-at (time/now-str)
        var-rows (snapshot-session-vars! dir ns-sym turn-id)
        snapshot (cond-> {:snapshot/id snapshot-id
                          :snapshot/kind :turn-final
                          :snapshot/turn-id turn-id
                          :snapshot/message-through-id (apply max 0 (map :message/id (:messages @state)))
                          :snapshot/eval-id (:eval/id eval-row)
                          :snapshot/ns (str ns-sym)
                          :snapshot/current-ns (str ns-sym)
                          :snapshot/created-at created-at
                          :snapshot/vars var-rows}
                   (:eval/id eval-row) (assoc :snapshot/eval-row (select-keys eval-row [:eval/id :eval/status])))
        snapshot-ref (blob-ref! dir
                                (str "blobs/snapshots/" (turn-token turn-id) ".edn")
                                snapshot)
        row {:snapshot/id snapshot-id
             :snapshot/kind :turn-final
             :snapshot/turn-id turn-id
             :snapshot/message-through-id (:snapshot/message-through-id snapshot)
             :snapshot/eval-id (:snapshot/eval-id snapshot)
             :snapshot/current-ns (str ns-sym)
             :snapshot/created-at created-at
             :snapshot/ref snapshot-ref
             :snapshot/summary (snapshot-summary var-rows)}]
    (swap! state update :snapshots conj row)
    (artifacts/add-event! state {:event/type :snapshot-written
                                 :snapshot/id snapshot-id
                                 :snapshot/kind :turn-final
                                 :turn/id turn-id})
    row))

(defn completed-turn-snapshot? [row]
  (= :turn-final (:snapshot/kind row)))

(defn snapshots [dir]
  (artifacts/read-edn-file (artifacts/path dir "snapshots.edn") []))

(defn latest-turn-snapshot [dir]
  (last (filter completed-turn-snapshot? (snapshots dir))))

(defn snapshot-for-turn [dir turn-id]
  (first (filter #(and (completed-turn-snapshot? %)
                       (= (long turn-id) (long (:snapshot/turn-id %))))
                 (snapshots dir))))

(defn select-snapshot [dir opts]
  (if-let [turn (:turn opts)]
    (snapshot-for-turn dir turn)
    (latest-turn-snapshot dir)))

(defn read-snapshot-blob [dir snapshot-row]
  (when-let [ref (:snapshot/ref snapshot-row)]
    (let [value (artifacts/read-ref dir ref)]
      (when-not (= ::artifacts/missing value)
        value))))

(defn snapshot-not-found-error [dir opts]
  {:error/type :snapshot/not-found
   :reason :no-completed-turn-snapshot
   :source/path (str dir)
   :turn (:turn opts)})

(defn require-snapshot [dir opts]
  (or (select-snapshot dir opts)
      (throw (ex-info "No completed turn snapshot exists"
                      (snapshot-not-found-error dir opts)))))

(defn require-snapshot-blob [dir snapshot-row]
  (or (read-snapshot-blob dir snapshot-row)
      (throw (ex-info "Snapshot blob is missing"
                      {:error/type :snapshot/blob-not-found
                       :source/path (str dir)
                       :snapshot/id (:snapshot/id snapshot-row)}))))

(defn restore-vars! [source-dir target-ns-sym snapshot]
  (let [restored (atom [])
        skipped (atom [])]
    (doseq [row (:snapshot/vars snapshot)]
      (case (:var/status row)
        :restorable
        (let [sym (symbol (:var/name row))
              value (artifacts/read-ref source-dir (:var/value-ref row))]
          (if (= ::artifacts/missing value)
            (swap! skipped conj {:var/name (:var/name row) :reason :missing-value})
            (do
              (intern (the-ns target-ns-sym) sym value)
              (swap! restored conj (:var/name row)))))
        :unrestorable
        (swap! skipped conj {:var/name (:var/name row)
                             :reason (:var/reason row)})
        (swap! skipped conj {:var/name (:var/name row)
                             :reason :unknown-var-status})))
    {:restored-vars @restored
     :skipped-vars @skipped
     :restored-count (count @restored)
     :skipped-count (count @skipped)}))

(defn messages-through [dir message-through-id]
  (->> (artifacts/read-edn-file (artifacts/path dir "messages.edn") [])
       (filter #(<= (long (:message/id %)) (long message-through-id)))
       vec))

(defn source-session-id [dir]
  (:session/id (artifacts/read-edn-file (artifacts/path dir "session.edn") {})))

(defn session-fingerprint [dir]
  (let [root (artifacts/path dir)
        files (with-open [paths (Files/walk root (make-array java.nio.file.FileVisitOption 0))]
                (->> (iterator-seq (.iterator paths))
                     (filter #(Files/isRegularFile ^Path % (make-array java.nio.file.LinkOption 0)))
                     (sort-by #(.toString (.relativize root ^Path %)))
                     (mapv (fn [^Path p]
                             (let [rel (.toString (.relativize root p))
                                   text (slurp (.toFile p))]
                               [rel (cache/sha256-string text)])))))]
    (cache/sha256-string (pr-str files))))

(defn write-restore-report! [dir report]
  (artifacts/write-edn! (artifacts/path dir "restore.edn") report))

(defn write-lineage! [dir lineage]
  (artifacts/write-edn! (artifacts/path dir "lineage.edn") lineage))
