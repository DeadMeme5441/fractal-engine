# 06 · Public API (the SDK surface)

`fractal.engine.api` is **the SDK** — the supported public surface for embedding the
engine as a library. It is thin: it delegates to the internals (`session`,
`session-loop`, `store`, `live`, `payload-io`) and exposes nothing else. Phase 1 ships the
**"clojure harness"** (drive a single non-recursive session). The **"rlm harness"**
(Phase 3/4) **extends this same surface** — recursion is internal, so these signatures
do not change.

---

## 1. Config

```clojure
(make-config opts) → cfg      ; normalize engine config (see 07 for the full shape)
```

`make-config` records only the adapter *choice* (a keyword, `:sdk`/`:fake`) — it never
constructs the `LlmAdapter` and never requires the adapter impls. `start-session!` is the
sole composition root that builds the adapter instance (§2).

## 2. Session lifecycle

```clojure
(start-session! cfg)            → handle    ; also (start-session! cfg opts) for :id/:system-overlay/:capability
(run-turn!       handle msg)    → TurnResult ; BLOCKING; runs steps until FINAL; returns the reply
(run-turn-async! handle msg)    → TurnHandle ; background; deref (:promise th) for the TurnResult
(stop-session!   handle)        → handle     ; request stop; optional (stop-session! handle {:wait? true})
(compact-session! handle)       → handle     ; force compaction now (07); usually automatic
```

- `start-session!` is the **sole composition root**: it creates the store session,
  resolves the provider via the SDK catalog (or `:fake` for the fake adapter),
  **constructs the `LlmAdapter`** (sdk from the catalog provider-id; fake from cfg
  `:fake/respond`) and stashes `cfg` + the adapter on the handle, builds the SCI ctx from
  the resolved capability profile, sets `:session/system-overlay` from `opts` (records
  `:session/started`), and returns a **handle** (02 §5). `make-config` records only the
  adapter *choice* keyword — it never constructs the instance. `opts`: `:id`, `:capability`
  (a per-session profile override, clamped to the cfg default — 04), `:system-overlay`
  (extra session-level system text).
- `run-turn!` first runs the **session gate before CAS**: if `:max-turns` is set and
  reached it **throws** `ex-info` `{:error/type :fractal/session-turn-limit}`; if the
  session status is `:stop-requested`/`:stopped`/`:error` it returns a `TurnResult`
  `{:status :error :error {:error/type :fractal/session-stopped …}}`. Otherwise it
  CAS-acquires the turn-lock, compacts if flagged (07 §2/§4), opens the turn (appends
  `:user` + `:turn/started`), runs the step loop, releases the lock, and returns a
  `TurnResult` (the FINAL value **hydrated**).
