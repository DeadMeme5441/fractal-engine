(ns spike.inspector
  "SPIKE — fulcro-tui as a chat + inspector for the engine. Three panes:
  left = session tree (run -> steps -> children -> leaves), center = the chat
  transcript of the selected node (what the model wrote / observed), right =
  metadata. A chat input at the bottom drives the engine: type a task, the rlm
  runs (background thread), the panes refresh. Multi-turn on one live session.

  Offline by default (scripted fake provider, no keys). Live:
    FRACTAL_PROVIDER=codex-backend FRACTAL_MODEL=gpt-5.5 clojure -M:inspector
  Browse an existing run read-only:
    FRACTAL_RUN=runs/backend-gemini clojure -M:inspector

  Keys: j/k move · l/⏎ expand · h collapse/up · g/G top/bottom · J/K scroll
        i compose · Esc browse · ctrl-q quit.  Not for ship."
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.components :as comp]
   [com.fulcrologic.fulcro.mutations :as m]
   [com.fulcrologic.fulcro.tui.application :as app]
   [com.fulcrologic.fulcro.tui.engine :as engine]
   [com.fulcrologic.fulcro.tui.elements :as e
    :refer [vbox hbox box text input line]]
   [fractal-engine.artifacts :as artifacts]
   [fractal-engine.process :as process]
   [fractal-engine.scripts :as scripts]
   [fractal-engine.session :as session]))

(def ^:private side-h 40)   ; rows shown in the tree pane
(def ^:private center-h 40) ; rows shown in the transcript pane

;; ── helpers ──────────────────────────────────────────────────────────────────

(defn- clip [s n]
  (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) " …") s)))

