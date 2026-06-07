# fractal-engine

A small **recursive language-model compute engine**. A model drives a persistent
Clojure REPL: it writes fenced Clojure, the host evaluates it in a sandboxed
interpreter and returns a compact observation, and the loop repeats until the model
calls `(FINAL value)`. Some of the functions the model can call are *themselves*
language models — so a problem can be decomposed into sub-problems, each solved by a
fresh recursion of the same loop.

> **This is a from-scratch rebuild.** The implementation that previously lived here
> has been removed. Everything needed to build it is in **[`spec/`](spec/)** — a
> self-contained development specification. **Start at [`spec/README.md`](spec/README.md).**
>
> The spec is the single source of truth. Build from it; do not go looking for old
> code to copy. (A v1 reference implementation still exists in the sibling
> directories `../fractal-engine` and `../fractal-engine-seeing` if a human needs to
> consult it, but the spec is deliberately complete on its own.)

## What to read

- **[`spec/README.md`](spec/README.md)** — index, golden rules, build order.
- **[`spec/00-vision-and-scope.md`](spec/00-vision-and-scope.md)** — what this is and why.
- **[`spec/11-build-plan.md`](spec/11-build-plan.md)** — the ordered phase-1 task list to execute.

## Stack

Clojure on the JVM · model-code eval via **SCI** (`org.babashka/sci`) · provider
access via the sibling **clojure-llm-sdk**. JDK 21+ and the Clojure CLI.
