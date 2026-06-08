(ns fractal.engine.cli
  "Agent-operable CLI/control plane over fractal.engine.api.

   This namespace is intentionally a thin executable seam. It does not introduce
   product nouns or alternate runtime semantics: commands open a durable SDK
   session, perform one usage/inspection action, emit JSON/EDN, and close."
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fractal.engine.api :as fe]
            [fractal.engine.kernel :as kernel]
            [fractal.engine.payload :as payload]
            [fractal.engine.store :as store]))

;; ---------------------------------------------------------------------------
;; CLI parsing
;; ---------------------------------------------------------------------------

(def commands
  #{"help" "init" "config"
    "start" "run" "turn" "resume" "stop" "close" "compact" "wait"
    "status" "progress" "view" "events" "heads" "head" "edges" "tree"
    "payload" "messages" "turns" "steps" "evals" "check" "report"})

(def option-spec
  {"--config" :config
   "--profile" :profile
   "--session" :session
   "--id" :session
   "--store-dir" :store-dir
   "--store" :store
   "--adapter" :adapter
   "--provider" :provider
   "--model" :model
   "--harness" :harness
   "--capability" :capability
   "--child-provider" :child-provider
   "--child-model" :child-model
   "--leaf-provider" :leaf-provider
   "--leaf-model" :leaf-model
   "--max-turns" :max-turns
   "--max-steps" :max-steps
   "--max-fanout" :max-fanout
   "--fanout-pool" :fanout-pool
   "--leaf-concurrency" :leaf-concurrency
   "--call-timeout-ms" :call-timeout-ms
   "--cache-ttl" :cache-ttl
   "--message" :message
   "--message-file" :message-file
   "--since" :since
   "--head" :head
   "--ref" :ref
   "--output" :output
   "--fake-final" :fake-final})

(def flag-spec
  {"--json" [:output :json]
   "--edn" [:output :edn]
   "--pretty" [:pretty true]
   "--wait" [:wait true]
   "--create" [:create true]
   "--force" [:force true]})

(def int-options #{:max-turns :max-steps :max-fanout :fanout-pool
                   :leaf-concurrency :call-timeout-ms :since})
(def keyword-options #{:store :adapter :provider :harness :capability
                       :child-provider :leaf-provider :output})

(defn- parse-int* [s k]
  (try
    (Long/parseLong (str s))
    (catch NumberFormatException e
      (throw (ex-info (str "expected integer for " (name k))
                      {:error/type :cli/bad-option :option k :value s}
                      e)))))

(defn- parse-option-value [k v]
  (cond
    (int-options k) (parse-int* v k)
    (keyword-options k) (keyword v)
    :else v))

(defn parse-args [argv]
  (let [[cmd argv] (if (and (seq argv) (commands (first argv)))
                     [(first argv) (rest argv)]
                     ["help" argv])]
    (loop [xs (seq argv) opts {} pos []]
      (if-let [x (first xs)]
        (cond
          (contains? flag-spec x)
          (let [[k v] (flag-spec x)]
            (recur (next xs) (assoc opts k v) pos))

          (contains? option-spec x)
          (let [k (option-spec x)
                v (second xs)]
            (when (nil? v)
              (throw (ex-info (str "missing value for " x)
                              {:error/type :cli/missing-option-value :option x})))
            (recur (nnext xs) (assoc opts k (parse-option-value k v)) pos))

          (str/starts-with? x "--")
          (throw (ex-info (str "unknown option: " x)
                          {:error/type :cli/unknown-option :option x}))

          :else
          (recur (next xs) opts (conj pos x)))
        {:command cmd :opts opts :args pos}))))

;; ---------------------------------------------------------------------------
;; Config
;; ---------------------------------------------------------------------------

(def default-session "default")

(defn- default-store-dir [sid]
  (str ".fractal/sessions/" sid))

(defn- read-edn-file [path]
  (when path
    (edn/read-string (slurp path))))

(defn- profile-map [m profile]
  (if-let [profiles (:profiles m)]
    (let [p (or profile (:default-profile m) :default)]
      (or (get profiles p)
          (get profiles (keyword p))
          (throw (ex-info (str "profile not found: " p)
                          {:error/type :cli/profile-not-found :profile p}))))
    m))