(defn- strip-fence [s]
  (-> (str s)
      (str/replace #"(?s)```(?:clojure|clj)?\n?" "")
      (str/replace #"```" "")
      str/trim))

(defn- read-edn* [f d] (try (edn/read-string (slurp f)) (catch Throwable _ d)))

(defn- rref [dir ref]
  (when ref
    (case (:value/kind ref)
      :inline (:value ref)
      :blob   (read-edn* (str dir "/" (:path ref)) :unreadable)
      nil)))

;; ── load the recursive run tree from a session dir ────────────────────────────

(defn- steps-of [msgs]
  (let [v (vec msgs)]
    (->> (map-indexed vector v)
         (keep (fn [[i m]]
                 (when (= :assistant (:message/role m))
                   (let [obs (first (filter #(= :observation (:message/role %)) (subvec v (inc i))))]
                     {:turn (:message/turn-id m)
                      :code (strip-fence (:message/content m))
                      :obs  (clip (:message/content obs) 1400)}))))
         (map-indexed (fn [n s] (assoc s :n (inc n))))
         vec)))

(defn- leaves-of [dir calls]
  (->> calls
       (filter #(#{:leaf :leaf-batch-item} (:call/type %)))
       (mapv (fn [c]
               {:idx    (:batch/index c)
                :input  (clip (pr-str (rref dir (:call/input-ref c))) 400)
                :output (clip (pr-str (rref dir (:call/result-ref c))) 400)}))))

(defn- child-label [cdir]
  (let [task     (->> (read-edn* (str cdir "/messages.edn") [])
                      (filter #(= :user (:message/role %))) first :message/content str)
        assigned (last (str/split task #"Assigned child task:\s*"))]
    (clip (first (str/split-lines (str/trim assigned))) 44)))

(defn- load-node [dir id label kind]
  (let [msgs  (read-edn* (str dir "/messages.edn") [])
        calls (read-edn* (str dir "/calls.edn") [])
        sess  (read-edn* (str dir "/session.edn") {})
        usage (read-edn* (str dir "/usage.edn") {})]
    {:id       id
     :label    label
     :kind     kind
     :model    (or (get-in sess [:session/provider :root :model])
                   (get-in sess [:session/provider :model]))
     :status   (:session/status sess)
     :cost     (get-in usage [:cost/total-tree :cost/usd])
     :steps    (steps-of msgs)
     :leaves   (leaves-of dir calls)
     :children (->> calls
                    (filter :child/dir)
                    (mapv (fn [c]
                            (let [cdir (str dir "/" (:child/dir c))]
                              (load-node cdir (str id "/" (:child/session-id c))
                                         (str (:child/session-id c) " · " (child-label cdir))
                                         :child)))))}))

(defn load-run-tree [dir]
  (load-node dir "root" (str "● " (last (str/split (str dir) #"/"))) :root))

(defn- empty-tree []
  {:id "root" :label "● new session — press i, type a task, ⏎"
   :kind :root :steps [] :leaves [] :children []})

(defn- visible-flat
  ([tree expanded] (visible-flat tree expanded 0 []))
  ([node expanded depth acc]
   (let [acc (conj acc (assoc node :depth depth
                              :open? (contains? expanded (:id node))
                              :has-kids (boolean (seq (:children node)))))]
     (if (contains? expanded (:id node))
       (reduce (fn [a c] (visible-flat c expanded (inc depth) a)) acc (:children node))
       acc))))

;; ── live session + app handles (mutated from the background run thread) ───────

(defonce session-ref (atom nil))
(defonce app-ref     (atom nil))
(defonce initial     (atom nil))   ; initial db, computed in -main before app build

;; ── mutations ─────────────────────────────────────────────────────────────────

(m/defmutation set-input  [{:keys [v]}]  (action [{:keys [state]}] (swap! state assoc :chat/input v)))
(m/defmutation set-status [{:keys [s e]}](action [{:keys [state]}] (swap! state assoc :chat/status s :chat/error e)))

(m/defmutation set-tree [{:keys [tree status]}]
  (action [{:keys [state]}]
    (swap! state assoc :run/tree tree :nav/cursor 0 :nav/scroll 0
           :nav/expanded #{(:id tree)} :chat/status (or status :idle) :chat/error nil)))

(m/defmutation nav-move [{:keys [d]}]
  (action [{:keys [state]}]
    (let [{:run/keys [tree] :nav/keys [cursor expanded]} @state
          n (count (visible-flat tree expanded))]
      (swap! state assoc :nav/cursor (max 0 (min (dec n) (+ cursor d))) :nav/scroll 0))))

(m/defmutation nav-top    [_] (action [{:keys [state]}] (swap! state assoc :nav/cursor 0 :nav/scroll 0)))
(m/defmutation nav-bottom [_] (action [{:keys [state]}]
                                (let [{:run/keys [tree] :nav/keys [expanded]} @state]
                                  (swap! state assoc :nav/cursor (dec (count (visible-flat tree expanded))) :nav/scroll 0))))
(m/defmutation scroll [{:keys [d]}] (action [{:keys [state]}] (swap! state update :nav/scroll #(max 0 (+ (or % 0) d)))))

(m/defmutation nav-expand [_]
  (action [{:keys [state]}]
    (let [{:run/keys [tree] :nav/keys [cursor expanded]} @state
          sel (nth (visible-flat tree expanded) cursor nil)]
      (when (and sel (:has-kids sel)) (swap! state update :nav/expanded conj (:id sel))))))

(m/defmutation nav-collapse [_]
  (action [{:keys [state]}]
    (let [{:run/keys [tree] :nav/keys [cursor expanded]} @state
          flat (visible-flat tree expanded)
          sel  (nth flat cursor nil)]
      (cond
        (and sel (:has-kids sel) (contains? expanded (:id sel)))
        (swap! state update :nav/expanded disj (:id sel))
        (and sel (pos? (:depth sel)))
        (let [pidx (some (fn [i] (when (< (:depth (nth flat i)) (:depth sel)) i))
                         (range (dec cursor) -1 -1))]
          (when pidx (swap! state assoc :nav/cursor pidx)))
        :else nil))))

;; ── run a turn on the live session (background thread) ────────────────────────

(defn submit! [this v]
  (let [task (str/trim (str v))]
    (when (and (seq task) @session-ref)
      (comp/transact! this [(set-input {:v ""}) (set-status {:s :running})])
      (engine/focus! @app-ref nil)
      (future
        (try
          (let [result (session/run-turn! @session-ref task)
                tree   (load-run-tree (str (:dir result)))]
            (comp/transact! @app-ref [(set-tree {:tree tree :status :idle})]))
          (catch Throwable t
            (comp/transact! @app-ref [(set-status {:s :error :e (.getMessage t)})])))))))

;; ── render ────────────────────────────────────────────────────────────────────

(defn- kind-color [kind] (if (= :child kind) :cyan :magenta))

(defn- sidebar [flat cursor]
  (let [start (max 0 (min (- cursor (quot side-h 2)) (max 0 (- (count flat) side-h))))
        rows  (->> flat (drop start) (take side-h) (map-indexed (fn [i n] [(+ start i) n])))]
    (apply vbox {}
      (for [[idx n] rows
            :let [marker (cond (not (:has-kids n)) "· "
                               (:open? n)          "▾ "
                               :else               "▸ ")]]
        (text {:highlight (= idx cursor) :color (kind-color (:kind n)) :bold (= idx cursor)}
          (str (apply str (repeat (* 2 (:depth n)) " ")) marker (clip (:label n) 28)))))))

(defn- node-lines [node]
  (vec
    (concat
      (mapcat (fn [s]
                (concat
                  [{:t (str "── STEP " (:n s) (when (:turn s) (str " · turn " (:turn s))) " ──") :c :magenta :b true}
                   {:t "▷ wrote" :c :green}]
                  (map (fn [l] {:t l}) (str/split-lines (clip (:code s) 1600)))
                  [{:t "◁ observed" :c :yellow}]
                  (map (fn [l] {:t l :c :white}) (str/split-lines (clip (:obs s) 1400)))
                  [{:t ""}]))
              (:steps node))
      (when (seq (:leaves node))
        (cons {:t (str "── LEAVES (" (count (:leaves node)) ") ──") :c :blue :b true}
              (mapcat (fn [lf] [{:t (str "  [" (:idx lf) "] in:  " (:input lf))}
                                {:t (str "       out: " (:output lf)) :c :green}])
                      (take 12 (:leaves node)))))
      (when (and (empty? (:steps node)) (empty? (:leaves node)))
        [{:t "(no steps yet — send a task)" :c :bright-black}]))))

(defn- transcript [node scroll]
  (let [lines (node-lines node)]
    (apply vbox {}
      (for [{:keys [t c b]} (->> lines (drop scroll) (take center-h))]
        (text {:color (or c :bright-white) :bold (boolean b) :wrap true} (or t ""))))))

(defn- meta-pane [node run-id status]
  (vbox {}
    (text {:bold true :color :yellow} "META")
    (line {})
    (text {} (str "node    " (clip (:label node) 28)))
    (text {} (str "kind    " (name (or (:kind node) :?))))
    (text {} (str "model   " (or (:model node) "—")))
    (text {} (str "status  " (or (:status node) "—")))
    (text {} (str "steps   " (count (:steps node))))
    (text {} (str "leaves  " (count (:leaves node))))
    (text {} (str "kids    " (count (:children node))))
    (text {} (str "cost    " (let [c (:cost node)] (if (map? c) (str (:status c)) c))))
    (line {})
    (text {:color :bright-black} (str "run " run-id))
    (text {:color (case status :running :yellow :error :red :green)}
      (str "● " (name (or status :idle))))))

(comp/defsc Root [this {:run/keys [tree] :nav/keys [cursor expanded scroll]
                        :chat/keys [input status error]}]
  {:query         [:run/tree :nav/cursor :nav/expanded :nav/scroll :chat/input :chat/status :chat/error]
   :initial-state (fn [_] @initial)}
  (let [flat (visible-flat tree expanded)
        sel  (nth flat (min cursor (dec (count flat))) tree)]
    (vbox {:padding 0 :bg :black}
      (text {:bold true :color :bright-magenta :bg :black}
        (str " fractal · chat + inspector (spike) · " (:label tree)
             (when (= status :running) "   ⏳ running…")
             (when (= status :error) (str "   ✗ " (clip error 60)))))
      (hbox {:grow 1 :bg :black}
        ;; sidebar is the focusable "browse" anchor: first in document order, so it
        ;; is auto-focused at start (browse mode) and j/k/l/h bubble to the keymap.
        (box {:id :browse :focusable? true :width 32 :border? true :padding 0 :bg :black}
          (sidebar flat cursor))
        (box {:grow 1 :border? true :padding 1 :bg :black}   (transcript sel scroll))
        (box {:width 34 :border? true :padding 1 :bg :black} (meta-pane sel (:label tree) status)))
      (hbox {:height 1}
        (text {:width 2 :color :green} "› ")
        (e/input {:id        :chat
                :grow      1
                :value     (or input "")
                :on-change (fn [v _] (comp/transact! this [(set-input {:v v})]))
                :on-submit (fn [v] (submit! this v))
                :on-key    (fn [ev] (when (= :escape (:key ev)) (engine/focus! @app-ref :browse)))}))
      (text {:color :bright-black}
        " j/k move · l/⏎ expand · h up · g/G ends · J/K scroll · i compose · Esc browse · ctrl-q quit"))))

;; ── keymap + entrypoint ────────────────────────────────────────────────────────

(defn- keymap []
  {[:ctrl "q"] (fn [a _] (app/quit! a))
   "q"  (fn [a _] (app/quit! a))
   "j"  (fn [a _] (comp/transact! a [(nav-move {:d 1})]))
   "k"  (fn [a _] (comp/transact! a [(nav-move {:d -1})]))
   "l"  (fn [a _] (comp/transact! a [(nav-expand {})]))
   :enter (fn [a _] (comp/transact! a [(nav-expand {})]))
   "h"  (fn [a _] (comp/transact! a [(nav-collapse {})]))
   "g"  (fn [a _] (comp/transact! a [(nav-top {})]))
   "G"  (fn [a _] (comp/transact! a [(nav-bottom {})]))
   "J"  (fn [a _] (comp/transact! a [(scroll {:d 3})]))
   "K"  (fn [a _] (comp/transact! a [(scroll {:d -3})]))
   "i"  (fn [a _] (engine/focus! a :chat))})

(defn- fake-responder []
  ;; offline demo: 2 root steps + one child, so there's a real tree to browse.
  (fn [req]
    (let [c     (str (:message/content (last (:request/messages req))))
          obs?  (str/includes? c "Observation:")
          child? (str/includes? c "Assigned child task")]
      (cond
        child? "```clojure\n(FINAL {:child-result 42 :note \"fake child\"})\n```"
        obs?   "```clojure\n(FINAL {:answer \"fake completed\" :kid kid})\n```"
        :else  "```clojure\n(def parts (range 3))\n(def kid (rlm \"demo child task\"))\n{:spawned true :parts (count parts)}\n```"))))

(defn- gem [m] {:provider :vertex-gemini :model m})

(defn- env-cfg []
  ;; Default: live Vertex Gemini with the split that worked in runs/backend-gemini
  ;; (strong pro root, cheap flash leaf/child). Needs GOOGLE_CLOUD_PROJECT /
  ;; GOOGLE_CLOUD_LOCATION exported into the JVM env + ADC. Override with
  ;; FRACTAL_PROVIDER (+ FRACTAL_MODEL); FRACTAL_PROVIDER=scripted for offline fake.
  (let [p (System/getenv "FRACTAL_PROVIDER")]
    (cond
      (= p "scripted")
      (process/config {:runs-dir "runs" :scripted/response-fn (fake-responder)})

      p
      (let [m  (or (System/getenv "FRACTAL_MODEL") "gpt-5.5")
            mk #(hash-map :provider (keyword p) :model %)]
        (process/config {:runs-dir "runs" :models {:root (mk m) :leaf (mk m) :child (mk m)}}))

      :else
      (process/config {:runs-dir "runs"
                       :models {:root  (gem (or (System/getenv "FRACTAL_MODEL") "gemini-3.1-pro-preview"))
                                :leaf  (gem "gemini-3.1-flash-lite-preview")
                                :child (gem "gemini-3.5-flash")}}))))

(defn -main [& _]
  (let [cfg     (env-cfg)
        run-env (System/getenv "FRACTAL_RUN")
        tree    (if run-env
                  (load-run-tree run-env)
                  (let [sid (artifacts/session-id)]
                    (reset! session-ref
                            (session/start-session! cfg {:id sid :dir (artifacts/path (:runs-dir cfg) sid)}))
                    (empty-tree)))]
    (reset! initial {:run/tree tree :nav/cursor 0 :nav/scroll 0
                     :nav/expanded #{(:id tree)} :chat/input "" :chat/status :idle :chat/error nil})
    (let [a (app/application {:root-class Root :global-keymap (keymap)})]
      (reset! app-ref a)
      (app/run-blocking! a {:global-keymap (keymap)}))))
