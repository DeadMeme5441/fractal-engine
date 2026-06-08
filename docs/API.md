# API

`fractal.engine.api` is the supported Clojure SDK facade. It is the public
embedding surface for starting sessions, running turns, reading durable state,
subscribing to live events, hydrating payloads, and reopening durable sessions.

The facade is intentionally small. Model-facing host functions are injected into
session runtimes; they are not ordinary vars exported from this namespace.

## Public Namespace

```clojure
(require '[fractal.engine.api :as fe])
```

```mermaid
flowchart LR
  CFG["make-config"] --> START["start-session!"]
  CFG --> RESUME["resume-session!"]
  START --> RUN["run-turn! / run-turn-async!"]
  RESUME --> RUN
  RUN --> READS["view / progress / events-since / read-payload"]
  RUN --> COMPACT["compact-session!"]
  COMPACT --> READS
  START --> STOP["stop-session!"]
  RESUME --> CLOSE["close-session!"]
  RUN --> CLOSE
  STOP --> CLOSE
```

Exported functions:

```clojure
(fe/make-config opts)

(fe/start-session! cfg)
(fe/start-session! cfg opts)

(fe/run-turn! handle msg)
(fe/run-turn-async! handle msg)

(fe/resume-session! cfg sid)
(fe/resume-session! cfg sid opts)

(fe/stop-session! handle)
(fe/stop-session! handle opts)

(fe/close-session! handle)
(fe/compact-session! handle)

(fe/view handle)
(fe/progress handle)
(fe/event-stream handle)
(fe/events-since handle event-id)
(fe/read-payload handle ref-or-value)
(fe/subscribe! handle callback)

(fe/responder clauses)
```

`responder` is a fake-adapter helper for tests and local examples.

## CLI Facade

The command-line facade is `fractal.engine.cli`, exposed by the `:cli` alias:

```sh
clojure -M:cli <command> [options] [args]
```

It is a thin process-per-command wrapper over this API. The CLI should not be
used as a different semantic layer: `run` and `turn` call `run-turn!`, `resume`
reopens through `resume-session!`, `compact` calls `compact-session!`, readback
commands call `view`, `progress`, `events-since`, or `read-payload`, and config
resolution ends in `make-config`.

The CLI defaults to JSON for agent automation and supports `--edn` when callers
need exact Clojure values such as payload refs. Its full command contract is in
[Agent Control Plane](AGENT_CONTROL_PLANE.md).

## Configuration

`make-config` normalizes and validates a config map. It records adapter choice,
resolves the capability profile, resolves known model context windows, applies
defaults, and validates enumerated options.

Required or notable options:

```clojure
{:adapter          :fake | :sdk
 :model            "model-id"
 :provider         :provider-id        ; optional explicit provider override
 :provider/config  {...}               ; optional, provider-specific SDK config
 :fake/respond     respond-fn          ; required when :adapter is :fake
 :capability       :locked-down | :default | :trusted | profile-map
 :harness          :clojure | :rlm
 :store            :memory | :sqlite
 :store/dir        "relative-or-configured-dir"
 :max-steps        25
 :max-turns        nil
 :call-timeout-ms  120000
 :retry            true
 :stream?          false
 :cache-ttl        "1h"                ; "5m" or "1h"
 :live/queue-bound 1024
 :live/drop        :drop-transient
 :context          {:compact-at 0.80
                    :hard-at 0.95
                    :unknown-window-chars 400000}
 :system-overlay   nil}
```

`make-config` throws `ex-info` for invalid config, including:

```clojure
:config/invalid-cache-ttl
:config/invalid-adapter
:config/missing-responder
:config/missing-model
:config/invalid-harness
:config/invalid-store
:config/missing-store-dir
:capability/unknown-profile
:capability/invalid
```

The default harness is `:clojure`. Use `:rlm` only when the model should receive
the recursive host-function surface described below.

## Fake Adapter Example

The fake adapter runs fully offline and is the simplest way to test API usage.

