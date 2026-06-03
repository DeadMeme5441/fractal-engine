(ns fractal-engine.event-log
  "Read-side helpers for the compact session event log.

  The event log is audit truth: append order, causality, row identity, and payload
  references. It is not the restore source; restore dereferences immutable head
  state roots. This namespace makes the audit surface explicit without replaying
  model calls, evals, or payload blobs."
  (:require [clojure.string :as str]))

(defn- id-str [x]
  (when (some? x) (str x)))

(defn- row-ref-value
  [kind id]
  (when-let [id (id-str id)]
    {:ref/kind kind :ref/id id}))

(defn- index-by
  [id-f rows]
  (into {}
        (keep (fn [row]
                (when-let [id (id-f row)]
                  [(str id) row])))
        rows))

(defn- indexes
  [history]
  {:message (index-by :message/id (:messages history))
   :turn (index-by :turn/id (:turns history))
   :eval (index-by :eval/id (:evals history))
   :call (index-by :call/id (:calls history))
   :snapshot (index-by :snapshot/id (:snapshots history))
   :head (index-by :head/id (:heads history))
   :invocation (index-by :invocation/id (:invocations history))})

(defn row-ref
  "Return the compact row reference named by an event row."
  [event]
  (row-ref-value (:event/row-kind event) (:event/row-id event)))

(defn- row-for
  [idx event]
  (case (:event/row-kind event)
    :message (get-in idx [:message (:event/row-id event)])
    :turn (get-in idx [:turn (:event/row-id event)])
    :eval (get-in idx [:eval (:event/row-id event)])
    :call (get-in idx [:call (:event/row-id event)])
    :snapshot (get-in idx [:snapshot (:event/row-id event)])
    :head (get-in idx [:head (:event/row-id event)])
    :invocation (get-in idx [:invocation (:event/row-id event)])
    nil))

(defn- compact-row
  [row keys]
  (not-empty (select-keys row keys)))

(defn- row-summary
  [event row]
  (case (:event/row-kind event)
    :session
    (str "session " (or (:event/status event) (:event/session event)))

    :session-ref
    (str "current head -> " (:event/head-id event))

    :message
    (format "message %s #%s"
            (or (:message/role row) "?")
            (or (:message/id row) (:event/row-id event)))

    :turn
    (format "turn #%s %s"
            (or (:turn/id row) (:event/turn-id event))
            (or (:event/status event) (:turn/status row) ""))

    :eval
    (format "eval #%s %s"
            (or (:eval/id row) (:event/eval-id event))
            (or (:eval/status row) (:event/status event) ""))

    :call
    (format "call #%s %s %s"
            (or (:call/id row) (:event/call-id event))
            (or (:call/type row) "?")
            (or (:event/status event) (:call/status row) ""))

    :snapshot
    (format "snapshot #%s"
            (or (:snapshot/id row) (:event/snapshot-id event)))

    :head
    (format "head %s %s"
            (or (:head/id row) (:event/head-id event))
            (or (:head/status row) (:event/status event) ""))

    :restore
    (str "restore from "
         (or (:event/source-head-id event) (:event/source-session-id event)))

    :invocation
    (format "invocation %s %s"
            (or (:invocation/id row) (:event/invocation-id event))
            (or (:event/status event) (:invocation/status row) ""))

    (str (name (:event/type event)))))

