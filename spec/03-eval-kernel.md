# 03 · Eval Kernel

`fractal.engine.kernel` is the model-code runtime. It evaluates fenced Clojure
through SCI, not JVM `eval`, and produces the durable eval records the state
port folds.

For the current Phase 1-4 implementation:

- one SCI context is owned per session
- the session namespace persists across steps and turns
- the loop owns turn/step/message appends
- the kernel owns per-block `:eval/added` appends
- successful turn completion and compaction snapshot vars for durable heads
- resume and attach restore from snapshots; compaction does not

---

## 1. One SCI context per session

Each session handle carries `:sci-ctx`, an atom holding the SCI context built by
`new-ctx`.

```clojure
(defn new-ctx [session-id capability-profile engine-fns]
  (let [ctx (sci/init (capability/sci-opts
                        (capability/validate-profile! capability-profile)
                        engine-fns))]
    (sci/eval-string* ctx (str "(in-ns '" (session-ns-sym session-id) ")"))
    ctx))
```

Important implementation detail:

- SCI does not keep the session namespace current across separate
  `eval-string*` calls.
- The engine therefore binds `sci/ns` to the session namespace around every
  block evaluation.
- That is how the implementation guarantees that later evals land back in the
  session namespace even if model code called `(in-ns ...)` earlier.

Session isolation is namespace-based:

```clojure
(session-ns-sym "s-123") => 'fractal.session.s-123
```

The persistent REPL semantics come from reusing the same SCI context value for
the life of the session.

---

## 2. Host functions and harness mode

The kernel itself provides the base host functions:

```clojure
{:FINAL   (make-FINAL)
 :inspect (make-inspect)}
```

`session/start-session!` and child-session spawners decide what else to inject:

- `:clojure` harness: `FINAL`, `inspect`
- `:rlm` harness: those plus `lm`, `map-lm`, `rlm`, `map-rlm`, `attach-rlm`

The capability profile can still withhold recursive functions even in
`:harness :rlm`. The built-in recursive surface is:

- `FINAL`
- `inspect`
- `lm`
- `map-lm`
- `rlm`
- `map-rlm`
- `attach-rlm`

They are bound inside the session SCI namespace, not exported as top-level API
functions.

SDK embedders may additionally configure namespaced surface functions such as
`jira/search` or `git/read-file`. Those functions are injected only when their
surface is configured and the resolved capability profile grants the qualified
symbol in `:surface/fns`. They never live in `clojure.core`.

### `FINAL`

`FINAL` is exception-based:

```clojure
(fn [v] (throw (ex-info "FINAL" {:fractal/final v})))
```

SCI wraps exceptions, so the kernel walks the cause chain to detect
`:fractal/final`.

### `inspect`

`inspect` prints to SCI's output stream, not the host JVM stdout:

```clojure
(fn [x]
  (binding [*out* @sci/out]
    (println (observe/inspect-text x)))
  nil)
```

This keeps inspect output inside the captured eval stdout.

---

## 3. Block extraction

The kernel only evaluates fenced Clojure blocks:

- accepts ```` ```clojure ... ``` ```` and ```` ```clj ... ``` ````
- returns blocks in textual order
- no fence means no eval work is done

`run-step!` handles the no-fence case by appending the fixed observation nudge
and continuing the turn.

---

## 4. Eval flow

`eval-batch` is the execution unit for one assistant message:

1. extract blocks
2. for each block
   - predict the next eval id with `peek-next-id`
   - bind `*current-turn-id*`, `*current-step-id*`, `*current-eval-id*`
   - evaluate one block
   - append one durable `:eval/added`
3. stop immediately on the first `:error` or `:final`
4. otherwise continue through all blocks

Return shape:

```clojure
{:eval-records [raw-rec ...]
 :status       :ok | :error | :final
 :final        {:final? true :value v}} ; only on :final
```

`eval-records` returned to the loop are **raw** records. They still carry the
live value fields needed for observation rendering and final-turn commit logic.

---

## 5. Single-block semantics

`eval-block` binds the session namespace and captures SCI output:

```clojure
(sci/binding [sci/ns  the-session-ns
              sci/out stdout-writer
              sci/err stderr-writer]
  (sci/eval-string* ctx code))
```

