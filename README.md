# fractal-engine

A small **recursive language-model compute engine**. A model drives a persistent
Clojure REPL: it writes fenced Clojure, the host evaluates it in a sandboxed
interpreter and returns a compact observation, and the loop repeats until the model
calls `(FINAL value)`. Some of the functions the model can call are *themselves*
language models — so a problem can be decomposed into sub-problems, each solved by a
fresh recursion of the same loop.

> **Phases 1, 2, 3 & 4 are built.** The complete non-recursive session core — the uniform
> step loop, the SCI eval kernel, the capability sandbox, the event-sourced state
> port, the adapter (sdk + fake), the public API, config, live-query, and compaction
> — is implemented from **[`spec/`](spec/)** under the `fractal.engine.*` namespace
> root, plus **Phase 2 durable storage**: a SQLite `SessionStore` + a file-based,
> content-addressed blob store slotted under the *same* port (zero loop changes), and
> durable `resume-session!`. **Phase 3 adds the RLM recursion layer**: the four
> model-calling host fns (`lm`/`map-lm` leaves, `rlm`/`map-rlm` children) and a
> **hot-swap** between a plain coding harness and the recursive harness *by config
> alone*. **Phase 4 adds the durable recursion data model**: immutable content-addressed
> heads, current-head publication, invocation/derivation lineage edges, and
> `attach-rlm` for deriving a fresh child from a prior session/head. `clojure -M:test`
> is green offline with no API keys, and a live codex-OAuth
> suite drives real recursion end to end (`clojure -M:live-test`).
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

### The recursion layer + the hot-swap (Phase 3)

Two harnesses live in one repo, **selected by config alone** (`:harness`, default
`:clojure`):

- **`:clojure`** — the plain coding harness. The REPL exposes only `FINAL`/`inspect`
  and the Phase-1 prompt. Byte-for-byte the Phase-1/2 behaviour.
- **`:rlm`** — the recursive harness. The REPL *also* exposes four model-calling host
  fns, `attach-rlm`, and the model gets the recursion doctrine:
  - `(lm input query [mode])` / `(map-lm inputs query [mode])` — **leaves**: one bounded
    model call (no session, no loop); `map-lm` fans out over ≤50 inputs, order-preserved.
  - `(rlm task)` / `(map-rlm tasks [shared])` — **children**: a fresh recursion (its own
    SCI ctx + session, capability inherited-and-clamped) running the whole loop to
    `FINAL`; returns an envelope `{:rlm/value … :rlm/session … :rlm/meta …}`.
  - `(attach-rlm handle task [opts])` — **reuse**: restore a prior session/head into a
    fresh derived child, run `task`, and return the same envelope shape. The source
    session is not advanced; lineage records the derivation edge.

Switching is zero code edits — it drives both the system prompt and the injected fns.
The capability profile still gates egress: **`:locked-down` drops `lm`/`rlm`**.

```clojure
;; codex via OAuth (creds read from ~/.codex/auth.json); :provider is explicit because
;; codex model ids resolve to :openai in the catalog.
(def cfg (fe/make-config {:adapter :sdk :provider :codex-backend :model "gpt-5.5"
                          :harness :rlm :capability :default}))
(def s (fe/start-session! cfg))
;; the model decomposes via map-rlm / map-lm and aggregates in Clojure
(fe/run-turn! s "Sum each of these 8 CSV blobs' amount column …")
```

`:turn/usage`/`:turn/cost` stay **self-only** at the root; each child's cost rides its
envelope's `:rlm/meta`. Partial fan-out never throws — a failed slot is a
`{:fractal/failed true …}` sentinel. Children are ordinary sessions in the same store,
so Phase-2 durability + `resume-session!` work in `:rlm` mode too. Every successful
turn publishes an immutable Merkle head; `rlm` records invocation edges and
`attach-rlm` records derivation edges.

## Develop

- `clojure -M:test` — the full offline suite (incl. the pinned SCI sandbox test,
  the snapshot/restore round-trip, the recursion suite — leaves/children/nested/
  fan-out/partial-failure/clamp/hot-swap/resume — and the namespace-graph acyclicity
  test). No API keys; never a paid call.
- `clojure -M:live-test` — the optional live suite. The codex-OAuth recursion proofs
  run from `~/.codex/auth.json` (no env key needed); the deepseek smoke test runs only
  if `DEEPSEEK_API_KEY` is set. Never on the default/CI path; makes paid calls.
- `clojure -M:dev -e "(require 'seeing) (seeing/demo)"` — the RUNS/SEES dev harness:
  prints the model's code beside the engine's fit-or-stub observations.

## What to read

- **[`spec/README.md`](spec/README.md)** — index, golden rules, the document map.
- **[`spec/00-vision-and-scope.md`](spec/00-vision-and-scope.md)** — what this is and why.
- **[`spec/01-architecture.md`](spec/01-architecture.md)** — layers, ontology, the acyclic dependency manifest.

## Stack

Clojure on the JVM · model-code eval via **SCI** (`org.babashka/sci`) · provider
access via the sibling **clojure-llm-sdk**. JDK 21+ and the Clojure CLI.
