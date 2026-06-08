# 05 · Adapter (the LLM boundary)

The adapter is the engine's **only network seam**. The engine never speaks HTTP or
provider-native tool protocols; it hands a narrowed request to an `LlmAdapter` and gets
back one assistant response as a normalized call record. Everything above that seam is
turns, steps, REPL state, recursion, and the durable API / CLI control plane used by
agents; everything below it is the sibling SDK.
`fractal.engine.adapter` (port) · `.sdk` / `.fake` (impls) · `.request` (request assembly).

---

## 1. The port: protocol, request, opts, response

```clojure
(defprotocol LlmAdapter
  (-complete [adapter request opts]
    "request -> normalized call record"))
```

`fractal.engine.adapter` is intentionally **port-only**: the protocol plus the honest
`:unknown` default sub-shapes for usage, cost, and cache. It has **zero engine deps**.

### Request: narrowed wire shape

```clojure
{:model    "model-id"
 :messages [{:role :system    :content "assembled doctrine + overlays"}
            {:role :user      :content "task"}
            {:role :assistant :content "```clojure ...```"}
            {:role :user      :content "Observation:\n..."}]
 :cache    {:enabled? true :ttl "1h" :scope-id "fr:agent:..."}}
```

Facts the adapter relies on:

- the request is **text-only**; there is no provider tool-calling at this boundary;
- the adapter sees only `:system`, `:user`, and `:assistant`;
- the engine's internal `:observation` role is rewritten to a `:user` message with the
  `"Observation:\n"` prefix before the adapter is called;
- `:cache` is an **opaque passthrough** owned by the engine and interpreted by the SDK.

### Opts: per-call runtime controls

```clojure
{:retry    true|false|nil|map
 :stream?  false
 :on-delta (fn [fragment])}
```

The adapter does **not** own the wall-clock timeout. `session-loop/adapter-call` wraps the
entire `-complete` call in `with-deadline :call-timeout-ms`, so the same timeout covers:

- the real SDK adapter;
- the fake adapter;
- the SDK's internal retry loop when retry is enabled.

### Response: normalized call record

```clojure
{:text          "assistant text"
 :finish-reason :stop|:length|:content-filter|:unknown|...
 :usage         {:usage/status :known|:unknown
                 :usage/input-tokens ...
                 :usage/output-tokens ...
                 :usage/cached-input-tokens ...
                 :usage/cache-write-tokens ...}
 :cost          {:cost/status :known|:unknown
                 :cost/usd ...}
 :model         "model-id"
 :provider      :provider-id
 :cache         {:cache/status :hit|:miss|:unknown
                 :cache/cached-tokens ...
                 :cache/cache-write-tokens ...}}
```

The engine stores this on `:step/response` **without** `:text`; the text becomes the
assistant message. The cache field is the **bare `:cache` key**, not the SDK's
`:response/cache` namespaced field.

---

## 2. Runtime failure semantics

The adapter port itself does not prescribe turn outcomes; `session-loop/run-step!` does.
The current behaviour is:

- `adapter/-complete` succeeds -> step continues with the returned call record;
- `with-deadline` times out -> terminal `TurnResult` status `:timeout`,
  `:error/type :fractal/deadline`;
- any other throwable from the adapter or SDK -> terminal `TurnResult` status `:error`,
  `:error/type :provider/failed`.

For non-timeout failures, the loop runs `kernel/err->map` over the cause chain first, so
the message and any structured data survive, then it stamps the top-level type as
`:provider/failed`.

This means:

- provider errors terminate the current turn;
- timeout is distinguished from general provider failure;
- the adapter implementations themselves stay simple and do not know about turn status.

---

## 3. Provider and model selection live above the port

The adapter port does not choose models or providers. The composition root in
`fractal.engine.session` does.

### Root session

`resolve-provider` applies the current rules:

1. `:adapter :fake` -> provider is `:fake`;
2. explicit `cfg :provider` wins;
3. otherwise resolve the provider from the model id via `fractal.engine.catalog`;
4. unknown model/provider resolution throws `:config/unknown-model`.

`start-session!` then builds:

- the **root adapter** from the resolved root provider;
- the **leaf adapter**:
  - reuse the root adapter when `:leaf-model` and `:leaf-provider` match the root;
  - otherwise build a dedicated adapter for the leaf provider/model.

### Child and attached child sessions

`spawn-child!` and `spawn-attached!` re-resolve the child side from config:

- child model defaults to `:child-model` or the root `:model`;
- explicit `:child-provider` wins;
- otherwise the child provider is resolved against the child model, with one special
  inheritance rule: if the child model equals the parent's model, an explicit parent
  provider can be reused.

Children then reset their **leaf defaults** to the child's own model/provider. A child
does not inherit the parent's leaf adapter choice.

