(ns fractal.engine.kernel
  "L2 · the SCI eval kernel (03). The model's Clojure runs here — in an SCI ctx
   (one per session), NOT JVM eval. Vars def'd in one eval persist across steps
   and turns. The loop owns turn/step/message/observation appends; the kernel
   owns the per-block :eval/added append (the deliberate kernel→store edge).

   ⚠ SCI 0.8.43 fact (verified): *ns* resets to `user` after every separate
   `eval-string*` call, so a standalone `(in-ns …)` does NOT persist. We instead
   bind `sci/ns` to the session-ns object around EVERY eval — which upholds every
   invariant the spec names (vars persist, session isolation, a model `(in-ns …)`
   never strands later evals — the binding re-establishes the session ns each
   call) and is pinned by the §7 regression test.

   ⚠ SCI wraps an exception thrown inside eval in a {:type :sci/error} ExceptionInfo
   whose CAUSE is the original — so FINAL detection + err->map walk the cause chain."
  (:require [sci.core :as sci]
            [fractal.engine.capability :as capability]
            [fractal.engine.observe :as observe]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.store :as store]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Host dynamic context (03 §7) — bound INSIDE the loop, never at spawn
;; ---------------------------------------------------------------------------

(def ^:dynamic *current-turn-id* nil)
(def ^:dynamic *current-step-id* nil)
(def ^:dynamic *current-eval-id* nil)

;; ---------------------------------------------------------------------------
;; The session namespace
;; ---------------------------------------------------------------------------

(defn session-ns-sym [session-id]
  (symbol (str "fractal.session." session-id)))

(defn- the-session-ns [ctx session-id]
  (sci/find-ns ctx (session-ns-sym session-id)))

;; ---------------------------------------------------------------------------
;; Host fns injected into the ctx (03 §1)
;; ---------------------------------------------------------------------------

(defn make-FINAL
  "FINAL — the exception-based turn-return signal (03 §3)."
  []
  (fn [v] (throw (ex-info "FINAL" {:fractal/final v}))))

(defn make-inspect
  "inspect — bounded value viewer. Prints to the SCI out (so §4 capture sees it),
   returns nil. Bridges by rebinding the host *out* to @sci/out around println."
  []
  (fn [x]
    (binding [*out* @sci/out] (println (observe/inspect-text x)))
    nil))

(defn engine-fn-impls
  "The host-fn impl map handed to capability/sci-opts (assembled here, threaded
   by session/start-session!; Phase 3 adds :lm/:rlm/…)."
  []
  {:FINAL   (make-FINAL)
   :inspect (make-inspect)})

;; ---------------------------------------------------------------------------
;; Context construction (03 §1)
;; ---------------------------------------------------------------------------

(defn new-ctx
  "Build the session's SCI ctx from its capability profile + the host-fn impls.
   Creates & registers the session ns (so find-ns resolves it later); every eval
   then binds sci/ns to it."
  ([session-id capability-profile engine-fns]
   (new-ctx session-id capability-profile engine-fns {}))
  ([session-id capability-profile engine-fns surface-namespaces]
   (let [ctx (sci/init (capability/sci-opts (capability/validate-profile! capability-profile)
                                            engine-fns
                                            surface-namespaces))]
     (sci/eval-string* ctx (str "(in-ns '" (session-ns-sym session-id) ")"))
     ctx)))

;; ---------------------------------------------------------------------------
;; Block extraction (03 §2)
;; ---------------------------------------------------------------------------

