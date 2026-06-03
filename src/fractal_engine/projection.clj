(ns fractal-engine.projection
  "Pure read-side substrate shared by every rendering of a run. It loads canonical
  Datahike facts plus BlobStore payloads into an addressable node tree.

  Addresses are the whole point of the recursive read: every node has a path like
  `root`, `root/child-0001`, `root/child-0001/child-0004`. A renderer shows a node
  plus the addresses of its children, and the caller drills by re-issuing with the
  child's address. Same scheme on the CLI and (later) in the TUI."
  (:require [clojure.string :as str]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.session-db :as session-db]))

;; ── canonical events → view ───────────────────────────────────────────────────

(defn event-stream
  "Canonical event stream for a session locator, in append order."
  [locator]
  (session-db/load-events (artifacts/store-root-for-locator locator)
                          (artifacts/session-id-for-locator locator)))

(defn view
  "Dereference the current head state for a session locator. Pure; zero provider
  calls. Pass a locator with :head/id to inspect a specific immutable head."
  [locator]
  (session-db/view (artifacts/store-root-for-locator locator)
                   (artifacts/session-id-for-locator locator)
                   (select-keys locator [:head/id])))

(defn history-view
  "Audit view for all facts/events ever recorded for the session."
  [locator]
  (session-db/history-view (artifacts/store-root-for-locator locator)
                           (artifacts/session-id-for-locator locator)))

(defn resolve-ref
  "Resolve a payload blob ref against its session locator.
  Returns ::missing for an unreadable blob, nil for a nil ref."
  [locator ref]
  (when ref (artifacts/read-ref locator ref)))

