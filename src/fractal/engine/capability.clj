(ns fractal.engine.capability
  "L1 · the per-session capability profile, the named lattice, `clamp` (gate
   meet), `validate-profile!`, and `sci-opts` (profile → the map passed to
   sci/init). Capability is DENIED BY DEFAULT (04): the SCI ctx grants nothing
   except what the profile explicitly injects/whitelists. Takes the host-fn
   impls (FINAL/inspect[/lm/rlm]) as DATA, so it never depends on the kernel.

   Gated IO (slurp/spit/sh/file-seq/io.reader/…) and the engine fns are injected
   into `clojure.core`, so they are available unqualified AND survive a model
   `(in-ns …)` (the §7 'gated slurp shadow survives in-ns' guarantee — SCI has
   no built-in slurp to revert to, and a clojure.core var is referred by every
   ns)."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as jio]
            [clojure.java.shell :as shell]
            [sci.core :as sci]
            ;; required so copy-ns can compile them into the SCI catalog:
            [clojure.pprint]
            [clojure.data]
            [clojure.zip]
            [clojure.core.protocols])
  (:import [java.io File]))

;; ===========================================================================
;; 1. Profile data + the named lattice (04 §1, §3)
;; ===========================================================================

(def safe-shell-commands
  "Genuinely non-exec / non-net / non-write tools (04 §2). Every interpreter and
   -exec/-write tool is excluded — they would defeat the gate."
  #{"grep" "cat" "head" "tail" "wc" "sort" "uniq" "cut" "tr" "comm" "ls" "stat"
    "file" "diff" "jq" "md5sum" "sha256sum" "date" "echo"})

(def default-ns-grant
  "The :default catalog grant: clojure.core + the SCI-default text namespaces +
   the copy-ns'd extras. (string/edn/set/walk are SCI built-ins; pprint/data/
   zip/core.protocols are injected via copy-ns, below.)"
  '#{clojure.core clojure.string clojure.edn clojure.set clojure.walk
     clojure.pprint clojure.data clojure.zip clojure.core.protocols})

(defn- canonical-path ^String [p]
  (let [f (File. (str p))]
    (try (.getCanonicalPath f) (catch Exception _ (.getAbsolutePath f)))))

(defn- workdir [] (canonical-path (System/getProperty "user.dir")))

