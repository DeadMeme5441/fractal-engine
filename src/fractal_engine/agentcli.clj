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
                                             fractal events <run>          audit trace
                                             fractal stream <run>          events as JSONL
                                             fractal inspect <run>         full artifact dump

  Every verb takes `--json`; exit codes mean something (0 final · 1 error ·
  2 no-final · 3 timeout · 5 confabulation-suspected). A drive verb prints the run's
  name so it chains straight into a read verb — `fractal run \"…\"` → `fractal verify
  <run>`. A node address is `root`, `child-0001`, or `child-0001/child-0004` (the
  leading `root/` is implied). `<run>` is a stable session id or alias resolved in
  the session store. The local store root defaults to `.fractal/` in the directory
  you invoke from (discovered up the tree like git/bd); override with `--runs-dir DIR`.
  Provider/model flags match the engine:
  `--provider`, `--model`, `--leaf-model`, `--child-model`, `--fake-script`,
  `--max-turns`, `--max-fanout`, `--max-leaf-concurrency`,
  `--call-timeout-ms`, `--cache-ttl` (5m|1h, default 1h)."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.cliopts :as cli]
            [fractal-engine.codebrain :as codebrain]
            [fractal-engine.config :as config]
            [fractal-engine.inspect :as inspect]
            [fractal-engine.projection :as proj]
            [fractal-engine.provenance :as prov]
            [fractal-engine.render :as r]
            [fractal-engine.resume :as resume]
            [fractal-engine.session :as session]
            [fractal-engine.session-db :as session-db]))

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

(defn resolve-run
  "Resolve a run token to a canonical session locator. Session id wins, then alias."
  [token {:keys [runs-dir]}]
  (let [runs-dir (or runs-dir (cli/default-runs-dir))]
    (when token
      (session-db/resolve-handle runs-dir token {}))))

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
         (instance? java.nio.file.Path v)      (str v)
         :else v))
   x))

(defn json-str [x] (json/generate-string (json-safe x) {:pretty true}))

;; ── verbs ─────────────────────────────────────────────────────────────────────

(defn- err [msg] {:out msg :exit 1 :err? true})

(defn- with-run
  "Resolve the run for verbs that need one; call (f locator token) or return a usage error."
  [pos flags f]
  (if-let [token (first pos)]
    (if-let [locator (resolve-run token flags)]
      (f locator token)
      (err (format "no such run: %s (expected session id or alias under %s)"
                   token (or (:runs-dir flags) (cli/default-runs-dir)))))
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
                    sid     (str token "-verify")
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
    (fn [locator token]
      (if (:json flags)
        (let [v (proj/view locator)
              u (artifacts/derive-usage locator (:calls v))]
          {:out (json-str (select-keys u [:usage/total-tree :cost/total-tree :usage/children]))
           :exit 0})
        {:out (r/cost-str locator {:exe "fractal" :run token}) :exit 0}))))

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
      (let [events (proj/event-stream dir)]
        ;; one JSON object per line (JSONL) — replayable and pipe-friendly
        {:out (str/join "\n" (map #(json/generate-string (json-safe %)) events))
         :exit 0}))))

(defn cmd-events [pos flags]
  (with-run pos flags
    (fn [dir token]
      (let [event-id (cli/parse-long-opt (:event flags))
            limit (cli/parse-long-opt (:limit flags))
            rows (if event-id
                   (proj/event-chain dir event-id)
                   (proj/event-trace dir))]
        (cond
          (and event-id (empty? rows))
          (err (format "no event %s in %s" event-id token))

          (:json flags)
          {:out (json-str (if event-id
                            {:event/id event-id :chain rows}
                            {:events rows}))
           :exit 0}

          :else
          {:out (r/event-trace-str token rows {:exe "fractal"
                                               :event-id event-id
                                               :limit limit})
           :exit 0})))))

(defn cmd-inspect
  "The offline inspector: a full dump of a run's artifacts. `show`/`tree`/`stream`
  are the focused read verbs; `inspect` is the everything-at-once view. Accepts a
  stable session id or alias as a positional (`fractal inspect <run>`)."
  [pos flags]
  (let [token (first pos)
        dir   (when token (resolve-run token flags))]
    (cond
      (nil? token) (err "missing <run>: fractal inspect <run> [--tree --snapshots --handles --json]")
      (nil? dir)   (err (str "no run: " token))
      (:json flags) {:out (json-str (inspect/structured dir {:tree (:tree flags)
                                                             :snapshots (:snapshots flags)
                                                             :handles (:handles flags)}))
                     :exit 0}
      :else         {:out (inspect/summary-string dir flags) :exit 0})))

