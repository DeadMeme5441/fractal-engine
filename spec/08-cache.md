# 08 · Cache

Prompt caching is a **host-owned request contract**. The engine chooses a stable cache
identity, derives deterministic scope ids, and forwards one opaque cache map on each
provider request. The provider SDK dependency owns all provider-specific marker placement.
`fractal.engine.cache`.

---

## 1. Request-side cache contract

Every ordinary root/child step request carries:

```clojure
{:enabled? true
 :ttl      "1h"
 :scope-id "fr:agent:<32-hex>"}
```

Every leaf (`lm` / `map-lm`) request carries the same shape, but with a `:leaf` scope:

```clojure
{:enabled? true
 :ttl      "1h"
 :scope-id "fr:leaf:<32-hex>"}
```

This map is intentionally provider-agnostic. The engine does **not** compute Anthropic
markers, OpenAI-family prompt keys, or other provider-native cache controls itself.

---

## 2. What the engine owns

```clojure
(def policy-version 1)

(defn cache-id [session]
  (or (:session/cache-id session) (:session/id session)))

(defn scope-id [cache-id purpose] ; purpose in #{:agent :leaf}
  (str "fr:" (name purpose) ":"
       (subs (sha256-hex (str policy-version ":" (name purpose) ":" cache-id)) 0 32)))
```

### `cache-id`

`cache-id` is a stable session identity distinct from `:session/id` only when a session
is being resumed from durable storage.

Current lifecycle:

- `start-session!` sets `:session/cache-id` to the new session id;
- `resume-session!` preserves the persisted `:session/cache-id`;
- `spawn-child!` allocates a **fresh** child session id and uses that as the child
  `:session/cache-id`;
- `spawn-attached!` also allocates a **fresh** child session id and uses that as the
  attached child's `:session/cache-id`.

So cache affinity is preserved across **resume**, but fresh child and attached-child
sessions always get new cache identities.

### `scope-id`

`scope-id` is deterministic from:

- `policy-version`;
- a purpose tag (`:agent` or `:leaf`);
- the chosen `cache-id`.

It is always emitted as `fr:<purpose>:<32 hex>`.

---

## 3. Root, leaf, child, and attach scopes

### Root or child step request

`build-cache-opts` uses the current session's `cache-id` and the `:agent` purpose:

```clojure
(defn build-cache-opts [view cfg]
  {:enabled? true
   :ttl      (:cache-ttl cfg)
   :scope-id (scope-id (cache-id (:session view)) :agent)})
```

That means:

- the root session keeps one stable agent scope across turns;
- a resumed durable session reuses the same agent scope;
- each child session has its own agent scope because it has a fresh `cache-id`;
- an attached child also has its own fresh agent scope; the source session/head cache is
  not reused or advanced by attach.

### Leaf request

`build-leaf-cache-opts` uses the **caller session's** `cache-id` and the `:leaf` purpose:

```clojure
(defn build-leaf-cache-opts [caller-cache-id cfg]
  {:enabled? true
   :ttl      (:cache-ttl cfg)
   :scope-id (scope-id caller-cache-id :leaf)})
```

Leaves run inside the caller's session state rather than creating their own session, so
their cache scope is:

- distinct from the caller's `:agent` scope;
- stable for that caller session;
- re-derived under the child's own `cache-id` when a leaf is invoked from a child or an
  attached child.

---

## 4. TTL

The engine currently validates only two TTLs:

- `"5m"`
- `"1h"`

`make-config` rejects anything else with `:config/invalid-cache-ttl`.

Current defaults and rationale:

- default is `"1h"`;
- recursive runs can leave multi-minute gaps between root steps, so the longer default is
  chosen to preserve prompt-cache affinity across those gaps;
- `"5m"` remains available for shorter or more cost-sensitive jobs.

The engine does not interpret TTL beyond validation and forwarding. Provider-specific
translation remains the SDK's job.

---

## 5. Response-side cache stats and honest `:unknown`

The SDK returns cache result stats in `:response/cache`. `SdkAdapter` maps that to the
engine's bare `:cache` field on the normalized call record:

```clojure
:cache {:cache/status :hit | :miss | :unknown
        :cache/cached-tokens <int> | :unknown
        :cache/cache-write-tokens <int> | :unknown}
```

The matching token counts also live under `:usage` as:

- `:usage/cached-input-tokens`
- `:usage/cache-write-tokens`

Absent provider data stays **honestly unknown**:

- never coerce missing cache stats to `0`;
- never treat `:unknown` as an error.

---

## 6. What currently rolls into `:turn/cache`

Only **ordinary loop step responses** become durable `:step/response` values, so only
those responses roll into `:turn/cache`.

`session-loop/roll-cache` currently computes:

- status:
  - `:unknown` if any step status is `:unknown`;
  - else `:hit` if any step hit;
  - else `:miss`;
- numeric fields:
  - summed when every summand is numeric;
  - otherwise `:unknown`.

So the durable turn-level cache accounting is:

```clojure
:turn/cache {:cache/status :hit | :miss | :unknown
             :cache/cached-tokens <int> | :unknown
             :cache/cache-write-tokens <int> | :unknown}
```

Two important recursion consequences follow from the current implementation:

- **leaf calls send cache opts but do not persist their own call records**, so leaf cache
  result stats do **not** currently surface as separate durable cache accounting on the
  parent turn;
- **child sessions do surface their own cache accounting**, because each child runs its
  own normal step loop and returns its self-only `:turn/cache` inside `:rlm/meta`.

---

## 7. Out of scope

The current cache contract does **not** model provider-specific cache lifecycles such as
explicit pre-created cached-content resources. If a provider needs a separate resource
creation/read/delete lifecycle, that belongs above this passthrough contract as a new
engine-level feature.
