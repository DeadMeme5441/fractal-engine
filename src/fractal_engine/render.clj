(ns fractal-engine.render
  "Pure text rendering of the projection/provenance substrate for the agent CLI.
  Every function returns a string; nothing prints or exits (that's `cli`). The
  surface is designed for *recursive reading*: the tree shows every node's address,
  and a node view ends with the exact `inspect --node <addr>` commands to drill into
  its children — so navigating a run is following printed addresses inward.

  Color is a tasteful default on a TTY and off otherwise (and always off for piped
  or --json output), so captured output is stable and agents parse clean text."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.projection :as proj]
            [fractal-engine.provenance :as prov]))

;; ── color (no-op unless enabled) ──────────────────────────────────────────────

(def ^:dynamic *color* false)

(def ^:private codes
  {:dim 2 :bold 1 :red 31 :green 32 :yellow 33 :blue 34 :magenta 35 :cyan 36 :gray 90})

(defn c [style s]
  (if (and *color* (codes style))
    (str "\033[" (codes style) "m" s "\033[0m")
    (str s)))

;; ── small helpers ─────────────────────────────────────────────────────────────

(defn clip [s n]
  (let [s (str s)]
    (if (> (count s) n) (str (subs s 0 (max 0 (dec n))) "…") s)))

(defn- one-line [s] (-> (str s) (str/replace #"\s+" " ") str/trim))

(defn node-arg
  "The CLI-friendly node token for an address: `root` stays `root`, `root/child-0001`
  becomes `child-0001` (the leading `root/` is implied), so drill commands read like
  `fractal show <run> child-0001` — positional, copy-paste, beads-style."
  [address]
  (let [a (str/replace-first (str address) #"^root/?" "")]
    (if (str/blank? a) "root" a)))

(defn- kv [k v] (format "  %-9s %s" k (str v)))

(defn- status-glyph [status]
  (case (keyword status)
    :final   (c :green "●")
    :running (c :yellow "◐")
    :error   (c :red "✗")
    (:stopped :final-reached) (c :gray "○")
    (c :gray "·")))

(defn- pretty [v] (str/trim-newline (with-out-str (pp/pprint v))))

(defn- short-final [final]
  (cond
    (nil? final) "—"
    (map? final)  (str "{" (clip (str/join ", " (map name (keys final))) 60) "}")
    (coll? final) (str (clip (one-line (pr-str final)) 60))
    :else         (clip (one-line (pr-str final)) 60)))

;; ── tree ──────────────────────────────────────────────────────────────────────

(defn tree-str
  "Render the whole run as an addressable tree. Each line: status glyph, the node's
  last address segment, and [steps leaves children] counts, indented by depth."
  [root-dir]
  (let [t (proj/tree root-dir)]
    (str
     (c :bold (str "run " (or (:session-id t) root-dir)))
     "  " (status-glyph (:status t))
     " " (c :dim (str (name (or (:kind t) :root)) " · " (or (:model t) "—")))
     "\n"
     (str/join "\n"
       ;; flatten with proper box-drawing connectors
       (letfn [(walk [node depth ancestors-last]
                 (let [seg (or (last (str/split (str (:address node)) #"/")) (:address node))
                       indent (apply str (map #(if % "    " "│   ") (butlast ancestors-last)))
                       connector (cond (zero? depth) ""
                                       (last ancestors-last) "└── "
                                       :else "├── ")
                       line (format "%s%s%s %s %s%s"
                                    indent connector
                                    (status-glyph (:status node))
                                    (c :cyan seg)
                                    (c :dim (format "[s%d l%d c%d]"
                                                    (get-in node [:counts :steps] 0)
                                                    (get-in node [:counts :leaves] 0)
                                                    (get-in node [:counts :children] 0)))
                                    (if (:label node)
                                      (str "  " (c :gray (clip (one-line (:label node)) 56)))
                                      ""))
                       kids (vec (:children node))]
                   (cons line
                         (mapcat (fn [i ch]
                                   (walk ch (inc depth)
                                         (conj (vec ancestors-last) (= i (dec (count kids))))))
                                 (range) kids))))]
         (walk t 0 []))))))

;; ── node detail (the recursive-read hub) ──────────────────────────────────────

(defn- step-block [s {:keys [full?]}]
  (let [hdr (c :magenta (format "── step %d%s ──" (:n s)
                                (if (:turn s) (str " · turn " (:turn s)) "")))
        code (if full? (:code s) (clip (:code s) 600))
        obs  (if full? (str (:obs s)) (clip (one-line (:obs s)) 400))]
    (str hdr "\n"
         (c :green "▷ wrote") "\n"
         (->> (str/split-lines (str code)) (map #(str "  " %)) (str/join "\n")) "\n"
         (c :yellow "◁ observed") "\n"
         (->> (str/split-lines obs) (map #(str "  " %)) (str/join "\n")))))

(defn- leaf-line [lf]
  (format "  [%s] %s\n        in  %s\n        out %s"
          (str (or (:index lf) (:call-id lf)))
          (c :dim (clip (one-line (:query lf)) 70))
          (clip (one-line (pr-str (:input lf))) 76)
          (c :green (clip (one-line (pr-str (:result lf))) 76))))

(defn node-str
  "Detailed view of one node, ending with the drill commands for its children — the
  recursive-read affordance. Opts: :step (one step full), :leaves?, :final?,
  :exe (program name, default \"fractal\"), :run (the run token the user typed)."
  [node {:keys [step leaves? final? exe run] :as opts}]
  (let [{:keys [address kind model status counts children final session-id]} node
        exe  (or exe "fractal")
        run  (or run (:session-id node))
        show (format "%s show %s" exe run)]
    (cond
      ;; a single step, in full
      step
      (if-let [s (nth (:steps node) (dec step) nil)]
        (step-block s {:full? true})
        (format "no step %d (node has %d)" step (count (:steps node))))

      ;; just the leaves
      leaves?
      (if (seq (:leaves node))
        (str (c :blue (format "leaves (%d) — %s\n" (count (:leaves node)) address))
             (str/join "\n" (map leaf-line (:leaves node))))
        (format "no leaves at %s" address))

      ;; just the final value, in full
      final?
      (str (c :bold (str "FINAL — " address)) "\n"
           (if (nil? final) "—" (pretty final)))

      :else
      (str/join "\n"
        (remove nil?
          [(c :bold (str "node " address "  " (status-glyph status)))
           (kv "session" session-id)
           (kv "kind" (name (or kind :?)))
           (kv "model" (or model "—"))
           (kv "status" (or status "—"))
           (kv "steps" (:steps counts 0))
           (kv "leaves" (:leaves counts 0))
           (kv "children" (:children counts 0))
           (kv "final" (short-final final))
           ""
           ;; steps, clipped
           (when (seq (:steps node))
             (str/join "\n\n" (map #(step-block % {:full? false}) (:steps node))))
           (when (seq (:leaves node))
             (str "\n" (c :blue (format "leaves (%d):" (count (:leaves node)))) "\n"
                  (str/join "\n" (map leaf-line (take 8 (:leaves node))))
                  (when (> (count (:leaves node)) 8)
                    (format "\n  … %d more — %s leaves %s %s"
                            (- (count (:leaves node)) 8) exe run (node-arg address)))))
           ;; children, with drill commands — the recursive-read surface
           (when (seq children)
             (str "\n" (c :blue (format "children (%d) — drill in:" (count children))) "\n"
                  (str/join "\n"
                    (map (fn [ch]
                           (format "  %s %-14s %s\n      %s %s %s"
                                   (status-glyph (:status ch))
                                   (node-arg (:address ch))
                                   (c :gray (clip (one-line (or (:label ch) "")) 48))
                                   (c :dim "↳") show (node-arg (:address ch))))
                         children))))
           (when final
             (str "\n" (c :dim (format "full final:  %s %s --final" show (node-arg address)))
                  "\n" (c :dim (format "verify:      %s verify %s %s" exe run (node-arg address)))))])))))

;; ── verify (claim-vs-evidence) ─────────────────────────────────────────────────

(defn- verdict-glyph [v]
  (case v
    :supported    (c :green "✓ supported")
    :partial      (c :yellow "~ partial")
    :unsupported  (c :red "✗ UNSUPPORTED")
    :file-missing (c :red "✗ file-missing")
    :file-unreadable (c :red "✗ unreadable")
    :no-file-cited (c :gray "· no file cited")
    :unverifiable  (c :gray "· unverifiable")
    (str v)))

(defn verify-str
  "Render claim-vs-evidence for a node's FINAL value: per-claim verdict, the cited
  file, and which identifiers landed vs went missing — so a reader can judge a
  confabulation flag rather than trust one boolean. `base` resolves relative
  citations against the repo root the run worked on."
  ([address final] (verify-str address final nil))
  ([address final base]
  (let [checks (prov/check-claims final base)
        sum    (prov/summarize checks)]
    (str
     (c :bold (str "claim-vs-evidence — " address)) "\n"
     (format "  %d claims · %s\n"
             (:total sum)
             (case (:overall sum)
               :supported (c :green "all supported")
               :mixed     (c :yellow "mixed")
               :suspect   (c :red "CONFABULATION SUSPECTED")
               :no-claims (c :gray "no evidenced claims")
               (str (:overall sum))))
     (when (:confabulation-suspected sum)
       (c :red "  ⚠ at least one claim cites evidence not found in its file\n"))
     "\n"
     (str/join "\n\n"
       (map (fn [ck]
              (str "  " (verdict-glyph (:verdict ck))
                   "  " (c :gray (clip (one-line (str (:label ck))) 60)) "\n"
                   (when (:file ck) (str "     file " (clip (:file ck) 80) "\n"))
                   (when-let [ids (:identifiers ck)]
                     (str "     found " (str/join " " (take 6 (:found ids)))
                          (when (seq (:missing ids))
                            (str "\n     " (c :red "missing ") (str/join " " (take 6 (:missing ids)))))))))
            checks))))))

(defn- deep-glyph [v]
  (case v
    :supported (c :green "✓ supported")
    :refuted   (c :red "✗ REFUTED")
    :unclear   (c :yellow "~ unclear")
    (c :gray "· —")))

(defn deep-verify-str
  "Render deep verify: the free grep floor verdict plus the engine's adversarial
  verdict per claim (the engine chose child-vs-leaves itself). The deep run is named
  so it's inspectable like any other."
  [address checks verify-run base]
  (let [refuted (filter #(= :refuted (get-in % [:deep :verdict])) checks)]
    (str
     (c :bold (str "deep verify — " address)) "\n"
     (format "  %d claims · %s · floor grep + engine judge (run %s)\n\n"
             (count checks)
             (if (seq refuted) (c :red (str (count refuted) " REFUTED — confabulation"))
                 (c :green "none refuted"))
             verify-run)
     (str/join "\n\n"
       (map (fn [ck]
              (let [d (:deep ck)]
                (str "  " (deep-glyph (:verdict d))
                     "  " (c :gray (clip (one-line (str (:label ck))) 56)) "\n"
                     "     floor " (verdict-glyph (:verdict ck)) "\n"
                     (when (:why d) (str "     why   " (clip (one-line (:why d)) 90) "\n"))
                     (when (:quote d) (str "     quote " (c :dim (clip (one-line (str (:quote d))) 90)))))))
            checks)))))

;; ── cost (spend visibility — the answer to runaway worry is the numbers) ──────

(defn- amount
  "Format a {:status :known|:partial|:unknown :known N} measure compactly."
  [m fmt]
  (case (:status m)
    :known   (format fmt (double (:known m)))
    :partial (str (format fmt (double (:known m 0))) (c :yellow (format "+%d?" (:unknown-calls m 0))))
    (c :gray "?")))

(defn- provider-call-count [usage]
  (get-in usage [:usage/total-tree :call/count] 0))

(defn- row-count [usage]
  (get-in usage [:usage/total-tree :call/total-tree-count]
          (get-in usage [:usage/total-tree :call/count] 0)))

(defn- call-count-brief [usage]
  (let [provider (provider-call-count usage)
        rows (row-count usage)]
    (str provider " LLM calls"
         (when (not= provider rows)
           (str " · " rows " rows")))))

(defn- child-subtree-provider-calls [usage]
  (reduce + 0
          (map #(get-in % [:child/usage :usage/total-tree :call/count] 0)
               (get-in usage [:usage/children :children]))))

(defn- child-subtree-rows [usage]
  (reduce + 0
          (map #(get-in % [:child/usage :usage/total-tree :call/total-tree-count]
                        (get-in % [:child/usage :usage/total-tree :call/count] 0))
               (get-in usage [:usage/children :children]))))

(defn- sum-rollups [rollups]
  (let [rollups (vec (remove nil? rollups))
        known (keep #(case (:status %)
                       :known (:known %)
                       :partial (:known % 0)
                       nil)
                    rollups)
        unknown (+ (count (filter #(= :unknown (:status %)) rollups))
                   (reduce + 0 (map #(:unknown-calls % 0) rollups)))]
    (cond
      (empty? rollups) {:status :unknown :call/count 0}
      (and (zero? unknown) (= (count known) (count rollups)))
      {:status :known :known (reduce + 0 known) :call/count (count rollups)}
      (seq known)
      {:status :partial :known (reduce + 0 known) :unknown-calls unknown :call/count (count rollups)}
      :else
      {:status :unknown :call/count (count rollups)})))

(defn- child-cost-rollup [usage]
  (sum-rollups (map #(get-in % [:child/usage :cost/total-tree :cost/usd])
                    (get-in usage [:usage/children :children]))))

(defn- tokens-brief [usage]
  (let [tot (:usage/total-tree usage)]
    (str "in=" (amount (:tokens/input tot) "%.0f")
         " out=" (amount (:tokens/output tot) "%.0f")
         " total=" (amount (:tokens/total tot) "%.0f"))))

(defn- cost-brief [usage]
  (str "$" (amount (get-in usage [:cost/total-tree :cost/usd]) "%.4f")))

(defn- cache-brief [usage]
  (let [cache (get-in usage [:cache/root])
        n (:call/count cache 0)]
    (when (pos? n)
      (str "cache "
           (:cache/hit-count cache 0) " hit / "
           (:cache/miss-count cache 0) " miss / "
           (:cache/unknown-count cache 0) " unknown"
           " · cached=" (amount (:tokens/cached cache) "%.0f")))))

(defn- usage-line* [label usage]
  (str (format "  %-13s" label)
       (call-count-brief usage) " · tokens " (tokens-brief usage)
       " · cost " (cost-brief usage)))

(defn- usage-split-lines [usage]
  (let [root-calls (get-in usage [:usage/root :call/count] 0)
        leaf-calls (get-in usage [:usage/leaf :call/count] 0)
        child-count (get-in usage [:usage/children :child/count] 0)
        child-calls (child-subtree-provider-calls usage)
        child-rows (child-subtree-rows usage)
        child-cost (child-cost-rollup usage)]
    (remove nil?
            [(str "    root       " root-calls " LLM calls · cost "
                  "$" (amount (get-in usage [:cost/root :cost/usd]) "%.4f")
                  (when-let [cache (cache-brief usage)] (str " · " cache)))
             (when (pos? child-count)
               (str "    children   " child-count " sessions · " child-calls
                    " LLM calls"
                    (when (not= child-calls child-rows)
                      (str " · " child-rows " rows"))
                    " · cost $" (amount child-cost "%.4f")))
             (when (pos? leaf-calls)
               (str "    leaves     " leaf-calls " LLM calls · cost "
                    "$" (amount (get-in usage [:cost/leaf :cost/usd]) "%.4f")))])))

(defn usage-report-str
  "Human usage block with both the just-completed turn and cumulative session
  tree. `report` is produced by `artifacts/derive-usage-report`."
  [report]
  (let [turn (:usage/turn report)
        cumulative (:usage/cumulative report)]
    (str
     (c :bold "usage") "\n"
     (when turn
       (str (usage-line* "this turn" turn) "\n"
            (str/join "\n" (usage-split-lines turn)) "\n"))
     (usage-line* "cumulative" cumulative))))

(defn cost-str
  "Spend breakdown for a run: tree total plus per-child cost, read from the
  canonical call facts. Visibility, not a cap."
  [root-locator {:keys [run exe]}]
  (let [exe (or exe "fractal")
        run (or run (:session/id root-locator))
        v   (proj/view root-locator)
        u   (artifacts/derive-usage root-locator (:calls v))
        tot   (:usage/total-tree u)
        cost  (get-in u [:cost/total-tree :cost/usd])
        kids  (get-in u [:usage/children :children])]
    (str
     (c :bold (str "cost — " run)) "\n"
     (kv "calls" (:call/total-tree-count tot (:call/count tot))) "\n"
     (kv "tokens" (str "in " (amount (:tokens/input tot) "%.0f")
                       "  out " (amount (:tokens/output tot) "%.0f")
                       "  total " (amount (:tokens/total tot) "%.0f"))) "\n"
     (kv "cached" (amount (:tokens/cached tot) "%.0f")) "\n"
     (kv "cost" (c :green (str "$" (amount cost "%.4f")))) "\n"
     (when (seq kids)
       (str "\n" (c :blue "by child:") "\n"
            (str/join "\n"
              (map (fn [ch]
                     (let [cu (get-in ch [:child/usage :cost/total-tree :cost/usd])]
                       (format "  %s %-14s $%-10s %s"
                               (status-glyph (:child/status ch))
                               (:child/session-id ch)
                               (amount cu "%.4f")
                               (c :dim (format "%s calls   ↳ %s show %s %s"
                                               (get-in ch [:child/usage :usage/total-tree :call/count] "?")
                                               exe run (:child/session-id ch))))))
                   kids)))))))

;; ── chat: live progress + per-turn summary (the "second brain" you talk to) ───

(defn progress-counts
  "Light tally for the live `◐ thinking…` line: children spawned, steps run, leaves
  judged. A thin projection of `projection/progress` (event-folded, ref-free, safe
  to poll on a live run)."
  [dir]
  (select-keys (proj/progress dir) [:steps :children :leaves]))

(defn progress-line [{:keys [steps children leaves]}]
  (str (c :yellow "◐") " thinking… "
       (when (pos? children) (str children " children · "))
       steps " steps"
       (when (pos? leaves) (str " · " leaves " leaves"))))

(defn- spend-brief [locator turn-id]
  (let [v (proj/view locator)
        report (artifacts/derive-usage-report locator (:calls v) {:turn-id turn-id})
        turn (:usage/turn report)
        cumulative (:usage/cumulative report)
        turn-calls (when turn (call-count-brief turn))
        total-calls (call-count-brief cumulative)]
    (str
     (when turn
       (str "turn " (cost-brief turn) " · " turn-calls))
     (when (and turn cumulative) "  ")
     (when cumulative
       (str "total " (cost-brief cumulative) " · " total-calls)))))

(defn turn-summary-str
  "What chat (and `run`) print after a turn settles: the ● result line, the compact
  final, the spend, and 1–2 'look inside' drill commands into the nodes this turn
  produced — so the conversation stays readable and depth is one command away."
  [root-node result {:keys [exe run]}]
  (let [exe   (or exe "fractal")
        run   (or run (:session-id result))
        st    (keyword (:status result))
        final (:final-value result)
        kids  (take-last 2 (:children root-node))
        evid? (seq (prov/extract-claims final))]
    (str
     (case st :final (c :green "●") :error (c :red "✗") (c :yellow "◐")) " "
     (cond
       (= :error st)                   (str (c :red "error ") (clip (one-line (pr-str (:error result))) 100))
       (contains? result :final-value) (clip (one-line (pr-str final)) 100)
       :else                           (c :yellow "no final this turn"))
     (when-let [sp (spend-brief (:locator result) (:turn-id result))] (str "   " (c :dim sp)))
     (when (or (seq kids) evid?)
       (str "\n"
            (str/join "\n"
              (concat
               (map (fn [ch]
                      (format "  %s %s %s %s"
                              (c :dim "↳") (str exe " show " run) (node-arg (:address ch))
                              (c :gray (clip (one-line (or (:label ch) "")) 36))))
                    kids)
               (when evid?
                 [(format "  %s %s verify %s %s"
                          (c :dim "↳") exe run (c :gray "(check the claims)"))]))))))))

;; ── prime (compact "what is this run") ─────────────────────────────────────────

(defn prime-str
  "A compact orientation: what this run is, its shape, and where to drill — the
  read-side analogue of `bd prime`. `run` is the token the user typed."
  [root-dir {:keys [exe run]}]
  (let [exe  (or exe "fractal")
        run  (or run root-dir)
        t    (proj/tree root-dir)
        node (proj/load-node (proj/node-locator root-dir "root") "root")
        kid-count (fn cnt [n] (reduce + (count (:children n)) (map cnt (:children n))))]
    (str
     (c :bold (str "run " (or (:session-id t) root-dir))) " " (status-glyph (:status t)) "\n"
     (kv "model" (or (:model t) "—")) "\n"
     (kv "status" (or (:status t) "—")) "\n"
     (kv "steps" (get-in t [:counts :steps] 0)) "\n"
     (kv "children" (str (count (:children t)) " direct · " (kid-count t) " total")) "\n"
     (kv "final" (short-final (:final node))) "\n"
     "\n" (c :dim "next:") "\n"
     (let [w (->> [(str exe " show " run " child-0001")
                   (str exe " verify " run)]
                  (map count) (apply max) (max 24) (+ 2))
           row (fn [cmd note] (format (str "  %-" w "s %s") cmd (c :gray note)))]
       (str/join "\n"
         [(row (str exe " tree " run)              "# full addressable tree")
          (row (str exe " show " run " child-0001") "# drill into a child")
          (row (str exe " verify " run)            "# claim-vs-evidence (confabulation check)")
          (row (str exe " cost " run)              "# spend breakdown")])))))
