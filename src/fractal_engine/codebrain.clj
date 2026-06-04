(ns fractal-engine.codebrain
  "CODEBRAIN — a persistent code-discovery brain built on the recursive engine.

  It is a thin PRODUCT surface, not part of the compute kernel: the value is two
  prompts, not new engine machinery. The kernel teaches a node how to be a
  recursive compute node (the base system prompt); codebrain is a second,
  session-level overlay on top of that one — a standing role added once at the
  brain's birth that says \"you are a code-discovery brain for THIS repo: build a
  repo map for yourself using your children and leaves, then answer a coding
  agent's questions from it, compact and cited.\" The overlay is carried across
  every turn and every resume via the message history; it is never re-stated per
  turn.

  Used like `bd`: brain metadata and its repo map live as product files under
  `<runs-dir>/codebrain/`, while the live brain session itself is canonical
  Datahike + BlobStore state in the configured store root. `codebrain init` is born
  once and builds its map; each `codebrain ask` resumes that same brain (its map
  and vars stay warm) to answer a query and advance the brain's head. The point is
  to move codebase discovery off the coding agent's context and onto the brain's:
  the agent gets a small cited answer instead of having to read the code itself."
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cliopts :as cli]
            [fractal-engine.render :as r]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.session :as session]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.time :as time])
  (:import [java.io File]))

;; ── the prompts (the product) ─────────────────────────────────────────────────

(defn session-overlay
  "The standing brain role, added once to the session's system message on top of
  the base behavior. References the repo root it is born to serve."
  [root]
  (str
   "== CODEBRAIN: standing role for this session ==\n\n"
   "You are CODEBRAIN, a long-lived RLM session dedicated to one repository:\n"
   root "\n\n"
   "Your caller is a coding agent that needs to make changes without spending its own\n"
   "working window rediscovering the repo. Your job is to explore the code once,\n"
   "hold a compact repo map in durable vars, and answer later questions with small,\n"
   "cited EDN values the caller can act on directly.\n\n"
   "What you own for the whole session:\n"
   "- `repo-map`: a compact navigation model of the repository: subsystems,\n"
   "  entrypoints, key files, public names, data/config surfaces, tests, build files,\n"
   "  and where a coding agent should start for common concerns.\n"
   "- `repo-map-checks`: coverage counts, excluded paths, stale signals, and anything\n"
   "  missing. If the map is incomplete, the incompleteness is state, not a secret.\n"
   "- `repo-evidence`: short evidence records with file paths, optional line ranges,\n"
   "  and verbatim quotes or handles that support the map.\n\n"
   "Operating rules:\n"
   "- Read-only by default. Do not modify the repository, create files, reformat code,\n"
   "  run destructive commands, or write generated analysis into the target repo.\n"
   "- The source is truth. The map is a guide. If the map points to the wrong place,\n"
   "  read current source, answer from it, and mark :map-stale? true.\n"
   "- Delegate reading. Your own transcript is expensive and replayed. Do not slurp\n"
   "  and print whole files at the root. Use Clojure to list and slice files, map-lm\n"
   "  for bounded file/snippet reads, and map-rlm for subsystem investigations that\n"
   "  need their own loop.\n"
   "- Do not over-decompose. You are the orchestrator: scout first, decide what is\n"
   "  independent and uncertain, and only spawn children for lanes that genuinely need\n"
   "  their own inspect/judge loop. Use Clojure or leaves for small bounded questions.\n"
   "- Ground every claim. A claim without a file path and either a quote, symbol, or\n"
   "  observed handle is not ready for FINAL.\n"
   "- Stay compact. FINAL is the machine-readable value for the coding agent, not a\n"
   "  report. Return EDN with answer, evidence, files-read, pointers, checks, and\n"
   "  missingness. Never return raw file contents.\n"
   "- Preserve state. Later turns should reuse `repo-map`, `repo-map-checks`, and\n"
   "  `repo-evidence` instead of rediscovering the whole repo.\n\n"
   "Your strengths in this role:\n"
   "- Turn a repo tree into a useful navigation surface.\n"
   "- Dispatch independent subsystems to child RLMs.\n"
   "- Batch bounded source reads through leaves.\n"
   "- Keep exact counts and joins in Clojure.\n"
   "- Give a coding agent the next file and line to open, with why.\n\n"
   "Each turn is either a map build/refresh or a coding-agent question. Complete the\n"
   "assigned turn fully, but do not gold-plate. The caller needs enough grounded\n"
   "information to move, not an essay."))

