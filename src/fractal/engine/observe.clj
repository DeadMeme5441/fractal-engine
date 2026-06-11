(ns fractal.engine.observe
  "L1 · observation rendering (03 §5, the v24 doctrine). The model is NOT handed
   values — after a batch it gets, per block: captured stdout, the form status,
   and the return value rendered FIT-OR-STUB. To look inside a stub it acts —
   `(inspect x)` (orchard-backed) or slices the live var."
  (:require [clojure.string :as str]
            [orchard.inspect :as oi]))

(def ok-fit    "Whole-value cap for an :ok return value."    400)
(def final-fit "Whole-value cap for a FINAL value."          1200)

;; ---------------------------------------------------------------------------
;; fit-or-stub (03 §5)
;; ---------------------------------------------------------------------------

(defn- bounded-writer
  "A Writer that appends into `sb` but throws once it would exceed `cap` — so a
   huge (or partially-realized) value aborts EARLY instead of being fully built."
  [^StringBuilder sb cap]
  (proxy [java.io.Writer] []
    (write
      ([x]
       (cond
         (integer? x) (.append sb (char (int x)))
         (string? x)  (.append sb ^String x)
         :else        (.append sb (String. ^chars x)))
       (when (> (.length sb) cap) (throw (ex-info "fit-cap-exceeded" {}))))
      ([cbuf off len]
       (.append sb (String. ^chars cbuf (int off) (int len)))
       (when (> (.length sb) cap) (throw (ex-info "fit-cap-exceeded" {})))))
    (flush [])
    (close [])))

(defn- try-fit
  "The readable EDN of v if it prints within `cap` chars, else nil (streamed —
   never materializes a value larger than the cap)."
  [v cap]
  (let [sb (StringBuilder.)]
    (try
      (binding [*out*                  (bounded-writer sb cap)
                *print-length*         nil
                *print-level*          nil
                *print-namespace-maps* false
                *print-meta*           false]
        (pr v))
      (str sb)
      (catch Throwable _ nil))))

(defn- stub-type-name [v]
  (cond
    (keyword? v) "keyword" (symbol? v) "symbol" (number? v) "number"
    (char? v)    "char"    (boolean? v) "boolean"
    :else (.getSimpleName (class v))))

(def ^:private seq-probe-cap
  "Upper bound on elements counted when sizing a (possibly lazy/infinite) seq —
   so a stub never fully realizes an unbounded value (the infinite-seq hang)."
  100000)

(defn- seq-size-label
  "`N items` for a finite seq, `≥CAP items` when realization is clipped at the
   probe cap (an unbounded/infinite seq)."
  [v kind]
  (let [n (bounded-count (inc seq-probe-cap) v)]
    (if (> n seq-probe-cap)
      (str "«" kind ", ≥" seq-probe-cap " items»")
      (str "«" kind ", " n " items»"))))

(defn value-stub
  "The one-line `«type, size»` stub (kind + size only, no contents). Pinned
   labels (GD36); nil is never a stub. Seq sizing is BOUNDED (never realizes an
   unbounded seq)."
  [v]
  (cond
    (nil? v)     "nil"
    (vector? v)  (str "«vector, " (count v) " items»")
    (set? v)     (str "«set, " (count v) " items»")
    (map? v)     (str "«map, " (count v) " entries»")
    (string? v)  (str "«string, " (count v) " chars»")
    (instance? clojure.lang.LazySeq v) (seq-size-label v "lazy-seq")
    (list? v)    (str "«list, " (count v) " items»")
    (seq? v)     (seq-size-label v "list")
    :else        (str "«" (stub-type-name v) "»")))

(defn value-display
  "Fit-or-stub: the whole value when it fits `cap`, else its `«type, size»`
   stub. nil → \"nil\"."
  [v cap]
  (or (try-fit v cap) (value-stub v)))

;; ---------------------------------------------------------------------------
;; inspect — orchard-backed bounded viewer (verified against orchard 0.41.0)
;; ---------------------------------------------------------------------------

(def inspect-config
  {:page-size 25 :max-atom-length 120 :max-value-length 3000
   :max-coll-size 20 :max-nested-depth 4})

(defn- render-segments
  "Walk orchard's flat :rendered seq: bare strings emit verbatim; (:newline) →
   \"\\n\"; (:value display-string nav-idx) → the display-string."
  [segs]
  (apply str
         (map (fn [s]
                (cond
                  (string? s) s
                  (and (sequential? s) (= :newline (first s))) "\n"
                  (and (sequential? s) (= :value (first s))) (second s)
                  :else ""))
              segs)))

(defn- strip-after [^String s ^String marker]
  (if-let [i (str/index-of s marker)] (subs s 0 i) s))

(defn inspect-text
  "A bounded, paginated textual inspection of x (class, count, a window of the
   contents, `…` for elisions), with orchard's interactive key-binding chrome
   stripped."
  [x]
  (-> (oi/start inspect-config x)
      :rendered
      render-segments
      (strip-after "\n--- View mode")
      str/trimr))

;; ---------------------------------------------------------------------------
;; The combined observation (03 §5) + the no-fence nudge (GD36)
;; ---------------------------------------------------------------------------

(def no-fence-nudge
  (str "No clojure block found in your reply. Emit a ```clojure …``` fenced block "
       "to run code, or call (FINAL v) inside one to end the turn."))

(defn- block-section [rec ok-cap final-cap]
  (let [bi     (:eval/block-index rec)
        out    (:eval/stdout rec)
        header (str "Block " bi ":")
        body   (case (:eval/status rec)
                 :error (str "ERROR: " (:error/message (:eval/error rec)))
                 :final (str "=> " (value-display (:eval/raw-final rec) final-cap) "   (FINAL)")
                 (str "=> " (value-display (:eval/raw-value rec) ok-cap)))]
    (str header "\n"
         (when (seq out) (str out (when-not (str/ends-with? out "\n") "\n")))
         body)))

(defn render-observation
  "One combined observation for the whole batch, fit-or-stub from RAW values
   (03 §5). `opts :final?` suppresses the still-open trailer; `opts :ok-fit` /
   `:final-fit` override the whole-value caps (cfg `:observe` — the observation
   window is the lever that shapes a blind-root discipline)."
  [eval-records {:keys [final?] :as opts}]
  (let [ok-cap    (or (:ok-fit opts) ok-fit)
        final-cap (or (:final-fit opts) final-fit)
        text (str/join "\n\n" (map #(block-section % ok-cap final-cap) eval-records))]
    (if final?
      text
      (str text (when (seq eval-records) "\n\n") "No FINAL was called; the turn is still open."))))