(defn progress
  "A lightweight, ref-free live snapshot of a session locator, folded straight
  from canonical DB events — correct mid-turn and cheap enough to poll while a turn is in flight (no
  blob reads, no child recursion). Pairs with an async turn: poll this to watch a
  turn settle. For full step/leaf/final detail, use `load-node` instead."
  [locator]
  (let [v       (history-view locator)
        calls   (:calls v)
        leaf?   #(artifacts/leaf-call-types (:call/type %))
        child?  #(artifacts/child-call-types (:call/type %))
        running (filterv #(= :running (:call/status %)) calls)
        status  (get-in v [:session :session/status])]
    {:session-id     (get-in v [:session :session/id])
     :status         status
     :running?       (= :running status)
     :final?         (some? (:final-ref v))
     :turn-count     (count (:turns v))
     :latest-turn-id (get-in v [:session :session/latest-turn-id])
     :steps          (count (:evals v))
     :leaves         (count (filter leaf? calls))
     :children       (count (filter child? calls))
     :calls          {:total   (count calls)
                      :running (count running)
                      :ok      (count (filter #(= :ok (:call/status %)) calls))}
     :in-flight      (mapv #(select-keys % [:call/id :call/type :call/turn-id :batch/index])
                           running)}))

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
  [locator {:keys [calls]}]
  (->> calls
       (filter #(artifacts/leaf-call-types (:call/type %)))
       (sort-by (juxt #(or (:batch/index %) 0) :call/id))
       (mapv (fn [c]
               {:call-id (:call/id c)
                :index   (:batch/index c)
                :query   (:call/query c)
                :input   (resolve-ref locator (:call/input-ref c))
                :result  (resolve-ref locator (:call/result-ref c))
                :status  (:call/status c)}))))

;; ── children (recursive calls: a sub-problem that runs the whole loop) ────────

(defn child-calls
  "Calls that spawned a child session, in spawn order."
  [{:keys [calls]}]
  (->> calls
       (filter #(artifacts/child-call-types (:call/type %)))
       (sort-by (juxt #(or (:batch/index %) 0) :call/id))
       vec))

(defn- current-head-id [v]
  (or (get-in v [:refs :ref/current-head])
      (:head/id (last (:heads v)))))

(defn- invocation-by-call-id [v]
  (into {}
        (keep (fn [inv]
                (when-let [call-id (:invocation/call-id inv)]
                  [call-id inv])))
        (:invocations v)))

(defn- session-exists? [locator]
  (some? (session-db/read-session (artifacts/store-root-for-locator locator)
                                  (artifacts/session-id-for-locator locator))))

(defn child-locator
  "Canonical locator for a child call."
  [parent-locator call]
  (artifacts/child-locator-for-call parent-locator call))

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

(defn- final-value [locator v]
  (when-let [ref (:final-ref v)]
    (resolve-ref locator ref)))

(defn load-node
  "Build ONE node at `address` from a session locator. Heavy fields (steps, leaves,
  resolved final) are realized; children are returned as lightweight *refs*
  (address + label + locator) so a node load is one head-state read, not a whole subtree.
  Drill into a child by calling `load-at` with the child address."
  ([locator] (load-node locator "root"))
  ([locator address]
   (let [v        (view locator)
         ccalls   (child-calls v)
         stepv    (steps v)
         leafv    (leaves locator v)
         kind     (get-in v [:session :session/kind])
         inv-by-call (invocation-by-call-id v)
         child-refs
         (mapv (fn [c]
                 (let [cloc  (child-locator locator c)
                       sid   (:child/session-id c)
                       inv   (get inv-by-call (:call/id c))
                       cv    (when (and cloc (session-exists? cloc))
                               (view cloc))]
                   {:address       (str address "/" sid)
                    :session-id    sid
                    :logical-session-id (:callee/session inv)
                    :locator       cloc
                    :call-id       (:call/id c)
                    :invocation-id (:invocation/id inv)
                    :parent-eval   (:call/parent-eval-id c)
                    :status        (or (:session/status (:session cv)) (:call/status c))
                    :label         (when cv (assigned-task cv))
                    :attached?     (= :attached-child (:call/type c))
                    :handle        (:child/session-handle c)
                    :head-before   (or (:child/head-before c) (:callee/head-before inv))
                    :head-after    (or (:child/head-after c) (:callee/head-after inv))}))
               ccalls)]
     {:address    address
      :locator    locator
      :session-id (get-in v [:session :session/id])
      :logical-session-id (get-in v [:session :session/logical-id])
      :current-head-id (current-head-id v)
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
      :heads      (:heads v)
      :invocations (:invocations v)
      :children   child-refs
      :final      (final-value locator v)})))

;; ── recursive tree (overview; summary nodes, fully expanded) ──────────────────

(defn tree
  "Recursively fold the whole run into a summary tree: each node carries its
  address, label, status, model and counts, plus expanded child nodes. Cheaper per
  node than `load-node` (no step/leaf/final text), so it scales to the whole tree."
  ([locator] (tree locator "root" nil))
  ([locator address label]
   (let [v      (view locator)
         ccalls (child-calls v)
         stepv  (steps v)
         leafv  (leaves locator v)]
     {:address    address
      :locator    locator
      :session-id (get-in v [:session :session/id])
      :logical-session-id (get-in v [:session :session/logical-id])
      :current-head-id (current-head-id v)
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
                          (let [cloc (child-locator locator c)
                                sid  (:child/session-id c)]
                            (if (and cloc (session-exists? cloc))
                              (tree cloc (str address "/" sid) (assigned-task (view cloc)))
                              {:address (str address "/" sid)
                               :session-id sid
                               :status (:call/status c)
                               :label nil
                               :missing? true
                               :children []})))
                        ccalls)})))

;; ── address resolution (the recursive-read primitive) ─────────────────────────

(defn node-locator
  "Resolve a node address (\"root\", \"root/child-0001\", \"root/child-0001/child-0004\")
  to its canonical locator, starting from the root session locator. Returns nil if
  any segment doesn't resolve. The first segment (\"root\") names the root session."
  [root-locator address]
  (let [segs (->> (str/split (str address) #"/")
                  (remove str/blank?))
        segs (if (= "root" (first segs)) (rest segs) segs)]
    (loop [locator root-locator [seg & more] segs]
      (cond
        (nil? seg) locator
        :else
        (let [v      (view locator)
              match  (->> (child-calls v)
                          (filter #(= seg (:child/session-id %)))
                          first)
              cloc   (some->> match (child-locator locator))]
          (when (and cloc (session-exists? cloc))
            (recur cloc more)))))))

(defn load-at
  "Load the full node at `address` within the run rooted at `root-locator`. nil if
  the address doesn't resolve."
  [root-locator address]
  (when-let [locator (node-locator root-locator address)]
    (load-node locator address)))