(defn- base-config [opts]
  (let [m (or (read-edn-file (:config opts)) {})
        profile (some-> (:profile opts) keyword)
        global (apply dissoc m [:profiles :default-profile])
        prof (profile-map m profile)]
    (merge global prof)))

(defn- fake-final-reply [s]
  (str "```clojure\n(FINAL " s ")\n```"))

(defn- normalize-fake-responder [cfg]
  (cond
    (fn? (:fake/respond cfg)) cfg
    (vector? (:fake/respond cfg)) (update cfg :fake/respond fe/responder)
    (:fake-final cfg) (-> cfg
                          (assoc :fake/respond
                                 (fe/responder [[:default (fake-final-reply (:fake-final cfg))]]))
                          (dissoc :fake-final))
    :else cfg))

(defn- cli-overrides [opts]
  (cond-> {}
    (:store-dir opts) (assoc :store/dir (:store-dir opts))
    (:store opts) (assoc :store (:store opts))
    (:adapter opts) (assoc :adapter (:adapter opts))
    (:provider opts) (assoc :provider (:provider opts))
    (:model opts) (assoc :model (:model opts))
    (:harness opts) (assoc :harness (:harness opts))
    (:capability opts) (assoc :capability (:capability opts))
    (:child-provider opts) (assoc :child-provider (:child-provider opts))
    (:child-model opts) (assoc :child-model (:child-model opts))
    (:leaf-provider opts) (assoc :leaf-provider (:leaf-provider opts))
    (:leaf-model opts) (assoc :leaf-model (:leaf-model opts))
    (:max-turns opts) (assoc :max-turns (:max-turns opts))
    (:max-steps opts) (assoc :max-steps (:max-steps opts))
    (:max-fanout opts) (assoc :max-fanout (:max-fanout opts))
    (:fanout-pool opts) (assoc :fanout-pool (:fanout-pool opts))
    (:leaf-concurrency opts) (assoc :leaf-concurrency (:leaf-concurrency opts))
    (:call-timeout-ms opts) (assoc :call-timeout-ms (:call-timeout-ms opts))
    (:cache-ttl opts) (assoc :cache-ttl (:cache-ttl opts))
    (:fake-final opts) (assoc :fake-final (:fake-final opts))))

(defn engine-config [opts]
  (let [sid (or (:session opts) default-session)
        cfg (-> (merge {:store :sqlite
                        :store/dir (default-store-dir sid)}
                       (base-config opts)
                       (cli-overrides opts))
                normalize-fake-responder)]
    (fe/make-config cfg)))

(defn session-id [opts]
  (or (:session opts) default-session))

(defn message [opts args]
  (cond
    (:message opts) (:message opts)
    (:message-file opts) (slurp (:message-file opts))
    (seq args) (str/join " " args)
    :else nil))

;; ---------------------------------------------------------------------------
;; Output
;; ---------------------------------------------------------------------------

(defn- json-key [k]
  (cond
    (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k))
    (symbol? k) (str k)
    :else (str k)))

(defn- json-value [v]
  (cond
    (map? v) (into {} (map (fn [[k vv]] [(json-key k) (json-value vv)])) v)
    (vector? v) (mapv json-value v)
    (set? v) (mapv json-value (sort-by pr-str v))
    (seq? v) (mapv json-value v)
    (keyword? v) (json-key v)
    (symbol? v) (str v)
    (instance? java.math.BigDecimal v) v
    :else v))

(defn emit! [opts value]
  (let [fmt (or (:output opts) :json)]
    (case fmt
      :edn (binding [*print-namespace-maps* false
                     *print-length* nil
                     *print-level* nil]
             (println (pr-str value)))
      (println (json/generate-string (json-value value) {:pretty (:pretty opts)})))))

(defn- err-map [e]
  (let [data (ex-data e)]
    (merge {:ok false
            :error/message (.getMessage e)
            :error/class (str (class e))}
           (when (seq data) {:error/data data}))))

