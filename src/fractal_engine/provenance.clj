(ns fractal-engine.provenance
  "The trust layer. A FINAL value is a *claim*, not a fact (the behavior prompt says
  so explicitly). Two reads make a claim auditable:

  1. CLAIM PROVENANCE — walk a node's FINAL value and surface every claim that
     carries evidence, with the address of the node that produced it, so you can
     follow FINAL field → child → leaf → cited file.
  2. CLAIM-VS-EVIDENCE — the confabulation backstop. For each cited `file: quote`,
     read the file and check whether the distinctive tokens and code snippets in the
     quote actually occur in it. A model can write a fluent, code-quoted analysis of
     a file it never read; this catches exactly that.

  Matching is deliberately tolerant: evidence is paraphrase, not a byte-exact slice.
  We extract identifiers (snake_case / CamelCase / dotted names) and backtick code
  spans from the quote and report how many land in the file, with the misses named
  so a human or agent can judge rather than trust a single boolean."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [fractal-engine.projection :as proj]))

;; ── extract claims from a FINAL value ─────────────────────────────────────────

(defn- evidence-strings
  "Every evidence string reachable from a map node: the :evidence value (string or
  collection of strings)."
  [m]
  (let [e (:evidence m)]
    (cond
      (string? e)     [e]
      (sequential? e) (filterv string? e)
      :else           [])))

(defn- claim-label [m]
  (or (:description m) (:name m) (:claim m) (:risk m) (:title m)
      (some-> (first (keys (dissoc m :evidence))) name)))

(defn extract-claims
  "Walk a FINAL value and collect every evidenced claim as
  {:label … :evidence [str…]}. A claim is any map carrying an :evidence key; bare
  `file: quote` strings found loose are also captured."
  [final]
  (let [acc (atom [])]
    (walk/postwalk
     (fn [x]
       (when (map? x)
         (let [es (evidence-strings x)]
           (when (seq es)
             (swap! acc conj {:label (claim-label x) :evidence (vec es)}))))
       x)
     final)
    @acc))

;; ── parse one evidence string into file + quote ───────────────────────────────

