# 12 · System Prompt

The behavioural core lives in `fractal.engine.prompt`. These prompts are the doctrine for
**agents doing long-context, long-horizon recursive work** through the engine's API / CLI
control plane while a human operator supervises, steers, and consumes reports. The prompt
teaches:

- persistent working state in REPL vars across steps and turns;
- truthful readback from observed state rather than performance for a transcript;
- branching into children when a sub-problem needs its own loop;
- leaf calls for bounded semantic judgment;
- attach from a prior immutable cognitive state into a fresh derived child;
- operator steering, resumability, and state-graph integrity over time.

The namespace currently defines four stamped prompt values:

```clojure
prompt/repl-p1    ; :fractal-engine/repl-p1, version 1
prompt/repl-rlm   ; :fractal-engine/repl-rlm, version 1
prompt/leaf       ; :fractal-engine/leaf, version 1
prompt/compaction ; :fractal-engine/compaction, version 1
```

Each value carries `{:prompt/name :prompt/version :prompt/hash :prompt/text}`. The stamps
are tested for reproducibility. The current wire request, however, sends **only the prompt
text**; the metadata is not yet embedded in request or session artifacts.

---

## 1. Harness selection

`prompt/system-prompt` selects the base doctrine by harness mode:

- `:clojure` -> `:fractal-engine/repl-p1`
- `:rlm` -> `:fractal-engine/repl-rlm`
- 0-arg call defaults to `:clojure` for back-compat

This is a config-only hot swap. The prompt choice and the injected host-fn surface move
together:

- `:clojure` means a plain coding harness with `FINAL` and `inspect`;
- `:rlm` means the recursive harness doctrine, with the recursion fns injected subject to
  capability gating.

Root and child sessions running in `:rlm` use the **same** recursive doctrine prompt. A
child is not a separate prompt family.

---

## 2. The plain Clojure harness prompt (`:fractal-engine/repl-p1`, v1)

The Phase 1 prompt is still the exact doctrine for non-recursive work:

- you are an **operator** with a live Clojure REPL, not a commentator;
- default to the **cheapest sufficient processing**, which is usually direct Clojure;
- reply in plain assistant text containing fenced `clojure` blocks only;
- the host evaluates fenced blocks in order and returns one compact observation;
- if you need to see a result before deciding, bind it with `def` and inspect it on the
  next step instead of calling `FINAL` speculatively;
- observations are fit-or-stub: small values may be shown whole, larger values appear as
  bounded type/size stubs until inspected;
- `inspect` is the deliberate peek tool for large live values;
- REPL vars are the working memory and remain live across **steps and turns**;
- `FINAL` ends the current turn but does not destroy the session state;
- `FINAL` must be built from vars and observations in the current session, not from prior
  expectation.

The prompt explicitly pushes **exact-answer discipline**:

- counting, ranking, comparisons, and exact extraction should be computed in Clojure;
- keep an auditable ledger var;
- verify the final answer against that ledger before returning.

The worked examples embedded in `repl-p1-text` teach the same pattern: represent, inspect,
compute, verify, `FINAL`.

---

## 3. The recursive harness prompt (`:fractal-engine/repl-rlm`, v1)

When `:harness :rlm` is selected, the base doctrine becomes `:fractal-engine/repl-rlm`.
It keeps the same REPL mechanics as the plain harness, but adds the long-horizon recursive
operator doctrine.

### Core processing hierarchy

The recursive prompt teaches one ordering principle:

1. **deterministic Clojure** first for exact work;
2. a **leaf** for one bounded probabilistic judgment;
3. a **child** for a sub-problem that needs its own inspect/judge loop;
4. **attach/reuse** when prior cognitive state is the right starting point.

The model is told to escalate only when the work in front of it proves the cheaper kind
is insufficient.

### Host-fn surface named in the prompt

The prompt names the full recursive surface:

- `(lm input query [mode])`
- `(map-lm inputs query [mode])`
- `(rlm task)`
- `(map-rlm tasks [shared-instruction])`
- `(attach-rlm handle task [opts])`
- `(FINAL value)`

And it teaches the intended semantics:

- `lm` / `map-lm` are **leaf** operations: bounded input, one model judgment, no REPL;
- `rlm` / `map-rlm` create **fresh child sessions** that run the whole loop;
- `attach-rlm` restores a prior session/head state into a **fresh derived child** and runs
  one new task there;
- `FINAL` is the only thing that returns a value to the caller.

SDK surfaces add prompt material from the same descriptor that drives SCI
injection. Root and child request assembly uses this order:

1. harness base doctrine;
2. generated stable SDK surface card for capability-exposed functions only;
3. config `:system-overlay`;
4. session `:system-overlay`;
5. dynamic per-request SDK surface context as a transient user message before
   the latest task/observation.