Properties:

- multi-form blocks use SCI's normal REPL semantics
- the returned value is the last top-level form's value
- `count-forms` uses SCI's own parser
- stdout and stderr are captured and truncated to 4000 chars
- code after `FINAL` in a block never runs

Error mapping uses `err->map`, which preserves any explicit `:error/type` found
in the exception cause chain and defaults to `:fractal/eval-error`.

The durable error shape is uniform everywhere:

```clojure
{:error/type    ...
 :error/message "..."
 :error/data    {...}}
```

---

## 6. Raw eval records vs durable eval rows

`build-eval-record` produces a **raw** record:

```clojure
{:eval/turn-id     ...
 :eval/step-id     ...
 :eval/block-index ...
 :eval/code-or-ref ...
 :eval/status      :ok | :final | :error
 :eval/stdout      "..."
 :eval/stderr      "..."
 :eval/forms-count ...
 :eval/elapsed-ms  ...
 :eval/error       ...
 :eval/raw-value   v
 :eval/raw-final   v} ; only on FINAL
```

Those raw fields are never persisted directly.

`append-eval!` transforms the raw record into the durable event payload:

1. `:eval/code-or-ref` may be content-addressed if large
2. `:eval/result-ref` stores the EDN-safe result inline or as a payload ref
3. `:eval/result-preview` stores a fit-or-stub preview
4. `:eval/raw-value` and `:eval/raw-final` are stripped
5. the durable row is appended as `:eval/added`

This split matters:

- observations render from the raw values
- the durable log stores only the stable projection

---

## 7. Observation rendering

`fractal.engine.observe` is the model-facing readback layer for eval results.

For each block, the observation includes:

- block number
- captured stdout, if any
- one of:
  - `=> value` for normal returns
  - `=> value   (FINAL)` for final returns
  - `ERROR: ...` for failures

Value rendering is fit-or-stub:

- full value when it prints within the cap
  - `ok-fit = 400`
  - `final-fit = 1200`
- otherwise a one-line stub such as:
  - `«vector, 1000 items»`
  - `«map, 20 entries»`
  - `«string, 900 chars»`

`inspect` is the deliberate peek tool for large values. It uses Orchard and
prints a bounded textual inspection into SCI stdout.

If the batch did not call `FINAL`, the observation ends with:

```text
No FINAL was called; the turn is still open.
```

The loop appends one observation message per evaluated assistant reply.

---

## 8. Snapshot and restore

The vars snapshot is the durable REPL-state seam used by heads, resume, and
attach.

### Snapshot

`snapshot-vars`:

1. enumerates vars from the session SCI namespace
2. round-trips each value through EDN where possible
3. marks unrestorable values explicitly
4. returns a canonical sorted map

Snapshot shape:

```clojure
{:vars/version 1
 :vars {"name" {:status :ok :value ...}
        "f"    {:status :unrestorable :reason "function"}}}
```

Unrestorable values are recorded, not dropped, so equal REPL states keep stable
content identity.

### Restore

`restore-vars!` is used only when materializing a different session from a
snapshot:

- `resume-session!`
- `spawn-attached!`

Restore clears the session namespace and `sci/intern`s only `:status :ok`
values. It does **not** reconstruct unrestorable values.

Why `sci/intern` matters:

- a printed list or symbol value would be re-evaluated incorrectly if restore
  used `eval-string*` plus `def`
- `sci/intern` binds the value as data

### Compaction does not restore

Compaction snapshots vars and publishes a compaction head, but it does not clear
or rehydrate the live SCI context. Live vars remain exactly as they were.

---

## 9. Kernel boundaries

The kernel owns:

- SCI context construction
- host-function makers
- fenced block extraction
- per-block evaluation
- durable `:eval/added` appends
- vars snapshot and restore helpers
- error-map normalization
- observation rendering helpers via `observe`

The loop owns:

- turn lifecycle
- step lifecycle
- adapter calls and deadlines
- assistant and observation messages
- final-turn commit and head publication

That split is what keeps the runtime deterministic and the durable log replayable
without re-running providers.