The result is a fully config-driven split across **root**, **leaf**, and **child**
provider/model selection, with all actual calls still going through the same adapter port.

---

## 4. `SdkAdapter`

`fractal.engine.adapter.sdk/SdkAdapter` is the only implementation that crosses the
network. It wraps `llm.sdk/complete`.

```clojure
(defrecord SdkAdapter [provider-id provider-config]
  LlmAdapter
  (-complete [_ request {:keys [retry stream? on-delta]}]
    (let [resp (llm.sdk/complete
                 provider-id
                 (->sdk-request request)
                 :stream?  stream?
                 :on-event (when stream? ...)
                 :retry    (when-not stream? retry)
                 :config   (sdk-config provider-config))]
      (sdk-response->call-record resp request))))
```

### Current `SdkAdapter` contract

- `provider-config` is the opaque `cfg :provider/config` map; when nil/empty, the SDK
  falls back to its own environment/auth defaults.
- `->sdk-request` converts the engine wire request into the SDK request using the SDK's
  **namespaced message keys**:

  ```clojure
  {:request/model    "model-id"
   :request/messages [{:message/role :system
                       :message/content "text"} ...]
   :request/cache    {:enabled? true :ttl "1h" :scope-id "fr:agent:..."}}
  ```

- `:request/cache` is omitted when the engine request has no cache map.
- `sdk-response->call-record`:
  - concatenates `:response/parts` text parts into `:text`;
  - normalizes missing usage/cost/cache to honest `:unknown`;
  - maps `:response/cache` to the engine's bare `:cache` key;
  - falls back to the request model when the SDK response omits `:response/model`.

### Streaming and retry are mutually exclusive

The current implementation disables SDK retry when `:stream? true` because a partially
consumed stream cannot be retried safely.

| `:stream?` | retry passed to SDK | live deltas |
|------------|---------------------|-------------|
| `false`    | yes                 | none |
| `true`     | no                  | `:on-delta` receives each `:stream/content-delta` fragment |

The engine publishes those deltas as **transient** live events
`{:event/type :delta/token :text frag :step/id ...}`. They are not durable artifacts.

---

## 5. Request assembly (`fractal.engine.adapter.request`)

The request builder is intentionally outside the port namespace because it depends on
engine concerns: prompt text, cache scope, payload hydration, and transcript compaction.

```clojure
(defn build-request [store view cfg]
  {:model    (:model cfg)
   :messages (->> (store/kept-messages view)
                  (map #(payload-io/hydrate-message store %))
                  (map observation->user)
                  (cons (system-message view cfg))
                  (mapv to-wire))
   :cache    (cache/build-cache-opts view cfg)})
```

### Current assembly rules

- `store/kept-messages` is the compaction-aware transcript view.
- `payload-io/hydrate-message` resolves `:message/content-or-ref` into
  `:message/content`.
- `observation->user` rewrites internal observation messages before the adapter sees them.
- `system-message` concatenates, in order:
  1. the base doctrine prompt chosen by `:harness`;
  2. `cfg :system-overlay`;
  3. `session :session/system-overlay`.
- `to-wire` is the last step that drops the engine's namespaced message shape.

The adapter therefore receives exactly one assembled system message plus the kept
transcript. There is no hidden tool state or side channel at this boundary.

One related Phase 3/4 truth: a child or attached-child task is **not** a different system
prompt. Recursion uses the same `:rlm` doctrine prompt and adds the child assignment as a
normal **user message frame** (`prompt/child-invocation-frame`).

---

## 6. `FakeAdapter`

`fractal.engine.adapter.fake/fake-adapter` is the offline, deterministic test adapter.

```clojure
(defn fake-adapter [respond-fn]
  (reify LlmAdapter
    (-complete [_ request _opts]
      (let [r (respond-fn request)]
        (if (map? r) r (text->call-record r request))))))
```

Important current behaviour:

- `respond-fn` is a **pure function of the request**, not a mutable queue;
- it may return either assistant text or a full call record;
- default text responses normalize to provider `:fake` and honest `:unknown`
  usage/cost/cache;
- it ignores streaming and retry opts internally, but the outer timeout wrapper still
  applies.

`fake/responder` builds a `respond-fn` from ordered clauses matching against the last user
message, a predicate, or `:default`.

---

## 7. Pinned SDK boundary

The repo currently pins `net.clojars.deadmeme5441/clojure-llm-sdk` **0.2.3** in
`deps.edn`. The adapter-side tests pin the parts of that contract the engine relies on:

- request messages use `:message/role` / `:message/content`;
- cache passthrough survives as `:request/cache`;
- response text is built from `:response/parts`;
- absent usage/cost/cache become honest `:unknown`;
- `:response/cache` maps onto the engine's bare `:cache`.

Any SDK bump that changes those truths should be treated like an interface change: re-run
the tests and update this spec to the new ground truth before relying on it.
