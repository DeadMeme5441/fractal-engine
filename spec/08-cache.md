# 08 · Cache

Provider prompt-caching keeps the growing root transcript cached across the multi-minute
gaps a recursive run produces (fan-out, child recursion) so step *N+1* isn't a full
cache miss. The **SDK owns all per-provider marker placement**; the engine's job is
tiny: own a stable cache identity and forward an **opaque passthrough** map.
`fractal.engine.cache`.

---

## 1. The contract: opaque passthrough

The engine attaches, on every adapter request (05 §4):

```clojure
{:enabled? true :ttl "1h" :scope-id "fr:agent:<digest>"}
```

The SDK's `decide-strategy` fans this one uniform map out correctly per provider —
Anthropic-style `cache_control` (uses `:ttl`), OpenAI-family `prompt_cache_key` (uses
`:scope-id`), Bedrock auto `cachePoint`, etc. The engine **never computes markers**; it
forwards. This single map is sufficient for every provider the engine targets except
explicit Gemini cached-content (out of scope — see §4).

## 2. What the engine owns

```clojure
;; cache-id: a STABLE session identity, separate from :session/id.
;;   defaults to the logical session id; PRESERVED across resume/fork (prompt-cache
;;   affinity — the resumed session hits the same provider bucket); FRESH per child.
(defn cache-id [session]
  (or (:session/cache-id session) (:session/id session)))

;; scope-id: deterministic, purpose-scoped, derived from cache-id.
(def policy-version 1)
(defn scope-id [cache-id purpose]                       ; purpose ∈ #{:agent :leaf}
  (str "fr:" (name purpose) ":"
       (subs (sha256-hex (str policy-version ":" (name purpose) ":" cache-id)) 0 32)))

;; ttl: cfg :cache-ttl, default "1h", validated at config time (only "5m"/"1h").
(defn build-cache-opts [view cfg]
  {:enabled? true
   :ttl      (:cache-ttl cfg)
   :scope-id (scope-id (cache-id (:session view)) :agent)})   ; Phase 1: root/:agent scope only
```

> **A single digest `scope-id`** — no separate human label. (Provenance may attach a
> readable purpose tag elsewhere if ever wanted.)

## 3. TTL = "1h" by default — and why

Anthropic dropped the *implicit* ephemeral cache default from 1h to 5m on 2026-03-06; a
recursive run routinely gaps past 5m between root steps. An explicit `"1h"` marker keeps
the root transcript cached across those gaps. Override with `:cache-ttl "5m"` for
cost-sensitive short jobs. The SDK pins the explicit TTL marker; the engine just chooses
the value. Unknown TTL throws at config time (only `"5m"`/`"1h"` are valid).

## 4. Scope notes (Phase 1)

- **Phase 1 uses only the root (`:agent`) scope** — there are no leaves yet. The
  `:leaf` purpose-scope arrives with Phase 3 (`lm`/`map-lm`) and is a near-no-op anyway
  (leaf prompts sit below Anthropic's 1024-token cache minimum); it's kept for symmetry.
- **Gemini explicit cached-content is out of scope** — it's a separate resource lifecycle
  (pre-create a CachedContent, reference by id), not a per-call passthrough. Default to
  implicit-only; model it as an engine-level lifecycle later if ever wanted.

## 5. Reading cache results back (honest `:unknown`)

The SDK's `Response` carries cache stats under `:response/cache` — the **SDK-side**
field (05). `sdk-response->call-record` maps it onto a **bare `:cache`** key on the
engine's call record (and thus onto `:step/response`, 02 §1); the engine never re-uses
the namespaced `:response/cache` key internally. This call-record `:cache` is the
*result* stats, distinct from the §1 request-side passthrough `:cache` (`:enabled?`/
`:ttl`/`:scope-id`). The matching token counts also ride `:usage`
(`:usage/cached-input-tokens`, `:usage/cache-write-tokens`, 05). **`:unknown` is the
expected, non-error outcome** for providers that don't echo cache stats (Perplexity,
Cohere, implicit Gemini, some prompt-key providers) — never coerce it to `0`, never treat
it as an error.

```clojure
;; SDK-side: the llm.sdk Response field (05)
:response/cache {:cache/status :hit | :miss | :unknown
                 :cache/cached-tokens <int> | :unknown
                 :cache/cache-write-tokens <int> | :unknown}

;; engine-side: the bare key on the call record + :step/response (same sub-shape;
;; sdk-response->call-record maps :response/cache → :cache)
:cache {:cache/status :hit | :miss | :unknown
        :cache/cached-tokens <int> | :unknown
        :cache/cache-write-tokens <int> | :unknown}
```

`commit-turn!` (07) rolls the per-step `:cache` up into `:turn/cache` (the 02 turn
entity, projected on the 06 `TurnResult`), **`:unknown`-aware** exactly like `:turn/usage`
(06): the token fields sum and any `:unknown` summand makes that field `:unknown` (never a
fabricated total); the status is `:unknown` if any step's status is `:unknown`, else
`:hit` if any step hit, else `:miss`.

```clojure
:turn/cache {:cache/status :hit | :miss | :unknown
             :cache/cached-tokens <int> | :unknown
             :cache/cache-write-tokens <int> | :unknown}
```
