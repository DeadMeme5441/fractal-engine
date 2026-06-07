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
   :engine-fns       #{:FINAL :inspect}})

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
   :engine-fns       #{:FINAL :inspect :lm :map-lm :rlm :map-rlm}})

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
   :engine-fns       #{:FINAL :inspect :lm :map-lm :rlm :map-rlm}})

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

(defn validate-profile!
  "Reject profiles that would breach the sandbox: a dangerous :cap/java-classes
   entry without an explicit :capability/unsafe co-marker, the unsafe marker on
   :default/:locked-down, or a non-map class whitelist. Returns the profile."
  [profile]
  (let [classes (:cap/java-classes profile)
        unsafe? (:capability/unsafe profile)]
    (when-not (map? classes)
      (throw (ex-info "capability :cap/java-classes must be an explicit finite map (never :all)"
                      {:error/type :capability/invalid :cap/java-classes classes})))
    ;; ⛔ every key must be a class-name SYMBOL — reject the SCI `{:allow :all}`
    ;; directive (and any non-class key), which would defeat the interop sandbox.
    (when-not (every? symbol? (keys classes))
      (throw (ex-info "capability :cap/java-classes must be keyed by class-name symbols (never :all / a directive)"
                      {:error/type :capability/invalid :cap/java-classes classes})))
    (when (and (seq (filter dangerous-class? (keys classes))) (not unsafe?))
      (throw (ex-info "capability whitelists a dangerous java class without :capability/unsafe"
                      {:error/type :capability/unsafe-class
                       :classes (filterv dangerous-class? (keys classes))})))
    (when (and unsafe? (#{:default :locked-down} (:capability/name profile)))
      (throw (ex-info "the :capability/unsafe marker is rejected on :default/:locked-down"
                      {:error/type :capability/unsafe-rejected
                       :capability/name (:capability/name profile)})))
    profile))

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

(defn clamp
  "The MEET of two profiles — the more restrictive of each gate (04 §3).
   `clamp(parent, child)` is the universal inherit-and-clamp for every spawn /
   per-session override."
  [a b]
  {:capability/name  (:capability/name b)
   :cap/fs-read      (meet-mode (:cap/fs-read a) (:cap/fs-read b) :paths)
   :cap/fs-write     (meet-mode (:cap/fs-write a) (:cap/fs-write b) :paths)
   :cap/shell        (meet-mode (:cap/shell a) (:cap/shell b) :commands)
   :cap/network      (meet-network (:cap/network a) (:cap/network b))
   :ns/granted       (set/intersection (:ns/granted a) (:ns/granted b))
   :cap/java-classes (select-keys (:cap/java-classes a) (keys (:cap/java-classes b)))
   :engine-fns       (set/intersection (:engine-fns a) (:engine-fns b))})

(defn- gate<=? [a b kind]
  (cond
    (= :deny a)  true
    (= :allow b) true
    (= :allow a) false
    (= :deny b)  false
    :else (case kind
            :paths    (every? #(within? (:paths b) %) (:paths a))
            :commands (set/subset? (:commands a) (:commands b)))))

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
       (set/subset? (set (keys (:cap/java-classes a))) (set (keys (:cap/java-classes b))))
       (set/subset? (:engine-fns a) (:engine-fns b))))

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

(defn sci-opts
  "Map a validated profile + engine-fn impls onto the options passed to
   sci/init (03, 04 §2). Engine fns + gated IO live in clojure.core; the
   copy-ns'd catalog namespaces are emitted iff granted; classes are an explicit
   finite whitelist (never :all); the deny set + `*read-eval* false` close the
   remaining holes."
  [profile engine-fn-impls]
  {:namespaces (merge {'clojure.core      (merge (engine-fn-vars profile engine-fn-impls)
                                                 (gated-io-vars profile))
                       'clojure.java.io   (gated-io-ns profile)
                       'clojure.java.shell {'sh (gated-sh profile)}}
                      (select-keys copy-ns-catalog (:ns/granted profile)))
   :classes    (:cap/java-classes profile)
   :deny       deny-set})
