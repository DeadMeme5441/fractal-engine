(ns fractal-engine.snapshot
  (:require [clojure.edn :as edn]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cache :as cache]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.time :as time])
  (:import [java.io InputStream OutputStream Reader Writer]
           [java.net Socket]
           [java.util.concurrent Future]))

(def special-symbols '#{FINAL lm map-lm rlm map-rlm attach-rlm})

(declare source-session-id)

(defn- edn-string [value]
  (store-io/formatted-edn value))

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

(defn- value-ref-for-var! [dir sym value]
  (artifacts/value-ref! dir
                        {:var/name (name sym)
                         :var/value value}
                        {:payload/kind :var}))

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
                            :var/value-ref (value-ref-for-var! dir sym value)
                            :var/summary (var-summary value)})))))
        [])
       (sort-by :var/name)
       vec))

(defn snapshot-summary [var-rows]
  {:var/count (count var-rows)
   :var/restorable (count (filter #(= :restorable (:var/status %)) var-rows))
   :var/unrestorable (count (filter #(= :unrestorable (:var/status %)) var-rows))
   :blob/count (count (filter #(some? (get-in % [:var/value-ref :blob/id])) var-rows))})

(defn write-turn-snapshot! [state ns-sym turn-row eval-row]
  (let [dir (:locator @state)
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
        snapshot-ref (artifacts/value-ref! dir snapshot {:payload/kind :snapshot})
        row {:snapshot/id snapshot-id
             :snapshot/kind :turn-final
             :snapshot/turn-id turn-id
             :snapshot/message-through-id (:snapshot/message-through-id snapshot)
             :snapshot/eval-id (:snapshot/eval-id snapshot)
             :snapshot/current-ns (str ns-sym)
             :snapshot/created-at created-at
             :snapshot/ref snapshot-ref
             :snapshot/summary (snapshot-summary var-rows)}]
    (artifacts/emit! state {:event/type :snapshot/added :snapshot row})
    row))

(defn completed-turn-snapshot? [row]
  (= :turn-final (:snapshot/kind row)))

(defn snapshots [dir]
  (:snapshots (session-db/history-view (artifacts/store-root-for-locator dir)
                                       (source-session-id dir))))

(defn heads [dir]
  (let [sid (source-session-id dir)]
    (filterv #(= sid (:head/session %))
             (session-db/list-head-records (artifacts/store-root-for-locator dir)))))

(defn refs [dir]
  (session-db/read-ref (artifacts/store-root-for-locator dir) (source-session-id dir)))

(defn current-head-id [dir]
  (or (:ref/current-head (refs dir))
      (:head/id (last (heads dir)))))

(defn head-for-id [dir head-id]
  (first (filter #(= head-id (:head/id %)) (heads dir))))

(defn latest-turn-snapshot [dir]
  (last (filter completed-turn-snapshot? (snapshots dir))))

(defn snapshot-for-turn [dir turn-id]
  (first (filter #(and (completed-turn-snapshot? %)
                       (= (long turn-id) (long (:snapshot/turn-id %))))
                 (snapshots dir))))

(defn snapshot-for-head [dir head-id]
  (when-let [head (head-for-id dir head-id)]
    (when-let [snapshot-id (:head/snapshot-id head)]
      (first (filter #(= snapshot-id (:snapshot/id %)) (snapshots dir))))))

(defn select-snapshot [dir opts]
  (cond
    (:head opts) (snapshot-for-head dir (:head opts))
    (:turn opts) (snapshot-for-turn dir (:turn opts))
    :else (latest-turn-snapshot dir)))

(defn read-snapshot-blob [dir snapshot-row]
  (when-let [ref (:snapshot/ref snapshot-row)]
    (let [value (artifacts/read-ref dir ref)]
      (when-not (= ::artifacts/missing value)
        value))))

(defn snapshot-not-found-error [dir opts]
  {:error/type :snapshot/not-found
   :reason :no-completed-turn-snapshot
   :source/session-id (source-session-id dir)
   :head (:head opts)
   :turn (:turn opts)})

(defn require-snapshot [dir opts]
  (or (select-snapshot dir opts)
      (throw (ex-info "No completed turn snapshot exists"
                      (snapshot-not-found-error dir opts)))))

(defn require-snapshot-blob [dir snapshot-row]
  (or (read-snapshot-blob dir snapshot-row)
      (throw (ex-info "Snapshot blob is missing"
                      {:error/type :snapshot/blob-not-found
                       :source/session-id (source-session-id dir)
                       :snapshot/id (:snapshot/id snapshot-row)}))))

(defn clear-session-vars! [target-ns-sym]
  (doseq [[sym _] (ns-publics target-ns-sym)
          :when (not (contains? special-symbols sym))]
    (ns-unmap target-ns-sym sym)))

(defn restore-vars! [source-dir target-ns-sym snapshot]
  (let [restored (atom [])
        skipped (atom [])]
    (clear-session-vars! target-ns-sym)
    (doseq [row (:snapshot/vars snapshot)]
      (case (:var/status row)
        :restorable
        (let [sym (symbol (:var/name row))
              payload (artifacts/read-ref source-dir (:var/value-ref row))
              value (if (and (map? payload) (contains? payload :var/value))
                      (:var/value payload)
                      payload)]
          (if (= ::artifacts/missing payload)
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
  (->> (:messages (session-db/history-view (artifacts/store-root-for-locator dir)
                                           (source-session-id dir)))
       (filter #(<= (long (:message/id %)) (long message-through-id)))
       vec))

(defn source-session-id [dir]
  (artifacts/session-id-for-locator dir))

(defn session-fingerprint [dir]
  (let [root (artifacts/store-root-for-locator dir)
        sid (source-session-id dir)
        ref (session-db/read-ref root sid)
        head (when-let [hid (:ref/current-head ref)]
               (session-db/read-head root sid hid))]
    (cache/sha256-string
     (pr-str {:fingerprint/version 4
              :session/id sid
              :session/current-head (:ref/current-head ref)
              :head/fingerprint (:head/fingerprint head)}))))

(defn write-restore-report! [dir report]
  report)

(defn write-lineage! [dir lineage]
  lineage)
