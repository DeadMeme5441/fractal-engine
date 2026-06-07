# 05 · Adapter (the LLM boundary)

The adapter is the engine's **only network seam**. It is a narrow port: a flat message
list in, the next assistant message out. The engine never speaks HTTP — it asks an
`LlmAdapter`, and the only real impl wraps the sibling `clojure-llm-sdk`. The adapter
knows nothing about turns, steps, the REPL, observations, or `FINAL`. It is called the
**same way every step** (the uniform loop — there is no user-turn vs tool-turn split).
`fractal.engine.adapter` (the **port**, zero engine deps) · `.sdk` / `.fake` (impls) ·
`.request` (request assembly, §4).

---

## 1. The protocol

```clojure
(defprotocol LlmAdapter
  (-complete [adapter request opts]
    "request → the per-step call record. See shapes below."))
```

> The 3-arity (request **and** opts) is deliberate. A pure `request → message` arity
> can't carry the streaming callback or retry policy — those are per-call runtime
> concerns, not part of the canonical request. (The wall-clock **deadline is not an
> opt**: `run-step!` wraps the whole `-complete` call in `with-deadline :call-timeout-ms`,
> covering every impl — §2, §5, 07, GD18.)

> **Port-only (GD10).** `fractal.engine.adapter` is *just* the protocol + the
> request/call-record shapes, with **zero engine deps** (no store/cache/prompt/payload).
> Request *assembly* lives in `fractal.engine.adapter.request` (§4); the impls live in
> `.sdk` (§2/§6) and `.fake` (§5).

### Request (a NARROWED, text-only projection of the SDK's canonical request)

```clojure
{:model    "claude-opus-4-8"
 :messages [{:role :system    :content "…assembled system prompt…"}
            {:role :user      :content "…the task…"}
            {:role :assistant :content "```clojure (def x …)```"}
            {:role :user      :content "Observation:\n…"}]   ; :observation already mapped → :user
 :cache    {:enabled? true :ttl "1h" :scope-id "fr:…"}}      ; opaque passthrough (08)
```

- **Text only.** The model writes Clojure as plain fenced text in its assistant message.
  **No provider tool-calling.** The SDK's tools/parts/modalities richness stays *below*
  this boundary.
- Roles the adapter sees: `:system`, `:user`, `:assistant`. The engine's internal
  `:observation` role is mapped to `:user` (+ `"Observation:\n"`) during request assembly
  (§4), *before* the adapter — the adapter never sees `:observation`.
- **Wire shape, not the engine's.** `:messages` is the **final wire map** (`:role`/
  `:content`), produced by `build-request` (§4) from the engine's namespaced `:message/*`
  entities (02 §1) — hydrate, `observation->user`, then a final map to `:role`/`:content`
  (GD11). The adapter never sees `:message/content-or-ref` / `:message/role`.

### Opts (per-call runtime)

```clojure
{:retry      nil      ; nil/false one-shot · true → SDK default policy · map → merged
 :stream?    false    ; default off — see §3
 :on-delta   nil}     ; 1-arg fn called per content fragment when :stream? true
```

> The wall-clock **deadline is applied by `run-step!`** (07), *outside* `-complete`, via
> `with-deadline :call-timeout-ms` — once, around the whole call, covering both the SDK
> retry loop and the fake (GD18). The adapter never sees a `:timeout-ms`.

### Response = the per-step **call record** (honest `:unknown`, see 08)

```clojure
{:text          "…assistant text, possibly with fenced clojure…"
 :finish-reason :stop                ; :stop | :length | :content-filter | :unknown | …
 :usage  {:usage/status :known       ; or :unknown — absent counts are :unknown, never 0
          :usage/input-tokens 1234 :usage/output-tokens 56
          :usage/cached-input-tokens 1000 :usage/cache-write-tokens 0}
 :cost   {:cost/status :known :cost/usd 0.0123}   ; or {:cost/status :unknown :cost/usd :unknown}
 :model  "claude-opus-4-8"
 :provider :anthropic
 :cache  {:cache/status :hit :cache/cached-tokens 1000 :cache/cache-write-tokens 0}}  ; BARE :cache key (GD30, 08 §5)
```

The loop stores this (sans `:text` — the text becomes the `:assistant` message) as
`:step/response`, carrying the **bare `:cache`** key (GD30). See 02 §1, 08.

---

## 2. `SdkAdapter` (the real one)

Maps the narrowed request onto `llm.sdk/complete` (the §6 contract). The wall-clock
deadline is **not here** — `run-step!` wraps the whole call (07, GD18):

