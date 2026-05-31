(ns fractal-eval.fanoutqa
  "FanOutQA adapter. FanOutQA (arXiv 2402.14116) is a multi-document fan-out-and-join
  benchmark: a question decomposes into many per-entity sub-questions whose answers
  must be gathered and combined into one structured answer. The answer depends on the
  whole set, and it's exact-match scored — the cleanest existing 'fanout + aggregate'
  task. It exercises map-lm / map-rlm fanout (see the README caveat: fanout is not
  recursion — pair this with a recursion-stressing task before claiming RLM wins).

  We support the 'evidence-provided' setting: the example carries the relevant docs
  in :context (or :context_path), so scoring is about decompose-gather-join, not web
  retrieval. gold is the structured answer flattened to a list of reference strings.

  Metrics — FAITHFUL to FanOutQA's canonical string metrics (Zhu et al. 2024):
    loose  — fraction of gold reference strings found (SQuAD-normalized substring) in
             the model's answer text. THIS IS THE HEADLINE (the benchmark reports the
             mean loose accuracy across questions).
    strict — 1 iff ALL gold reference strings are present (loose == 1.0).
  The model's answer is treated as one text blob (lists are joined), matching the
  benchmark's free-text containment check — NOT a deduped set, so repeated gold values
  (e.g. \"Various\"×3) and single-string answers score correctly."
  (:require [clojure.string :as str]
            [fractal-eval.scoring :as score]))

(defn engine-task
  [{:keys [question]} context-path]
  (str "Fan-out question: it breaks into several independent sub-questions whose "
       "answers you must gather and JOIN into one answer.\n\n"
       "The provided evidence documents are on disk at this path (slurp it):\n"
       "  " context-path "\n\n"
       "Decompose the question into its independent parts. Gather each part from the "
       "evidence — use map-lm over the parts for bounded extraction, or map-rlm if a "
       "part needs its own investigation. Join the gathered facts in Clojure. Carry "
       "identity so results merge safely; do not fabricate an item you cannot ground "
       "in the evidence.\n\n"
       "Question:\n  " question "\n\n"
       "FINAL exactly this EDN shape:\n"
       "  {:answer <a vector of the answer strings, or a single string>\n"
       "   :evidence [<short verbatim quotes/handles you grounded each item in>]\n"
       "   :missing [<sub-questions you could not answer from the evidence>]}"))

(defn flat-prompt
  [{:keys [question context]}]
  (str "Answer this multi-part question using only the provided documents. Reply with "
       "EDN {:answer [<strings>]} and nothing else.\n\n"
       "Documents:\n" context "\n\nQuestion: " question))

(defn score-one
  "FanOutQA loose/strict, faithful to the canonical metric. Gold is a list of
  reference strings; the model's answer is treated as one text blob. loose = fraction
  of gold strings present (SQuAD-normalized substring); strict = all present."
  [{:keys [gold]} predicted-raw]
  (let [pred (score/extract-answer predicted-raw)
        pred-text (score/squad-normalize
                   (if (coll? pred) (str/join " | " (map str pred)) (str pred)))
        golds (->> (if (sequential? gold) gold [gold]) (map str) (remove str/blank?) vec)
        gnorm (mapv score/squad-normalize golds)
        present? (fn [g] (and (seq g) (str/includes? pred-text g)))
        found (count (filter present? gnorm))
        loose (if (seq gnorm) (/ (double found) (count gnorm)) 0.0)
        strict? (and (seq gnorm) (= found (count gnorm)))]
    {:gold golds
     :predicted (if (coll? pred) (vec pred) pred)
     :n-gold (count gnorm)
     :n-found found
     :loose-accuracy loose            ; HEADLINE metric (per-question recall of gold strings)
     :correct? (if strict? 1 0)        ; FanOutQA strict accuracy = all gold strings present
     :missed (vec (keep (fn [[g gn]] (when-not (present? gn) g)) (map vector golds gnorm)))}))
