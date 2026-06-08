(ns fractal.engine.deps-acyclic-test
  "CI guard (01): the fractal.engine.* namespaces form a strict acyclic DAG, and
   the load-bearing layering edges hold (engine-free L0 leaves; store depends
   only on the PURE payload; live has no store dep; the adapter port is
   engine-dep-free; capability never depends on the kernel)."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- engine-clj-files []
  (->> (file-seq (io/file "src/fractal/engine"))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".clj")))))

(defn- ns-form [^java.io.File file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (read {:read-cond :allow :eof nil} r)))

(defn- require-clauses [nf]
  (->> nf (filter #(and (seq? %) (= :require (first %)))) first rest))

(defn- engine-deps [nf]
  (->> (require-clauses nf)
       (map #(if (sequential? %) (first %) %))
       (filter symbol?)
       (map name)
       (filter #(str/starts-with? % "fractal.engine"))
       set))

(defn- graph []
  (into {} (for [f (engine-clj-files)
                 :let [nf (ns-form f)]]
             [(name (second nf)) (engine-deps nf)])))

(defn- find-cycle [g]
  (let [visiting (atom #{}) done (atom #{}) result (atom nil)]
    (letfn [(visit [n path]
              (when (and (not @result) (not (@done n)))
                (if (@visiting n)
                  (reset! result (conj (vec (drop-while #(not= % n) path)) n))
                  (do (swap! visiting conj n)
                      (doseq [m (get g n)] (visit m (conj path n)))
                      (swap! visiting disj n)
                      (swap! done conj n)))))]
      (doseq [n (keys g)] (visit n []))
      @result)))

(deftest dependency-graph-is-acyclic
  (let [g (graph)]
    (is (seq g) "found the engine namespaces")
    (is (nil? (find-cycle g)) (str "namespace cycle detected: " (find-cycle g)))))

(deftest layering-edges-hold
  (let [g (graph)]
    (testing "L0 leaves are engine-free"
      (doseq [n ["fractal.engine.time" "fractal.engine.payload"
                 "fractal.engine.concurrent" "fractal.engine.catalog"]]
        (is (empty? (get g n)) (str n " must have no fractal.engine deps"))))
    (testing "store depends only on the PURE payload (never payload-io)"
      (is (= #{"fractal.engine.payload"} (get g "fractal.engine.store"))))
    (testing "live has no store dependency"
      (is (not (contains? (get g "fractal.engine.live") "fractal.engine.store"))))
    (testing "the adapter PORT is engine-dep-free"
      (is (empty? (get g "fractal.engine.adapter"))))
    (testing "capability never depends on the kernel (it takes impls as data)"
      (is (not (contains? (get g "fractal.engine.capability") "fractal.engine.kernel"))))
    (testing "the deliberate downward edges exist"
      (is (contains? (get g "fractal.engine.kernel") "fractal.engine.store") "kernel→store")
      (is (contains? (get g "fractal.engine.store.memory") "fractal.engine.live") "store.memory→live"))))
