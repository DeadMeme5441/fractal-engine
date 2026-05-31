(ns fractal-eval.dataset
  "Loading benchmark examples from a normalized JSONL shape, and fingerprinting the
  dataset so a results file pins exactly what it ran on.

  The harness never bundles the real OOLONG / FanOutQA corpora (license + size). It
  reads a *normalized JSONL* you produce with the documented converter (see
  evals/README.md), one example per line:

    {\"id\": \"...\",            ; stable example id
     \"benchmark\": \"oolong\",  ; or \"fanoutqa\"
     \"question\": \"...\",      ; the question posed over the surface
     \"context\": \"...\",       ; the surface, inline  (OR \"context_path\")
     \"context_path\": \"...\",  ; ...a file (relative to the jsonl) for large surfaces
     \"answer_type\": \"count\", ; oolong: \"count\" | \"label\"
     \"gold\": 3,                ; the reference answer (number / string / list)
     \"meta\": {...}}            ; anything else, carried through

  Tiny hand-written sample fixtures live in evals/resources/fixtures/ so the whole
  pipeline is testable offline with no download."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(defn sha256-hex [^String s]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes s "UTF-8"))
         (map #(format "%02x" %))
         (apply str))))

(defn- read-jsonl [reader]
  (->> (line-seq reader)
       (map str/trim)
       (remove str/blank?)
       (mapv #(json/parse-string % true))))

(defn load-examples
  "Load and normalize examples from a JSONL file. Resolves :context_path to inline
  :context (relative to the dataset file) so downstream code sees one shape."
  [path]
  (let [file (io/file path)
        base (.getParentFile (.getCanonicalFile file))
        rows (with-open [r (io/reader file)] (read-jsonl r))]
    (mapv (fn [row]
            (let [ctx (or (:context row)
                          (when-let [p (:context_path row)]
                            (slurp (io/file base p))))]
              (-> row
                  (assoc :context ctx)
                  (dissoc :context_path))))
          rows)))

(defn fingerprint
  "A stable fingerprint of the loaded examples: count + a content hash over the
  ordered (id, question, context, gold) tuples. Pins exactly what a run scored."
  [path examples]
  {:dataset/path (str path)
   :dataset/count (count examples)
   :dataset/sha256
   (sha256-hex
    (str/join "\n"
              (map (fn [e]
                     (str (:id e) "" (:question e) ""
                          (:answer_type e) "" (pr-str (:gold e)) ""
                          (sha256-hex (str (:context e)))))
                   examples)))})

(defn limit-examples
  "Deterministic example selection: optional take of the first N (seed is recorded
  for provenance; ordering is the file's, so selection is reproducible without RNG)."
  [examples {:keys [limit]}]
  (if (and limit (pos? limit)) (vec (take limit examples)) (vec examples)))
