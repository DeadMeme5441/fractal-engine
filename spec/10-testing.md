# 10 · Testing Strategy

**All of Phase 1 builds and validates offline — no API keys, no spend.** The model is
replaced by a deterministic `FakeAdapter`; the store is in-memory; the SCI kernel runs
real Clojure. Live model runs are a separate, optional smoke check.

---

## 1. `FakeAdapter` + `responder` (the backbone)

The `FakeAdapter` (05 §5) is a *pure function of the request*, so scripted multi-step
runs are deterministic and order-independent (essential once fan-out arrives). Script a
whole turn arc:

```clojure
(def respond
  (fe/responder
    [["count the errors" "```clojure\n(def n (count (filter #(str/includes? % \"ERROR\")\n                                  (str/split-lines (slurp \"log.txt\")))))\nn\n```"]
     [#(str/includes? (last-user %) "Observation") "```clojure (FINAL {:errors n})```"]
     [:default "```clojure (FINAL :ok)```"]]))
```

Each clause: `[match reply]` — `match` is a substring of the last user message, a
predicate on the request, or `:default`; `reply` is an assistant string, a full call
record, or a fn of the request. ⛔ Do **not** use a mutable response queue — undefined
order under concurrency; the content-addressed responder is race-free.

## 2. The RUNS / SEES dev harness

Port v1's "seeing harness" as a dev tool (under a `:dev`/`:seeing` path, never in the
build): it drives the **real** session loop and prints, per step, what the model
**RUNS** (`:assistant` messages — the code) beside what the engine **SEES** (the
`:observation` messages — the fit-or-stub feedback), plus an untruncated FINAL the model
only saw as a stub. This is the fastest way to tune the observation surface (`ok-fit`,
`max-coll-size`) against realistic code. **The output format below is normative** (the
dev harness must produce it): one `RUNS` block per `:assistant` message and one `SEES`
block per `:observation`, in `:event/id` order, closing with the untruncated FINAL value
(which the model itself only saw as a fit-or-stub).

```
──── fractal RUNS ────   (def files (->> (file-seq …) (mapv str)))
──── fractal SEES ────   «vector, 412 items»
──── fractal RUNS ────   (count files)
──── fractal SEES ────   412
──── FINAL (full, harness view) ────   {:n 412 …}
```

## 3. Component tests

- **State port (02).** `apply-event` purity; `fold(events) == read-state` structure;
  store-assigned ids == folded counter max, always; persist-before-fold (Phase 2);
  idempotent `create-session!` (second call preserves state); `verify-no-dangling-refs`;
  **content-addressing: identical values intern to the identical ref (dedup), and the
  ref id is `sha256:` over canonical bytes** (Merkle invariant, 02 §9).
- **Eval kernel (03).** Vars persist across evals and turns (the `eval-string*` ctx);
  batch semantics (error stops batch; FINAL ends turn; multi-block ids distinct via
  `peek-next-id`); **a multi-form block REPL-interleaves** — a `require`/`in-ns`/`def` in
  form 1 is visible to form 2 and the block value is the *last* form's value (the
  `sci/eval-string*` interleave guarantee, 03 §2); **the per-step `(in-ns …)`
  re-assertion holds** — a model `(in-ns …)` does not strand later host evals
  (`eval-batch` re-asserts the session ns as its first action, 03 §2); stdout captured
  via `sci/out`; fit-or-stub thresholds (small → whole, large → `«type, size»`);
  `inspect` bounded + chrome-stripped; snapshot round-trips and marks unrestorables;
  **restore via `sci/intern` round-trips a list value `(1 2 3)` correctly** (the
  eval-string trap, 03 §6) — the snapshot/restore plumbing is pinned to SCI 0.8.43.
- **Capability sandbox (04).** The **pinned SCI regression test** (04 §7) — interop
  denied, `slurp`/`sh` absent, `#=` blocked, gated-slurp shadow survives `in-ns`,
  `binding` allowed. Plus: `:default` reads a file but refuses `(slurp "http://…")` and
  refuses `git`/`python3` via `sh`; `clamp` yields the meet; an override that loosens any
  gate is rejected; a `:locked-down` child of a `:default` parent (inherit-and-clamp).
- **Adapter (05).** `FakeAdapter` returns a well-formed call record; `:observation`→
  `:user` mapping; honest `:unknown` when the responder omits usage; **the deadline lives
  in `run-step!`** (07, `with-deadline :call-timeout-ms`), not inside any adapter — so a
  slow **fake** responder trips it → `:status :timeout` (the same wrapper covers
  `SdkAdapter`, which carries no internal deadline; `FakeAdapter` ignores `opts`).
- **Runtime (07).** `run-turn!` blocks to FINAL and hydrates the value; `run-turn-async!`
  delivers a `TurnResult` map (incl. on error); `:fractal/turn-in-flight` on a concurrent
  turn; **release-before-deliver** (deref + immediate re-invoke does not spuriously throw
  turn-in-flight); `:max-steps` → `:budget-exceeded`; `stop-session!` ordering (no
  `:turn/*` event after `:session/stopped`).
- **Compaction (07).** Fires over `:compact-at`; produces `[system, compact-frame,
  …new…]` via `:compact-from-event-id`; the compact message id is store-stamped;
  **vars are NOT cleared/restored during compaction** (an unrestorable var defined
  before compaction is still usable after).
- **Live query (09).** A subscriber sees events in `:event/id` order; a throwing
  subscriber doesn't break writes; a slow subscriber doesn't stall writes; overflow drops
  only transient deltas and emits a `:subscribe/gap`; `events-since` recovers the
  backlog; reentrant `append-event!` from a callback throws `:subscribe/reentrant`.

## 4. Determinism rules

- No `Math/random`/`Date.now` in tested code paths where reproducibility matters; inject
  clocks/ids where a test needs to pin them. (Real `sha256`/timestamps are fine in
  production paths; tests that assert hashes use fixed input values.)
- The pinned SCI regression test **and the snapshot/restore round-trip** (both pinned to
  SCI 0.8.43) are **CI-blocking** on any `org.babashka/sci` bump.
- No live provider call in any unit test. A single optional `live-smoke` test (behind a
  flag + real key) exercises `SdkAdapter` end-to-end; it is never on the default path.
