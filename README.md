# fractal-engine

`fractal-engine` is a small recursive compute kernel for running an agent through
a persistent Clojure REPL.

The loop is intentionally simple:

1. messages are the model context
2. the model emits fenced Clojure
3. the host evaluates that Clojure in a persistent namespace
4. the host appends projected observations as messages
5. the current user turn repeats until `(FINAL value)`

A session is long-lived. `(FINAL value)` completes the current user turn; it
does not end the session. The same message history, REPL namespace, and cache
scope remain available to later turns.

The only model-facing host functions are:

```clojure
FINAL
lm
map-lm
rlm
map-rlm
attach-rlm
```

There is no magic context var, tool registry, workflow layer, storage handle
surface, or repository-specific helper API in the compute kernel.

`map-lm` and `map-rlm` are real parallel fanout surfaces, capped at 50 inputs
per call by default. Child RLM sessions are also prompted and nudged to call
`FINAL` before turn exhaustion so parent sessions receive values, not progress.

## Status

This is an early implementation. It is suitable for fake-provider development,
artifact inspection, and live-provider smoke tests through
[`clojure-llm-sdk`](https://github.com/DeadMeme5441/clojure-llm-sdk).

The runtime executes trusted local Clojure. It is not sandboxed.

## Quickstart

Run the offline scripted provider:

```bash
clojure -M -m fractal-engine run --fake-script simple --question "Define x and return it."
```

Inspect the generated run:

```bash
clojure -M -m fractal-engine inspect --dir runs/<session-id>
```

Run tests:

```bash
clojure -M:test
```

## Live Providers

Provider calls go through `llm.sdk/complete` from `clojure-llm-sdk`. The CLI
accepts a provider and model:

```bash
clojure -M -m fractal-engine run \
  --provider openai \
  --model gpt-4o-mini \
  --question "Use Clojure to compute (+ 20 22), then FINAL the answer."
```

Credentials are read from environment variables. For local development, an
ignored `.env` file can also be used by this CLI. Do not commit secrets.

## Artifacts

Each session writes a directory:

```text
runs/<session-id>/
  session.edn
  messages.edn
  turns.edn
  evals.edn
  calls.edn
  events.edn
  snapshots.edn
  final.edn
  usage.edn
  tree.edn
  blobs/
  children/
```

Canonical files are `session.edn`, `messages.edn`, `turns.edn`, `evals.edn`,
`calls.edn`, `snapshots.edn`, `lineage.edn` when present, and canonical child
session summaries under `children/`.

`final.edn`, `usage.edn`, and `tree.edn` are derived views.

Large EDN values in call request/response refs and turn snapshots are stored
under `blobs/` with refs containing the relative path, SHA-256, and byte count.
Small refs remain inline. Session fingerprints hash canonical state files and
child session fingerprints, not arbitrary blob contents.

Child RLM sessions use the same shape under `children/child-0001/`,
`children/child-0002/`, and so on. `rlm` and `map-rlm` run child sessions for
one turn by default; after a successful one-turn child result, the child session
is marked `:stopped` and its latest turn remains `:final`.

## Resume

Snapshots are written at graceful boundaries. Resume restores message history
and EDN-safe vars into a fresh namespace, reinstalls the five runtime functions,
and appends a new user turn.

```bash
clojure -M -m fractal-engine resume \
  --dir runs/<session-id> \
  --fake-script resume-use \
  --question "Use the restored var and FINAL the result."
```

Non-EDN values are recorded as unresumable rather than silently dropped.

## CLI

The CLI is a thin shell over the compute engine:

```text
run
chat
inspect
resume
fork
```

`chat` accepts multi-paragraph messages. Finish a message with `/send` on its
own line; enter `/exit` on an empty prompt to stop the session.

It does not define workflows or product-specific behavior.

## Anti-Goals

The core runtime does not include:

- persistent memory databases
- vector search
- workflow templates
- task schemas
- repository analyzers
- MCP server concepts
- web UI
- deterministic planner layers
- hidden convenience functions

Those belong in layers around the kernel, not in the kernel.
