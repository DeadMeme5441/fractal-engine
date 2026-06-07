# fractal-engine — development specification

This directory is the **complete, self-contained spec** for building fractal-engine
from scratch. A fresh session should be able to build **Phase 1** end-to-end from
these documents alone, without reading any prior implementation.

Read the documents **in order**. Each is dense and load-bearing; nothing here is
filler. Every design decision below was reached deliberately and adversarially
pressure-tested — where a decision corrects a known mistake, the doc says so, so you
don't "fix" it back.

---

## Document map

| # | Doc | What it pins down |
|---|-----|-------------------|
| — | [`00-vision-and-scope.md`](00-vision-and-scope.md) | What the engine IS, the RLM thesis, the six functions, anti-goals, the phase plan, Phase-1 scope (IN/OUT) |
| — | [`01-architecture.md`](01-architecture.md) | The layered architecture, the ontology/vocabulary, how the pieces compose, the global invariants, the namespace layout + the acyclic dependency manifest (engine-free leaves `concurrent`/`catalog`) |
| — | [`02-state-port.md`](02-state-port.md) | The session-state value, the entities, the **event taxonomy**, payload-refs (pure `payload` + store-coupled `payload-io`), the `SessionStore` protocol, `MemoryStore`, the storage invariants |
| — | [`03-eval-kernel.md`](03-eval-kernel.md) | The **SCI** eval kernel: ctx-per-session, host-fn injection, the extract→eval→batch process, `FINAL`, stdout capture, the eval record, fit-or-stub + `inspect` observations, snapshot/restore |
| — | [`04-capability-sandbox.md`](04-capability-sandbox.md) | The per-session **capability profile**, the gate lattice, clamp, the mapping onto real SCI config, the named profiles, the pinned regression test |
| — | [`05-adapter.md`](05-adapter.md) | The `LlmAdapter` protocol (port-only), the request/response shapes, `build-request` (`adapter.request`), `SdkAdapter` + `FakeAdapter`, the cache passthrough, streaming, `:observation`→`:user`, system-prompt assembly |
| — | [`06-public-api.md`](06-public-api.md) | The public **SDK surface** (`fractal.engine.api`): lifecycle, reads, `read-payload`, `TurnResult`, honest-`:unknown`, the rlm-extension seam, an end-to-end usage example |
| — | [`07-runtime-config-compaction.md`](07-runtime-config-compaction.md) | `make-config`, concurrency / single-writer / async, deadline+timeout+retry, **compaction** (ceil(chars/4) token estimate, `:unknown-window-chars` fallback, role-labeled transcript), the namespace responsibilities |
| — | [`08-cache.md`](08-cache.md) | The cache contract (engine owns id/scope/ttl, SDK owns placement), the per-provider reference, honest-`:unknown` results |
| — | [`09-live-query.md`](09-live-query.md) | Live observability: async turns, ref-free snapshots, `progress`, `subscribe!`/`events-since`, durable-vs-transient, the dispatch + drop/gap policy |
| — | [`10-testing.md`](10-testing.md) | The testing strategy: `FakeAdapter`, the RUNS/SEES dev harness, fold-verify, the pinned SCI test, what to test per component |
| — | [`11-build-plan.md`](11-build-plan.md) | The **phased roadmap** + the **ordered Phase-1 task list to execute** + the open later-phase decisions |
| — | [`12-system-prompt.md`](12-system-prompt.md) | The Phase-1 system prompt (verbatim) + the v24 observation doctrine; the full recursion prompt arrives in Phase 3 |

---

## Golden rules (the spirit; details in the docs)

1. **The session is the core.** A session is a uniform step loop over a flat message
   stream: `user → llm → (clojure block) → eval → observation → llm → … → FINAL → user`.
   There is no separate "user turn" vs "tool-result turn" code path. **`FINAL` is the
   model's reply to the user**; it closes a turn. Phase 1 builds exactly ONE
   non-recursive session. Recursion is Phase 3+.

2. **The REPL is SCI, sandboxed.** The model's Clojure runs in an SCI context
   (one per session), not via JVM `eval`. Capability is **denied by default** and
   granted explicitly per a capability profile. The engine itself is a normal JVM
   program; SCI is just the eval library.

3. **Process is decoupled from storage.** The loop talks to a `SessionStore` *port*
   and never touches a database or blob store directly. Phase 1 ships an in-memory
   store; Phase 2 slots SQLite + blobs under the same port with **zero loop changes**.

4. **The event log carries results, never recipes.** Folding the event stream
   reconstructs session state without re-running any model call or eval. The store
   assigns all ids; persistence happens before the in-memory view advances.

5. **Honesty over zeros.** Cost/usage/cache that the provider didn't report are
   `:unknown`, never `0`. Budget math gates on `:known` only.

6. **The public API IS the SDK.** `fractal.engine.api` is the supported surface. The
   "rlm harness" (Phase 3/4) *extends the same surface* — recursion is internal.

7. **You have full license to design the best thing.** This spec already encodes the
   decisions; implement them faithfully. Where the spec is silent on a mechanical
   detail, choose the simplest thing consistent with the invariants above.

---

## Conventions

- **Namespace root:** all new code is under **`fractal.engine.*`** (dotted). e.g.
  `fractal.engine.api`, `fractal.engine.session-loop`, `fractal.engine.store`.
- **Vocabulary (frozen):** `session ⊃ turn ⊃ step ⊃ eval`. A **turn** = one user
  message → `FINAL`. A **step** = one adapter-call iteration within a turn (one
  assistant message + its eval batch + one observation). An **eval** = one fenced
  Clojure block. A **message** = one entry in the flat conversation. An **event** =
  one appended fact in the log. (Note: this renames v1's "call" → "step".)
- **Keyword namespaces:** entity keys are namespaced (`:turn/id`, `:step/status`,
  `:eval/raw-value`, `:message/role`, `:event/type`, `:usage/status`, …).
- **Build target:** **Phase 1 only.** Do not build Phase 2–4 unless the spec section
  is explicitly marked in-scope. The roadmap (`11-build-plan.md`) is the authority.

## How to proceed (fresh session)

1. Read `00` and `01` to load the model.
2. Read `02`–`10` for the component you're about to build.
3. Execute the **ordered task list in `11-build-plan.md`**, top to bottom.
4. Test offline with the `FakeAdapter` at every step (`10-testing.md`). No live model
   calls are needed to build or validate Phase 1.
