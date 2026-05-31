(ns fractal-eval.scoring
  "Shared, deterministic scoring helpers. Scoring is exact Clojure on purpose: the
  engine may use models to *produce* an answer, but whether that answer is right is
  computed here with no model in the loop — that is the whole point of an exact-
  ground-truth benchmark.

  `extract-answer` pulls the predicted answer out of whatever the engine (or the
  flat baseline) returned. The engine is asked to FINAL `{:answer X ...}`; a flat
  baseline returns raw text. Both funnel through here."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn normalize-text [s]
  (when (some? s)
    (-> (str s)
        str/lower-case
        (str/replace #"[\p{Punct}]" " ")
        (str/replace #"\s+" " ")
        str/trim)))

(defn squad-normalize
  "SQuAD-style normalization used by FanOutQA's string metrics: lowercase, drop the
  articles a/an/the, punctuation→space, collapse whitespace. Used for the loose/strict
  reference-string containment check so trivial surface differences don't fail a match."
  [s]
  (-> (or s "") str str/lower-case
      (str/replace #"\b(?:a|an|the)\b" " ")
      (str/replace #"[^\p{Alnum}\s]" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn strip-answer-prefix
  "Strip a leading lead-in word that models prepend when a benchmark asks for the
  answer 'in the form Label: X' / 'Answer: X'. Operates on already-normalized text
  (lowercase, punctuation→space), so \"Label: negative\" → \"label negative\" →
  \"negative\", and \"User: 84614\" → \"84614\". Leaves a bare label untouched."
  [s]
  (when (some? s)
    (-> (str s)
        (str/replace #"^(?:the\s+)?(?:final\s+)?(?:answer|label|user|result|response)s?\s+" "")
        str/trim)))

(defn parse-number
  "First integer/decimal found in `x` (handles a bare number, a numeric string, or a
  number embedded in prose like \"there are 12 spam emails\"). nil if none."
  [x]
  (cond
    (number? x) x
    (nil? x) nil
    :else (when-let [m (re-find #"-?\d+(?:\.\d+)?" (str x))]
            (let [n (edn/read-string m)] (when (number? n) n)))))

(defn try-edn
  "Best-effort EDN read of model text. Returns the value or nil (never throws)."
  [s]
  (when (and (string? s) (seq (str/trim s)))
    (try (edn/read-string s) (catch Throwable _ nil))))

(defn extract-answer
  "The predicted answer value from a returned result.

  - If it's a map with :answer (the requested FINAL shape), use that.
  - If it's any other map, nil (no answer field).
  - If it's a string, try to EDN-read it into {:answer ...}; else return the string.
  - Otherwise return the value as-is (number, vector, keyword)."
  [final-value]
  (cond
    (and (map? final-value) (contains? final-value :answer)) (:answer final-value)
    (map? final-value) (get final-value "answer")          ; tolerate string keys
    (string? final-value) (let [v (try-edn final-value)]
                            (if (and (map? v) (contains? v :answer))
                              (:answer v)
                              final-value))
    :else final-value))

(defn ->answer-set
  "Coerce a predicted/gold answer into a set of normalized strings, for set-based
  EM (FanOutQA-style). A scalar becomes a one-element set; a collection is mapped."
  [x]
  (cond
    (nil? x) #{}
    (coll? x) (set (keep normalize-text x))
    :else (let [n (normalize-text x)] (if (str/blank? (str n)) #{} #{n}))))