(defn build-message
  "The per-turn message that asks the brain to (re)build its repo map. The HOW lives
  in the session overlay; this states the task and pins the FINAL shape."
  [root]
  (str
   "Build (or rebuild) your repo map of " root " now.\n\n"
   "Return a durable navigation map, not a prose audit. Work in this order:\n\n"
   "1. Inventory with Clojure. Bind `repo-root`, `all-files`, `source-files`,\n"
   "   `test-files`, `build-files`, and `excluded-files`. Exclude .git, .fractal,\n"
   "   runs, target, build, dist, node_modules, dot-directories, dotfiles such as\n"
   "   .env, binaries, generated artifacts, and vendored dependency trees. Inspect\n"
   "   counts and representative paths before reading file contents.\n"
   "2. Partition before solving. Use directory shape, filenames, and small bounded\n"
   "   reads to propose subsystems. If names are ambiguous, use map-lm over compact\n"
   "   path/file-header records to classify them. Keep a `partition-checks` var that\n"
   "   proves every included source file belongs to exactly one subsystem or is listed\n"
   "   in :missing.\n"
   "3. Choose decomposition deliberately. Use Clojure for obvious exact structure,\n"
   "   map-lm for bounded classification/extraction over files or snippets, and\n"
   "   map-rlm only for independent subsystem lanes whose answer requires a child to\n"
   "   inspect, judge, and settle. Do not spawn a child just because a folder exists.\n"
   "   When you do use map-rlm, give each child one lane name, its file list,\n"
   "   exclusions, and this FINAL shape:\n"
   "   {:subsystem <name>\n"
   "    :purpose <one sentence>\n"
   "    :key-files [{:path <relative> :role <short> :symbols [..]}]\n"
   "    :entrypoints [{:path <relative> :what <short>}]\n"
   "    :depends-on [<subsystems-or-libs>]\n"
   "    :tests [{:path <relative> :what <short>}]\n"
   "    :gotchas [<short grounded notes>]\n"
   "    :evidence [{:file <relative> :quote <verbatim-or-symbol> :why <short>}]\n"
   "    :missing [..]}.\n"
   "   The child must read real bounded file content, preferably through map-lm\n"
   "   batches. Path-name summaries alone are not acceptable.\n"
   "4. Compose deterministically. If you used children, map-rlm returns envelopes;\n"
   "   use `(mapv :rlm/value children)` as the source of truth. If Clojure/leaves were\n"
   "   sufficient, compose directly from those vars. Build `where-to-look` by joining\n"
   "   the grounded results in Clojure for topics a coding agent asks about: entrypoints, config,\n"
   "   data model, command/API surface, control flow, persistence, provider boundary,\n"
   "   error handling, tests, build, and CLI/API integration.\n"
   "5. Bind durable vars: `(def repo-map ...)`, `(def repo-map-checks ...)`, and\n"
   "   `(def repo-evidence ...)`. Then FINAL exactly `repo-map` with this shape:\n"
   "   {:root " root "\n"
   "    :overview <2-3 grounded sentences>\n"
   "    :languages [..]\n"
   "    :coverage {:included n :excluded n :subsystems n :unmapped n}\n"
   "    :subsystems [<child values>]\n"
   "    :where-to-look [{:topic <keyword-or-string> :start [relative-paths]}]\n"
   "    :entrypoints [{:path <relative> :what <short>}]\n"
   "    :glossary [{:term <short> :means <short> :where [relative-paths]}]\n"
   "    :decomposition {:strategy <why this split was enough> :child-count n :leaf-count n}\n"
   "    :checks repo-map-checks\n"
   "    :missing [..]}.\n\n"
   "Keep the map compact: names, locations, one-line purposes, key symbols, and\n"
   "evidence handles. Never include raw file bodies. It must navigate a coding agent\n"
   "to any major concern in one hop."))

