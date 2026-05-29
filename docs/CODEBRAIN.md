# codebrain — a persistent code-discovery brain

`codebrain` turns the fractal engine into a **code-discovery brain** that a coding
agent can offload to. The agent (e.g. Claude Code) asks the brain a question about
a codebase; the brain spends *its* context exploring the code and returns a small,
cited answer the agent can act on — so the agent doesn't burn its own context
reading dozens of files.

It is a thin **product surface**, not new engine machinery. The value is two
prompts. The kernel already teaches a node how to be a recursive compute node (the
base system prompt). codebrain adds a second, **session-level overlay** on top of
that one — a standing role, stated once when the brain is born and carried across
every turn and every resume:

> *You are a code-discovery brain for THIS repo. Build a repo map for yourself
> using your children and leaves, then answer a coding agent's questions from it —
> compact and cited. Delegate the reading to children so your own context stays
> small. Ground every claim in a real file:line.*

Used like `bd`: durable state lives on disk under `<runs-dir>/codebrain/` and
survives across invocations. You **born it once**, it builds its repo map; each
**ask resumes** that same brain (its map and REPL vars stay warm) and advances the
brain's HEAD.

## Why a brain and not a regex index

The repo map is **not** a deterministic symbol dump. The brain builds it the way
the engine is meant to work: it lists the tree with ordinary Clojure, groups files
into subsystems, then **fans out one child (`map-rlm`) per subsystem** to read that
slice and return a compact, grounded module summary; it uses **leaves (`lm` /
`map-lm`)** for bounded semantic reads. The root only ever holds the children's
compact summaries — which is what keeps the brain itself from becoming a context
sink, and what lets a later `ask` reuse it cheaply.

## Quickstart

```bash
# 1. born once — builds the repo map for the target repo (live; costs money)
fractal codebrain init --path ./src \
  --provider vertex-gemini --model gemini-3.1-pro-preview \
  --child-model gemini-3.5-flash --leaf-model gemini-3.1-flash-lite-preview \
  --max-turns 20 --max-fanout 14 --call-timeout-ms 180000

# 2. ask it anything about the code — resumes the warm brain, answers cited
fractal codebrain ask "Where are CLI verbs registered and what's the handler contract?"

# 3. read the map yourself (no model call), or check freshness
fractal codebrain map           # rendered markdown;  --json for the raw EDN
fractal codebrain status        # root, when built, turn count, HEAD
```

`--path` defaults to the current directory. Point it at a subtree (`./src`) to
bound a first build's cost.

## The answer shape

Every `ask` returns compact EDN designed for an agent to consume without opening
the code:

```clojure
{:answer     "direct, specific answer"
 :evidence   [{:file "path" :lines "a-b" :quote "verbatim line you can find"}]
 :files-read ["path" ...]
 :pointers   [{:what "where to go / what to change" :file "path" :lines "a-b"}]
 :missing    ["what could not be determined"]
 :map-stale? false}   ; true (with a note) if the map pointed it wrong
```

Because each ask is a saved engine run, you can audit it with the trust layer:
the command prints `verify: fractal verify <run>` — run it to check the answer's
quotes actually exist in the cited files (confabulation check).

## Auth — any provider

A provider is a **value**: a descriptor that says how it authenticates. Pick one of
these for `--provider` / `--model` (and optionally split roles with
`--child-model` / `--leaf-model`). The engine reads the rest from the descriptor.

| `--provider`     | auth          | what you set up |
| ---------------- | ------------- | --------------- |
| `vertex-gemini`  | ADC + env     | `gcloud auth application-default login`, and **export** `GOOGLE_CLOUD_PROJECT` and `GOOGLE_CLOUD_LOCATION` into the JVM env (see note) |
| `codex-backend`  | OAuth file    | sign in so `~/.codex/auth.json` exists; the SDK reads it. No key needed |
| `anthropic`      | API key       | `export ANTHROPIC_API_KEY=…` (or put it in `.env`) |
| `openai`         | API key       | `export OPENAI_API_KEY=…` |
| `openrouter` / `deepseek` / `kimi-code` / `cohere` | API key | export the provider's key env var |
| `scripted`       | none          | offline fake (`--fake-script …`); no network, no cost |

Check whether a provider's auth is satisfied as data — the descriptor knows what it
needs.

### vertex-gemini note (the one that bites)

ADC supplies the token, but the two env vars **must be exported into the JVM's
environment** — a `.env` file is read by the engine's own loader for API keys, but
it is **not** pushed to `System/getenv`, and the GCP SDK reads them from there. So:

```bash
gcloud auth application-default login        # one-time: creates ADC
export GOOGLE_CLOUD_PROJECT="your-project"
export GOOGLE_CLOUD_LOCATION="us-central1"   # or your region
fractal codebrain init --path ./src --provider vertex-gemini --model gemini-3.1-pro-preview …
```

If you keep them in `.env`, export them before launching, e.g.
`export GOOGLE_CLOUD_PROJECT="$(grep -E '^GOOGLE_CLOUD_PROJECT=' .env | cut -d= -f2-)"`.

## Storage model

```
<runs-dir>/codebrain/
  meta.edn        # root, born-at, map-built-at, turn count, HEAD pointer
  repo-map.edn    # the latest built map (the brain's memory, on disk)
  repo-map.md     # human-readable rendering (what `codebrain map` prints)
  t0000/          # birth run (the build); addressable with `fractal show`
  t0001/ t0002/…  # each ask is a resumed run; HEAD advances
```

`<runs-dir>` is discovered like `git`/`bd`: a `.fractal/` in the current dir or any
ancestor, else created in the cwd. Override with `--runs-dir DIR`. The brain's turn
dirs are nested one level under `codebrain/`, so they never clutter `fractal ls`.

## Cost & leashing

The engine has no budget governor — **leash every live run**:
`--max-turns N`, `--max-fanout N`, `--call-timeout-ms MS` (the timeout is total
wall-clock per call, including retry backoff).

The economics: **the build is the expensive, one-time, amortized cost; asks are
cheap** because they resume the warm map instead of re-exploring. As a reference
point, mapping a ~20-file Clojure source tree with a strong root cost on the order
of a dollar once, while subsequent cited answers ran a few cents each. Build a
focused subtree first (`--path ./src`) and a cheaper root model if cost matters;
re-`init` to rebuild after big structural changes.

## Using it from a coding agent

The whole point is context offload. Drop a note like this in your agent's project
instructions (`CLAUDE.md`):

> When you need to understand this codebase — where something lives, how a
> subsystem works, what a function's contract is — prefer
> `fractal codebrain ask "…"` over reading source files yourself. It returns a
> compact, cited answer (file:line evidence) so you spend your context on the
> change, not on discovery. Build the brain once with `fractal codebrain init`.
> If `:map-stale?` comes back true, re-run `fractal codebrain init`.
