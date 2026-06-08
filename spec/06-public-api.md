# 06 · Public API

`fractal.engine.api` is the supported embedding surface for the engine. The
runtime, agent-facing CLI control plane, and audit/readback tools should build
on this layer instead of reaching into internals.

The intended consumers are:

- agents with shell and CLI access
- supervising humans reading reports, chronicles, and summaries
- automation that needs a stable, scriptable control plane

That is why the surface stays small, explicit, JSON-friendly, resumable, and
readback-oriented. The CLI is not framed as an end-user interactive product
surface here; it is a durable control-plane seam over this API.

The public config map also exposes SDK surfaces via `:surfaces`. Surfaces are
embedder-provided descriptors for namespaced host functions, prompt metadata,
and public resume stamps. The engine validates descriptors, gates functions with
`:surface/fns`, renders prompt cards/context, and refuses durable resume when
configured surface stamps differ from the persisted session stamps.

---

## 1. Exported functions

Current public functions:

```clojure
(make-config opts)

(start-session! cfg)
(start-session! cfg opts)

(run-turn! handle msg)
(run-turn-async! handle msg)

(resume-session! cfg sid)
(resume-session! cfg sid opts)

(stop-session! handle)
(stop-session! handle opts)

(close-session! handle)
(compact-session! handle)

(view handle)
(progress handle)
(event-stream handle)
(events-since handle ev-id)
(read-payload handle ref-or-value)
(subscribe! handle callback)

(responder clauses) ; fake-adapter test helper
```

The public API is intentionally lifecycle/run/read focused. Model-facing host
functions live inside the session REPL, not here.

---

## 2. Config

`make-config` normalizes and validates runtime config. It:

- validates required fields and enumerated options
- resolves the default capability profile
- resolves `:context-window` from the model catalog or sets `:unknown`
- records adapter choice and runtime knobs
- does **not** construct the adapter instance

Adapter construction happens later in `start-session!` or `resume-session!`,
which are the composition roots.

---

## 3. Session lifecycle

### `start-session!`

```clojure
(start-session! cfg)
(start-session! cfg {:id ... :capability ... :system-overlay ...})
```

Behavior:

- builds the store from `cfg :store`
- resolves provider and constructs the adapter
- creates the store session
- builds the SCI context
- appends `:session/started`
- returns a handle

The session handle is the control-plane token for later API calls.

### `resume-session!`

```clojure
(resume-session! cfg sid)
(resume-session! cfg sid opts)
```

Status:

- public but `^:alpha`
- supported only for `:store :sqlite`

Behavior:

1. reopens the durable store
2. folds the persisted event log into a fresh in-memory view
3. rebuilds a fresh SCI context
4. restores vars from the current head's vars snapshot when present
5. falls back to top-level `:vars-ref` only if no current head exists

Failure cases throw:

- unsupported store
- unknown session id
- configuration/provider setup errors

### `stop-session!`

```clojure
(stop-session! handle)
(stop-session! handle {:wait? true})
```

Behavior:

- appends `:session/stop-requested` immediately
- if no turn is running, also appends `:session/stopped`
- if a turn is running, the loop appends `:session/stopped` at the next step
  boundary
- `:wait? true` blocks until the session is idle, then ensures stopped status

### `close-session!`

Releases process-local resources:

- memory store: stops the live dispatcher
- sqlite store: closes the JDBC connection and stops dispatchers

This is the process/control-plane shutdown seam. A closed durable session can be
reopened later with `resume-session!`.

### `compact-session!`

Forces compaction immediately under the same rules the runtime uses for automatic
compaction.

Behavior:

- acquires the turn lock
- throws `:fractal/turn-in-flight` if a turn is already running
- performs a model call to summarize the transcript
- appends one `:session/compacted` event
- publishes a `:compaction` head

Compaction failures propagate as throws; this function does not wrap them in a
`TurnResult`.

---

## 4. Turn execution

### `run-turn!`

```clojure
(run-turn! handle msg) => TurnResult
```

Execution order:

1. reject reentrant subscriber-driven calls
2. reject stopped/error sessions
3. enforce `:max-turns`
4. acquire the turn lock
5. auto-compact if needed
6. append the user message and open the turn
7. run the step loop to termination
8. release the turn lock

Returned failure vs thrown failure is deliberate.

#### Returns a `TurnResult` for modeled terminal outcomes

- normal final completion
- provider failure during the step loop
- deadline timeout during the step loop
- `:max-steps` exhaustion
- hard abort on context window
- stopped session rejection

