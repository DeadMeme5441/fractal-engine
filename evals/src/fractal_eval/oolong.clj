(ns fractal-eval.oolong
  "OOLONG adapter. OOLONG (arXiv 2511.02817) is an *aggregation* benchmark: analyze
  each chunk of a long context atomically, then aggregate to answer a distributional
  question (counting / classification-then-count / temporal & relational reasoning).
  Frontier models score <50% at 128K. It is the purest existing match for this
  engine's thesis — and the benchmark RLM led with.

  This adapter turns one example into an engine task that plays to the engine's
  design (slurp the surface from a file, represent it, aggregate in Clojure), and
  scores the returned answer deterministically.

  answer_type:
    \"count\" — gold is a number (or a list of numbers for a tie). Primary metric:
               exact match. Secondary: continuous numeric-accuracy.
    \"label\" — gold is a category word, OR a LIST of words when the source answer is
               a tie (OOLONG stores answers as Python lists, e.g. ['positive',
               'negative']). Matching strips any \"Label:\"/\"Answer:\" lead-in the
               model emits and accepts an exact-or-contained match of ANY gold value."
  (:require [clojure.string :as str]
            [fractal-eval.scoring :as score]))

(defn engine-task
  "The task string handed to the engine. `context-path` is a file the engine can
  slurp; we do NOT inline a 128K surface into the prompt — representing it from a
  file is exactly the move the engine is prompted toward."
  [{:keys [question]} context-path]
  (str "Aggregation question over a long, heterogeneous surface.\n\n"
       "The full surface is on disk at this path (slurp it; do not assume its size):\n"
       "  " context-path "\n\n"
       "Represent and validate the surface first (parse records, separate data from "
       "headings/metadata, check any stated counts). Analyze each record atomically "
       "with leaves where semantic judgment is needed, then AGGREGATE THE ANSWER "
       "DETERMINISTICALLY IN CLOJURE from the per-record results. Do not let a model "
       "produce the final count or label.\n\n"
       "Question:\n  " question "\n\n"
       "FINAL exactly this EDN shape:\n"
       "  {:answer <the answer: a number for a count, a string/keyword for a label>\n"
       "   :method \"<one line: how you aggregated>\"\n"
       "   :checks {<parsed counts / consistency checks you ran>}\n"
       "   :missing [<ids or notes for anything answer-sensitive you could not resolve>]}"))

(defn flat-prompt
  "The flat single-window baseline: stuff the whole surface + question into one
  non-recursive prompt. This is what the engine is competing against."
  [{:keys [question context]}]
  (str "You are given a long context. Read all of it and answer the question with an "
       "exact answer. Reply with EDN {:answer <number-or-string>} and nothing else.\n\n"
       "Context:\n" context "\n\nQuestion: " question))

(defn- gold-seq
  "Gold may be a single value or a vector (OOLONG ties). Always work over a seq."
  [gold]
  (if (sequential? gold) gold [gold]))

(defn score-one
  "Score a predicted answer against gold. Returns a map with :correct? (0/1 primary
  metric) and benchmark-specific detail. Gold may be a single value or a list (tie);
  a match against ANY listed gold counts."
  [{:keys [answer_type gold]} predicted-raw]
  (let [pred (score/extract-answer predicted-raw)]
    (if (= :count (keyword (or answer_type "count")))
      (let [golds (keep score/parse-number (gold-seq gold))
            p (score/parse-number pred)
            exact? (boolean (and (some? p) (some #(== % p) golds)))
            ;; numeric-accuracy against the NEAREST gold (ties take the best)
            num-acc (if (and (seq golds) (some? p))
                      (apply max (map (fn [g]
                                        (max 0.0 (- 1.0 (/ (double (abs (- (double p) (double g))))
                                                           (max 1.0 (double (abs g)))))))
                                      golds))
                      0.0)]
        {:answer_type :count
         :gold (vec golds) :predicted p :raw-predicted pred
         :correct? (if exact? 1 0)
         :numeric-accuracy num-acc})
      ;; label (incl. comparison / month-year). Strip any "Label:"/"Answer:" lead-in,
      ;; then accept an exact OR contained match against any gold value (handles ties).
      (let [golds (->> (gold-seq gold)
                       (map #(score/normalize-text (if (keyword? %) (name %) %)))
                       (remove str/blank?)
                       distinct vec)
            p (-> (if (keyword? pred) (name pred) pred)
                  score/normalize-text
                  score/strip-answer-prefix)
            match (when (and (seq golds) (some? p) (seq p))
                    (some (fn [g]
                            (cond (= g p) :exact
                                  (str/includes? p g) :pred-contains-gold
                                  (str/includes? g p) :gold-contains-pred))
                          golds))]
        {:answer_type :label
         :gold golds :predicted p :raw-predicted pred
         :correct? (if match 1 0)
         :match (or match :none)}))))
