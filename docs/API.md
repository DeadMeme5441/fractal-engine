# Clojure API

`fractal-engine.api` is the supported public facade for Clojure consumers. Use it to
start sessions, run turns, read artifacts, and inspect claim provenance without
depending on runtime namespace details.

The `fractal` CLI is built on the same engine, but it is only one consumer. Library
code should prefer:

```clojure
(require '[fractal-engine.api :as fe])
```

## Dependency shape

Use the public repository as a Git dependency until a packaged release is available:

```clojure
{:deps {io.github.deadmeme5441/fractal-engine
        {:git/url "https://github.com/DeadMeme5441/fractal-engine"
         :git/sha "<commit-sha>"}}}
```

Your application can add its own namespaces to the classpath. The engine does not need
a special integration layer for them: the session overlay or task prompt can ask the
model to require those namespaces and call their public functions from the session
REPL.

## Session And Drive

```clojure
(def cfg
  (fe/config {:runs-dir ".fractal"
              :models {:root  {:provider :scripted :model "scripted"}
                       :leaf  {:provider :scripted :model "scripted"}
                       :child {:provider :scripted :model "scripted"}}}))

(def s
  (fe/start-session! cfg {:id "demo"
                          :dir ".fractal/demo"
                          :overlay "Additional application role instructions can go here."}))

(def result
  (fe/run-turn! s "Define x and FINAL {:answer 42}."))

(fe/stop-session! s)
```

Public drive functions:

| function | purpose |
|---|---|
| `config` | normalize generic engine configuration |
| `start-session!` | start a live session; accepts `:id`, `:dir`, and `:overlay` |
| `run-turn!` | run one user message on a live session |
| `stop-session!` | mark the session stopped and flush artifacts |
| `resume-session!` | restore from a completed turn snapshot into a live session |
| `fork-session!` | branch a session into a new run directory |
| `run-task!` | one-shot helper: start, run one task, stop |

The overlay is appended once to the base system message. It is a standing session
specialization carried by the transcript; it is not a per-turn task and does not add
new model-facing functions.

## Model-Facing Surface

The public Clojure API is for host applications. It does not change what the model can
call inside the session REPL. The model-facing surface remains exactly:

```text
FINAL lm map-lm rlm map-rlm attach-rlm
```

Application-specific patterns should live in your application namespaces or in vars the
model defines during the session, not in the engine runtime API.

## Read Surface

Artifacts are read from the append-only journal and folded into data. These functions
perform no provider calls:

| function | purpose |
|---|---|
| `view` | fold a run directory's journal into the materialized view |
| `load-node` | load one node, including steps, leaves, children, and final value |
| `load-at` | resolve an address within a root run and load that node |
| `tree` | load the recursively expanded summary tree |
| `node-dir` | resolve a node address to an on-disk directory |
| `journal-events` | read the raw append-only event stream |

Example:

```clojure
(def root (fe/load-node (:dir result)))
(def full-tree (fe/tree (:dir result)))
(def events (fe/journal-events (:dir result)))
```

## Trust And Provenance

The trust helpers expose the same claim-vs-evidence data used by `fractal verify`:

| function | purpose |
|---|---|
| `extract-claims` | collect evidenced claims from a final value |
| `check-claims` | check cited evidence against files, optionally relative to a base dir |
| `summarize-claims` | roll check verdicts into totals and an overall status |
| `node-provenance` | return a node's final value, claims, child refs, and leaf refs |

Example:

```clojure
(def node (fe/load-at (:dir result) "root"))
(def checks (fe/check-claims (:final node) "."))
(fe/summarize-claims checks)
```

## Provider Helpers

Provider auth is data:

| function | purpose |
|---|---|
| `provider-descriptor` | return the descriptor for a provider id |
| `auth-status` | report whether credentials are currently available |

```clojure
(fe/provider-descriptor :scripted)
(fe/auth-status :scripted)
```

## Intentionally Not Exposed

Rendering functions remain outside the stable API. `fractal-engine.render` returns
human-oriented strings that include CLI command hints and formatting choices; those are
allowed to evolve with the CLI. Library consumers should use the data-returning API
functions and render their own views.

The compute internals also remain outside the public facade: `process/run-process!`,
runtime eval helpers, artifact writers, event projectors, cache internals, and prompt
construction are implementation details. Public consumers should treat the journal and
projection data returned by `fractal-engine.api` as the supported read contract.