Dynamic request context is not appended to durable transcript state. When it is
present, request cache metadata limits system-and-tail providers to one
breakpoint so the stable system prompt can cache while dynamic tail text does
not become a cache anchor. Leaf calls get `:surface/prompts :leaf` inside the
leaf system prompt.

### Long-horizon state and branching

This prompt is explicitly about state that lives over time:

- the current session's vars persist across turns, so the model can build up durable
  working state rather than restating context every step;
- a child gets its **own** session state and may recurse again;
- attach starts from a **prior immutable head** and produces a new branch rather than
  mutating the source session;
- the model is reminded that recursion spends real calls and should only be used where
  the branching state is justified.

### Envelopes, fan-out, and failure handling

The recursive prompt teaches the current envelope/readback contract:

```clojure
{:rlm/result true
 :rlm/value  <child FINAL>
 :rlm/session <session handle>
 :rlm/head    <immutable head handle>
 :rlm/meta    <recognition data>}
```

It also teaches:

- read a child result at `(:rlm/value env)`, not from a prose summary;
- `:rlm/meta` is deterministic recognition data, not authoritative semantic truth;
- `map-lm` and `map-rlm` are capped at **50** inputs per call;
- oversized work should be chunked with `partition-all`;
- partial fan-out failure returns index-aligned `{:fractal/failed true ...}` sentinels
  instead of throwing away the whole batch.

### Trust discipline

The recursive doctrine is strict about truthfulness:

- a leaf output or child `FINAL` is a **claim**, not a fact;
- load-bearing delegated claims must be re-grounded before reuse;
- exact totals and counts must still be computed in Clojure from returned values;
- a child's summary is not proof that the child really observed what it claims.

That is the current prompt-level guard against long-horizon drift as work branches,
rejoins, resumes, and attaches over time.

---

## 4. The leaf prompt (`:fractal-engine/leaf`, v1)

`prompt/leaf-prompt` is a separate prompt for leaf calls. It defines a leaf as:

- one bounded input;
- one query;
- one output;
- no REPL;
- no tools;
- no memory;
- no world-discovery.

The leaf prompt also requires:

- return only the requested shape;
- echo identity fields such as `:id`, `:index`, `:path`, `:handle`, or `:lane` when they
  are present so the caller can merge results;
- do not invent counts or unsupported facts;
- in `:edn` mode, return exactly one schema-shaped EDN value with no prose, Markdown, or
  acknowledgement text.

This keeps leaves as the engine's bounded semantic primitive rather than mini-agents.

---

## 5. Child invocation frame and attach guidance

`prompt/child-invocation-frame` is **not** a different system prompt. It is a user-message
frame prepended to the child's assigned task.

The frame tells the child:

- you were invoked to settle **one sub-problem**;
- do not solve the caller's larger mission unless asked;
- you inherit none of the caller's vars, helpers, or working directory;
- default to direct settlement: Clojure first, then one leaf, then deeper recursion only
  when the task itself truly splits;
- if an exact EDN shape is requested, `FINAL` exactly that shape.

Attach uses this same invocation frame, with one important host-side nuance:

- before the task turn runs, the engine restores the selected source head's vars snapshot
  into the fresh attached child;
- the source session/head is not advanced;
- the attached child returns the same envelope shape as `rlm`.

So attach is "start a new branch from prior settled cognitive state", not "resume in
place" and not "let the caller reach into the source session mutably".

---

## 6. Compaction prompt (`:fractal-engine/compaction`, v1)

The compaction prompt is sent to the **root model** when transcript compaction runs. Its
job is to rewrite the visible transcript into one continuation briefing.

The compaction contract is:

- preserve the caller's task and requested return shape;
- preserve load-bearing facts, decisions, counts, intermediate results, and open
  questions;
- preserve the **names and meanings** of important live REPL vars;
- drop chatter and superseded attempts;
- return plain prose with **no code fences**.

This is crucial to the long-horizon story:

- compaction rewrites the **transcript** the model sees;
- it does **not** destroy the REPL vars;
- the session's durable cognitive state remains in vars and heads, not in hidden context.

---

## 7. Overlays and no hidden context

`adapter.request/system-message` assembles the final system text in this order:

1. the harness-selected base doctrine prompt;
2. `cfg :system-overlay`;
3. `session :session/system-overlay`.

Those overlays specialize behaviour. They do not add new model-facing functions.

The current prompt contract therefore has no magic context channel:

- the model sees the system prompt text plus the kept transcript;
- durable working state lives in REPL vars and published heads;
- children and attached children get explicit task frames;
- compaction rewrites visible history instead of inventing hidden memory.

That is the present behavioural contract for persistent, branching, supervised recursive
work in fractal-engine.
