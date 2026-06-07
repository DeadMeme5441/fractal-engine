# 07 · Runtime: Config, Concurrency, Compaction

`fractal.engine.config` (config), `fractal.engine.session` (turn lifecycle + the
turn-lock), `fractal.engine.session-loop` (the step loop), `fractal.engine.compaction`
(v1-style compaction).

---

## 1. `make-config`

```clojure
{:adapter        :sdk                ; :sdk | :fake — RECORDS the impl choice; the instance
                                     ;   is constructed at start-session!, not here (05, GD5)
 :model          "claude-opus-4-8"   ; concrete model id; provider + :context-window resolve
                                     ;   from it via the SDK catalog (§1, §4, 05)
 :fake/respond   nil                 ; respond-fn for the fake adapter (05, 10)
 :provider/config nil                ; OPTIONAL opaque credential override {:api-key …}|{:auth-token …};
                                     ;   threaded onto SdkAdapter at start-session! (05 §2/§6, D9).
                                     ;   nil/absent ⇒ the SDK's own env-var defaults. Pass-through only.

 :capability     :default            ; the DEFAULT capability profile (a name or a profile value) (04)

 :max-steps      25                  ; per-TURN cap on steps (the model's moves within one turn)
 :max-turns      nil                 ; optional per-SESSION cap on turns

 :call-timeout-ms 120000             ; wall-clock budget for ONE adapter call INCLUDING SDK retries
 :retry          true                ; nil/false one-shot · true → SDK default policy · map → merged
 :stream?        false               ; opt-in token streaming (05 §3); default off

 :cache-ttl      "1h"                ; "5m" | "1h" (08). Validated at config time; unknown ⇒ throw.

 :store          :memory             ; :memory (Phase 1). Phase 2 adds :sqlite under the same port.

 :live/queue-bound 1024              ; per-session live dispatch queue bound (09)
 :live/drop      :drop-transient     ; overflow policy: drop transient deltas, never durable events (09)

 :context        {:compact-at 0.80   ; compaction & hard-abort ratios (§4)
                  :hard-at    0.95
                  :unknown-window-chars 400000}  ; safety net when the model window is unknown (§4)

 :system-overlay nil}                ; extra engine-level system text (05 §4)
```

`make-config` normalizes: merges defaults, validates `:cache-ttl`, validates/clamps the
default capability profile, **records the `:adapter` choice keyword** (it never constructs
the instance or requires the adapter impls — `start-session!` is the sole composition root
that builds the adapter, GD5/§5/05), and **resolves + stamps `:context-window`** from the
model id via the engine-free SDK **catalog** (`fractal.engine.catalog`, a wrapper over
`llm.sdk` `model-context-length`; `:unknown` when the catalog doesn't know — §4 then uses
the `:unknown-window-chars` fallback). A static catalog lookup is allowed outside the
adapter; only *completions* go through `-complete` (GD20). The **model id is concrete**;
role→model resolution (`:root`/`:leaf`/`:child`, Phase 3) is engine config, never the
adapter's job.

> ⛔ **`max-steps` is NOT `max-turns`.** v1's `max-turns` actually bounded *steps within
> a turn* — that collides with the ratified `turn ⊃ step` split. Here `:max-steps` caps
> a turn's steps (exceeding ⇒ `TurnResult :status :budget-exceeded`, error type
> `:fractal/max-steps`); the optional `:max-turns` caps a session's turns (exceeding ⇒
> `run-turn!`/`run-turn-async!` **throw** `(ex-info … {:error/type
> :fractal/session-turn-limit …})` **before the turn-lock CAS** — async throws
> synchronously on the caller thread, *before* spawning the future, GD17. There is no
> `:session/turn-limit` *status*: it is a thrown guard, never a `TurnResult`).

---

## 2. Concurrency: two locks (the store lock + the turn-lock)

Two named per-session locks, two jobs (GD27a):

- The **store lock** (`:lock` on the `MemoryStore` slot, 02 §7) serializes **every**
  `append-event!`. Durable writes are linearized through it, off the live-read path.
- The **turn-lock** (the `busy` atom on the handle, 02 §5) bounds the *turn-running*
  writer: at most one turn drives the step loop at a time, CAS-acquired.

