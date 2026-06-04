(ns fractal-engine.cache
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(def policy-version 1)

(def default-ttl
  "Explicit prompt-cache TTL for fractal requests.

  Anthropic dropped the *implicit* ephemeral cache default from 1h to 5m on
  2026-03-06. The SDK's 5m marker carries no explicit ttl field, so it inherits
  whatever the server default is — which means fractal's caching silently went
  from 1h to 5m on that date, independent of any engine change. A recursive run
  whose root steps are separated by long leaf fan-outs or child recursion
  routinely gaps past 5m and loses its whole root-transcript cache between steps.

  We pin an explicit 1h ttl so the root transcript survives those gaps. (Leaves
  never clear Anthropic's 1024-token cache minimum, so this only ever creates
  cache on the root surface; the leaf marker is a harmless no-op.) No beta header
  is required for 1h as of 2026 — the ttl in cache_control is sufficient."
  "1h")

(def valid-ttls
  "TTL strings Anthropic accepts. Anything else falls back to the implicit
  server default in the SDK marker, so we reject unknown values loudly."
  #{"5m" "1h"})

(defn normalize-ttl
  "Coerce a caller-supplied ttl to a valid Anthropic ttl, or default-ttl when
  absent. Throws on an unrecognized value rather than silently degrading to the
  server default."
  [ttl]
  (cond
    (nil? ttl) default-ttl
    (contains? valid-ttls ttl) ttl
    :else (throw (ex-info "Unsupported cache ttl"
                          {:error/type :fractal/invalid-cache-ttl
                           :cache/ttl ttl
                           :cache/valid valid-ttls}))))

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

(defn request-cache
  ([session-id purpose] (request-cache session-id purpose default-ttl))
  ([session-id purpose ttl]
   {:enabled? true
    :ttl (normalize-ttl ttl)
    :scope-id (provider-scope session-id purpose)}))

(defn session-cache [session-id]
  {:enabled? true
   :policy-version policy-version
   :agent-scope (agent-scope session-id)
   :leaf-scope (leaf-scope session-id)
   :agent-provider-scope (provider-scope session-id :agent)
   :leaf-provider-scope (provider-scope session-id :leaf)})
