# 11 · Build Plan and Status

Phases 1-4 are implemented. This document is no longer just a "next tasks" note for
the core engine. It now serves three jobs:

1. a **status ledger** for what Phase 1-4 delivered;
2. the **historical Phase-1 construction order**, still useful as a layering guide;
3. a short list of work that remains **outside** core Phase 1-4 completeness.

The key status truth is simple: **no core Phase 1-4 engine deliverable remains open in
this tree.** The remaining work is validation hardening and longer-horizon
operational proof.

The engine claim is: **persistent recursive working state over time**, with
restart / resume, branching through children and leaves, attach from prior
cognitive state, operator steering, and durable state-graph integrity.

---

## Roadmap status

| Phase | Deliverable | Status | Main proof surfaces |
|---|---|---|---|
| **1** | The session core: sandboxed SCI REPL, deterministic turn loop, persistent working state within a session, public API, compaction, live query, fake + sdk adapters. | built | `session_test`, `kernel_test`, `api_test`, `live_test`, `deps_acyclic_test` |
| **2** | Durable storage under the same `SessionStore` port: SQLite event log + content-addressed BlobStore + durable restart / `resume-session!`. | built | `store_contract_test`, `store_sqlite_test`, `blobstore_test` |
| **3** | Recursive decomposition over time: `lm` / `map-lm` leaves, `rlm` / `map-rlm` children, harness hot-swap, fan-out, child capability inheritance, durable child sessions. | built | `recursion_test`, `live_recursion_test` |
| **4** | The durable state graph: immutable heads, current-head publication, invocation / derivation lineage edges, attach from prior state via `attach-rlm`, current-head-based restore. | built | `phase4_test`, Phase-4 store invariants in `store_contract_test`, manual second-provider smoke |

### Current build-state notes

- The engine is no longer in "Phase 1 only" shape. The spec should speak in terms of
  the implemented Phase 1-4 line, not a future tense build-out.

- The status lens is **state over time**: persistent vars, durable resume, recursive
  children, branching from immutable heads, attach from prior state, and integrity of
  the stored head / edge graph.

- The **historical ordered Phase-1 list below is still worth keeping**. It remains the
  cleanest layering guide for a rebuild, a large refactor, or a new implementation of
  the same engine model.

- The acyclic dependency test is now the guard that keeps that construction order
  honest. The list is historical; the graph test is live.

- Current validation has already gone beyond offline unit/integration proof. The
  implemented line has a passing offline suite, a passing checked-in live suite, and a
  separate manual live smoke on a second provider stack. That is evidence for the
  status above, not a promise that repo-level end-to-end testing is finished.

---

## Historical Phase-1 construction order (complete)

Each step below **was** the construction order. Keep it as the layering guide if
rebuilding from scratch or doing a large architectural refactor. "DoD" means "what had
to be true before moving on"; all of it is now reflected in the current tree.

> **Order = a topological linearization of 01's dependency manifest** (L0→L5): every
> task depends only on earlier ones, so each was testable offline before the next. The
> CI namespace-graph test now asserts the dependency graph stays acyclic.

### 1. Foundations — `time`, `payload`, `concurrent`, `catalog`
- `fractal.engine.time`: `now-str` (ISO-8601).
- `fractal.engine.payload` (**pure**, zero engine deps): `canonical-bytes` (deterministic
  EDN: sorted keys, no meta, print-length/level nil), `sha256-hex`, `payload-ref?`, the
  ref constructor. (Store-coupled `maybe-intern`/`read-payload` move to `payload-io`,
  step 2.)
- `fractal.engine.concurrent`: `with-deadline` (daemon thread, JVM-exit-safe). Engine-free.
- `fractal.engine.catalog`: an engine-free SDK-catalog wrapper — `provider-from-model-id`
  / `context-window` over `llm.sdk` `model-info` / `model-context-length` (a `nil` window
  is tolerated). Static lookup only; completions still go through the adapter.
- **DoD:** identical values → identical `sha256:` ref via the ctor (dedup); `payload-ref?`
  discriminates a tagged ref; `with-deadline` fires and never pins JVM exit; `catalog`
  resolves provider + window for a known model and tolerates an unknown window. (02 §3, §9)

