(ns fractal-engine.store.value
  "Pure store-facing value helpers.

  These functions build identities, lookup refs, summaries, and scalar facts.
  They do not write storage and do not know about runtime sessions."
  (:require [fractal-engine.session-model :as session-model]))

(defn uid [session-id kind id]
  (str session-id ":" (name kind) ":" id))

(defn turn-ref [session-id turn-id]
  (when turn-id [:turn/uid (uid session-id :turn turn-id)]))

(defn message-ref [session-id message-id]
  (when message-id [:message/uid (uid session-id :message message-id)]))

(defn call-ref [session-id call-id]
  (when call-id [:call/uid (uid session-id :call call-id)]))

(defn eval-ref [session-id eval-id]
  (when eval-id [:eval/uid (uid session-id :eval eval-id)]))

(defn snapshot-ref [session-id snapshot-id]
  (when snapshot-id [:snapshot/uid (uid session-id :snapshot snapshot-id)]))

(defn head-ref [head-id]
  (when head-id [:head/id head-id]))

(defn session-ref [session-id]
  (when session-id [:session/id session-id]))

(defn assoc-some
  [m & kvs]
  (reduce (fn [m [k v]]
            (if (some? v) (assoc m k v) m))
          m
          (partition 2 kvs)))

(defn preview-string
  [value]
  (let [s (pr-str value)]
    (if (> (count s) 512)
      (str (subs s 0 511) "...")
      s)))

(defn value-kind [value]
  (cond
    (nil? value) :nil
    (map? value) :map
    (vector? value) :vector
    (set? value) :set
    (seq? value) :seq
    (string? value) :string
    (keyword? value) :keyword
    (symbol? value) :symbol
    (number? value) :number
    (boolean? value) :boolean
    :else :object))

(defn numeric-field
  [m ks]
  (some (fn [k]
          (let [v (get m k)]
            (when (number? v) v)))
        ks))

(defn long-field
  [m ks]
  (some-> (numeric-field m ks) long))

(defn derivation-id
  [target-session-id source-session-id source-head-id kind]
  (str target-session-id "<-" source-session-id "#" source-head-id ":" (name (or kind :restore))))

(defn derivation-record
  [target-session-id source kind created-at]
  (let [source-session-id (:source/session-id source)
        source-head-id (:source/head-id source)]
    (when (and target-session-id source-session-id source-head-id)
      {:derivation/id (derivation-id target-session-id source-session-id source-head-id kind)
       :derivation/version session-model/derivation-version
       :derivation/type (or kind :restore)
       :derivation/source-session source-session-id
       :derivation/source-head source-head-id
       :derivation/target-session target-session-id
       :derivation/created-at created-at})))