(defn parse-evidence
  "Split `\"/abs/path.ext: some quoted text\"` into {:file :quote}. The path is the
  leading non-space run ending at the first colon; everything after is the quote.
  If there's no leading path, :file is nil and the whole string is the quote."
  [s]
  (let [s (str/trim (str s))]
    (if-let [[_ file quote] (re-matches #"(?s)(\S+?):\s+(.*)" s)]
      (if (re-find #"[/.]" file)            ; looks path-ish (has a slash or dot)
        {:file file :quote (str/trim quote)}
        {:file nil :quote s})
      {:file nil :quote s})))

;; ── pull the checkable bits out of a quote ─────────────────────────────────────

(defn- code-shaped?
  "A token worth checking against source: snake_case, dotted.path, or CamelCase. We
  deliberately exclude plain English words (however long) — they're paraphrase, not
  citation, and counting them as 'missing' would just add noise to the verdict."
  [token]
  (or (str/includes? token "_")
      (str/includes? token ".")
      (re-find #"[a-z][A-Z]" token)        ; internal capital → camelCase
      (re-find #"[A-Z].*[A-Z]" token)))    ; PascalCase / acronym-ish

(defn- identifiers
  "Distinctive code tokens in a quote — the names that, if the model truly read the
  file, should occur in it verbatim. Compared case-insensitively."
  [quote]
  (->> (re-seq #"[A-Za-z_][A-Za-z0-9_.]*[A-Za-z0-9_]" (str quote))
       (filter #(>= (count %) 4))
       (filter code-shaped?)
       (map str/lower-case)
       distinct
       vec))

(defn- code-spans
  "Literal code spans the model put in backticks — the strongest claims to check."
  [quote]
  (->> (re-seq #"`([^`]+)`" (str quote))
       (map second)
       (map str/trim)
       (remove str/blank?)
       vec))

;; ── check one claim against its file ──────────────────────────────────────────

(defn- normalize-ws [s] (-> (str s) (str/replace #"\s+" " ") str/lower-case))

(defn- span-fragments
  "A backtick span is often partial (`f\"UPDATE … = :value ...\"`). Split on ellipsis
  so each contiguous fragment can be matched independently."
  [span]
  (->> (str/split span #"\.\.\.|…")
       (map str/trim)
       (remove #(< (count %) 4))))

(defn- resolve-file
  "Locate a cited file. Absolute paths are used as-is; relative paths (the normal way
  a brain cites, relative to the repo root it was given) resolve against `base`.
  Returns the existing path or nil."
  [file base]
  (when file
    (let [f (java.io.File. ^String file)]
      (cond
        (.isAbsolute f)            (when (.exists f) file)
        :else (let [bf (java.io.File. (str (or base ".") "/" file))]
                (when (.exists bf) (.getPath bf)))))))

(defn check-evidence
  "Check one parsed evidence {:file :quote} against the filesystem, resolving a
  relative path against `base` (the repo root). Returns a verdict map naming what was
  found and what was missing — explainable, not just a boolean."
  ([parsed] (check-evidence parsed nil))
  ([{:keys [file quote]} base]
  (let [resolved (resolve-file file base)]
   (cond
    (nil? file)
    {:verdict :no-file-cited :quote quote}

    (nil? resolved)
    {:verdict :file-missing :file file :base base}

    :else
    (let [file resolved
          contents (try (slurp file) (catch Throwable _ nil))]
      (if (nil? contents)
        {:verdict :file-unreadable :file file}
        (let [hay     (normalize-ws contents)
              raw     (str contents)
              ids     (identifiers quote)
              spans   (code-spans quote)
              id-hit? (fn [id] (str/includes? (str/lower-case raw) id))
              found   (filterv id-hit? ids)
              missing (vec (remove id-hit? ids))
              frags   (mapcat span-fragments spans)
              frag-ok (filterv #(str/includes? hay (normalize-ws %)) frags)
              ratio   (if (seq ids) (/ (double (count found)) (count ids)) nil)
              verdict (cond
                        (and (empty? ids) (empty? frags)) :unverifiable
                        (and (zero? (count found)) (zero? (count frag-ok))) :unsupported
                        (or (nil? ratio) (>= ratio 0.6)
                            (and (seq frags) (= (count frag-ok) (count frags)))) :supported
                        :else :partial)]
          {:verdict        verdict
           :file           file
           :identifiers    {:found found :missing missing
                            :ratio (some-> ratio (* 100) Math/round (/ 100.0))}
           :code-spans     {:checked (vec frags) :found frag-ok}})))))))

(defn check-claims
  "Run claim-vs-evidence over every evidenced claim in a FINAL value, resolving
  relative citations against `base` (the repo root the run worked on). Each result is
  {:label … :evidence \"…\" :file … :verdict …}."
  ([final] (check-claims final nil))
  ([final base]
   (->> (extract-claims final)
        (mapcat (fn [{:keys [label evidence]}]
                  (map (fn [ev]
                         (let [parsed (parse-evidence ev)]
                           (merge {:label label :evidence ev}
                                  parsed
                                  (check-evidence parsed base))))
                       evidence)))
        vec)))

(defn summarize
  "Roll verdicts up to one banner: counts per verdict + an overall trust call."
  [checks]
  (let [freqs (frequencies (map :verdict checks))
        bad   (+ (get freqs :unsupported 0) (get freqs :file-missing 0))
        total (count checks)]
    {:total total
     :by-verdict freqs
     :confabulation-suspected (pos? bad)
     :overall (cond
                (zero? total)          :no-claims
                (pos? bad)             :suspect
                (pos? (get freqs :partial 0)) :mixed
                :else                  :supported)}))

;; ── deep verify: hand the claims back to the engine, let IT decide how ────────

(defn verify-task
  "Build the task that asks the engine to validate its own claims against source.
  It does NOT prescribe children-vs-leaves — that's the engine's call, the whole
  point. Input is the floor's parsed checks (so floor and deep share one parse);
  output instruction is a compact per-claim verdict vector."
  [checks _base]
  (let [items (->> checks
                   (map-indexed (fn [i ck]
                                  {:id i
                                   :claim (str (:label ck))
                                   :file (:file ck)
                                   :cited-evidence (str (:quote ck))}))
                   vec)]
    (str
     "Validate claims another model made about a codebase, against the ACTUAL source. "
     "Be adversarial: try to REFUTE each claim by reading the real code.\n\n"
     "For each claim: read the cited file (slurp it; read surrounding context if you need it) "
     "and decide whether the code genuinely supports the claim. It is YOUR call how to check "
     "each one — spawn a child (rlm) to investigate a claim that needs its own read/search loop, "
     "or judge a bounded one with a leaf (lm / map-lm). Use the cheapest sufficient check; do "
     "not over-investigate a claim the file plainly settles.\n\n"
     "Claims (EDN):\n" (pr-str items) "\n\n"
     "FINAL EDN: a vector, one entry per claim, "
     "[{:id <id> :verdict :supported|:refuted|:unclear :why \"<short>\" "
     ":quote \"<a verbatim line from the file that settles it, or nil>\"}]. "
     "The :quote must be copied from the file you read, never paraphrased.")))

(defn merge-verdicts
  "Fold the engine's deep verdict vector back onto the floor checks by :id."
  [checks verdicts]
  (let [by-id (into {} (map (fn [v] [(:id v) v])) (when (sequential? verdicts) verdicts))]
    (vec (map-indexed (fn [i ck] (assoc ck :deep (get by-id i))) checks))))

;; ── provenance: which node produced the FINAL, and its evidenced claims ───────

(defn node-provenance
  "For the node at `address`, the chain that backs its FINAL value: the final value
  itself, the evidenced claims it contains, and the child/leaf calls that fed it.
  Pure structural read over the journal fold."
  [root-dir address]
  (when-let [node (proj/load-at root-dir address)]
    {:address  address
     :final    (:final node)
     :claims   (extract-claims (:final node))
     :children (mapv #(select-keys % [:address :label :status]) (:children node))
     :leaves   (mapv #(select-keys % [:call-id :index :status]) (:leaves node))}))
