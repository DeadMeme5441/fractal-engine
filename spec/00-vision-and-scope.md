# 00 · Vision and Scope

## What fractal-engine is

A small **recursive language-model compute engine**. A model is given a *programming
environment* — a persistent Clojure REPL — and drives it: it writes fenced Clojure,
the host evaluates the code and feeds back one compact **observation**, and the loop
repeats until the model calls `(FINAL value)` to return a result. Some of the
functions the model may call are *themselves* language models, so a hard problem can
be decomposed into sub-problems, each handed to a fresh recursion of the same loop.

## Why this shape (the RLM thesis)

Large contexts are expensive and lossy. Instead of stuffing everything into one
window, fractal-engine lets the model **decide how to read its own input** — slicing
with ordinary code, judging bounded pieces with cheap model calls, and handing whole
sub-problems to fresh recursions. The model is an *operator*, not a chat partner. It
is built in the spirit of Recursive Language Models (Alex Zhang et al.).

The single mental model to hold: **it is a coding-harness agent loop whose only tool
is "evaluate this Clojure in a durable REPL."**

```
user → llm → ```clojure …``` → host evals → observation → llm → … → (FINAL v) → user
```

There is **no separate "user turn" vs "tool-result turn"** code path. Every step is
identical: take the whole message list, ask the model for the next assistant message,
evaluate its code, append the result as the next message, repeat. The user's task is
just `message[0]`; an observation is just `message[n]`. **`FINAL` is the model's reply
to the user** — it ends a turn and hands control back; the next user message starts
the next turn on the same live session.

## The model-facing surface — the six functions

Inside a session's REPL the model has ordinary Clojure (a curated, sandboxed subset —
see `04`) plus a small set of host-injected functions. The full set is six; **Phase 1
ships only the first two.**

| Fn | Kind | Phase | Meaning |
|----|------|-------|---------|
| `(FINAL value)` | — | **1** | Emit the turn's output and end the turn. The value is the reply. The session stays live for later turns. |
| `(inspect x)` | — | **1** | Print a bounded, paginated view of a value (so the model can look inside a stubbed value). Returns `nil`. |
| `(lm input query [mode])` | leaf | 3 | One bounded input → one model-judged output. A pure function whose body is a model. `mode` ∈ `:string`/`:edn`. |
| `(map-lm inputs query [mode])` | leaf | 3 | `lm` mapped over ≤50 bounded inputs in one parallel fan-out, order preserved. |
| `(rlm task)` | child | 3 | Hand one sub-problem to a fresh RLM session running this whole loop. Returns an *envelope*, not a bare value. |
| `(map-rlm tasks [shared])` | child | 3 | Recursion mapped over ≤50 independent sub-problems. |
| `(attach-rlm handle task [opts])` | reuse | 4 | Reuse a prior session — continue its current head, or branch from an immutable head. |

> Phase 1's REPL therefore exposes **plain Clojure + `FINAL` + `inspect`**, nothing
> else. The four model-calling functions are added in Phase 3 as host fns injected
> into the same SCI context; they require nothing new from the loop (see `03`, `11`).

## The cheapness hierarchy (behavioural doctrine)

The system prompt (see `12`) trains a strict order — *choosing the cheapest sufficient
kind of processing for each transformation is the entire skill*:

1. **Deterministic Clojure** — the base default. IO, parsing, regex, counting,
   sorting, grouping, joining, shape checks. If Clojure can compute it, nothing else
   should.
2. **A leaf** (`lm`/`map-lm`) — one probabilistic transformation over an
   *already-bounded* input. Only when genuine semantic judgement is needed.
3. **A child** (`rlm`/`map-rlm`) — a full recursion, only when a surface is too large
   or uncertain for the current step budget, needs its own inspect/judge loop, or has
   genuinely independent lanes.

When in doubt, collapse to the cheaper kind.

## Anti-goals

- **Not a chat agent.** The model produces *the value the caller consumes*, via
  `FINAL` — not prose for a transcript.
- **The engine never speaks HTTP.** All provider access goes through the sibling
  `clojure-llm-sdk`. The engine's only network seam is the adapter (`05`).
