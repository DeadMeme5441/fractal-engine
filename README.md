# fractal-engine

A small **recursive language-model compute engine**. A model drives a persistent
Clojure REPL: it writes fenced Clojure, the host evaluates it in a sandboxed
interpreter and returns a compact observation, and the loop repeats until the model
calls `(FINAL value)`. Some of the functions the model can call are *themselves*
language models — so a problem can be decomposed into sub-problems, each solved by a
fresh recursion of the same loop.

> **Phases 1 & 2 are built.** The complete non-recursive session core — the uniform
> step loop, the SCI eval kernel, the capability sandbox, the event-sourced state
> port, the adapter (sdk + fake), the public API, config, live-query, and compaction
> — is implemented from **[`spec/`](spec/)** under the `fractal.engine.*` namespace
> root, plus **Phase 2 durable storage**: a SQLite `SessionStore` + a file-based,
> content-addressed blob store slotted under the *same* port (zero loop changes), and
> durable `resume-session!` (reopen a persisted session by folding its event log and
> restoring its REPL vars). `clojure -M:test` is green offline (no API keys). The
> recursive layer (`lm`/`map-lm`/`rlm`/`map-rlm`, Phases 3/4) is out of scope and only
> has clean seams reserved.
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

### Durable sessions (Phase 2)

Add `:store :sqlite :store/dir "..."` and the event log + content-addressed blobs
persist to disk. Close the handle, then `resume-session!` reopens it — folding the
durable log and restoring the live REPL vars — so a new turn can use state from
before the restart:

```clojure
(def cfg (fe/make-config {:adapter :fake :model "fake-model" :capability :default
                          :store :sqlite :store/dir "/tmp/fe-demo"
                          :fake/respond (fe/responder
                                          [["define" "```clojure (def x 7) (FINAL :ok)```"]
                                           ["use"    "```clojure (FINAL (* x 6))```"]])}))
(def s (fe/start-session! cfg {:id "demo"}))
(fe/run-turn! s "define the value")     ;=> {:status :final :turn/final-value :ok}
(fe/close-session! s)                   ; releases the connection (e.g. process exit)

(def s2 (fe/resume-session! cfg "demo")) ; fresh process: fold the log + restore vars
(:turn/final-value (fe/run-turn! s2 "use it")) ;=> 42  (x survived the reopen)
```

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
