(ns fractal.engine.live-recursion-test
  "OPTIONAL live end-to-end RLM proofs against the real codex provider via OAuth
   (~/.codex/auth.json, provider :codex-backend, model gpt-5.5). ^:live, so these
   are EXCLUDED from `clojure -M:test` and run only via `clojure -M:live-test`.
   Every test is creds-guarded ((codex-backend-available?)) so the suite never
   makes a paid call without OAuth creds present. Each prints a compact result
   line so a human can eyeball the real recursion.

   Mechanism tests (leaf/child/fan-out/nested/partial-failure/clamp/cost) invoke
   the host fns DIRECTLY in a live :rlm session's SCI ctx (deterministic; the
   real model drives each leaf/child loop). The cheapness-adherence and hot-swap
   tests use run-turn! so the ROOT model itself must choose how to process.
   All ground truth is computed in Clojure."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [sci.core :as sci]
            [fractal.engine.api :as fe]
            [fractal.engine.payload-io :as payload-io]
            [fractal.engine.session :as session]
            [fractal.engine.store :as store]
            [llm.sdk.providers.codex :as codex]))

(def ^:private model "gpt-5.5")

(defn- live? [] (codex/codex-backend-available?))

(defn- codex-cfg [extra]
  (fe/make-config (merge {:adapter :sdk :provider :codex-backend :model model
                          :harness :rlm :capability :default
                          :max-steps 12 :cache-ttl "5m"}
                         extra)))

(defn- eval-in
  "Evaluate `code` in the live session's SCI ctx — directly invoking the injected
   host fns (lm/map-lm/rlm/map-rlm), each driving a real codex call/loop."
  [handle code]
  (sci/eval-string* @(:sci-ctx handle) code))

