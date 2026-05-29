# fractal-engine

A small **recursive language-model compute engine**. A model drives a persistent
Clojure REPL: it writes fenced Clojure, the host evaluates it and returns a compact
observation, and the loop repeats until the model calls `(FINAL value)`. Some of the
functions the model can call are *themselves* language models — so a problem can be
decomposed into sub-problems, each solved by a fresh recursion of the same loop.

```clojure
;; the model writes this; the host evaluates it and feeds back the result
(def files (->> (file-seq (io/file "src")) (filter #(.endsWith (str %) ".clj")) (mapv str)))
(def summaries (map-lm files "Summarize this file's responsibility in one line." :string))
(FINAL {:n (count files) :summaries summaries})
```

**Why this shape?** Large contexts are expensive and lossy. Instead of stuffing
everything into one window, fractal-engine gives the model a programming environment
and lets it decide how to read its own input — slicing with ordinary code, judging
bounded pieces with cheap model calls, and handing whole sub-problems to fresh
recursions. It is built in the spirit of
[Recursive Language Models](https://github.com/alexzhang13/rlm) (Alex Zhang et al.).

---

## Table of contents

**Get it running:** [Requirements](#requirements) · [Install](#install) ·
[Quickstart (no API keys)](#quickstart-no-api-keys) · [Where runs live](#where-runs-live-fractal) ·
[The `fractal` CLI](#the-fractal-cli) · [codebrain](#codebrain--a-code-discovery-brain) ·
[Going live: providers](#going-live-providers) · [Troubleshooting](#troubleshooting)

**Understand it:** [How the loop works](#how-the-loop-works) · [The six functions](#the-six-functions) ·
[Artifacts & the journal](#artifacts--the-journal) · [The trust layer](#the-trust-layer) ·
[Resume & fork](#resume--fork) · [Sandboxing](#sandboxing) · [Architecture](#architecture) ·
[Anti-goals](#anti-goals) · [Relevant reading](#relevant-reading)

**Deep docs:** [`docs/CONCEPTS.md`](docs/CONCEPTS.md) · [`docs/CLI.md`](docs/CLI.md) ·
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) · [`docs/CODEBRAIN.md`](docs/CODEBRAIN.md)

---

## Requirements

You need two things, both free and cross-platform (macOS, Linux, Windows/WSL):

| | What | Check it | Get it |
|---|---|---|---|
| **JDK 21+** | A Java runtime (the engine evaluates Clojure on the JVM) | `java -version` | [Temurin](https://adoptium.net/), [Homebrew](https://formulae.brew.sh/formula/openjdk) `brew install openjdk@21`, or your package manager |
| **Clojure CLI** | The `clojure` / `clj` build+run tool | `clojure --version` | [clojure.org/guides/install_clojure](https://clojure.org/guides/install_clojure) |

There is no native binary on purpose — the engine compiles and runs model-generated
code at runtime, so it ships as a JVM uberjar. Tested on OpenJDK 21 and Clojure CLI
1.12.

## Install

The engine runs on any platform with a **JDK 21+** — it ships as a single
self-contained uberjar (a native binary is out: the core loop `eval`s
model-emitted Clojure at run time, which needs the JVM and the Clojure compiler
present).

### Download the prebuilt jar (no build)

Grab `fractal.jar` from the [latest release](https://github.com/DeadMeme5441/fractal-engine/releases/latest):

```bash
mkdir -p ~/.local/bin
curl -fsSL -o ~/.local/bin/fractal.jar \
  https://github.com/DeadMeme5441/fractal-engine/releases/latest/download/fractal.jar

# run it directly…
java -jar ~/.local/bin/fractal.jar help

# …or drop a tiny wrapper on your PATH so `fractal` just works:
printf '#!/usr/bin/env bash\nexec java -jar "$HOME/.local/bin/fractal.jar" "$@"\n' > ~/.local/bin/fractal
chmod +x ~/.local/bin/fractal
fractal help
```

### Build from source

Clone, build the uberjar, and put `fractal` on your `PATH`:

```bash
git clone https://github.com/DeadMeme5441/fractal-engine.git
cd fractal-engine

clojure -T:build uber            # -> target/fractal.jar   (prints: built target/fractal.jar)

# put `fractal` on your PATH (any dir on your PATH works; ~/.local/bin is common)
mkdir -p ~/.local/bin
ln -sf "$PWD/bin/fractal" ~/.local/bin/fractal

fractal help                     # should print the verb list
```

`bin/fractal` prefers the built jar (fast startup; runs in whatever directory you
invoke it from) and resolves symlinks, so a `fractal` symlink anywhere on your `PATH`
still finds the repo. If the jar isn't built it transparently falls back to running
from source.

**Prefer not to build?** Skip the uberjar and run the engine straight from source
(slower startup, and it runs from the repo directory):

```bash
clojure -M -m fractal-engine <verb> ...      # e.g. clojure -M -m fractal-engine help
```

## Quickstart (no API keys)

The default provider is an **offline scripted fake** — no keys, no network, no spend.
Run a scripted task end to end:

```bash
fractal run "Define x and return it." --fake-script simple
```

You should see something like:

```text
run session-3269ffb5-65ba-41c8-921b-c4a1d8beebd8
● {:answer 42}   $? · 2 calls
  next: fractal show session-3269ffb5-65ba-41c8-921b-c4a1d8beebd8   ·   fractal verify session-3269ffb5-65ba-41c8-921b-c4a1d8beebd8
```

That is a full run: the green `●` means the model reached `(FINAL …)`, `{:answer 42}`
is the final value, and `$? · 2 calls` is the spend summary — `$?` because the
scripted provider has no real pricing (a live run shows a dollar amount here).

**Did it work?** Two checks:

```bash
ls .fractal/                # a run directory appeared here (see "Where runs live")
fractal ls                  # ○ session-…  s2 c0 final
```

Now look inside what it did:

```bash
fractal show <run>          # node detail: steps, leaves, children, final
fractal tree <run>          # the whole addressable run tree
```

`<run>` is the `session-…` name printed above (or copy it from `fractal ls`).
`fractal show` prints the run's steps — the exact Clojure the model wrote and the
observation the host fed back at each step — ending in the `FINAL` value.

Run the test suite to confirm a healthy checkout (all offline, no keys):

```bash
clojure -M:test                # Ran 40 tests containing 424 assertions. 0 failures, 0 errors.
```

Other offline scenarios are available via `--fake-script`: `simple`, `lm`, `map-lm`,
`rlm`, `map-rlm`, `multi-turn-chat`, and more (see `src/fractal_engine/scripts.clj`).

## Where runs live: `.fractal/`

Every session writes a directory. Like `git` and `bd`, `fractal` keeps its data in a
**`.fractal/` directory in the directory you invoke it from**, and finds it the same
way git finds `.git`:

- If a `.fractal/` already exists in the current directory **or any ancestor**, that
  one is reused — so running from a subdirectory still lands in the project's runs.
- Otherwise a fresh `.fractal/` is created in the current directory on first write.

So `cd ~/some-project && fractal run "…"` just works, and keeps that project's runs
with that project. Point it somewhere else for a single command with `--runs-dir`:

```bash
fractal run "…" --runs-dir /tmp/scratch-runs       # write here instead of ./.fractal
fractal ls       --runs-dir /tmp/scratch-runs       # read from there too
```

`.fractal/` is git-ignored by default. A `<run>` argument is either a path
(`.fractal/foo`) or a bare name resolved under the runs dir (`foo` → `.fractal/foo`).

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
children.

**Exit codes:** `0` final · `1` error · `2` no-final · `3` timeout · `5` confabulation
suspected. So you can gate on them: `fractal verify <run> --deep --root . && deploy`.

## codebrain — a code-discovery brain

`fractal codebrain` is a thin product surface that points the engine at a codebase:
it builds itself a **repo map** (by fanning children over the subsystems, not by a
regex dump), keeps it on disk like `bd` keeps its db, and then answers a coding
agent's questions about the code with small, **cited** EDN — so the agent spends
its context on the change, not on reading the tree.

```bash
fractal codebrain init --path ./src --provider vertex-gemini --model gemini-3.1-pro-preview
fractal codebrain ask  "Where are CLI verbs registered and what's the handler contract?"
fractal codebrain map        # show the persisted map  ·  status  # freshness + HEAD
```

Born once (the build is the amortized cost); each `ask` resumes the warm brain and
runs cheap. Full setup, auth for every provider, and the answer shape:
[`docs/CODEBRAIN.md`](docs/CODEBRAIN.md).

## Going live: providers

Provider calls go through [`clojure-llm-sdk`](https://github.com/DeadMeme5441/clojure-llm-sdk).
Select provider and model per role — root / leaf / child can differ (a common, cheap
split is a strong root with cheaper children and leaves):

```bash
fractal run "Map this repo's subsystems with evidence." \
  --provider vertex-gemini       --model gemini-3.1-pro-preview \
  --leaf-provider vertex-gemini  --leaf-model gemini-3.1-flash-lite-preview \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --max-turns 15 --call-timeout-ms 120000
```

Credentials come from environment variables (an ignored `.env` is read for local dev —
never commit secrets). Two non-obvious provider facts that will cost you time if you
miss them:

- **Codex OAuth** is the provider keyword `codex-backend` (plain `codex` is the
  API-key path). It reads `~/.codex/auth.json`.
- **Vertex Gemini** (`vertex-gemini`) needs `GOOGLE_CLOUD_PROJECT` and
  `GOOGLE_CLOUD_LOCATION` **exported into the JVM environment** (the `.env` loader does
  *not* push them to `System/getenv`), plus Application Default Credentials
  (`gcloud auth application-default login`).

> **Root-model strength is decisive.** A weak root will confabulate past its own
> correct observations. Don't put a mini model at the root when you care about the
> answer.

> **Live runs cost money and can hang.** There is no engine-level budget/timeout
> governor yet. Always leash live runs: `--call-timeout-ms`, `--max-turns`,
> `--max-fanout`, and run them in the background with monitoring.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `fractal: command not found` | The symlink isn't on your `PATH`. Confirm the target dir is on `PATH` (`echo $PATH`), or just run `./bin/fractal` from the repo. |
| `java: command not found` / `UnsupportedClassVersionError` | No JDK, or older than 21. `bin/fractal` runs the jar with `java`. Install JDK 21+ and ensure `java -version` reports 21 or newer. |
| `Could not find or load main class` / jar missing | The uberjar isn't built. Run `clojure -T:build uber` (creates `target/fractal.jar`). Without it, `bin/fractal` falls back to source — that needs `clojure` on your `PATH`. |
| `no such run: <name>` | Runs resolve under `.fractal/` of the directory you're in (or an ancestor). Run `fractal ls` to see what's there, `cd` to the project, or pass `--runs-dir <dir>`. |
| Live provider: `unauthorized` / auth errors | `codex-backend` needs `~/.codex/auth.json`; `vertex-gemini` needs `GOOGLE_CLOUD_PROJECT` + `GOOGLE_CLOUD_LOCATION` exported into the JVM env and valid ADC. See [Going live](#going-live-providers). |
| Vertex first-call hang / `EOF` | A known cold-start transport hiccup. Retry is on by default and the SDK retries transient EOF/timeout with backoff; if a call truly hangs, your `--call-timeout-ms` bounds the whole retry loop (total wall-clock). |
| A live run hangs for minutes | There's no governor yet — kill it and re-run leashed: `--call-timeout-ms`, `--max-turns`, `--max-fanout`, in the background. |

---

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

Everything is `input -> processing -> output`; only the *kind* of processing varies —
**deterministic** (plain Clojure), **probabilistic** (a *leaf*, `lm`/`map-lm`, whose
body is a model), or **recursive** (a *child*, `rlm`/`map-rlm`, a full recursion of the
loop). A leaf is the non-recursive base case. There is **no magic `context` variable** —
working state lives in REPL vars the model defines with `def`. The root, every child,
and every leaf run the *same* loop; there is no separate "planner" or "executor." See
[`docs/CONCEPTS.md`](docs/CONCEPTS.md) for the model in depth.

## Artifacts & the journal

Every session writes a directory under `.fractal/`. The **source of truth** is
`events.ednl`, an append-only event log (one EDN form per line); everything else is a
**projection** of it, materialized at turn boundaries for convenient reading.

```text
.fractal/<session-id>/
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
catches confabulations the grep waves through. Details in
[`docs/CONCEPTS.md`](docs/CONCEPTS.md#the-trust-layer).

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
