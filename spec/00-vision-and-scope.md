# 00 · Vision and Scope

## What fractal-engine is

A small recursive language-model compute engine. A model gets a persistent Clojure
REPL, writes fenced Clojure, the host evaluates it, feeds back one compact
observation, and repeats until the model calls `(FINAL value)`. The session then
stays live for later turns with its vars intact.

The core claim is long-context, long-horizon work. Working state persists across
many steps and turns, survives restart via durable reopen, can branch into children,
and can be revisited through immutable published heads.

v1 ships two harness modes under the same public API:

- `:clojure`: the plain durable session harness (`FINAL` + `inspect`)
- `:rlm`: the recursive harness (`FINAL`, `inspect`, `lm`, `map-lm`, `rlm`,
  `map-rlm`, `attach-rlm`)

The engine is not a transcript-first chat loop. It is a programmable working-state
engine in which the model uses code, not prompt prose, to decide what to read,
compute, delegate, branch, resume, and return.

## Why this shape

Large contexts are expensive and lossy. Long-horizon work also needs continuity:
state that survives exploration, delegation, correction, interruption, and restart.
Instead of forcing every problem into one window, fractal-engine lets the model
operate a live REPL with durable state:

- deterministic work stays in Clojure
- bounded judgment can go to a leaf model call
- larger or uncertain subproblems can become child sessions
- prior work can be resumed from immutable heads rather than replayed from scratch
- a human operator can steer at the boundary while the agent executes inside the
  durable state graph

The model is the active operator inside the loop. The human operator supervises,
steers, and inspects from outside the loop.

The mental model is one loop:

```text
user -> llm -> ```clojure ...``` -> host evals -> observation -> llm -> ... -> (FINAL v) -> user
```

There is no separate "tool turn" path. A user message, an observation, and a child
result are all just material in the same message stream.

## Primary consumer and control plane

The primary consumer is an agent with CLI or shell access, supervised by a human
operator.

- The **API** is the core programmatic surface.
- A **CLI/readback seam**, when present, is a durable control plane over that API:
  JSON-first, non-interactive, resumable, governed, inspectable, and scriptable.
- Human-readable summaries, chronicles, and reports are secondary readbacks for
  operator steering; they do not replace machine-readable audit and control data.
- The engine itself is therefore a stateful compute substrate, not a
  consumer-facing terminal application.

## Current v1 status

All four planned architectural phases are now implemented.

| Phase | Shipped result | Status |
|-------|----------------|--------|
| 1 | Session core: SCI REPL loop, capability sandbox, in-memory store, adapter seam, public API, live query, compaction | built |
| 2 | Durable `SessionStore`: SQLite event log plus content-addressed BlobStore payloads, durable reopen via `resume-session!` | built |
| 3 | Recursive harness: `lm`, `map-lm`, `rlm`, `map-rlm`, fan-out, child-session spawning, capability inheritance/clamping | built |
| 4 | Durable recursion data model: immutable content-addressed heads, current-head publication, invocation and derivation lineage edges, `attach-rlm` | built |

This document therefore describes the shipped v1 architecture, not a partial Phase 1
proposal.

## The model-facing surface

| Fn | Available in | Meaning |
|----|--------------|---------|
| `(FINAL value)` | `:clojure`, `:rlm` | Emit the turn's output and end the turn. The session remains live. |
| `(inspect x)` | `:clojure`, `:rlm` | Print a bounded view of a value into the next observation. Returns `nil`. |
| `(lm input query [mode])` | `:rlm` | One bounded leaf-model call over one bounded input. `mode` is `:string` or `:edn`. |
| `(map-lm inputs query [mode])` | `:rlm` | Parallel leaf fan-out over up to `:max-fanout` inputs, preserving order and returning sentinels for failed slots. |
| `(rlm task)` | `:rlm` | Spawn a fresh child session, run its whole loop to `FINAL`, and return an envelope. |
| `(map-rlm tasks [shared])` | `:rlm` | Parallel child-session fan-out over independent tasks. |
| `(attach-rlm handle task [opts])` | `:rlm` | Restore a selected immutable source head into a fresh derived child, run one task, and return an envelope without advancing the source session. |

