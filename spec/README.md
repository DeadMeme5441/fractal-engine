# fractal-engine — development specification

This directory is the v1 spec and architecture record for fractal-engine. It now
describes the implemented Phase 1-4 system end to end: long-context, long-horizon
recursive work over durable working state, with the session loop, the SCI eval
kernel, restart/resume, branching, immutable heads, current-head publication, and
lineage.

Some docs intentionally preserve build history because that history explains the
layering. Read `00` and `01` as current-state truth. Read `11` as the construction
record plus the remaining open decisions.

---

## Document map

| # | Doc | What it pins down |
|---|-----|-------------------|
| — | [`00-vision-and-scope.md`](00-vision-and-scope.md) | What the engine is now, the RLM thesis, the fixed model-facing surface, the durable-storage and lineage doctrine, the shipped v1 scope |
| — | [`01-architecture.md`](01-architecture.md) | The current layered architecture, ontology, turn and recursion flow, namespace responsibilities, and the dependency DAG |
| — | [`02-state-port.md`](02-state-port.md) | The session view, event taxonomy, payload refs, the `SessionStore` protocol, and the storage invariants that both memory and SQLite implementations obey |
| — | [`03-eval-kernel.md`](03-eval-kernel.md) | The SCI eval kernel: ctx-per-session, host-fn injection, extract/eval/batch semantics, `FINAL`, observation rendering, snapshot/restore |
| — | [`04-capability-sandbox.md`](04-capability-sandbox.md) | The per-session capability profile, clamp rules, SCI gating, and the pinned sandbox regression tests |
| — | [`05-adapter.md`](05-adapter.md) | The adapter port, request/response shapes, request assembly, fake/sdk adapters, and the provider boundary |
| — | [`06-public-api.md`](06-public-api.md) | The public SDK surface (`fractal.engine.api`): lifecycle, reads, payload hydration, async turns, and the stable extension seam |
| — | [`07-runtime-config-compaction.md`](07-runtime-config-compaction.md) | Config, composition roots, single-writer runtime rules, deadlines, compaction, and loop ownership |
| — | [`08-cache.md`](08-cache.md) | The cache contract and honest `:unknown` accounting rules |
| — | [`09-live-query.md`](09-live-query.md) | Live-query dispatch, transient vs durable signals, progress snapshots, backlog replay, and subscriber behavior |
| — | [`10-testing.md`](10-testing.md) | The testing strategy: fake/offline coverage, durable reopen checks, and where live validation fits |
| — | [`11-build-plan.md`](11-build-plan.md) | The historical phase plan, ordered construction record, and what remains open after Phase 4 |
| — | [`12-system-prompt.md`](12-system-prompt.md) | The current clojure-harness, recursive-harness, leaf, and child-invocation prompt doctrine |

---

## Golden rules

1. **The session is the core.** A session is one durable REPL-backed working state.
   A turn is `user -> steps -> FINAL`. `FINAL` returns the value and closes the turn;
   the session stays live, can branch, and can later be resumed.

2. **The model-facing surface is fixed and explicit.** The model sees ordinary
   Clojure plus injected host fns. In `:clojure` harness that is `FINAL` and
   `inspect`. In `:rlm` harness it is `FINAL`, `inspect`, `lm`, `map-lm`, `rlm`,
   `map-rlm`, and `attach-rlm`. There is no magic context var or hidden session
   object in the model contract.

3. **The compute kernel stays small.** The kernel owns SCI ctx setup, block
   extraction, eval batching, `FINAL`, `inspect`, and snapshot/restore. Session
   orchestration, recursion, storage policy, and lineage stay outside it.

4. **Process is decoupled from storage.** The loop talks only to the `SessionStore`
   port. `MemoryStore` and `SqliteStore` obey the same contract. The canonical
   durable backend is SQLite for the event log plus a file BlobStore for payloads.

5. **Heads and lineage are first-class durable data.** A finalized turn snapshots
   vars, commits the turn, and publishes an immutable content-addressed current
   head. Invocation and derivation edges preserve auditable state-graph integrity
   across children, branches, attach, and restart/resume.

6. **Filesystem layout is a backend detail.** Logical identity lives in session ids,
   payload ids, head ids, and lineage-edge ids. Paths under the store directory are
   physical storage only, not the semantic API.

7. **Events carry results, never recipes.** Folding the event log reconstructs the
   session view without re-running a provider call or eval. The store assigns ids,
   persists first, folds second, and only then notifies live subscribers.

8. **Honesty over zeros.** Usage, cost, and cache fields that the provider did not
   report remain `:unknown`, never fabricated `0`.

9. **The public API is the core programmatic surface.** `fractal.engine.api` is the
   supported embedding surface. A CLI or readback layer sits above it as a durable,
   scriptable, JSON-first control plane for agents under human supervision, not as
   a consumer-facing terminal shell.

---

## Conventions

- **Namespace root:** all code lives under `fractal.engine.*`.
- **Vocabulary:** `session ⊃ turn ⊃ step ⊃ eval`. Also: `head` = immutable published
  continuation boundary; `edge` = durable invocation/derivation lineage fact.
- **Keyword namespaces:** entity keys are namespaced (`:turn/id`, `:head/id`,
  `:edge/type`, `:usage/status`, ...).
- **Build target:** Phases 1-4 are implemented. The remaining work is packaging,
  operational hardening, or future architecture-adjacent work, not missing core
  layers.

## How to proceed

1. Read `00` and `01` to load the current v1 model.
2. Read the targeted detail docs (`02`-`10`) for the component you are touching.
3. Use `11` as the layering/history guide and open-decisions log, not as the source
   of truth when it conflicts with the current-state narrative or code.
4. Validate offline with the `FakeAdapter` first. When touching durability or
   recursion semantics, also validate the SQLite/reopen/Phase-4 paths.
