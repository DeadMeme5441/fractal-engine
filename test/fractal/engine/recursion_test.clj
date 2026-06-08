(ns fractal.engine.recursion-test
  "Phase 3 offline proofs (FakeAdapter, deterministic, no keys): lm/map-lm
   leaves, rlm/map-rlm children (incl. nested + fan-out + partial failure +
   capability clamp + :locked-down drops lm/rlm), the hot-swap (config-only),
   and durability/resume across recursion. Every responder is a PURE fn of the
   request (race-free under fan-out)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [sci.core :as sci]
            [fractal.engine.api :as fe]
            [fractal.engine.adapter.fake :as fake]
            [fractal.engine.adapter.request :as request]
            [fractal.engine.recursion :as recursion]
            [fractal.engine.session :as session]
            [fractal.engine.store :as store]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- start
  ([harness cap] (start harness cap {}))
  ([harness cap extra]
   (fe/start-session!
     (fe/make-config (merge {:adapter :fake :fake/respond (:fake/respond extra (constantly "x"))
                             :model "fake-model" :harness harness :capability cap
                             :max-steps (:max-steps extra 4)}
                            (dissoc extra :max-steps))))))

(defn- session-with [harness cap responder & {:as extra}]
  (fe/start-session!
    (fe/make-config (merge {:adapter :fake :fake/respond responder
                            :model "fake-model" :harness harness :capability cap
                            :max-steps 4}
                           extra))))

(defn- resolves-ok?
  "True iff `expr` evaluates in the session's SCI ctx without throwing — used to
   probe which host fns are bound (a free symbol throws 'Could not resolve')."
  [handle expr]
  (try (sci/eval-string* @(:sci-ctx handle) expr) true
       (catch Throwable _ false)))

