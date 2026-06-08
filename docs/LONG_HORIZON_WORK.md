# Long-Horizon Work Semantics

fractal-engine is built for work that unfolds across turns, branches, restarts, and
operator steering. The important unit is not a single prompt. It is durable working
state that can be refined, branched, resumed, attached, checked, and eventually
returned as a value.

This document describes how to design that kind of run without turning the engine
into a scoring harness, a domain-specific analyzer, or a general workflow app.

## Mental Model

A session is a persistent working state. A turn appends new work to that state until
`FINAL` returns a value for the turn. After `FINAL`, the session remains alive for
future turns, and its current head becomes the authoritative continuation point.

Operators steer the root session over time. They can narrow scope, provide new
evidence, reject a branch, ask for a different decomposition, or attach from a
useful prior head. The engine should preserve enough state for those steering moves
to be meaningful without pretending that every branch is equally current.

Children explore branches. Leaves answer bounded semantic questions. Clojure keeps
the ledger, performs exact work, validates outputs, and builds final values.

## Designing A Long-Horizon Run

Start by writing down the durable shape of the work:

- the goal for the current turn
- the state that should persist into later turns
- the facts, assumptions, and unknowns that must stay distinct
- the work units that can be processed independently
- the checks that must pass before `FINAL`
- the handles or heads worth preserving for attach

Then choose the execution shape:

| Need | Prefer |
| --- | --- |
| Exact computation or validation | Clojure in the current session |
| One bounded semantic judgment | `lm` |
| Many independent bounded judgments | `map-lm` |
| One uncertain lane that needs its own loop | `rlm` |
| Many independent uncertain lanes | `map-rlm` |
| Continue from useful prior cognitive state without mutating it | `attach-rlm` |
| Continue the same durable session from its current point | resume |

The root should keep enough structured state to explain what happened: inputs,
normalization choices, branch assignments, returned values, failed lanes,
assumptions, and final checks. Long-horizon work fails when this state collapses
into prose memory.

## Turn Discipline

Treat each turn as a settlement boundary:

1. Load or build the current representation.
2. Decide what can be done exactly and do that in Clojure.
3. Delegate only the parts that need model judgment or independent branch state.
4. Store returned envelopes, heads, failures, and evidence in vars.
5. Re-ground load-bearing claims before composing them.
6. Run consistency checks against the ledger.
7. Call `FINAL` with the compact value for this turn.

Do not rush to `FINAL` from expectation. Build the final value from observed vars
and returned data. If a branch is incomplete, encode that incompleteness explicitly
instead of smoothing it away.

## Branch And Head Semantics

The current head is authoritative for resume. Resuming a durable session means
continuing the same session from its current published state, not replaying old
provider calls, old evals, or a loose transcript.

Branching preserves alternatives:

- `rlm` creates a fresh child from the caller's assignment.
- `map-rlm` creates one fresh child per independent lane.
- `attach-rlm` creates a fresh derived child from a selected prior session/head.
- Older heads remain useful as attach points even after the source session's current
  head moves on.

This makes long-horizon work operator-friendly. A human can keep the root on the
main line, inspect branch outcomes, discard weak branches, and later derive new work
from a head that proved useful.

## State To Keep

Prefer structured values over narrative summaries:

- `:inputs` or stable ids for the material under consideration
- `:ledger` with expected counts, partitions, and status per lane
- `:results` with normalized child or leaf outputs
- `:failures` with fan-out sentinels and missingness
- `:evidence` with quoted or referenced support where applicable
- `:checks` with deterministic validation outcomes
- `:assumptions` separated from verified facts
- `:handles` or returned envelopes for attach-worthy branches

The root does not need to keep every raw byte in the final value. It does need to
keep enough durable working state to resume reasoning honestly on a later turn.

## Handling Failure Over Time

A long-horizon run should degrade visibly:

- A failed map lane returns a sentinel and the rest of the fan-out remains usable.
- A weak child result stays marked weak until re-grounded.
- A missing input is represented as missing, not silently skipped.
- A budgeted branch can return partial state if that is the most honest result.
- A later turn can attach from useful prior state and continue with narrower scope.

The goal is not to make every branch succeed. The goal is to preserve enough truth
about success, failure, uncertainty, and lineage that the next operator move is well
informed.

## Live Dogfooding

Live dogfooding should prove the engine's semantics under realistic model behavior,
not just that a happy-path call returns text.

A useful live run demonstrates:

- the root can maintain working state across turns
- deterministic Clojure is used for exact aggregation and validation
- leaves are bounded to one judgment
- children have isolated session state and return envelopes
- `map-lm` and `map-rlm` preserve order and expose partial failures as sentinels
- child capability is inherited and clamped rather than expanded
- resume uses the current head as the continuation authority
- attach derives a fresh child from the selected source head
- source sessions do not advance under attach
- branch outputs can be re-grounded before final aggregation

Good dogfooding should be runnable through the agent control plane when possible:
use CLI config profiles, explicit session ids, `run` / `turn` for writes, and
inspection commands such as `report`, `events`, `heads`, `edges`, `tree`,
`turns`, and `payload` for readback. The useful artifact is not a polished
transcript; it is a clear, public-safe account of what was run, what state
persisted, which branches were created, which heads were attached, which lanes
failed, which checks passed, and what remains uncertain.

## Anti-Patterns

- Treating long-horizon work as a single giant prompt.
- Letting prose summaries replace structured state.
- Delegating exact work that Clojure can compute.
- Creating children without explicit boundaries, return shapes, or missingness rules.
- Rejoining branches by trusting summaries instead of returned values and evidence.
- Hiding partial failure in a polished final answer.
- Replaying old work to recover state when a head-based resume or attach is available.
- Treating every branch as the current branch.
- Making storage layout, local paths, or operator environment part of the semantic
  model.

## Completion Standard

A long-horizon run is healthy when an operator can answer these questions from the
session state:

- What is the current line of work?
- Which branches were explored, and why?
- Which values are verified, assumed, missing, or failed?
- Which head would be used by resume?
- Which prior heads are worth attaching from?
- What deterministic checks support the final value?

If those answers are available without replaying old work or trusting prose memory,
the engine is doing its job.