### 2. State port + payload IO — `store`, `payload-io`
- `fractal.engine.store`: `empty-view`, the **event taxonomy** (incl. `:turn/started`,
  `:step/started`, `:session/vars-snapshotted`) + `apply-event` (pure fold,
  `replace-by-id`, `bump-counters`), the `SessionStore` protocol (incl. `current-view`,
  `subscribe!`/`events-since`/`notify-transient`), `verify-no-dangling-refs` (uses the
  pure `payload-ref?`, so `store` depends only on pure `payload`).
- `fractal.engine.payload-io` (store-coupled): `maybe-intern` (inline small scalar /
  string≤512 / number / keyword / bool / nil / small-coll, else intern content-addressed),
  `read-payload` (pass non-refs through), `hydrate-message`
  (`:message/content-or-ref` → `:message/content`).
- **DoD:** fold reproduces the view; the taxonomy carries `:step/started`+`:turn/started`;
  `read-payload` passes non-refs through; `maybe-intern` inlines scalars, interns large
  values content-addressed; `verify-no-dangling-refs` resolves every ref. (02)

### 3. Live dispatch — `live`
- `fractal.engine.live`: the per-session ordered dispatch **as a pure mechanism, no store
  dep** — `dispatch`, `schedule-notify` (non-blocking offer), `notify-transient`, the
  `*in-dispatch*` reentrancy guard, the bounded queue + `:drop-transient` + a
  `:subscribe/gap {:last-delivered-event-id N}` marker, and `progress` over a view value.
- **DoD:** ordered delivery; a throwing/slow subscriber cannot break or stall writes;
  overflow drops only transient deltas and emits a gap carrying the last delivered id;
  `progress` derives a ref-free snapshot from a view value. (09)

### 4. In-memory store — `store.memory`
- `fractal.engine.store.memory`: `MemoryStore` (global content-addressed `blobs` atom +
  per-session slots; `new-slot` holds `view`/`lock`/`dispatch`/`sci-ctx`(atom nil)/
  `busy`(atom false), stable across an idempotent re-create). `append-event!` stamps the
  event id + any entity id from the per-session counters, persist(no-op), fold, then
  `schedule-notify` via `live` (out of the lock). Idempotent `create-session!`; **strong**
  `current-view` (never delegates to `read-state`); `read-state`/`peek-next-id`;
  `intern-payload!`/`read-payload*`; `subscribe!`/`events-since`/`notify-transient`
  delegate to `live`.
- **DoD:** fold reproduces the view; the store assigns all ids (a fresh nested entity id
  per creating event); an idempotent create preserves the stable slot; dedup;
  `current-view` is strong read-your-writes; `events-since` recovers the backlog. (02, 09)

### 5. Capability — `capability`
- `fractal.engine.capability`: the profile data shape, the named profiles
  (`:locked-down`/`:default`/`:trusted`), `clamp` (gate-meet), `validate-profile!`,
  `sci-opts` (`:namespaces`/`:classes`/`:deny`, gated `slurp`/`spit`/`sh`, the
  network-aware read gate, the canonical path-boundary check, the dangerous-class throw,
  `:engine-fns`). `sci-opts` selects `:ns/granted ∩ catalog`; document the copy-ns
  catalog for the non-default namespaces (`clojure.pprint`/`clojure.data`/`clojure.zip`/
  `clojure.core.protocols`; string/edn/set/walk are SCI defaults). Takes
  `engine-fn-impls` as **data** (no kernel dep).
- **DoD:** the **pinned SCI regression test (04 §7) passes**; `clamp` = meet; loosening
  overrides rejected; `:default` reads a file but refuses URL-slurp and `git`. (04)

### 6. Observation + eval kernel — `observe`, `kernel`
- `fractal.engine.observe`: `value-display` (fit-or-stub; `ok-fit` 400 / `final-fit`
  1200), `value-stub` (the mechanical labels), `inspect-text` (orchard),
  `render-observation`, the `:eval/result-preview` projection.
- `fractal.engine.kernel`: `new-ctx` **3-arity** `[session-id capability-profile
  engine-fn-impls]` (`in-ns`), `make-FINAL`/`make-inspect`, `extract-blocks`,
  `eval-block`/`eval-batch` (the per-step `(in-ns …)` re-assertion is the **first** action
  of `eval-batch`; interleaved read+eval returning the last form's value; `sci/out`
  capture; the eval rec carries transient `:eval/raw-value`(+`:eval/raw-final` on FINAL) +
  status/stdout/stderr/forms-count/elapsed-ms/error; `FINAL` exception; batch semantics;
  `peek-next-id` per block; before each `:eval/added` intern raw → `:eval/result-ref`
  (payload-io) + compute `:eval/result-preview` (observe), then append the **stripped**
  entity), `snapshot-vars` (canonical, content-addressed; enumerates ns-interns + host
  values via a sci eval; `the-session-ns` via sci `find-ns`), `restore-vars!`
  (sci `intern`, **P2/4**; `clear-ns-vars` via `ns-unmap`),
  `*current-turn-id*`/`*current-eval-id*`.