(defn- redact-config [cfg]
  (-> cfg
      (assoc :capability (:capability/name cfg))
      (update :fake/respond (fn [v] (when v :configured)))
      (update :provider/config (fn [v] (when v :redacted)))))

;; ---------------------------------------------------------------------------
;; Session helpers
;; ---------------------------------------------------------------------------

(defn- close-quietly! [h]
  (when h
    (try (fe/close-session! h) (catch Throwable _ nil))))

(defn- resume* [cfg sid]
  (fe/resume-session! cfg sid))

(defn- try-resume [cfg sid]
  (try
    [:ok (resume* cfg sid)]
    (catch clojure.lang.ExceptionInfo e
      (if (= :fractal/unknown-session (:error/type (ex-data e)))
        [:missing nil]
        (throw e)))))

(defn- ensure-session [cfg sid create?]
  (let [[status h] (try-resume cfg sid)]
    (case status
      :ok h
      :missing (if create?
                 (fe/start-session! cfg {:id sid})
                 (throw (ex-info (str "no persisted session: " sid)
                                 {:error/type :cli/session-not-found :session/id sid}))))))

(defn- start-or-existing [cfg sid]
  (let [[status h] (try-resume cfg sid)]
    (case status
      :ok [h false]
      :missing [(fe/start-session! cfg {:id sid}) true])))

(defn- summary [h]
  (let [v (fe/view h)]
    {:session/id (:session-id h)
     :session/status (get-in v [:session :session/status])
     :current-head (:current-head v)
     :event-count (count (:events v))
     :turn-count (count (:turns v))
     :step-count (count (:steps v))
     :eval-count (count (:evals v))
     :head-count (count (:heads v))
     :edge-count (count (:edges v))}))

(defn- final-turn [v]
  (last (:turns v)))

(defn- head-map [v head-id]
  (store/head-by-id v head-id))

(defn- edge-session-ids [root-sid edges]
  (->> edges
       (mapcat (juxt :edge/parent-session :edge/child-session
                     :edge/source-session :edge/target-session
                     :edge/from-session :edge/to-session))
       (remove nil?)
       (cons root-sid)
       distinct
       vec))

(defn- load-session-view [h sid]
  (store/create-session! (:store h) {:session/id sid})
  (store/current-view (:store h) sid))

(defn- known-session-views [h]
  (let [root (fe/view h)
        sids (edge-session-ids (:session-id h) (:edges root))]
    (mapv (fn [sid] [sid (load-session-view h sid)]) sids)))

(defn- tree-view [h]
  (let [views (known-session-views h)]
    {:root-session (:session-id h)
     :sessions (mapv (fn [[sid v]]
                       {:session/id sid
                        :session/status (get-in v [:session :session/status])
                        :current-head (:current-head v)
                        :turn-count (count (:turns v))
                        :head-count (count (:heads v))})
                     views)
     :edges (:edges (fe/view h))}))

(defn- check-view [h]
  (let [views (known-session-views h)
        ref-errors (mapcat (fn [[sid _]]
                             (map (fn [ref] {:session/id sid :payload/ref ref})
                                  (store/verify-no-dangling-refs (:store h) sid)))
                           views)
        head-errors (keep (fn [[sid v]]
                            (when (and (:current-head v)
                                       (nil? (store/head-by-id v (:current-head v))))
                              {:session/id sid
                               :error/type :missing-current-head
                               :head/id (:current-head v)}))
                          views)
        errors (vec (concat ref-errors head-errors))]
    {:ok (empty? errors)
     :session/id (:session-id h)
     :checked-sessions (mapv first views)
     :error-count (count errors)
     :errors errors}))

(defn- report-view [h]
  (let [v (fe/view h)
        turn (final-turn v)]
    (merge (summary h)
           {:last-turn/status (:turn/status turn)
            :last-turn/id (:turn/id turn)
            :last-turn/final-preview (:turn/final-preview turn)
            :lineage (frequencies (map :edge/type (:edges v)))
            :provider (get-in v [:session :session/provider])
            :model (get-in v [:session :session/model])})))

;; ---------------------------------------------------------------------------
;; Commands
;; ---------------------------------------------------------------------------

