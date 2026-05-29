# CLI reference — `fractal`

`fractal` is the engine's use surface: one grammar to **drive** the engine and **read**
what it did. It's designed to feel like `bd` — short verbs, a positional address
instead of flag ceremony, run-name resolution, and output that prints the next command.

```
fractal <verb> <subject> [args] [--flags]
```

Invoke it as `fractal …` (the `bin/fractal` wrapper, jar-backed, runs in any directory)
or directly as `clojure -M -m fractal-engine …`.

## Conventions

- **`<run>`** — a run directory path (`.fractal/foo`) or a bare name resolved under the
  runs dir (`foo` → `<runs-dir>/foo`). The runs dir defaults to `.fractal/` in the
  directory you invoke from, discovered up the tree like git/`bd` (override with
  `--runs-dir`).
- **`[node]`** — a node address within a run: `root` (default), `child-0001`, or a path
  like `child-0001/child-0004`. The leading `root/` is implied. A node view prints the
  exact `fractal show <run> <child>` commands to drill into its children.
- **`--json`** — every verb accepts it and emits parseable output (objects for reads,
  JSONL for `stream`).
- **`--no-color`** — disable ANSI color (color is auto-off when piped or `--json`).

### Exit codes

| code | meaning |
|---|---|
| `0` | a `FINAL` value exists / claims supported |
| `1` | error (engine error, or a usage error like an unknown run/verb) |
| `2` | no `FINAL` (the turn ran but produced none) |
| `3` | timeout |
| `5` | confabulation suspected (`verify` found a refuted/unsupported claim) |

So you can gate on them: `fractal verify <run> --root . --deep && ./publish.sh`.

---

## Drive verbs

### `chat [run]`
Talk to a persistent, resumable session — the headline mode. Holds one live session and
runs each line you type as a turn; REPL vars persist in memory and the journal grows. A
live `◐ thinking…` line shows children/steps/leaves as the engine works; each turn
settles to a compact result plus drill hints into the nodes that turn produced. `/quit`
to leave. With `[run]`, resumes that session; without, starts a fresh one (name it with
`--name`).

```bash
fractal chat                      # new brain
fractal chat my-session           # resume an existing one
fractal chat --provider vertex-gemini --model gemini-3.1-pro-preview
```

### `run "<task>"`
One-shot: start a session, run a single turn, print a chainable run handle.

```bash
fractal run "Summarize this repo." --fake-script simple --name demo
fractal run "..." --provider openai --model gpt-4o-mini --json
```

### `resume <run> "<task>"`
Continue a saved session from its last snapshot (restores history + EDN-safe vars).
`--turn N` resumes from a specific turn; `--new-dir DIR` writes to a new directory.

### `fork <run> "<task>"`
Branch a session into a new directory at a turn (`--turn N`), preserving lineage.

---

## Read verbs

### `show <run> [node]`
The hub. Node detail: identity/model/status/counts, each step's `▷ wrote` / `◁ observed`,
leaves, the final value (summary), and **drill commands** for children. Flags: `--final`
(print the full final value), `--leaves` (just the leaf I/O).

```bash
fractal show my-run                     # root
fractal show my-run child-0002          # drill into a child
fractal show my-run child-0002 --final  # that child's full FINAL value
```

### `tree <run>`
The whole run as an addressable tree: one line per node with a status glyph, address
segment, and `[steps leaves children]` counts.

### `prime <run>`
Compact orientation — model, status, step/child counts, the final summary, and the next
commands to run. The read-side analogue of `bd prime`.

### `ls`
List runs under the runs dir with status, counts, and whether each reached a final.

### `verify <run> [node]`
The trust layer — claim-vs-evidence over a node's `FINAL` value.

- default: the **grep floor** (free) — checks cited code symbols exist in the cited file.
- `--root <repo>`: resolve relative citations against this repo root (usually required —
  models cite repo-relative paths).
- `--deep`: escalate to the **engine judge** — hands the claims back to the engine
  (needs provider flags) to adversarially re-read the source. Exit `5` if any claim is
  refuted.

```bash
fractal verify my-run child-0001 --root /path/to/repo
fractal verify my-run child-0001 --root /path/to/repo --deep \
  --provider vertex-gemini --model gemini-3.1-pro-preview
```

### `trace <run> [node]`
Claim provenance for a node: the final value, its evidenced claims, and the child/leaf
calls that fed it.

### `cost <run>`
Spend breakdown: tree totals (calls, tokens, cost) and per-child cost, read from the
materialized usage projection. Visibility, not a cap.

### `leaves <run> [node]`
A node's leaf calls with inputs and outputs resolved.

### `step <run> [node] N`
One step in full (the complete `▷ wrote` code and `◁ observed` text). `N` is 1-based.

### `stream <run>`  (alias `tail`)
The session's journal events as JSONL — one JSON object per line, replayable and
pipe-friendly.

---

## Engine options (drive verbs)

These mirror the engine's configuration and apply to `run` / `resume` / `fork` / `chat`:

| flag | meaning |
|---|---|
| `--provider` / `--model` | the **root** provider and model |
| `--leaf-provider` / `--leaf-model` | provider/model for leaves (`lm`/`map-lm`) |
| `--child-provider` / `--child-model` | provider/model for children (`rlm`/`map-rlm`) |
| `--fake-script NAME` | offline scripted provider (`simple`, `lm`, `map-lm`, `rlm`, `map-rlm`, …) |
| `--runs-dir DIR` | where run directories live (default `.fractal/` in the invocation dir) |
| `--name ID` | name the session (otherwise a UUID) |
| `--max-turns N` | leash: max turns |
| `--max-fanout N` | leash: max parallel fanout |
| `--call-timeout-ms MS` | leash: per-call wall-clock timeout |

> Live runs cost money and can hang; always set `--call-timeout-ms` and `--max-turns`
> and run in the background with monitoring. See the README's provider warnings.

## Recipes

```bash
# offline: run, then read, in one grammar
fractal run "Define x and return it." --fake-script simple --name demo
fractal show demo            # see steps + final
fractal tree demo            # see the shape

# live: map a repo, then trust-check a child's claims against the source
fractal chat --provider vertex-gemini --model gemini-3.1-pro-preview
#   › map this repo's subsystems with file-cited risks    (then /quit)
fractal verify <run> child-0004 --root . --deep \
  --provider vertex-gemini --model gemini-3.1-pro-preview

# machine-readable: stream events, or get JSON
fractal stream <run> | jq 'select(.["event/type"] == "call/started")'
fractal show <run> --json | jq '.final'
```
