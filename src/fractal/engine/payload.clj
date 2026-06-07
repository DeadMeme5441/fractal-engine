(ns fractal.engine.payload
  "L0 · PURE payload primitives (zero engine deps). The Merkle substrate (02 §3,
   §9): a blob's id is `sha256:<hex>` over `canonical-bytes`, so equal values
   dedup to one content-addressed node across every store impl and across
   processes. Store-coupled IO (maybe-intern / read-payload / hydrate-message)
   lives in `fractal.engine.payload-io` — NOT here — so `store` can depend on
   this pure ns without a cycle."
  (:import [java.security MessageDigest]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Canonical bytes — the deterministic hashing basis
;; ---------------------------------------------------------------------------

(defn- key-order
  "A total order over arbitrary EDN values, used only to sort map keys / set
   members deterministically. Comparing the canonical printed form sidesteps
   `compare`'s throw on mixed types while staying stable across processes."
  [a b]
  (compare (pr-str a) (pr-str b)))

(defn- canonicalize
  "Recursively rewrite v into a shape whose `pr-str` is deterministic: map keys
   sorted, set members sorted, type preserved (a list and a vector stay
   distinct so a restored var keeps its concrete type). Metadata is dropped by
   `*print-meta* false` at print time."
  [v]
  (cond
    (map? v)    (into (sorted-map-by key-order)
                      (map (fn [[k vv]] [(canonicalize k) (canonicalize vv)]))
                      v)
    (set? v)    (into (sorted-set-by key-order) (map canonicalize) v)
    (vector? v) (mapv canonicalize v)
    (seq? v)    (apply list (map canonicalize v))   ; preserve list-ness
    (coll? v)   (mapv canonicalize v)               ; any other coll → vector form
    :else       v))

(defn canonical-bytes
  "Deterministic UTF-8 bytes for v: canonical EDN — sorted map keys, no meta,
   `*print-length*`/`*print-level*` nil, no namespaced-map shorthand. The same
   logical value always hashes the same way (02 §3). ⚠ Volatile fields
   (timestamps, ids) are simply part of whatever value you pass — a *blob* is
   only ever the bare value, never an event, so equal values dedup."
  ^bytes [v]
  (binding [*print-length*         nil
            *print-level*          nil
            *print-namespace-maps* false
            *print-meta*           false
            *print-readably*       true]
    (.getBytes (pr-str (canonicalize v)) StandardCharsets/UTF_8)))

;; ---------------------------------------------------------------------------
;; sha256
;; ---------------------------------------------------------------------------

(defn sha256-hex
  "Lowercase hex SHA-256 of the given bytes."
  ^String [^bytes bs]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") bs)
        sb     (StringBuilder. (* 2 (alength digest)))]
    (doseq [b digest]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (.toString sb)))

(defn content-id
  "The content hash id of v: `\"sha256:<hex>\"` over `canonical-bytes`."
  ^String [v]
  (str "sha256:" (sha256-hex (canonical-bytes v))))

;; ---------------------------------------------------------------------------
;; Tagged refs (opaque to the loop)
;; ---------------------------------------------------------------------------

(defn make-ref
  "Build a tagged payload-ref from an already-computed content id / kind / size.
   The store uses this (it already holds the canonical bytes) to avoid a second
   hash pass."
  [id kind size]
  {:fractal/ref  :payload
   :payload/id   id
   :payload/kind kind
   :payload/size size})

(defn ref-for
  "PURE content-addressed ref constructor for a value (the L0-DoD ctor): same
   value ⇒ identical `sha256:` ref ⇒ dedup. Stores reuse `canonical-bytes` +
   `make-ref` directly; this is the standalone value→ref form."
  [v kind]
  (let [bs (canonical-bytes v)]
    (make-ref (str "sha256:" (sha256-hex bs)) kind (alength bs))))

(defn payload-ref?
  "True iff x is a tagged payload-ref. The ONLY structural test the engine ever
   does on a ref — the loop never inspects `:payload/id` (02 §3, §8)."
  [x]
  (and (map? x) (= :payload (:fractal/ref x))))