A turn's eval/loop thread is the sole writer **while a turn runs**. A few **control
events** (`:session/started` at create; `:session/stop-requested`; the idle
`:session/stopped`) are appended from a non-turn thread (e.g. a `stop-session!` caller) —
they still serialize on the store lock, so they never tear a concurrent durable write.
Live reads (`current-view`/`progress`/`subscribe!`) take **neither** lock (02 §8, 09).

`start-session!` (the composition root) stashes `:cfg` and `:adapter` on the handle
(GD5, 02 §5 / 05); `run-turn!`/`run-loop!`/`run-step!`/`compact-session!` read both off it.

```clojure
(defn run-turn! [handle msg]
  (or (reject-if-stopped! handle)        ; GD27c: :stop-requested/:stopped/:error ⇒ RETURN an :error TurnResult (pre-CAS), never throw
      (do
        (check-turn-limit! handle)       ; GD17:  :max-turns reached ⇒ throw :fractal/session-turn-limit (pre-CAS)
        (when-not (compare-and-set! (:busy handle) false true)
          (throw (ex-info "turn in flight" {:error/type :fractal/turn-in-flight})))
        (try
          (when (compaction/should-compact? (current-view handle) (:cfg handle))   ; GD28: assess BEFORE the turn…
            (compaction/compact-session! handle))                                  ; …compact via the IMPL, under the held turn-lock
          (let [tid (open-turn! handle msg)]   ; GD26: appends :user (+ :message/turn-id) then :turn/started; returns the turn id
            (run-loop! handle tid))            ; GD25: (run-loop! handle turn-id)
          (finally (reset! (:busy handle) false))))))

(defn run-turn-async! [handle msg]
  (if-let [stopped (reject-if-stopped! handle)]  ; GD27c: pre-CAS, synchronous; RETURN a handle whose :promise is ALREADY delivered with that :error TurnResult (so @(:promise th) works)
    {:turn/id nil :promise (doto (promise) (deliver stopped))}
    (do
      (check-turn-limit! handle)           ; GD17, synchronous (throws BEFORE the future)
      (when-not (compare-and-set! (:busy handle) false true)
        (throw (ex-info "turn in flight" {:error/type :fractal/turn-in-flight})))
      (let [tid (try (when (compaction/should-compact? (current-view handle) (:cfg handle))
                       (compaction/compact-session! handle))          ; GD28, under the held lock
                     (open-turn! handle msg)                          ; SYNCHRONOUS → real store-assigned id (02 §8)
                     (catch Throwable e
                       (reset! (:busy handle) false)                  ; GD27b: a sync failure releases busy…
                       (throw e)))                                     ; …then rethrows on the caller thread
            p   (promise)]
        (future                                                        ; daemon; now the sole writer
          (try
            (let [res (try (run-loop! handle tid)
                           (catch Throwable e (error-result handle tid e)))]
              (reset! (:busy handle) false)                           ; ⛔ release BEFORE delivering (the re-invoke race)
              (deliver p res))
            (finally                                                   ; GD27b: ALWAYS settle…
              (reset! (:busy handle) false)                           ; idempotent
              (when-not (realized? p)                                  ; …even if error-result itself threw
                (deliver p {:status :error :session/id (:session/id handle) :turn/id tid
                            :error {:error/type :fractal/internal       ; FULL nested TurnResult shape, never a bare error map (06 §5)
                                    :error/message "async turn failed to settle"}})))))
        {:turn/id tid :promise p}))))
```

`open-turn!` (in `session`, GD26): get the id via `(peek-next-id store sid :turn)`, append
the `:user` `:message/appended` with `:message/turn-id` set to it, append `:turn/started`
carrying `:turn/id` + `:turn/user-message-id`, and **return the turn id**.

`error-result` maps a `Throwable` to a full `TurnResult` (06 §5): it `finalize-turn!`s
first (appends the terminal `:turn/put`, §3) then projects `{:status … :error
(err->map e)}` with the namespaced error map (GD13: `:error/type`/`:error/message`/
`:error/data`); a deadline ⇒ `:timeout`, `:fractal/max-steps` ⇒ `:budget-exceeded`,
otherwise `:error`.

