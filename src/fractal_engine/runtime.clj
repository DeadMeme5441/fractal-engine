(ns fractal-engine.runtime
  "The eval kernel: turn model-emitted Clojure into a status map, and project
  values/observations for the model to read. Persistence (snapshot/restore) is a
  separate concern and lives in `snapshot.clj`."
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.time :as time])
  (:import [java.io PushbackReader StringReader StringWriter]))

(def ^:dynamic *current-eval-id* nil)
(def ^:dynamic *current-turn-id* nil)
(def observation-string-limit 4000)

(defn session-ns-symbol [session-id]
  (symbol (str "fractal.session." (str/replace session-id #"[^A-Za-z0-9_]" "_"))))

(defn extract-clojure-blocks [text]
  (let [m (re-matcher #"(?s)```(?:clojure|clj)\s*\n(.*?)```" (or text ""))]
    (loop [blocks []]
      (if (.find m)
        (recur (conj blocks (.group m 1)))
        blocks))))

(defn read-forms [code]
  (let [eof (Object.)]
    (with-open [r (PushbackReader. (StringReader. code))]
      (binding [*read-eval* false]
        (loop [forms []]
          (let [form (read {:eof eof} r)]
            (if (identical? eof form)
              forms
              (recur (conj forms form)))))))))

(defn project-output-string [s]
  (let [s (str s)]
    (if (> (count s) observation-string-limit)
      (str (subs s 0 observation-string-limit)
           "\n... [truncated " (- (count s) observation-string-limit) " chars]")
      s)))

(defn final! [value]
  (throw (ex-info "FINAL" {:fractal/final value})))

(defn clear-ns! [ns-sym]
  (let [ns (create-ns ns-sym)]
    (doseq [[sym _] (ns-publics ns)]
      (ns-unmap ns sym))))

(defn ensure-ns!
  ([ns-sym ops] (ensure-ns! ns-sym ops {}))
  ([ns-sym ops {:keys [clear?]}]
   (when clear?
     (clear-ns! ns-sym))
   (create-ns ns-sym)
   (binding [*ns* (the-ns ns-sym)]
     (clojure.core/refer 'clojure.core)
     (intern *ns* 'FINAL (fn [value] (final! value)))
     (intern *ns* 'lm (:lm ops))
     (intern *ns* 'map-lm (:map-lm ops))
     (intern *ns* 'rlm (:rlm ops))
     (intern *ns* 'map-rlm (:map-rlm ops))
     (intern *ns* 'attach-rlm (:attach-rlm ops)))
   (the-ns ns-sym)))

(defn throwable-data [^Throwable t]
  (merge {:error/type :eval/exception
          :error/class (.getName (class t))
          :error/message (.getMessage t)}
         (ex-data t)))

(defn eval-code [ns-sym code]
  (let [out (StringWriter.)
        err (StringWriter.)
        started (time/now-str)
        started-ns (System/nanoTime)
        forms-count (volatile! nil)]
    (binding [*ns* (the-ns ns-sym)
              *out* out
              *err* err]
      (try
        (let [forms (read-forms code)
              _ (vreset! forms-count (count forms))
              result (loop [forms forms
                            last-value nil]
                       (if-let [form (first forms)]
                         (recur (rest forms) (eval form))
                         last-value))]
          {:eval/status :ok
           :eval/forms-count @forms-count
           :eval/value (artifacts/project-value result)
           :eval/raw-value result
           :eval/stdout (project-output-string out)
           :eval/stderr (project-output-string err)
           :eval/started-at started
           :eval/ended-at (time/now-str)
           :eval/elapsed-ms (quot (- (System/nanoTime) started-ns) 1000000)})
        (catch clojure.lang.ExceptionInfo e
          (if (contains? (ex-data e) :fractal/final)
            (let [v (:fractal/final (ex-data e))]
              {:eval/status :final
               :eval/forms-count @forms-count
               :eval/final-value (artifacts/project-value v)
               :eval/raw-final-value v
               :eval/stdout (project-output-string out)
               :eval/stderr (project-output-string err)
               :eval/started-at started
               :eval/ended-at (time/now-str)
               :eval/elapsed-ms (quot (- (System/nanoTime) started-ns) 1000000)})
            {:eval/status :error
             :eval/forms-count @forms-count
             :eval/error (throwable-data e)
             :eval/stdout (project-output-string out)
             :eval/stderr (project-output-string err)
             :eval/started-at started
             :eval/ended-at (time/now-str)
             :eval/elapsed-ms (quot (- (System/nanoTime) started-ns) 1000000)}))
        (catch Throwable t
          {:eval/status :error
           :eval/forms-count @forms-count
           :eval/error (throwable-data t)
           :eval/stdout (project-output-string out)
           :eval/stderr (project-output-string err)
           :eval/started-at started
           :eval/ended-at (time/now-str)
           :eval/elapsed-ms (quot (- (System/nanoTime) started-ns) 1000000)})))))

;; ── intuitive rendering of projected values (what the model reads) ────────────
;; `artifacts/project-value` produces a compact EDN structure where bulky values
;; become wrapper maps (`{:value/type :string ...}`, `:seq`, `:set`, `:object`).
;; Rendered raw, a truncated string nested in a map looks like a literal map and
;; the model mis-reads its own data. `render-node` turns those wrappers into
;; self-describing «inspector» annotations so the kind/size of every value is
;; unambiguous, while ordinary maps/vectors still read as ordinary EDN.

(declare render-node)

(defn- string-wrapper? [m]
  (and (= :string (:value/type m)) (contains? m :value/preview)))
(defn- set-wrapper? [m]
  (and (= :set (:value/type m)) (contains? m :value/items)))
(defn- seq-wrapper? [m]
  (and (= :seq (:value/type m)) (contains? m :value/items)))
(defn- object-wrapper? [m]
  (and (= :object (:value/type m)) (contains? m :class)))

(defn- render-entry [[k v]]
  (str (render-node k) " " (render-node v)))

(defn- render-node [v]
  (cond
    (nil? v) "nil"
    (string? v) (pr-str v)
    (map? v)
    (cond
      (string-wrapper? v) (str (pr-str (:value/preview v))
                               " «string, " (:value/count v) " chars»")
      (set-wrapper? v) (str "#{" (str/join " " (map render-node (:value/items v))) "}"
                            " «set, " (:value/count v) " items»")
      (seq-wrapper? v) (str "(" (str/join " " (map render-node (:value/items v))) ")"
                            " «seq, " (count (:value/items v)) " shown»")
      (object-wrapper? v) (str "«" (:class v) ": " (:preview v) "»")
      (:truncated? v) (str "«…truncated"
                           (cond (:reason v) (str " (" (name (:reason v)) ")")
                                 (:remaining v) (str " +" (:remaining v) " more")
                                 :else "") "»")
      :else (str "{" (str/join ", " (map render-entry v)) "}"))
    (vector? v)
    (let [trunc (when (and (map? (peek v)) (:truncated? (peek v))) (peek v))
          items (if trunc (pop v) v)]
      (str "[" (str/join " " (map render-node items)) "]"
           (when trunc (str " «+" (:remaining trunc) " more items»"))))
    :else (pr-str v)))

(defn- top-tag
  "A trailing kind/size annotation for the whole value. Skipped for values that
  already self-describe (string/seq/set/object wrappers) or are plain scalars."
  [v]
  (cond
    (vector? v) (let [trunc (when (and (map? (peek v)) (:truncated? (peek v))) (peek v))
                      n (if trunc (dec (count v)) (count v))]
                  ;; remainder, if any, is shown inline by render-node; don't repeat it here
                  (str "vector, " n " items" (when trunc " shown")))
    (and (map? v)
         (not (string-wrapper? v)) (not (set-wrapper? v))
         (not (seq-wrapper? v)) (not (object-wrapper? v)) (not (:truncated? v)))
    (str "map, " (count v) " entries")
    :else nil))

(defn- pretty-value [value]
  (binding [*print-length* 80
            *print-level* 8
            *print-namespace-maps* false]
    (let [body (render-node value)
          tag  (top-tag value)]
      (if tag (str body "  «" tag "»") body))))

(defn- observation-header [idx row]
  (str "(eval " (inc idx)
       " id=" (:eval/id row)
       " elapsed=" (or (:eval/elapsed-ms row) 0) "ms"
       (when-let [n (:eval/forms-count row)]
         (str " forms=" n))
       " status=" (name (:eval/status row))
       ")"))

(defn- observation-row-text [idx row]
  (let [stdout (str (:eval/stdout row))
        stderr (str (:eval/stderr row))
        lines (cond-> [(observation-header idx row)]
                (seq stdout) (conj (str "stdout:\n" (str/trimr stdout)))
                (seq stderr) (conj (str "stderr:\n" (str/trimr stderr))))]
    (str/join
     "\n"
     (case (:eval/status row)
       :ok (conj lines (str "=> " (pretty-value (:eval/value row))))
       :final (conj lines (str "FINAL=> " (pretty-value (:eval/final-value row))))
       :error (conj lines (str "error=> " (pretty-value (:eval/error row))))
       (conj lines (str "=> " (pretty-value (select-keys row [:eval/status :eval/error]))))))))

(defn observation [rows]
  (str "Evaluation observation. Values shown here are compact projections; full live values remain in your REPL vars. «...» notes annotate a value's kind and size -- e.g. \"text...\" «string, N chars» is one whole string truncated for display, not a map.\n\n"
       (str/join "\n\n" (map-indexed observation-row-text rows))
       (when (and (seq rows)
                  (not-any? #(= :final (:eval/status %)) rows))
         "\n\nNo FINAL was called in this batch; the current turn is still open.")))