- `run-turn-async!` runs the **same session gate on the caller thread** (max-turns ⇒
  **throws** `:fractal/session-turn-limit` synchronously, before the future is spawned; a
  stopped session ⇒ a `:fractal/session-stopped` error `TurnResult`), CAS-acquires the
  lock, compacts if flagged, then opens the turn **synchronously** (to capture the
  store-assigned turn id into the `TurnHandle`) and runs the loop on a daemon future. The
  future delivers a `TurnResult` map (an error becomes `{:status :error …}`, never an
  escaping throw) and releases the lock **before delivering** the promise (so a caller that
  derefs then re-invokes can't hit a stale busy flag). (Concurrency details: 07.)
- A second concurrent turn on a busy session throws `:fractal/turn-in-flight`.
- `stop-session!` writes `:session/stop-requested` immediately (non-blocking, safe from a
  `finally`/shutdown hook). If the session is **idle** (no turn in flight) it also appends
  `:session/stopped` right away; if a turn is **in flight** it appends only
  `:session/stop-requested`, and the **loop** appends `:session/stopped` at the next step
  boundary. `:wait? true` blocks on the turn-lock for synchronous teardown, then appends
  `:session/stopped`.
- `compact-session!` CAS-acquires the turn-lock (throws `:fractal/turn-in-flight` if a
  turn is in flight) and compacts under the held lock in a `try/finally`, releasing it
  after. Automatic compaction also runs inside `run-turn!`/`run-turn-async!` when flagged
  (07 §4).

> **resume / fork** are `^:alpha`/Phase-2 (cross-process resume needs the persistent
> store). In Phase 1, simply retain the handle — the live SCI ctx and view are on it. An
> in-process `resume-session!` may be exposed `^:alpha` (a status flip reusing the live
> ctx) but is redundant with holding the handle; prefer to **defer it to Phase 2**.

## 3. Reads (pure projections — no provider calls)

```clojure
(view     handle)            → the strong current view (02 §5)    ; via the store port: (current-view store sid)
(progress handle)           → a ref-free live snapshot (09)       ; reads current-view, then live/progress
(event-stream handle)       → the ordered event log
(events-since handle ev-id) → events with :event/id > ev-id (09)
(read-payload handle ref-or-value) → hydrated value              ; PUBLIC (see below)
```

⛔ **`read-payload` is PUBLIC and load-bearing.** The read/live surface returns content
as opaque payload-refs (02 §3): `view` returns `:messages` whose content may be a ref;
events carry `:final-ref`/`:result-ref`/`:content-or-ref`/`:vars-ref`. A caller hydrates
any of these through `read-payload` (a non-ref arg passes through unchanged). Callers
never branch on the ref tag. `read-payload` is the thin `api` wrapper that takes the
**handle** and delegates to `fractal.engine.payload-io/read-payload` against the handle's
store (the pure `fractal.engine.payload` ns is store-free — the store-coupled hydrate
lives in `payload-io`, 02 §3).

## 4. Live query

```clojure
(subscribe! handle callback) → unsubscribe-fn   ; callback gets each event + transient delta (09)
```
See 09 for delivery guarantees, the durable-vs-transient split, and the gap marker.

## 5. `TurnResult`

```clojure
{:status        :final | :error | :timeout | :budget-exceeded
 :session/id    "s-…"
 :turn/id       7
 :turn/final-value <hydrated FINAL value>   ; present on :final
 :turn/usage    {:usage/status :known|:unknown …}   ; honest (08); summed over steps, :unknown-aware
 :turn/cost     {:cost/status  :known|:unknown :cost/usd …}
 :turn/cache    {:cache/status :hit|:miss|:unknown        ; honest (08); :unknown-aware rollup
                 :cache/cached-tokens <int>|:unknown :cache/cache-write-tokens <int>|:unknown}
 :step-count    4                                         ; derived (filter :steps on :turn/id) — no id list stored
 :error         nil | {:error/type … :error/message … :error/data …}}
```

- **Honest accounting.** Usage/cost/cache that the provider didn't report are
  `:unknown`, never `0`. Per-turn rollups sum per-step values **`:unknown`-aware** (any
  `:unknown` summand ⇒ the rollup is `:unknown`, not a fabricated total). Budget logic
  gates on `:known`.
- **Projected from the turn entity.** `:turn/usage`/`:turn/cost`/`:turn/cache` are read
  straight off the committed turn (02 §1), as are `:status`/`:turn/id`/`:error`.
  `:step-count` is **derived** by filtering `:steps` on `:turn/id` — the turn carries no
  step-id list.
- **Namespaced errors.** `:error` (when present) is the uniform error map
  `{:error/type … :error/message … :error/data …}` (02/03); the same shape an `ex-info`
  throws and that `:turn/error`/`:eval/error`/`:session/error` carry.
- The async path delivers the **same** `TurnResult` shape on failure (full keys), not a
  bare error.

## 6. The rlm-extension seam (why the surface is stable)

When Phases 3/4 land, the public surface above **does not change shape**:
- `(rlm …)`/`(map-rlm …)` appear *inside* a session's REPL (injected host fns, 03/04);
  callers of the API don't see new functions.
- `:turn/usage`/`:turn/cost` are **SELF-ONLY** (root-session steps) — *even in Phase 3*.
  Subtree (child) token/cost rollups are exposed via a *separate* `session-tree`
  read fn, not by silently changing the meaning of `:turn/usage`. This freezes the
  field's meaning so the extension is genuinely non-breaking.

## 7. End-to-end usage (Phase 1, with the fake adapter — no keys)

```clojure
(require '[fractal.engine.api :as fe])

(def cfg (fe/make-config {:adapter :fake
                          :fake/respond (fe/responder
                                          [[:default "```clojure (FINAL {:answer 42})```"]])
                          :model "fake-model"
                          :capability :default}))

(def s (fe/start-session! cfg))

;; blocking
(def res (fe/run-turn! s "What is 6 times 7?"))
(:status res)            ;=> :final
(:turn/final-value res)  ;=> {:answer 42}

;; async + live observation
(def th (fe/run-turn-async! s "Now classify these…"))
(def unsub (fe/subscribe! s (fn [ev] (println :live (:event/type ev)))))
@(:promise th)           ;=> a TurnResult
(unsub)
(fe/stop-session! s)
```

The session stays live across turns; `def`'d REPL vars persist; each `run-turn!`
returns when the model calls `FINAL`.