**`stop-session!` (in `session`, GD27c).** Idempotent. If the session is **idle** (no turn
in flight) it appends **both** `:session/stop-requested` and `:session/stopped`. If a turn
**is in flight** it appends only `:session/stop-requested`; the running loop sees the flag
at the next step boundary and appends `:session/stopped` itself (§3). `{:wait? true}`
blocks on the turn-lock, then appends `:session/stopped`. New `run-turn!`/`run-turn-async!`
calls against a `:stop-requested`/`:stopped`/`:error` session are rejected pre-CAS by
`reject-if-stopped!`, which **returns** an `:error` `TurnResult` (`:fractal/session-stopped`,
the full nested-`:error` shape) — never a throw: `run-turn!` returns it directly,
`run-turn-async!` hands back a handle whose `:promise` is already delivered with it (GD27c).

⛔ Two ordering rules the v1 code got wrong (still load-bearing):
1. **`open-turn!` runs synchronously on the caller thread**, so the store-assigned turn id
   in the `TurnHandle` is real; do **not** "peek" on the caller thread and assign on the
   future thread (that splits the writer identity, 02 §8).
2. **Release the busy flag before delivering the promise.** Delivering first lets a caller
   `@p` then immediately `run-turn-async!` and hit the still-true flag → spurious
   `:fractal/turn-in-flight`. The `finally` is a *backstop* (idempotent release + a
   fallback delivery so the promise always settles, GD27b), not the normal release point.

Do **not** wrap the future in `bound-fn` for `*current-turn-id*`/`*current-step-id*`/
`*current-eval-id*` — those bind *inside* the loop (03 §7; §3), so `bound-fn` at spawn
captures nil. (`bound-fn` is for Phase-3 fan-out, where the parent is already bound.)

---

## 3. The step loop (`session-loop/run-loop!` → `run-step!`)

`(run-loop! handle turn-id)` (GD25) iterates `run-step!` until
`:final`/`:error`/`:timeout`/`:budget-exceeded`/stop. One `run-step!` is the spine from
01 §"One step". **The loop owns the `:turn/*`/`:step/*`/`:message/*` appends; the kernel
owns the per-block `:eval/added` appends** (GD14):

1. **(before-step)** If `:session/stop-requested`, append `:session/stopped`,
   `finalize-turn!` (`:turn/status :error`), and return the `:error` `TurnResult`
   `{:status :error :error {:error/type :fractal/session-stopped …}}` (GD27c).
2. **Open the step.** Append `:step/started` — the store assigns `:step/id`,
   `:step/status :running`, `:step/started-at`, `:step/turn-id` (GD1). Bind
   `kernel/*current-step-id*` to that id for the rest of the step, so live observers see
   the step **in flight** and every child record (assistant/observation message, eval)
   stamps it.
3. **Assemble the request.** `(adapter.request/build-request store (current-view handle)
   cfg)` (05; GD10 moved it to `adapter.request`) — prune before `:compact-from-event-id`,
   hydrate `:message/content` (payload-io), `:observation`→`:user`, prepend the system
   message.
4. **Assess context** (`compaction/assess`, §4): **mid-step does ONLY the hard-abort**
   (GD28) — if over `:hard-at`, end the turn `{:status :budget-exceeded :error/type
   :fractal/context-window}`. Compaction (over `:compact-at`) runs **before the next
   turn** (§2), never mid-step.