```clojure
(require '[fractal.engine.api :as fe])

(def cfg
  (fe/make-config
    {:adapter :fake
     :model "fake-model"
     :capability :default
     :fake/respond
     (fe/responder
       [[:default "```clojure\n(FINAL {:answer 42})\n```"]])}))

(def handle (fe/start-session! cfg {:id "example-session"}))

(try
  (let [result (fe/run-turn! handle "What is 6 times 7?")]
    (:turn/final-value result))
  (finally
    (fe/stop-session! handle)))
;; => {:answer 42}
```

`fe/responder` accepts `[[match reply] ...]` clauses. `match` can be a substring
of the last user message, a predicate on the provider request, or `:default`.
`reply` can be an assistant string, a call-record map, or a function of the
provider request.

## Live Provider Pattern

The live adapter is `:sdk`. Provider credentials are not part of this repository
surface. Pass provider config from your runtime environment or omit
`:provider/config` and let the SDK use its own defaults.

```clojure
(require '[fractal.engine.api :as fe])

(def provider-config
  (cond-> {}
    (System/getenv "MODEL_API_KEY")
    (assoc :api-key (System/getenv "MODEL_API_KEY"))))

(def cfg
  (fe/make-config
    {:adapter :sdk
     :provider :your-provider
     :model "provider-model-id"
     :capability :default
     :provider/config provider-config}))

(def handle (fe/start-session! cfg))
```

If `:provider` is omitted, the engine attempts to resolve a provider from the
model catalog. If the model is unknown and no explicit provider is supplied,
session start throws `:config/unknown-model`.

Do not log or commit credential values. Treat `:provider/config` as runtime
configuration and keep public examples placeholder-only.

## Durable SQLite Example

Use `:store :sqlite` with `:store/dir` to persist the session event log and
content-addressed payloads. Durable sessions can be closed and reopened with
`resume-session!`.

```clojure
(require '[fractal.engine.api :as fe])

(def session-id "durable-example")

(def cfg
  (fe/make-config
    {:adapter :fake
     :model "fake-model"
     :capability :default
     :store :sqlite
     :store/dir "var/example-session-store"
     :fake/respond
     (fe/responder
       [["define" "```clojure\n(def remembered 7)\n(FINAL :defined)\n```"]
        ["use" "```clojure\n(FINAL (* remembered 6))\n```"]])}))

(let [h1 (fe/start-session! cfg {:id session-id})]
  (try
    (fe/run-turn! h1 "define the value")
    (finally
      (fe/close-session! h1))))

(let [h2 (fe/resume-session! cfg session-id)]
  (try
    (:turn/final-value (fe/run-turn! h2 "use the value"))
    (finally
      (fe/close-session! h2))))
;; => 42
```

`resume-session!` is public but marked alpha. It currently requires
`:store :sqlite`; using it with `:store :memory` throws
`:config/unsupported-store`. Resuming an unknown session id throws
`:fractal/unknown-session`.

## Running Turns

`run-turn!` is blocking:

```clojure
(fe/run-turn! handle "user message")
```

It returns a `TurnResult` when a turn opens and the runtime reaches a modeled
terminal outcome. It also returns a `TurnResult` when the session is already
stopped and no new turn is opened.

```clojure
{:status          :final | :error | :timeout | :budget-exceeded
 :session/id      "session-id"
 :turn/id         1
 :turn/final-value {:answer 42}     ; present only when :status is :final
 :turn/usage      {:usage/status :known | :unknown, ...}
 :turn/cost       {:cost/status :known | :unknown, ...}
 :turn/cache      {:cache/status :hit | :miss | :unknown, ...}
 :step-count      1
 :error           nil | {:error/type keyword, :error/message string, ...}}
```

Status meanings:

- `:final`: the model called `FINAL`.
- `:timeout`: the adapter call exceeded `:call-timeout-ms`.
- `:budget-exceeded`: max steps or hard context-window limit stopped the turn.
- `:error`: provider failure, model/runtime error, stopped session, or another
  non-final terminal failure.

`run-turn!` throws for pre-turn failures, including:

- `:fractal/session-turn-limit`
- `:fractal/turn-in-flight`
- `:subscribe/reentrant`
- config/provider setup errors
- auto-compaction errors before the turn opens
- unexpected uncaught internal errors on the synchronous path

## Async Turns

