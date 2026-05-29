(ns fractal-engine.agentcli
  "The agent USE surface for the engine — not an inspector, the whole interface an
  agent works through, the way `bd` is for tasks. One grammar `fractal <verb>
  <subject> [args] [--flags]` covers both halves of the loop:

  DRIVE (do work):                       READ (see what happened):
      fractal run    <task>                  fractal show   <run> [node]   detail; the hub
      fractal resume <run> <task>            fractal tree   <run>          addressable tree
      fractal fork   <run> <task>            fractal prime  <run>          orientation
                                             fractal ls                    list runs
                                             fractal verify <run> [node]   claim-vs-evidence
                                             fractal trace  <run> [node]   provenance
                                             fractal cost   <run>          spend
                                             fractal leaves <run> [node]   leaf I/O
                                             fractal step   <run> [node] N one step
                                             fractal stream <run>          events as JSONL

  Every verb takes `--json`; exit codes mean something (0 final · 1 error ·
  2 no-final · 3 timeout · 5 confabulation-suspected). A drive verb prints the run's
  name so it chains straight into a read verb — `fractal run \"…\"` → `fractal verify
  <run>`. A node address is `root`, `child-0001`, or `child-0001/child-0004` (the
  leading `root/` is implied). `<run>` is a path (`.fractal/foo`) or a bare name
  resolved under the runs dir (`foo` → `.fractal/foo`). Runs live in `.fractal/` in
  the directory you invoke from (discovered up the tree like git/bd); override with
  `--runs-dir DIR`. Provider/model flags match the engine:
  `--provider`, `--model`, `--leaf-model`, `--child-model`, `--fake-script`,
  `--max-turns`, `--max-fanout`, `--call-timeout-ms`."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cli :as cli]
            [fractal-engine.journal :as journal]
            [fractal-engine.projection :as proj]
            [fractal-engine.provenance :as prov]
            [fractal-engine.render :as r]
            [fractal-engine.resume :as resume]
            [fractal-engine.session :as session])
  (:import [java.io File]))

;; ── argument parsing (positionals + flags, no ceremony) ───────────────────────

(defn parse-args
  "Split args into {:pos [..] :flags {..}}. `--k v` is a value flag; `--k` (followed
  by another flag or nothing) is boolean."
  [args]
  (loop [xs args pos [] flags {}]
    (if (empty? xs)
      {:pos pos :flags flags}
      (let [[x & more] xs]
        (if (str/starts-with? x "--")
          (let [k (keyword (subs x 2))]
            (if (and (seq more) (not (str/starts-with? (first more) "--")))
              (recur (rest more) pos (assoc flags k (first more)))
              (recur more pos (assoc flags k true))))
          (recur more (conj pos x) flags))))))

;; ── run + node resolution ─────────────────────────────────────────────────────

(defn- dir? [s] (and s (.isDirectory (File. ^String s))))

(defn resolve-run
  "Resolve a run token to a dir: an existing path wins; otherwise `<runs-dir>/<token>`.
  Returns the dir string or nil."
  [token {:keys [runs-dir]}]
  (let [runs-dir (or runs-dir (cli/default-runs-dir))]
    (cond
      (dir? token) token
      (dir? (str runs-dir "/" token)) (str runs-dir "/" token)
      :else nil)))

(defn node-address
  "Normalize a node token to a projection address. nil/\"root\" → \"root\";
  \"child-0001\" → \"root/child-0001\"; a stray leading \"root/\" is tolerated."
  [token]
  (let [t (some-> token str str/trim)]
    (cond
      (or (nil? t) (str/blank? t) (= "root" t)) "root"
      (str/starts-with? t "root/") t
      :else (str "root/" t))))

;; ── exit codes ────────────────────────────────────────────────────────────────

(defn node-exit [node]
  (cond
    (nil? node)                            1
    (some? (:final node))                  0
    (= :error   (keyword (:status node)))  1
    (= :timeout (keyword (:status node)))  3
    :else                                  2))

