(ns fractal-engine.runtime
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.time :as time])
  (:import [java.io PushbackReader StringReader StringWriter]))

(def special-symbols '#{FINAL lm map-lm rlm map-rlm attach-rlm})
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

(defn ensure-ns! [ns-sym ops]
  (create-ns ns-sym)
  (binding [*ns* (the-ns ns-sym)]
    (clojure.core/refer 'clojure.core)
    (intern *ns* 'FINAL (fn [value] (final! value)))
    (intern *ns* 'lm (:lm ops))
    (intern *ns* 'map-lm (:map-lm ops))
    (intern *ns* 'rlm (:rlm ops))
    (intern *ns* 'map-rlm (:map-rlm ops))
    (intern *ns* 'attach-rlm (:attach-rlm ops)))
  (the-ns ns-sym))

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

(defn- pretty-value [value]
  (binding [*print-length* 80
            *print-level* 8
            *print-namespace-maps* false]
    (pr-str value)))

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
  (str "Evaluation observation. Values shown here are compact projections; full live values remain in REPL vars you defined.\n\n"
       (str/join "\n\n" (map-indexed observation-row-text rows))
       (when (and (seq rows)
                  (not-any? #(= :final (:eval/status %)) rows))
         "\n\nNo FINAL was called in this batch; the current turn is still open.")))

(defn edn-safe? [value]
  (try
    (= value (edn/read-string (binding [*print-dup* false *print-readably* true] (pr-str value))))
    (catch Throwable _ false)))

(defn snapshot-vars [state ns-sym]
  (let [vars (ns-publics ns-sym)
        entries (reduce-kv
                 (fn [acc sym v]
                   (if (special-symbols sym)
                     acc
                     (let [value @v]
                       (if (edn-safe? value)
                         (assoc-in acc [:vars sym] (artifacts/value-ref! (:dir @state) value))
                         (assoc-in acc [:unresumable sym]
                                   {:class (.getName (class value))
                                    :reason :not-edn})))))
                 {:vars {} :unresumable {}}
                 vars)]
    {:snapshot/status :complete
     :snapshot/after-turn-id *current-turn-id*
     :snapshot/after-message-id (apply max 0 (map :message/id (:messages @state)))
     :snapshot/after-eval-id (apply max 0 (map :eval/id (:evals @state)))
     :snapshot/ns ns-sym
     :snapshot/vars (:vars entries)
     :snapshot/unresumable (:unresumable entries)}))

(defn restore-snapshot! [state ns-sym ops snapshot]
  (ensure-ns! ns-sym ops)
  (let [restored (atom [])
        skipped (atom [])]
    (doseq [[sym ref] (:snapshot/vars snapshot)]
      (let [value (artifacts/read-ref (:dir @state) ref)]
        (if (= ::artifacts/missing value)
          (swap! skipped conj {:var sym :reason :missing-value})
          (do (intern (the-ns ns-sym) sym value)
              (swap! restored conj sym)))))
    (doseq [[sym info] (:snapshot/unresumable snapshot)]
      (swap! skipped conj {:var sym :reason (:reason info)}))
    {:resume/restored-vars @restored
     :resume/skipped-vars @skipped
     :resume/messages (count (:messages @state))
     :resume/snapshot-id (:snapshot/id snapshot)}))
