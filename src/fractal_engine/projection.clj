(ns fractal-engine.projection
  "Pure read-side substrate shared by every rendering of a run (agent CLI today,
  human TUI later). It folds a session's event journal — `events.ednl`, the source
  of truth, correct even mid-turn — into an *addressable, recursively-navigable*
  node tree. It deliberately reads the journal, never the boundary-materialized
  `.edn` projections (those are stale between turn boundaries).

  Addresses are the whole point of the recursive read: every node has a path like
  `root`, `root/child-0001`, `root/child-0001/child-0004`. A renderer shows a node
  plus the addresses of its children, and the caller drills by re-issuing with the
  child's address. Same scheme on the CLI and (later) in the TUI."
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.event :as event]
            [fractal-engine.journal :as journal])
  (:import [java.nio.file Files LinkOption Path]))

;; ── journal → view ────────────────────────────────────────────────────────────

(defn journal-events
  "Raw event stream for a session dir, in append order. Tolerates concurrent appends
  (a half-written trailing line is dropped), so it's safe to poll a live run."
  [dir]
  (journal/read-events dir))

(defn view
  "Fold a session dir's journal into the materialized view. Pure; zero provider
  calls (events carry results, not recipes)."
  [dir]
  (event/fold (journal/read-events dir)))

(defn resolve-ref
  "Resolve a value-ref ({:value/kind :inline|:blob ...}) against its session dir.
  Returns ::missing for an unreadable blob, nil for a nil ref."
  [dir ref]
  (when ref (artifacts/read-ref dir ref)))

;; ── steps (the chat transcript: what the model wrote, what the host observed) ──

(defn- assistant? [m] (= :assistant (:message/role m)))
(defn- observation? [m] (= :observation (:message/role m)))