(defn locked-down
  "Maximum sandbox: no fs/shell/network, no interop, no lm/rlm egress."
  []
  {:capability/name  :locked-down
   :cap/fs-read      :deny
   :cap/fs-write     :deny
   :cap/shell        :deny
   :cap/network      :deny
   :ns/granted       '#{clojure.core clojure.string clojure.edn clojure.set clojure.walk}
   :cap/java-classes {}
   :engine-fns       #{:FINAL :inspect}
   :surface/fns      #{}})

(defn default-profile
  "The RLM workhorse: reads the work area (the thesis needs easy file reads),
   gates writes/network/shell. lm/rlm injected (Phase 3 uses them)."
  []
  {:capability/name  :default
   :cap/fs-read      {:paths [(workdir)]}
   :cap/fs-write     :deny
   :cap/shell        {:commands safe-shell-commands}
   :cap/network      :deny
   :ns/granted       default-ns-grant
   :cap/java-classes {}
   :engine-fns       #{:FINAL :inspect :lm :map-lm :rlm :map-rlm :attach-rlm}
   :surface/fns      #{}})

(defn trusted
  "Broad: fs-read everywhere, writes to the work area, shell + network open."
  []
  {:capability/name  :trusted
   :cap/fs-read      :allow
   :cap/fs-write     {:paths [(workdir)]}
   :cap/shell        :allow
   :cap/network      :allow
   :ns/granted       default-ns-grant
   :cap/java-classes {}
   :engine-fns       #{:FINAL :inspect :lm :map-lm :rlm :map-rlm :attach-rlm}
   :surface/fns      #{}})

(defn named-profile [k]
  (case k
    :locked-down (locked-down)
    :default     (default-profile)
    :trusted     (trusted)
    nil))

(defn resolve-profile
  "A keyword → its named profile value; a map → itself."
  [name-or-value]
  (cond
    (map? name-or-value)     name-or-value
    (keyword? name-or-value) (or (named-profile name-or-value)
                                 (throw (ex-info (str "unknown capability profile: " name-or-value)
                                                 {:error/type :capability/unknown-profile
                                                  :name name-or-value})))
    :else (throw (ex-info "capability must be a keyword or a profile map"
                          {:error/type :capability/invalid :value name-or-value}))))

;; ===========================================================================
;; 2. Path + network gates (04 §2)
;; ===========================================================================

(defn- within?
  "Canonical path-boundary check (never string-prefix): admit iff the requested
   path equals an allowed prefix or sits beneath it (`/work` must not admit
   `/work-secret`). Canonicalization resolves symlinks, so a symlink escaping
   the boundary is denied."
  [allowed-prefixes requested]
  (let [req (canonical-path requested)]
    (boolean
      (some (fn [prefix]
              (let [p (canonical-path prefix)]
                (or (= req p)
                    (.startsWith req (str p File/separator)))))
            allowed-prefixes))))

(defn- cap-error [cap arg msg]
  (ex-info msg {:error/type :capability/denied :cap cap :arg (str arg)}))

;; ⛔ The gate must resolve a read/write target EXACTLY as clojure.java.io will,
;; or it becomes a confused deputy (a `file:` URL string, an uppercase scheme,
;; or a File arg slips past a string-only/case-sensitive/string-typed check
;; while io still opens the real target). `classify` mirrors io's resolution:
;; try URL first (like the IOFactory String path), else it is a file path.

(defn- classify-url [^java.net.URL u]
  (let [proto (str/lower-case (or (.getProtocol u) ""))
        host  (.getHost u)]
    (if (and (= "file" proto) (str/blank? host))
      {:kind :file :path (.getPath u)}    ; file:/abs or file:///abs → a local path
      {:kind :network :scheme proto})))   ; http/https/ftp/jar/file://host/other → network

(defn- url-of [^String s]
  (try (java.net.URL. s) (catch Throwable _ nil)))

(defn- classify
  "Resolve a read/write target the way clojure.java.io resolves it. Returns
   {:kind :network :scheme s} | {:kind :file :path p} | {:kind :other} (a
   stream/reader/byte[] — no locator to gate)."
  [arg]
  (cond
    (instance? File arg)         {:kind :file :path (.getPath ^File arg)}
    (instance? java.net.URL arg) (classify-url arg)
    (string? arg)                (if-let [u (url-of arg)]
                                   (classify-url u)
                                   {:kind :file :path arg})
    :else                        {:kind :other}))

(defn- check-read!
  "Apply the fs-read + network gates for a read of `arg`, gating the RESOLVED
   target (not the raw string)."
  [fs-read network arg]
  (let [t (classify arg)]
    (case (:kind t)
      :network (when-not (= :allow network)
                 (throw (cap-error :network arg (str "network read denied: " arg))))
      :file    (cond
                 (= :deny fs-read)  (throw (cap-error :fs-read arg (str "fs-read denied: " arg)))
                 (= :allow fs-read) nil
                 (map? fs-read)     (when-not (within? (:paths fs-read) (:path t))
                                      (throw (cap-error :fs-read arg (str "fs-read path denied: " arg)))))
      :other   nil)))

(defn- check-write!
  [fs-write arg]
  (let [t (classify arg)]
    (case (:kind t)
      :network (throw (cap-error :network arg (str "network write denied: " arg)))
      :file    (cond
                 (= :deny fs-write)  (throw (cap-error :fs-write arg (str "fs-write denied: " arg)))
                 (= :allow fs-write) nil
                 (map? fs-write)     (when-not (within? (:paths fs-write) (:path t))
                                       (throw (cap-error :fs-write arg (str "fs-write path denied: " arg)))))
      :other   nil)))

;; ===========================================================================
;; 3. Gated host-fn builders (04 §2)
;; ===========================================================================

(defn- gated-slurp [{:keys [cap/fs-read cap/network]}]
  (fn [src & opts]
    (check-read! fs-read network src)
    (apply slurp src opts)))

(defn- gated-spit [{:keys [cap/fs-write]}]
  (fn [dst content & opts]
    (check-write! fs-write dst)
    (apply spit dst content opts)))

(defn- gated-file-seq [{:keys [cap/fs-read cap/network]}]
  (fn [dir]
    (check-read! fs-read network dir)
    (file-seq (jio/file (str dir)))))

(defn- gated-reader [{:keys [cap/fs-read cap/network]}]
  (fn [src & opts] (check-read! fs-read network src) (apply jio/reader src opts)))

(defn- gated-input-stream [{:keys [cap/fs-read cap/network]}]
  (fn [src & opts] (check-read! fs-read network src) (apply jio/input-stream src opts)))

(defn- gated-as-url [{:keys [cap/network]}]
  (fn [x] (when-not (= :allow network)
            (throw (cap-error :network x (str "network (as-url) denied: " x))))
    (jio/as-url x)))

(defn- gated-copy [{:keys [cap/fs-read cap/fs-write cap/network]}]
  (fn [input output & opts]
    ;; io/copy reads a File/URL input (a String input is literal char DATA, not
    ;; a path — so it is NOT read-gated); it writes a File/URL output. Gating the
    ;; resolved File/URL closes the File-arg bypass.
    (when (or (instance? File input) (instance? java.net.URL input))
      (check-read! fs-read network input))
    (when (or (instance? File output) (instance? java.net.URL output))
      (check-write! fs-write output))
    (apply jio/copy input output opts)))

(defn- gated-sh [{:keys [cap/shell]}]
  (fn [& args]
    (let [cmd (str (first args))]
      (cond
        (= :deny shell)  (throw (cap-error :shell cmd (str "shell denied: " cmd)))
        (= :allow shell) nil
        (map? shell)     (when-not (contains? (:commands shell) cmd)
                           (throw (cap-error :shell cmd (str "shell command not allowed: " cmd)))))
      (apply shell/sh args))))

;; ===========================================================================
;; 4. validate-profile! + clamp (04 §2, §3)
;; ===========================================================================

(def ^:private dangerous-class-patterns
  [#"^java\.net\." #"^java\.lang\.ProcessBuilder$" #"^java\.lang\.Runtime$"
   #"^java\.lang\.Thread$" #"^java\.lang\.reflect\." #"^java\.lang\.ClassLoader$"
   #"^jdk\." #"^sun\."])

(defn- dangerous-class? [sym]
  (boolean (some #(re-find % (name sym)) dangerous-class-patterns)))

(defn- classes-need-unsafe?
  "True when a class grant requires the explicit :capability/unsafe co-marker:
   the :all sentinel (it trivially includes every dangerous class) or any
   dangerous class key in a finite map."
  [classes]
  (or (= :all classes)
      (boolean (some dangerous-class? (keys classes)))))

(defn validate-profile!
  "Reject profiles that would breach the sandbox: dangerous :cap/java-classes
   entries (or the :all sentinel) without an explicit :capability/unsafe
   co-marker, the unsafe marker on :default/:locked-down, or a malformed class
   whitelist. Returns the profile."
  [profile]
  (let [classes (:cap/java-classes profile)
        unsafe? (:capability/unsafe profile)
        surface-fns (or (:surface/fns profile) #{})]
    (when-not (or (= :all classes) (map? classes))
      (throw (ex-info "capability :cap/java-classes must be a finite symbol-keyed map, or :all (requires :capability/unsafe)"
                      {:error/type :capability/invalid :cap/java-classes classes})))
    ;; ⛔ in the MAP form every key must be a class-name SYMBOL — the raw SCI
    ;; `{:allow :all}` directive (and any non-class key) stays rejected; the
    ;; only blanket grant is the profile-level `:all` sentinel above, which
    ;; demands the unsafe co-marker and is banned on the built-in names.
    (when (and (map? classes) (not (every? symbol? (keys classes))))
      (throw (ex-info "capability :cap/java-classes must be keyed by class-name symbols (never a directive like {:allow :all})"
                      {:error/type :capability/invalid :cap/java-classes classes})))
    (when (and (classes-need-unsafe? classes) (not unsafe?))
      (throw (ex-info "capability grants dangerous java classes (or :all) without :capability/unsafe"
                      {:error/type :capability/unsafe-class
                       :classes (if (= :all classes) :all (filterv dangerous-class? (keys classes)))})))
    (when (and unsafe? (#{:default :locked-down} (:capability/name profile)))
      (throw (ex-info "the :capability/unsafe marker is rejected on :default/:locked-down"
                      {:error/type :capability/unsafe-rejected
                       :capability/name (:capability/name profile)})))
    (when-not (set? surface-fns)
      (throw (ex-info "capability :surface/fns must be an explicit finite set"
                      {:error/type :capability/invalid :surface/fns surface-fns})))
    (when-not (every? #(and (symbol? %)
                            (namespace %)
                            (not (str/blank? (namespace %)))
                            (not (str/blank? (name %))))
                      surface-fns)
      (throw (ex-info "capability :surface/fns must contain qualified symbols"
                      {:error/type :capability/invalid :surface/fns surface-fns})))
    (assoc profile :surface/fns surface-fns)))

(defn- surface-fns [profile]
  (or (:surface/fns profile) #{}))

(defn- meet-mode
  "Meet of a :deny|:allow|{:paths}|{:commands} gate (04 §3): :deny annihilates,
   :allow is identity, two boundaries intersect (paths by mutual containment,
   commands by set intersection)."
  [a b kind]
  (cond
    (or (= :deny a) (= :deny b)) :deny
    (= :allow a) b
    (= :allow b) a
    :else (case kind
            :paths    {:paths (->> (concat (filter #(within? (:paths b) %) (:paths a))
                                           (filter #(within? (:paths a) %) (:paths b)))
                                   (map canonical-path) distinct vec)}
            :commands {:commands (set/intersection (:commands a) (:commands b))})))

(defn- meet-network [a b] (if (or (= :deny a) (= :deny b)) :deny :allow))

(defn- meet-classes
  ":all is the TOP of the class lattice: :all ∧ x = x, :all ∧ :all = :all. Two
   finite maps meet by key intersection, keeping a's VALUES — the live Class
   objects when a is the caller-side profile (an EDN-round-tripped persisted
   profile is only ever passed as b; see session.clj)."
  [a b]
  (cond
    (= :all a) b
    (= :all b) a
    :else (select-keys a (keys b))))

(defn clamp
  "The MEET of two profiles — the more restrictive of each gate (04 §3).
   `clamp(parent, child)` is the universal inherit-and-clamp for every spawn /
   per-session override. The :capability/unsafe marker survives the meet iff the
   RESULT still needs it (dangerous classes / :all) and an input carried it —
   so a clamped unsafe profile revalidates instead of tripping the unsafe gate."
  [a b]
  (let [classes (meet-classes (:cap/java-classes a) (:cap/java-classes b))
        unsafe? (and (classes-need-unsafe? classes)
                     (or (:capability/unsafe a) (:capability/unsafe b)))]
    (cond->
      {:capability/name  (:capability/name b)
       :cap/fs-read      (meet-mode (:cap/fs-read a) (:cap/fs-read b) :paths)
       :cap/fs-write     (meet-mode (:cap/fs-write a) (:cap/fs-write b) :paths)
       :cap/shell        (meet-mode (:cap/shell a) (:cap/shell b) :commands)
       :cap/network      (meet-network (:cap/network a) (:cap/network b))
       :ns/granted       (set/intersection (:ns/granted a) (:ns/granted b))
       :cap/java-classes classes
       :engine-fns       (set/intersection (:engine-fns a) (:engine-fns b))
       :surface/fns      (set/intersection (surface-fns a) (surface-fns b))}
      unsafe? (assoc :capability/unsafe true))))

(defn- gate<=? [a b kind]
  (cond
    (= :deny a)  true
    (= :allow b) true
    (= :allow a) false
    (= :deny b)  false
    :else (case kind
            :paths    (every? #(within? (:paths b) %) (:paths a))
            :commands (set/subset? (:commands a) (:commands b)))))

(defn- classes<=?
  "Class-grant restrictiveness: everything <= :all; :all <= only :all; finite
   maps compare by key subset."
  [a b]
  (cond
    (= :all b) true
    (= :all a) false
    :else (set/subset? (set (keys a)) (set (keys b)))))

(defn profile<=?
  "True iff `a` is at least as restrictive as `b` on EVERY gate (the per-gate
   restrictiveness lattice, 04 §3) — the predicate that rejects a loosening
   override."
  [a b]
  (and (gate<=? (:cap/fs-read a) (:cap/fs-read b) :paths)
       (gate<=? (:cap/fs-write a) (:cap/fs-write b) :paths)
       (gate<=? (:cap/shell a) (:cap/shell b) :commands)
       (or (= :deny (:cap/network a)) (= :allow (:cap/network b)))
       (set/subset? (:ns/granted a) (:ns/granted b))
       (classes<=? (:cap/java-classes a) (:cap/java-classes b))
       (set/subset? (:engine-fns a) (:engine-fns b))
       (set/subset? (surface-fns a) (surface-fns b))))

(defn resolve-override
  "Resolve a per-session capability override against the cfg base: REJECT it if
   it loosens any gate beyond the base, else return clamp(base, override) (04
   §3, §4)."
  [base override]
  (let [ov (validate-profile! (resolve-profile override))]
    (when-not (profile<=? ov base)
      (throw (ex-info "capability override loosens a gate beyond the base profile"
                      {:error/type :capability/loosening-override
                       :override (:capability/name ov)})))
    (clamp base ov)))

;; ===========================================================================
;; 5. The deny set + sci-opts (04 §2, §5)
;; ===========================================================================

(def deny-set
  "Symbols the model may NEVER call (04 §5). `*read-eval*` stays false (SCI
   default) so `#=` is already blocked — there is no read-string-with-eval
   symbol to deny, and `binding` is deliberately NOT denied."
  '#{eval clojure.core/eval resolve ns-resolve requiring-resolve
     find-ns find-var intern load-string load-file load})

;; ---------------------------------------------------------------------------
;; The :all class catalog. SCI 0.8.43 resolves class SYMBOLS only through
;; explicit :classes entries (+ imports) — {:allow :all} alone only widens
;; reflective access to objects already in hand (verified against the pinned
;; version). So :all enumerates the JDK's public java.*/javax.* classes from
;; the boot image (jrt:/) ONCE per JVM and hands SCI the explicit map, the
;; babashka approach. Classes are loaded WITHOUT initialization (no static
;; initializers run at enumeration time).
;; ---------------------------------------------------------------------------

(defn- jrt-class-names []
  (let [fs   (java.nio.file.FileSystems/getFileSystem (java.net.URI/create "jrt:/"))
        root (.getPath fs "/modules" (make-array String 0))]
    (with-open [stream (java.nio.file.Files/walk root (make-array java.nio.file.FileVisitOption 0))]
      (->> (iterator-seq (.iterator stream))
           (keep (fn [p]
                   (let [s (str p)]
                     (when (.endsWith s ".class")
                       ;; /modules/<module>/java/lang/String.class → java.lang.String
                       (let [parts (remove str/blank? (str/split s #"/"))
                             cls   (drop 2 parts)]
                         (when (seq cls)
                           (let [cname (str/replace (str/join "." cls) #"\.class$" "")]
                             (when (and (or (str/starts-with? cname "java.")
                                            (str/starts-with? cname "javax."))
                                        (not (str/ends-with? cname "package-info"))
                                        (not (str/ends-with? cname "module-info")))
                               cname))))))))
           distinct
           doall))))

(def ^:private jdk-class-map
  "Delayed {fqn-symbol Class} over the JDK's public java.*/javax.* classes.
   Built once per JVM, only when an :all profile builds its first SCI ctx."
  (delay
    (into {}
          (keep (fn [^String cname]
                  (try
                    (let [c (Class/forName cname false nil)]
                      (when (java.lang.reflect.Modifier/isPublic (.getModifiers c))
                        [(symbol cname) c]))
                    (catch Throwable _ nil))))
          (jrt-class-names))))

(def ^:private java-lang-imports
  "Simple-name → FQN imports for direct java.lang members (String, System,
   Math, …), mirroring Clojure's default imports — :all profiles only."
  (delay
    (into {}
          (keep (fn [[sym _]]
                  (let [n (name sym)]
                    (when (and (str/starts-with? n "java.lang.")
                               (not (str/includes? (subs n 10) "."))
                               (not (str/includes? n "$")))
                      [(symbol (subs n 10)) sym]))))
          @jdk-class-map)))

(def ^:private copy-ns-catalog
  "The non-SCI-default namespaces, compiled into SCI namespaces via copy-ns (a
   macro — needs literal names). Selected by `:ns/granted` at sci-opts time."
  {'clojure.pprint         (sci/copy-ns clojure.pprint (sci/create-ns 'clojure.pprint))
   'clojure.data           (sci/copy-ns clojure.data (sci/create-ns 'clojure.data))
   'clojure.zip            (sci/copy-ns clojure.zip (sci/create-ns 'clojure.zip))
   'clojure.core.protocols (sci/copy-ns clojure.core.protocols (sci/create-ns 'clojure.core.protocols))})

(defn- engine-fn-vars
  "Symbol→fn map for the engine fns the profile injects (FINAL/inspect[/lm/…])."
  [profile engine-fn-impls]
  (into {}
        (map (fn [[k v]] [(symbol (name k)) v]))
        (select-keys engine-fn-impls (:engine-fns profile))))

(defn- gated-io-vars
  "The gated IO host fns injected into clojure.core (always present; gated at the
   VAR level by the profile)."
  [profile]
  {'slurp        (gated-slurp profile)
   'spit         (gated-spit profile)
   'file-seq     (gated-file-seq profile)
   'sh           (gated-sh profile)})

(defn- gated-io-ns
  "A gated clojure.java.io namespace (always injected; read/write/network gated
   at the var level). `file` is an ungated constructor — reading through it is
   still gated by slurp/reader."
  [profile]
  {'file         (fn [& args] (apply jio/file args))
   'reader       (gated-reader profile)
   'input-stream (gated-input-stream profile)
   'as-url       (gated-as-url profile)
   'copy         (gated-copy profile)})

(defn- surface-ns-vars
  "Filter SDK-supplied SCI namespaces through the capability's :surface/fns set."
  [profile surface-namespaces]
  (let [allowed (surface-fns profile)]
    (into {}
          (keep (fn [[ns-sym fns]]
                  (let [exposed (into {}
                                      (keep (fn [[fn-sym f]]
                                              (let [q (symbol (name ns-sym) (name fn-sym))]
                                                (when (contains? allowed q)
                                                  [fn-sym f]))))
                                      fns)]
                    (when (seq exposed) [ns-sym exposed]))))
          (or surface-namespaces {}))))

(defn sci-opts
  "Map a validated profile + engine-fn impls onto the options passed to
   sci/init (03, 04 §2). Engine fns + gated IO live in clojure.core; the
   copy-ns'd catalog namespaces are emitted iff granted; classes are an explicit
   finite whitelist — or the profile-level :all sentinel (validated to carry
   :capability/unsafe, never on :default/:locked-down), which maps to SCI's
   {:allow :all} HERE and only here; the deny set + `*read-eval* false` close
   the remaining holes."
  ([profile engine-fn-impls]
   (sci-opts profile engine-fn-impls {}))
  ([profile engine-fn-impls surface-namespaces]
   (let [all? (= :all (:cap/java-classes profile))]
     (cond-> {:namespaces (merge {'clojure.core      (merge (engine-fn-vars profile engine-fn-impls)
                                                            (gated-io-vars profile))
                                  'clojure.java.io   (gated-io-ns profile)
                                  'clojure.java.shell {'sh (gated-sh profile)}}
                                 (select-keys copy-ns-catalog (:ns/granted profile))
                                 (surface-ns-vars profile surface-namespaces))
              :classes    (if all?
                            (assoc @jdk-class-map :allow :all)
                            (:cap/java-classes profile))
              :deny       deny-set}
       all? (assoc :imports @java-lang-imports)))))
