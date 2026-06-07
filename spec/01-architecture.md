# 01 · Architecture

## The layers

```
        ┌──────────────────────────────────────────────────────────┐
 public │  fractal.engine.api          ← THE SDK surface (06)       │
        └───────────────┬──────────────────────────────────────────┘
                        │ start-session! / run-turn! / reads / subscribe!
        ┌───────────────▼──────────────────────────────────────────┐
 core   │  fractal.engine.session       turn lifecycle, turn-lock   │
        │  fractal.engine.session-loop  the STEP loop (run-step!)   │
        └───┬───────────┬───────────┬───────────┬──────────────┬────┘
            │           │           │           │              │
   ┌────────▼──┐ ┌──────▼─────┐ ┌───▼────────┐ ┌▼───────────┐ ┌▼─────────────┐
   │ kernel    │ │ store      │ │ adapter    │ │ capability │ │ live         │
   │ (SCI)     │ │ (port)     │ │ (port)     │ │ (profiles) │ │ (dispatch)   │
   │ 03        │ │ 02         │ │ 05         │ │ 04         │ │ 09           │
   └───────────┘ └─────┬──────┘ └─────┬──────┘ └────────────┘ └──────────────┘
                 ┌─────▼──────┐  ┌─────▼─────────┐
                 │ store.     │  │ adapter.sdk   │──→ clojure-llm-sdk (network)
                 │ memory     │  │ adapter.fake  │   (the only network seam)
                 │ (P1 impl)  │  └───────────────┘
                 └────────────┘

   kernel ──(peek-next-id · interns result · appends :eval/added)──→ store
                                              (the one non-loop append; see below)
```

Everything above the two **ports** (`store`, `adapter`) is provider- and
storage-agnostic. The session loop knows only: *append events to a store*, *ask an
adapter for the next message*, *run code in a kernel*, *clamp to a capability
profile*, *publish to a live dispatch*. Swap the store impl (memory → SQLite) or the
adapter impl (sdk → fake) without touching the loop.

**Append ownership.** The loop (`session-loop`) owns every `:turn/*`, `:step/*`,
`:message/*`, and observation append; the **kernel** owns the per-block `:eval/added`
append (during an eval batch it reads `peek-next-id`, interns the raw result, then
appends `:eval/added` itself) — the single place a non-loop component writes to the
store.

## Ontology (frozen vocabulary)

| Term | Definition |
|------|------------|
| **session** | A durable unit of identity: a flat message stream + a persistent SCI ctx (REPL vars) + a capability profile + counters. Survives across turns. |
| **turn** | One user message → steps → `FINAL`. The public unit of work. `FINAL` is the reply. A session has many turns over its life. |
| **step** | One iteration of the loop *within* a turn: one adapter call → one assistant message → its eval batch → one observation. (v1 called this a "call" — renamed.) |
| **eval** | One fenced Clojure block evaluated within a step. A step's assistant message may contain several. |
| **message** | One entry in the flat conversation: `{:message/role :system|:user|:assistant|:observation …}`. `:observation` is engine-internal; it maps to `:user` at the adapter boundary. |
| **event** | One appended fact in the per-session log. Events carry **results, never recipes**. Folding the log reconstructs the session view. |
| **blob / payload-ref** | A large value is **content-addressed** (`sha256` over canonical bytes) into a *global* blob store and referenced by an opaque tagged `payload-ref`. Small values are inlined. Blobs are Merkle leaves. (`02 §3, §9`) |
| **head** | *(Phase 2/4 only)* A **content-addressed, immutable** checkpoint — a Merkle inner node pinning a var snapshot + final + event-range + basis, for resume/fork/lineage. Not in Phase 1; the Phase-1 proxy is the session's `:vars-ref`. (`02 §9`) |

## One step, end to end (the control/data flow)

This is the spine. `run-step!` (in `session-loop`) does, for one iteration:

1. **Open the step.** Append `:step/started` (the store assigns `:step/id`, status
   `:running`, `:step/started-at`, `:step/turn-id`) and bind the host var
   `*current-step-id*` to that id — so live observers see the step *in flight*, and every
   downstream `:assistant`/`:observation` message (`:message/step-id`) and `:eval/added`
   (`:eval/step-id`) stamps it.
2. **Assemble the request.** Read the current view (strong `current-view`), prune
   messages before any `:compact-from-event-id`, hydrate interned content via
   `read-payload`, map `:observation`→`:user` (+ `"Observation:\n"`), assemble the
   system message (base doctrine ++ config overlay ++ session overlay), attach the
   opaque `:cache` map. → a narrowed adapter request (`05`).
3. **Assess context.** If the model's context window is known and the request exceeds
   the hard ratio, abort the turn (`:budget-exceeded`/context); if it exceeds the
   compaction ratio, compaction runs *before the next turn* (`07`).
4. **Call the adapter** (read off the handle) under a single wall-clock **deadline**
   (`concurrent/with-deadline call-timeout-ms`, daemon thread) applied *here* in
   `run-step!` so it wraps **both** the fake and the sdk adapter — the deadline owns the
   timeout, not the adapter. The sdk adapter wraps `clojure-llm-sdk` and (when not
   streaming) retries internally; the one deadline wraps the whole retry loop. The
   response *is* the step's call record: `{:text :finish-reason :usage :cost :model
   :provider :cache}` (honest `:unknown`). Token deltas (if `:stream?`) push to the live
   dispatch via `notify-transient` (transient `:delta/token`s).
5. **Append the `:assistant` message** (store-assigned message id, content interned if
   large) **and finalize the step via `:step/put`** (the call record sans text).
   Persist-before-fold; notify live.
6. **Eval the code.** The kernel extracts fenced blocks and evaluates them as a batch
   in the session's SCI ctx (`03`). For each block it appends an `:eval/added` event
   carrying the eval record (status/raw-value/stdout/…). A block that **errors stops
   the batch**; a block that calls **`FINAL` ends the turn**.
7. **Append the observation** (`:message/appended :role :observation`) — one combined
   observation for the whole batch, rendered fit-or-stub from raw values (`03`).
8. **Decide:** `FINAL` → commit the turn (snapshot vars to `:vars-ref` via
   `:session/vars-snapshotted`, *then* append `:turn/put`, hydrate + return the final
   value); error/continue → loop to the next step; `max-steps` reached → end with
   `:budget-exceeded`.

A **turn** wraps this: `run-turn!` CAS-acquires the turn-lock, `open-turn!`s (appends the
`:user` message + `:turn/started`, returning the store-assigned turn id), runs steps
until `FINAL`/error/limit, releases the lock, returns a `TurnResult`. `run-turn-async!`
runs `open-turn!` **synchronously** on the caller thread (to get the store-assigned turn
id) then hands the step loop to a daemon future.

## Global invariants (every component upholds these)

1. **Decoupled storage.** The loop calls the `SessionStore` port only. No component
   above the port names a database/blob/file. (`02`)
2. **Events carry results, not recipes.** `fold(events)` reproduces the view's
   *structure*; payloads live in the store's content-addressed area. (`02`)
3. **The store assigns all ids** (event + entity). The loop reads ids off the append
   return value. (`02`)
4. **Persist-before-fold.** Append = assign id+ts → persist → on success fold into the
   in-memory view cache → notify subscribers. A failed persist does not advance the
   view. (`02`)
5. **Single writer per session — two locks.** Every append serializes on the per-session
   **store lock** (inside `append-event!`); the per-session **turn-lock** (the `busy`
   atom on the handle) bounds *turn-running* writers to one. During a turn its eval/loop
   thread is the sole writer; control events (`:session/started`, `:session/stop-requested`,
   the idle `:session/stopped`) may be appended from a non-turn thread, still under the
   store lock. Live reads take neither lock. (`07`, `09`)
6. **Sandbox by default.** The SCI ctx grants nothing (no interop, no IO, no shell)
   except what the session's capability profile explicitly injects/whitelists. (`04`)
7. **Honest `:unknown`.** Cost/usage/cache absent from the provider are `:unknown`,
   never `0`. Budget gates on `:known`. (`08`)
8. **The public surface is stable across phases.** Adding the rlm harness (Phase 3/4)
   does not change `fractal.engine.api` signatures; recursion is internal. (`06`)
9. **Content-addressed & Merkle-aligned.** Every blob is content-addressed
   (`sha256`/canonical) in *every* store impl; the REPL var snapshot, FINAL values, and
   eval results are blobs. The whole model is forward-compatible with the Phase-2/4
   **Merkle DAG** — heads are content-addressed nodes linked by `:head/basis` plus
   cross-session invocation/derivation edges, and attach *additively* without
   restructuring Phase-1 state. (`02 §9`)

## Namespace layout (`fractal.engine.*`)

| Namespace | Responsibility | Spec |
|-----------|----------------|------|
| `fractal.engine.api` | The public SDK surface. Thin; delegates to the internals. | 06 |
| `fractal.engine.session` | Session lifecycle (`start-session!`/`stop-session!`) — the **composition root**: builds the SCI ctx, constructs the adapter (sdk from the catalog-resolved provider; fake from `:fake/respond`) and stashes `cfg`+`adapter` on the handle. The turn-lock, `run-turn!`/`run-turn-async!`, `open-turn!`, `compact-session!`. | 07 |
| `fractal.engine.session-loop` | `run-loop!`/`run-step!`: `:step/started` + the deadline (`concurrent/with-deadline`, wrapping the adapter call) + eval batch + per-step hard-abort + the loop's `append-event!`s; owns `commit-turn!` (snapshot vars + `:turn/put`). The step loop. | 07 |
| `fractal.engine.kernel` | The SCI eval kernel: ctx setup, `in-ns`, host-fn injection, `extract-blocks`, `eval-block`/`eval-batch`, `FINAL`, stdout capture, the eval record. | 03 |
| `fractal.engine.observe` | Observation rendering: fit-or-stub, the `«type, size»` stub, `inspect` (orchard) wiring. | 03 |
| `fractal.engine.store` | The `SessionStore` protocol (incl. `current-view`, `subscribe!`/`events-since`/`notify-transient`), the **event taxonomy**, `apply-event` (the pure fold), `empty-view`, the view shape, `verify-no-dangling-refs`. | 02 |
| `fractal.engine.store.memory` | `MemoryStore` — the Phase-1 in-memory impl (atoms); implements `subscribe!`/`events-since`/`notify-transient` by delegating to `live`. | 02 |
| `fractal.engine.payload` | **Pure** (zero engine deps): `canonical-bytes`, `sha256-hex`, `payload-ref?`, the tagged-ref constructor. | 02 |
| `fractal.engine.payload-io` | Store-coupled payload IO: `maybe-intern`, `read-payload`/`read-payload*` over a store. Built after `store`. | 02 |
| `fractal.engine.adapter` | **Port only** (zero engine deps): the `LlmAdapter` protocol + the request/call-record shapes. | 05 |
| `fractal.engine.adapter.sdk` | `SdkAdapter` — wraps `llm.sdk/complete` (no internal deadline; the loop owns it). | 05 |
| `fractal.engine.adapter.fake` | `FakeAdapter` — scripted/offline, deterministic. | 05, 10 |
| `fractal.engine.adapter.request` | `build-request` (prune + hydrate + `observation→user` + system assembly) → the narrowed request. Built after `cache`+`prompt`. | 05 |
| `fractal.engine.catalog` | Engine-free SDK-catalog wrapper: `provider-from-model-id`/`context-window` over `llm.sdk` `model-info`/`model-context-length`. **Static** lookups only — not completions (see the manifest note). | 05 |
| `fractal.engine.capability` | Capability profiles (data), the named lattice, `clamp`, `validate-profile!`, the SCI-config mapping, gated `slurp`/`spit`/shell builders. | 04 |
| `fractal.engine.cache` | `cache-id`/`scope-id` derivation, `build-cache-opts` → the opaque `:cache` map. | 08 |
| `fractal.engine.config` | `make-config` + normalization. Records the adapter *choice* keyword only — never constructs the adapter instance. | 07 |
| `fractal.engine.compaction` | Compaction: `assess` (token estimate `ceil(chars/4)` over hydrated `:message/content`, `:unknown-window-chars` fallback), `should-compact?`, rewrite to a role-labeled transcript, snapshot vars, advance `:compact-from-event-id`. | 07 |
| `fractal.engine.prompt` | The system prompt(s). | 12 |
| `fractal.engine.live` | The live dispatch as a **pure mechanism** (no store dep): ordered `dispatch`/`schedule-notify`/`notify-transient`, the `*in-dispatch*` reentrancy guard, the bounded queue + drop-transient + gap, `progress` over a view value. | 09 |
| `fractal.engine.concurrent` | `with-deadline` (daemon thread, JVM-exit-safe). Engine-free. | — |
| `fractal.engine.time` | `now-str` (ISO-8601 timestamps). Keep IO-free helpers here. | — |

> Keep each namespace's *public* surface small. The loop composes them; they do not
> reach into each other. `apply-event` (the fold) lives in `store` and must stay pure
> (no IO) — store impls call it; nothing else should need to.

## Dependency manifest (acyclic layers)

The namespaces form a **strict DAG** — a higher layer may require a lower one, never the
reverse, and there are no cycles. Build bottom-up (`11`):

| Layer | Namespaces | Depends on |
|-------|-----------|------------|
| **L0** | `time`, `payload` (pure), `concurrent`, `catalog` | — (engine-free) |
| **L1** | `store`, `capability`, `prompt`, `cache`, `observe`, `live` | L0 |
| **L1.5** | `payload-io`, `store.memory` | L1 (`store`; `store.memory`→`live`) |
| **L2** | `kernel`, `adapter`, `adapter.fake`, `adapter.sdk` | ≤ L1.5 |
| **L3** | `adapter.request`, `compaction`, `session-loop` | ≤ L2 |
| **L4** | `config`, `session` | ≤ L3 |
| **L5** | `api` | ≤ L4 |

Two edges are easy to get wrong, and both are deliberate downward edges (so the graph
stays acyclic): **kernel → store** (the kernel appends its own `:eval/added` and reads
`peek-next-id`, L2→L1) and **store.memory → live** (L1.5→L1). A **CI test asserts the
dependency graph has no namespace cycle** — run it on every build.

**Static catalog reads are not completions.** `catalog` (L0) wraps the SDK's *static*
model metadata (`model-info`/`model-context-length`) and may be called **outside** the
adapter: `config` resolves the model + context-window, `compaction` reads the
context-window, `start-session!` resolves the provider. Only `complete` (the actual
provider call) is confined to the **adapter** seam — the "adapter is the only network
seam" invariant is about completions, not catalog lookups.

## Dependencies (Phase 1)

`org.clojure/clojure` · `org.babashka/sci` (eval kernel — **pinned**, `04` has the
regression test) · `cider/orchard` (`inspect`) · `net.clojars.deadmeme5441/clojure-llm-sdk`
(provider access) · `cheshire` (json). No SQLite/Datahike in Phase 1.

## What is deliberately *not* here yet

- **Heads / resume / fork / lineage** — Phase 2/4. The architecture reserves the seam:
  heads add *new* top-level view keys + a `publish-head!` store op (with optimistic
  CAS) without touching existing fields; `:vars-ref` is the Phase-1 proxy.
- **The four model-calling host fns** — Phase 3. They inject into the same SCI ctx as
  `FINAL`/`inspect`; recursion is the host spawning child sessions (each its own ctx),
  orchestrated *between* interpreters, never inside one.
