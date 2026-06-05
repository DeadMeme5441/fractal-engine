(ns fractal-engine.core-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fractal-engine.artifacts :as artifacts]
            [fractal-engine.blob-store :as blob]
            [fractal-engine.cache :as cache]
            [fractal-engine.codebrain :as codebrain]
            [fractal-engine.inspect :as inspect]
            [fractal-engine.process :as process]
            [fractal-engine.projection :as projection]
            [fractal-engine.prompt :as prompt]
            [fractal-engine.render :as render]
            [fractal-engine.session :as session]
            [fractal-engine.session-db :as session-db]
            [fractal-engine.session-model :as session-model]
            [fractal-engine.session-sqlite :as sqlite]
            [fractal-engine.snapshot :as snapshot]
            [fractal-engine.store.consistency :as store-consistency]
            [fractal-engine.store.index :as store-index]
            [fractal-engine.store.io :as store-io]
            [fractal-engine.store.schema :as store-schema])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption]))

(def model-surface '#{FINAL lm map-lm rlm map-rlm attach-rlm})

(defn tmp-dir [name]
  (str (Files/createTempDirectory
        (str "fractal-" name "-")
        (make-array java.nio.file.attribute.FileAttribute 0))))

;; The suite intentionally uses the production file-backed Datahike config.
;; Datahike's :memory backend works for tiny local smokes, but this suite's
;; repeated connect/release pattern hit core.async's pending-take limit in the
;; memory writer. Slow file-backed tests are storage cost, not a hang.

(defn base-config [root extra]
  (process/config (merge {:runs-dir root
                          :models {:root {:provider :scripted :model "scripted-root"}
                                   :leaf {:provider :scripted :model "scripted-leaf"}
                                   :child {:provider :scripted :model "scripted-child"}}
                          :max-turns 8
                          :max-fanout 8}
                         extra)))

(defn scripted-cfg [root responses]
  (base-config root {:scripted/responses (atom (vec responses))}))

(defn scripted-fn-cfg [root response-fn]
  (base-config root {:scripted/response-fn response-fn}))

(defn run-root! [root session-id responses task]
  (process/run-process! (scripted-cfg root responses)
                        {:id session-id
                         :store-root root
                         :kind :root
                         :task task}))

(defn run-root-fn! [root session-id response-fn task]
  (process/run-process! (scripted-fn-cfg root response-fn)
                        {:id session-id
                         :store-root root
                         :kind :root
                         :task task}))

(defn issue-types [report]
  (set (map :issue/type (:issues report))))

(defn quick-ok? [root]
  (= :ok (:status (store-consistency/check-consistency root {:mode :quick}))))

(defn deep-ok? [root]
  (= :ok (:status (store-consistency/check-consistency root {:mode :deep}))))

(defn attr-values [root attr]
  (store-index/q root store-schema/schema '[:find ?v :in $ ?attr :where [_ ?attr ?v]] attr))

(defn blob-file [root ref]
  (blob/path root "blobs" (:blob/key ref)))

(defn blob-ok? [root ref]
  (= :ok (:status (blob/verify (blob/file-store root) ref))))

(defn blob-value [root ref]
  (blob/read-edn (blob/file-store root) ref))

(defn request-text [request]
  (->> (:request/messages request)
       (filter #(= :user (:message/role %)))
       last
       :message/content
       str))

(defn leaf-input [request]
  (some-> (re-find #"(?s)Input EDN:\n(.*?)\n\nQuery:" (request-text request))
          second
          edn/read-string))

(defn response-map [content]
  {:message/content content})

(defn rlm-envelope? [value]
  (and (:rlm/result value)
       (= :final (:rlm/status value))
       (contains? value :rlm/value)
       (get-in value [:rlm/session :session/id])
       (get-in value [:rlm/head :head/id])
       (get-in value [:rlm/invocation :invocation/id])
       (get-in value [:rlm/meta :kind])
       (get-in value [:rlm/meta :task/hash])
       (get-in value [:rlm/meta :task/preview])
       (get-in value [:rlm/meta :value/preview])))

(defn rlm-value [value]
  (:rlm/value value))

(defn read-call-request [root call]
  (blob-value root (:call/request-ref call)))

(defn raw-head-state-root [root head]
  (blob-value root (:head/state-ref head)))

(deftest prompt-contract
  (is (= 22 prompt/prompt-version))
  (doseq [phrase ["You are the active RLM in fractal-engine"
                  "complete the task fully -- do not gold-plate, but do not leave it half-done"
                  "The caller may be a human, a CLI/API host, or another RLM session."
                  "Be an operator, not a commentator."
                  "Your strengths:"
                  "map-lm and map-rlm are capped at 50 parallel inputs per call."
                  "For more than 50 items, sequence batches of 40-50 with partition-all, run each chunk as its own map-lm or map-rlm, reduce each chunk locally, then reduce those partials globally."
                  "The host will return a recoverable fanout error for a single oversized fan-out; retry by chunking, not by raising the cap."
                  "Successful map-lm slots hold leaf values; successful map-rlm slots hold RLM envelopes, with the child FINAL at :rlm/value."
                  "rlm/map-rlm return envelopes, not bare child FINAL values."
                  "FINAL is your return value. It is not a progress note, not a message to a human, and not a place to display raw material."
                  "Default cadence for non-trivial work:"
                  "attach-rlm handle task"]]
    (is (str/includes? prompt/system-prompt phrase)))
  (doseq [p [prompt/system-prompt prompt/child-prompt prompt/leaf-prompt]]
    (is (not (str/includes? p "context")))
    (doseq [forbidden ["product" "storage" "workflow"]]
      (is (not (str/includes? (str/lower-case p) forbidden)))))
  (doseq [sym (map name model-surface)]
    (is (str/includes? prompt/system-prompt sym)))
  (is (= (:prompt/hash prompt/prompt-metadata)
         (cache/sha256-string prompt/system-prompt)))
  (is (= prompt/system-prompt prompt/child-prompt)
      "root/child is an invocation-edge role, not a different behavior prompt")
  (is (= prompt/prompt-metadata prompt/child-prompt-metadata))
  (is (str/includes? (prompt/child-invocation-frame "task")
                     "This assignment describes this invocation edge only"))
  (is (str/includes? (prompt/attach-invocation-frame "task" :branch)
                     "branches from an immutable source head")))

(deftest child-role-is-an-invocation-frame-not-a-session-prompt
  (let [root (tmp-dir "child-frame")
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "spawn framed child")
                        "```clojure\n(FINAL (rlm \"EDGE_TASK\"))\n```"

                        (str/includes? text "Assigned task:\nEDGE_TASK")
                        "```clojure\n(FINAL {:saw-frame true})\n```"

                        :else
                        "```clojure\n(FINAL :unknown)\n```")))
        result (run-root-fn! root "root" responder "spawn framed child")
        inv (first (session-db/list-invocation-records root))
        child-view (projection/view (session-db/locator root (:callee/session inv)))
        child-messages (:messages child-view)
        system-message (first (filter #(= :system (:message/role %)) child-messages))
        user-message (first (filter #(= :user (:message/role %)) child-messages))]
    (is (rlm-envelope? (:final-value result)))
    (is (= {:saw-frame true} (rlm-value (:final-value result))))
    (is (= prompt/system-prompt (:message/content system-message)))
    (is (str/includes? (:message/content user-message)
                       "This assignment describes this invocation edge only"))
    (is (str/includes? (:message/content user-message) "EDGE_TASK"))
    (is (= #{"scripted-child"} (set (map :call/model (:calls child-view))))
        "the invocation edge still uses the configured child model")))

(deftest codebrain-prompts-calibrate-decomposition
  (let [root "/repo"
        overlay (codebrain/session-overlay root)
        build (codebrain/build-message root)
        ask (codebrain/ask-message "Where is the CLI dispatched?")]
    (is (str/includes? overlay "Do not over-decompose."))
    (is (str/includes? overlay "only spawn children for lanes that genuinely need"))
    (is (str/includes? overlay "Operation contracts live here"))
    (is (str/includes? (str/lower-case overlay)
                       "do not spawn a child just because a folder exists."))
    (is (str/includes? overlay "rlm/map-rlm only"))
    (is (str/includes? build ":codebrain/op :build-map"))
    (is (str/includes? ask ":codebrain/op :ask"))
    (is (not (str/includes? build "Do not spawn a child just because a folder exists.")))
    (is (not (str/includes? ask "Use the cheapest sufficient work:")))
    (is (< (count build) 120))
    (is (< (count ask) 180))))

(deftest codebrain-fake-script-builds-and-resumes-warm-map
  (let [root (tmp-dir "codebrain")
        flags {:runs-dir root :fake-script "codebrain" :max-turns "8" :max-fanout "8"}
        init-result (codebrain/command ["init"] flags)
        ask-result (codebrain/command ["ask" "Where is the loop?"] flags)
        bdir (codebrain/brain-dir root)
        m (codebrain/load-map bdir)
        meta (codebrain/load-meta bdir)]
    (is (= 0 (:exit init-result)))
    (is (= 0 (:exit ask-result)))
    (is (= "r" (:root m)))
    (is (= {:strategy "bounded toy repo; no child needed"
            :child-count 0
            :leaf-count 0}
           (:decomposition m)))
    (is (= 1 (:turns meta)))
    (is (str/includes? (:out ask-result) "the map knows 1 subsystem"))
    (is (quick-ok? root))))

(deftest model-facing-surface-is-exact
  (let [root (tmp-dir "surface")
        s (session/start-session! (scripted-cfg root []) {:id "surface"
                                                          :store-root root})
        public-vars (set (keys (ns-publics (the-ns (:ns-sym s)))))]
    (session/stop-session! s)
    (is (= model-surface public-vars))))

(deftest semantic-fact-payload-boundary-is-enforced
  (let [root (tmp-dir "boundary")
        result (run-root! root "root"
                          [{:response/parts [{:part/type :text
                                               :text "```clojure\n(def tiny 1)\n(FINAL tiny)\n```"}]
                            :response/usage {:usage/input-tokens (int 12)
                                             :usage/output-tokens (int 3)}
                            :response/cost {:cost/usd 0.0004}}]
                          "small payloads still go to blobs")
        locator (:locator result)
        node (projection/load-node locator)
        view (projection/view locator)
        head (session-db/read-head root (:head-id result))
        direct-head (store-index/pull root store-schema/schema
                                     '[:head/id :head/snapshot-id
                                       {:head/snapshot-ref [:blob/id]}]
                                     [:head/id (:head-id result)])
        snapshot-link (set
                       (store-index/q root store-schema/schema
                                     '[:find ?snapshot-id ?head-blob-id ?snapshot-blob-id
                                       :in $ ?head-id
                                       :where
                                       [?h :head/id ?head-id]
                                       [?h :head/session ?session]
                                       [?h :head/snapshot-id ?snapshot-id]
                                       [?h :head/snapshot-ref ?head-blob]
                                       [?head-blob :blob/id ?head-blob-id]
                                       [?snapshot :snapshot/session ?session]
                                       [?snapshot :snapshot/id ?snapshot-id]
                                       [?snapshot :snapshot/ref ?snapshot-blob]
                                       [?snapshot-blob :blob/id ?snapshot-blob-id]]
                                     (:head-id result)))
        snapshot-row (last (:snapshots view))
        snapshot-blob (snapshot/require-snapshot-blob locator snapshot-row)
        events (projection/event-stream locator)
        trace (projection/event-trace locator)
        head-trace (first (filter #(and (= :head/created (:event/type %))
                                        (= (:head/id head)
                                           (get-in % [:trace/row :ref/id])))
                                  trace))
        ref-trace (first (filter #(and (= :session/ref-updated (:event/type %))
                                       (= (:head/id head)
                                          (get-in % [:trace/row :ref/id])))
                                 trace))
        ref-chain (projection/event-chain locator (:event/id ref-trace))
        event-payload-refs (attr-values root :event/payload-ref)
        files (->> (file-seq (java.io.File. root)) (map #(.getName %)) set)]
    (is (= 1 (:final node)))
    (testing "content-like fields are not inline Datahike facts"
      (doseq [attr [:message/content :eval/code :request/messages :response/body
                    :final/value :snapshot/vars :var/value :error/stacktrace]]
        (is (empty? (attr-values root attr)) (str attr))))
    (testing "bounded metadata remains queryable"
      (is (contains? (set (map first (attr-values root :message/role))) :assistant))
      (is (= #{"scripted-root"} (set (map first (attr-values root :call/model)))))
      (is (= #{:scripted} (set (map first (attr-values root :call/provider)))))
      (is (= Long (class (ffirst (attr-values root :call/input-tokens)))))
      (is (= Long (class (ffirst (attr-values root :call/output-tokens)))))
      (is (= #{["root"]} (store-index/q root store-schema/schema '[:find ?cache
                                              :where [_ :session/cache-id ?cache]]))))
    (testing "all payloads are content-addressed blobs, even tiny payloads"
      (is (= (long (:snapshot/id snapshot-row)) (:head/snapshot-id direct-head))
          "completed head exposes snapshot id as a direct Datahike fact")
      (is (= #{[(long (:snapshot/id snapshot-row))
                (get-in (:head/snapshot-ref head) [:blob/id])
                (get-in (:snapshot/ref snapshot-row) [:blob/id])]}
             snapshot-link)
          "head snapshot id resolves to the matching snapshot fact and blob")
      (is (blob-ok? root (:head/final-ref head)))
      (is (blob-ok? root (:head/snapshot-ref head)))
      (is (blob-ok? root (:snapshot/ref snapshot-row)))
      (is (= 1 (blob-value root (:head/final-ref head))))
      (is (some #(and (= "tiny" (:var/name %))
                      (get-in % [:var/value-ref :blob/id]))
                (:snapshot/vars snapshot-blob))))
    (testing "events are compact facts, not duplicated full payload blobs"
      (is (empty? event-payload-refs))
      (is (seq events))
      (is (every? #(contains? % :event/row-kind) events))
      (is (not (str/includes? (pr-str events) ":message/content")))
      (is (not (str/includes? (pr-str events) ":eval/code")))
      (is (not (str/includes? (pr-str events) ":request/messages"))))
    (testing "event trace is a causal audit surface, not a restore surface"
      (is (= (count events) (count trace)))
      (is (every? :trace/summary trace))
      (is (every? :trace/row trace))
      (is (not (str/includes? (pr-str trace) ":message/content")))
      (is (not (str/includes? (pr-str trace) ":eval/code")))
      (is (some #(= {:ref/kind :snapshot
                     :ref/id (str (:head/snapshot-id head))}
                  %)
                (:trace/causes head-trace))
          "head creation is causally linked to the snapshot it seals")
      (is (some #{(:event/id head-trace)} (:trace/cause-event-ids ref-trace))
          "current-head ref movement is causally linked to the head event")
      (is (= (:event/id ref-trace) (:event/id (last ref-chain))))
      (is (some #(= :head/created (:event/type %)) ref-chain)))
    (testing "runtime did not create legacy per-session source files"
      (is (not (contains? files "session.edn")))
      (is (not (contains? files "events.ednl")))
      (is (not (contains? files "ref.edn"))))
    (is (deep-ok? root))))

(deftest compact-head-state-manifests-share-prior-state-and_restore_exact_head
  (let [root (tmp-dir "compact-head")
        large-payload (apply str (repeat 900 " public-safe payload"))
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "COMPACT_T1")
                        "```clojure\n(def x 1)\n(FINAL {:turn 1 :x x})\n```"

                        (str/includes? text "COMPACT_T2")
                        "```clojure\n(def y 2)\n(def future-leaf (lm {:id 7} \"Return id as EDN.\" :edn))\n(FINAL {:turn 2 :x x :y y :future-leaf future-leaf})\n```"

                        (str/includes? text "COMPACT_BRANCH")
                        "```clojure\n(FINAL {:x x :y-bound? (contains? (ns-publics *ns*) 'y) :future-leaf-bound? (contains? (ns-publics *ns*) 'future-leaf)})\n```"

                        (some? (leaf-input request))
                        (pr-str (:id (leaf-input request)))

                        :else
                        "```clojure\n(FINAL :unknown)\n```")))
        cfg (scripted-fn-cfg root responder)
        s (session/start-session! cfg {:id "main" :alias "main" :store-root root})
        first-result (session/run-turn! s (str "COMPACT_T1\n" large-payload))
        first-head (:head-id first-result)
        second-result (session/run-turn! s (str "COMPACT_T2\n" large-payload))
        second-head (:head-id second-result)
        resumed (session/resume-session! cfg "main" {:head first-head})
        branch-result (session/run-turn! resumed "COMPACT_BRANCH")
        branch-head (:head-id branch-result)
        current-view (projection/view (:locator branch-result))
        history-view (projection/history-view (:locator branch-result))
        heads (filterv #(= "main" (:head/session %))
                       (session-db/list-head-records root))
        raw-states (mapv #(raw-head-state-root root %) heads)
        turn-heads (filterv :head/turn-id heads)
        current-text (pr-str (select-keys current-view [:messages :calls :evals]))]
    (is (= {:x 1 :y-bound? false :future-leaf-bound? false}
           (:final-value branch-result))
        "restore from the first head must not see vars created on the abandoned later head")
    (is (= first-head (:head/basis (session-db/read-head root branch-head))))
    (is (contains? (set (map :head/id (:heads history-view))) second-head)
        "abandoned sibling heads remain queryable through history")
    (is (not (contains? (set (map :head/id (:heads current-view))) second-head))
        "current/head view follows only the active head")
    (is (not (str/includes? current-text "COMPACT_T2"))
        "current view excludes abandoned future messages")
    (is (empty? (filter #(= :leaf (:call/type %)) (:calls current-view)))
        "current view excludes abandoned future leaf calls")
    (is (every? #(= 2 (:state/version %)) raw-states))
    (is (every? #(contains? % :state/collections) raw-states))
    (is (every? #(not (contains? % :messages)) raw-states)
        "head state roots are manifests, not full active-state blobs")
    (is (every? #(< (get-in % [:head/state-ref :blob/size]) 20000) turn-heads)
        "head state root blobs stay small instead of repeating the full prior transcript")
    (is (quick-ok? root))))

(deftest root-call-request-refs_are_descriptors_while_leaf_refs_remain_readable
  (let [root (tmp-dir "request-descriptor")
        large-payload (apply str (repeat 900 " public-safe request payload"))
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "REQUEST_T1")
                        "```clojure\n(FINAL {:turn 1})\n```"

                        (str/includes? text "REQUEST_T2")
                        "```clojure\n(def leaf-value (lm {:id 9} \"Return id as EDN.\" :edn))\n(FINAL {:turn 2 :leaf leaf-value})\n```"

                        (some? (leaf-input request))
                        (pr-str (:id (leaf-input request)))

                        :else
                        "```clojure\n(FINAL :unknown)\n```")))
        cfg (scripted-fn-cfg root responder)
        s (session/start-session! cfg {:id "main" :alias "main" :store-root root})
        _first (session/run-turn! s (str "REQUEST_T1\n" large-payload))
        result (session/run-turn! s (str "REQUEST_T2\n" large-payload))
        view (projection/view (:locator result))
        root-calls (filterv #(= :root (:call/type %)) (:calls view))
        leaf-call (first (filter #(= :leaf (:call/type %)) (:calls view)))
        root-requests (mapv #(read-call-request root %) root-calls)
        leaf-request (read-call-request root leaf-call)
        node (projection/load-node (:locator result))
        inspected (inspect/structured (:locator result) {:tree true :snapshots true :handles true})]
    (is (= {:turn 2 :leaf 9} (:final-value result)))
    (is (seq root-calls))
    (is (every? #(= :root-agent (:request/kind %)) root-requests))
    (is (every? false? (map #(contains? % :request/messages) root-requests))
        "root request blobs store message ids, not the full rendered transcript")
    (is (every? seq (map :request/message-ids root-requests)))
    (is (every? #(< (get-in % [:call/request-ref :blob/size]) 5000) root-calls))
    (is (seq (:request/messages leaf-request))
        "leaf requests remain rendered and independently inspectable")
    (is (= 9 (blob-value root (:call/result-ref leaf-call))))
    (is (= {:turn 2 :leaf 9} (:final node)))
    (is (= "main" (get-in inspected [:session :id])))
    (is (quick-ok? root))))

(deftest model-driven-context-compaction-advances-current-head-and-preserves-vars
  (let [root (tmp-dir "model-compaction")
        large-payload (apply str (repeat 500 " public-safe transcript payload"))
        compaction-seen (atom nil)
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "Create a compact continuation user message")
                        (do
                          (reset! compaction-seen text)
                          "Continuation frame: the session defined `saved` as 42, completed the seed turn, and should use the preserved REPL var for later turns.")

                        (str/includes? text "SEED_COMPACTION")
                        "```clojure\n(def saved 42)\n(FINAL {:seed saved})\n```"

                        (str/includes? text "USE_COMPACTED")
                        "```clojure\n(FINAL {:saved saved :compact-frame-visible true})\n```"

                        :else
                        "```clojure\n(FINAL :unknown)\n```")))
        cfg (scripted-fn-cfg root responder)
        s (session/start-session! cfg {:id "main" :alias "main" :store-root root})
        seed-result (session/run-turn! s (str "SEED_COMPACTION\n" large-payload))
        seed-head (:head-id seed-result)
        compact-result (session/compact-session! s)
        compact-head (:head-id compact-result)
        compacted-view (projection/view (:locator compact-result))
        use-result (session/run-turn! s "USE_COMPACTED")
        current-view (projection/view (:locator use-result))
        compacted-messages (:messages compacted-view)
        active-text (pr-str (:messages current-view))
        seed-head-text (pr-str (:messages (session-db/read-head-state root "main" seed-head)))
        compaction-head (session-db/read-head root "main" compact-head)
        compaction-call (first (filter #(= :context-compaction (:call/purpose %))
                                       (:calls current-view)))
        compaction-request (read-call-request root compaction-call)]
    (is (= {:seed 42} (:final-value seed-result)))
    (is (= :compacted (:status compact-result)))
    (is (= seed-head (:head/basis compaction-head)))
    (is (= :context-compaction (:head/kind compaction-head)))
    (is (= {:saved 42 :compact-frame-visible true} (:final-value use-result)))
    (is (str/includes? @compaction-seen "SEED_COMPACTION"))
    (is (str/includes? @compaction-seen "public-safe transcript payload"))
    (is (= [:system :user] (mapv :message/role compacted-messages)))
    (is (str/includes? active-text "Continuation frame:"))
    (is (not (str/includes? active-text "SEED_COMPACTION")))
    (is (not (str/includes? active-text "public-safe transcript payload")))
    (is (str/includes? seed-head-text "SEED_COMPACTION")
        "prior transcript remains reachable through the previous immutable head")
    (is (= :context-compaction (:request/kind compaction-request)))
    (is (= seed-head (:request/source-head-id compaction-request)))
    (is (false? (:request/rendered? compaction-request)))
    (is (quick-ok? root))))

(deftest context-hard-preflight-refuses-oversized-provider-request
  (let [root (tmp-dir "context-hard-limit")
        called? (atom false)
        cfg (base-config root {:scripted/response-fn (fn [_]
                                                       (reset! called? true)
                                                       "```clojure\n(FINAL :called)\n```")
                               :context {:auto-compact? false
                                         :window-tokens 100
                                         :hard-ratio 0.5
                                         :output-reserve-tokens 0
                                         :chars-per-token 1}})
        s (session/start-session! cfg {:id "main" :store-root root})
        result (session/run-turn! s "too large for the deliberately tiny context window")]
    (is (= :error (:status result)))
    (is (= :fractal/context-limit (get-in result [:error :error/type])))
    (is (false? @called?)
        "the provider is not called after hard context preflight fails")
    (is (quick-ok? root))))

(deftest context-soft-threshold-auto-compacts-before-next-user-turn
  (let [root (tmp-dir "context-auto-compact")
        compactions (atom 0)
        responder (fn [request]
                    (let [text (request-text request)
                          full-text (str/join "\n" (map :message/content (:request/messages request)))]
                      (cond
                        (str/includes? text "Create a compact continuation user message")
                        (do
                          (swap! compactions inc)
                          "Auto compact frame: saved remains 5 and the next turn should use the preserved var.")

                        (str/includes? text "AUTO_SEED")
                        "```clojure\n(def saved 5)\n(FINAL {:saved saved})\n```"

                        (str/includes? text "AUTO_USE")
                        (do
                          (is (str/includes? full-text "Auto compact frame:"))
                          (is (not (str/includes? full-text "AUTO_SEED")))
                          "```clojure\n(FINAL {:saved saved :auto-compacted true})\n```")

                        :else
                        "```clojure\n(FINAL :unknown)\n```")))
        cfg (base-config root {:scripted/response-fn responder
                               :context {:window-tokens 100000
                                         :compact-at-ratio 0.01
                                         :hard-ratio 0.95
                                         :output-reserve-tokens 0
                                         :chars-per-token 1}})
        s (session/start-session! cfg {:id "main" :store-root root})
        seed-result (session/run-turn! s "AUTO_SEED")
        use-result (session/run-turn! s "AUTO_USE")
        current-view (projection/view (:locator use-result))
        compact-heads (filterv #(= :context-compaction (:head/kind %))
                               (:heads current-view))]
    (is (= {:saved 5} (:final-value seed-result)))
    (is (= {:saved 5 :auto-compacted true} (:final-value use-result)))
    (is (= 1 @compactions))
    (is (= 1 (count compact-heads)))
    (is (quick-ok? root))))

(deftest request-cache-pins-explicit-ttl
  (testing "request-cache defaults to an explicit 1h ttl, not the implicit server default"
    (is (= "1h" (:ttl (cache/request-cache "sid" :agent))))
    (is (= "1h" (:ttl (cache/request-cache "sid" :leaf nil)))
        "nil ttl falls back to the explicit default rather than emitting no ttl")
    (is (= "5m" (:ttl (cache/request-cache "sid" :agent "5m"))))
    (is (true? (:enabled? (cache/request-cache "sid" :agent)))))
  (testing "unsupported ttl values fail loudly instead of silently degrading to the server default"
    (is (thrown? clojure.lang.ExceptionInfo (cache/request-cache "sid" :agent "30m")))
    (is (= :fractal/invalid-cache-ttl
           (try (cache/normalize-ttl "nope")
                (catch clojure.lang.ExceptionInfo e (:error/type (ex-data e))))))))

(deftest config-cache-ttl-default-and-override
  (testing "config defaults cache-ttl to 1h and accepts a valid override"
    (is (= "1h" (:cache-ttl (process/config {}))))
    (is (= "5m" (:cache-ttl (process/config {:cache-ttl "5m"})))))
  (testing "config rejects an unsupported cache-ttl at construction time"
    (is (thrown? clojure.lang.ExceptionInfo (process/config {:cache-ttl "12h"})))))

(deftest cache-ttl-threads_into_root_and_leaf_requests
  (let [root (tmp-dir "cache-ttl")
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "DRIVE")
                        "```clojure\n(def v (lm {:id 1} \"Return id as EDN.\" :edn))\n(FINAL {:leaf v})\n```"
                        (some? (leaf-input request))
                        (pr-str (:id (leaf-input request)))
                        :else "```clojure\n(FINAL :unknown)\n```")))
        cfg (assoc (scripted-fn-cfg root responder) :cache-ttl "1h")
        s (session/start-session! cfg {:id "main" :alias "main" :store-root root})
        result (session/run-turn! s "DRIVE")
        view (projection/view (:locator result))
        calls (:calls view)
        root-call (first (filter #(= :root (:call/type %)) calls))
        leaf-call (first (filter #(= :leaf (:call/type %)) calls))]
    (is (= {:leaf 1} (:final-value result)))
    (is (= "1h" (get-in root-call [:call/cache-request :ttl]))
        "root agent calls carry the configured 1h cache ttl")
    (is (= "1h" (get-in leaf-call [:call/cache-request :ttl]))
        "leaf calls carry the configured cache ttl too")
    (is (= "1h" (get-in (read-call-request root leaf-call) [:request/cache :ttl]))
        "the rendered leaf request the provider receives carries the ttl")
    (is (quick-ok? root))))

(deftest final-keeps-session-live-resume-advances-and-fork-does-not_mutate_source
  (let [root (tmp-dir "lifecycle")
        cfg (scripted-cfg root ["```clojure\n(def saved 9)\n(FINAL {:saved saved})\n```"
                                "```clojure\n(FINAL {:again saved})\n```"])
        s (session/start-session! cfg {:id "main" :alias "main" :store-root root})
        first-result (session/run-turn! s "save")
        first-head (:ref/current-head (session-db/read-ref root "main"))
        second-result (session/run-turn! s "use saved")
        second-head (:ref/current-head (session-db/read-ref root "main"))]
    (is (= {:saved 9} (:final-value first-result)))
    (is (= :running (get-in @(:state s) [:session :session/status]))
        "FINAL ends a turn, not the session")
    (is (= {:again 9} (:final-value second-result)))
    (is (not= first-head second-head))
    (let [resumed (session/resume-session!
                   (scripted-cfg root ["```clojure\n(FINAL {:resumed saved})\n```"])
                   "main")
          resume-result (session/run-turn! resumed "resume")
          resume-head (:ref/current-head (session-db/read-ref root "main"))]
      (is (= {:resumed 9} (:final-value resume-result)))
      (is (= "main" (:session-id resume-result)))
      (is (= "main" (:cache-id resume-result)))
      (is (not= second-head resume-head)))
    (let [source-ref-before-fork (session-db/read-ref root "main")
          forked (session/fork-session!
                  (scripted-cfg root ["```clojure\n(FINAL {:forked saved})\n```"])
                  "main"
                  nil
                  {:id "fork" :alias "fork"})
          fork-result (session/run-turn! forked "fork")]
      (is (= {:forked 9} (:final-value fork-result)))
      (is (= "fork" (:session-id fork-result)))
      (is (= "fork" (:cache-id fork-result)))
      (is (= source-ref-before-fork (session-db/read-ref root "main"))))))

(deftest usage-report-separates-turn-from-cumulative-tree
  (let [root (tmp-dir "usage-report")
        response (fn [text in out cost]
                   {:response/parts [{:part/type :text :text text}]
                    :response/usage {:usage/input-tokens in
                                     :usage/output-tokens out
                                     :usage/total-tokens (+ in out)}
                    :response/cost {:cost/usd cost}
                    :response/cache {:cache/status :unknown}})
        s (session/start-session!
           (scripted-cfg root [(response "```clojure\n(FINAL {:turn 1})\n```"
                                         10 1 0.10M)
                               (response "```clojure\n(def child (rlm \"child spends more\"))\n(FINAL {:turn 2 :child child})\n```"
                                         20 2 0.20M)
                               (response "```clojure\n(FINAL {:child true})\n```"
                                         70 7 0.70M)])
           {:id "usage" :store-root root})
        first-result (session/run-turn! s "first")
        second-result (session/run-turn! s "second")
        locator (:locator second-result)
        view (projection/view locator)
        report (artifacts/derive-usage-report locator (:calls view)
                                              {:turn-id (:turn-id second-result)})
        rendered (render/usage-report-str report)]
    (session/stop-session! s)
    (is (= {:turn 1} (:final-value first-result)))
    (is (rlm-envelope? (get-in second-result [:final-value :child])))
    (is (= {:child true} (rlm-value (get-in second-result [:final-value :child]))))
    (is (= 2 (:turn/id report)))
    (is (= 2 (get-in report [:usage/turn :usage/total-tree :call/count]))
        "the selected turn includes the root provider call and its child provider call")
    (is (= 3 (get-in report [:usage/turn :usage/total-tree :call/total-tree-count]))
        "structural child invocation rows are tracked separately from LLM calls")
    (is (= 3 (get-in report [:usage/cumulative :usage/total-tree :call/count])))
    (is (= 4 (get-in report [:usage/cumulative :usage/total-tree :call/total-tree-count])))
    (is (= 0.90M (get-in report [:usage/turn :cost/total-tree :cost/usd :known])))
    (is (= 1.00M (get-in report [:usage/cumulative :cost/total-tree :cost/usd :known])))
    (is (str/includes? rendered "this turn"))
    (is (str/includes? rendered "cumulative"))
    (is (str/includes? rendered "this turn"))
    (is (str/includes? rendered "2 LLM calls · 3 rows"))
    (is (str/includes? rendered "cumulative"))
    (is (str/includes? rendered "3 LLM calls · 4 rows"))
    (is (str/includes? rendered "children   1 sessions · 1 LLM calls · cost $0.7000"))))

(deftest resume-from-older-head-branches-from-that-head-without_later_state
  (let [root (tmp-dir "older-resume")
        cfg (scripted-cfg root ["```clojure\n(def x 1)\n(FINAL {:x x})\n```"
                                "```clojure\n(def y 2)\n(FINAL {:x x :y y})\n```"])
        s (session/start-session! cfg {:id "main" :alias "main" :store-root root})
        first-result (session/run-turn! s "define x")
        first-head (:head-id first-result)
        second-result (session/run-turn! s "define y")
        second-head (:head-id second-result)
        resumed (session/resume-session!
                 (scripted-cfg root ["```clojure\n(FINAL {:x x :y-bound? (contains? (ns-publics *ns*) 'y)})\n```"])
                 "main"
                 {:head first-head})
        branch-result (session/run-turn! resumed "resume from x only")
        branch-head (:head-id branch-result)
        current-view (projection/view (:locator branch-result))
        history-view (projection/history-view (:locator branch-result))
        current-heads (set (map :head/id (:heads current-view)))
        history-heads (set (map :head/id (:heads history-view)))
        current-turn-ids (mapv :turn/id (:turns current-view))]
    (is (= {:x 1 :y-bound? false} (:final-value branch-result)))
    (is (= current-turn-ids (distinct current-turn-ids))
        "branch-visible turn ids must not collide")
    (is (not (contains? (set current-turn-ids) (:turn-id second-result)))
        "abandoned later turn is historical, not branch-visible")
    (is (= first-head (:head/basis (session-db/read-head root branch-head))))
    (is (not= second-head (:head/basis (session-db/read-head root branch-head))))
    (is (= branch-head (:ref/current-head (session-db/read-ref root "main"))))
    (is (contains? history-heads second-head)
        "later historical head remains queryable")
    (is (not (contains? current-heads second-head))
        "current/head view excludes the abandoned branch")
    (is (quick-ok? root))))

(deftest fork-from-older-head-restores_exact_state_without_mutating_source
  (let [root (tmp-dir "older-fork")
        source (session/start-session!
                (scripted-cfg root ["```clojure\n(def x 1)\n(FINAL {:x x})\n```"
                                    "```clojure\n(def y 2)\n(FINAL {:x x :y y})\n```"])
                {:id "source" :alias "source" :store-root root})
        first-result (session/run-turn! source "define x")
        first-head (:head-id first-result)
        second-result (session/run-turn! source "define y")
        second-head (:head-id second-result)
        source-ref-before (session-db/read-ref root "source")
        source-fp-before (snapshot/session-fingerprint (:locator second-result))
        forked (session/fork-session!
                (scripted-cfg root ["```clojure\n(FINAL {:x x :y-bound? (contains? (ns-publics *ns*) 'y)})\n```"])
                "source"
                root
                {:id "fork" :alias "fork" :head first-head})
        fork-result (session/run-turn! forked "fork from x only")
        fork-view (projection/view (:locator fork-result))
        fork-text (pr-str (select-keys fork-view [:messages :evals :calls]))
        fork-head (session-db/read-head root (:head-id fork-result))
        derivations (session-db/list-derivation-records root)]
    (is (= {:x 1 :y-bound? false} (:final-value fork-result)))
    (is (= "fork" (:session-id fork-result)))
    (is (= "fork" (:cache-id fork-result)))
    (is (nil? (:head/basis fork-head))
        "forked sessions have no same-session basis until they create their own heads")
    (is (some #(and (= :fork (:derivation/type %))
                   (= "source" (:derivation/source-session %))
                   (= first-head (:derivation/source-head %))
                   (= "fork" (:derivation/target-session %)))
              derivations)
        "fork provenance is a derivation edge, not head basis")
    (is (= source-ref-before (session-db/read-ref root "source")))
    (is (= source-fp-before (snapshot/session-fingerprint (:locator second-result))))
    (is (str/includes? (pr-str (projection/history-view (:locator second-result))) second-head))
    (is (not (str/includes? fork-text "define y")))
    (is (not (str/includes? fork-text "def y")))
    (is (quick-ok? root))))

(deftest fork-from-stable-head-handle-uses_that_heads_snapshot
  (let [root (tmp-dir "stable-handle-fork")
        source (session/start-session!
                (scripted-cfg root ["```clojure\n(def x 1)\n(FINAL {:x x})\n```"
                                    "```clojure\n(def y 2)\n(FINAL {:x x :y y})\n```"])
                {:id "source" :alias "source" :store-root root})
        first-result (session/run-turn! source "define x")
        first-head (:head-id first-result)
        _second-result (session/run-turn! source "define y")
        source-handle (session-model/invocation-handle "source" first-head (:cache-id first-result) root)
        forked (session/fork-session!
                (scripted-cfg root ["```clojure\n(FINAL {:x x :y-bound? (contains? (ns-publics *ns*) 'y)})\n```"])
                source-handle
                root
                {:id "fork" :alias "fork"})
        fork-result (session/run-turn! forked "fork from stable handle")
        fork-session (session-db/read-session root "fork")]
    (is (= {:x 1 :y-bound? false} (:final-value fork-result)))
    (is (= first-head (get-in fork-session [:session/restored-from :source/head-id])))
    (is (= 1 (get-in fork-session [:session/restored-from :source/snapshot-id])))
    (is (quick-ok? root))))

(deftest attach-from-older-head-restores_exact_child_state_and_records_edges
  (let [root (tmp-dir "older-attach")
        source (session/start-session!
                (scripted-cfg root ["```clojure\n(def x 1)\n(FINAL {:x x})\n```"
                                    "```clojure\n(def y 2)\n(FINAL {:x x :y y})\n```"])
                {:id "source" :alias "source" :store-root root})
        first-result (session/run-turn! source "define x")
        first-head (:head-id first-result)
        second-result (session/run-turn! source "define y")
        source-ref-before (session-db/read-ref root "source")
        source-fp-before (snapshot/session-fingerprint (:locator second-result))
        source-handle (session-model/invocation-handle "source" first-head (:cache-id first-result) root)
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "attach old source")
                        (str "```clojure\n(FINAL (attach-rlm " (pr-str source-handle) " \"attached child sees x only\"))\n```")
                        (str/includes? text "attached child sees x only")
                        "```clojure\n(FINAL {:x x :y-bound? (contains? (ns-publics *ns*) 'y)})\n```"
                        :else "```clojure\n(FINAL :unknown)\n```")))
        caller (session/start-session! (scripted-fn-cfg root responder)
                                       {:id "caller" :alias "caller" :store-root root})
        caller-head-before (:ref/current-head (session-db/read-ref root "caller"))
        result (session/run-turn! caller "attach old source")
        inv (first (filter #(= :attach (:invocation/type %))
                           (session-db/list-invocation-records root)))
        child-view (projection/view (session-db/locator root (:callee/session inv)))
        child-head (session-db/read-head root (:callee/head-after inv))
        derivations (session-db/list-derivation-records root)
        child-text (pr-str (select-keys child-view [:messages :evals :calls]))]
    (is (rlm-envelope? (:final-value result)))
    (is (= {:x 1 :y-bound? false} (rlm-value (:final-value result))))
    (is (= source-ref-before (session-db/read-ref root "source")))
    (is (= source-fp-before (snapshot/session-fingerprint (:locator second-result))))
    (is (= caller-head-before (:caller/head-before inv)))
    (is (= (:head-id result) (:caller/head-after inv)))
    (is (= first-head (:attach/source-head-id inv)))
    (is (nil? (:callee/head-before inv))
        "attached children start as a new session; their source is provenance")
    (is (nil? (:head/basis child-head))
        "attached child first head has no same-session basis")
    (is (some #(and (= :attached-child (:derivation/type %))
                   (= "source" (:derivation/source-session %))
                   (= first-head (:derivation/source-head %))
                   (= (:callee/session inv) (:derivation/target-session %)))
              derivations)
        "attach source is a derivation edge separate from invocation and head basis")
    (is (= (:ref/current-head (session-db/read-ref root (:callee/session inv)))
           (:callee/head-after inv)))
    (is (not (str/includes? child-text "define y")))
    (is (not (str/includes? child-text "def y")))
    (is (quick-ok? root))))

(deftest mixed_parallel_fanout_records_every_call_row
  (let [root (tmp-dir "mixed-fanout")
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "mixed fanout")
                        "```clojure\n(def one (lm {:id :leaf} \"leaf one\" :edn))\n(def many (map-lm [1 2] \"double\" :edn))\n(def child (rlm \"return :kid\"))\n(def kids (map-rlm [\"a\" \"b\"] \"return keyword\"))\n(FINAL {:one one :many many :child child :kids kids})\n```"
                        (str/includes? text "leaf one") ":leaf"
                        (some? (leaf-input request)) (pr-str (* 2 (leaf-input request)))
                        (str/includes? text "return :kid") "```clojure\n(FINAL :kid)\n```"
                        (str/includes? text "Task:\n\"a\"") "```clojure\n(FINAL :a)\n```"
                        (str/includes? text "Task:\n\"b\"") "```clojure\n(FINAL :b)\n```"
                        :else "```clojure\n(FINAL :unknown)\n```")))
        result (run-root-fn! root "root" responder "mixed fanout")
        calls (:calls (projection/view (:locator result)))
        invocations (session-db/list-invocation-records root)]
    (is (= {:one :leaf :many [2 4]}
           (select-keys (:final-value result) [:one :many])))
    (is (rlm-envelope? (get-in result [:final-value :child])))
    (is (= :kid (rlm-value (get-in result [:final-value :child]))))
    (is (= :child (get-in result [:final-value :child :rlm/meta :kind])))
    (is (every? rlm-envelope? (get-in result [:final-value :kids])))
    (is (= [:a :b] (mapv rlm-value (get-in result [:final-value :kids]))))
    (is (= [:child-batch-item :child-batch-item]
           (mapv #(get-in % [:rlm/meta :kind]) (get-in result [:final-value :kids]))))
    (is (= 1 (count (filter #(= :leaf (:call/type %)) calls))))
    (is (= 2 (count (filter #(= :leaf-batch-item (:call/type %)) calls))))
    (is (= 1 (count (filter #(= :child (:call/type %)) calls))))
    (is (= 2 (count (filter #(= :child-batch-item (:call/type %)) calls))))
    (is (= 4 (count (session-db/list-session-records root)))
        "leaf calls do not create sessions; root plus three RLM children exist")
    (is (= #{:rlm :map-rlm} (set (map :invocation/type invocations))))
    (is (= 3 (count invocations)))
    (is (= 3 (count (session-db/outgoing-invocations root "root"))))
    (is (quick-ok? root))))

(deftest map-rlm-preserves_order_and_partial_failure_facts
  (let [root (tmp-dir "partial")
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "partial children")
                        "```clojure\n(FINAL (map-rlm [\"ok\" \"bad\"] \"return keyword or fail\"))\n```"
                        (str/includes? text "Task:\n\"ok\"") "```clojure\n(FINAL :ok)\n```"
                        (str/includes? text "Task:\n\"bad\"") "```clojure\n(def no_final_yet 1)\n```"
                        :else "```clojure\n(def still_no_final 2)\n```")))
        result (process/run-process! (base-config root {:scripted/response-fn responder
                                                        :max-turns 4})
                                     {:id "root"
                                      :store-root root
                                      :kind :root
                                      :task "partial children"})
        final (:final-value result)
        invocations (session-db/list-invocation-records root)]
    (is (rlm-envelope? (first final)))
    (is (= :ok (rlm-value (first final))))
    (is (= true (:fractal/failed (second final))))
    (is (= 2 (count final)) "result order is input order")
    (is (= 2 (count invocations)) "successful and failed child facts are preserved")
    (is (= #{:final :error} (set (map :invocation/status invocations))))
    (is (quick-ok? root))))

(deftest attach_session_refs_continue_source_and_return_reusable_envelopes
  (let [root (tmp-dir "attach")
        source (session/start-session!
                (scripted-cfg root ["```clojure\n(def source_var 41)\n(FINAL {:source source_var})\n```"])
                {:id "source" :alias "source-alias" :store-root root})
        source-result (session/run-turn! source "source")
        source-ref-before (session-db/read-ref root "source")
        source-fp-before (snapshot/session-fingerprint (:locator source-result))
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "attach-id-root")
                        "```clojure\n(FINAL (attach-rlm \"source\" \"return source_var\"))\n```"
                        (str/includes? text "attach-alias-root")
                        "```clojure\n(FINAL (attach-rlm \"source-alias\" \"return incremented source_var\"))\n```"
                        (str/includes? text "incremented") "```clojure\n(FINAL {:x (inc source_var)})\n```"
                        (str/includes? text "source_var") "```clojure\n(FINAL {:x source_var})\n```"
                        :else "```clojure\n(FINAL :unknown)\n```")))
        caller (session/start-session! (scripted-fn-cfg root responder)
                                       {:id "caller" :alias "caller" :store-root root})
        by-id (session/run-turn! caller "attach-id-root")
        by-alias (session/run-turn! caller "attach-alias-root")
        source-ref-after (session-db/read-ref root "source")
        source-fp-after (snapshot/session-fingerprint (:locator source-result))
        invocations (->> (session-db/list-invocation-records root)
                         (filter #(= :attach (:invocation/type %)))
                         (sort-by :invocation/n)
                         vec)
        first-inv (first invocations)
        second-inv (second invocations)
        attached-session-ids (mapv :callee/session invocations)
        derivations (session-db/list-derivation-records root)
        source-view (projection/view (session-db/locator root "source"))
        source-user-messages (->> (:messages source-view)
                                  (filter #(= :user (:message/role %)))
                                  (mapv :message/content))
        source-call-models (mapv :call/model (:calls source-view))]
    (is (= {:source 41} (:final-value source-result)))
    (is (rlm-envelope? (:final-value by-id)))
    (is (rlm-envelope? (:final-value by-alias)))
    (is (= {:x 41} (rlm-value (:final-value by-id))))
    (is (= {:x 42} (rlm-value (:final-value by-alias))))
    (is (= :continued (get-in by-id [:final-value :rlm/meta :kind])))
    (is (= "source" (get-in by-id [:final-value :rlm/session :session/id])))
    (is (= "source" (get-in by-alias [:final-value :rlm/session :session/id])))
    (is (not= source-ref-before source-ref-after)
        "session refs continue the source session instead of creating a derived child")
    (is (not= source-fp-before source-fp-after))
    (is (= (get-in by-alias [:final-value :rlm/head :head/id])
           (:ref/current-head source-ref-after)))
    (is (= 2 (count invocations)))
    (is (every? #(= "source" (:attach/source-session-id %)) invocations))
    (is (= (:head-id source-result) (:attach/source-head-id first-inv)))
    (is (= (:callee/head-after first-inv) (:attach/source-head-id second-inv))
        "the second session-ref attach continues from the first attached turn")
    (is (every? #(get-in % [:attach/source-snapshot-ref :blob/id]) invocations))
    (is (= ["source" "source"] attached-session-ids))
    (is (every? #(= :continued (:edge/type %)) invocations))
    (is (= 2 (count (filter #{"scripted-child"} source-call-models)))
        "continued attach invocations use the child model for the edge")
    (is (some #(str/includes? % "You have been attached to restored RLM state")
              source-user-messages))
    (is (some #(str/includes? % "continues the callee session's current head")
              source-user-messages))
    (is (= prompt/system-prompt
           (:message/content (first (filter #(= :system (:message/role %))
                                            (:messages source-view)))))
        "the callee keeps the same base behavior prompt across entry and attached turns")
    (is (empty? (filter #(= :attached-child (:derivation/type %)) derivations))
        "continuing a session ref is an invocation edge, not a new derivation")
    (is (quick-ok? root))))

(deftest prior_child_session_can_be_continued_from_a_later_root_turn
  (let [root (tmp-dir "child-continue")
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "spawn reusable child")
                        "```clojure\n(def child (rlm \"CHILD_SEED\"))\n(FINAL {:child child})\n```"

                        (str/includes? text "continue reusable child")
                        "```clojure\n(def continued (attach-rlm (:rlm/session child) \"CHILD_CONTINUE\"))\n(FINAL {:continued continued\n        :same-session? (= (get-in child [:rlm/session :session/id])\n                          (get-in continued [:rlm/session :session/id]))})\n```"

                        (str/includes? text "CHILD_SEED")
                        "```clojure\n(def child_state 10)\n(FINAL {:state child_state})\n```"

                        (str/includes? text "CHILD_CONTINUE")
                        "```clojure\n(FINAL {:state child_state :next (inc child_state)})\n```"

                        :else
                        "```clojure\n(FINAL :unknown)\n```")))
        root-session (session/start-session! (scripted-fn-cfg root responder)
                                             {:id "root" :alias "root" :store-root root})
        first-result (session/run-turn! root-session "spawn reusable child")
        child-envelope (get-in first-result [:final-value :child])
        child-session-id (get-in child-envelope [:rlm/session :session/id])
        child-head-before (get-in child-envelope [:rlm/head :head/id])
        child-ref-before (session-db/read-ref root child-session-id)
        second-result (session/run-turn! root-session "continue reusable child")
        continued (get-in second-result [:final-value :continued])
        attach-inv (->> (session-db/list-invocation-records root)
                        (filter #(and (= :attach (:invocation/type %))
                                      (= :continued (:edge/type %))))
                        (sort-by :invocation/n)
                        last)
        child-ref-after (session-db/read-ref root child-session-id)
        tree (projection/tree (:locator second-result))]
    (is (rlm-envelope? child-envelope))
    (is (= {:state 10} (rlm-value child-envelope)))
    (is (rlm-envelope? continued))
    (is (= {:state 10 :next 11} (rlm-value continued)))
    (is (= true (get-in second-result [:final-value :same-session?])))
    (is (= child-session-id (get-in continued [:rlm/session :session/id])))
    (is (= child-head-before (:ref/current-head child-ref-before)))
    (is (not= child-head-before (:ref/current-head child-ref-after)))
    (is (= (:ref/current-head child-ref-after)
           (get-in continued [:rlm/head :head/id])))
    (is (= child-head-before (:callee/head-before attach-inv)))
    (is (= (:ref/current-head child-ref-after) (:callee/head-after attach-inv)))
    (is (= :continued (get-in continued [:rlm/meta :kind])))
    (is (= 2 (count (:children tree)))
        "the root tree shows both the spawned child and the later continued invocation")
    (is (= [child-session-id child-session-id]
           (mapv :session-id (:children tree)))
        "continued session refs point at the same callee session without infinite expansion")
    (is (quick-ok? root))))

(deftest attach_current_session_ref_is_rejected
  (let [root (tmp-dir "self-attach")
        s (session/start-session!
           (scripted-cfg root ["```clojure\n(def x 1)\n(FINAL {:x x})\n```"
                               "```clojure\n(try\n  (attach-rlm \"root\" \"SELF_ATTACH\")\n  (catch clojure.lang.ExceptionInfo e\n    (FINAL {:error (:error/type (ex-data e))})))\n```"])
           {:id "root" :alias "root" :store-root root})
        first-result (session/run-turn! s "seed")
        second-result (session/run-turn! s "self attach")]
    (is (= {:x 1} (:final-value first-result)))
    (is (= {:error :attach/self-current-session} (:final-value second-result)))
    (is (quick-ok? root))))

(deftest checker_catches_missing_blob_bad_alias_missing_head_and_broken_invocation
  (let [root (tmp-dir "checker")
        result (run-root! root "root"
                          ["```clojure\n(FINAL {:ok true})\n```"]
                          "go")
        ref (first (sqlite/all-rows root :blob))]
    (Files/delete (blob-file root ref))
    (is (contains? (issue-types (store-consistency/check-consistency root)) :blob/missing)))
  (let [root (tmp-dir "checker-hash")
        result (run-root! root "root"
                          ["```clojure\n(FINAL {:ok true})\n```"]
                          "go")
        ref (first (sqlite/all-rows root :blob))]
    (Files/write (blob-file root ref)
                 (.getBytes "corrupt" StandardCharsets/UTF_8)
                 (make-array java.nio.file.OpenOption 0))
    (is (contains? (issue-types (store-consistency/check-consistency root {:mode :deep}))
                   :blob/hash-mismatch)))
  (let [root (tmp-dir "broken-db")]
    (store-index/transact! root store-schema/schema [{:session/id "bad-head"
                                 :session/cache-id "bad-head"
                                 :session/current-head -1}
                                {:alias/name "bad-alias"
                                 :alias/session -2}
                                {:invocation/id "bad-inv"
                                 :invocation/type :rlm
                                 :invocation/caller-session [:session/id "bad-head"]
                                 :invocation/callee-session -3}])
    (session-db/register-session! root {:session/id "bad-head"
                                        :session/cache-id "bad-head"
                                        :session/status :running})
    (session-db/write-head! root {:head/id "broken-snapshot-head"
                                  :head/session "bad-head"
                                  :head/kind :turn-final
                                  :head/turn-id 1})
    (let [issues (issue-types (store-consistency/check-consistency root))]
      (is (contains? issues :session-db/current-head-missing))
      (is (contains? issues :session-db/alias-target-missing))
      (is (contains? issues :session-db/invocation-callee-missing))
      (is (contains? issues :session-db/head-snapshot-id-missing))
      (is (contains? issues :session-db/head-snapshot-ref-missing)))))

(deftest file-backed-datahike-store-smoke
  (let [root (tmp-dir "file-backend")
        result (run-root! root "root"
                          ["```clojure\n(FINAL {:file-backed true})\n```"]
                          "go")]
    (is (= {:file-backed true} (:final-value result)))
    (is (Files/isRegularFile (store-io/path root "store.sqlite")
                             (make-array LinkOption 0)))
    (is (not (Files/isDirectory (store-io/path root "datahike")
                                (make-array LinkOption 0)))
        "the Datahike index is not part of the hot write path")
    (is (seq (store-index/q root store-schema/schema '[:find ?sid :where [_ :session/id ?sid]])))
    (is (Files/isDirectory (store-io/path root "datahike")
                           (make-array LinkOption 0)))
    (is (deep-ok? root))))

(deftest datahike_projection_is_rebuildable_not_runtime_truth
  (let [root (tmp-dir "projection-rebuild")
        result (run-root! root "root"
                          ["```clojure\n(def saved 9)\n(FINAL {:saved saved})\n```"]
                          "go")
        locator (:locator result)
        index-dir (store-io/path root "datahike")]
    (is (= {:saved 9} (:final-value result)))
    (is (not (Files/isDirectory index-dir (make-array LinkOption 0))))
    (let [resumed (session/resume-session! (scripted-cfg root ["```clojure\n(FINAL {:restored saved})\n```"])
                                           locator)
          resumed-result (session/run-turn! resumed "use saved")]
      (is (= {:restored 9} (:final-value resumed-result)))
      (is (not (Files/isDirectory index-dir (make-array LinkOption 0)))
          "resume reads SQLite head rows and BlobStore state, not Datahike"))
    (is (seq (store-index/q root store-schema/schema '[:find ?sid :where [_ :session/id ?sid]])))
    (is (Files/isDirectory index-dir (make-array LinkOption 0)))
    (store-index/close-connections!)
    (Files/deleteIfExists (store-io/path root "datahike-id.edn"))
    (doseq [f (reverse (file-seq (.toFile index-dir)))]
      (Files/deleteIfExists (.toPath f)))
    (is (not (Files/isDirectory index-dir (make-array LinkOption 0))))
    (store-index/rebuild! root store-schema/schema)
    (is (= #{["root"]}
           (set (store-index/q root store-schema/schema '[:find ?sid :where [_ :session/id ?sid]]))))
    (is (deep-ok? root))))

(deftest projection_tree_inspect_and_json_ready_reads_use_canonical_store
  (let [root (tmp-dir "read")
        result (run-root! root "root"
                          ["```clojure\n(FINAL {:child (rlm \"WIDGET child\")})\n```"
                           "```clojure\n(FINAL {:answer \"child\"})\n```"]
                          "spawn")
        locator (:locator result)
        tree (projection/tree locator)
        child-address (-> tree :children first :address)]
    (is (= "root" (:address tree)))
    (is (= "root/child-0001" child-address))
    (is (rlm-envelope? (get-in (projection/load-node locator) [:final :child])))
    (is (= {:answer "child"} (rlm-value (get-in (projection/load-node locator) [:final :child]))))
    (is (= {:answer "child"} (:final (projection/load-at locator child-address))))
    (is (= (:locator (first (:children tree)))
           (projection/node-locator locator child-address)))
    (is (pos? (count (projection/event-stream locator))))
    (is (quick-ok? root))))

(deftest nested-recursion-preserves_session_edges_and_tree_reads
  (let [root (tmp-dir "nested")
        responder (fn [request]
                    (let [text (request-text request)]
                      (cond
                        (str/includes? text "nested recursion")
                        "```clojure\n(FINAL {:nested (rlm \"NESTED_LEVEL_1\")})\n```"

                        (str/includes? text "NESTED_LEVEL_1")
                        "```clojure\n(FINAL {:nested (rlm \"NESTED_LEVEL_2\")})\n```"

                        (str/includes? text "NESTED_LEVEL_2")
                        "```clojure\n(FINAL {:leaf :done})\n```"

                        :else
                        "```clojure\n(FINAL :unknown)\n```")))
        result (run-root-fn! root "root" responder "nested recursion")
        locator (:locator result)
        tree (projection/tree locator)
        child (first (:children tree))
        grandchild (first (:children child))]
    (is (rlm-envelope? (get-in result [:final-value :nested])))
    (is (rlm-envelope? (get-in result [:final-value :nested :rlm/value :nested])))
    (is (= {:leaf :done}
           (rlm-value (get-in result [:final-value :nested :rlm/value :nested]))))
    (is (= 3 (count (session-db/list-session-records root))))
    (is (= 2 (count (session-db/list-invocation-records root))))
    (is (= "root/child-0001" (:address child)))
    (is (= "root/child-0001/child-0001" (:address grandchild)))
    (is (= {:leaf :done} (:final (projection/load-at locator (:address grandchild)))))
    (is (quick-ok? root))))