5. **Adapter call.** Wrap the adapter's `-complete` in `concurrent/with-deadline
   :call-timeout-ms` (GD18 — applied ONCE here, covering both fake + sdk; the adapter no
   longer self-deadlines, 05) on a daemon thread (a stuck provider can't pin JVM exit; an
   orphaned call may still cost — document). The adapter retries internally when not
   streaming; the deadline wraps the whole retry loop. Pass `:on-delta` — a closure over
   `store` + `*current-step-id*` calling `notify-transient` (02 §4, GD29) with a transient
   `{:event/type :delta/token :text frag :step/id <step-id>}` (no `:event/id`, never
   persisted/folded — 09). Map exhausted-retries → `{:status :error :error/type
   :provider/failed}`; a fired deadline → `{:status :timeout :error/type :fractal/deadline}`.
6. **Finalize the step.** Append the `:assistant` `:message/appended`
   (`:message/step-id <step-id>`, content interned if large) and `:step/put` — the call
   record sans `:text`: `:step/status :done`, `:step/ended-at`, `:step/assistant-message-id`,
   `:step/response` = `{:finish-reason :usage :cost :model :provider :cache}` (the bare
   `:cache` key, 02 §1 / 08 / GD30).
7. **Eval the code.** `(kernel/eval-batch handle turn-id blocks)` (03) → eval records +
   maybe-FINAL. **The kernel appends each `:eval/added`** (stamping `:eval/step-id`); the
   loop does not.
8. **Append the observation.** One combined `:message/appended` `:message/role
   :observation` (`:message/step-id <step-id>`, 03 §5). No fence ⇒ a nudge observation;
   the turn stays open.
9. **Decide.** `:final` → `commit-turn!`; a non-`:final` terminal status
   (`:timeout`/`:budget-exceeded`/`:error`) → `finalize-turn!`; a continuable `:error`/`:ok`
   → next step; steps reached `:max-steps` → `finalize-turn!`
   `{:status :budget-exceeded :error/type :fractal/max-steps}`.

`commit-turn!` (in `session-loop`, GD4 — keeping the loop off `session` breaks the
`session ↔ session-loop` cycle):
1. Intern the FINAL value → `:turn/final-ref` (content-addressed, 02 §3) + compute
   `:turn/final-preview`.
2. Snapshot the REPL vars (`kernel/snapshot-vars` → canonical, content-addressed) and
   append **`:session/vars-snapshotted`** (`:vars-ref`) **just before** the final
   `:turn/put` (GD3; compaction instead carries its snapshot *inside* the single
   `:session/compacted` event, §4).
3. Append `:turn/put` (`:turn/status :final`, `:turn/ended-at`, `:turn/final-ref`/
   `:turn/final-preview`, and the rolled-up `:turn/usage`/`:turn/cost`/`:turn/cache` —
   summed over **this turn's** steps `:unknown`-aware: any `:unknown` summand ⇒ the rollup
   is `:unknown`, never a fabricated total, GD12).
4. Hydrate the FINAL value; return the `TurnResult` (06 §5). `:step-count` **derives** by
   filtering `:steps` on `:turn/id` (GD16 — there are no `:turn/step-ids`).

`finalize-turn!` (in `session-loop`, the non-`:final` sibling of `commit-turn!`, GD4) does
the same for **every other terminal outcome** — `:timeout`, `:budget-exceeded`, and
`:error` (the hard-abort, fired-deadline, exhausted-retries, `:max-steps`, mid-turn stop,
and `error-result` paths). It appends a finalizing `:turn/put` (`:turn/status` = that
terminal value, `:turn/ended-at`, and the same `:unknown`-aware `:turn/usage`/`:turn/cost`/
`:turn/cache` rollups, GD12) **before** the `TurnResult` is built — so every result is
genuinely projected from the committed turn (06 §5) and the live view never shows a
terminated turn still `:running`. A non-final turn carries **no** `:turn/final-ref`/
`:turn/final-preview` and no vars snapshot.

---

## 4. Compaction (adopt v1's mechanism, adapted)

> **Decision: do compaction exactly as v1 does**, adapted to SCI vars + the event-sourced
> store. The mechanism is not redesigned — only the wiring changes.

**Assess** (`compaction/assess`) — estimate the request's input tokens and compare to the
model's context window:
- **Token estimate** = `ceil(total-chars / 4)` over the hydrated `:message/content` of
  every assembled message (GD41) — cheap, provider-agnostic, no tokenizer dependency.
- **Window** = cfg `:context-window` (resolved once at `make-config` via the catalog,
  §1 / GD20). When it is `:unknown`, fall back to the **char** cap `:unknown-window-chars`
  (cfg `:context`) — comparing `total-chars` directly.
- ⛔ **Unknown-window safety net:** never let an unknown window silently disable *both*
  compaction and the hard-abort (the transcript would grow unbounded) — the
  `:unknown-window-chars` fallback always yields a bound.

Ratios from cfg `:context`:
- over `:hard-at` (0.95) → **hard-abort** the turn mid-step (`:budget-exceeded` /
  `:fractal/context-window`, §3).
- over `:compact-at` (0.80) → **compact before the next turn** (`should-compact?` in
  `run-turn!`/`run-turn-async!`, §2) — never mid-step.

**Compact** (`compaction/compact-session!`, the impl). Called either from §2's auto path
**under the already-held turn-lock**, or from the public `session/compact-session!`, which
first CASes the turn-lock — throwing `:fractal/turn-in-flight` if a turn is running (GD28).
It reads `:cfg`/`:adapter` off the handle:
1. Format the completed transcript with a **role-labeled formatter** over hydrated
   `:message/content` (GD41 / GD11) and send it to the **root model** via the session's
   adapter (`-complete`) with the compaction system prompt (12); it returns plain text —
   ONE continuation frame summarizing the conversation so far. ⛔ Wrap this `-complete` in
   `concurrent/with-deadline :call-timeout-ms` (like `run-step`, §3 / GD18) with opts
   `{:retry (:retry cfg) :stream? false}` — compaction runs under the **held turn-lock**, so
   an un-bounded provider hang would wedge the whole session.
2. Record that text as a **synthetic `:user` message**. ⛔ Its message id is **stamped by
   `append-event!`** (the store counter), **not** invented inside the fold.
3. **Snapshot the REPL vars** for durability (`:vars-ref`).
4. Append **ONE `:session/compacted` event** carrying `{:vars-ref, the stamped compact
   message, :compact-from-event-id}` (a single event — avoid the two-event torn-write
   window, 02 §2). `build-request`'s **prune-before** thereafter keeps every message whose
   owning message-bearing event (`:message/appended` or `:session/compacted`) has
   `:event/id` **≥** `:compact-from-event-id` — derived by scanning the view's `:events`
   (each carries both its `:event/id` and its `:message`), so the compact frame itself
   (whose own `:session/compacted` `:event/id` **==** the boundary) and everything after
   survive: the provider sees `[system, compact-frame, …new…]` while the full history stays
   in the log (audit).

⛔ **Compaction does NOT restore/clear the REPL vars.** The live SCI ctx keeps every var
(including `:unrestorable` ones, usable live); compaction only snapshots + rewrites the
transcript (03 §6). Var *restore* (clear+`sci/intern`) is resume/fork only (Phase 2/4).

⛔ The estimator/formatter reads `:message/content` (+ `:message/id`/`:message/turn-id`) —
feed it hydrated messages in that shape. payload-io `hydrate-message` renames
`:message/content-or-ref` → `:message/content` (GD11); the formatter consumes that shape,
not `{:role :content}`, or token estimates and the transcript header break.

> In Phase 1 (in-memory, no cross-process resume) the `:vars-ref` snapshot is written for
> durability/audit and the Merkle-DAG seam (02 §9); it is not read back. That's correct.

---

## 5. Namespace responsibilities (don't blur them)

- `fractal.engine.session` — `start-session!`/`stop-session!`, `run-turn!`/
  `run-turn-async!`, `open-turn!` (appends the `:user` message **and** `:turn/started`),
  the turn-lock, and the **public** `compact-session!` (CAS the turn-lock → delegate to the
  compaction impl). The **turn** lifecycle. **Not** `commit-turn!`/`finalize-turn!` (GD4).
  (`stop-session!` ordering: §2 / GD27c.)
- `fractal.engine.session-loop` — `run-loop!`/`run-step!` **and** `commit-turn!`/
  `finalize-turn!`: the deadline, the adapter call, `eval-batch`, per-step
  assess/hard-abort, and the loop's `:turn/put`/`:step/*`/`:message/*` appends
  (`:turn/started` belongs to `open-turn!` in `session`, §2; the kernel owns the per-block
  `:eval/added` appends, GD14). `commit-turn!`/`finalize-turn!` live here so the loop never
  depends on `session` — that breaks the `session ↔ session-loop` cycle (GD4). The
  **step** loop. (This is where the timeout + adapter + eval actually live.)
- `fractal.engine.config` — `make-config` + normalization/validation (catalog lookup for
  `:context-window`; never constructs the adapter — GD5 / GD20).
- `fractal.engine.compaction` — `assess`, `should-compact?`, the `compact-session!` impl,
  the role-labeled transcript formatter.

> Request assembly (`build-request`) is **not** here — it lives in
> `fractal.engine.adapter.request` `(build-request store view cfg)` (GD10 / 05), an L3 ns
> the loop requires (alongside `compaction`), built on `cache`/`prompt`/`payload-io`
> (the dependency manifest, 01 / GD19).