(defn- sys-prompt-of [handle]
  (->> (request/build-request (:store handle)
                              (store/current-view (:store handle) (:session-id handle))
                              (:cfg handle))
       :messages (filter #(= :system (:role %))) first :content))

(defn- leaf-req? [req] (str/starts-with? (or (fake/last-user req) "") "Input EDN:"))
(defn- child-req? [req] (str/includes? (or (fake/last-user req) "") "Assigned task:"))
(defn- num-in [req] (some-> (re-find #"NUM=(\d+)" (fake/last-user req)) second Integer/parseInt))

;; ===========================================================================
;; LEAF — lm
;; ===========================================================================

(deftest lm-string-mode
  (let [resp (fe/responder [["echo-leaf" "GREEN"]
                            ["T" "```clojure\n(FINAL (lm {:id 1} \"echo-leaf\"))\n```"]])
        s (session-with :rlm :default resp)]
    (is (= "GREEN" (:turn/final-value (fe/run-turn! s "T")) ) "lm :string returns the raw provider text")
    (fe/stop-session! s)))

(deftest lm-edn-mode
  (let [resp (fe/responder [["double-leaf" "{:id 1 :double 8}"]
                            ["T" "```clojure\n(FINAL (lm {:id 1 :n 4} \"double-leaf\" :edn))\n```"]])
        s (session-with :rlm :default resp)]
    (is (= {:id 1 :double 8} (:turn/final-value (fe/run-turn! s "T")))
        "lm :edn parses the leaf text into one EDN value")
    (fe/stop-session! s)))

(deftest lm-edn-strips-accidental-fence
  (let [resp (fe/responder [["fenced-leaf" "```edn\n{:ok true}\n```"]
                            ["T" "```clojure\n(FINAL (lm {} \"fenced-leaf\" :edn))\n```"]])
        s (session-with :rlm :default resp)]
    (is (= {:ok true} (:turn/final-value (fe/run-turn! s "T")))
        "an EDN leaf that wrapped its output in a fence still parses")
    (fe/stop-session! s)))

;; ===========================================================================
;; LEAF — map-lm (fan-out, order, modes, partial failure, ≤50 cap)
;; ===========================================================================

(defn- leaf-double [req] (let [n (num-in req)] (str "{:n " n " :double " (* 2 n) "}")))

(deftest map-lm-order-preserved-edn
  (let [resp (fn [req]
               (cond (leaf-req? req) (leaf-double req)
                     :else "```clojure\n(FINAL (map-lm [{:n 1 :v \"NUM=1\"} {:n 2 :v \"NUM=2\"} {:n 3 :v \"NUM=3\"}] \"double\" :edn))\n```"))
        s (session-with :rlm :default resp)]
    (is (= [{:n 1 :double 2} {:n 2 :double 4} {:n 3 :double 6}]
           (:turn/final-value (fe/run-turn! s "go")))
        "map-lm fans out, preserves input order")
    (fe/stop-session! s)))

(deftest map-lm-partial-failure-sentinel
  (let [resp (fn [req]
               (cond (leaf-req? req) (let [n (num-in req)]
                                       (if (= n 2) "{:broken" (str "{:n " n "}")))  ; n=2 ⇒ bad EDN
                     :else "```clojure\n(FINAL (map-lm [{:n 1 :v \"NUM=1\"} {:n 2 :v \"NUM=2\"} {:n 3 :v \"NUM=3\"}] \"q\" :edn))\n```"))
        s (session-with :rlm :default resp)
        out (:turn/final-value (fe/run-turn! s "go"))]
    (is (= {:n 1} (nth out 0)))
    (is (true? (:fractal/failed (nth out 1))) "the unparseable slot is a :fractal/failed sentinel")
    (is (= 1 (:index (nth out 1))) "the sentinel carries its input index")
    (is (= :fractal/leaf-parse-failed (get-in (nth out 1) [:error :error/type])))
    (is (= {:n 3} (nth out 2)) "the other slots still succeed")
    (fe/stop-session! s)))

(deftest fanout-cap-enforced
  (testing "bounded-fanout-inputs admits exactly :max-fanout and throws above it"
    (let [cfg {:max-fanout 50}]
      (is (= 50 (count (recursion/bounded-fanout-inputs :leaf cfg (range 50))))
          "exactly 50 inputs is admitted")
      (is (= 3 (count (recursion/bounded-fanout-inputs :leaf cfg (range 3))))
          "fewer than the cap passes through unchanged")
      (doseq [kind [:leaf :child]]                        ; the cap applies to map-lm AND map-rlm
        (let [ex (try (recursion/bounded-fanout-inputs kind cfg (range 51)) nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :fractal/fanout-exceeded (:error/type (ex-data ex))) (str kind " cap throws"))
          (is (= kind (:fanout/kind (ex-data ex))))
          (is (true? (:error/retryable? (ex-data ex))) "the cap error is recoverable (chunk + retry)"))))))

(deftest assemble-batch-results-folds-sentinels
  (is (= [:a {:fractal/failed true :index 1 :error {:error/type :x}} :c]
         (recursion/assemble-batch-results
           [{:ok true :index 0 :value :a}
            {:ok false :index 1 :error {:error/type :x}}
            {:ok true :index 2 :value :c}]))))

;; ===========================================================================
;; CHILD — rlm (envelope, single child)
;; ===========================================================================

(deftest rlm-single-child-envelope
  (let [resp (fn [req]
               (cond (child-req? req) (str "```clojure\n(FINAL {:n " (num-in req) "})\n```")
                     :else "```clojure\n(FINAL (rlm \"count NUM=5\"))\n```"))
        s (session-with :rlm :default resp)
        env (:turn/final-value (fe/run-turn! s "T-RLM"))]
    (testing "rlm returns an ENVELOPE, not a bare value"
      (is (true? (:rlm/result env)))
      (is (= :final (:rlm/status env)))
      (is (= {:n 5} (:rlm/value env)) "the child's FINAL is at :rlm/value"))
    (testing "the continuation/branch proxies + recognition meta are present"
      (is (string? (get-in env [:rlm/session :session/id])))
      (is (get-in env [:rlm/head :vars-ref]) "the Phase-1/2 head proxy is the child :vars-ref")
      (is (= :child (get-in env [:rlm/meta :kind])))
      (is (string? (get-in env [:rlm/meta :child/session-id]))))
    (testing "child accounting rides :rlm/meta (root :turn/usage stays self-only — 06 §6)"
      (is (contains? (:rlm/meta env) :usage))
      (is (contains? (:rlm/meta env) :cost))
      (is (contains? (:rlm/meta env) :cache)))
    (fe/stop-session! s)))

(deftest rlm-child-is-a-distinct-session-in-the-store
  (let [resp (fn [req]
               (cond (child-req? req) "```clojure\n(FINAL :child-done)\n```"
                     :else "```clojure\n(FINAL (:rlm/value (rlm \"sub\")))\n```"))
        s (session-with :rlm :default resp)]
    (is (= :child-done (:turn/final-value (fe/run-turn! s "go"))))
    (is (= 2 (count @(:sessions (:store s)))) "root + one child session live in the same store")
    (fe/stop-session! s)))

;; ===========================================================================
;; CHILD — nested recursion (recursion BETWEEN interpreters, depth ≥ 2)
;; ===========================================================================

(deftest nested-recursion-depth-2
  (let [resp (fn [req]
               (let [lu (fake/last-user req)]
                 (cond
                   (str/includes? lu "inner NUM=9") "```clojure\n(FINAL {:n 9})\n```"
                   (str/includes? lu "outer")        "```clojure\n(FINAL {:inner (:rlm/value (rlm \"inner NUM=9\"))})\n```"
                   :else                             "```clojure\n(FINAL (:rlm/value (rlm \"outer\")))\n```")))
        s (session-with :rlm :default resp)]
    (is (= {:inner {:n 9}} (:turn/final-value (fe/run-turn! s "T-NESTED")))
        "the root's child itself recurses — independent SCI ctxs to depth 2")
    (is (= 3 (count @(:sessions (:store s)))) "root + child + grandchild")
    (fe/stop-session! s)))

(deftest nested-child-fans-out-with-map-lm
  (testing "a child can itself fan out leaves (map-lm) inside its own loop"
    (let [resp (fn [req]
                 (cond (leaf-req? req) (leaf-double req)
                       (child-req? req) "```clojure\n(FINAL (mapv :double (map-lm [{:n 2 :v \"NUM=2\"} {:n 3 :v \"NUM=3\"}] \"double\" :edn)))\n```"
                       :else "```clojure\n(FINAL (:rlm/value (rlm \"sum doubles\")))\n```"))
          s (session-with :rlm :default resp)]
      (is (= [4 6] (:turn/final-value (fe/run-turn! s "go")))
          "the child's own map-lm fan-out ran and returned to the root")
      (fe/stop-session! s))))

;; ===========================================================================
;; ACCOUNTING — :turn/usage/:turn/cost stay SELF-ONLY at the root (06 §6)
;; ===========================================================================

(defn- known-rec
  "A FakeAdapter call-record with KNOWN usage/cost so accounting is assertable."
  [text in-tokens]
  {:text text :finish-reason :stop
   :usage {:usage/status :known :usage/input-tokens in-tokens :usage/output-tokens 1
           :usage/cached-input-tokens 0 :usage/cache-write-tokens 0}
   :cost  {:cost/status :known :cost/usd (double (* in-tokens 0.001))}
   :cache {:cache/status :miss :cache/cached-tokens 0 :cache/cache-write-tokens 0}
   :model "fake-model" :provider :fake})

(deftest root-turn-accounting-is-self-only
  (testing "the root :turn/usage/:turn/cost count ONLY the root's own steps; the child's
            cost rides the envelope :rlm/meta (06 §6)"
    (let [resp (fn [req]
                 (if (child-req? req)
                   (known-rec "```clojure\n(FINAL {:n 5})\n```" 7)        ; child step: 7 tokens
                   (known-rec "```clojure\n(FINAL (rlm \"child\"))\n```" 100))) ; root step: 100 tokens
          s (session-with :rlm :default resp)
          res (fe/run-turn! s "T")
          env (:turn/final-value res)]
      (is (= 100 (get-in res [:turn/usage :usage/input-tokens]))
          "root usage = 100 (its single step), NOT 107 — the child's 7 is excluded")
      (is (= 0.1 (get-in res [:turn/cost :cost/usd])) "root cost is self-only (100 * 0.001)")
      (is (= 7 (get-in env [:rlm/meta :usage :usage/input-tokens]))
          "the child's own usage is carried on the envelope :rlm/meta")
      (is (= 0.007 (get-in env [:rlm/meta :cost :cost/usd])) "the child's cost lives in the meta")
      (fe/stop-session! s))))

;; ===========================================================================
;; CHILD — map-rlm (independent lanes, order, partial failure)
;; ===========================================================================

(deftest map-rlm-independent-lanes
  (let [resp (fn [req]
               (cond (child-req? req) (let [n (num-in req)] (str "```clojure\n(FINAL {:sq " (* n n) "})\n```"))
                     :else "```clojure\n(FINAL (mapv :rlm/value (map-rlm [\"l NUM=1\" \"l NUM=2\" \"l NUM=3\"])))\n```"))
        s (session-with :rlm :default resp)]
    (is (= [{:sq 1} {:sq 4} {:sq 9}] (:turn/final-value (fe/run-turn! s "T")))
        "each lane is an isolated child; results are index-aligned")
    (is (= 4 (count @(:sessions (:store s)))) "root + 3 lane children")
    (fe/stop-session! s)))

(deftest map-rlm-shared-instruction
  (let [seen (atom [])
        resp (fn [req]
               (cond (child-req? req)
                     (do (swap! seen conj (fake/last-user req))
                         "```clojure\n(FINAL :ok)\n```")
                     :else "```clojure\n(FINAL (map-rlm [\"a\" \"b\"] \"SHARED-CTX\"))\n```"))
        s (session-with :rlm :default resp)]
    (fe/run-turn! s "T")
    (is (every? #(str/includes? % "SHARED-CTX") @seen)
        "the shared instruction is prepended to every lane's task")
    (fe/stop-session! s)))

(deftest map-rlm-partial-failure-never-throws
  (let [resp (fn [req]
               (let [lu (fake/last-user req)]
                 (cond
                   (and (child-req? req) (str/includes? lu "FAILME")) "```clojure\n(def x 1)\n```" ; never FINALs
                   (child-req? req) (str "```clojure\n(FINAL {:sq " (* (num-in req) (num-in req)) "})\n```")
                   :else "```clojure\n(FINAL (map-rlm [\"l NUM=2\" \"FAILME lane\" \"l NUM=3\"]))\n```")))
        s (session-with :rlm :default resp :max-steps 1)   ; the FAILME child exhausts in 1 step
        out (:turn/final-value (fe/run-turn! s "T"))]
    (is (= {:sq 4} (:rlm/value (nth out 0))) "lane 0 child succeeds (envelope)")
    (is (true? (:fractal/failed (nth out 1))) "lane 1 child never FINAL'd → sentinel, no throw")
    (is (= :fractal/child-failed (get-in (nth out 1) [:error :error/type])))
    (is (= {:sq 9} (:rlm/value (nth out 2))) "lane 2 child succeeds (envelope)")
    (fe/stop-session! s)))

;; ===========================================================================
;; CAPABILITY — inherit-and-clamp on every child spawn
;; ===========================================================================

(deftest child-inherits-parent-capability
  (let [s (session-with :rlm :default (constantly "x"))
        child (session/spawn-child! s {})]
    (is (= :default (:capability/name (:capability child))) "no override ⇒ child = parent")
    (is (= :deny (:cap/network (:capability child))) "parent :default denies network ⇒ child denies")
    (is (false? (resolves-ok? child "(slurp \"http://example.com/secret\")"))
        "the child cannot escape to the network — denied INSIDE the child")
    (session/stop-session! child)
    (fe/stop-session! s)))

(deftest child-override-cannot-loosen-clamps-down
  (let [s (session-with :rlm :default (constantly "x"))
        child (session/spawn-child! s {:capability :trusted})]  ; :trusted would allow network…
    (is (= :deny (:cap/network (:capability child)))
        "a loosening override is clamped to the parent's deny (escalation closed)")
    (session/stop-session! child)
    (fe/stop-session! s)))

;; ===========================================================================
;; HOT-SWAP — config-only; :locked-down drops lm/rlm
;; ===========================================================================

(deftest hot-swap-clojure-is-phase-1
  (let [s (start :clojure :default)]
    (is (true? (resolves-ok? s "FINAL")))
    (is (true? (resolves-ok? s "inspect")))
    (is (false? (resolves-ok? s "lm"))  ":clojure harness does not bind lm")
    (is (false? (resolves-ok? s "rlm")) ":clojure harness does not bind rlm")
    (is (false? (resolves-ok? s "attach-rlm")) ":clojure harness does not bind attach-rlm")
    (is (str/includes? (sys-prompt-of s) "operator with a live Clojure REPL"))
    (is (not (str/includes? (sys-prompt-of s) "active RLM in fractal-engine")))
    (fe/stop-session! s)))

(deftest hot-swap-rlm-is-recursive
  (let [s (start :rlm :default)]
    (is (true? (resolves-ok? s "FINAL")))
    (doseq [f ["lm" "map-lm" "rlm" "map-rlm" "attach-rlm"]]
      (is (true? (resolves-ok? s f)) (str f " is bound in :rlm harness")))
    (is (str/includes? (sys-prompt-of s) "active RLM in fractal-engine"))
    (fe/stop-session! s)))

(deftest hot-swap-is-config-only
  (testing "the SAME cfg differing ONLY in :harness flips the surface — zero code edits"
    (let [base {:adapter :fake :fake/respond (constantly "x") :model "fake-model" :capability :default}
          sc (fe/start-session! (fe/make-config (assoc base :harness :clojure)))
          sr (fe/start-session! (fe/make-config (assoc base :harness :rlm)))]
      (is (false? (resolves-ok? sc "rlm")))
      (is (true?  (resolves-ok? sr "rlm")))
      (fe/stop-session! sc)
      (fe/stop-session! sr))))

(deftest locked-down-drops-lm-rlm
  (testing ":locked-down profile drops lm/rlm even in :rlm harness (unfilterable egress)"
    (let [s (start :rlm :locked-down)]
      (is (true? (resolves-ok? s "FINAL")))
      (is (true? (resolves-ok? s "inspect")))
      (is (false? (resolves-ok? s "lm")))
      (is (false? (resolves-ok? s "map-lm")))
      (is (false? (resolves-ok? s "rlm")))
      (is (false? (resolves-ok? s "map-rlm")))
      (is (false? (resolves-ok? s "attach-rlm")))
      (testing "and CALLING lm at execution time is an unresolved-symbol error (not just unbound)"
        (is (false? (resolves-ok? s "(lm {} \"q\")")))
        (is (false? (resolves-ok? s "(rlm \"t\")")))
        (is (false? (resolves-ok? s "(attach-rlm {} \"t\")"))))
      (fe/stop-session! s))))

;; ===========================================================================
;; DURABILITY / RESUME across recursion (sqlite)
;; ===========================================================================

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-rec" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(deftest resume-works-in-rlm-mode-and-children-persist
  (let [dir (temp-dir!) sid "rlm-resume-root"
        resp (fe/responder
               [["Assigned task" "```clojure (def childvar 99) (FINAL {:n 5})```"]
                ["spawn" "```clojure (def rootvar 1) (FINAL (:rlm/value (rlm \"child NUM=5\")))```"]
                ["again" "```clojure (FINAL (+ rootvar 41))```"]])
        cfg (fe/make-config {:adapter :fake :fake/respond resp :model "fake-model"
                             :harness :rlm :capability :default
                             :store :sqlite :store/dir dir})]
    (try
      (let [child-sids (atom nil)]
        ;; run 1 — a turn that recurses into a child; capture the child sids
        (let [h1 (fe/start-session! cfg {:id sid})
              r1 (fe/run-turn! h1 "please spawn")]
          (is (= :final (:status r1)))
          (is (= {:n 5} (:turn/final-value r1)))
          (reset! child-sids (remove #{sid} (keys @(:sessions (:store h1)))))
          (is (= 1 (count @child-sids)) "the child is a durable session in the same store")
          (fe/close-session! h1))                          ; close the JDBC connection
        ;; resume the ROOT from disk — rlm harness, vars restored, new turn works
        (let [h2 (fe/resume-session! cfg sid)]
          (is (= sid (:session-id h2)))
          (is (true? (resolves-ok? h2 "rlm")) "the resumed session is still rlm-native")
          (is (:current-head (fe/view h2)) "the root current head survived the durable reopen")
          (is (some #(= :invocation (:edge/type %)) (:edges (fe/view h2)))
              "the parent invocation edge survived the durable reopen")
          (let [r2 (fe/run-turn! h2 "again")]
            (is (= :final (:status r2)))
            (is (= 42 (:turn/final-value r2)) "a root var def'd before the reopen survived"))
          (fe/close-session! h2))
        ;; the CHILD itself reopens durably from the same store
        (let [hc (fe/resume-session! cfg (first @child-sids))]
          (is (seq (:events (fe/view hc))) "the child's durable event log survived")
          (is (= :child (get-in (fe/view hc) [:session :session/kind])))
          (is (:current-head (fe/view hc)) "the child's immutable current head survived")
          (fe/close-session! hc)))
      (finally (rm-rf! dir)))))
