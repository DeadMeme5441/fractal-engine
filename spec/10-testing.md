# 10 · Testing and Validation

Phases 1-4 are built. The default validation gate is still **offline-first**:
`clojure -M:test` must pass with no credentials and no paid calls. Live-provider
checks exist, but they are opt-in, credential-gated, and must stay clearly
separate from the default path.

The engine claim under test is not "can a model answer one prompt". It is
**long-context, long-horizon recursive work**: persistent working state, restart /
resume, branching through children and leaves, attach from prior cognitive state,
operator steering, and durable state-graph integrity over time.

This doc distinguishes three things:

1. the **current checked-in default gate**;
2. the **optional live-provider checks** that prove the real adapter/recursion path;
3. the **higher tiers still manual or not yet automated**.

---

## 1. Current validation ladder

| Tier | Command / shape | What it proves | Default path? |
|---|---|---|---|
| Offline suite | `clojure -M:test` | The implemented Phase 1-4 engine is correct under deterministic, no-spend conditions, including persistent state, recursion, resume, and Phase-4 graph integrity. `^:live` tests are excluded. | yes |
| Hygiene check | `git diff --check` | No whitespace / patch-format damage in the current change set. | recommended on every branch |
| Opt-in live suite | `clojure -M:live-test` | The real `SdkAdapter`, real provider auth, real recursion host fns, SDK surfaces, and real cost/cache/usage paths work end to end. Paid and slow. | no |
| Focused live surface smoke | `clojure -M:live-test -n fractal.engine.live-surface-test` | A real provider can use an injected Git-like surface across root, child, and leaf request paths. | no |
| Second-provider smoke | manual live smoke on a second provider stack | The runtime is not accidentally coupled to one provider family. | no |

**Current implementation evidence.** The present Phase 1-4 line has already been
validated with a green offline suite, a clean `git diff --check`, the checked-in
live suite, and an additional manual second-provider smoke on `:vertex-gemini`.
Treat that as current evidence, not as the required default gate for every edit.

---

## 2. The required offline suite

The default suite is now broader than "Phase 1 unit tests". It is the main proof
that the full Phase 1-4 engine still holds together without spending money,
including the long-horizon state machinery that later phases added.

### Deterministic scripted provider

Most offline multi-step and recursive proofs still lean on the `FakeAdapter` and
`fe/responder`. The critical rule is unchanged: scripted replies should be a **pure
function of the request**, not a mutable response queue. That keeps fan-out tests
deterministic and order-independent even after recursion and parallel lanes arrive.

### Foundations and pure-value surfaces

- `time_test` — time formatting contract.
- `payload_test` — canonical bytes, `sha256:` refs, content-addressing, dedup.
- `payload_io_test` — inline-vs-ref payload decisions, hydration, EDN-safe coercion.
- `catalog_test` — model-catalog lookup and unknown-model tolerance.
- `prompt_cache_test` — prompt stamping, stable cache id / scope id / TTL shape.
- `surface_test` — SDK surface descriptor validation, public stamps, prompt-card
  rendering, dynamic prompt functions, and resume compatibility errors.
- `observe_test` — fit-or-stub rendering, bounded inspect text, observation format.
- `concurrent_test` — deadline wrapper, bounded fan-out, pool bounds, dynamic-binding
  propagation.

### Sandbox and adversarial regressions

- `sci_sandbox_test` — the pinned SCI behavior: interop denied, reader-eval blocked,
  escape hatches withheld, gated `slurp` survives `in-ns`, multi-form REPL
  interleaving holds, injected namespace catalog behaves as expected.
- `capability_test` — profile ordering, clamp, loosening rejection, dangerous-class
  validation, default-vs-locked-down runtime gates.
- `review_fixes_test` — regressions found by adversarial review stay fixed:
  infinite-seq bounding, file / URL path gating, case-insensitive scheme denial,
  `io/copy` gating, invalid allow-all class grants, terminal stop delivery.

### Store, kernel, runtime, and API

