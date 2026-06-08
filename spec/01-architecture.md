# 01 · Architecture

## The layers

The current engine is a strict layered system. The important fact is not just that
there are ports, but that long-horizon recursive state already survives on them:
across turns, children, branches, attach, and process restart.

```text
public surface
  fractal.engine.api

composition roots
  fractal.engine.session
  fractal.engine.recursion   (host fns assembled by session, never requires session)

execution spine
  fractal.engine.session-loop
  fractal.engine.kernel

ports and shared services
  fractal.engine.store
  fractal.engine.adapter
  fractal.engine.payload-io
  fractal.engine.capability
  fractal.engine.surface
  fractal.engine.compaction
  fractal.engine.live

backends
  fractal.engine.store.memory
  fractal.engine.store.sqlite
  fractal.engine.store.blobstore
  fractal.engine.adapter.fake
  fractal.engine.adapter.sdk

foundations
  fractal.engine.payload
  fractal.engine.cache
  fractal.engine.prompt
  fractal.engine.catalog
  fractal.engine.concurrent
  fractal.engine.time
```

### What each layer owns

- **`api`** is the supported SDK surface and nothing else.
- **`session`** is the sole composition root: it chooses the store backend, builds
  adapters, resolves capability and model/provider defaults, creates or resumes
  sessions, and assembles built-in plus configured host-fn surfaces.
- **`session-loop`** owns the step spine: open turn, build request, call adapter
  under deadline, finalize assistant output, run kernel eval, append observations,
  and commit or finalize the turn.
- **`kernel`** owns only SCI evaluation mechanics, not session orchestration.
- **`recursion`** implements leaf calls, child calls, attach calls, envelopes, and
  lineage-edge recording. It depends on closures injected by `session`, not on the
  `session` namespace itself.
- **`surface`** validates embedder-provided SDK surface descriptors, produces
  public stamps, renders stable and dynamic prompt fragments, and assembles
  capability-filtered SCI namespaces.
- **`store`** defines the event-sourced semantic contract. `store.memory` and
  `store.sqlite` implement it; `store.blobstore` is the physical payload backend
  used by SQLite durability.

### Append ownership

Write ownership is deliberately split:

- **`session`** appends session lifecycle facts such as `:session/started` and
  stop-status events.
- **`session-loop`** appends `:turn/*`, `:step/*`, `:message/*`, and
  `:session/vars-snapshotted`, and publishes the current head when a turn ends in
  `FINAL`.
- **`kernel`** appends `:eval/added` for each evaluated fenced block.
- **`recursion`** appends durable lineage edges.
- **`store`** stamps ids and timestamps, persists first, folds second, then notifies
  subscribers.

No other namespace should invent its own durable write path.

## Control-plane seam

The core consumer is an agent operating with shell or CLI access under human
supervision.

- `fractal.engine.api` is the core programmatic surface.
- A CLI or readback layer, when present, sits above the API as a durable,
  scriptable control plane: JSON-first, non-interactive, resumable, governed, and
  inspectable.
- Human-readable summaries are secondary readbacks for operator steering.
- The engine itself therefore owns state and execution semantics, not an end-user
  terminal UX.

## Ontology

| Term | Definition |
|------|------------|
| **session** | A durable unit of identity: message stream, session metadata, capability profile, SCI ctx, counters, and published heads. |
| **harness** | The built-in model-facing host-fn surface selected by config: `:clojure` or `:rlm`. |
| **SDK surface** | An embedder-provided namespaced host-function world, gated by `:surface/fns`, rendered into prompt context, and stamped for durable resume compatibility. |
| **turn** | One user message through zero or more steps until `FINAL` or a terminal error/timeout/budget outcome. |
| **step** | One adapter-call iteration inside a turn: assistant output, eval batch, and one combined observation. |
| **eval** | One fenced Clojure block evaluated by the kernel. |
| **message** | One flat transcript entry with role `:system`, `:user`, `:assistant`, or `:observation`. |
| **event** | One durable appended fact. Folding events reconstructs the session view. |
| **payload ref** | A content-addressed reference to a large value stored in the payload backend. |
| **head** | An immutable content-addressed continuation boundary for a session. A finalized turn publishes a new current head with basis, event range, vars snapshot, and final value refs. |
| **current head** | The published head the session presently advances from. Resume reads this before any projection fallback. |
| **branch** | A new continuation in the durable state graph, realized as a fresh child session or attached child rooted at a selected head. |
| **lineage edge** | A durable content-addressed invocation or derivation fact linking sessions and heads across recursion. |

## One turn, end to end

This is the current control and data flow for a normal turn.