(defn cmd-ls [_pos flags]
  (let [runs-dir (or (:runs-dir flags) (cli/default-runs-dir))
        aliases (into {} (map (juxt :alias/session :alias/name))
                      (session-db/list-alias-records runs-dir))
        rows (mapv (fn [session]
                     (let [locator (session-db/locator runs-dir (:session/id session))
                           node (proj/load-at locator "root")]
                       {:run (or (get aliases (:session/id session))
                                 (:session/id session))
                        :session/id (:session/id session)
                        :locator locator
                        :status (:status node)
                        :steps (get-in node [:counts :steps] 0)
                        :children (get-in node [:counts :children] 0)
                        :final? (some? (:final node))}))
                   (session-db/list-session-records runs-dir))]
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
       :exit 0})))

(defn cmd-store [pos flags]
  (let [runs-dir (or (:runs-dir flags) (cli/default-runs-dir))
        subcmd (first pos)]
    (case subcmd
      "check"
      (let [check-consistency (requiring-resolve 'fractal-engine.store.consistency/check-consistency)
            report (check-consistency runs-dir)]
        (if (:json flags)
          {:out (json-str report) :exit (if (= :ok (:status report)) 0 1)}
          {:out (if (= :ok (:status report))
                  "store ok"
                  (with-out-str
                    (println "store issues:" (:issue-count report))
                    (doseq [issue (:issues report)]
                      (println " " (:issue/type issue) (dissoc issue :issue/type)))))
           :exit (if (= :ok (:status report)) 0 1)}))

      "rebuild-index"
      (let [rebuild! (requiring-resolve 'fractal-engine.store.index/rebuild!)
            schema (var-get (requiring-resolve 'fractal-engine.store.schema/schema))
            check-consistency (requiring-resolve 'fractal-engine.store.consistency/check-consistency)]
        (rebuild! runs-dir schema)
        (let [report (check-consistency runs-dir {:mode :quick})]
          (if (:json flags)
            {:out (json-str report) :exit (if (= :ok (:status report)) 0 1)}
            {:out (if (= :ok (:status report))
                    "index rebuilt"
                    (str "index rebuilt with issues: " (:issue-count report)))
             :exit (if (= :ok (:status report)) 0 1)})))

      (err "usage: fractal store check|rebuild-index [--runs-dir DIR] [--json]"))))

;; ── drive verbs (do work — the other half of the agent loop) ──────────────────

(defn- ensure-config!
  "Run the first-time setup wizard when no config file exists and the user
  hasn't supplied --provider on the command line. Returns flags, potentially
  augmented with :provider and :model from the wizard so the current run
  proceeds without a restart."
  [flags]
  (let [store-root (or (:runs-dir flags) (cli/default-runs-dir))]
    (if (or (:provider flags) (config/config-exists? store-root))
      flags
      (merge flags (config/run-setup! store-root)))))

(defn- result-exit [result]
  (cond
    (= :error   (keyword (:status result)))   1
    (= :timeout (keyword (:status result)))   3
    (contains? result :final-value)           0
    :else                                     2))

(defn- run-name [result] (:session-id result))

(defn- drive-out [result token flags]
  (let [run (run-name result)]
    (if (:json flags)
      {:out (json-str {:run run :locator (:locator result) :status (:status result)
                       :turn (:turn-id result) :final (:final-value result)
                       :error (:error result)})
       :exit (result-exit result)}
      {:out (str (r/c :bold (str "run " run)) "\n"
                 (r/turn-summary-str (proj/load-node (:locator result)) result {:exe "fractal" :run run})
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
      (let [flags (ensure-config! flags)
            cfg   (cli/cfg-from-opts (flags->opts flags))
            opts  (cond-> flags (:name flags) (assoc :session (:name flags)))
            s     (session/start-session! cfg (cli/session-start-opts cfg opts))
            result (session/run-turn! s task)]
        (session/stop-session! s)
        (drive-out result (run-name result) flags)))))

(defn cmd-resume [pos flags]
  (let [flags (ensure-config! flags)]
    (with-run pos flags
      (fn [dir token]
        (let [task (or (second pos) (:task flags) "Continue and call FINAL.")
              result (resume/resume! (cli/cfg-from-opts (flags->opts flags)) dir task
                                     (cond-> {}
                                       (:turn flags) (assoc :turn (cli/parse-long-opt (:turn flags)))
                                       (:name flags) (assoc :id (:name flags))))]
          (drive-out result token flags))))))

(defn cmd-fork [pos flags]
  (let [flags (ensure-config! flags)]
    (with-run pos flags
      (fn [dir token]
        (let [task    (or (second pos) (:task flags) "Continue.")
              sid     (or (:name flags) (artifacts/session-id))
              result  (resume/fork! (cli/cfg-from-opts (flags->opts flags)) dir nil task
                                    (cond-> {:id sid :alias sid}
                                      (:turn flags) (assoc :turn (cli/parse-long-opt (:turn flags)))))]
          (drive-out result (run-name result) flags))))))

;; ── chat: the second brain you talk to (interactive, persistent, resumable) ───

(def ^:private chat-quit #{"/quit" "/exit" "/q" ":quit"})

(defn- run-turn-live!
  "Run one turn while painting a live `◐ thinking…` line that updates in place from
  canonical event facts. The turn runs on the engine's own async primitive (a daemon thread);
  we poll its progress view for the live line and the returned promise for completion. The
  status line is cleared before the caller prints the settled summary."
  [s task]
  (let [locator (:locator s)
        result (session/run-turn-async! s task)]
    (loop []
      (when (not (realized? result))
        (let [line (r/progress-line (r/progress-counts locator))]
          (print (str "\r\033[K" line)) (flush))
        (Thread/sleep 300)
        (recur)))
    (print "\r\033[K") (flush)            ; wipe the progress line
    @result))

(defn cmd-chat [pos flags]
  ;; resume a named run if given and it exists; else start a fresh brain
  (let [flags    (ensure-config! flags)
        cfg      (cli/cfg-from-opts (flags->opts flags))
        token    (first pos)
        locator  (when token (resolve-run token flags))
        fresh-id (or (:name flags) token)         ; --name, else the positional, else auto
        s        (cond
                   locator  (session/resume-session! cfg locator)
                   fresh-id (session/start-session! cfg (cli/session-start-opts cfg (assoc flags :session fresh-id)))
                   :else    (session/start-session! cfg (cli/session-start-opts cfg flags)))
        run   (get-in @(:state s) [:session :session/id])
        turns (count (:turns (proj/view (:locator s))))]
    (println (str (r/c :bold (str "brain ● " run)) " · "
                  (or (:model flags) (:provider flags) "scripted") " · "
                  turns " turns" (when locator " (resumed)"))
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
                  root   (proj/load-node (:locator s))]
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
             "  fractal fork   <run> \"<task>\" [--turn N --name NAME]\n"
             "  fractal codebrain <init|ask|map|status>   persistent code-discovery brain\n\n"
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
             "  fractal events <run>            audit trace; --event N, --limit N\n"
             "  fractal stream <run>            canonical events as JSONL\n"
             "  fractal inspect <run>           full artifact dump (everything at once)\n\n"
             "store:\n"
             "  fractal store check              validate SQLite facts, derived index, and blob refs\n\n"
             (r/c :dim "every verb takes --json; node address drops the implied root/ prefix"))
   :exit 0})

;; ── dispatch ──────────────────────────────────────────────────────────────────

(def verbs
  {;; drive
   "chat" cmd-chat "run" cmd-run "resume" cmd-resume "fork" cmd-fork
   ;; codebrain — a persistent code-discovery brain (its own sub-grammar)
   "codebrain" codebrain/command "cb" codebrain/command
   ;; read
   "show" cmd-show "tree" cmd-tree "prime" cmd-prime "ls" cmd-ls "list" cmd-ls
   "verify" cmd-verify "trace" cmd-trace "cost" cmd-cost "leaves" cmd-leaves
   "step" cmd-step "events" cmd-events "event-log" cmd-events
   "stream" cmd-stream "tail" cmd-stream "inspect" cmd-inspect
   "store" cmd-store "check" cmd-store
   ;; meta
   "help" cmd-help "--help" cmd-help "-h" cmd-help})

(defn handles?
  "True for verbs this surface owns. It now owns the whole CLI — drive, read, chat,
  codebrain, and inspect — so this is effectively every command."
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
