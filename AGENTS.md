# Agent & contributor guide

This is the canonical guide for working in `fractal-engine` — whether you're a human
contributor or an AI agent. It defines the project's shape, its hard invariants, and how
to develop and validate changes. Start with the [README](README.md) for the user view
and [`docs/`](docs/) for depth; this file is the boundary that keeps the engine honest.

This repository is public and open source. **Treat every tracked file as public-facing.**

## Public-safe boundary

Never commit or mention private workplace details, internal repository names, ticket
identifiers, company or customer names, local secret paths, API keys, private hostnames,
or copied proprietary content — in tracked files, commit messages, or commit metadata.
Session runs (`.fractal/`, the default; `runs/` if you override `--runs-dir`), `.env`,
and the Beads working data (`.beads/`) are gitignored and hold the only place such
content may legitimately appear locally; keep it there.

## Project shape

Keep the compute engine small and strict. Everything is
`input -> processing -> output`; only the *kind* of processing varies — ordinary Clojure
is deterministic, `lm`/`map-lm` are probabilistic (a *leaf*), `rlm`/`map-rlm` are
recursive (a *child*).

- conversation messages are the model's working transcript
- the model emits fenced Clojure
- the host evaluates it in a persistent namespace and returns projected observations
- the loop repeats until `(FINAL value)`; a session stays live across turns
- the only model-facing functions are `FINAL`, `lm`, `map-lm`, `rlm`, `map-rlm`,
  `attach-rlm`
- there is **no magic `context` var** — working state lives in REPL vars the model defines

The engine is layered by concern (full map in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)):

- **compute engine** — the agent + persistent REPL loop, fanout, child/attach
- **journal & projections** — an append-only event log (`events.ednl`, the source of
  truth) plus pure folds into views
- **persistence** — snapshot / restore / resume / fork / lineage
- **provider** — the LLM adapter boundary
- **read surface** — a journal-folding projection and the trust layer (provenance,
  claim-vs-evidence), rendered by the `fractal` CLI
- **product** — the CLI (and, later, a TUI / MCP adapter)

Do not add storage, memory, workflow, product, repository-analysis, or task-template
concepts to the compute kernel. Those belong in layers *around* the kernel.

## Current status

Early but real, validated live end-to-end. The engine decomposes real problems on real
codebases with cheap-child / strong-root model splits, including emergent recursive
fanout (`map-rlm`) with evidence-cited results. The `fractal` CLI is the use surface
(drive + read, `--json`, meaningful exit codes), and the trust layer catches
confabulation by checking cited evidence against source (grep floor + `--deep` engine
judge). Behavior prompt is at version 15; tests green.

Known limitations, documented and not yet built:

- no engine-level budget/timeout governor (leash live runs yourself)
- no true in-process sandbox (the engine runs trusted local Clojure; a best-effort OS
  sandbox is provided — see the README)
- no storage/retrieval data layer
- `attach-rlm` has no prior-session discoverability index
- the live `--deep` verify judge is itself a model's judgment (grounded in quotes you
  can inspect, but a weak verifier can misjudge — use a strong model, or a panel)

## Invariants (do not break)

- The model-facing surface is **exactly the six functions** above — no convenience
  functions, no revived retired names. New patterns belong in the model's own session
  via `defn`, not in the engine API.
- The behavior prompt must not contain the substrings `context`, `product`, `storage`,
  or `workflow` (enforced by the `prompt-contract` test).
- `prompt-contract` pins exact phrases and the `prompt-version`. If you change
  `src/fractal_engine/prompt.clj` or the leaf system prompt in `process.clj`, update the
  test in lockstep and bump `prompt-version`.
- Only the compute engine belongs in core runtime namespaces.
- The journal stores **results, not recipes** — folding it must never re-run a model.

## Develop & validate

Run the tests before claiming anything is done, and report results plainly:

```bash
clojure -M:test
git diff --check
git status --short --branch
```

Offline development uses the fake/scripted provider (no keys): `--fake-script <name>`.
Inspect any run with `fractal show <run>` / `fractal tree <run>`. Build the binary with
`clojure -T:build uber`.

Two non-obvious live-provider facts: the Codex OAuth provider keyword is `codex-backend`
(plain `codex` is the API-key path), and Vertex Gemini (`vertex-gemini`) needs
`GOOGLE_CLOUD_PROJECT` / `GOOGLE_CLOUD_LOCATION` **exported into the JVM environment**
(the `.env` loader does not push them to `System/getenv`). Root-model strength is
decisive — weak roots confabulate past their own observations. **Live runs cost money
and can hang; always leash them** (`--call-timeout-ms`, `--max-turns`, `--max-fanout`,
background + monitor) until a governor exists.

Keep commits narrow and intentional. If a file is already dirty, understand whether the
change is yours before editing. Don't perform git operations unless asked.

### Task tracking

This project tracks work with Beads (`bd`) — use it for task tracking rather than
ad-hoc markdown. Its working data lives in `.beads/` (gitignored, local-only, no remote).

```bash
bd ready                 # ready-to-work issues
bd show <id>             # details + dependencies
bd update <id> --claim   # claim work
bd close <id>            # finish
```

## Design discipline

Before implementing a large feature, write or update a spec defining: the model-facing
surface, runtime responsibilities, artifact responsibilities, failure semantics, tests
and live validation, and explicit anti-goals. Refinement means **decomplection** (pull
braided concerns apart so hard features become small decorators over one seam), not
relocation. Validate on real, hard cases — never toy demos — and never trust pretty
output: check cited evidence against the source (that's what the trust layer is for).