```clojure
(defrecord SdkAdapter [provider-id provider-config]   ; provider-config = cfg :provider/config (D9), threaded at start-session!
  LlmAdapter
  (-complete [_ request {:keys [retry stream? on-delta]}]   ; no :timeout-ms — the deadline is in run-step (07, GD18)
    (let [resp (llm.sdk/complete
                 provider-id
                 (->sdk-request request)     ; canonical SDK request (messages, cache, model) — §6
                 :stream?  stream?
                 :on-event (when stream? (fn [ev] (when-let [d (content-delta ev)]
                                                    (on-delta d))))
                 :retry    (when-not stream? retry)   ; ⛔ streaming ⇒ NO retry (§3)
                 :config   (sdk-config provider-id provider-config))]   ; D9: nil/empty ⇒ SDK env defaults
      (sdk-response->call-record resp request))))   ; §6: Response → the §1 bare-:cache call record
```

- `provider-id` is resolved from the model id via the **catalog**
  (`catalog/provider-from-model-id`, 01/GD19) at `start-session!` (07, GD20) and recorded
  on the session (`:session/provider`). Static catalog lookups are allowed outside the
  adapter; only *completions* go through `complete` (GD20).
- The SDK already does honest cost/cache (`:cost/usd :unknown` etc.) and per-provider
  cache marker placement — `SdkAdapter` just forwards the opaque `:cache` passthrough (08)
  and `sdk-response->call-record` (§6) copies the SDK's response usage/cost/cache into the
  call record, normalizing to the engine's `:usage/status` shape (absent ⇒ `:unknown`) and
  the **bare `:cache`** key (GD30).
- **`sdk-config` (D9).** Returns the SDK `:config` map for `provider-id`: the caller's
  `cfg :provider/config` override (`{:api-key …}` | `{:auth-token …}`) when present, else
  `nil`/empty — in which case the SDK falls back to its **own env-var defaults**. The
  override is threaded onto the `SdkAdapter` (as `provider-config`) at `start-session!`
  (§6); credentials never live in the engine, and `make-config` only carries the opaque
  `cfg :provider/config` it is handed.

## 3. Streaming ⟂ retry (opt-in, default OFF)

The SDK **cannot retry a streaming call** (a partially consumed stream can't be
resumed). So these two are mutually exclusive, surfaced as a deliberate config choice:

| `:stream?` | path | retry | live deltas |
|------------|------|-------|-------------|
| `false` (default) | non-streaming, retrying | yes (per `:retry`) | none |
| `true` | streaming | **no** | `:on-delta` per fragment → live dispatch (09) |

Either way, **durable events are identical** — they derive from the *completed*
assistant message, not the deltas. Token deltas are transient (09); they are never
persisted as per-token events.

> **How `:on-delta` is wired (GD29).** `run-step!` (07) passes an `:on-delta` closure that
> calls `(notify-transient store sid {:event/type :delta/token :text frag
> :step/id *current-step-id*})` — a **transient** signal (no `:event/id`, never folded),
> routed through the live dispatch under the drop-transient/gap policy (02 §4, 09). The
> adapter only invokes the callback per fragment; it never touches the store.

## 4. Request assembly (`fractal.engine.adapter.request/build-request`, called by run-step!)

Lives in its **own** namespace (GD10): the port (§1) stays engine-dep-free, while
`adapter.request` requires `cache` (08), `prompt` (12), and `payload-io` (02 §3) — so it
sits at **L3**, built after them (01/11, GD19). `run-step!` calls it with the handle's
store, the strong `current-view`, and cfg:

```clojure
(defn build-request [store view cfg]
  {:model    (:model cfg)
   :messages (->> (kept-messages view)                              ; compaction-aware; derived from :events (07 §4, see below)
                  (map #(payload-io/hydrate-message store %))       ; :message/content-or-ref → :message/content (02 §3, GD11)
                  (map observation->user)                           ; :observation → :user (+ "Observation:\n") — still namespaced
                  (cons (system-message view cfg))                  ; prepend the assembled :system message
                  (map to-wire))                                    ; FINAL: namespaced :message/* → {:role :content} wire map (GD11)
   :cache    (cache/build-cache-opts view cfg)})                    ; opaque {:enabled? :ttl :scope-id} (08)
```

- `hydrate-message`, `observation->user`, and `system-message` all operate on the engine's
  **namespaced** message shape (`:message/role`/`:message/content`); `to-wire` is the *last*
  step and is the only thing that emits the adapter's `:role`/`:content` wire map (§1, GD11).
