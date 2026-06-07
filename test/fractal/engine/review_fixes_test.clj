(ns fractal.engine.review-fixes-test
  "Regression tests for the 7 findings from the adversarial Phase-1 review.
   Each pins a confirmed bug so it stays fixed."
  (:require [clojure.test :refer [deftest testing is]]
            [sci.core :as sci]
            [fractal.engine.kernel :as k]
            [fractal.engine.observe :as o]
            [fractal.engine.payload :as payload]
            [fractal.engine.payload-io :as pio]
            [fractal.engine.capability :as cap]
            [fractal.engine.store :as store]
            [fractal.engine.store.memory :as mem]
            [fractal.engine.api :as fe]))

(defn- bounded
  "Run thunk on a future; fail with ::timeout instead of hanging the suite."
  [ms thunk]
  (let [f (future (thunk))
        v (deref f ms ::timeout)]
    (when (= v ::timeout) (future-cancel f))
    v))

(defn- kernel-handle []
  (let [s (mem/memory-store) sid "rf"
        h (store/create-session! s {:session/id sid :session/status :running})]
    (store/append-event! s sid {:event/type :session/started
                                :session {:session/id sid :session/status :running}})
    (reset! (:sci-ctx h) (k/new-ctx sid (cap/default-profile) (k/engine-fn-impls)))
    h))

(defn- run-cap
  "Eval code in a fresh ctx for `profile`; return the denied cap or :ok/:value."
  [profile code]
  (let [ctx (sci/init (cap/sci-opts profile (k/engine-fn-impls)))]
    (try {:ok (sci/eval-string* ctx code)}
         (catch Throwable e
           {:err (some #(:error/type (ex-data %))
                       (take-while some? (iterate #(some-> ^Throwable % .getCause) e)))}))))

;; --- Finding 1: snapshot survives a model shadow of a core symbol ----------

(deftest snapshot-survives-core-symbol-shadow
  (let [h (kernel-handle)]
    (k/eval-batch h 1 ["(def total 5)" "(def items [1 2 3])"
                       "(def name (fn [m] (:name m)))"   ; shadows clojure.core/name
                       "(def into (fn [a b] :x))"])       ; shadows clojure.core/into
    (let [snap (k/snapshot-vars @(:sci-ctx h) (:session-id h))]
      (is (= {:status :ok :value 5} (get-in snap [:vars "total"])) "real var survives the shadow")
      (is (= {:status :ok :value [1 2 3]} (get-in snap [:vars "items"])))
      (is (= #{"total" "items" "name" "into"} (set (keys (:vars snap))))
          "no silent var loss / collapse to a nil key"))))

;; --- Finding 2: bounded rendering / interning of unbounded lazy seqs -------

(deftest infinite-seqs-do-not-hang
  (testing "value-stub on an infinite seq is bounded (kind label varies by seq type)"
    (let [r (bounded 5000 #(o/value-stub (range)))]
      (is (string? r))
      (is (clojure.string/includes? r "≥100000 items"))))
  (testing "value-display on an infinite seq stubs (try-fit aborts, stub bounded)"
    (let [r (bounded 5000 #(o/value-display (map inc (range)) o/ok-fit))]
      (is (clojure.string/includes? r "≥100000 items"))))
  (testing "maybe-intern of a value holding an infinite seq is bounded"
    (let [s (mem/memory-store)]
      (is (payload/payload-ref?
            (bounded 8000 #(pio/maybe-intern s {:k (iterate inc 0)} {:payload/kind :eval-result}))))))
  (testing "snapshot marks an infinite-seq var unrestorable instead of hanging"
    (let [h (kernel-handle)]
      (k/eval-batch h 1 ["(def inf (range))"])
      (is (= :unrestorable
             (bounded 8000 #(:status (get-in (k/snapshot-vars @(:sci-ctx h) (:session-id h))
                                             [:vars "inf"]))))))))

;; --- Finding 3: file: URL prefix cannot bypass the path boundary -----------

(deftest file-url-prefix-is-path-gated
  (testing ":default denies an out-of-workdir read whether plain or file:-prefixed"
    (is (= :capability/denied (:err (run-cap (cap/default-profile) "(slurp \"/etc/hostname\")"))))
    (is (= :capability/denied (:err (run-cap (cap/default-profile) "(slurp \"file:/etc/hostname\")"))))
    (is (= :capability/denied (:err (run-cap (cap/default-profile) "(slurp \"file:///etc/hostname\")")))))
  (testing "an in-workdir read still works through both forms"
    (is (string? (:ok (run-cap (cap/default-profile) "(slurp \"deps.edn\")"))))))

;; --- Finding 4: scheme matching is case-insensitive -----------------------

(deftest network-scheme-matching-is-case-insensitive
  (testing ":default (network :deny) denies uppercase schemes too"
    (is (= :capability/denied (:err (run-cap (cap/default-profile) "(slurp \"HTTP://example.com/x\")"))))
    (is (= :capability/denied (:err (run-cap (cap/default-profile) "(slurp \"JAR:http://example.com/x.jar!/y\")"))))
    (is (= :capability/denied (:err (run-cap (cap/default-profile) "(slurp \"FILE://host/x\")"))))))

;; --- Finding 5: io/copy gates File args, not just strings ------------------

(deftest io-copy-gates-file-args
  (testing ":default denies io/copy writing a File outside the workdir"
    (is (= :capability/denied
           (:err (run-cap (cap/default-profile)
                          "(require '[clojure.java.io :as io]) (io/copy (io/file \"deps.edn\") (io/file \"/tmp/exfil\"))")))))
  (testing ":default denies io/copy reading a File outside the workdir"
    (is (= :capability/denied
           (:err (run-cap (cap/default-profile)
                          "(require '[clojure.java.io :as io]) (io/copy (io/file \"/etc/hostname\") (io/file \"out.txt\"))"))))))

;; --- Finding 6: validate-profile! rejects {:allow :all} -------------------

(deftest validate-rejects-allow-all
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"class-name symbols"
        (cap/validate-profile! (assoc (cap/locked-down) :cap/java-classes {:allow :all}))))
  (testing "a legitimate class-symbol whitelist still validates"
    (is (cap/validate-profile! {:capability/name :custom :cap/java-classes {'java.util.Date java.util.Date}
                                :cap/fs-read :deny :cap/fs-write :deny :cap/shell :deny
                                :cap/network :deny :ns/granted #{} :engine-fns #{}}))))

;; --- Finding 7: stop delivers the terminal :session/stopped to a subscriber -

(deftest stop-delivers-terminal-event-to-subscriber
  (let [s (fe/start-session! (fe/make-config
                               {:adapter :fake :model "fake-model"
                                :fake/respond (fe/responder [[:default "```clojure (FINAL :ok)```"]])}))
        seen (atom [])]
    (fe/run-turn! s "go")
    (fe/subscribe! s (fn [ev] (swap! seen conj (:event/type ev))))
    (fe/stop-session! s)
    (let [deadline (+ (System/currentTimeMillis) 2000)]
      (while (and (not (some #{:session/stopped} @seen))
                  (< (System/currentTimeMillis) deadline))
        (Thread/sleep 5)))
    (is (some #{:session/stopped} @seen)
        "the just-enqueued terminal :session/stopped reached the live subscriber")))