1. **Enter through `session`.** `run-turn!` or `run-turn-async!` checks stop status
   and `:max-turns`, acquires the turn lock, optionally compacts, appends the user
   message and `:turn/started`, and hands the turn id to `session-loop`.

2. **Open a step.** `session-loop/run-step!` appends `:step/started` before any
   provider work so live observers can see the step in flight.

3. **Assemble the request.** `adapter.request/build-request` reads the current view,
   prunes compacted history, hydrates payload refs, maps `:observation` messages to
   adapter-facing `:user` messages, assembles the system text, inserts any transient
   dynamic SDK surface request context, and attaches cache metadata.

4. **Call the adapter under one deadline.** The step calls the adapter through the
   adapter port inside `with-deadline`. Streaming token deltas, when enabled, are
   transient live signals only.

5. **Finalize the assistant side of the step.** The loop appends the assistant
   message and `:step/put`.

6. **Run the kernel batch.** The kernel extracts fenced Clojure blocks, evaluates
   them in the session's SCI ctx, appends one `:eval/added` per block, and stops the
   batch on the first error or `FINAL`.

7. **Append the observation.** The loop renders one combined observation from the
   eval records and appends it as the next message.

8. **Either continue or settle.**
   - No `FINAL`: loop to the next step.
   - `FINAL`: snapshot vars, append `:session/vars-snapshotted`, commit the final
     `:turn/put`, publish a new immutable current head, hydrate the final value, and
     return the `TurnResult`.
   - Error, timeout, stop, or budget exhaustion: append a final `:turn/put` with the
     terminal status and return a non-`final` `TurnResult`.

## One recursive call, end to end

The recursion layer preserves the same session model instead of inventing a second one.
Children and attached children are new branches in the same durable state graph.

- **`lm` / `map-lm`** are leaf calls. They do not create child sessions, heads, or
  lineage. They are bounded adapter calls using the leaf prompt and honest failure
  sentinels for partial fan-out.

- **`rlm` / `map-rlm`** spawn fresh child sessions in the same store through
  `session/spawn-child!`. Each child gets its own SCI ctx, cache id, turn loop, and
  published heads. When the child reaches `FINAL`, the parent receives an envelope
  and records an invocation lineage edge to the child's immutable head.

- **`attach-rlm`** resolves a selected source session/head, spawns a fresh derived
  child through `session/spawn-attached!`, restores the source head's vars snapshot
  into that child, runs one task, returns the usual envelope, and records a
  derivation edge. The source session and source head do not advance.

## Global invariants

1. **Stable public surface.** `fractal.engine.api` stays the SDK surface across both
   harness modes. Recursion and durability extend behavior without changing the API
   shape.

2. **No magic context object.** Model code gets ordinary Clojure plus injected host
   fns. Host dynamic vars used for turn/step/eval bookkeeping are internal only and
   are not part of the model contract.

3. **Surfaces are declared, namespaced, and gated.** The built-in functions remain
   the harness surface. SDK surfaces can add qualified host functions only when
   configured by the embedder and granted by `:surface/fns`; they never create
   hidden context vars or extend `clojure.core`.

4. **Small kernel.** The kernel owns SCI mechanics, not session composition,
   storage, lineage, or provider policy.

5. **Port-only orchestration.** The loop talks to the adapter and `SessionStore`
   ports only. It never reaches into SQLite, BlobStore, or provider SDK details.

6. **Canonical durability.** The canonical durable backend is SQLite for the event
   log plus a global BlobStore for payload bytes. `MemoryStore` is the same semantic
   contract used for tests and minimal runs, not a different architecture.

7. **Events carry results, never recipes.** Folding the durable event stream
   reconstructs the session view without re-running a provider call or eval.

8. **Persist before fold.** The store stamps ids, durably persists the event, folds
   it into the in-process view cache only on success, then emits live notifications.

9. **Current head is authoritative.** Finalizing a turn publishes a content-addressed
   immutable head and updates the session's current-head pointer. Resume and attach
   restore from published heads rather than reconstructing state from looser
   projections when a head exists.

10. **Lineage is durable data.** Invocation and derivation edges are content-addressed
   facts in the store, not inferred after the fact.

11. **State-graph integrity matters over time.** Published heads, durable lineage
    edges, and head-based resume/attach semantics preserve auditable continuity
    across operator steering, branching, and restart.

12. **Attach is additive, not mutating.** `attach-rlm` creates a fresh target
    session and never advances the source session or source head.

13. **Filesystem paths are backend details only.** The logical identity model is
    session ids, payload ids, head ids, and lineage-edge ids. On-disk directories
    are physical storage layout.