`run-turn-async!` opens a turn synchronously and runs the step loop on a
background future:

```clojure
(def async-result (fe/run-turn-async! handle "user message"))

(:turn/id async-result)
;; => 2

@(:promise async-result)
;; => TurnResult
```

Caller-thread behavior:

- stopped sessions return `{:turn/id nil :promise p}` where `p` is already
  delivered with an error `TurnResult`
- `:max-turns`, `:turn-in-flight`, reentrant writes, and pre-open compaction
  failures throw synchronously

Promise behavior:

- the promise delivers a `TurnResult`
- the turn lock is released before promise delivery
- unexpected async failures are converted to an internal error result when the
  future cannot otherwise settle

## Lifecycle

`stop-session!` requests a stop:

```clojure
(fe/stop-session! handle)
(fe/stop-session! handle {:wait? true})
```

It appends `:session/stop-requested`. If no turn is running, it also appends
`:session/stopped`. With `:wait? true`, it waits for the turn lock before
ensuring the stopped state.

`close-session!` releases process-local resources:

```clojure
(fe/close-session! handle)
```

For SQLite stores, this closes the JDBC connection and stops dispatchers. A
closed durable session can be reopened with `resume-session!`.

`compact-session!` forces compaction immediately:

```clojure
(fe/compact-session! handle)
```

It acquires the turn lock and throws `:fractal/turn-in-flight` if another turn
is running. Compaction can make a provider call and appends durable compaction
events when successful.

## Reads And Live Events

Read functions do not make provider calls:

```clojure
(fe/view handle)
(fe/progress handle)
(fe/event-stream handle)
(fe/events-since handle event-id)
(fe/read-payload handle ref-or-value)
(fe/subscribe! handle callback)
```

`view` returns the strong folded session view from the store. It may contain
payload refs for large messages, final values, eval records, or snapshots.

`progress` returns a small polling shape:

```clojure
{:session/id     "session-id"
 :session/status :running | :stop-requested | :stopped | :error
 :running?       true
 :turn-count     1
 :current-turn   1
 :step-count     1
 :in-flight      false
 :last-event-id  8}
```

`event-stream` returns the folded durable event list currently present in the
view. `events-since` returns ordered durable events with `:event/id` greater
than the supplied id.

`read-payload` is the supported hydration seam:

```clojure
(fe/read-payload handle maybe-ref)
```

It dereferences payload refs and returns non-ref values unchanged.

`subscribe!` registers a callback for durable events and transient deltas:

```clojure
(def unsubscribe
  (fe/subscribe! handle
    (fn [event]
      (println (:event/type event)))))

(unsubscribe)
```

A subscriber callback must not write to the same session. Reentrant writes throw
`:subscribe/reentrant`. Subscribers should use `events-since` to recover from
`:subscribe/gap` notifications.

## Harnesses And Model-Facing Functions

The engine has two harness modes:

- `:clojure`: default harness. The model-facing runtime includes `FINAL` and
  inspection support.
- `:rlm`: recursive harness. The runtime also injects `lm`, `map-lm`, `rlm`,
  `map-rlm`, and `attach-rlm`, subject to the capability profile.

The model-facing functions are:

```clojure
FINAL
lm
map-lm
rlm
map-rlm
attach-rlm
```

They are available inside the session's evaluated Clojure when the selected
harness and capability profile allow them. They are not exported as public
Clojure API functions.

In `:rlm` mode:

- `lm` performs one bounded leaf provider call.
- `map-lm` performs ordered fan-out over leaf calls, capped by `:max-fanout`.
- `rlm` starts a fresh child session in the same store and runs it to `FINAL`.
- `map-rlm` performs ordered fan-out over child sessions, capped by
  `:max-fanout`.
- `attach-rlm` starts a fresh derived child from a selected prior session/head;
  the source session is not advanced.

Child calls return envelopes containing `:rlm/value`, `:rlm/session`,
`:rlm/head`, and `:rlm/meta`. Partial fan-out failures are represented in-place
as sentinel maps such as `{:fractal/failed true, ...}` rather than aborting the
whole batch.

Root turn usage and cost stay scoped to the root turn. Child accounting is
reported in the child envelope metadata.