- `kept-messages` applies compaction over the **log**, because message entities carry no
  `:event/id` (and `:message/id` is a *different* counter): it collects the `:message` of
  every message-bearing event (`:message/appended` / `:session/compacted`) in `(:events
  view)` whose `:event/id ≥ (:compact-from-event-id view)` (nil ⇒ all). The compact frame's
  own `:session/compacted` event-id *equals* the boundary, so it and everything after
  survive — the provider sees `[system, compact-frame, …new…]`. ⛔ Do NOT prune over
  `(:messages view)` directly (you'd have no event id to compare). (02 §1/§2, 07 §4)
- `hydrate-message` (payload-io, 02 §3) dereferences `:message/content-or-ref` and **renames
  it to `:message/content`** — the same hydrated shape the compaction formatter consumes (07, GD11).

**System message assembly order (documented):**
`base doctrine prompt (12)` ++ `cfg :system-overlay` ++ `session :session/system-overlay`
(`start-session!` sets `:session/system-overlay` from `opts :system-overlay` — 02 §1, 06, GD32).
The overlays specialize a session's behavior; they do not add model-facing functions.

## 5. `FakeAdapter` (offline, deterministic — the test backbone)

`FakeAdapter` short-circuits the network so **all of Phase 1 builds and tests with no
API keys and no spend**. It ignores `opts` internally (no streaming/retry) — but
`run-step!`'s `with-deadline` still wraps the fake call too, so timeout behavior is
uniform across impls (GD18). Construct it with a **responder**: a fn of the request → an
assistant-message string (or a full call record). The recommended form is
content-addressed — *not* a mutable response queue — so it is race-free under future
fan-out:

```clojure
(defn fake-adapter [respond-fn]   ; respond-fn : request → string | call-record
  (reify LlmAdapter
    (-complete [_ request _opts]
      (let [r (respond-fn request)]
        (if (map? r) r (text->call-record r request))))))   ; provider :fake, usage :unknown
```

A `responder` helper builds `respond-fn` from `[[match reply] …]` clauses (match =
substring of the last user message, a predicate on the request, or `:default`; reply =
a string, a call-record, or a fn of the request). Because it is a pure fn of the
request, scripted runs are deterministic and order-independent. See 10.

> `FakeAdapter`'s `:provider` is `:fake` and its `:usage`/`:cost`/`:cache` (the **bare**
> `:cache` key, GD30) are `:unknown` (honest) unless the responder supplies a full record.
> Provider resolution (07, GD20) returns `:fake` for the fake adapter rather than
> catalog-resolving the model id.

---

## 6. Construction (the composition root) + the SDK contract

### Where adapters are built (GD5)

`make-config` records **only the adapter choice keyword** (`:adapter :sdk | :fake`); it
never constructs an instance and never requires `.sdk`/`.fake`. `start-session!` (07) is
the **sole composition root**: it builds the adapter instance — `SdkAdapter` from the
catalog-resolved `provider-id` (GD20) **+ the `cfg :provider/config` credential override
threaded in as `provider-config` (D9; `nil`/empty ⇒ SDK env defaults)**, or `fake-adapter`
from `cfg :fake/respond` — and
stashes both the **adapter** and **cfg** on the **handle** (02 §5, 06). `run-step!` reads
`(:adapter handle)` / `(:cfg handle)`; nothing inside the loop constructs an adapter.

### The SDK contract (`clojure-llm-sdk` 0.2.3 — verified; pinned in `deps.edn`) (GD21)

The only impl that crosses the network. Pinned facts (re-verify on every bump — 04 has the
SCI regression test; this contract is its adapter-side analogue):

**`(llm.sdk/complete provider-id request & opts)`** — opts: `:stream?` (bool), `:on-event`
(1-arg fn), `:retry` (nil/true/map), `:config` (`{:api-key … | :auth-token …}`).

**Request** — `->sdk-request` builds it from the §1 narrowed request:

```clojure
{:request/model    "claude-opus-4-8"
 :request/messages [{:role :system|:user|:assistant :content "…"}]   ; content is a STRING
 :request/cache    {:enabled? true :ttl "5m"|"1h" :scope-id "fr:…"}} ; the §1/08 passthrough, verbatim
```

**Response**:

```clojure
{:response/provider      :anthropic
 :response/model         "claude-…"
 :response/parts         [{:text "…"} …]      ; assistant text = concat of each TextPart's :text
 :response/finish-reason :stop
 :response/usage         {:input … :output … :cached-input … :cache-write …}
 :response/cost          {:usd <number>|:unknown :estimated? <bool>}
 :response/cache         {:status :hit|:miss|:unknown :cached-tokens … :cache-write-tokens …}}
```

**Streaming**: text arrives as `:event/delta` on `:stream/content-delta` events;
`content-delta` extracts the fragment for `:on-delta` (§3).

**Catalog** (`fractal.engine.catalog`, an engine-free static wrapper — 01/GD19):
`provider-from-model-id` over `model-info` → `{:model … :provider …}`; `context-window`
over `model-context-length` → tokens (int) | nil. Used for provider resolution at
`start-session!` (GD20) and the model context window (resolved into cfg by `make-config`,
read by compaction — 07). These static lookups are allowed **outside** the adapter — only
*completions* go through `complete` (GD20).

**`sdk-response->call-record`** maps a Response onto the §1 call record: `:text` from
`:response/parts`, `:usage` (+ `:usage/status`), `:cost` (+ `:cost/status`),
`:response/cache` → the **bare `:cache`** (GD30, 08 §5), plus
`:model` / `:provider` / `:finish-reason`.