14. **Single writer per session.** The store lock serializes appends; the turn lock
    serializes active turns. Live reads avoid both.

15. **Honest accounting.** Usage, cost, and cache values stay `:unknown` when the
    provider did not report them.

## Namespace layout

| Namespace | Responsibility |
|-----------|----------------|
| `fractal.engine.api` | Public SDK surface: config, lifecycle, reads, payload hydration, live subscribe helper, fake responder re-export. |
| `fractal.engine.session` | Sole composition root. Builds/resumes sessions, chooses store backend, builds adapters, resolves profiles/providers, assembles host fns, owns the turn lock, and spawns child or attached sessions. |
| `fractal.engine.session-loop` | Turn and step execution spine, terminal turn commit/finalization, deadline ownership, assistant and observation appends, current-head publication. |
| `fractal.engine.recursion` | `lm`, `map-lm`, `rlm`, `map-rlm`, `attach-rlm`, fan-out behavior, envelopes, and lineage-edge recording. |
| `fractal.engine.surface` | SDK surface descriptor validation, public stamps, stable/dynamic prompt rendering, and namespaced SCI function assembly. |
| `fractal.engine.kernel` | SCI ctx construction, block extraction, eval-batch semantics, `FINAL`, `inspect`, snapshot-vars, and restore-vars. |
| `fractal.engine.observe` | Fit-or-stub rendering, `inspect` text, observation assembly, eval and final previews. |
| `fractal.engine.store` | `SessionStore` protocol, session-view fold, event taxonomy, head helpers, lineage-edge helpers, ref auditing. |
| `fractal.engine.store.memory` | In-memory `SessionStore` implementation. |
| `fractal.engine.store.sqlite` | Durable `SessionStore` implementation backed by SQLite event storage and a shared BlobStore. |
| `fractal.engine.store.blobstore` | File-based global content-addressed payload backend used by the SQLite store. |
| `fractal.engine.payload` | Pure payload hashing and tagged-ref helpers. |
| `fractal.engine.payload-io` | Store-coupled intern/hydrate behavior over payload refs. |
| `fractal.engine.adapter` | Adapter port and call-record shapes. |
| `fractal.engine.adapter.fake` | Deterministic offline adapter. |
| `fractal.engine.adapter.sdk` | Provider-backed adapter implementation. |
| `fractal.engine.adapter.request` | Request assembly from the current view, compaction boundary, overlays, and cache metadata. |
| `fractal.engine.capability` | Capability profiles, clamp/validation, and SCI option assembly. |
| `fractal.engine.compaction` | Context assessment and transcript compaction. |
| `fractal.engine.live` | Live dispatch, transient notifications, backlog replay helpers, progress derivation. |
| `fractal.engine.config` | Config normalization and validation. |
| `fractal.engine.cache` | Cache-scope and cache-option helpers. |
| `fractal.engine.prompt` | Harness, leaf, and child prompt doctrine. |
| `fractal.engine.catalog` | Static model/provider metadata lookup. |
| `fractal.engine.concurrent` | Deadline and bounded-fanout primitives. |
| `fractal.engine.time` | Small time helpers. |

## Dependency manifest

The namespaces form a strict DAG.

| Layer | Namespaces | Notes |
|-------|------------|-------|
| **L0** | `time`, `payload`, `concurrent`, `catalog` | Pure or engine-free foundations |
| **L1** | `store`, `capability`, `surface`, `prompt`, `cache`, `observe`, `live` | Core contracts and shared services |
| **L1.5** | `payload-io`, `store.memory`, `store.blobstore`, `store.sqlite` | Store-coupled helpers and backends |
| **L2** | `adapter`, `adapter.fake`, `adapter.sdk`, `kernel` | Execution primitives |
| **L3** | `adapter.request`, `compaction`, `session-loop` | Request assembly and loop support |
| **L4** | `config`, `recursion`, `session` | Composition roots and recursion behavior |
| **L5** | `api` | Public surface |

Two edges matter most:

- **`kernel -> store`** is deliberate: the kernel appends durable eval facts but does
  not own any higher-level session flow.
- **`session -> recursion` with injected closures back into recursion** is deliberate:
  recursion never requires `session`, so the graph stays acyclic even though child
  spawning and running are composition-root responsibilities.

## Deliberately not expanded further in v1

- No separate public fork-session API yet, even though branching semantics exist
  through published heads and child sessions.
- No secondary graph database or query index beyond the event log plus
  content-addressed payload store.
- No provider billing or accounting policy as a core architectural component;
  the governor controls execution, not accounting.
- No provider-specific logic above the adapter seam.
