(ns fractal.engine.payload-io
  "L1.5 · store-coupled payload IO (02 §3). Wraps the store's
   `intern-payload!`/`read-payload*` with the inline/hydrate policy. Built after
   `store`; the PURE `payload` ns stays store-free so `store` itself depends
   only on it (no cycle)."
  (:require [fractal.engine.payload :as payload]
            [fractal.engine.store :as store]))

(def ^:const inline-limit
  "A value whose canonical bytes are ≤ this is embedded directly in its event;
   larger values are content-addressed into the blob store (02 §3)."
  512)

(defn- restorable-leaf?
  "A leaf that survives a canonical EDN round-trip."
  [x]
  (or (nil? x) (number? x) (string? x) (keyword? x) (symbol? x)
      (boolean? x) (char? x) (inst? x) (uuid? x)))

(def ^:const max-seq-elems
  "Cap on realized elements per (possibly lazy/infinite) seq during coercion, so
   interning a value containing an unbounded seq is BOUNDED, not a hang."
  100000)

(def ^:const max-depth 500)

(defn- edn-safe*
  [x depth]
  (cond
    (> depth max-depth)  {:fractal/truncated :depth}
    (map? x)             (persistent!
                           (reduce-kv (fn [m k v]
                                        (assoc! m (edn-safe* k (inc depth)) (edn-safe* v (inc depth))))
                                      (transient {}) x))
    (vector? x)          (mapv #(edn-safe* % (inc depth)) x)
    (set? x)             (into #{} (map #(edn-safe* % (inc depth))) x)
    (seq? x)             (let [head  (take max-seq-elems x)
                               more? (seq (drop max-seq-elems x))]   ; bounded: realizes cap+1
                           (cond-> (mapv #(edn-safe* % (inc depth)) head)
                             more? (conj {:fractal/truncated :length})))
    (coll? x)            (mapv #(edn-safe* % (inc depth)) (seq x))
    (restorable-leaf? x) x
    :else (let [s (pr-str x)]
            {:fractal/unrestorable (subs s 0 (min 200 (count s)))})))

(defn edn-safe
  "Recursively replace members that would not survive a canonical round-trip
   (fns, atoms/derefables, host objects) with an opaque marker, and BOUND any
   (possibly infinite) lazy seq — so every stored or inlined value is EDN-safe,
   canonical, hashable, and finite. Pure finite data is returned unchanged
   (02 §1, the :eval-result/:final snapshot)."
  [v]
  (edn-safe* v 0))

(defn- inline?
  "Small enough to embed directly in an event (no content-addressing)."
  [value]
  (cond
    (nil? value)    true
    (string? value) (<= (count value) inline-limit)
    :else           (<= (alength (payload/canonical-bytes value)) inline-limit)))

(defn maybe-intern
  "Return `value` inline (unchanged) when small — scalar / string ≤512 /
   number / keyword / boolean / nil / small collection (canonical bytes ≤512);
   otherwise content-address it and return a tagged payload-ref (kind =
   `opts :payload/kind`). Unrestorable members are EDN-coerced first so the
   stored value is always canonical (02 §3)."
  [store value opts]
  (let [safe (edn-safe value)]
    (if (inline? safe)
      safe
      (store/intern-payload! store safe opts))))

(defn read-payload
  "Dereference a tagged ref via the store; pass any non-ref value through
   unchanged. The ONLY way the loop reads a `…-or-ref`/`…-ref` field — it never
   compares or branches on a raw ref (02 §3)."
  [store ref-or-value]
  (if (payload/payload-ref? ref-or-value)
    (store/read-payload* store ref-or-value)
    ref-or-value))

(defn hydrate-message
  "Rename `:message/content-or-ref` → `:message/content`, dereferencing if it is
   a ref. The hydrated shape both request-assembly and the compaction formatter
   consume (05 §4, 07 §4, GD11)."
  [store msg]
  (-> msg
      (assoc :message/content (read-payload store (:message/content-or-ref msg)))
      (dissoc :message/content-or-ref)))
