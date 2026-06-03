# CLI reference: `fractal`

`fractal` is the command-line surface for driving sessions and reading what happened.
It uses canonical SQLite rows plus BlobStore payloads; Datahike is a rebuildable query
index, and the CLI does not read per-session filesystem homes.

```text
fractal <verb> <subject> [args] [--flags]
```

Invoke it as `fractal ...` through `bin/fractal`, or directly with:

```bash
clojure -M -m fractal-engine.agentcli ...
```

## Conventions

- `<run>` is a session id or alias resolved in the canonical store. The store root
  defaults to `.fractal/` in the invocation directory, override with `--runs-dir`.
- `[node]` is an address within a recursive tree: `root`, `child-0001`, or
  `root/child-0001/child-0004`.
- `--json` emits parseable JSON. `stream` emits JSONL.
- `--no-color` disables ANSI color.

The local store root physically contains `store.sqlite`, content-addressed blobs, and
optionally a derived Datahike index. Those files are backend state, not session identity.

## Exit codes

| code | meaning |
|---|---|
| `0` | success / final value exists / claims supported |
| `1` | engine or usage error |
| `2` | no `FINAL` |
| `3` | timeout |
| `5` | confabulation suspected by `verify` |

## Drive verbs

### `chat [run]`

Talk to a persistent, resumable session. Without `[run]`, starts a new session. With
`[run]`, resolves the existing session id/alias and resumes the same session. REPL vars
persist across turns through completed-head snapshots. A live progress line polls the
canonical store while the turn runs.

```bash
fractal chat
fractal chat my-session
fractal chat --provider vertex-gemini --model gemini-3.5-flash
```

### `run "<task>"`

Start a session, run one turn, and print a chainable session handle.

```bash
fractal run "Summarize this repo." --fake-script simple --name demo
fractal run "..." --provider openai --model gpt-4o-mini --json
```

### `resume <run> "<task>"`

Restore the selected completed head into the same logical session and run another turn.
Resume advances the same session current-head and preserves the same cache id. Use
`--turn N` to restore a specific completed turn head.

### `fork <run> "<task>"`

Restore a source head into a new user/API session with a new cache id by default. Fork
leaves the source session current-head and fingerprint unchanged. Use `--name NAME` to
assign a stable id/alias to the fork.

## Read verbs

### `show <run> [node]`

Display node identity, model/status/counts, step observations, leaves, child addresses,
and final summary. `--final` resolves and prints the full final value. `--leaves` focuses
on leaf calls.

```bash
fractal show my-run
fractal show my-run child-0002
fractal show my-run child-0002 --final
```

### `tree <run>`

Print the recursive session tree: one line per node with status and step/leaf/child
counts.

### `prime <run>`

Compact orientation for a run: model, status, counts, final summary, and next commands.

### `ls`

List canonical sessions in the store root with status, counts, and final presence.

### `store check|rebuild-index`

Validate the canonical SQLite + BlobStore root, or rebuild the derived Datahike index:

```bash
fractal store check --json
fractal store rebuild-index
```

### `inspect <run> [node]`

Structured session/node details. Use `--json` for API-friendly output.

### `verify <run> [node]`

Run the trust layer over a node's `FINAL` value.

- default: deterministic grep floor over cited file evidence;
- `--root <repo>`: resolve relative citations against a repo root;
- `--deep`: use the engine as an adversarial judge.

```bash
fractal verify my-run child-0001 --root .
fractal verify my-run child-0001 --root . --deep \
  --provider vertex-gemini --model gemini-3.5-flash
```

### `trace <run> [node]`

Claim provenance for a node: final value, evidenced claims, and child/leaf refs.

### `cost <run>`

Spend breakdown from canonical call facts: calls, tokens, cost, and per-child rollups.
It reports visibility, not a cap.

Drive commands that settle a turn print a compact `turn` plus `total` spend
summary. `codebrain init` and `codebrain ask` print the full usage block:
`this turn` versus `cumulative`, with root, child RLM, leaf, token, cache, and
estimated-cost splits. `LLM calls` are provider calls; `rows` include structural
invocation records such as child RLM edges.

### `leaves <run> [node]`

Resolve a node's leaf call inputs and outputs from BlobStore.

### `step <run> [node] N`

Print one model/eval step in full.

### `events <run>`

Print the agent/operator audit view for a session. This is the readable event-log
surface: a compact panel plus a timeline of meaningful facts such as model calls,
`FINAL`, snapshots, checkpoint sealing, child invocation, and current-head movement.

Use `--event N` to focus on one event's causal chain:

```bash
fractal events demo
fractal events demo --event 12
fractal events demo --limit 40
fractal events demo --json
```

`events` suppresses repetitive progress churn by default. It does not resolve raw
payload blobs and is not a restore/replay mechanism. It is for understanding what
happened and why a checkpoint, call, invocation, or ref movement exists. See
[`EVENT_LOG.md`](EVENT_LOG.md).

### `stream <run>` (alias `tail`)

Print canonical event facts as JSONL in append order. This is not `tail events.ednl`; no
session event file is required. Use `stream` when you want raw compact event rows for
another program; use `events` when a human or agent needs the audit trace.

## Engine options

These apply to `run`, `resume`, `fork`, and `chat`:

| flag | meaning |
|---|---|
| `--provider` / `--model` | root provider and model |
| `--leaf-provider` / `--leaf-model` | provider/model for `lm` and `map-lm` |
| `--child-provider` / `--child-model` | provider/model for `rlm`, `map-rlm`, `attach-rlm` |
| `--fake-script NAME` | offline scripted provider |
| `--runs-dir DIR` | canonical store root; default `.fractal/` |
| `--name ID` | assign session id/alias |
| `--max-turns N` | turn leash |
| `--max-fanout N` | fanout leash |
| `--call-timeout-ms MS` | per-call wall-clock timeout |

Live runs cost money and can hang. Normal use should set practical leashes until an
engine-level governor exists.

## Operation semantics

- `FINAL` returns control and records a completed head; it does not terminate the
  session.
- `resume` advances the same session.
- `fork` creates a new user/API session and leaves the source unchanged.
- `rlm` and `map-rlm` create child sessions, invocation facts, and model-visible RLM
  envelopes (`:rlm/value`, `:rlm/session`, `:rlm/head`, `:rlm/meta`).
- `attach-rlm` continues a session ref or branches from a head ref. Session-ref attach
  advances the callee session; head-ref attach creates a new attached child and leaves
  the source head unchanged.
- `lm` and `map-lm` create calls only.

## Recipes

```bash
# offline: run, then read
fractal run "Define x and return it." --fake-script simple --name demo
fractal show demo
fractal tree demo
fractal inspect demo --json

# live: start a session with explicit models
fractal chat --provider vertex-gemini --model gemini-3.5-flash \
  --child-provider vertex-gemini --child-model gemini-3.5-flash \
  --leaf-provider vertex-gemini --leaf-model gemini-3.1-flash-lite-preview \
  --call-timeout-ms 600000

# machine-readable events
fractal stream demo | jq 'select(.["event/type"] == "call/started")'
fractal events demo --event 12 --json | jq '.chain'
fractal show demo --json | jq '.final'
```

## Backend status

This pass implements local SQLite, a filesystem-backed BlobStore, and a rebuildable
filesystem-backed Datahike index. No S3/AWS backend is implemented.