(defn ask-message
  "The per-turn message carrying a coding agent's query. Lean on purpose — the
  brain role and working rules already live in the session overlay; this delivers
  the question and pins the cited FINAL shape the agent consumes."
  [question]
  (str
   "Coding-agent query:\n\n" question "\n\n"
   "Answer using the warm `repo-map` first: identify likely files and subsystems,\n"
   "then read current source to confirm. If `repo-map` is missing, stale, or points\n"
   "wrong, say so in :map-stale? and repair only the slice needed for this answer.\n\n"
   "Use the cheapest sufficient work:\n"
   "- Clojure for exact path search, grep results, parsing, counts, and joins.\n"
   "- map-lm for bounded semantic reads over snippets, defs, tests, or small files.\n"
   "- rlm/map-rlm only when the question spans independent subsystems or needs a\n"
   "  real investigation loop.\n\n"
   "Read the CURRENT source to ground every claim. Do not answer from repo-map alone\n"
   "unless the question is literally about the map. FINAL one compact EDN value the\n"
   "coding agent can act on without opening the whole repo itself:\n"
   " {:answer <direct, specific answer>\n"
   "  :evidence [{:file <path> :lines \"a-b\" :quote <verbatim>}]\n"
   "  :files-read [<paths>]\n"
   "  :pointers [{:what <what to do / where to go next> :file <path> :lines \"a-b\"}]\n"
   "  :checks {:used-map? boolean :current-source-read? boolean :child-count n :leaf-count n}\n"
   "  :missing [<what you could not determine>]\n"
   "  :map-stale? <true/false, with a note if the map was wrong>}"))