- `store_test` — pure `apply-event`, folded structure, status transitions.
- `store_memory_test` — id assignment, re-fold equivalence, idempotent create, dedup,
  read-your-writes view, backlog recovery, `peek-next-id`, dangling-ref verification.
- `blobstore_test` — on-disk blob ids, round-trips, dedup, missing-blob behavior.
- `store_contract_test` — the shared `SessionStore` invariants executed against both
  MemoryStore and SqliteStore, including Phase-4 head / edge behavior.
- `store_sqlite_test` — persist-before-fold, batch atomicity, durable reopen, global
  blob sharing, end-to-end resume after process restart.
- `kernel_test` — block extraction, persistent vars, batch semantics, `FINAL`,
  stdout capture, stripped eval records, snapshot / restore, large FINAL handling.
- `session_test` — turn loop, multi-step REPL use, vars across turns, honest
  accounting, no-fence recovery, max-steps, context-window abort, eval-error
  recovery, async behavior, stop ordering, compaction behavior.
- `compaction_test` — compaction thresholds and role-labeled transcript rendering.
- `live_test` — live subscriber ordering, slow / throwing subscribers, overflow gap
  policy, reentrancy rejection, pure progress projection.
- `api_test` — the public API end-to-end example, progress, payload hydration,
  event tailing.
- `surface_session_test` — root SCI injection, denied function absence, system
  prompt ordering, dynamic request prompt transience and cache breakpoint shape,
  leaf prompt wiring, child inheritance, durable resume stamp matching, and a
  concrete Git-like surface through recursion and leaves.

### Recursion and Phase 4

- `recursion_test` — offline proofs for `lm`, `map-lm`, `rlm`, `map-rlm`, nested
  recursion, fan-out caps, partial failure sentinels, capability inheritance and
  clamp, hot-swap by config alone, `:locked-down` host-fn dropping, resume in `:rlm`
  mode, durable child sessions.
- `phase4_test` — immutable head publication, resume preferring `current-head`,
  invocation lineage edges, and `attach-rlm` restoring from a selected prior head
  into a fresh derived child.

### Structural guard

- `deps_acyclic_test` — the namespace graph stays acyclic and the deliberate layer
  boundaries still hold.

---

## 3. Failure modes already pinned

The suite now covers a meaningful set of "it broke in the real world" cases, not
just happy-path construction.

- **Sandbox escape attempts.** Interop, `#=` reader-eval, `eval`, `load-string`,
  `requiring-resolve`, URL reads, case-variant schemes, `file:` prefix bypasses,
  and `io/copy` file-argument escapes are pinned as denied.

- **Capability mistakes.** A child cannot loosen its parent; invalid
  `{:allow :all}` class grants are rejected; dangerous classes require explicit
  unsafe intent.

- **Turn-control failures.** Max steps return `:budget-exceeded`; a hard
  context-window overflow aborts; a concurrent turn throws
  `:fractal/turn-in-flight`; max turns fail before the busy CAS; async turn
  results are delivered without leaving the session spuriously busy.

- **Recovery after model mistakes.** A missing code fence yields the "no fence"
  nudge; an eval error becomes an observation and the turn can recover on a later
  step; a multi-form block still behaves like a REPL.

- **Live-query backpressure.** A throwing or slow subscriber cannot stall the
  writer; overflow drops only transient deltas and emits a `:subscribe/gap`;
  reentrant append from a callback is rejected.

- **Durability and store consistency.** The store assigns ids; fold reproduces the
  view; append batches are atomic; durable writes happen before the view advances;
  missing blobs are handled explicitly; resume after reopen restores vars and
  continues the session.

- **Phase-4 lineage mistakes.** `publish-head!` rejects stale expected bases;
  `resume-session!` restores from `current-head`; successful child work produces
  durable invocation / derivation edges; `attach-rlm` restores from the selected
  source head without advancing the source session.

- **Recursive partial failure.** One bad leaf parse or one budget-exhausted child
  lane turns into an index-aligned sentinel; other lanes still return normally.

---

## 4. Optional live-provider testing

The checked-in live suite exists to prove the real provider path, not to replace the
offline gate.

### `clojure -M:live-test`

