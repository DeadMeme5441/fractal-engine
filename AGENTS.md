# Agent Instructions

This repository is intended to become a public, open-source project. Treat every
tracked file as public-facing.

## Public-Safe Boundary

Do not commit or mention private workplace details, internal repository names,
ticket identifiers, company names, customer names, local secret paths, API keys,
private hostnames, or copied proprietary content.

If local-only notes or private specifications exist under `build_spec/`, they
are intentionally ignored by git. You may use them for local implementation
guidance, but do not commit that directory or quote private material from it into
tracked files.

## Project Shape

This project should keep the compute engine small and strict:

- conversation messages are the model context
- the model emits fenced Clojure
- the host evaluates that Clojure in a persistent namespace
- the host returns projected observations as messages
- the process repeats until `(FINAL value)`
- the only special model-facing functions are `FINAL`, `lm`, `map-lm`, `rlm`,
  and `map-rlm`

Do not add storage, memory, workflow, product, repository-analysis, or task
template concepts to the compute kernel. Those belong in layers around the
kernel.

## Development Workflow

Use Beads for task tracking.

```bash
bd prime
bd ready
bd show <id>
bd update <id> --claim
bd close <id>
```

For code changes, validate before closing work:

```bash
clojure -M:test
git diff --check
git status --short --branch
```

Keep commits narrow and intentional. If a file is already dirty, understand
whether the change is yours before editing it. Never revert unrelated user work.

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