- **No spending governor / no step budget beyond `max-steps`/`max-turns`.** Budget
  enforcement, if needed, is an external concern (as the v1 evals harness did it).
- **Storage is never woven into the loop.** The loop talks to a port (`02`).
- **The REPL is not "full JVM + OS sandbox as the only line".** Capability is
  controlled at the language layer via SCI; the OS sandbox is a backstop, not the
  primary boundary (`04`).

## The two-repo stack

fractal-engine sits on top of **`clojure-llm-sdk`** (a sibling repo, the provider SDK:
one canonical API over many LLM providers — chat/embed/etc., with honest cost/cache
accounting). The engine depends on it (`net.clojars.deadmeme5441/clojure-llm-sdk`)
and uses **only its chat-completion text path**, narrowed behind the engine's own
adapter port. The SDK's richness (tools, modalities, streaming parts) stays *below*
the adapter boundary. See `05` and `08`.

```
┌────────────────────────────────────────────┐
│ fractal-engine  (this repo)                 │  recursive LM compute engine
│   public API = the SDK (fractal.engine.api) │
│   session loop · SCI eval kernel · store    │
└───────────────┬────────────────────────────┘
                │ adapter port (text-only chat)
┌───────────────▼────────────────────────────┐
│ clojure-llm-sdk  (../clojure-llm-sdk)       │  provider SDK — the only network seam
└─────────────────────────────────────────────┘
```

## Phases

| Phase | Deliverable |
|-------|-------------|
| **1** | **The session core ("clojure harness").** One non-recursive session: the REPL loop (plain Clojure + `FINAL`/`inspect`), the SCI eval kernel, the capability sandbox, the in-memory state port, the adapter (sdk + fake), the public API surface, config, live-query, and compaction. **This spec's primary target.** |
| 2 | Storage: a persistent `SessionStore` impl (SQLite + content-addressed blobs) under the same port. *Datahike keep/drop is an open decision.* |
| 3 | The RLM layer: the four model-calling host fns (`lm`/`map-lm`/`rlm`/`map-rlm`), leaf-vs-child distinction, fan-out concurrency. |
| 4 | The recursion data model: invocations/edges/lineage/envelopes; immutable heads; `attach-rlm`. |
| — (parallel) | The public API *is* the SDK; the "rlm harness" extends the same surface as Phases 3–4 land. |

## Phase-1 scope — IN / OUT

**IN (build this):**
- The uniform step loop + turn lifecycle (`01`, `07`).
- The SCI eval kernel: ctx-per-session, `FINAL` (exception signal), `inspect`
  (orchard), fenced-block extraction, batch eval, fit-or-stub observations (`03`).
- The capability sandbox: per-session profile, the named lattice, SCI config mapping
  (`04`).
- The event-sourced state port + `MemoryStore` (`02`).
- The adapter port + `SdkAdapter` + `FakeAdapter` (`05`).
- The public API surface (`fractal.engine.api`) (`06`).
- Config (`make-config`), single-writer concurrency, `run-turn!` / `run-turn-async!`,
  deadline/timeout/retry passthrough (`07`).
- The cache contract passthrough (`08`).
- Live-query: snapshots, `progress`, `subscribe!`/`events-since`, opt-in token
  streaming (`09`).
- Compaction: token-estimate the transcript vs the model window (`ceil(chars/4)` over
  hydrated `:message/content`, `:unknown-window-chars` fallback), then past `:compact-at`
  fold it to one continuation frame — the concrete mechanism in `07` §4.
- Offline testing via `FakeAdapter` + a RUNS/SEES dev harness (`10`).

**OUT (do not build in Phase 1):**
- The four model-calling fns (`lm`/`map-lm`/`rlm`/`map-rlm`) and `attach-rlm`.
- Any persistent storage (SQLite/blobs/Datahike) — Phase 2.
- Immutable heads + cross-session lineage + invocation edges — Phase 4.
- Cross-process resume/fork (an in-process resume may be exposed `^:alpha`; see `06`).
- Provenance/claim-checking, codebrain, the evals harness, a full CLEAN CLI — later /
  open keep-drop decisions (`11`).
- Native-image / babashka distribution — a *possible later* option SCI unlocks; not now.