This suite currently has two shapes:

- `live_recursion_test` — live recursion proofs over a real OAuth-backed provider:
  leaf judgment, map-lm order at the fan-out cap, single-child `rlm`, independent
  `map-rlm` lanes, nested recursion, partial-failure resilience, cheapness
  adherence, hot-swap, capability clamp, and honest cost separation across the
  tree.

- `live_smoke_test` — a smaller non-recursive live smoke over a second provider
  style, proving that a real model can drive the REPL to `FINAL` through the real
  `SdkAdapter`.

- `live_surface_test` — a real-provider smoke over an injected Git-like SDK
  surface. It proves that the model can call configured surface functions, that
  child sessions inherit the surface, and that leaf prompt context participates
  in a live run.

Two important truths about the current live suite:

1. it is **real-provider / real-engine** testing, with paid calls and real auth;
2. many of the recursion mechanism tests invoke `lm` / `map-lm` / `rlm` /
   `map-rlm` directly inside a live session's SCI ctx for determinism, instead of
   always starting from a natural user prompt at the root.

That direct-host-fn shape is intentional today: it keeps the ground truth crisp
while still exercising the real provider, the real adapter, the real child-session
machinery, and the real storage/event pipeline.

### Second-provider live smoke

The current Phase-4 line has also been manually smoked on `:vertex-gemini`. That
extra run is useful because it proves the recursion/runtime path is not only green
on the checked-in live provider family. It is **evidence**, not yet a checked-in
default lane.

### CLI trace readback

The old standalone trace harness has been retired. Human inspection now goes
through the public control-plane seam:

```sh
clojure -M:cli trace --config fractal.edn --session demo --pretty
```

`trace` reopens a durable session, selects a turn, hydrates assistant code and
observation messages, and includes the hydrated final value when a final ref is
present. It is useful for tuning observation shape and for understanding
multi-step / recursive behavior, but it is a diagnostic command, not an
automated gate.

---

## 5. What remains manual or unguarded

The test story is materially stronger now, but it is not yet a full long-horizon
recursive continuity program.

- **No always-on multi-provider matrix.** One checked-in live provider family and one
  smaller live smoke exist; broader provider drift still needs explicit manual runs.

- **Most live recursion proofs are mechanism-first, not root-prompt-first.** Only a
  subset of live tests force the root model to choose the whole decomposition from a
  natural user request. More of those are needed for genuine end-to-end confidence.

- **No crash / process-kill fault injection mid-turn.** Resume after committed turns is
  covered; interrupted live turns, restart in the middle of recursion, and partial
  provider-call failure around durable boundaries still need explicit fault testing.

- **No sustained load / performance / cost regression gate.** The suite proves
  correctness under bounded fan-out and durable reopen, but not long-running store
  growth, large-blob churn, or cost-envelope stability over time.

- **The CLI trace view is diagnostic, not a full semantic verifier.** It is useful
  for observation-surface tuning, and its core hydration path is covered by CLI
  tests, but pretty rendering is not the proof of runtime correctness.

- **Prompt quality is only partly pinned.** Cache ids, prompt stamping, and SDK
  surface prompt placement are tested, but real-provider behavior quality still
  depends on live scenario choice and manual judgment.

---

## 6. Recommended future tiers (not current gates)

If the goal is **genuine end-to-end long-horizon testing**, the next layers should be:

1. **Root-prompt live scenarios** on at least two provider families, where the test
   starts from a natural user request and asserts not just the final value but also
   the stored session / head / edge shape.

2. **Operator-steered continuity scenarios** across many turns: stop / resume, branch
   from a prior head, attach into fresh children, and continue work without losing
   the working state or corrupting lineage.

3. **Durability fault-injection scenarios**: commit boundary crashes, resume after
   interrupted recursion, and attached-child restore after restart.

4. **Load / performance / cost scenarios**: larger fan-outs, many durable sessions,
   big payload churn, and explicit time / cost envelopes.

The rule stays the same: keep the default gate deterministic and offline, and make
the live tiers explicit about what they prove and what they still do not prove.
