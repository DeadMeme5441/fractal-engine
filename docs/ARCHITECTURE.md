# Architecture

The codebase is **decomplected by concern**: each part changes for one reason, and only
the compute engine lives in core runtime namespaces. This is the map.

## The layers

```
                model-facing surface:  FINAL lm map-lm rlm map-rlm attach-rlm
                                              │
   ┌──────────────────────────────────────────────────────────────────────┐
   │ compute engine — the agent + persistent REPL loop                      │
   │   process · runtime · prompt · concurrent · call                       │
   └───────────────┬──────────────────────────────────────┬────────────────┘
                   │ emits events                          │ provider calls
   ┌───────────────▼───────────────┐          ┌────────────▼────────────────┐
   │ journal & projections          │          │ provider                    │
   │   journal · event · artifacts  │          │   provider (clojure-llm-sdk) │
   └───────────────┬───────────────┘          └─────────────────────────────┘
                   │ pure folds
   ┌───────────────▼───────────────┐   ┌────────────────────────────────────┐
   │ persistence                    │   │ read surface (this work)           │
   │   snapshot · resume · session  │   │   projection · provenance · render │
   └────────────────────────────────┘   └───────────────┬────────────────────┘
                                                         │
                                          ┌──────────────▼───────────────┐
                                          │ public use surfaces           │
                                          │   api · agentcli · cli        │
                                          └───────────────────────────────┘
```

**Guiding rule:** policy is data, mechanism is code, and the two are not braided. A
provider is a value. A leash limit is a value. Only the compute engine belongs in the
core; storage/memory/workflow/product concepts stay in layers around it.

## Namespaces

### Compute engine
- **`process`** — the loop: run a turn, evaluate the model's fenced Clojure, build
  observations, and drive `FINAL`. Also fanout dispatch and child/attach orchestration,
  plus the leaf system prompt.
- **`runtime`** — the eval kernel: evaluate code in a persistent namespace, project
  values into compact observations. Split from snapshotting so each is about one thing.
- **`prompt`** — the behavior prompt (the system prompt that teaches the model the loop
  and the six functions). Pinned by the `prompt-contract` test + a `prompt-version`.
- **`concurrent`** — parallel fanout for `map-lm` / `map-rlm`.
- **`call`** — the call value/seam shared across root/leaf/child/attach.

### Journal & projections (event sourcing)
- **`journal`** — the append-only event log `events.ednl`: O(1) append, never rewritten,
  tolerant of a truncated trailing line. Dependency-free; it only appends and reads.
- **`event`** — the **pure projector**. `apply-event` folds one event into a view;
  `fold` reconstructs a whole view from the stream. No IO, no provider/child/leaf calls.
- **`artifacts`** — emits events (assign id + timestamp, append, apply), materializes the
  `.edn` projection files at boundaries, and resolves value refs (inline / blob).

The load-bearing invariant: **the journal stores results, not recipes.** A `call-ended`
event carries the outcome (inline, or a blob ref for large values), so folding the
journal re-applies recorded outputs and never re-invokes a model. Crash recovery,
inspect, and resume are all pure folds that cost zero provider calls. The only thing
ever re-produced is work that never completed.

### Persistence
- **`snapshot`** — capture/restore EDN-safe REPL vars + lineage at turn boundaries;
  non-EDN values recorded as unresumable, not dropped. Session fingerprints.
- **`session`** — start / resume / fork a session object; the thing `run-turn!` runs on.
- **`resume`** — thin entry points over `session` for resume/fork.

### Provider
- **`provider`** — the only place that talks to a model, via
  [`clojure-llm-sdk`](https://github.com/DeadMeme5441/clojure-llm-sdk). Providers are
  values; the request transcript is stored under a ref (not inline) to keep the journal
  from growing O(n²).

### Read surface
- **`projection`** — pure recursive fold of `events.ednl` into an **addressable,
  recursively-navigable node tree** (`load-node` / `load-at` / `tree` / `node-dir`).
  Reads the journal, not the stale `.edn` projections, so it's correct mid-turn. Shared
  by the CLI today and the human TUI later.
- **`provenance`** — the trust layer: extract evidenced claims from a `FINAL` value;
  the deterministic grep floor (`check-claims`); and `verify-task`, which hands claims
  back to the engine for the `--deep` judge.
- **`render`** — pure text rendering of nodes, trees, verify reports, cost, and the
  live chat turn summary. Returns strings; never prints or exits.

### Public API
- **`api`** — the stable Clojure facade for external consumers. It wraps generic
  session drive (`config`, `start-session!`, `run-turn!`, `stop-session!`,
  `resume-session!`, `fork-session!`, `run-task!`), journal-backed reads
  (`load-node`, `load-at`, `tree`, `journal-events`), trust/provenance helpers, and
  provider auth data. It deliberately does not expose runtime eval internals or
  CLI-oriented rendering strings.

### Product
- **`agentcli`** — the `fractal` verb dispatch (drive + read), `--json`, exit codes, the
  live chat loop.
- **`cli`** — engine option parsing shared with drive verbs; legacy `inspect`/interactive
  paths.

### Support
- **`cache`** — request/response cache scopes and hashing.
- **`scripts`** — offline scripted-provider fixtures for `--fake-script` (and tests).
- **`time`** — clock seam.

## Why event sourcing

Before the refinement, one mutable atom held seven logs and `flush!` rewrote all seven
files (plus re-derived the child tree) on every event — O(n²) IO and a god-atom braiding
seven independent append-only logs. Event sourcing un-braids it:

- `events.ednl` is the single source of truth (O(1) append).
- messages/turns/evals/calls/snapshots are **projections** — pure folds over the journal.
- materialized `.edn` files are rebuilt at boundaries (turn end, session end), not per
  event; mid-turn durability comes from the journal.
- in-memory state is a *cache of a fold*, not the authority. Crash recovery = replay.

This is the biggest single simplicity *and* performance win in the engine, and it's why
the read surface can fold a live run's journal for accurate mid-turn progress.

## One traced call seam

Root, leaf, child, and attach all flow through one conceptual seam — *reserve ids, emit
`call-started`, run the thunk, emit `call-ended`/`call-failed`, record the result ref* —
so cross-cutting policy (tracing today; a governor and sandbox decorator later) attaches
in one place instead of being scattered across four bespoke call sites.

## Testing

`clojure -M:test` runs the suite (offline; the scripted provider needs no keys). Two
checks worth knowing: `prompt-contract` pins exact phrases and the `prompt-version` (and
forbids the substrings `context` / `product` / `storage` / `workflow` in the behavior
prompt), and the read-surface tests build a hermetic scripted root→child run in a temp
dir, so they never depend on real `runs/`.

## What's deliberately absent

No storage/retrieval data layer, no memory database, no workflow/task-template engine, no
repository-analysis API, no MCP concept in the kernel, no web UI. Those are layers around
the engine. See [`AGENTS.md`](../AGENTS.md) for the invariants that keep it that way.
