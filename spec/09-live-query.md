# 09 · Live Query & Streaming

While a session runs, an external observer must be able to query it **live** — mid-turn,
mid-step. This is a first-class read-surface requirement, and it falls almost free out
of the event-sourced store: every state change is already an appended event, so "watch
it live" is just tapping the same stream the store folds. `fractal.engine.live`.

---

## 1. The three ways to observe

1. **Async turn.** `run-turn-async!` (07) runs the turn on a daemon future; the caller
   gets a `TurnHandle` and observes concurrently while the turn proceeds.
2. **Ref-free snapshot.** `current-view`/`read-state` (02) and a cheap derived
   `progress` are non-blocking against writes — live reads NEVER take the write lock
   (02 §8).
3. **Push feed.** `subscribe!` fires a callback on each appended event (and each
   transient delta); `events-since` lets an observer that joins mid-session catch up the
   backlog, then tail live.

```clojure
(progress handle) →
  {:session/id … :session/status :running
   :running?   true
   :turn-count 3 :current-turn 3
   :step-count 5 :in-flight true
   :last-event-id 142}
```

`live/progress` is a cheap, **pure** derivation over a **view value** — no store dep, no
payload hydration, safe to poll at high frequency. The public `progress` (06 §3) reads
the strong `current-view` (02 §5) then hands that value to `live/progress`.

> **Layering (GD8).** `fractal.engine.live` is a **pure mechanism** with **no store
> dependency**: the per-session ordered dispatch, `schedule-notify`, `notify-transient`,
> the `*in-dispatch*` guard, the bounded queue + `:drop-transient` + `:subscribe/gap`,
> and `progress` (over a view value). The `SessionStore` methods `subscribe!`/
> `events-since`/`notify-transient` (02 §4) are **implemented in `store.memory`**, which
> **delegates to `live`** (`store.memory → live`, so `live` is built first — 11):
> `subscribe!`/`notify-transient` drive the dispatch, `events-since` serves the backlog
> from the view's `:events`. The loop and api call only the port methods; only
> `store.memory` knows the live mechanism exists.

## 2. The per-session ordered dispatch

Notifications are delivered **out of the write lock** through a single ordered
per-session dispatch (a queue/agent/`core.async` chan on the slot). `append-event!`,
inside the lock, captures the stamped event + a subscriber snapshot, then schedules
delivery *after releasing the lock*. One dispatcher delivers in `:event/id` order.

⛔ Why (each fixes a real defect):
- A subscriber callback runs on the **dispatch** thread, never holding the write lock —
  so a slow or blocking subscriber can't stall writes.
- Callbacks are wrapped in `try/catch` — a throwing/broken subscriber can't corrupt a
  write; log + drop (or auto-unsubscribe) on throw.
- Single ordered dispatcher ⇒ no out-of-order or reentrant-reordered delivery.

### Reentrancy guard

A callback must not drive the session it's observing. While invoking callbacks the
dispatch binds a dynamic `*in-dispatch*` (the session id); `append-event!`/`run-turn!`/
`compact-session!` check it and throw `:subscribe/reentrant` if set for that session.
Callbacks must be fast and side-effect-only w.r.t. the session.

## 3. Two layers of live data: durable vs transient

| Layer | Examples | Persisted? | Droppable? |
|-------|----------|------------|------------|
| **Durable** | `:turn/started`, `:step/started`, `:message/appended`, `:step/put`, `:eval/added`, `:turn/put` | yes (the event log) | **never** |
| **Transient** | `:delta/token` (streaming, 05 §3) `{:text … :step/id …}`, `:eval/stdout` chunks in-flight | **no** (only the completed aggregate becomes an event) | yes (on overflow) |

`:step/started` (GD1) is appended **first thing each step** (02/07) — the store assigns
the step id and marks it `:running` — so a live observer sees a step **in flight**, not
only after `:step/put` finalizes it. Without it there is no live signal that a step has
begun (the previously-open gap).