(defn- causal-refs
  [event row]
  (case (:event/type event)
    :session/started
    []

    :session/status
    []

    :session/error
    []

    :session/final
    []

    :session/restored
    (cond-> []
      (:event/source-session-id event) (conj (row-ref-value :session (:event/source-session-id event)))
      (:event/source-head-id event) (conj (row-ref-value :head (:event/source-head-id event)))
      (:event/snapshot-id event) (conj (row-ref-value :snapshot (:event/snapshot-id event))))

    :message/added
    (cond-> []
      (:message/turn-id row) (conj (row-ref-value :turn (:message/turn-id row)))
      (:message/call-id row) (conj (row-ref-value :call (:message/call-id row))))

    :turn/started
    (cond-> []
      (:turn/head-before row) (conj (row-ref-value :head (:turn/head-before row))))

    :turn/put
    (cond-> []
      (:turn/id row) (conj (row-ref-value :turn (:turn/id row)))
      (:turn/head-before row) (conj (row-ref-value :head (:turn/head-before row))))

    :eval/added
    (cond-> []
      (:eval/turn-id row) (conj (row-ref-value :turn (:eval/turn-id row)))
      (:eval/message-id row) (conj (row-ref-value :message (:eval/message-id row)))
      (:eval/call-id row) (conj (row-ref-value :call (:eval/call-id row))))

    :call/started
    (cond-> []
      (:call/turn-id row) (conj (row-ref-value :turn (:call/turn-id row)))
      (:call/parent-eval-id row) (conj (row-ref-value :eval (:call/parent-eval-id row))))

    :call/put
    (cond-> []
      (:call/id row) (conj (row-ref-value :call (:call/id row)))
      (:call/turn-id row) (conj (row-ref-value :turn (:call/turn-id row)))
      (:call/parent-eval-id row) (conj (row-ref-value :eval (:call/parent-eval-id row)))
      (:child/logical-session-id row) (conj (row-ref-value :session (:child/logical-session-id row)))
      (:child/session-id row) (conj (row-ref-value :session (:child/session-id row))))

    :snapshot/added
    (cond-> []
      (or (:snapshot/turn-id row) (:event/turn-id event))
      (conj (row-ref-value :turn (or (:snapshot/turn-id row) (:event/turn-id event))))
      (or (:snapshot/eval-id row) (:event/eval-id event))
      (conj (row-ref-value :eval (or (:snapshot/eval-id row) (:event/eval-id event)))))

    :head/created
    (cond-> []
      (:head/basis row)
      (conj (row-ref-value :head (:head/basis row)))
      (or (:head/turn-id row) (:event/turn-id event))
      (conj (row-ref-value :turn (or (:head/turn-id row) (:event/turn-id event))))
      (or (:head/snapshot-id row) (:event/snapshot-id event))
      (conj (row-ref-value :snapshot (or (:head/snapshot-id row) (:event/snapshot-id event)))))

    :session/ref-updated
    (cond-> []
      (:event/head-id event) (conj (row-ref-value :head (:event/head-id event))))

    :invocation/started
    (cond-> []
      (:invocation/call-id row) (conj (row-ref-value :call (:invocation/call-id row)))
      (:caller/head-before row) (conj (row-ref-value :head (:caller/head-before row)))
      (:callee/head-before row) (conj (row-ref-value :head (:callee/head-before row))))

    :invocation/completed
    (cond-> [(row-ref-value :invocation (:event/invocation-id event))]
      (:callee/head-after row) (conj (row-ref-value :head (:callee/head-after row))))

    :invocation/failed
    [(row-ref-value :invocation (:event/invocation-id event))]

    :invocation/caller-head-recorded
    (cond-> [(row-ref-value :invocation (:event/invocation-id event))]
      (:caller/head-after row) (conj (row-ref-value :head (:caller/head-after row))))

    (cond-> []
      (:event/turn-id event) (conj (row-ref-value :turn (:event/turn-id event)))
      (:event/call-id event) (conj (row-ref-value :call (:event/call-id event)))
      (:event/invocation-id event) (conj (row-ref-value :invocation (:event/invocation-id event)))
      (:event/head-id event) (conj (row-ref-value :head (:event/head-id event)))
      (:event/snapshot-id event) (conj (row-ref-value :snapshot (:event/snapshot-id event))))))

(defn- with-event-status [row event status-key]
  (cond-> row
    (:event/status event) (assoc status-key (:event/status event))))