The contract is intentionally narrow:

- the model gets ordinary Clojure plus these host fns
- embedders may configure additional namespaced SDK surface functions, gated by
  explicit `:surface/fns` capabilities
- there is no magic context object, hidden mutable session map, or ambient handle
- host-internal dynamic vars used for bookkeeping stay internal to the engine

## The processing doctrine

Choosing the cheapest sufficient kind of processing is the core skill:

1. **Deterministic Clojure first.** Parsing, counting, joins, filtering, shape
   checks, exact aggregation, and validation belong here.
2. **Leaf calls second.** Use `lm` or `map-lm` only for bounded semantic judgment
   that Clojure cannot do directly.
3. **Child sessions third.** Use `rlm` or `map-rlm` when the work needs its own
   inspect/delegate loop, broader context, or naturally independent lanes.
4. **Reuse before recompute.** If the needed state already exists in a published
   head, prefer `attach-rlm` over rebuilding it.

## Durability and lineage doctrine

v1 is a long-horizon working-state engine, not just a transient recursive loop:

- The semantic storage seam is `SessionStore`.
- The canonical durable backend is a SQLite event log plus a global file BlobStore
  for content-addressed payloads.
- Large values are stored as payload refs; payload identity is content identity.
- A finalized turn snapshots REPL vars, commits the turn, and publishes a new
  immutable current head.
- `resume-session!` restores from the published current head when one exists.
- `rlm` and `map-rlm` record invocation lineage edges to child heads.
- `attach-rlm` records a derivation lineage edge from a selected source head to a
  fresh target head and does not mutate the source session.
- Filesystem paths under the durable store are physical backends only. The logical
  API is session ids, payload ids, head ids, and lineage-edge ids.

## Anti-goals

- **Not a transcript-first chat loop or end-user CLI shell.** `FINAL` returns the
  value the caller consumes; CLI concerns are control-plane concerns above the API.
- **Not a provider SDK.** Provider-specific networking stays behind the adapter seam.
- **Not a hidden-context runtime.** The model does not program against a secret
  engine object or context var.
- **Not a filesystem-shaped API.** Paths are storage implementation details, not the
  logical state model.
- **Not a billing/accounting subsystem.** The built governor controls execution:
  per-turn limits, per-call deadlines, fan-out caps, compaction boundaries, leaf
  concurrency, and honest usage/cost observability when providers report it.
- **Not an alternate graph database design in v1.** The durable truth is the
  SQLite event log plus content-addressed payloads unless a concrete future query
  need justifies more.

## System boundary

```text
human operator
  steers through reports, chronicles, and readback

agents with shell access
  execute through CLI/control plane and API

fractal-engine
  public API
  durable session lifecycle and loop
  recursion, branching, heads, lineage
  SessionStore port
  adapter port

SessionStore backends:
  - MemoryStore
  - SQLite event log + BlobStore

Adapter backends:
  - fake adapter
  - provider-backed adapter
```

The engine owns the recursive compute model, durable working state, and state-graph
integrity. Provider calls and physical storage backends stay below stable ports.

## What v1 scope includes today

- Session lifecycle, async and blocking turns, live query, and compaction
- Capability-gated SCI evaluation with durable per-session vars
- Memory and SQLite storage under one `SessionStore` contract
- Content-addressed payload storage and payload hydration
- Durable reopen via `resume-session!`
- Recursive leaf and child host fns with capability inheritance and clamping
- Immutable heads, current-head publication, durable lineage edges, and
  `attach-rlm`
- A stable public API that works for both harness modes

## Still outside this high-level spec

- A separate public fork-session lifecycle API
- Provider billing or accounting policy as a core runtime concern
- Secondary indexes or alternate durable stores beyond the SQLite-plus-blob model
- Packaging and deployment hardening choices beyond the core engine contract