- **DoD:** vars persist across evals/turns; batch error stops / `FINAL` ends; a model
  `(in-ns …)` does **not** strand later host evals (the per-step re-assertion); a
  multi-form block returns the last value; the kernel interns raw → `:eval/result-ref` +
  computes `:eval/result-preview` before appending the stripped `:eval/added`; fit-or-stub
  correct; `snapshot-vars` round-trips (via sci eval) + marks unrestorables;
  `restore-vars!` of `(1 2 3)` via sci `intern` is correct. (03)

### 7. Adapter — `adapter`, `adapter.fake`, `adapter.sdk`
- `fractal.engine.adapter`: the `LlmAdapter` protocol + the request/response/call-record
  **shapes only** (zero engine deps; `build-request` moves to `adapter.request`, step 8).
- `fractal.engine.adapter.fake`: `FakeAdapter` + `responder` (ignores `opts` — no
  streaming/retry/deadline).
- `fractal.engine.adapter.sdk`: `SdkAdapter` (wraps `llm.sdk/complete`; **no internal
  deadline** — the loop applies it in `run-step!`; honest `:unknown` normalization;
  `sdk-response->call-record` per the SDK-contract (05) — text from parts, usage(+status),
  cost(+status), `response/cache` → bare `cache`).
- **DoD:** the fake returns a well-formed call record; honest `:unknown` when the responder
  omits usage; `sdk-response->call-record` maps text/usage/cost/cache per the SDK
  contract. (05)

### 8. Cache + prompt + request assembly + config — `cache`, `prompt`, `adapter.request`, `config`
- `fractal.engine.cache`: `cache-id`, `scope-id`, `build-cache-opts`.
- `fractal.engine.prompt`: the Phase-1 system prompt + the compaction prompt (12).
- `fractal.engine.adapter.request` (built **after** cache+prompt): `build-request`
  `[store view cfg]` — prune-before-`:compact-from-event-id` + `hydrate-message`
  (payload-io) + `observation->user` (on the namespaced shape) + system assembly (base
  doctrine ++ cfg `:system-overlay` ++ session `:system-overlay`) + a final map to wire
  `{:role :content}`; attaches the opaque `:cache` (cache) map. Requires cache/prompt/
  payload-io.
- `fractal.engine.config`: `make-config` (normalize/validate cfg, validate+clamp the
  default capability profile, resolve model + `:context-window` via `catalog`, **record
  the adapter choice keyword only** — never construct the adapter instance, validate
  `:cache-ttl`).
- **DoD:** deterministic `scope-id`; `build-request` prunes/hydrates, maps `:observation`→
  `:user`, assembles the system overlays; config rejects bad ttl / over-loose capability
  and resolves model+window via `catalog`; `make-config` records the adapter keyword
  without constructing it. (07, 08)