(defn- row-metadata
  [event row]
  (case (:event/row-kind event)
    :message (compact-row row [:message/id :message/role :message/turn-id :message/call-id
                               :message/char-count])
    :turn (some-> (compact-row row [:turn/id :turn/status :turn/head-before :turn/head-after])
                  (with-event-status event :turn/status))
    :eval (compact-row row [:eval/id :eval/status :eval/turn-id :eval/message-id :eval/call-id])
    :call (some-> (compact-row row [:call/id :call/type :call/status :call/turn-id :call/parent-eval-id
                                    :call/model :call/provider :child/session-id :child/logical-session-id])
                  (with-event-status event :call/status))
    :snapshot (compact-row row [:snapshot/id :snapshot/kind :snapshot/turn-id :snapshot/eval-id
                                :snapshot/var-count])
    :head (compact-row row [:head/id :head/basis :head/status :head/turn-id :head/snapshot-id
                            :head/fingerprint])
    :invocation (some-> (compact-row row [:invocation/id :invocation/type :invocation/status
                                          :invocation/call-id :caller/session :callee/session
                                          :caller/head-before :caller/head-after
                                          :callee/head-before :callee/head-after])
                        (with-event-status event :invocation/status))
    nil))

(defn- event->trace-row
  [idx event]
  (let [row (row-for idx event)
        causes (vec (remove nil? (causal-refs event row)))]
    (cond-> {:event/id (:event/id event)
             :event/type (:event/type event)
             :event/session (:event/session event)
             :event/at (:event/at event)
             :trace/row (row-ref event)
             :trace/summary (row-summary event row)
             :trace/causes causes}
      (:event/status event) (assoc :event/status (:event/status event))
      (:event/payload-ref event) (assoc :event/payload-ref (:event/payload-ref event))
      (row-metadata event row) (assoc :trace/row-data (row-metadata event row)))))

(defn trace
  "Return compact audit rows with derived causal refs.

  `history` is a `projection/history-view` shaped map. The output deliberately
  contains ids, statuses, summaries, row refs, and blob refs, not raw payload
  content."
  [history]
  (let [idx (indexes history)]
    (loop [events (:events history)
           latest-by-ref {}
           out []]
      (if-let [event (first events)]
        (let [row (event->trace-row idx event)
              cause-event-ids (vec (keep #(get latest-by-ref [(:ref/kind %) (:ref/id %)])
                                         (:trace/causes row)))
              row' (cond-> row
                     (seq cause-event-ids) (assoc :trace/cause-event-ids cause-event-ids))
              r (:trace/row row')
              latest' (cond-> latest-by-ref
                        (and (:ref/kind r) (:ref/id r))
                        (assoc [(:ref/kind r) (:ref/id r)] (:event/id row')))]
          (recur (rest events) latest' (conj out row')))
        out))))

(defn event-at
  [trace event-id]
  (first (filter #(= (long event-id) (long (:event/id %))) trace)))

(defn causal-chain
  "Return causal ancestors plus the requested event, in causal order."
  [trace event-id]
  (let [by-id (into {} (map (juxt :event/id identity)) trace)]
    (letfn [(walk [id seen]
              (if (contains? seen id)
                []
                (let [row (get by-id id)
                      seen' (conj seen id)]
                  (vec (concat (mapcat #(walk % seen') (:trace/cause-event-ids row))
                               (when row [row]))))))]
      (vec (distinct (walk (long event-id) #{}))))))

(defn format-row
  [row]
  (let [causes (:trace/cause-event-ids row)
        cause-text (when (seq causes)
                     (str "  <- " (str/join "," causes)))]
    (format "%04d %-32s %-14s %s%s"
            (:event/id row)
            (name (:event/type row))
            (name (get-in row [:trace/row :ref/kind] :none))
            (:trace/summary row)
            (or cause-text ""))))

(defn format-trace
  [rows]
  (str/join "\n" (map format-row rows)))