(def help-text
  "fractal-engine control plane

Usage:
  clojure -M:cli <command> [options] [args]

Usage commands:
  init      Create a config reference file and its store directory.
  config    Print resolved/redacted engine config.
  start     Create a durable session if absent; otherwise report existing.
  run       Start-or-resume, send one turn, wait, and close.
  turn      Resume an existing session, send one turn, wait, and close.
  resume    Verify a durable session can reopen.
  stop      Request stop on a durable session.
  close     Reopen and immediately close resources.
  compact   Force compaction.
  wait      Report status; async cross-process waiting is not implemented yet.

Inspection commands:
  status progress view events heads head edges tree payload messages turns steps evals check report

Common options:
  --config FILE --profile NAME --session ID --store-dir DIR
  --json --edn --pretty
  --message TEXT --message-file FILE
  --provider ID --model ID --harness clojure|rlm
  --child-provider ID --child-model ID --leaf-provider ID --leaf-model ID
  --max-turns N --max-steps N --max-fanout N --fanout-pool N --call-timeout-ms N

Config files are EDN. They may be flat engine config maps or {:default-profile k
:profiles {k {...}}}. Command line options override the selected profile.")

(defn- command-help [_opts _args]
  {:ok true :help help-text})

(defn- write-init-file! [opts sid]
  (let [path (or (:config opts) "fractal.edn")
        f (io/file path)
        store-dir (or (:store-dir opts) (default-store-dir sid))]
    (when (and (.exists f) (not (:force opts)))
      (throw (ex-info (str "config already exists: " path)
                      {:error/type :cli/config-exists :config path})))
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (.mkdirs (io/file store-dir))
    (spit f
          (with-out-str
            (binding [*print-namespace-maps* false]
              (prn {:default-profile :fake
                    :profiles
                    {:fake
                     {:adapter :fake
                      :model "fake-model"
                      :harness :rlm
                      :capability :default
                      :store :sqlite
                      :store/dir store-dir
                      :fake/respond [[:default "```clojure\n(FINAL {:ok true})\n```"]]}}}))))
    {:ok true :command :init :config path :store/dir store-dir :session/id sid}))

(defn- command-init [opts _args]
  (write-init-file! opts (session-id opts)))

(defn- command-config [opts _args]
  (let [cfg (engine-config opts)]
    {:ok true :command :config :config (redact-config cfg)}))

(defn- with-open-session [opts create? f]
  (let [cfg (engine-config opts)
        sid (session-id opts)
        h (ensure-session cfg sid create?)]
    (try (f h)
         (finally (close-quietly! h)))))

(defn- command-start [opts _args]
  (let [cfg (engine-config opts)
        sid (session-id opts)
        [h created?] (start-or-existing cfg sid)]
    (try
      (assoc (summary h) :ok true :command :start :created created?)
      (finally (close-quietly! h)))))

(defn- run-turn-command [opts args create? command]
  (let [msg (or (message opts args)
                (throw (ex-info "turn requires --message, --message-file, or positional text"
                                {:error/type :cli/missing-message})))]
    (with-open-session opts create?
      (fn [h]
        (let [res (fe/run-turn! h msg)]
          {:ok true
           :command command
           :result res
           :summary (summary h)})))))

(defn- command-run [opts args] (run-turn-command opts args true :run))
(defn- command-turn [opts args] (run-turn-command opts args false :turn))

(defn- command-resume [opts _args]
  (with-open-session opts false
    (fn [h] (assoc (summary h) :ok true :command :resume))))

(defn- command-stop [opts _args]
  (with-open-session opts false
    (fn [h]
      (fe/stop-session! h {:wait? true})
      (assoc (summary h) :ok true :command :stop))))

(defn- command-close [opts _args]
  (with-open-session opts false
    (fn [h] (assoc (summary h) :ok true :command :close))))

(defn- command-compact [opts _args]
  (with-open-session opts false
    (fn [h]
      (fe/compact-session! h)
      (assoc (summary h) :ok true :command :compact))))

(defn- command-wait [opts _args]
  (with-open-session opts false
    (fn [h]
      (assoc (summary h)
             :ok true
             :command :wait
             :note "cross-process async waiting is not implemented; status was read after resume"))))

(defn- command-status [opts _args]
  (with-open-session opts false
    (fn [h] (assoc (summary h) :ok true :command :status))))

(defn- command-progress [opts _args]
  (with-open-session opts false
    (fn [h] {:ok true :command :progress :progress (fe/progress h)})))

(defn- command-view [opts _args]
  (with-open-session opts false
    (fn [h] {:ok true :command :view :view (fe/view h)})))

(defn- command-events [opts _args]
  (with-open-session opts false
    (fn [h]
      {:ok true :command :events :since (or (:since opts) 0)
       :events (fe/events-since h (or (:since opts) 0))})))

(defn- command-heads [opts _args]
  (with-open-session opts false
    (fn [h] {:ok true :command :heads :current-head (:current-head (fe/view h))
             :heads (:heads (fe/view h))})))

(defn- command-head [opts args]
  (let [hid (or (:head opts) (first args))]
    (when-not hid
      (throw (ex-info "head requires --head or a head id argument"
                      {:error/type :cli/missing-head})))
    (with-open-session opts false
      (fn [h]
        (let [head (head-map (fe/view h) hid)]
          (when-not head
            (throw (ex-info (str "head not found: " hid)
                            {:error/type :cli/head-not-found :head/id hid})))
          {:ok true :command :head :head head})))))

(defn- command-edges [opts _args]
  (with-open-session opts false
    (fn [h] {:ok true :command :edges :edges (:edges (fe/view h))})))

(defn- command-tree [opts _args]
  (with-open-session opts false
    (fn [h] (assoc (tree-view h) :ok true :command :tree))))

(defn- command-payload [opts args]
  (let [s (or (:ref opts) (first args))]
    (when-not s
      (throw (ex-info "payload requires --ref or an EDN ref argument"
                      {:error/type :cli/missing-payload-ref})))
    (with-open-session opts false
      (fn [h]
        (let [ref (edn/read-string s)]
          (when-not (payload/payload-ref? ref)
            (throw (ex-info "payload argument is not a payload ref"
                            {:error/type :cli/not-payload-ref :value ref})))
          {:ok true :command :payload :ref ref :value (fe/read-payload h ref)})))))

(defn- command-collection [command k opts _args]
  (with-open-session opts false
    (fn [h] {:ok true :command command k (k (fe/view h))})))

(defn- command-check [opts _args]
  (with-open-session opts false
    (fn [h] (assoc (check-view h) :command :check))))

(defn- command-report [opts _args]
  (with-open-session opts false
    (fn [h] (assoc (report-view h) :ok true :command :report))))

(def command-fns
  {"help" command-help
   "init" command-init
   "config" command-config
   "start" command-start
   "run" command-run
   "turn" command-turn
   "resume" command-resume
   "stop" command-stop
   "close" command-close
   "compact" command-compact
   "wait" command-wait
   "status" command-status
   "progress" command-progress
   "view" command-view
   "events" command-events
   "heads" command-heads
   "head" command-head
   "edges" command-edges
   "tree" command-tree
   "payload" command-payload
   "messages" (partial command-collection :messages :messages)
   "turns" (partial command-collection :turns :turns)
   "steps" (partial command-collection :steps :steps)
   "evals" (partial command-collection :evals :evals)
   "check" command-check
   "report" command-report})

(defn run-command
  "Run argv, emit to *out*/*err*, and return a process-style exit code."
  [argv]
  (let [parsed (try
                 (parse-args argv)
                 (catch Throwable e
                   (emit! {:output :json} (err-map e))
                   ::parse-error))]
    (if (= ::parse-error parsed)
      1
      (let [{:keys [command opts args]} parsed]
        (try
          (let [f (or (command-fns command)
                      (throw (ex-info (str "unknown command: " command)
                                      {:error/type :cli/unknown-command :command command})))
                out (f opts args)]
            (emit! opts out)
            0)
          (catch Throwable e
            (emit! opts (err-map e))
            1))))))

(defn -main [& argv]
  (System/exit (run-command argv)))