#### Throws

- `:fractal/session-turn-limit` before opening a new turn
- `:fractal/turn-in-flight` if another turn already owns the session
- auto-compaction failure before a turn is opened
- pre-turn configuration/provider failures
- unexpected uncaught internal exceptions on the synchronous path

That distinction matters to control-plane callers:

- no opened turn means a direct throw
- modeled opened-turn failures settle as a `TurnResult`
- the sync path still exposes uncaught internal failures as throws

### `run-turn-async!`

```clojure
(run-turn-async! handle msg) => {:turn/id ... :promise p}
```

The async path uses the same runtime semantics with one important split:

- pre-turn gates still run on the caller thread
- the step loop runs on a background future

Caller-thread behavior:

- stopped session: returns a handle whose promise is already delivered with an
  error `TurnResult`
- `:max-turns`, `:turn-in-flight`, and pre-open auto-compaction failures throw
  synchronously
- successful pre-open path returns a real store-assigned `:turn/id`

Promise behavior:

- always delivers a full `TurnResult`
- never delivers a bare exception object
- releases the turn lock before promise delivery

This is the intended surface for agents that need a resumable background run
plus concurrent progress/readback.

---

## 5. Turn result contract

The stable result shape is:

```clojure
{:status          :final | :error | :timeout | :budget-exceeded
 :session/id      "s-..."
 :turn/id         7 | nil
 :turn/final-value <hydrated value> ; on :final only
 :turn/usage      {:usage/status ...}
 :turn/cost       {:cost/status ...}
 :turn/cache      {:cache/status ...}
 :step-count      4
 :error           nil | {:error/type ... :error/message ... :error/data ...}}
```

Semantics:

- `:turn/final-value` is hydrated through the payload seam
- `:step-count` is derived from folded steps for that turn
- `:turn/usage`, `:turn/cost`, and `:turn/cache` are projected from the
  committed turn entity
- root turn accounting stays self-only even when child sessions were invoked;
  child accounting lives on recursion envelopes

Status mapping used by the runtime:

- `:final` for successful `FINAL`
- `:timeout` for `:fractal/deadline`
- `:budget-exceeded` for `:fractal/max-steps` or `:fractal/context-window`
- `:error` for other terminal failures

The async promise delivers the same shape.

---

## 6. Read and audit surface

These calls do not make provider requests:

```clojure
(view handle)
(progress handle)
(event-stream handle)
(events-since handle ev-id)
(read-payload handle ref-or-value)
(subscribe! handle callback)
```

### `view`

- returns the strong folded view from `current-view`
- may contain payload refs for large values
- exposes heads, current-head, edges, events, and transcript projection data

### `progress`

- pure derivation over the folded view
- cheap to poll
- designed for control-plane status checks and JSON reporting

### `event-stream` and `events-since`

- expose durable ordered events
- `events-since` is the gap-recovery seam for live subscribers
- callers should treat the event stream as the canonical audit surface

### `read-payload`

Load-bearing public function:

- dereferences payload refs through the handle's store
- passes non-ref values through unchanged
- is the supported way for CLI/readback tools to hydrate large final values,
  messages, eval results, and vars snapshots

### `subscribe!`

- attaches a live callback for durable events and transient deltas
- is the push side of the live-query seam
- must be paired with `events-since` for gap recovery

---

## 7. Model-facing surface is inside the session, not the API

The public API deliberately does **not** export `FINAL`, `lm`, `map-lm`, `rlm`,
`map-rlm`, or `attach-rlm` as top-level functions.

Those are host functions injected into the session SCI context:

- `FINAL`
- `lm`
- `map-lm`
- `rlm`
- `map-rlm`
- `attach-rlm`

This keeps the outer control plane small while preserving the model-facing
language surface inside the session runtime.

SDK surface functions follow the same rule. A configured surface can expose
qualified calls such as `jira/search` inside the session SCI runtime when the
capability profile grants the symbol in `:surface/fns`, but those functions are
not exported from `fractal.engine.api`. The API owns the descriptor input,
session lifecycle, and readback; the embedder owns the function implementation.

---

## 8. Control-plane guidance

The current implementation is aligned for an agent-supervised control plane:

- API calls are explicit and side-effectful in clear places
- async turns expose durable ids and promise settlement
- resume is durable-store based
- live readback is event-first
- payload hydration is explicit
- human-readable summaries can be layered on top, but JSON-first/state-first
  reporting should be treated as primary

That is the framing downstream CLI and automation specs should use.
