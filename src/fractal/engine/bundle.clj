(ns fractal.engine.bundle
  "L1.5 · the BUNDLE identity (yjy): the stamped, content-addressed identity of
   the model-facing world a session runs under — {harness, doctrine stamp,
   surface stamps, normalized capability}. The bundle is the general form of
   rehydration: a head produced under bundle B can only be trusted as an attach/
   resume basis when the caller re-presents a compatible world. Surface impls
   are fns and can never be serialized, so rehydration is IDENTITY VERIFICATION
   + RE-PRESENTATION (the embedder supplies the world again; the engine checks
   the stamps), never deserialization.

   Compatibility is component-wise, mirroring existing semantics:
   - surfaces must MATCH exactly (typed :bundle/surface-mismatch) — restored
     vars were created against those namespaced fns;
   - doctrine must MATCH on RESUME (the session's own conversation continues
     under the words it ran with) but is NOT checked on attach (a derived child
     is a fresh conversation under the caller's doctrine);
   - capability is NEVER matched here — it follows the clamp lattice (narrowing
     is legal and handled by session/capability).
   Sessions recorded before bundles existed (no :session/bundle) skip
   verification (the legacy path, like the capability-profile fallback)."
  (:require [fractal.engine.payload :as payload]
            [fractal.engine.prompt :as prompt]
            [fractal.engine.surface :as surface]))

(def bundle-version 1)

(defn- doctrine-stamp
  "The stamp (sans text) of the doctrine this cfg runs under: the cfg :doctrine
   override when present (37i), else the built-in harness-selected prompt —
   resolved through prompt/base-doctrine, the SAME selection request assembly
   uses, so the stamp can never drift from the text actually sent."
  [cfg]
  (select-keys (or (:doctrine cfg) (prompt/base-doctrine (:harness cfg)))
               [:prompt/name :prompt/version :prompt/hash]))

(defn- normalize-classes
  "Hash-stable form of a :cap/java-classes grant: the :all sentinel survives;
   a finite map reduces to its sorted key NAMES (the lattice compares key sets;
   live Class values neither round-trip nor hash stably)."
  [classes]
  (if (= :all classes)
    :all
    (vec (sort (map str (keys (or classes {})))))))

(defn- normalize-capability
  "The capability component of the bundle: the validated profile as pure data,
   classes normalized. Sets/maps hash deterministically via canonical-bytes."
  [profile]
  (-> (select-keys profile
                   [:capability/name :cap/fs-read :cap/fs-write :cap/shell
                    :cap/network :ns/granted :cap/java-classes :engine-fns
                    :surface/fns :capability/unsafe])
      (update :cap/java-classes normalize-classes)))

(defn describe
  "The bundle description for a cfg + resolved profile — pure data, suitable
   for persistence on the session row."
  [cfg profile]
  {:bundle/version    bundle-version
   :bundle/harness    (:harness cfg)
   :bundle/doctrine   (doctrine-stamp cfg)
   :bundle/surfaces   (surface/canonical-stamps (surface/stamps (:surfaces cfg)))
   :bundle/capability (normalize-capability profile)})

(defn stamp
  "describe + the content-addressed :bundle/hash over the description."
  [cfg profile]
  (let [d (describe cfg profile)]
    (assoc d :bundle/hash (payload/content-id d))))

(defn- mismatch! [etype recorded computed component]
  (throw (ex-info (str "bundle " (name component) " does not match the recorded session world")
                  {:error/type etype
                   :bundle/component component
                   :bundle/recorded (get recorded component)
                   :bundle/computed (get computed component)
                   :bundle/recorded-hash (:bundle/hash recorded)
                   :bundle/computed-hash (:bundle/hash computed)})))

(defn assert-resume-compatible!
  "RESUME verification: surfaces AND doctrine must match the recorded bundle.
   nil recorded (legacy session) ⇒ no-op. Returns the computed bundle."
  [recorded computed]
  (when recorded
    (when (not= (:bundle/surfaces recorded) (:bundle/surfaces computed))
      (mismatch! :bundle/surface-mismatch recorded computed :bundle/surfaces))
    (when (not= (:bundle/doctrine recorded) (:bundle/doctrine computed))
      (mismatch! :bundle/doctrine-mismatch recorded computed :bundle/doctrine)))
  computed)

(defn assert-attach-compatible!
  "ATTACH verification: the derived child must re-present the SOURCE's surfaces
   (restored vars were created against them); doctrine/capability are the
   caller's concern (fresh conversation / clamp lattice). `allow-mismatch?`
   skips the check explicitly — divergence becomes the embedder's stated
   choice instead of a silent wrong-world rehydration. nil recorded ⇒ no-op.
   Returns the computed bundle."
  [recorded computed allow-mismatch?]
  (when (and recorded (not allow-mismatch?))
    (when (not= (:bundle/surfaces recorded) (:bundle/surfaces computed))
      (mismatch! :bundle/surface-mismatch recorded computed :bundle/surfaces)))
  computed)
