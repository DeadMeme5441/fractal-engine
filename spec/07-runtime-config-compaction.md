# 07 · Runtime, Config, and Compaction

This spec covers the runtime knobs and execution rules that the current engine
actually implements. These settings are not decorative. They are the control
plane for agents and supervising humans to leash work, inspect progress, and
recover durable state.

Relevant namespaces:

- `fractal.engine.config`
- `fractal.engine.session`
- `fractal.engine.session-loop`
- `fractal.engine.compaction`

---

## 1. `make-config`

The normalized config shape currently includes:

```clojure
{:adapter          :sdk | :fake
 :model            "..."
 :provider         nil | <provider-keyword>
 :provider/config  nil | <opaque auth/config override>
 :fake/respond     nil | respond-fn

 :capability       <validated profile>
 :capability/name  :default | :locked-down | ...
 :harness          :clojure | :rlm

 :leaf-model       "..." | nil
 :leaf-provider    nil | <provider-keyword>
 :child-model      "..." | nil
 :child-provider   nil | <provider-keyword>

 :max-fanout       50
 :fanout-pool      16
 :leaf-concurrency 8

 :max-steps        25
 :max-turns        nil
 :call-timeout-ms  120000
 :retry            true | false | <retry-map>
 :stream?          false

 :cache-ttl        "5m" | "1h"

 :store            :memory | :sqlite
 :store/dir        nil | "..."

 :live/queue-bound 1024
 :live/drop        :drop-transient

 :context-window   <int> | :unknown
 :context          {:compact-at 0.80
                    :hard-at    0.95
                    :unknown-window-chars 400000}

 :system-overlay   nil | "..."}
```

### Validation and normalization

`make-config` currently enforces:

- `:adapter` must be `:sdk` or `:fake`
- `:fake/respond` is required when `:adapter :fake`
- `:model` is required
- `:harness` must be `:clojure` or `:rlm`
- `:store` must be `:memory` or `:sqlite`
- `:store/dir` is required when `:store :sqlite`
- `:cache-ttl` must be `"5m"` or `"1h"`

It also:

- resolves and validates the default capability profile
- stamps `:capability/name`
- resolves `:context-window` from the model catalog, or `:unknown`
- defaults `:leaf-model` and `:child-model` to the root model

It does **not** construct adapters or stores. That happens at session start or
resume time.

---

## 2. Composition roots

### `start-session!`

`start-session!` is the main composition root. It:

1. builds the store from config
2. resolves provider
3. constructs the adapter
4. constructs leaf adapter/model state
5. creates the session slot
6. builds and installs the SCI context
7. appends `:session/started`

### `resume-session!`

`resume-session!` is the durable reopen path for `:store :sqlite`. It rebuilds
the folded view from durable events, then restores REPL state from the current
head's vars snapshot.

### Child session spawners

`spawn-child!` and `spawn-attached!` are also composition roots. They reuse the
same store but create fresh sessions, fresh SCI contexts, fresh cache ids, and
their own busy locks.

---

## 3. Runtime ownership and concurrency

There are two distinct coordination mechanisms.

### Per-session store lock

Owned by the store slot. It serializes:

- `append-event!`
- `append-events!`
- `publish-head!`

This is the ordering guarantee for durable writes and folded state.

### Per-session turn lock

Owned by the handle's `:busy` atom. It serializes turn-running writers:

- `run-turn!`
- `run-turn-async!`
- `compact-session!`

This is the guarantee that only one turn or manual compaction is actively
driving session runtime state at a time.

### Read path

Live reads do not take either lock:

- `view`
- `progress`
- `event-stream`
- `events-since`
- subscriptions

That separation is what makes the engine usable as an inspectable control plane
while work is in flight.

---

## 4. Turn gating and failure boundaries

Before a turn opens, `run-turn!` and `run-turn-async!` apply these gates:

1. reject reentrant subscriber-driven calls
2. reject `:stop-requested`, `:stopped`, and `:error` sessions
3. enforce `:max-turns`
4. acquire the turn lock
5. auto-compact if needed

Failure mode by gate:

- stopped/error session: return an error `TurnResult`
- `:max-turns`: throw `:fractal/session-turn-limit`
- busy session: throw `:fractal/turn-in-flight`
- pre-open compaction failure: throw on the caller thread

Once the turn is opened, modeled terminal failures settle as turn results.
Unexpected uncaught synchronous runtime bugs still escape as throws.

---

## 5. Turn opening and step loop

`open-turn!` does two durable writes in order:

1. append the user message
2. append `:turn/started`

The user message gets `:message/turn-id` from `peek-next-id :turn` so it points
at the turn id the store will assign.

### One step

`session-loop/run-step!` currently executes in this order:

1. if the session is `:stop-requested`, append `:session/stopped` and finalize
   the turn as `:error`
2. append `:step/started`
3. build the request from the folded view
4. assess context size
5. call the adapter under `with-deadline`
6. append the assistant message and `:step/put`
7. evaluate fenced code blocks
8. append one observation message
9. either continue, finalize, or commit the turn