(def ^:private fence-re
  ;; ```clojure / ```clj … ``` — language tag then ≥1 whitespace (newline OR a
  ;; single space, as the responder's one-line fences use), code captured lazily.
  #"(?s)```(?:clojure|clj)[ \t\r\n]+(.*?)```")

(defn extract-blocks
  "Ordered vector of code strings from fenced clojure blocks. No fence → []."
  [assistant-text]
  (->> (re-seq fence-re (or assistant-text ""))
       (map (comp str/trim second))
       (remove str/blank?)
       vec))

;; ---------------------------------------------------------------------------
;; Error mapping + FINAL unwrap (cause-chain aware — SCI wraps)
;; ---------------------------------------------------------------------------

(defn- cause-chain [^Throwable e]
  (take-while some? (iterate (fn [^Throwable t] (.getCause t)) e)))

(defn- unwrap-final
  "Search e and its cause chain for an ex-data carrying :fractal/final."
  [e]
  (some (fn [t]
          (let [d (when (instance? clojure.lang.IExceptionInfo t) (ex-data t))]
            (when (and d (contains? d :fractal/final))
              {:value (:fractal/final d)})))
        (cause-chain e)))

(defn err->map
  "The uniform namespaced error map (02 'The error map'). Preserves an explicit
   :error/type set anywhere in the cause chain (deadline/provider/capability),
   else :fractal/eval-error; message from the (already-informative) wrapper."
  [e]
  (let [infos (keep #(when (instance? clojure.lang.IExceptionInfo %) (ex-data %))
                    (cause-chain e))
        d     (first infos)]
    {:error/type    (or (some :error/type infos) :fractal/eval-error)
     :error/message (or (ex-message e) (str (class e)))
     :error/data    (cond-> {}
                      (:line d) (assoc :line (:line d) :column (:column d)))}))

;; ---------------------------------------------------------------------------
;; Eval one block (03 §2 — REPL semantics)
;; ---------------------------------------------------------------------------

(defn count-forms
  "Count top-level forms with SCI's OWN parser — exactly what eval reads."
  [ctx code]
  (let [rdr (sci/reader code)]
    (loop [n 0]
      (if (= :sci.core/eof (sci/parse-next ctx rdr)) n (recur (inc n))))))

(def ^:private cap-chars 4000)

(defn- cap-str [^String s]
  (if (and s (> (count s) cap-chars))
    (str (subs s 0 cap-chars) " … [truncated " (- (count s) cap-chars) " chars]")
    s))

(defn- elapsed-ms [t0] (long (/ (- (System/nanoTime) t0) 1000000)))

(defn- build-eval-record
  "A RAW eval record: the durable 02 §1 fields PLUS the transient (never
   persisted) :eval/raw-value (+ :eval/raw-final on FINAL)."
  [code block-index out sw ew forms t0]
  (let [status (cond (:final out) :final (:error out) :error :else :ok)]
    (cond-> {:eval/turn-id     *current-turn-id*
             :eval/step-id     *current-step-id*
             :eval/block-index block-index
             :eval/code-or-ref code
             :eval/status      status
             :eval/stdout      (cap-str (str sw))
             :eval/stderr      (cap-str (str ew))
             :eval/forms-count forms
             :eval/elapsed-ms  (elapsed-ms t0)
             :eval/error       (when (= :error status) (:error out))}
      (= :ok status)    (assoc :eval/raw-value (:value out))
      (= :final status) (assoc :eval/raw-value (:value out) :eval/raw-final (:value out)))))

(defn- forced
  "Realize every (possibly nested) lazy member of v INSIDE the eval guard, with
   the same bounds the persistence path uses (payload-io/edn-safe: ≤100k elems
   per seq, depth-capped) — so a lazy value whose realization throws degrades
   into the normal recoverable :eval/error instead of escaping run-turn! later,
   when the observation/persistence path first realizes it. Realization caches
   in the seqs themselves; v is returned raw (identity kept for FINAL/preview)."
  [v]
  (payload-io/edn-safe v)
  v)

(defn eval-block
  "Evaluate one fenced block in the session's SCI ctx → a RAW eval record.
   sci/eval-string* REPL-interleaves the block's forms and returns the LAST
   form's value (verified). sci/out/err capture model IO; sci/ns pins the
   session ns. The value (FINAL's included) is bounded-FORCED inside the guard
   — a poisoned lazy is an :eval/error here, never a turn-killing escape."
  [handle code block-index]
  (let [ctx    (deref (:sci-ctx handle))
        the-ns (the-session-ns ctx (:session-id handle))
        sw     (java.io.StringWriter.)
        ew     (java.io.StringWriter.)
        t0     (System/nanoTime)
        forms  (try (count-forms ctx code) (catch Throwable _ 0))
        out    (try
                 (sci/binding [sci/ns the-ns sci/out sw sci/err ew]
                   {:value (forced (sci/eval-string* ctx code))})
                 (catch clojure.lang.ExceptionInfo e
                   (or (when-let [f (unwrap-final e)]
                         (try {:final true :value (forced (:value f))}
                              (catch Throwable e' {:error (err->map e')})))
                       {:error (err->map e)}))
                 (catch Throwable e {:error (err->map e)}))]
    (build-eval-record code block-index out sw ew forms t0)))

;; ---------------------------------------------------------------------------
;; The kernel↔store edge (03 §2, GD14/GD15)
;; ---------------------------------------------------------------------------

(defn- append-eval!
  "Intern the raw value → :eval/result-ref, compute the inline preview, strip the
   transient raw fields, then append the durable :eval/added (the store stamps
   :eval/id == the peeked id)."
  [handle raw-rec]
  (let [store (:store handle)
        sid   (:session-id handle)
        v     (:eval/raw-value raw-rec)
        durable (-> raw-rec
                    (dissoc :eval/raw-value :eval/raw-final)
                    (update :eval/code-or-ref #(payload-io/maybe-intern store % {:payload/kind :code}))
                    (assoc :eval/result-ref     (payload-io/maybe-intern store v {:payload/kind :eval-result})
                           :eval/result-preview (observe/value-display v observe/ok-fit)))]
    (store/append-event! store sid {:event/type :eval/added :eval durable})))

;; ---------------------------------------------------------------------------
;; Eval the batch (03 §2 — strict semantics)
;; ---------------------------------------------------------------------------

(defn eval-batch
  "Evaluate a step's blocks as a batch. A block that errors STOPS the batch; a
   block that calls FINAL ends the turn. The kernel appends each :eval/added.
   Returns RAW recs (raw values intact for render-observation + commit-turn!)."
  [handle turn-id blocks]
  (let [store (:store handle)
        sid   (:session-id handle)]
    (loop [i 0 recs []]
      (if-let [code (nth blocks i nil)]
        (let [eid (store/peek-next-id store sid :eval)
              rec (binding [*current-turn-id* turn-id
                            *current-eval-id* eid]
                    (eval-block handle code i))]
          (append-eval! handle rec)
          (case (:eval/status rec)
            :final {:eval-records (conj recs rec)
                    :final {:final? true :value (:eval/raw-final rec)}
                    :status :final}
            :error {:eval-records (conj recs rec) :status :error}
            (recur (inc i) (conj recs rec))))
        {:eval-records recs :status :ok}))))

;; ---------------------------------------------------------------------------
;; Snapshot / restore (03 §6 — Merkle-aligned; restore is resume/fork ONLY)
;; ---------------------------------------------------------------------------

(defn- ns-var-values
  "{\"name\" → host-value …} for the session ns, via a SCI eval over `*ns*`
   (avoids the denied find-ns; sci/ns is bound to the session ns). ⛔ Every core
   symbol is FULLY-QUALIFIED so a model `(def name …)`/`(def into …)` in the
   session ns cannot shadow it and corrupt/crash the snapshot — the model cannot
   def a namespaced symbol into clojure.core (verified)."
  [ctx session-id]
  (sci/binding [sci/ns (the-session-ns ctx session-id)]
    (sci/eval-string* ctx
      (str "(clojure.core/into {} (clojure.core/for "
           "[[s v] (clojure.core/ns-interns clojure.core/*ns*)] "
           "[(clojure.core/name s) (clojure.core/deref v)]))"))))

(def ^:private restore-print-length
  "Cap on elements printed in the restorable? round-trip — so an unbounded/
   infinite lazy seq is bounded (and correctly marked :unrestorable) instead of
   hanging pr-str (the snapshot infinite-seq sink)."
  100000)

(defn- restorable? [v]
  (try (= v (binding [*read-eval* false
                      *print-length* restore-print-length
                      *print-level* 200]
              (read-string (pr-str v))))
       (catch Throwable _ false)))

(defn- unrestorable-reason [v]
  (cond
    (fn? v)                            "function"
    (instance? clojure.lang.IDeref v)  "deref-able (atom/ref/agent)"
    :else (str "non-round-trippable " (.getSimpleName (class v)))))

(defn snapshot-vars
  "A canonical, content-addressable snapshot of the session ns's vars (03 §6).
   Unrestorable vars are recorded INSIDE the snapshot so it is a faithful,
   hashable record and equal REPL states dedup."
  [ctx session-id]
  (binding [*print-length* nil *print-level* nil
            *print-namespace-maps* false *print-meta* false]
    {:vars/version 1
     :vars (into (sorted-map)
                 (for [[nm v] (ns-var-values ctx session-id)]
                   [nm (if (restorable? v)
                         {:status :ok :value v}
                         {:status :unrestorable :reason (unrestorable-reason v)})]))}))

(defn- clear-ns-vars! [ctx session-id]
  (sci/binding [sci/ns (the-session-ns ctx session-id)]
    (sci/eval-string* ctx
      (str "(clojure.core/doseq [s (clojure.core/keys "
           "(clojure.core/ns-interns clojure.core/*ns*))] "
           "(clojure.core/ns-unmap clojure.core/*ns* s))"))))

(defn restore-vars!
  "Phase 2/4 ONLY (resume/fork). Clear the session ns, then sci/intern each
   :ok var DIRECTLY (⛔ never via a `(def …)` eval — a list/symbol value would be
   re-evaluated and corrupt; intern binds the value as data, 03 §6)."
  [ctx session-id snapshot]
  (clear-ns-vars! ctx session-id)
  (doseq [[nm {:keys [status value]}] (:vars snapshot) :when (= :ok status)]
    (sci/intern ctx (the-session-ns ctx session-id) (symbol nm) value)))
