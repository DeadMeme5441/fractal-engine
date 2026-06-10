(ns fractal.engine.cross-process-test
  "Cross-process read contract: a second SqliteStore opened on the SAME dir must
   observe events committed by the first store AFTER the second one opened —
   `events-since` reads the durable log (not the open-time cache) and
   `read-state` re-folds it. CLI `wait` builds on this: it polls the durable log
   and settles when a turn driven by ANOTHER store instance finishes."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest testing is]]
            [fractal.engine.api :as fe]
            [fractal.engine.cli :as cli]
            [fractal.engine.store :as store]
            [fractal.engine.store.sqlite :as sqlite]))

(defn- temp-dir! ^java.io.File []
  (.toFile (java.nio.file.Files/createTempDirectory
             "fe-xproc" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f) (run! rm-rf! (.listFiles f)))
  (.delete f))

(deftest second-store-sees-events-committed-after-it-opened
  (let [dir (temp-dir!) sid "xproc"]
    (try
      (let [writer (sqlite/sqlite-store {:dir dir})
            _      (store/create-session! writer {:session/id sid})
            _      (store/append-event! writer sid
                     {:event/type :session/started :session {:session/id sid}})
            ;; "second process": opens AFTER event 1, folds a 1-event log.
            reader (sqlite/sqlite-store {:dir dir})
            _      (store/create-session! reader {:session/id sid})]
        ;; the writer commits MORE events after the reader opened
        (store/append-event! writer sid
          {:event/type :message/appended
           :message {:message/role :user :message/content-or-ref "hello"}})
        (store/append-event! writer sid {:event/type :turn/started :turn {:turn/status :running}})
        (testing "events-since serves the durable log, not the reader's open-time cache"
          (is (= [:session/started :message/appended :turn/started]
                 (mapv :event/type (store/events-since reader sid 0))))
          (is (= [:message/appended :turn/started]
                 (mapv :event/type (store/events-since reader sid 1)))))
        (testing "read-state re-folds the durable log to the writer's current state"
          (let [v (store/read-state reader sid)]
            (is (= 3 (count (:events v))))
            (is (= :running (:turn/status (last (:turns v)))))))
        (testing "current-view remains the reader's in-process open-time cache"
          (is (= 1 (count (:events (store/current-view reader sid))))))
        (sqlite/close! writer)
        (sqlite/close! reader))
      (finally (rm-rf! dir)))))

;; ---------------------------------------------------------------------------
;; CLI wait
;; ---------------------------------------------------------------------------

(defn- write-config! [^java.io.File dir store-dir final-or-respond]
  (let [f (java.io.File. dir "fractal.edn")]
    (spit f (pr-str {:adapter :fake
                     :model "fake-model"
                     :harness :clojure
                     :capability :default
                     :store :sqlite
                     :store/dir store-dir
                     :fake/respond final-or-respond}))
    (.getPath f)))

(defn- run-cli [& args]
  (let [out (java.io.StringWriter.)
        code (binding [*out* out] (cli/run-command (vec args)))]
    {:code code :out (edn/read-string (str out))}))

(deftest cli-wait-returns-immediately-when-idle
  (let [dir (temp-dir!)
        store-dir (.getPath (java.io.File. dir "store"))
        cfg-path (write-config! dir store-dir [[:default "```clojure\n(FINAL :done)\n```"]])]
    (try
      (is (= 0 (:code (apply run-cli ["run" "--config" cfg-path "--session" "w1"
                                      "--message" "go" "--edn"]))))
      (let [{:keys [code out]} (run-cli "wait" "--config" cfg-path "--session" "w1"
                                        "--wait-timeout-ms" "2000" "--edn")]
        (is (= 0 code))
        (is (true? (:ok out)))
        (is (false? (:in-flight out)))
        (is (= :final (:turn/status out))))
      (finally (rm-rf! dir)))))

(deftest cli-wait-blocks-on-an-in-flight-turn-from-another-process
  (let [dir (temp-dir!)
        store-dir (.getPath (java.io.File. dir "store"))
        sid "w2"
        ;; the SDK "writer process": a slow responder holds the turn open ~1.2s
        slow (fn [_req] (Thread/sleep 1200) "```clojure\n(FINAL :slow-done)\n```")
        sdk-cfg (fe/make-config {:adapter :fake :fake/respond slow :model "fake-model"
                                 :harness :clojure :capability :default
                                 :store :sqlite :store/dir store-dir})
        ;; the CLI "reader process": its own store instance over the same dir
        cfg-path (write-config! dir store-dir [[:default "```clojure\n(FINAL :unused)\n```"]])]
    (try
      (let [h (fe/start-session! sdk-cfg {:id sid})
            fut (future (fe/run-turn! h "slow work"))]
        ;; ensure the turn has durably opened before waiting on it
        (loop [n 0]
          (when (and (< n 100) (empty? (:turns (fe/view h))))
            (Thread/sleep 20) (recur (inc n))))
        (testing "a short wait times out against the in-flight turn (exit 1)"
          (let [{:keys [code out]} (run-cli "wait" "--config" cfg-path "--session" sid
                                            "--wait-timeout-ms" "200" "--poll-ms" "50" "--edn")]
            (is (= 1 code))
            (is (false? (:ok out)))
            (is (true? (:timed-out out)))
            (is (true? (:in-flight out)))))
        (testing "a long wait settles when the other process's turn finishes"
          (let [{:keys [code out]} (run-cli "wait" "--config" cfg-path "--session" sid
                                            "--wait-timeout-ms" "10000" "--poll-ms" "50" "--edn")]
            (is (= 0 code))
            (is (true? (:ok out)))
            (is (false? (:in-flight out)))
            (is (= :final (:turn/status out)))))
        (is (= :final (:status @fut)))
        (fe/close-session! h))
      (finally (rm-rf! dir)))))