;; ── on-disk brain state (durable, survives invocations like bd's db) ───────────

(defn brain-dir
  "The directory holding this repo's brain metadata and persisted repo map."
  [runs-dir]
  (str (store-io/path runs-dir "codebrain")))

(defn- meta-path [bdir] (store-io/path bdir "meta.edn"))
(defn- map-edn-path [bdir] (store-io/path bdir "repo-map.edn"))
(defn- map-md-path [bdir] (store-io/path bdir "repo-map.md"))

(defn load-meta [bdir] (store-io/read-edn-file (meta-path bdir) nil))
(defn load-map  [bdir] (store-io/read-edn-file (map-edn-path bdir) nil))

(def ^:private brain-session-id "codebrain")

(defn- render-map-md [m]
  (str "# Repo map — " (:root m) "\n\n"
       (when (:overview m) (str (:overview m) "\n\n"))
       (when (seq (:languages m)) (str "Languages: " (str/join ", " (:languages m)) "\n\n"))
       (when (seq (:entrypoints m))
         (str "## Entrypoints\n"
              (str/join "\n" (for [e (:entrypoints m)] (str "- `" (:path e) "` — " (:what e)))) "\n\n"))
       "## Subsystems\n"
       (str/join "\n\n"
                 (for [s (:subsystems m)]
                   (str "### " (:subsystem s) " — " (:purpose s) "\n"
                        (when (seq (:depends-on s)) (str "depends-on: " (str/join ", " (:depends-on s)) "\n"))
                        (str/join "\n" (for [f (:key-files s)]
                                         (str "- `" (:path f) "`" (when (:role f) (str " — " (:role f)))
                                              (when (seq (:symbols f)) (str "  [" (str/join " " (:symbols f)) "]"))))))))
       "\n\n## Where to look\n"
       (str/join "\n" (for [w (:where-to-look m)]
                        (str "- **" (:topic w) "** → " (str/join ", " (map #(str "`" % "`") (:start w))))))
       "\n"))

(defn persist-map! [bdir m]
  (store-io/ensure-dir! bdir)
  (store-io/write-edn! (map-edn-path bdir) m)
  (spit (str (map-md-path bdir)) (render-map-md m))
  m)

(defn- save-meta! [bdir meta]
  (store-io/ensure-dir! bdir)
  (store-io/write-edn! (meta-path bdir) meta)
  meta)

;; ── rendering the answer the agent consumes ────────────────────────────────────

(defn- answer-str [run-token final usage]
  (let [ev (:evidence final)
        ptrs (:pointers final)]
    (str (r/c :bold "answer") "\n"
         (r/clip (str (:answer final)) 2000) "\n\n"
         (when (seq ev)
           (str (r/c :bold "evidence") "\n"
                (str/join "\n" (for [e ev]
                                 (str "  " (r/c :cyan (str (:file e) (when (:lines e) (str ":" (:lines e)))))
                                      (when (:quote e) (str "  " (r/c :dim (r/clip (str/replace (str (:quote e)) #"\s+" " ") 120)))))))
                "\n\n"))
         (when (seq ptrs)
           (str (r/c :bold "pointers") "\n"
                (str/join "\n" (for [p ptrs]
                                 (str "  " (r/c :cyan (str (:file p) (when (:lines p) (str ":" (:lines p)))))
                                      "  " (str (:what p)))))
                "\n\n"))
         (when (seq (:missing final))
           (str (r/c :yellow "missing") " " (str/join "; " (map str (:missing final))) "\n"))
         (when (:map-stale? final) (str (r/c :yellow "map-stale?") " " (pr-str (:map-stale? final)) "\n"))
         (when usage (str usage "\n"))
         (r/c :dim (str "  brain: " run-token "   ·   verify: fractal verify " run-token)))))

(defn- usage-report-str [locator turn-id]
  (when locator
    (let [v (session-db/view (artifacts/store-root-for-locator locator)
                             (artifacts/session-id-for-locator locator))
          report (artifacts/derive-usage-report locator (:calls v) {:turn-id turn-id})]
      (r/usage-report-str report))))

;; ── verbs ──────────────────────────────────────────────────────────────────────

(defn- err [msg] {:out msg :exit 1 :err? true})

(defn- repo-root [flags]
  (let [p (or (:path flags) (System/getProperty "user.dir"))]
    (.getCanonicalPath (File. ^String p))))

(defn- result-exit [result]
  (cond
    (= :error   (keyword (:status result))) 1
    (= :timeout (keyword (:status result))) 3
    (contains? result :final-value)         0
    :else                                   2))

(defn cmd-init
  "Birth the brain and build its first repo map. Re-running starts a fresh brain."
  [pos flags]
  (let [cfg   (cli/cfg-from-opts flags)
        bdir  (brain-dir (:runs-dir cfg))
        root  (repo-root flags)
        s     (session/start-session! cfg {:id brain-session-id
                                           :alias brain-session-id
                                           :store-root (:runs-dir cfg)
                                           :overlay (session-overlay root)})
        res   (session/run-turn! s (build-message root))
        _     (session/stop-session! s)
        locator (:locator s)
        m     (:final-value res)
        ts    (time/now-str)]
    (when (map? m) (persist-map! bdir m))
    (save-meta! bdir {:root root :born-at ts :map-built-at (when (map? m) ts)
                      :origin locator :head locator :session-id brain-session-id
                      :alias brain-session-id :turns 0
                      :provider (:provider flags) :model (:model flags)})
    (if (not= 0 (result-exit res))
      (assoc (err (str "map build did not finalize (status " (:status res) ")"
                       (when (:error res) (str ": " (pr-str (:error res)))) "\n  inspect: fractal show " brain-session-id))
             :exit (result-exit res))
      {:out (str (r/c :bold "codebrain born") " · " root "\n"
                 (if (map? m)
                   (str "  mapped " (count (:subsystems m)) " subsystems → " (str (map-md-path bdir)) "\n"
                        (r/c :dim (str "  ask it: fractal codebrain ask \"where is X handled?\"   ·   see the map: fractal codebrain map")))
                   (r/c :yellow "  (FINAL was not a map; inspect the run)"))
                 "\n" (when-let [u (usage-report-str locator (:turn-id res))] (str u "\n")))
       :exit 0})))

(defn cmd-ask
  "Resume the brain and answer one coding-agent query, advancing its HEAD."
  [pos flags]
  (let [cfg      (cli/cfg-from-opts flags)
        bdir     (brain-dir (:runs-dir cfg))
        meta     (load-meta bdir)
        question (or (first pos) (:question flags))]
    (cond
      (nil? meta)     (err "no brain yet — build one first: fractal codebrain init [--path DIR] <provider/model flags>")
      (nil? question) (err "missing query: fractal codebrain ask \"your question\"")
      :else
      (let [head    (:head meta)
            n       (inc (:turns meta 0))
            run-token (or (:alias meta) brain-session-id)
            s       (session/resume-session! cfg head {:id brain-session-id})
            res     (session/run-turn! s (ask-message question))
            _       (session/stop-session! s)
            locator (:locator s)
            final   (:final-value res)]
        (save-meta! bdir (assoc meta :head locator :session-id brain-session-id
                                :alias brain-session-id :turns n
                                :last-ask-at (time/now-str)))
        (if (and (= 0 (result-exit res)) (map? final))
          {:out (answer-str run-token final (usage-report-str locator (:turn-id res))) :exit 0}
          (assoc (err (str "ask did not finalize cleanly (status " (:status res) ")"
                           (when (:error res) (str ": " (pr-str (:error res))))
                           "\n  inspect: fractal show " run-token))
                 :exit (result-exit res)))))))

(defn cmd-map
  "Show the brain's persisted repo map."
  [pos flags]
  (let [cfg  (cli/cfg-from-opts flags)
        bdir (brain-dir (:runs-dir cfg))
        m    (load-map bdir)]
    (cond
      (nil? m)        (err "no repo map yet — build one: fractal codebrain init")
      (:json flags)   {:out (pr-str m) :exit 0}
      :else           {:out (render-map-md m) :exit 0})))

(defn cmd-status
  [pos flags]
  (let [cfg  (cli/cfg-from-opts flags)
        bdir (brain-dir (:runs-dir cfg))
        meta (load-meta bdir)]
    (if (nil? meta)
      (err "no brain yet — build one: fractal codebrain init [--path DIR]")
      {:out (str (r/c :bold "codebrain") " · " (:root meta) "\n"
                 "  born   " (:born-at meta) "\n"
                 "  map    " (or (:map-built-at meta) "(not built)") "\n"
                 "  turns  " (:turns meta) "\n"
                 "  head   " (:head meta) "\n"
                 (r/c :dim "  ask: fractal codebrain ask \"…\"   ·   map: fractal codebrain map"))
       :exit 0})))

(defn cmd-help [_ _]
  {:out (str (r/c :bold "fractal codebrain — a persistent code-discovery brain") "\n\n"
             "  fractal codebrain init [--path DIR] [--provider P --model M --child-model M --leaf-model M]\n"
             "                                  birth the brain and build its repo map\n"
             "  fractal codebrain ask \"<question>\"   resume the brain, answer a coding-agent query (cited)\n"
             "  fractal codebrain map [--json]       show the persisted repo map\n"
             "  fractal codebrain status             freshness + brain HEAD\n\n"
             (r/c :dim "the brain serves a coding agent: it explores the code so the agent doesn't have to.\n")
             (r/c :dim "leash live runs: --max-turns N --max-fanout N --max-leaf-concurrency N --call-timeout-ms MS --cache-ttl 5m|1h"))
   :exit 0})

(def ^:private subverbs
  {"init" cmd-init "build" cmd-init "rebuild" cmd-init
   "ask" cmd-ask "q" cmd-ask
   "map" cmd-map "status" cmd-status
   "help" cmd-help "--help" cmd-help "-h" cmd-help})

(defn command
  "Dispatch a `fractal codebrain <sub> …` invocation. `pos` is the positionals
  AFTER the `codebrain` verb (sub-command first); `flags` the parsed flags."
  [pos flags]
  (let [sub (first pos)
        f   (get subverbs (str sub))]
    (if f
      (f (vec (rest pos)) flags)
      (cmd-help nil flags))))
