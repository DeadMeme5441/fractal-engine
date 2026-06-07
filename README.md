# fractal-engine

A small **recursive language-model compute engine**. A model drives a persistent
Clojure REPL: it writes fenced Clojure, the host evaluates it in a sandboxed
interpreter and returns a compact observation, and the loop repeats until the model
calls `(FINAL value)`. Some of the functions the model can call are *themselves*
language models — so a problem can be decomposed into sub-problems, each solved by a
fresh recursion of the same loop.

> **Phase 1 is built.** The complete non-recursive session core — the uniform step
> loop, the SCI eval kernel, the capability sandbox, the event-sourced in-memory
> state port, the adapter (sdk + fake), the public API, config, live-query, and
> compaction — is implemented from **[`spec/`](spec/)** under the `fractal.engine.*`
> namespace root. `clojure -M:test` is green offline (no API keys). The recursive
> layer (`lm`/`map-lm`/`rlm`/`map-rlm`, Phases 3/4) and persistent storage (Phase 2)
> are out of scope and only have clean seams reserved.
>
> The spec in `spec/` (`00`–`12`) is the source of truth and is deliberately
> complete on its own. (A v1 reference implementation lives in the sibling
> directories `../fractal-engine` and `../fractal-engine-seeing`.)

## Quick start

```clojure
(require '[fractal.engine.api :as fe])

;; offline, no keys — the FakeAdapter scripts the model
(def cfg (fe/make-config {:adapter :fake :model "fake-model" :capability :default
                          :fake/respond (fe/responder
                                          [[:default "```clojure (FINAL {:answer 42})```"]])}))
(def s   (fe/start-session! cfg))
(def res (fe/run-turn! s "What is 6 times 7?"))
(:turn/final-value res)   ;=> {:answer 42}

;; live — a real provider via the sibling SDK (model resolves the provider)
(def cfg (fe/make-config {:adapter :sdk :model "deepseek-chat" :capability :default}))
```

The session stays live across turns; `def`'d REPL vars persist; each `run-turn!`
returns when the model calls `FINAL`.

## Develop

- `clojure -M:test` — the full offline suite (incl. the pinned SCI sandbox test,
  the snapshot/restore round-trip, and the namespace-graph acyclicity test).
- `clojure -M:live-test` — the optional live smoke test (needs a provider key in
  the env; never on the default/CI path).
- `clojure -M:dev -e "(require 'seeing) (seeing/demo)"` — the RUNS/SEES dev harness:
  prints the model's code beside the engine's fit-or-stub observations.

## What to read

- **[`spec/README.md`](spec/README.md)** — index, golden rules, the document map.
- **[`spec/00-vision-and-scope.md`](spec/00-vision-and-scope.md)** — what this is and why.
- **[`spec/01-architecture.md`](spec/01-architecture.md)** — layers, ontology, the acyclic dependency manifest.

## Stack

Clojure on the JVM · model-code eval via **SCI** (`org.babashka/sci`) · provider
access via the sibling **clojure-llm-sdk**. JDK 21+ and the Clojure CLI.