### Terminal conditions

The loop can terminate a turn with:

- `:final`
- `:timeout`
- `:budget-exceeded`
- `:error`

`max-steps` is enforced at the loop boundary, not inside the kernel.

---

## 6. Request sizing and hard limits

The runtime uses `fractal.engine.compaction/assess` to estimate request size.

### Estimator

- for known windows: estimate tokens as `ceil(total-chars / 4)`
- for unknown windows: compare chars directly to
  `:context :unknown-window-chars`

### Thresholds

From `cfg :context`:

- above `:compact-at` => compact before the next turn
- above `:hard-at` => hard abort the current step with
  `{:status :budget-exceeded :error/type :fractal/context-window}`

The unknown-window fallback is mandatory. An unknown catalog window is not
allowed to silently disable both compaction and the hard stop.

---

## 7. Adapter call envelope

Each step performs exactly one adapter call through `adapter/-complete` under
`concurrent/with-deadline`.

Runtime options applied there:

- `:call-timeout-ms`
- `:stream?`
- `:retry` when not streaming
- `:on-delta` callback for transient token streaming

Terminal mappings:

- deadline => `:timeout` / `:fractal/deadline`
- provider or adapter error => `:error` / `:provider/failed`
- hard context stop => `:budget-exceeded` / `:fractal/context-window`
- step cap => `:budget-exceeded` / `:fractal/max-steps`

The deadline wraps the whole adapter call path for that step, including retries.

---

## 8. Turn commit and head publication

Successful `FINAL` commit is implemented in `commit-turn!`:

1. content-address the final value as `:turn/final-ref`
2. snapshot vars and append `:session/vars-snapshotted`
3. append the final `:turn/put`
4. publish a `:turn-final` head
5. return a hydrated final value in the `TurnResult`

The head publication is part of the runtime boundary. A successful final turn is
not durable until that head exists.

### Non-final terminal outcomes

`finalize-turn!`:

- appends the final `:turn/put`
- rolls up usage/cost/cache
- does not snapshot vars
- does not publish a head

Only successful final turns and compactions publish heads.

---

## 9. Honest accounting

The loop rolls usage, cost, and cache from this turn's steps only.

Rules:

- root turn accounting is self-only
- child accounting stays on recursion envelopes
- any unknown summand yields an unknown rollup field
- the runtime never fabricates zeroes for missing provider data

This is why `TurnResult` accounting is safe for agent and human audit/reporting.

---

## 10. Compaction

Compaction is an implemented runtime path, not a placeholder.

### When it runs

- automatically before opening a turn when `should-compact?` is true
- manually through `compact-session!`

It never runs mid-step. Mid-step only the hard-abort path is allowed.

### What it does

`fractal.engine.compaction/compact-session!`:

1. reads the current folded view
2. computes `kept-messages`
3. hydrates message content
4. formats a role-labeled transcript
5. calls the root adapter with the compaction prompt
6. snapshots vars
7. appends one `:session/compacted` event
8. publishes a `:compaction` head

The compact message is stored as a synthetic `:user` message inside the
`:session/compacted` event payload.

### Boundary semantics

The compaction prune boundary is:

```clojure
(store/peek-next-id store sid :event)
```

immediately before appending `:session/compacted`.

That event's own id is the boundary, which is why the compact frame survives the
subsequent `kept-messages` scan.

### Head semantics

The compaction head has:

- `:head/kind :compaction`
- `:head/final-ref nil`
- `:head/vars-ref <snapshot>`
- `:head/compact-from-event-id <boundary>`

Compaction therefore produces a new authoritative restore boundary.

### Live vars

Compaction does **not** clear or restore the live SCI context. It snapshots vars
for durable restore and continues with the existing in-memory REPL state.

### Failure semantics

Compaction itself performs a model call. If that call fails or times out:

- manual compaction throws
- pre-turn automatic compaction throws before a new turn is opened

This is important for control-plane consumers. Compaction is a runtime operation
with real failure modes, not just a local transcript rewrite.

---

## 11. Child and attached-child runtime behavior

### `spawn-child!`

- creates a fresh child session in the same store
- clamps capability from the parent
- defaults child model/provider from child config or root config
- forces `:harness :rlm`

### `spawn-attached!`

- resolves a selected source head
- rejects sources more privileged than the caller
- creates a fresh attached child
- restores that child from the source head's vars snapshot
- records source session/head metadata on the child session map

`attach-rlm` is therefore a derived-branch operation, not a continuation of the
source session.

---

## 12. Control-plane reading

The runtime knobs here are the leashes and recovery points:

- `:max-steps`
- `:max-turns`
- `:call-timeout-ms`
- `:max-fanout`
- `:leaf-concurrency`
- `:store :sqlite` plus `resume-session!`
- event stream plus live-query gap recovery
- published heads

That is the intended framing for downstream CLI and automation docs: a
scriptable, resumable, inspectable runtime control plane over the public API.