;; ── --json helpers ────────────────────────────────────────────────────────────

(defn- json-safe [x]
  (walk/postwalk
   (fn [v]
     (cond
       (symbol? v)                          (str v)
       (ratio? v)                           (double v)
       (instance? java.math.BigDecimal v)   (double v)
       (instance? java.io.File v)           (str v)
       :else v))
   x))

(defn json-str [x] (json/generate-string (json-safe x) {:pretty true}))

;; ── verbs ─────────────────────────────────────────────────────────────────────

(defn- err [msg] {:out msg :exit 1 :err? true})

(defn- with-run
  "Resolve the run for verbs that need one; call (f dir) or return a usage error."
  [pos flags f]
  (if-let [token (first pos)]
    (if-let [dir (resolve-run token flags)]
      (f dir token)
      (err (format "no such run: %s (looked for a dir or %s/%s)"
                   token (or (:runs-dir flags) (cli/default-runs-dir)) token)))
    (err "missing <run> argument")))

(defn cmd-show [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [addr (node-address (second pos))
            node (proj/load-at dir addr)]
        (cond
          (nil? node) (err (format "no node %s in %s" addr token))
          (:json flags) {:out (json-str node) :exit (node-exit node)}
          :else {:out (r/node-str node (cond-> {:exe "fractal" :run token}
                                         (:final flags)  (assoc :final? true)
                                         (:leaves flags) (assoc :leaves? true)))
                 :exit (node-exit node)})))))

(defn cmd-tree [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [t (proj/tree dir)]
        (if (:json flags)
          {:out (json-str t) :exit (node-exit (proj/load-at dir "root"))}
          {:out (r/tree-str dir) :exit (node-exit (proj/load-at dir "root"))})))))

(defn cmd-prime [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [node (proj/load-at dir "root")]
        (if (:json flags)
          {:out (json-str {:run (:session-id node) :status (:status node)
                           :model (:model node) :counts (:counts node)
                           :final (:final node)})
           :exit (node-exit node)}
          {:out (r/prime-str dir {:exe "fractal" :run token}) :exit (node-exit node)})))))

(defn cmd-verify [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [addr (node-address (second pos))
            node (proj/load-at dir addr)]
        (if (nil? node)
          (err (format "no node %s in %s" addr token))
          (let [base   (:root flags)
                checks (prov/check-claims (:final node) base)]
            (if (:deep flags)
              ;; deep: hand the claims back to the engine; IT picks child vs leaves
              (let [cfg     (cli/cfg-from-opts flags)
                    ;; derive the verify-run name from the run's basename, not the
                    ;; token (which may be a path like runs/foo → would nest wrongly)
                    sid     (str (last (str/split (str dir) #"/")) "-verify")
                    s       (session/start-session! cfg (cli/session-start-opts cfg (assoc flags :session sid)))
                    task    (prov/verify-task checks base)
                    result  (session/run-turn! s task)
                    _       (session/stop-session! s)
                    merged  (prov/merge-verdicts checks (:final-value result))
                    refuted (filter #(= :refuted (get-in % [:deep :verdict])) merged)
                    exit    (if (seq refuted) 5 0)]
                (if (:json flags)
                  {:out (json-str {:address addr :verify-run sid :checks merged}) :exit exit}
                  {:out (r/deep-verify-str addr merged sid base) :exit exit}))
              (let [sum  (prov/summarize checks)
                    exit (if (:confabulation-suspected sum) 5 0)]
                (if (:json flags)
                  {:out (json-str {:address addr :summary sum :checks checks}) :exit exit}
                  {:out (r/verify-str addr (:final node) base) :exit exit})))))))))

(defn cmd-trace [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [addr (node-address (second pos))
            p    (prov/node-provenance dir addr)]
        (cond
          (nil? p) (err (format "no node %s in %s" addr token))
          (:json flags) {:out (json-str p) :exit 0}
          :else
          {:out (str (r/c :bold (str "provenance — " addr)) "\n"
                     "  claims: " (count (:claims p)) "  children: " (count (:children p))
                     "  leaves: " (count (:leaves p)) "\n\n"
                     (r/verify-str addr (:final (proj/load-at dir addr))))
           :exit 0})))))