### 9. Runtime — `compaction`, `session-loop`, `session`
- `fractal.engine.compaction` (built **before** `session-loop` — `run-step!` requires
  `assess`; L3 per 01's manifest): `assess`, `should-compact?`, `compact-session!`
  (single `:session/compacted` event; **no var restore**; store-stamped compact-msg id;
  token estimate `ceil(chars/4)` over hydrated `:message/content` + `:unknown-window-chars`
  fallback; a role-labeled transcript formatter).
- `fractal.engine.session-loop`: `run-loop!` `[handle turn-id]` / `run-step!` +
  `commit-turn!`. `run-step!`: append `:step/started` **first** (store assigns `:step/id`
  + running/started-at/turn-id; bind host `*current-step-id*`) → `build-request` → assess
  (hard-abort only) → adapter call **under `with-deadline :call-timeout-ms`** (covers
  fake+sdk; timeout → `:timeout`, exhausted-retries → `:error`/`:provider/failed`; the
  `:on-delta` closure calls `notify-transient` with a `:delta/token` item carrying text +
  current-step-id) → finalize `:step/put` + append `:assistant` → `eval-batch` (kernel
  appends `:eval/added`/block) + observation (loop-owned appends set `:message/step-id`/
  `:eval/step-id`) → decide. `commit-turn!`: intern `:turn/final-ref`, append
  `:session/vars-snapshotted` (`:vars-ref`) just before the final `:turn/put`, roll
  `:turn/usage`/`:turn/cost`/`:turn/cache` `:unknown`-aware.
- `fractal.engine.session`: `start-session!`/`stop-session!`, the turn-lock,
  `run-turn!`/`run-turn-async!` (`open-turn!` returns the turn-id; run the loop on it in a
  try/finally that resets `busy`), `open-turn!` (peek the `:turn` id, append the user
  message with `:message/turn-id`, then `:turn/started` with that id + `:turn/user-message-id`,
  return the turn-id), `compact-session!` (CAS the busy turn-lock, throw
  `:fractal/turn-in-flight` if busy). `start-session!` is the **sole composition root**
  that constructs the adapter (sdk from the catalog provider-id; fake from `:fake/respond`)
  and stashes cfg+adapter on the handle, resets the `sci-ctx` atom after create, and sets
  `:session/system-overlay` from opts.
- **DoD:** a full turn arc to `FINAL` with the fake adapter (verify via RUNS/SEES); each
  step appends `:step/started` **before** the adapter call, so a live observer sees the
  step in flight; the deadline wraps the single adapter call; async delivers a result map
  and **always releases `busy` before delivering** (even if `error-result` throws);
  `:fractal/turn-in-flight`; `:fractal/max-steps` → `:budget-exceeded`; `:max-turns` throws
  `:fractal/session-turn-limit` pre-CAS; stop ordering (idle → stop-requested+stopped;
  in-flight → stop-requested then stopped at the next step boundary); compaction fires +
  keeps vars. (07)

### 10. Public API — `api`
- `fractal.engine.api`: re-export the surface (06) — `make-config`, lifecycle, reads
  (`view`/`progress`/`event-stream`/`events-since`), `read-payload` (takes a **handle**,
  delegates to `payload-io` on the handle store), `subscribe!`, `responder`. `progress`
  reads `current-view` then `live/progress`. Nothing else public.
- **DoD:** the end-to-end example (06 §7) runs green offline.

### 11. Dev harness (recommended) — `dev/seeing`
- The RUNS/SEES tool (10 §2). Behind a `:dev` path; never in the build.

> After step 10, Phase 1 **was** complete: a fresh session could run the full offline
> suite green with no credentials, and `start-session!` / `run-turn!` could drive a real
> SCI REPL to `FINAL` via the fake adapter (or a live `SdkAdapter` with credentials). The
> namespace-graph test remains the guard against dependency cycles.

---

## Resolved after Phase 1

These were once deferred decisions. They are no longer open in the current tree.

- **Storage (Phase 2):** built: SQLite event log + file BlobStore under the same
  `SessionStore` + `intern-payload!` / `read-payload*` contract. Datahike stayed out;
  no concrete query need justified a second derived index.

- **Recursion (Phase 3):** built: the four host fns, leaf-vs-child separation,
  bounded fan-out, capability inheritance-and-clamp, and harness hot-swap by config alone.

- **Merkle heads / lineage (Phase 4):** built: `publish-head!`, current-head restore,
  immutable head fingerprints, invocation / derivation edges, and `attach-rlm`.

---

## Still outside core Phase 1-4 completeness

This is the honest remaining plan. It is **not** missing engine implementation; it is
what comes next if the goal is stronger confidence in long-horizon recursive work.

- **Long-horizon validation hardening.** The next real lane is a stronger end-to-end
  matrix for persistent recursive work: more root-prompt live scenarios, more than one
  checked-in provider family, and explicit proofs for multi-turn continuity,
  stop / resume, branch / attach, and durable graph integrity.

- **Fault and recovery proof.** The engine already proves normal restart / resume after
  committed work. What is still missing is harsher proof around interrupted recursion,
  commit-boundary crashes, and recovery from partially completed live work without
  corrupting the state graph.

- **Load / cost / duration proof.** The engine correctness story is ahead of its
  operational proof story. Larger fan-outs, longer-running sessions, heavier blob churn,
  and explicit time / cost envelopes remain a separate validation lane.

- **Packaging, if wanted later, is separate.** Distribution choices are not part of the
  current engine claim and should not be confused with Phase 1-4 completeness.
