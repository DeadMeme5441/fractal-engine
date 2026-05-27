(ns fractal-engine.cache
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def policy-version 1)

(defn sha256-string [s]
  (let [bytes (.getBytes (str s) StandardCharsets/UTF_8)
        digest (.digest (MessageDigest/getInstance "SHA-256") bytes)]
    (str "sha256:"
         (apply str (map #(format "%02x" (bit-and % 0xff)) digest)))))

(defn agent-scope [session-id]
  (str "fractal:" session-id ":agent"))

(defn leaf-scope [session-id]
  (str "fractal:" session-id ":leaf"))

(defn provider-scope [session-id purpose]
  (let [digest (subs (sha256-string (str policy-version ":" (name purpose) ":" session-id))
                     7 39)]
    (str "fr:" (name purpose) ":" digest)))

(defn request-cache [session-id purpose]
  {:enabled? true
   :scope-id (provider-scope session-id purpose)})

(defn session-cache [session-id]
  {:enabled? true
   :policy-version policy-version
   :agent-scope (agent-scope session-id)
   :leaf-scope (leaf-scope session-id)
   :agent-provider-scope (provider-scope session-id :agent)
   :leaf-provider-scope (provider-scope session-id :leaf)})