(defn cmd-cost [pos flags]
  (with-run pos flags
    (fn [dir token]
      (if (:json flags)
        (let [u (artifacts/read-edn-file (artifacts/path dir "usage.edn") nil)]
          {:out (json-str (select-keys u [:usage/total-tree :cost/total-tree :usage/children]))
           :exit 0})
        {:out (r/cost-str dir {:exe "fractal" :run token}) :exit 0}))))

(defn cmd-leaves [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [addr (node-address (second pos))
            node (proj/load-at dir addr)]
        (cond
          (nil? node) (err (format "no node %s in %s" addr token))
          (:json flags) {:out (json-str (:leaves node)) :exit (node-exit node)}
          :else {:out (r/node-str node {:exe "fractal" :run token :leaves? true})
                 :exit (node-exit node)})))))

(defn cmd-step [pos flags]
  (with-run pos flags
    (fn [dir token]
      ;; step <run> <node> N  — or  step <run> N (node defaults to root)
      (let [rest-pos (vec (drop 1 pos))
            [node-tok n-tok] (if (re-matches #"\d+" (str (first rest-pos)))
                               [nil (first rest-pos)]
                               [(first rest-pos) (second rest-pos)])
            addr (node-address node-tok)
            n    (some-> n-tok str (Long/parseLong))
            node (proj/load-at dir addr)]
        (cond
          (nil? node) (err (format "no node %s in %s" addr token))
          (nil? n)    (err "missing step number: fractal step <run> [node] N")
          (:json flags) {:out (json-str (nth (:steps node) (dec n) nil)) :exit (node-exit node)}
          :else {:out (r/node-str node {:exe "fractal" :run token :step n}) :exit (node-exit node)})))))

(defn cmd-stream [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [events (journal/read-events dir)]
        ;; one JSON object per line (JSONL) — replayable and pipe-friendly
        {:out (str/join "\n" (map #(json/generate-string (json-safe %)) events))
         :exit 0}))))

(defn cmd-ls [_pos flags]
  (let [runs-dir (or (:runs-dir flags) (cli/default-runs-dir))
        root (File. ^String runs-dir)]
    (if-not (.isDirectory root)
      (err (str "no runs dir: " runs-dir))
      (let [runs (->> (.listFiles root)
                      (filter #(.isDirectory ^File %))
                      (filter #(journal/exists? (str %)))
                      (sort-by #(.getName ^File %)))
            rows (mapv (fn [^File d]
                         (let [node (proj/load-at (str d) "root")]
                           {:run (.getName d)
                            :status (:status node)
                            :steps (get-in node [:counts :steps] 0)
                            :children (get-in node [:counts :children] 0)
                            :final? (some? (:final node))}))
                       runs)]
        (if (:json flags)
          {:out (json-str rows) :exit 0}
          {:out (if (empty? rows)
                  (str "no runs under " runs-dir)
                  (str/join "\n"
                    (for [row rows]
                      (format "%s %-40s %s"
                              (case (keyword (:status row))
                                :final (r/c :green "●") :error (r/c :red "✗")
                                :running (r/c :yellow "◐") (r/c :gray "○"))
                              (:run row)
                              (r/c :dim (format "s%d c%d %s" (:steps row) (:children row)
                                                (if (:final? row) "final" "no-final")))))))
           :exit 0})))))

;; ── drive verbs (do work — the other half of the agent loop) ──────────────────

(defn- result-exit [result]
  (cond
    (= :error   (keyword (:status result)))   1
    (= :timeout (keyword (:status result)))   3
    (contains? result :final-value)           0
    :else                                     2))

(defn- run-name [result] (last (str/split (str (:dir result)) #"/")))

(defn- drive-out [result token flags]
  (let [run (run-name result)]
    (if (:json flags)
      {:out (json-str {:run run :dir (str (:dir result)) :status (:status result)
                       :turn (:turn-id result) :final (:final-value result)
                       :error (:error result)})
       :exit (result-exit result)}
      {:out (str (r/c :bold (str "run " run)) "\n"
                 (r/turn-summary-str (proj/load-node (:dir result)) result {:exe "fractal" :run run})
                 "\n" (r/c :dim (format "  next: fractal show %s   ·   fractal verify %s" run run)))
       :exit (result-exit result)})))

(defn- flags->opts
  "The drive verbs share the engine's option vocabulary with `cli`; pass the parsed
  flags straight through (string keys already match :provider/:model/…)."
  [flags] flags)

(defn cmd-run [pos flags]
  (let [task (or (first pos) (:task flags))]
    (if-not task
      (err "missing <task>: fractal run \"your task\" [--provider … --model …]")
      (let [cfg   (cli/cfg-from-opts (flags->opts flags))
            opts  (cond-> flags (:name flags) (assoc :session (:name flags)))
            s     (session/start-session! cfg (cli/session-start-opts cfg opts))
            result (session/run-turn! s task)]
        (session/stop-session! s)
        (drive-out result (run-name result) flags)))))

(defn cmd-resume [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [task (or (second pos) (:task flags) "Continue and call FINAL.")
            result (resume/resume! (cli/cfg-from-opts (flags->opts flags)) dir task
                                   (cond-> {}
                                     (:turn flags)    (assoc :turn (cli/parse-long-opt (:turn flags)))
                                     (:name flags)    (assoc :id (:name flags))
                                     (:new-dir flags) (assoc :dir (:new-dir flags))))]
        (drive-out result token flags)))))

(defn cmd-fork [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [task    (or (second pos) (:task flags) "Continue.")
            new-dir (or (:new-dir flags)
                        (str (or (:runs-dir flags) (cli/default-runs-dir)) "/"
                             (or (:name flags) (artifacts/session-id))))
            result  (resume/fork! (cli/cfg-from-opts (flags->opts flags)) dir new-dir task
                                  (cond-> {} (:turn flags) (assoc :turn (cli/parse-long-opt (:turn flags)))))]
        (drive-out result (run-name result) flags)))))

;; ── chat: the second brain you talk to (interactive, persistent, resumable) ───

(def ^:private chat-quit #{"/quit" "/exit" "/q" ":quit"})

(defn- run-turn-live!
  "Run one turn while painting a live `◐ thinking…` line that updates in place from
  the journal, on a background thread. Returns the turn result. The status line is
  cleared before the caller prints the settled summary."
  [s task]
  (let [dir (str (:dir s))
        fut (future (session/run-turn! s task))]
    (loop []
      (when (not (realized? fut))
        (let [line (r/progress-line (r/progress-counts dir))]
          (print (str "\r\033[K" line)) (flush))
        (Thread/sleep 300)
        (recur)))
    (print "\r\033[K") (flush)            ; wipe the progress line
    @fut))

(defn cmd-chat [pos flags]
  ;; resume a named/path run if given and it exists; else start a fresh brain
  (let [cfg      (cli/cfg-from-opts (flags->opts flags))
        token    (first pos)
        dir      (when token (resolve-run token flags))
        fresh-id (or (:name flags) token)         ; --name, else the positional, else auto
        s        (cond
                   dir      (session/resume-session! cfg dir)
                   fresh-id (session/start-session! cfg (cli/session-start-opts cfg (assoc flags :session fresh-id)))
                   :else    (session/start-session! cfg (cli/session-start-opts cfg flags)))
        run   (last (str/split (str (:dir s)) #"/"))
        turns (count (:turns (proj/view (:dir s))))]
    (println (str (r/c :bold (str "brain ● " run)) " · "
                  (or (:model flags) (:provider flags) "scripted") " · "
                  turns " turns" (when dir " (resumed)"))
             (str "   " (r/c :dim "talk to it · /quit to leave")))
    (loop []
      (print (r/c :green "› ")) (flush)
      (let [line (read-line)]
        (cond
          (nil? line)                          (do (println) :eof)
          (chat-quit (str/trim line))          :quit
          (str/blank? line)                    (recur)
          :else
          (do
            (let [result (run-turn-live! s line)
                  root   (proj/load-node (:dir s))]
              (println (r/turn-summary-str root result {:exe "fractal" :run run})))
            (recur)))))
    (session/stop-session! s)
    {:out (r/c :dim (format "left %s — resume anytime: fractal chat %s" run run))
     :exit 0}))

;; ── help ──────────────────────────────────────────────────────────────────────

(defn cmd-help [_ _]
  {:out (str (r/c :bold "fractal — recursive LM compute engine; agent use surface") "\n\n"
             "drive (do work):\n"
             "  fractal chat   [run]            talk to it — persistent, resumable\n"
             "  fractal run    \"<task>\" [--provider P --model M --fake-script S]\n"
             "  fractal resume <run> \"<task>\" [--turn N]\n"
             "  fractal fork   <run> \"<task>\" [--turn N --name NAME]\n\n"
             "read (see what happened):\n"
             "  fractal show   <run> [node]     detail; the hub. node defaults to root\n"
             "  fractal tree   <run>            addressable run tree\n"
             "  fractal prime  <run>            compact orientation\n"
             "  fractal ls                      list runs\n"
             "  fractal verify <run> [node]     claim-vs-evidence (confabulation check)\n"
             "  fractal trace  <run> [node]     claim provenance\n"
             "  fractal cost   <run>            spend breakdown\n"
             "  fractal leaves <run> [node]     leaf inputs/outputs\n"
             "  fractal step   <run> [node] N   one step, in full\n"
             "  fractal stream <run>            journal events as JSONL\n\n"
             (r/c :dim "every verb takes --json; node address drops the implied root/ prefix"))
   :exit 0})

;; ── dispatch ──────────────────────────────────────────────────────────────────

(def verbs
  {;; drive
   "chat" cmd-chat "run" cmd-run "resume" cmd-resume "fork" cmd-fork
   ;; read
   "show" cmd-show "tree" cmd-tree "prime" cmd-prime "ls" cmd-ls "list" cmd-ls
   "verify" cmd-verify "trace" cmd-trace "cost" cmd-cost "leaves" cmd-leaves
   "step" cmd-step "stream" cmd-stream "tail" cmd-stream
   ;; meta
   "help" cmd-help "--help" cmd-help "-h" cmd-help})

(defn handles?
  "True for verbs this surface owns. Interactive `chat` and the legacy `inspect`
  stay in `cli`."
  [cmd]
  (contains? verbs cmd))

(defn dispatch
  "Run a verb, returning {:out str :exit int}. Pure except for filesystem reads and
  (for drive verbs) running the engine — no printing, no System/exit (that's -main),
  so the surface is unit-testable."
  [cmd args]
  (let [{:keys [pos flags]} (parse-args args)]
    (binding [r/*color* (boolean (and (System/console) (not (:json flags)) (not (:no-color flags))))]
      (cond
        (or (nil? cmd) (str/blank? (str cmd))) (cmd-help pos flags)
        (verbs cmd)                            ((verbs cmd) pos flags)
        :else (-> (err (str "unknown command: " cmd "\n\n" (:out (cmd-help pos flags))))
                  (assoc :exit 1))))))

(defn -main [& args]
  (let [{:keys [out exit]} (dispatch (first args) (rest args))]
    (when (seq (str out)) (println out))
    (System/exit (or exit 0))))
