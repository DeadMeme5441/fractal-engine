# fractal-engine

A small **recursive language-model compute engine**. A model drives a persistent
Clojure REPL: it writes fenced Clojure, the host evaluates it and returns a compact
observation, and the loop repeats until the model calls `(FINAL value)`. Some of the
functions the model can call are themselves language models — so a problem can be
*decomposed* into sub-problems, each solved by a fresh recursion of the same loop.

It is a from-scratch JVM/Clojure engine built in the spirit of
[Recursive Language Models](https://github.com/alexzhang13/rlm) (Alex Zhang et al.):
instead of stuffing everything into one context window, the model **offloads its
input into a REPL it can program against and call sub-models inside of**. See
[Relevant reading](#relevant-reading).

```clojure
;; the model writes this; the host evaluates it and feeds back the result
(def files (->> (file-seq (io/file "src")) (filter #(.endsWith (str %) ".clj")) (mapv str)))
(def summaries (map-lm files "Summarize this file's responsibility in one line." :string))
(FINAL {:n (count files) :summaries summaries})
```

---

## Table of contents

- [Why](#why) · [How the loop works](#how-the-loop-works) · [The six functions](#the-six-functions)
- [Install](#install) · [Quickstart](#quickstart)
- [The `fractal` CLI](#the-fractal-cli) — drive the engine and read what it did
- [Live providers](#live-providers) · [Artifacts & the journal](#artifacts--the-journal)
- [The trust layer](#the-trust-layer) · [Resume & fork](#resume--fork) · [Sandboxing](#sandboxing)
- [Architecture](#architecture) · [Anti-goals](#anti-goals) · [Relevant reading](#relevant-reading)
- Deep docs: [`docs/CONCEPTS.md`](docs/CONCEPTS.md) · [`docs/CLI.md`](docs/CLI.md) · [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

---

## Why

Large contexts are expensive and lossy. The usual fix is retrieval; the recursive-LM
fix is different: **give the model a programming environment and let it decide how to
read its own input.** It can parse and slice with ordinary code, judge bounded pieces
with cheap model calls, and hand whole sub-problems to fresh recursions — spending the
expensive, powerful processing only where the work actually needs it.

`fractal-engine` is the smallest engine that makes that real:

- **One loop, used everywhere.** The root, every child, and every leaf run the *same*
  evaluate-observe-until-`FINAL` loop. There is no separate "planner" or "executor."
- **Three kinds of processing, one shape.** Everything is `input -> processing ->
  output`; only the *kind* of processing varies — deterministic (plain Clojure),
  probabilistic (a leaf, whose body is a model), recursive (a child, which runs this
  whole loop on a sub-problem).
- **Results, not recipes.** Every step is recorded as an event in an append-only
  journal. Re-reading a run never re-runs a model call.
- **A trust layer.** A model's answer is a *claim*. The engine can check the claim's
  cited evidence against the actual source files — first with a free grep, then, if you
  ask, by handing the claim back to the engine to adversarially re-verify.

It is deliberately tiny. Storage, retrieval, workflows, and product surfaces live in
*layers around* the kernel — never in it (see [Anti-goals](#anti-goals)).

## How the loop works

1. Conversation messages are the model's working transcript.
2. The model replies with one or more fenced ` ```clojure ` blocks.
3. The host evaluates them, in order, in a **persistent namespace** (vars survive
   across steps and turns).
4. The host appends a compact **observation** message (values are projected, not dumped).
5. Steps repeat until the model calls `(FINAL value)`, which ends the **turn** and
   returns `value`.

A session is long-lived. `(FINAL value)` completes the current turn; it does **not**
end the session — the same history, REPL vars, and cache scope are available to the
next turn. That is what makes a session a *persistent brain you can keep talking to.*

## The six functions

The entire model-facing surface is six functions, plus ordinary Clojure:

| function | kind | what it does |
|---|---|---|
| `(FINAL value)` | — | end the current turn and return `value` to the caller |
| `(lm input query [mode])` | probabilistic | one bounded input → one model judgment (`:string`/`:edn`) |
| `(map-lm inputs query [mode])` | probabilistic | `lm` mapped over up to 50 inputs, in parallel |
| `(rlm task)` | recursive | run this whole loop on a sub-problem; returns its `FINAL` |
| `(map-rlm tasks [shared])` | recursive | `rlm` over up to 50 independent sub-problems, in parallel |
| `(attach-rlm path task [opts])` | recursive | resume a prior session as a child, then run `task` |

A **leaf** (`lm`/`map-lm`) is the non-recursive base case: a pure function whose body
happens to be a model. A **child** (`rlm`/`map-rlm`) is a full recursion of the loop.
There is **no magic `context` variable** — working state lives in REPL vars the model
defines with `def`. See [`docs/CONCEPTS.md`](docs/CONCEPTS.md) for the model in depth.

---

## Install

Requires a JVM (21+) and the [Clojure CLI](https://clojure.org/guides/install_clojure).
Native-image is intentionally **out** (the engine evaluates code at runtime); we ship an
uberjar.

```bash
git clone https://github.com/DeadMeme5441/fractal-engine.git
cd fractal-engine
clojure -T:build uber          # -> target/fractal.jar  (lean: engine + cheshire, no UI deps)

# put `fractal` on your PATH (optional)
ln -s "$PWD/bin/fractal" ~/.local/bin/fractal
fractal help
```

`bin/fractal` runs the jar from any working directory and falls back to running from
source (`clojure -M -m fractal-engine`) if the jar isn't built. You can always invoke
the engine directly without building:

```bash
clojure -M -m fractal-engine <verb> ...
```

## Quickstart

No API keys needed — the default provider is an **offline scripted** fake:

```bash
# drive: run a scripted task end to end
fractal run "Define x and return it." --fake-script simple
#   run session-…  ● final   {:answer 42}
#   next: fractal show session-…   ·   fractal verify session-…

# read: inspect what happened
fractal show <run>            # node detail: steps, leaves, children, final
fractal tree <run>            # the whole addressable run tree
```

Run the tests:

```bash
clojure -M:test
```

---

## The `fractal` CLI

`fractal` is the engine's use surface — modeled on tools like `bd`: short verbs, a
positional address instead of flag ceremony, run-name resolution, and output that
prints the next command to run. **One grammar covers both halves of the loop — driving
the engine and reading what it did.** Every verb takes `--json`; exit codes mean
something. Full reference: [`docs/CLI.md`](docs/CLI.md).

### Drive (do work)

```bash
fractal chat [run]            # talk to a persistent, resumable session (the "brain")
fractal run    "<task>"       # one-shot; prints a run handle to chain into a read verb
fractal resume <run> "<task>" # continue a saved session from its snapshot
fractal fork   <run> "<task>" # branch a session at a turn
```

`fractal chat` is the headline. It holds one live session and runs each message as a
turn — REPL vars persist in memory, the journal grows, and a live `◐ thinking…` line
shows children/steps/leaves as the engine works. Leave with `/quit`; come back with
`fractal chat <run>`.

### Read (look inside its head)

```bash
fractal show   <run> [node]   # node detail — the hub; node defaults to root
fractal tree   <run>          # the full addressable run tree
fractal prime  <run>          # compact "what is this run"
fractal ls                    # list runs
fractal verify <run> [node]   # claim-vs-evidence (the confabulation backstop)
fractal trace  <run> [node]   # claim provenance
fractal cost   <run>          # spend breakdown
fractal leaves <run> [node]   # leaf inputs/outputs
fractal step   <run> [node] N # one step, in full
fractal stream <run>          # journal events as JSONL
```

A **node address** is `root`, `child-0001`, or `child-0001/child-0004` — the leading
`root/` is implied. Drilling is just following the addresses a node view prints for its
children. A `<run>` is a path (`runs/foo`) or a bare name resolved under the runs dir.

**Exit codes:** `0` final · `1` error · `2` no-final · `3` timeout · `5` confabulation
suspected. So you can gate on them: `fractal verify <run> --deep --root . && deploy`.

---

## Live providers

Provider calls go through [`clojure-llm-sdk`](https://github.com/DeadMeme5441/clojure-llm-sdk).
Select provider and model per role (root / leaf / child can differ — a common, cheap
split is a strong root with cheaper children and leaves):

```bash
fractal run "Map this repo's subsystems with evidence." \
  --provider vertex-gemini       --model gemini-3.1-pro-preview \
  --leaf-provider vertex-gemini  --leaf-model gemini-3.1-flash-lite-preview \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --max-turns 15 --call-timeout-ms 120000
```

Credentials come from environment variables (an ignored `.env` is read for local dev —
never commit secrets). Two non-obvious provider facts:

- **Codex OAuth** is the provider keyword `codex-backend` (plain `codex` is the API-key
  path); it reads `~/.codex/auth.json`.
- **Vertex Gemini** (`vertex-gemini`) needs `GOOGLE_CLOUD_PROJECT` and
  `GOOGLE_CLOUD_LOCATION` **exported into the JVM environment** (the `.env` loader does
  not push them to `System/getenv`) plus Application Default Credentials.

> ⚠️ **Root-model strength is decisive.** A weak root will confabulate past its own
> correct observations. Don't put a mini model at the root when you care about the
> answer.

> ⚠️ **Live runs cost money and can hang.** There is no engine-level budget/timeout
> governor yet. Always leash live runs: `--call-timeout-ms`, `--max-turns`,
> `--max-fanout`, and run them in the background with monitoring.

## Artifacts & the journal

Every session writes a directory. The **source of truth** is `events.ednl`, an
append-only event log (one EDN form per line); everything else is a **projection** of
it, materialized at turn boundaries for convenient reading.

```text
runs/<session-id>/
  events.ednl        # append-only journal — the source of truth
  session.edn  messages.edn  turns.edn  evals.edn  calls.edn  snapshots.edn
  final.edn  usage.edn  tree.edn        # derived views
  blobs/                               # large values, referenced by SHA-256
  children/child-0001/ …               # child sessions, same shape, recursively
```

The load-bearing invariant is **results, not recipes**: an event carries the *outcome*
of work (inline, or a blob ref for large values), so folding the journal reconstructs
the full state without ever re-invoking a model. Reading, resuming, and inspecting are
all pure folds that cost zero provider calls. Mid-turn, the journal is authoritative
(the `.edn` projections are only rebuilt at boundaries) — which is why `fractal`'s read
verbs fold `events.ednl` rather than trusting the projections.

## The trust layer

A model's `FINAL` value is a **claim**, not a fact. `fractal verify` makes claims
auditable in two layers:

1. **Grep floor (free, default).** Extract the code symbols a claim cites as evidence
   and check they actually occur in the cited file. Catches a fabricated citation
   instantly. Cited paths are resolved against `--root <repo>`.
2. **Engine judge (`--deep`).** Hand the claims *back to the engine* as a fresh task —
   "read the cited code and decide whether it genuinely supports the claim; spawn a
   child or use leaves, your call; be adversarial." The engine reads the real source and
   returns `:supported` / `:refuted` per claim.

The floor and the judge are complementary: grep answers *"are the cited symbols real?"*;
the judge answers *"does the code actually mean what's claimed?"* In practice the judge
catches confabulations the grep waves through (a claim can cite real symbols and still
misread what they do). Details in [`docs/CONCEPTS.md`](docs/CONCEPTS.md#the-trust-layer).

```bash
fractal verify <run> child-0001 --root /path/to/repo          # grep floor
fractal verify <run> child-0001 --root /path/to/repo --deep \
  --provider vertex-gemini --model gemini-3.1-pro-preview     # + engine judge
```

## Resume & fork

Snapshots are written at graceful turn boundaries: message history plus EDN-safe REPL
vars (non-EDN values are recorded as unresumable, never silently dropped). `resume`
restores that state into a fresh namespace, reinstalls the runtime functions, and runs
a new turn. `fork` does the same into a new directory, branching the lineage.

```bash
fractal resume <run> "Use the var you defined and FINAL the result."
fractal fork   <run> "Try a different approach." --turn 2
```

## Sandboxing

The engine evaluates model-generated Clojure in a live JVM — it can read files, spawn
subprocesses, and use the network. That power is the point (it is how a node inspects a
codebase and reaches its provider), but it means **you must treat the model's code as
code you are running locally.**

There is no true in-process sandbox on a modern JVM: the `SecurityManager` was disabled
in JDK 24 ([JEP 486](https://openjdk.org/jeps/486)), and an interpreter sandbox (e.g.
SCI) can't preserve the engine's real-var / snapshot model. Isolation is therefore a
**process/OS-level** concern. `bin/fractal-sandboxed` runs the engine under a
best-effort OS sandbox:

- **macOS** — `sandbox-exec` with `sandbox/macos.sb` (Seatbelt). Tested.
- **Linux** — `bwrap` (bubblewrap). Written but untested; review before relying on it.

**It is a safety net, not a prison:** reads/subprocesses/compute/network stay open;
only **writes** are confined to the run workspace and temp. It does **not** filter the
network — a node that reads a file can send it to your provider (its purpose) or
elsewhere. For untrusted inputs, use a hardened tier: a network-filtering sandbox such
as [Anthropic's sandbox-runtime](https://github.com/anthropic-experimental/sandbox-runtime)
(allowlist the provider domain) or a container with a locked-down network namespace.

## Architecture

The codebase is decomplected by concern; only the compute engine lives in core
namespaces. Full map in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

| layer | responsibility | namespaces |
|---|---|---|
| compute engine | the agent + persistent REPL loop, fanout, child/attach | `process` `runtime` `prompt` `concurrent` `call` |
| journal & projections | append-only events + pure folds into views | `journal` `event` `artifacts` |
| persistence | snapshot / restore / resume / fork / lineage | `snapshot` `resume` `session` |
| provider | the LLM adapter boundary | `provider` |
| read surface | journal-folding projection + the trust layer | `projection` `provenance` `render` |
| product | the `fractal` CLI | `agentcli` `cli` |

## Anti-goals

The core runtime does **not** include persistent-memory databases, vector search,
workflow templates, task schemas, repository analyzers, MCP-server concepts, a web UI,
deterministic planner layers, or hidden convenience functions. Those belong in layers
*around* the kernel. The model-facing surface is exactly the six functions — kept small
on purpose.

## Relevant reading

- **Recursive Language Models** — Alex Zhang et al.: the idea this engine is built in
  the spirit of (REPL-as-context, recursive self-calls).
  [Repo](https://github.com/alexzhang13/rlm).
- `AGENTS.md` — the design boundary and invariants for contributors and agents.
- `docs/CONCEPTS.md`, `docs/CLI.md`, `docs/ARCHITECTURE.md` — deep dives.

## License

[Apache-2.0](LICENSE).