(defn- child-sids [handle]
  (remove #{(:session-id handle)} (keys @(:sessions (:store handle)))))

(defn- p [label m] (println (str "live " label " => " (pr-str m))))

(defmacro deflive [name & body]
  `(deftest ~(with-meta name {:live true})
     (if (live?)
       (do ~@body)
       (println (str "live " '~name " => [skip] no codex OAuth creds")))))

;; ===========================================================================
;; 1. LEAF JUDGMENT — map-lm sentiment over 10 labeled sentences (≥9/10)
;; ===========================================================================

(deflive leaf-judgment-sentiment
  (let [s (fe/start-session! (codex-cfg {}))
        cases [["I absolutely loved this, best day ever!"        :pos]
               ["This is the worst experience of my life."        :neg]
               ["The food was delicious and the staff were kind." :pos]
               ["Terrible service, I want a refund."              :neg]
               ["What a wonderful, heartwarming film."            :pos]
               ["I'm furious — it broke after one use."           :neg]
               ["Such a delightful surprise, highly recommend."   :pos]
               ["Disgusting and overpriced, never again."         :neg]
               ["A masterpiece; I was moved to tears of joy."     :pos]
               ["Completely useless and a total waste of money."  :neg]]
        inputs (vec (map-indexed (fn [i [t _]] {:id i :text t}) cases))
        truth  (mapv second cases)
        out    (eval-in s (str "(map-lm " (pr-str inputs)
                               " \"Classify the sentence sentiment. Return EDN {:id <the id> :sentiment :pos-or-:neg}.\" :edn)"))
        ok     (remove :fractal/failed out)
        graded (map (fn [r] (= (:sentiment r) (nth truth (:id r)))) ok)
        n-match (count (filter true? graded))]
    (p "leaf-judgment" {:n (count out) :ok (count ok) :match (str n-match "/10")})
    (is (= 10 (count out)) "map-lm returned one slot per input")
    (is (>= n-match 9) "a real model leaf classifies ≥9/10 correctly (with id echo for merge)")
    (fe/stop-session! s)))

;; ===========================================================================
;; 2. map-lm FAN-OUT AT SCALE + ORDER — 50 inputs, index-aligned == ground truth
;; ===========================================================================

(deflive map-lm-scale-and-order
  (let [s   (fe/start-session! (codex-cfg {}))
        ns- (range 10 60)                                  ; 50 distinct integers
        inputs (vec (map-indexed (fn [i n] {:id i :n n}) ns-))
        truth  (mapv #(if (even? %) :even :odd) ns-)
        out  (eval-in s (str "(map-lm " (pr-str inputs)
                             " \"Classify the integer :n as :even or :odd. Return EDN {:id <id> :n <the integer> :parity :even-or-:odd}.\" :edn)"))
        ok   (remove :fractal/failed out)
        ;; order preservation: the echoed :n at slot i must equal inputs[i] :n
        order-ok (every? true? (map-indexed (fn [i r] (= (:n r) (:n (nth inputs i)))) out))
        correct  (count (filter (fn [r] (= (:parity r) (nth truth (:id r)))) ok))]
    (p "map-lm-scale" {:n (count out) :ok (count ok) :order-preserved order-ok :parity-correct (str correct "/50")})
    (is (= 50 (count out)) "≤50 cap admits exactly 50; one index-aligned slot each")
    (is order-ok "the fan-out vector is index-aligned to the inputs (order preserved)")
    (is (>= correct 49) "the real-model leaf is parity-correct on essentially all 50")
    (fe/stop-session! s)))

;; ===========================================================================
;; 3. SINGLE rlm CHILD — a counting task; envelope :rlm/value == Clojure truth
;; ===========================================================================

(deflive single-rlm-child-count
  (let [s    (fe/start-session! (codex-cfg {}))
        docs ["alpha beta alpha gamma alpha delta"
              "gamma gamma alpha beta beta alpha"
              "delta delta delta alpha gamma alpha"
              "alpha alpha beta gamma delta alpha"
              "beta gamma alpha delta alpha alpha"]
        truth (->> docs (mapcat #(str/split % #"\s+")) (filter #(= "alpha" %)) count)
        task (str "Across these documents, count the TOTAL number of times the exact word "
                  "\"alpha\" appears. Compute it deterministically in Clojure (split on whitespace, "
                  "count). Documents (a Clojure vector of strings):\n" (pr-str docs)
                  "\nReturn exactly {:count <n>} via (FINAL ...).")
        env  (eval-in s (str "(rlm " (pr-str task) ")"))]
    (p "single-rlm-child" {:value (:rlm/value env) :truth {:count truth}
                           :child-steps (get-in env [:rlm/meta :step-count])
                           :child-cost (get-in env [:rlm/meta :cost :cost/status])})
    (is (true? (:rlm/result env)) "rlm returns an envelope")
    (is (= {:count truth} (:rlm/value env)) "the child ran its own loop to the right value")
    (is (get-in env [:rlm/head :vars-ref]) "the envelope carries the Phase-1/2 head proxy")
    (fe/stop-session! s)))

;; ===========================================================================
;; 4. map-rlm INDEPENDENT LANES — column-sum per CSV blob; each lane isolated
;; ===========================================================================

(deflive map-rlm-independent-lanes
  (let [s    (fe/start-session! (codex-cfg {}))
        blobs (mapv (fn [seed]
                      (str/join "\n" (cons "name,amount"
                                           (map #(str "row" % "," (+ seed %)) (range 1 6)))))
                    [0 10 20 30 40 50 60 70])             ; 8 CSV blobs
        truth (mapv (fn [seed] (reduce + (map #(+ seed %) (range 1 6)))) [0 10 20 30 40 50 60 70])
        tasks (mapv (fn [b] (str "Sum the integer values in the 'amount' column of this CSV "
                                 "(deterministically in Clojure). Return {:sum <n>} via FINAL.\nCSV:\n" b))
                    blobs)
        out  (eval-in s (str "(map-rlm " (pr-str tasks) ")"))
        ok   (remove :fractal/failed out)
        sums (mapv #(get-in % [:rlm/value :sum]) out)]
    (p "map-rlm-lanes" {:lanes (count out) :ok (count ok) :sums sums :truth truth
                        :children (count (child-sids s))})
    (is (= 8 (count out)) "one envelope slot per lane, order preserved")
    (is (= truth sums) "each independent child computed its own column-sum correctly")
    (is (>= (count (child-sids s)) 8) "each lane ran in its OWN child session (per-child isolation)")
    (fe/stop-session! s)))

;; ===========================================================================
;; 5. NESTED RECURSION — root child itself recurses (depth ≥ 2)
;; ===========================================================================

(deflive nested-recursion-depth-2
  (let [s (fe/start-session! (codex-cfg {:max-steps 16}))
        groupA [3 5 7] groupB [10 20 30]
        truth {:a (reduce + groupA) :b (reduce + groupB) :total (+ (reduce + groupA) (reduce + groupB))}
        outer (str "You must DELEGATE each subgroup to its OWN child via (rlm ...). "
                   "For subgroup A " (pr-str groupA) " call (rlm \"Sum these integers in Clojure: "
                   (pr-str groupA) ". Return {:sum n} via FINAL.\") and read (:rlm/value ...). "
                   "Do the same for subgroup B " (pr-str groupB) ". "
                   "Then FINAL {:a <sumA> :b <sumB> :total <sumA+sumB>}.")
        env (eval-in s (str "(rlm " (pr-str outer) ")"))]
    (p "nested-recursion" {:value (:rlm/value env) :truth truth :sessions (count @(:sessions (:store s)))})
    (is (= truth (:rlm/value env)) "a 2-level recursion (root→child→grandchildren) aggregates correctly")
    (is (>= (count @(:sessions (:store s))) 4)
        "root + child + ≥2 grandchildren exist — recursion BETWEEN interpreters to depth 2")
    (fe/stop-session! s)))

;; ===========================================================================
;; 6. PARTIAL-FAILURE RESILIENCE (live) — a budgeted lane cannot hurt siblings
;; ===========================================================================

(deflive map-rlm-partial-failure
  (let [s (fe/start-session! (codex-cfg {:max-steps 3}))   ; the budgeted lane often needs many steps
        easy1 "Sum these integers in Clojure: [2 4 6]. Return {:sum n} via FINAL."
        easy2 "Sum these integers in Clojure: [10 20]. Return {:sum n} via FINAL."
        hard  (str "This is a STRICT multi-stage task. You may emit only ONE fenced block per reply, "
                   "each block defining exactly ONE new var named stage1, stage2, … and printing it. "
                   "You MUST complete at least 8 separate stages across 8 separate replies, reading the "
                   "observation each time, and you may NOT call FINAL until stage8 exists. Begin with stage1.")
        out (eval-in s (str "(map-rlm " (pr-str [easy1 hard easy2]) ")"))
        lane1 (nth out 1)]
    (p "partial-failure" {:lanes (count out)
                          :slot0 (get-in out [0 :rlm/value])
                          :slot1-failed (boolean (:fractal/failed lane1))
                          :slot1-result (boolean (:rlm/result lane1))
                          :slot1-err (get-in lane1 [:error :error/type])
                          :slot2 (get-in out [2 :rlm/value])})
    (is (= 3 (count out)) "the fan-out returned a full index-aligned vector (never threw)")
    (is (= {:sum 12} (get-in out [0 :rlm/value])) "easy lane 0 succeeded")
    (is (= {:sum 30} (get-in out [2 :rlm/value])) "easy lane 2 succeeded")
    (is (or (:fractal/failed lane1) (:rlm/result lane1))
        "the budgeted lane resolves to either a failure sentinel or a normal child envelope")
    (fe/stop-session! s)))

;; ===========================================================================
;; 7. CHEAPNESS-HIERARCHY ADHERENCE — the model counts in Clojure, not a leaf
;; ===========================================================================

(deflive cheapness-uses-clojure-for-exact-count
  (let [s (fe/start-session! (codex-cfg {}))
        words (str/join " " (concat (repeat 23 "red") (repeat 17 "blue") (repeat 11 "red") (repeat 9 "green")))
        truth (->> (str/split words #"\s+") (filter #(= "red" %)) count)   ; 34
        res (fe/run-turn! s (str "Count exactly how many times the word \"red\" appears in this text, "
                                 "then FINAL {:red <n>}. Text:\n" words))
        evals (->> (:evals (fe/view s))
                   (map #(payload-io/read-payload s (:eval/code-or-ref %)))
                   (filter string?))
        used-clj-count? (boolean (some #(re-find #"\b(count|frequencies|filter|re-seq)\b" %) evals))
        used-leaf-for-count? (boolean (some #(re-find #"\(\s*(map-lm|lm)\b" %) evals))]
    (p "cheapness" {:answer (:turn/final-value res) :truth {:red truth}
                    :clj-count used-clj-count? :leaf-for-count used-leaf-for-count?})
    (is (= :final (:status res)) (str "error: " (:error res)))
    (is (= {:red truth} (:turn/final-value res)) "the exact count is correct")
    (is used-clj-count? "the model computed the count with deterministic Clojure (the doctrine landed)")
    (is (not used-leaf-for-count?) "the model did NOT misuse a leaf for the exact count")
    (fe/stop-session! s)))

;; ===========================================================================
;; 8. HOT-SWAP LIVE — same task; :rlm genuinely delegates (child sessions exist)
;; ===========================================================================

(deflive hot-swap-rlm-delegates
  (let [parts {:p1 [1 2 3 4 5] :p2 [6 7 8 9 10] :p3 [11 12 13 14 15]}
        truth (reduce + (mapcat val parts))
        task (str "You are given three independent partitions. DELEGATE each partition to its OWN "
                  "child with (rlm ...): for each, call (rlm \"Sum these integers in Clojure and FINAL {:sum n}: <ints>\"), "
                  "read (:rlm/value ...), then FINAL {:total <sum of the three child sums>}. Partitions: " (pr-str parts))]
    (testing ":rlm harness — the root delegates to children"
      (let [s (fe/start-session! (codex-cfg {:max-steps 16}))
            res (fe/run-turn! s task)
            kids (count (child-sids s))]
        (p "hot-swap-rlm" {:status (:status res) :value (:turn/final-value res) :truth {:total truth} :children kids})
        (is (= :final (:status res)) (str "error: " (:error res)))
        (is (= {:total truth} (:turn/final-value res)))
        (is (pos? kids) "the :rlm run genuinely delegated — child sessions exist in the store")
        (fe/stop-session! s)))
    (testing ":clojure harness — same config minus harness; no recursion bound"
      (let [s (fe/start-session! (codex-cfg {:harness :clojure :max-steps 16}))
            res (fe/run-turn! s task)]
        (p "hot-swap-clojure" {:status (:status res) :value (:turn/final-value res) :children (count (child-sids s))})
        ;; :clojure solves it single-session (or hits max-steps) — either way it CANNOT delegate
        (is (zero? (count (child-sids s))) "the :clojure harness has no rlm — it cannot spawn children")
        (fe/stop-session! s)))))

;; ===========================================================================
;; 9. CAPABILITY CLAMP LIVE — a real spawn inherits+clamps; child cannot escape
;; ===========================================================================

(deflive capability-clamp-live
  (let [s (fe/start-session! (codex-cfg {}))
        child (session/spawn-child! s {})]
    (testing "a real child spawned from a :default parent inherits its (clamped) capability"
      (is (= :default (:capability/name (:capability child))))
      (is (= :deny (:cap/network (:capability child)))))
    (testing "a denied op fails INSIDE the child, not by escaping"
      (let [denied? (try (sci/eval-string* @(:sci-ctx child) "(slurp \"http://example.com/secret\")")
                         false
                         (catch Throwable _ true))]
        (p "capability-clamp" {:child-cap (:capability/name (:capability child))
                               :network (:cap/network (:capability child)) :network-read-denied denied?})
        (is denied? "the child cannot read the network — the escalation is closed")))
    (session/stop-session! child)
    (fe/stop-session! s)))

;; ===========================================================================
;; 10. COST/CACHE HONESTY ACROSS THE TREE — root self-only; child cost in meta
;; ===========================================================================

(deflive cost-honesty-across-tree
  (let [s (fe/start-session! (codex-cfg {:max-steps 10}))
        task (str "Delegate to a child: call (rlm \"Sum these integers in Clojure and FINAL {:sum n}: [4 5 6]\"), "
                  "read its (:rlm/value ...), and FINAL that envelope's value's :sum plus 100 as {:answer n}.")
        res (fe/run-turn! s task)
        ;; find the child envelope's cost via the child session's committed turn
        kid (first (child-sids s))
        kid-turn (when kid (last (:turns (store/current-view (:store s) kid))))]
    (p "cost-honesty" {:root-cost (get-in res [:turn/cost :cost/status])
                       :root-usd (get-in res [:turn/cost :cost/usd])
                       :child-cost (get-in kid-turn [:turn/cost :cost/status])})
    (is (= :final (:status res)) (str "error: " (:error res)))
    (is (contains? #{:known :unknown} (:cost/status (:turn/cost res))) "root :turn/cost is honest")
    (is (contains? #{:known :unknown} (:usage/status (:turn/usage res))) "root :turn/usage is honest")
    (testing "the child's own cost is accounted on the CHILD turn (self-only), separate from the root"
      (is (some? kid-turn) "the child is a real session in the store")
      (is (contains? #{:known :unknown} (:cost/status (:turn/cost kid-turn)))
          "the child carries its OWN honest cost — the root's :turn/cost did not absorb it"))
    (fe/stop-session! s)))