(defn strip-fence
  "Drop the ```clojure fences so the bare code shows; the kernel evaluated exactly
  this text."
  [s]
  (-> (str s)
      (str/replace #"(?s)```(?:clojure|clj)?\n?" "")
      (str/replace #"```" "")
      str/trim))

(defn steps
  "Pair each assistant message with the observation the host returned for it. One
  step = one ▷wrote / ◁observed exchange. Numbered from 1."
  [{:keys [messages]}]
  (let [v (vec messages)]
    (->> (map-indexed vector v)
         (keep (fn [[i m]]
                 (when (assistant? m)
                   (let [obs (first (filter observation? (subvec v (inc i))))]
                     {:turn (:message/turn-id m)
                      :code (strip-fence (:message/content m))
                      :raw  (:message/content m)
                      :obs  (:message/content obs)}))))
         (map-indexed (fn [n s] (assoc s :n (inc n))))
         vec)))

;; ── leaves (probabilistic calls: one bounded input → one model judgment) ──────

(defn leaves
  "Leaf calls with their input and result resolved from refs. Ordered by batch
  index then call id."
  [dir {:keys [calls]}]
  (->> calls
       (filter #(artifacts/leaf-call-types (:call/type %)))
       (sort-by (juxt #(or (:batch/index %) 0) :call/id))
       (mapv (fn [c]
               {:call-id (:call/id c)
                :index   (:batch/index c)
                :query   (:call/query c)
                :input   (resolve-ref dir (:call/input-ref c))
                :result  (resolve-ref dir (:call/result-ref c))
                :status  (:call/status c)}))))

;; ── children (recursive calls: a sub-problem that runs the whole loop) ────────

(defn child-calls
  "Calls that spawned a child session, in spawn order."
  [{:keys [calls]}]
  (->> calls
       (filter #(artifacts/child-call-types (:call/type %)))
       (sort-by (juxt #(or (:batch/index %) 0) :call/id))
       vec))

(defn- dir-exists? [^Path p]
  (Files/isDirectory p (make-array LinkOption 0)))

(defn child-dir
  "Absolute on-disk dir for a child call, resolved against its parent dir."
  [parent-dir call]
  (when-let [rel (:child/dir call)]
    (artifacts/path parent-dir rel)))

(defn- section-tail
  "Text after the last occurrence of `marker`, or nil if absent."
  [s marker]
  (let [parts (str/split (str s) (re-pattern (str marker "\\s*")))]
    (when (> (count parts) 1) (last parts))))

(defn- assigned-task
  "A child's distinguishing label, read from its first user message. `map-rlm`
  prefixes a shared instruction under `Assigned child task:` and puts the per-child
  payload under a trailing `Task:` block — that payload is what differs child to
  child, so prefer it. Plain `rlm` has only the `Assigned child task:` tail. Read
  from the child's view so the label survives even mid-run."
  [child-view]
  (let [content (->> (:messages child-view)
                     (filter #(= :user (:message/role %)))
                     first :message/content str)
        tail    (or (section-tail content "Task:")
                    (section-tail content "Assigned child task:")
                    content)]
    (some-> tail str/trim str/split-lines
            (->> (remove str/blank?) first)
            str/trim)))

;; ── one node ──────────────────────────────────────────────────────────────────

(defn- node-model [v]
  (or (get-in v [:session :session/provider :root :provider])
      (get-in v [:session :session/provider :root :model])
      (get-in v [:session :session/provider :model])))

(defn- final-value [dir v]
  (when-let [ref (:final-ref v)]
    (resolve-ref dir ref)))

(defn load-node
  "Build ONE node at `address` from session `dir`. Heavy fields (steps, leaves,
  resolved final) are realized; children are returned as lightweight *refs*
  (address + label + dir) so a node load is one journal fold, not a whole subtree.
  Drill into a child by calling `load-node` (or `tree`) on its dir/address."
  ([dir] (load-node dir "root"))
  ([dir address]
   (let [v        (view dir)
         ccalls   (child-calls v)
         stepv    (steps v)
         leafv    (leaves dir v)
         kind     (get-in v [:session :session/kind])
         child-refs
         (mapv (fn [c]
                 (let [cdir  (child-dir dir c)
                       sid   (:child/session-id c)
                       cv    (when (and cdir (dir-exists? cdir) (journal/exists? cdir))
                               (view cdir))]
                   {:address       (str address "/" sid)
                    :session-id    sid
                    :dir           (str cdir)
                    :call-id       (:call/id c)
                    :parent-eval   (:call/parent-eval-id c)
                    :status        (or (:session/status (:session cv)) (:call/status c))
                    :label         (when cv (assigned-task cv))
                    :attached?     (= :attached-child (:call/type c))}))
               ccalls)]
     {:address    address
      :dir        (str dir)
      :session-id (get-in v [:session :session/id])
      :kind       (or kind (if (= "root" address) :root :child))
      :model      (node-model v)
      :status     (get-in v [:session :session/status])
      :turn-count (count (:turns v))
      :counts     {:steps    (count stepv)
                   :leaves   (count leafv)
                   :children (count child-refs)
                   :calls    (count (:calls v))
                   :evals    (count (:evals v))}
      :steps      stepv
      :leaves     leafv
      :children   child-refs
      :final      (final-value dir v)})))

;; ── recursive tree (overview; summary nodes, fully expanded) ──────────────────

(defn tree
  "Recursively fold the whole run into a summary tree: each node carries its
  address, label, status, model and counts, plus expanded child nodes. Cheaper per
  node than `load-node` (no step/leaf/final text), so it scales to the whole tree."
  ([dir] (tree dir "root" nil))
  ([dir address label]
   (let [v      (view dir)
         ccalls (child-calls v)
         stepv  (steps v)
         leafv  (leaves dir v)]
     {:address    address
      :dir        (str dir)
      :session-id (get-in v [:session :session/id])
      :kind       (or (get-in v [:session :session/kind])
                      (if (= "root" address) :root :child))
      :label      label
      :model      (node-model v)
      :status     (get-in v [:session :session/status])
      :counts     {:steps    (count stepv)
                   :leaves   (count leafv)
                   :children (count ccalls)
                   :calls    (count (:calls v))}
      :children   (mapv (fn [c]
                          (let [cdir (child-dir dir c)
                                sid  (:child/session-id c)]
                            (if (and cdir (dir-exists? cdir) (journal/exists? cdir))
                              (tree cdir (str address "/" sid) (assigned-task (view cdir)))
                              {:address (str address "/" sid)
                               :session-id sid
                               :status (:call/status c)
                               :label nil
                               :missing? true
                               :children []})))
                        ccalls)})))

;; ── address resolution (the recursive-read primitive) ─────────────────────────

(defn node-dir
  "Resolve a node address (\"root\", \"root/child-0001\", \"root/child-0001/child-0004\")
  to its on-disk dir, starting from the root run dir. Returns nil if any segment
  doesn't resolve. The first segment (\"root\") names the run dir itself."
  [root-dir address]
  (let [segs (->> (str/split (str address) #"/")
                  (remove str/blank?))
        segs (if (= "root" (first segs)) (rest segs) segs)]
    (loop [dir (artifacts/path root-dir) [seg & more] segs]
      (cond
        (nil? seg) dir
        :else
        (let [v      (view dir)
              match  (->> (child-calls v)
                          (filter #(= seg (:child/session-id %)))
                          first)
              cdir   (some->> match (child-dir dir))]
          (when (and cdir (dir-exists? cdir))
            (recur cdir more)))))))

(defn load-at
  "Load the full node at `address` within the run rooted at `root-dir`. nil if the
  address doesn't resolve."
  [root-dir address]
  (when-let [dir (node-dir root-dir address)]
    (load-node dir address)))