This keeps the event log from bloating to one-event-per-token. The completed assistant
message and the completed eval record are the durable truth; the deltas are a live
convenience. ⛔ Transient items (`:delta/token`, and the `:subscribe/gap` marker of §4)
carry **no `:event/id`**, are **never persisted, never folded** (02 §2), and may be
dropped on overflow; only the completed aggregate ever becomes a durable event.

## 4. Drop policy + the gap marker

`schedule-notify` is a **non-blocking offer** onto the bounded dispatch queue
(`:live/queue-bound`, default 1024) — it **never blocks the writer** (the append already
persisted + folded under the lock; delivery is best-effort and off-lock). On overflow the
offer sheds load **in this order**:
- **Transient deltas drop from delivery first** (`:live/drop :drop-transient`) — they
  carry no `:event/id` and the durable aggregate is unaffected.
- If the queue is *still* full, a **durable event is skipped from delivery only** — it
  was already persisted + folded under the lock (⛔ **never dropped from the log**); only
  its push to subscribers is skipped.
- Either way the dispatch emits **one** `:subscribe/gap {:last-delivered-event-id N}`
  marker (coalesced — not one per skipped item) so the subscriber knows to **re-sync via
  `events-since`** from `N`. A live subscriber therefore never silently loses a durable
  event: it either receives it, or learns of the gap and recovers the backlog (the log
  still holds it in full).

## 5. Streaming (opt-in)

When `:stream? true` (05 §3), `run-step!` builds the adapter's `:on-delta` (05 §1) as a
closure that calls `store/notify-transient` (02 §4) with a transient item
`{:event/type :delta/token :text d :step/id <*current-step-id*>}` — `*current-step-id*` is
the host var bound by `run-step!` (07, GD1). The adapter invokes that closure per content
fragment while the call is in flight; the loop still appends the **completed** assistant
message as the durable `:message/appended`. So you can watch the model produce its message
token-by-token, but the durable record and everything derived from it are unchanged
whether or not streaming is on.

⛔ **Concurrent enqueue.** Deltas are offered from the adapter's deadline **daemon thread**
(05 §2, 07) while durable events are appended from the loop thread — so durable and
transient **share one** per-session dispatch with a **single** ordered drain. The offer
(§2/§4) is non-blocking and **tolerates concurrent enqueue** from those two threads (a
thread-safe queue); only the lone dispatcher delivers, preserving `:event/id` order.

> **Open (decide at build time):** Phase 1 may ship with step/eval-granularity live data
> only (durable events + `:eval/stdout` transient), with `:delta/token` streaming as a
> fast-follow. The dispatch and the durable/transient split are designed for both; the
> only question is whether `SdkAdapter` wires `:on-delta` in the first cut. Default:
> wire it (it's cheap and the SDK supports it), `:stream?` off by default.

## 6. Subscribe API

```clojure
(subscribe! handle callback) → unsubscribe-fn
;; callback receives each delivered item:
;;   a durable event   {:event/id N :event/type … …}
;;   a transient delta  {:event/type :delta/token :text "…" :step/id …}        (no :event/id)
;;   a gap marker       {:event/type :subscribe/gap :last-delivered-event-id N} (no :event/id)
(events-since handle ev-id) → [events with :event/id > ev-id]   ; backlog catch-up
```

`subscribe!` and `events-since` are **`SessionStore` protocol methods** (02 §4),
re-exported by `fractal.engine.api` over a handle (06 §4). `store.memory` implements them
by **delegating to the live mechanism** (GD8): `subscribe!` registers the callback on the
per-session dispatch; `events-since` serves the backlog from the view's `:events`.

⛔ `notify-transient` (02 §4, the producer side for `:delta/token`, §5) is **internal-only**.
It is called by `run-step!`'s `:on-delta` closure to push a transient delta; it is **not**
part of the public api surface (06). Subscribers only *receive* deltas through their
callback — they never call `notify-transient`. Transient items are never persisted or
folded (§3).
