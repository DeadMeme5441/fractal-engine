# Agent Instructions

This repository is a public, open-source project. Treat every tracked file as
public-facing. (`CLAUDE.md` complements this file with collaboration notes; the
rules here are canonical.)

## Public-Safe Boundary

Do not commit or mention private workplace details, internal repository names,
ticket identifiers, company names, customer names, local secret paths, API keys,
private hostnames, or copied proprietary content.

## Project Shape

Keep the compute engine small and strict. Everything is
`input -> processing -> output`; only the *kind* of processing varies — ordinary
Clojure is deterministic, `lm`/`map-lm` are probabilistic, `rlm`/`map-rlm` are
recursive.

- conversation messages are the model's working transcript
- the model emits fenced Clojure
- the host evaluates that Clojure in a persistent namespace
- the host returns projected observations as messages
- the process repeats until `(FINAL value)`; a session stays live across turns
- the only special model-facing functions are `FINAL`, `lm`, `map-lm`, `rlm`,
  `map-rlm`, and `attach-rlm`
- there is **no magic `context` var**; working state lives in REPL vars the model
  defines

Do not add storage, memory, workflow, product, repository-analysis, or task
template concepts to the compute kernel. Those belong in layers around the
kernel.

## Current Status

Early but real. Validated live end-to-end on two provider stacks (a strong root
plus cheaper child/leaf models), including emergent recursive decomposition
(`map-rlm`) on real repositories with evidence-cited results. Prompt is at
version 15; tests green. Known limitations, documented and not yet built: no
engine-level budget/timeout governor, no sandbox (the engine runs trusted local
Clojure), no storage/retrieval data layer, and `attach-rlm` has no
prior-session discoverability index.

## Invariants (do not break)

- The model-facing surface is exactly the six functions above — no convenience
  functions, no revived retired names. New patterns belong in the model's own
  session via `defn`, not in the engine API.
- The system prompt must not contain the substrings `context`, `product`,
  `storage`, or `workflow` (enforced by the `prompt-contract` test).
- `prompt-contract` pins exact phrases and the `prompt-version`. If you change
  `src/fractal_engine/prompt.clj` or `leaf-system-prompt` in `process.clj`, update
  the test in lockstep and bump `prompt-version`.
- Only the compute engine belongs in core runtime namespaces.

## Development Workflow

This project tracks work with **Beads** (`bd`) — use it for all task tracking,
not TodoWrite or markdown files. Create an issue before non-trivial work, claim
it when you start, and close it when done.

```bash
bd prime                 # recover context after compaction / new session
bd ready                 # ready-to-work issues (no blockers)
bd show <id>             # details + dependencies
bd update <id> --claim
bd close <id>
bd create --title="..." --description="..." --type=task|feature|bug --priority=2
```

Use `bd remember "insight"` for durable cross-session knowledge (`bd memories
<keyword>` to search). Avoid `bd edit` — it opens an editor and blocks.

For code changes, validate before closing work and report results plainly:

```bash
clojure -M:test
git diff --check
git status --short --branch
```

Offline development uses the fake/scripted provider (no keys). For live provider
validation, two non-obvious facts: the Codex OAuth provider keyword is
`:codex-backend` (plain `:codex` is the API-key path), and Vertex Gemini
(`:vertex-gemini`) needs `GOOGLE_CLOUD_PROJECT` / `GOOGLE_CLOUD_LOCATION`
exported into the JVM environment — the `.env` loader does not push them to
`System/getenv`. Root-model strength matters: weak roots confabulate past their
own observations. Live runs cost real money and can hang, so always leash them
(timeout + background + monitor) until a governor exists.

Keep commits narrow and intentional. If a file is already dirty, understand
whether the change is yours before editing it. Never revert unrelated user work.
Do not perform git operations unless explicitly asked.

## Design Discipline

Before implementing a large feature, write or update a spec. The spec should
define:

- model-facing surface
- runtime responsibilities
- artifact responsibilities
- failure semantics
- tests and live validation
- explicit anti-goals

The most important architectural rule is separation of concerns:

- compute engine: agent plus persistent REPL loop
- artifact layer: trace, inspectability, process tree, resume snapshots
- provider layer: LLM adapter boundary
- storage layer: optional future handles, indexes, blobs, retrieval
- product layer: CLI, UI, MCP, workflows, skill packs

Only the compute engine belongs in core runtime namespaces.
