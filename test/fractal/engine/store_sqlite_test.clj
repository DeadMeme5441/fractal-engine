(ns fractal.engine.store-sqlite-test
  "SqliteStore-specific durability proofs (02 §3/§8/§9) — beyond the shared
   port-contract suite (store_contract_test). Covers persist-before-fold, durable
   recovery by folding the on-disk log, the on-disk content-addressed blob store
   (dedup + GLOBAL cross-session sharing), and the end-to-end RESUME payoff: a
   sqlite session driven to (FINAL …) by the FakeAdapter, closed, reopened by
   folding its durable log, its REPL vars restored, and a NEW turn using a var that
   was def'd before the reopen."
  (:require [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.store :as store]
            [fractal.engine.store.blobstore :as blob]
            [fractal.engine.store.sqlite :as sqlite]))

;; ---------------------------------------------------------------------------
;; Temp-dir plumbing
;; ---------------------------------------------------------------------------

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-sqlite" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(defmacro with-store
  "Bind `s` to a fresh SqliteStore under a fresh temp `dir`; close + delete after."
  [[s dir] & body]
  `(let [~dir (temp-dir!)
         ~s   (sqlite/sqlite-store {:dir ~dir})]
     (try ~@body
          (finally (sqlite/close! ~s) (rm-rf! ~dir)))))

(defn- seed! [s sid]
  (store/create-session! s {:session/id sid :session/status :running})
  (store/append-event! s sid {:event/type :session/started
                              :session {:session/id sid :session/status :running}}))

;; ---------------------------------------------------------------------------
;; ⛔ Persist-before-fold: a failed durable write must NOT advance the view (§8.2)
;; ---------------------------------------------------------------------------

(deftest persist-before-fold
  (with-store [s dir]
    (let [sid "pf"]
      (seed! s sid)
      (let [before  (store/current-view s sid)
            next-id (store/peek-next-id s sid :event)]
        ;; Occupy the next event_id with a blocker row ⇒ the next append's INSERT
        ;; collides on the (session_id, event_id) PRIMARY KEY and throws — BEFORE
        ;; the in-process fold.
        (with-open [ps (.prepareStatement (:conn s)
                         "INSERT INTO events(session_id,event_id,event_type,event_at,event_edn) VALUES (?,?,?,?,?)")]
          (.setString ps 1 sid) (.setLong ps 2 (long next-id))
          (.setString ps 3 ":blocker") (.setString ps 4 "t") (.setString ps 5 "{}")
          (.executeUpdate ps))
        (is (thrown? Exception
              (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}}))
            "the failed durable INSERT propagates")
        (let [after (store/current-view s sid)]
          (testing "a failed persist leaves the view cache untouched"
            (is (= (:counters before) (:counters after)) "counters not advanced")
            (is (= (count (:turns before)) (count (:turns after))) "no turn folded")
            (is (= (:events before) (:events after)) "event log cache unchanged")))))))

(deftest persist-before-fold-batch
  ;; The same invariant for append-events!: the whole batch is ONE transaction —
  ;; a mid-batch failure rolls back and folds NOTHING (no torn/partial write).
  (with-store [s dir]
    (let [sid "pfb"]
      (seed! s sid)
      (let [before  (store/current-view s sid)
            next-id (store/peek-next-id s sid :event)]
        ;; Occupy the SECOND event_id the batch would assign ⇒ the batch INSERT
        ;; collides part-way through, the transaction rolls back, nothing folds.
        (with-open [ps (.prepareStatement (:conn s)
                         "INSERT INTO events(session_id,event_id,event_type,event_at,event_edn) VALUES (?,?,?,?,?)")]
          (.setString ps 1 sid) (.setLong ps 2 (long (inc next-id)))
          (.setString ps 3 ":blocker") (.setString ps 4 "t") (.setString ps 5 "{}")
          (.executeUpdate ps))
        (is (thrown? Exception
              (store/append-events! s sid
                [{:event/type :turn/started :turn {:turn/status :running}}
                 {:event/type :step/started :step {:step/status :running}}
                 {:event/type :eval/added   :eval {:eval/status :ok}}]))
            "the batch transaction fails on the mid-batch PK collision")
        (let [after (store/current-view s sid)]
          (testing "a failed batch persist rolls back atomically — nothing folded"
            (is (= (:counters before) (:counters after)) "counters not advanced")
            (is (= (count (:turns before)) (count (:turns after))) "no turn folded")
            (is (= (count (:steps before)) (count (:steps after))) "no step folded")
            (is (= (:events before) (:events after)) "event log cache unchanged")))
        ;; the store is still usable afterwards (autocommit restored, txn rolled back)
        (let [ev (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})]
          (is (= next-id (:event/id ev)) "a fresh single append still succeeds at the next id"))))))

;; ---------------------------------------------------------------------------
;; Durability: close + reopen reproduces the view by FOLDING the durable log (§9)
;; ---------------------------------------------------------------------------

(deftest durability-reopen-folds-log
  (let [dir (temp-dir!) sid "dur"]
    (try
      (let [s (sqlite/sqlite-store {:dir dir})]
        (seed! s sid)
        (store/append-event! s sid {:event/type :turn/started :turn {:turn/status :running}})
        (let [ref (store/intern-payload! s (vec (range 2000)) {:payload/kind :message})]
          (store/append-event! s sid {:event/type :message/appended
                                      :message {:message/role :user :message/content-or-ref ref}}))
        (store/append-event! s sid {:event/type :step/started :step {:step/status :running}})
        (store/append-event! s sid {:event/type :eval/added
                                    :eval {:eval/status :ok :eval/result-ref 42}})
        (store/append-event! s sid {:event/type :turn/put :turn {:turn/id 1 :turn/status :final}})
        (let [v1 (store/current-view s sid)]
          (sqlite/close! s)                                ; simulate process exit
          (let [s2 (sqlite/sqlite-store {:dir dir})]       ; fresh store — no in-process cache
            (try
              (store/create-session! s2 {:session/id sid}) ; reopen ⇒ fold the durable log
              (let [v2 (store/current-view s2 sid)]
                (is (= v1 v2)
                    "reopen reproduces the FULL view (events + counters) by folding the log")
                (is (empty? (store/verify-no-dangling-refs s2 sid))
                    "the on-disk blob referenced before the reopen still resolves"))
              (finally (sqlite/close! s2))))))
      (finally (rm-rf! dir)))))

;; ---------------------------------------------------------------------------
;; The on-disk content-addressed blob store (02 §3): dedup + GLOBAL sharing
;; ---------------------------------------------------------------------------

(deftest on-disk-blob-dedup
  (with-store [s dir]
    (let [v (vec (range 1000))
          r1 (store/intern-payload! s v {:payload/kind :final})
          r2 (store/intern-payload! s v {:payload/kind :final})]
      (is (= r1 r2))
      (is (= 1 (blob/count-blobs (:blobs s)))
          "identical values dedup to ONE on-disk node"))))

(deftest global-blob-sharing-across-sessions
  (with-store [s dir]
    (store/create-session! s {:session/id "a" :session/status :running})
    (store/create-session! s {:session/id "b" :session/status :running})
    (let [v  (vec (range 1500))
          ra (store/intern-payload! s v {:payload/kind :final})   ; "interned from session a"
          rb (store/intern-payload! s v {:payload/kind :final})]  ; "interned from session b"
      (is (= ra rb) "blobs are GLOBAL (no sid) — same value ⇒ same ref across sessions")
      (is (= 1 (blob/count-blobs (:blobs s))) "one shared on-disk Merkle node")
      (is (= v (store/read-payload* s ra)))
      (is (= v (store/read-payload* s rb))))))

;; ---------------------------------------------------------------------------
;; End-to-end RESUME (06 §2 ^:alpha): reopen → restore vars → continue a turn
;; ---------------------------------------------------------------------------

(deftest resume-restores-vars-and-continues
  (let [dir (temp-dir!) sid "resume-e2e"
        responder (fe/responder
                    [["define" "```clojure (def remembered 7) (FINAL :defined)```"]
                     ["use"     "```clojure (FINAL (* remembered 6))```"]])
        cfg (fe/make-config {:adapter :fake :fake/respond responder
                             :model "fake-model" :capability :default
                             :store :sqlite :store/dir dir})]
    (try
      ;; turn 1 — define a REPL var; commit-turn! snapshots it into :vars-ref
      (let [h1 (fe/start-session! cfg {:id sid})
            r1 (fe/run-turn! h1 "please define the value")]
        (is (= :final (:status r1)))
        (is (= :defined (:turn/final-value r1)))
        (fe/close-session! h1))                            ; close the JDBC connection
      ;; resume — a FRESH store on the same dir: fold the durable log + restore vars
      (let [h2 (fe/resume-session! cfg sid)]
        (is (= sid (:session-id h2)))
        (is (= 1 (count (:turns (fe/view h2))))
            "the durable turn is present after the reopen-fold")
        (is (= :running (get-in (fe/view h2) [:session :session/status])))
        (let [r2 (fe/run-turn! h2 "now use the remembered value")]
          (is (= :final (:status r2)))
          (is (= 42 (:turn/final-value r2))
              "a var def'd BEFORE the durable reopen survived resume and drove a new turn"))
        (is (= 2 (count (:turns (fe/view h2)))) "the post-resume turn is also durable")
        (fe/close-session! h2))
      (finally (rm-rf! dir)))))
