# Getting Started

This guide uses only the public API and the fake adapter, so it is safe to run
offline and does not require provider credentials. The examples assume a normal
Clojure REPL with this repository on the classpath.

The public namespace is:

```clojure
(require '[fractal.engine.api :as fe])
```

The agent-operable CLI is:

```sh
clojure -M:cli <command> [options] [args]
```

Use the API when embedding the engine in Clojure. Use the CLI when an agent or
script should drive a durable session from the shell while a human inspects
structured artifacts.

## 1. Run a Fake-Adapter Turn

The fake adapter scripts what the model will say. The engine still runs the real
session loop: it appends messages and events, extracts fenced Clojure, evaluates
it in SCI, and returns only when the code calls `FINAL`.

```clojure
(def cfg
  (fe/make-config
   {:adapter :fake
    :model "fake-model"
    :capability :default
    :fake/respond
    (fe/responder
     [[:default "```clojure\n(FINAL {:answer 42})\n```"]])}))

(def session (fe/start-session! cfg))
(def result (fe/run-turn! session "What is 6 times 7?"))

(:status result)
;; => :final

(:turn/final-value result)
;; => {:answer 42}

(fe/stop-session! session)
```

Important details:

- `make-config` validates runtime options but does not construct an adapter.
- `start-session!` is the composition root that builds the store, adapter, and
  SCI context.
- `run-turn!` blocks until `FINAL` or a terminal modeled outcome.
- The returned `:turn/final-value` is hydrated through the payload layer.

## 2. Inspect the Session

Read calls do not make provider requests:

```clojure
(def session
  (fe/start-session!
   (fe/make-config
    {:adapter :fake
     :model "fake-model"
     :fake/respond
     (fe/responder
      [[:default "```clojure\n(FINAL :ok)\n```"]])})))

(fe/run-turn! session "go")

(select-keys (fe/progress session)
             [:session/id :session/status :turn-count :last-event-id])

(count (fe/event-stream session))

(map :event/type (fe/events-since session 0))
```

Use `view` when you need the full folded state, including messages, turns,
evals, heads, current-head, lineage edges, counters, and events:

```clojure
(select-keys (fe/view session) [:current-head :heads :edges])

(fe/stop-session! session)
```

Large values may be represented internally as payload refs. Use
`read-payload` on values returned by readback surfaces when you need explicit
hydration; non-refs pass through unchanged.

## 3. Drive A Durable Session With The CLI

The CLI defaults to JSON and expects explicit config and session ids for
automation. `init` creates a small fake-adapter config file that uses a durable
SQLite store under an ignored `.fractal/` directory:

```sh
clojure -M:cli init \
  --config fractal.edn \
  --store-dir .fractal/sessions/demo \
  --session demo
```

Run one turn, then read the compact report:

```sh
clojure -M:cli run \
  --config fractal.edn \
  --session demo \
  --message "return a small value" \
  --pretty

clojure -M:cli report \
  --config fractal.edn \
  --session demo \
  --pretty
```

Continue the same durable session with `turn`:

```sh
clojure -M:cli turn \
  --config fractal.edn \
  --session demo \
  --message "continue from the same REPL vars" \
  --pretty
```

Use `--edn` for exact Clojure-shaped payload refs:

```sh
clojure -M:cli turns --config fractal.edn --session demo --edn
```

The command inventory and output contract are documented in
[Agent Control Plane](AGENT_CONTROL_PLANE.md).

## 4. Reopen a Durable Session

The default store is in-memory. Use `:store :sqlite` with a writable relative
store directory when the session should survive process restart.

```clojure
(def store-dir "var/demo-store")

(def durable-cfg
  (fe/make-config
   {:adapter :fake
    :model "fake-model"
    :capability :default
    :store :sqlite
    :store/dir store-dir
    :fake/respond
    (fe/responder
     [["define" "```clojure\n(def remembered 7)\n(FINAL :defined)\n```"]
      ["use" "```clojure\n(FINAL (* remembered 6))\n```"]])}))

(def first-handle (fe/start-session! durable-cfg {:id "demo"}))

(fe/run-turn! first-handle "define the value")
;; => {:status :final, ... :turn/final-value :defined, ...}

(fe/close-session! first-handle)

(def reopened (fe/resume-session! durable-cfg "demo"))

(:turn/final-value
 (fe/run-turn! reopened "use the remembered value"))
;; => 42

(fe/close-session! reopened)
```

Durable state is not reconstructed by replaying provider calls. The SQLite event
store is folded, payloads are read through the BlobStore, and the live REPL vars
are restored from the published current head when one exists.

## 5. Try the Recursive Harness Offline

Set `:harness :rlm` to inject the recursive host functions into the model's
session REPL. The public API call shape is unchanged.

```clojure
(def recursive-cfg
  (fe/make-config
   {:adapter :fake
    :model "fake-model"
    :harness :rlm
    :capability :default
    :fake/respond
    (fe/responder
     [["Assigned task"
       "```clojure\n(FINAL {:child-answer 41})\n```"]
      ["delegate"
       "```clojure\n(FINAL (:rlm/value (rlm \"compute child answer\")))\n```"]])}))

(def root (fe/start-session! recursive-cfg))

(:turn/final-value (fe/run-turn! root "delegate to a child"))
;; => {:child-answer 41}

(fe/stop-session! root)
```

Inside the model's REPL:

- `lm` makes one bounded leaf model call.
- `map-lm` fans out leaf calls up to the configured fan-out cap.
- `rlm` spawns a fresh child session and returns an envelope.
- `map-rlm` runs independent child sessions and preserves input order.
- `attach-rlm` derives a fresh child from a selected immutable source head
  without advancing the source session.
- `FINAL` is the only way to return the turn value to the caller.

Child accounting lives in the child envelope's `:rlm/meta`; the root turn's
usage and cost remain self-only.

The same harness can be driven through the CLI by setting `:harness :rlm` in the
selected config profile and running `clojure -M:cli run` or `turn`.

## 6. Move Toward Provider-Backed Runs

Provider-backed runs use the same API with `:adapter :sdk`, a model id, and
provider configuration appropriate for the adapter. Keep fake-adapter tests as
the first validation path, then add live-provider checks only when credentials
and cost controls are intentionally configured.

The important boundary is architectural: application code should depend on
`fractal.engine.api`, not on the internal adapter, store, kernel, or recursion
namespaces.

For CLI-driven live runs, keep provider credentials outside tracked files and use
a config profile with explicit leashes such as `:max-steps`, `:max-turns`,
`:call-timeout-ms`, `:max-fanout`, and `:fanout-pool`.

## 7. Read Deeper

- [Overview](README.md): public-facing orientation and repo map.
- [Agent Control Plane](AGENT_CONTROL_PLANE.md): CLI commands, config files,
  output contract, and inspection workflows.
- [`spec/README.md`](../spec/README.md): index and golden rules.
- [`spec/00-vision-and-scope.md`](../spec/00-vision-and-scope.md): thesis,
  scope, model-facing surface, and anti-goals.
- [`spec/01-architecture.md`](../spec/01-architecture.md): layers, ontology,
  one-turn flow, one-recursive-call flow, and namespace map.
- [`spec/02-state-port.md`](../spec/02-state-port.md): event store, BlobStore,
  payload refs, heads, and lineage.
- [`spec/06-public-api.md`](../spec/06-public-api.md): supported SDK functions,
  result contracts, and readback semantics.
- [`spec/12-system-prompt.md`](../spec/12-system-prompt.md): model-facing prompt
  doctrine for the plain and recursive harnesses.
