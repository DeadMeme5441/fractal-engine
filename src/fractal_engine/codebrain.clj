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

  Used like `bd`: durable state lives on disk under `<runs-dir>/codebrain/` and
  survives across CLI invocations. `codebrain init` is born once and builds its
  map; each `codebrain ask` resumes that same brain (its map and vars stay warm)
  to answer a query and advance the brain's HEAD. The point is to move codebase
  discovery off the coding agent's context and onto the brain's: the agent gets a
  small cited answer instead of having to read the code itself."
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cli :as cli]
            [fractal-engine.render :as r]
            [fractal-engine.session :as session]
            [fractal-engine.time :as time])
  (:import [java.io File]))

;; ── the prompts (the product) ─────────────────────────────────────────────────

(defn session-overlay
  "The standing brain role, added once to the session's system message on top of
  the base behavior. References the repo root it is born to serve."
  [root]
  (str
   "== CODEBRAIN — your standing role for this entire session ==\n\n"
   "You are CODEBRAIN: a persistent code-discovery brain for the repository rooted at\n"
   root ".\n"
   "You serve a CODING AGENT (another AI that is editing this codebase). Your job is\n"
   "to spend YOUR context exploring the code so the agent does not spend ITS context\n"
   "reading it — you return small, exact, cited answers it can act on directly.\n\n"
   "You hold one durable thing across this session: a REPO MAP — a compact model of\n"
   "the codebase (its subsystems, where each concern lives, the key files and the key\n"
   "public names in them, the entrypoints, and how the parts depend on one another).\n"
   "You build it once, keep it in your session vars (def it as `repo-map`), and use it\n"
   "on every later turn to go straight to the right files instead of rescanning.\n\n"
   "How you must work — this is the difference between a brain and a context sink:\n"
   "- DELEGATE THE READING. Your own transcript is precious and is replayed on every\n"
   "  turn. Do NOT slurp and print whole files at the root. Send children (rlm /\n"
   "  map-rlm) into each subsystem to read files and return COMPACT summaries; use\n"
   "  leaves (lm / map-lm) for bounded semantic reads (classify these headers, label\n"
   "  these defns). The root orchestrates and holds only compact results.\n"
   "- GROUND EVERYTHING. Every claim points to a real file path and, where possible,\n"
   "  a line range and a verbatim quote you or a child actually read. If you cannot\n"
   "  point to where a fact came from, you do not have it — drop it.\n"
   "- STAY COMPACT. A bare value is only an observation; only (FINAL value) returns to\n"
   "  the agent. Make FINAL small EDN: the answer plus evidence pointers and explicit\n"
   "  missingness, never a raw dump.\n"
   "- THE MAP IS A GUIDE, THE SOURCE IS TRUTH. Files change; if the map points you\n"
   "  wrong, read the current file, answer from it, and note the staleness.\n\n"
   "On each turn the coding agent will either ask you to (re)build your repo map or\n"
   "ask a question about the code. Behave according to this role on every turn; it is\n"
   "stated once, here, and stands for the whole session."))

(defn build-message
  "The per-turn message that asks the brain to (re)build its repo map. The HOW lives
  in the session overlay; this states the task and pins the FINAL shape."
  [root]
  (str
   "Build (or rebuild) your repo map of " root " now.\n\n"
   "Work the engine's way — do not read every file at the root:\n"
   "1. With ordinary Clojure, list the source tree under " root " (file-seq),\n"
   "   excluding noise and never reading secrets: .git, .fractal, runs, target,\n"
   "   build, dist, node_modules, dot-directories, dotfiles such as .env, and\n"
   "   binaries. def the file list and group it into a handful of\n"
   "   SUBSYSTEMS by directory and role. Verify the grouping covers every source file.\n"
   "2. Use map-rlm to send ONE child into each subsystem. Give each child only its own\n"
   "   file list, its boundary, and this exact job: read your files (Clojure to read;\n"
   "   lm/map-lm for bounded semantic reads), then FINAL compact EDN\n"
   "   {:subsystem name :purpose <one line> :key-files [{:path :role :symbols [top\n"
   "   public names]}] :entrypoints [..] :depends-on [subsystems-or-libs] :gotchas\n"
   "   [..] :evidence [verbatim quotes or handles] :missing [..]}. A child must read\n"
   "   real files and ground every claim; summarizing from path names alone is failure.\n"
   "3. Aggregate the children's summaries in Clojure (do not regenerate them from\n"
   "   memory; lift them from the returned vars). Build a WHERE-TO-LOOK index mapping\n"
   "   the concerns a coding agent asks about (entrypoints, config, data model, the\n"
   "   core loop / control flow, error handling, persistence, tests, build) to the\n"
   "   subsystems and paths to start from.\n"
   "4. Validate that every source file is accounted for; note what you did not open.\n"
   "   Then (def repo-map ...) the whole thing and (FINAL repo-map) in this shape:\n"
   "   {:root " root " :overview <2-3 grounded sentences> :languages [..]\n"
   "    :subsystems [<children's summaries>] :where-to-look [{:topic :start [paths]}]\n"
   "    :entrypoints [{:path :what}] :glossary [{:term :means}] :missing [..]}\n\n"
   "Keep it compact: names, locations, one-line purposes, key symbols — never file\n"
   "contents. It must be complete enough to navigate to any concern in one hop."))

