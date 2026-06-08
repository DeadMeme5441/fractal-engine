# 09 · Live Query

Live query is the readback seam for agents and supervising humans while a
session is running. The implementation is event-first: durable state changes are
already appended as events, so live observation is built by exposing that stream
plus a small transient layer for in-flight deltas.

This is not an interactive end-user UI contract. It is a control-plane contract
for polling, subscribing, replaying, and recovering audit state during or after a
run.

---

## 1. Observation surfaces

The implemented live-query surface is:

```clojure
(progress handle)
(event-stream handle)
(events-since handle ev-id)
(subscribe! handle callback)
```

These are layered over the public API and the store port:

- `progress` is a pure projection over the folded view
- `event-stream` is the full durable event vector currently folded
- `events-since` is the durable backlog recovery seam
- `subscribe!` is the push feed for durable events and transient deltas

The intended consumer pattern is:

1. read current state
2. catch up with `events-since`
3. subscribe for tail updates
4. if a gap marker arrives, recover again with `events-since`

---

## 2. Durable vs transient items

Two different kinds of items can reach a subscriber callback.

### Durable events

Examples:

- `:session/started`
- `:turn/started`
- `:step/started`
- `:message/appended`
- `:step/put`
- `:eval/added`
- `:turn/put`
- `:session/compacted`
- `:head/published`
- `:lineage/edge-added`

Properties:

- have `:event/id`
- are persisted in the durable log when using SQLite
- are folded into the current view
- are recoverable through `events-since`

### Transient items

Examples:

- `{:event/type :delta/token :text "..." :step/id ...}`
- `{:event/type :subscribe/gap :last-delivered-event-id N}`

Properties:

- have no `:event/id`
- are never persisted
- are never folded
- may be dropped from delivery under load

The durable event stream is the authoritative audit surface. Transients exist
only to improve in-flight visibility.

---

## 3. Dispatch model

Each session has one live dispatch built by `fractal.engine.live/make-dispatch`.

State includes:

```clojure
{:q              <persistent queue>
 :gap?           false
 :last-delivered 0}
```

And per dispatch:

```clojure
{:sid    "s-..."
 :bound  1024
 :drop   :drop-transient
 :subs   (atom {...})
 :alive  (atom true)
 :thread (atom nil)}
```

Important implementation properties:

- the dispatcher thread starts lazily on the first subscriber
- durable events and transient deltas share one ordered queue
- writes never invoke callbacks inline while holding the store lock
- delivery happens on the dispatcher thread only

This keeps live readback cheap and prevents observers from stalling the writer.

---

## 4. Delivery ordering

Durable events are delivered in `:event/id` order.

How that is achieved:

1. the store appends and folds an event under the store lock
2. it enqueues the stamped event for live delivery after the fold
3. one dispatcher thread drains the queue and invokes callbacks

`notify-transient` uses the same dispatch queue, so callbacks observe one
session-local ordered stream of:

- durable events
- token deltas
- gap markers

`step/started` is durable and is appended first in every step. That is why a
subscriber can observe an in-flight step before the assistant message or evals
arrive.

---

## 5. Overflow and the gap marker

Enqueue is intentionally non-blocking. If the queue is full:

1. evict queued transient items first
2. if still full, skip push delivery of some durable item
3. set the coalesced `:gap?` flag

When the dispatcher later drains the queue, it emits one gap marker:

```clojure
{:event/type :subscribe/gap
 :last-delivered-event-id N}
```

Recovery rule:

- treat `N` as the last durable event definitely delivered
- call `events-since` with that id

Durable events are never removed from the durable log by overflow. At worst
their push delivery is skipped and the subscriber must recover from the log.

---

## 6. `events-since`

`events-since` returns durable events whose `:event/id` is strictly greater than
the supplied id.

Properties:

- ordered
- durable-only
- backed by the folded event vector
- safe to call repeatedly

It is the intended readback seam for:

- joining mid-session
- recovering after `:subscribe/gap`
- reconstructing a recent audit window without replaying the entire log

---

## 7. Subscriber failure and reentrancy

### Throwing callbacks

Callback invocation is wrapped in `try/catch`.

Current behavior:

- a throwing subscriber does not break the writer
- the throwing subscriber is removed
- other subscribers continue receiving events

### Slow callbacks

Slow subscribers cannot stall writes because callbacks run on the dispatcher
thread, not inside the store lock.

### Reentrancy guard

While a callback is running, `fractal.engine.live/*in-dispatch*` is bound to the
session id. Same-session write-side entry points reject reentrant calls:

- `append-event!`
- `run-turn!`
- `run-turn-async!`
- `compact-session!`

They throw:

```clojure
{:error/type :subscribe/reentrant}
```

This prevents a subscriber from recursively driving the same session while it is
observing it.

---

## 8. Token streaming

When `:stream? true`, each adapter call receives an `:on-delta` callback that
publishes transient token fragments:

```clojure
{:event/type :delta/token
 :text       "..."
 :step/id    3}
```

Properties:

- produced while the adapter call is in flight
- never persisted
- may be dropped on overflow
- share the same dispatch queue as durable events

The durable assistant message still arrives later as `:message/appended`. Token
streaming is purely an in-flight convenience layer.

---

## 9. Progress projection

`progress` is a pure function over the folded view:

```clojure
{:session/id     "s-..."
 :session/status :running | :stop-requested | :stopped | :error
 :running?       true | false
 :turn-count     3
 :current-turn   3 | nil
 :step-count     5
 :in-flight      true | false
 :last-event-id  142}
```

It does not hydrate payloads and does not call the provider. It is intended for:

- status polling
- machine-readable summaries
- lightweight human monitoring

Because it reads only the folded view, it remains available mid-turn without
contending with the writer.

---

## 10. Readback framing

The implementation should be described as a scriptable audit/readback seam:

- durable events are primary
- `events-since` is the recovery primitive
- subscriptions are best-effort push on top of the durable stream
- progress is a cheap summary projection
- payload hydration stays explicit through the API

That framing matches the actual consumers: agents executing through the API/CLI,
with humans supervising from reports and state summaries rather than from a
productized interactive shell.
