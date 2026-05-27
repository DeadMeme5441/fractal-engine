(ns fractal-engine.artifacts
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [fractal-engine.time :as time])
  (:import [java.io PushbackReader]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path StandardCopyOption]
           [java.security MessageDigest]
           [java.util UUID]))

(def artifact-version 1)
(def surface-version 1)
(def surface '[FINAL lm map-lm rlm map-rlm])
(def inline-limit 8192)

(defn session-id []
  (str "session-" (UUID/randomUUID)))

(defn child-id [n]
  (format "child-%04d" (long n)))

(defn path [dir & parts]
  (let [base (cond
               (instance? Path dir) dir
               (instance? java.io.File dir) (.toPath ^java.io.File dir)
               :else (.toPath (io/file dir)))]
    (reduce (fn [^Path p part] (.resolve p (str part)))
            base
            parts)))

(defn ensure-dir! [p]
  (Files/createDirectories (path p) (make-array java.nio.file.attribute.FileAttribute 0))
  p)

(defn formatted-edn [value]
  (binding [*print-dup* false
            *print-readably* true]
    (with-out-str
      (pp/write value :stream *out* :pretty true)
      (newline))))

(defn- atomic-spit! [file value]
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

(defn read-edn-file [file default]
  (let [f (if (instance? Path file)
            (.toFile ^Path file)
            (io/file file))]
    (if (.exists f)
      (with-open [r (PushbackReader. (io/reader f))]
        (edn/read {:eof default} r))
      default)))

(defn sha256 [^bytes bytes]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

(defn value-summary [value]
  (cond
    (nil? value) {:kind :nil}
    (string? value) {:kind :string :count (count value)}
    (map? value) {:kind :map :count (count value) :keys (vec (take 20 (keys value)))}
    (vector? value) {:kind :vector :count (count value)}
    (set? value) {:kind :set :count (count value)}
    (seq? value) {:kind :seq :count (count value)}
    :else {:kind (keyword (-> value class .getSimpleName str/lower-case))}))

(defn project-value
  ([value] (project-value value 0))
  ([value depth]
   (cond
     (> depth 4) {:truncated? true :reason :max-depth}
     (nil? value) nil
     (or (boolean? value) (number? value) (keyword? value) (symbol? value)) value
     (string? value) (if (> (count value) 2000)
                       {:value/type :string
                        :value/preview (subs value 0 2000)
                        :value/truncated? true
                        :value/count (count value)}
                       value)
     (map? value) (into {}
                        (map (fn [[k v]] [(project-value k (inc depth))
                                          (project-value v (inc depth))]))
                        (take 50 value))
     (vector? value) (cond-> (vec (map #(project-value % (inc depth)) (take 50 value)))
                       (> (count value) 50)
                       (conj {:truncated? true :remaining (- (count value) 50)}))
     (set? value) {:value/type :set
                   :value/count (count value)
                   :value/items (vec (map #(project-value % (inc depth)) (take 50 value)))}
     (seq? value) {:value/type :seq
                   :value/items (vec (map #(project-value % (inc depth)) (take 50 value)))}
     :else {:value/type :object
            :class (.getName (class value))
            :preview (try (pr-str value)
                          (catch Throwable _ (str value)))})))

(defn write-blob! [dir value]
  (let [edn-str (binding [*print-dup* false *print-readably* true] (pr-str value))
        bytes (.getBytes edn-str StandardCharsets/UTF_8)
        hex (sha256 bytes)
        rel (str "blobs/sha256/" (subs hex 0 2) "/" (subs hex 2 4) "/" hex ".edn")
        p (path dir rel)]
    (when-not (Files/exists p (make-array java.nio.file.LinkOption 0))
      (Files/createDirectories (.getParent p) (make-array java.nio.file.attribute.FileAttribute 0))
      (atomic-spit! p value))
    {:value/kind :blob
     :blob/id (str "sha256:" hex)
     :value/type :edn
     :value/summary (value-summary value)}))

(defn blob-path [dir blob-id]
  (let [hex (str/replace blob-id #"^sha256:" "")]
    (path dir "blobs" "sha256" (subs hex 0 2) (subs hex 2 4) (str hex ".edn"))))

(defn read-ref [dir ref]
  (case (:value/kind ref)
    :inline (:value ref)
    :blob (read-edn-file (blob-path dir (:blob/id ref)) ::missing)
    ::missing))

(defn value-ref! [dir value]
  (let [s (try (binding [*print-dup* false *print-readably* true] (pr-str value))
               (catch Throwable _ nil))]
    (if (and s (<= (count (.getBytes s StandardCharsets/UTF_8)) inline-limit))
      {:value/kind :inline :value value}
      (write-blob! dir value))))

(defn empty-usage []
  {:usage/own-root {:usage/status :unknown}
   :usage/own-leaf {:usage/status :unknown}
   :usage/children {:usage/status :unknown}
   :usage/total-tree {:usage/status :unknown}})

(defn- child-tree [child-dir]
  (let [session (read-edn-file (path child-dir "session.edn") {})
        calls (read-edn-file (path child-dir "calls.edn") [])
        final (read-edn-file (path child-dir "final.edn") nil)]
    {:tree/session-id (:session/id session)
     :tree/status (:session/status session)
     :tree/final-preview (:final/value-preview final)
     :tree/children
     (->> calls
          (keep (fn [call]
                  (when (#{:child :child-batch-item} (:call/type call))
                    (let [dir (path child-dir (:child/dir call))
                          ct (child-tree dir)]
                      (assoc ct
                             :call/id (:call/id call)
                             :edge/type (:edge/type call :spawned)
                             :child/session-id (:child/session-id call)
                             :child/dir (:child/dir call)
                             :child/status (:tree/status ct))))))
          vec)}))

(defn derive-tree [dir session calls]
  {:tree/session-id (:session/id session)
   :tree/status (:session/status session)
   :tree/children
   (->> calls
        (keep (fn [call]
                (when (#{:child :child-batch-item} (:call/type call))
                  (let [rel (:child/dir call)
                        ct (child-tree (path dir rel))]
                    (assoc ct
                           :call/id (:call/id call)
                           :edge/type (:edge/type call :spawned)
                           :child/session-id (:child/session-id call)
                           :child/dir rel
                           :child/status (:tree/status ct))))))
        vec)})

(defn rebuild-derived! [state]
  (let [{:keys [dir session messages evals calls final-value]} @state
        final-ref (when (contains? @state :final-value)
                    (value-ref! dir final-value))
        tree (derive-tree dir session calls)
        usage (empty-usage)]
    (atomic-spit! (path dir "final.edn")
                  (cond-> {:final/session-id (:session/id session)
                           :final/status (:session/status session)
                           :final/turn-count (count (filter #(= :assistant (:message/role %)) messages))
                           :final/eval-count (count evals)
                           :final/call-count (count calls)
                           :final/child-count (count (filter #(#{:child :child-batch-item} (:call/type %)) calls))
                           :final/usage usage
                           :final/tree-ref "tree.edn"}
                    final-ref (assoc :final/value-ref final-ref
                                     :final/value-preview (project-value final-value))))
    (atomic-spit! (path dir "usage.edn") usage)
    (atomic-spit! (path dir "tree.edn") tree)))

(defn flush! [state]
  (let [{:keys [dir session messages evals calls events snapshots]} @state]
    (doseq [sub ["blobs" "children"]]
      (ensure-dir! (path dir sub)))
    (atomic-spit! (path dir "session.edn") session)
    (atomic-spit! (path dir "messages.edn") messages)
    (atomic-spit! (path dir "evals.edn") evals)
    (atomic-spit! (path dir "calls.edn") calls)
    (atomic-spit! (path dir "events.edn") events)
    (atomic-spit! (path dir "snapshots.edn") snapshots)
    (rebuild-derived! state)
    state))

(defn next-counter! [state k]
  (let [v (get-in (swap! state update-in [:counters k] (fnil inc 0)) [:counters k])]
    v))

(defn add-event! [state event]
  (let [id (next-counter! state :event)]
    (swap! state update :events conj (assoc event :event/id id :event/at (time/now-str)))
    (flush! state)
    id))

(defn add-message! [state role content]
  (let [id (next-counter! state :message)
        msg {:message/id id :message/role role :message/content content}]
    (swap! state update :messages conj msg)
    (add-event! state {:event/type :message-added :message/id id})
    msg))

(defn add-call! [state call]
  (let [id (next-counter! state :call)
        call' (merge {:call/id id
                      :call/status :running
                      :call/started-at (time/now-str)}
                     call)]
    (swap! state update :calls conj call')
    (add-event! state {:event/type :call-started :call/id id})
    call'))

(defn update-call! [state call-id f & args]
  (swap! state update :calls
         (fn [calls]
           (mapv (fn [c] (if (= call-id (:call/id c))
                           (apply f c args)
                           c))
                 calls)))
  (add-event! state {:event/type :call-ended
                     :call/id call-id
                     :call/status (:call/status (first (filter #(= call-id (:call/id %)) (:calls @state))))}))

(defn add-eval! [state eval-row]
  (let [id (next-counter! state :eval)
        row (assoc eval-row :eval/id id)]
    (swap! state update :evals conj row)
    (add-event! state {:event/type :eval-ended :eval/id id :eval/status (:eval/status row)})
    row))

(defn add-snapshot! [state snapshot]
  (let [id (next-counter! state :snapshot)
        row (assoc snapshot :snapshot/id id :snapshot/created-at (time/now-str))]
    (swap! state update :snapshots conj row)
    (add-event! state {:event/type :snapshot-written :snapshot/id id})
    row))

(defn update-status! [state status]
  (swap! state update :session assoc
         :session/status status
         :session/ended-at (when (not= status :running) (time/now-str)))
  (flush! state))

(defn mark-final! [state value]
  (swap! state assoc :final-value value)
  (update-status! state :final)
  (add-event! state {:event/type :process-final}))

(defn mark-error! [state error]
  (swap! state assoc :error error)
  (update-status! state :error)
  (add-event! state {:event/type :process-error :error error}))

(defn new-state!
  [{:keys [dir id kind provider parent resumed-from forked-from]}]
  (ensure-dir! dir)
  (ensure-dir! (path dir "blobs"))
  (ensure-dir! (path dir "children"))
  (let [created (time/now-str)
        state (atom {:dir (path dir)
                     :session {:session/id (or id (session-id))
                               :session/kind (or kind :root)
                               :session/status :running
                               :session/created-at created
                               :session/ended-at nil
                               :session/artifact-version artifact-version
                               :session/surface-version surface-version
                               :session/surface surface
                               :session/provider provider
                               :session/cache {:enabled? true
                                               :scope-id (or id "pending")}
                               :session/parent parent
                               :session/resumed-from resumed-from
                               :session/forked-from forked-from}
                     :messages []
                     :evals []
                     :calls []
                     :events []
                     :snapshots []
                     :counters {:message 0 :eval 0 :call 0 :event 0 :snapshot 0 :child 0}})]
    (swap! state assoc-in [:session :session/cache :scope-id] (:session/id (:session @state)))
    (flush! state)
    (add-event! state {:event/type :session-started :session/id (:session/id (:session @state))})
    state))

(defn load-state! [dir]
  (let [messages (read-edn-file (path dir "messages.edn") [])
        evals (read-edn-file (path dir "evals.edn") [])
        calls (read-edn-file (path dir "calls.edn") [])
        events (read-edn-file (path dir "events.edn") [])
        snapshots (read-edn-file (path dir "snapshots.edn") [])
        session (read-edn-file (path dir "session.edn") {})]
    (atom {:dir (path dir)
           :session (assoc session :session/status :running :session/ended-at nil)
           :messages messages
           :evals evals
           :calls calls
           :events events
           :snapshots snapshots
           :counters {:message (apply max 0 (map :message/id messages))
                      :eval (apply max 0 (map :eval/id evals))
                      :call (apply max 0 (map :call/id calls))
                      :event (apply max 0 (map :event/id events))
                      :snapshot (apply max 0 (map :snapshot/id snapshots))
                      :child (count (filter #(#{:child :child-batch-item} (:call/type %)) calls))}})))