(defn ask-message
  "The per-turn message carrying a coding agent's query. Lean on purpose — the
  brain role and working rules already live in the session overlay; this delivers
  the question and pins the cited FINAL shape the agent consumes."
  [question]
  (str
   "Coding-agent query:\n\n" question "\n\n"
   "Answer it using your repo map (`repo-map`) to go straight to the relevant files.\n"
   "Delegate deep reads to children (rlm/map-rlm) and bounded semantic reads to\n"
   "leaves; read the CURRENT source to ground every claim. FINAL one compact EDN\n"
   "value the agent can act on without opening the code itself:\n"
   " {:answer <direct, specific answer>\n"
   "  :evidence [{:file <path> :lines \"a-b\" :quote <verbatim>}]\n"
   "  :files-read [<paths>]\n"
   "  :pointers [{:what <what to do / where to go next> :file <path> :lines \"a-b\"}]\n"
   "  :missing [<what you could not determine>]\n"
   "  :map-stale? <true/false, with a note if the map was wrong>}"))

;; ── on-disk brain state (durable, survives invocations like bd's db) ───────────

(defn brain-dir
  "The directory holding this repo's brain. Lives under the runs dir so it travels
  with `.fractal/` discovery, but its turn dirs are nested one level deeper so they
  never show up in `fractal ls` (which only scans immediate run dirs)."
  [runs-dir]
  (str (artifacts/path runs-dir "codebrain")))

(defn- meta-path [bdir] (artifacts/path bdir "meta.edn"))
(defn- map-edn-path [bdir] (artifacts/path bdir "repo-map.edn"))
(defn- map-md-path [bdir] (artifacts/path bdir "repo-map.md"))

(defn load-meta [bdir] (artifacts/read-edn-file (meta-path bdir) nil))
(defn load-map  [bdir] (artifacts/read-edn-file (map-edn-path bdir) nil))

(defn- turn-dir [bdir n] (str (artifacts/path bdir (format "t%04d" (long n)))))

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

(defn persist-map! [bdir m run-dir]
  (artifacts/ensure-dir! bdir)
  (artifacts/write-edn! (map-edn-path bdir) m)
  (spit (str (map-md-path bdir)) (render-map-md m))
  m)

(defn- save-meta! [bdir meta] (artifacts/ensure-dir! bdir) (artifacts/write-edn! (meta-path bdir) meta) meta)

;; ── rendering the answer the agent consumes ────────────────────────────────────

(defn- answer-str [run-dir final usage]
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
         (r/c :dim (str "  brain: " run-dir "   ·   verify: fractal verify " run-dir)))))

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
  "Birth the brain and build its first repo map. Re-running starts a fresh brain
  (the prior turn dirs are left in place for provenance)."
  [pos flags]
  (let [cfg   (cli/cfg-from-opts flags)
        bdir  (brain-dir (:runs-dir cfg))
        root  (repo-root flags)
        dir0  (turn-dir bdir 0)
        s     (session/start-session! cfg {:id "codebrain" :dir dir0 :overlay (session-overlay root)})
        res   (session/run-turn! s (build-message root))
        _     (session/stop-session! s)
        m     (:final-value res)
        ts    (time/now-str)]
    (when (map? m) (persist-map! bdir m dir0))
    (save-meta! bdir {:root root :born-at ts :map-built-at (when (map? m) ts)
                      :origin dir0 :head dir0 :turns 0
                      :provider (:provider flags) :model (:model flags)})
    (if (not= 0 (result-exit res))
      (assoc (err (str "map build did not finalize (status " (:status res) ")"
                       (when (:error res) (str ": " (pr-str (:error res)))) "\n  inspect: fractal show " dir0))
             :exit (result-exit res))
      {:out (str (r/c :bold "codebrain born") " · " root "\n"
                 (if (map? m)
                   (str "  mapped " (count (:subsystems m)) " subsystems → " (str (map-md-path bdir)) "\n"
                        (r/c :dim (str "  ask it: fractal codebrain ask \"where is X handled?\"   ·   see the map: fractal codebrain map")))
                   (r/c :yellow "  (FINAL was not a map; inspect the run)"))
                 "\n" (when-let [u (cli/usage-line dir0)] (str u "\n")))
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
            next-d  (turn-dir bdir n)
            s       (session/resume-session! cfg head {:id "codebrain" :dir next-d})
            res     (session/run-turn! s (ask-message question))
            _       (session/stop-session! s)
            final   (:final-value res)]
        (save-meta! bdir (assoc meta :head next-d :turns n :last-ask-at (time/now-str)))
        (if (and (= 0 (result-exit res)) (map? final))
          {:out (answer-str next-d final (cli/usage-line next-d)) :exit 0}
          (assoc (err (str "ask did not finalize cleanly (status " (:status res) ")"
                           (when (:error res) (str ": " (pr-str (:error res))))
                           "\n  inspect: fractal show " next-d))
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
             (r/c :dim "leash live runs: --max-turns N --max-fanout N --max-leaf-concurrency N --call-timeout-ms MS"))
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
