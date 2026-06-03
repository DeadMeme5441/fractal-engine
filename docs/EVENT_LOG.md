# Event Log Guide

The event log is the engine's audit surface: it answers "what happened, in what
order, and what caused this fact?" It is not the restore mechanism. Restore,
resume, fork, and attach read immutable head state roots.

```text
event log  = audit and causality
head state = exact runtime restore
stream     = raw compact event rows for scripts
events     = agent/operator UI over the same rows
```

## Quick use

Run a session:

```bash
fractal run "Define x and return it." --fake-script simple --name demo
```

Open the audit view:

```bash
fractal events demo
```

Typical output:

```text
audit - demo
  34 facts - 1 turn - 2 model calls - 0 leaves - 0 children - 1 checkpoint
  current checkpoint head-abc12345 via event #31
  event log explains what happened; checkpoints restore state

timeline
  turn started
  root model called
  root answered
  model response
  FINAL returned
  snapshot saved
  checkpoint sealed
  session advanced

next:
  fractal show demo                  # inspect current checkpoint
  fractal events demo --event 31     # ask why that fact happened
  fractal stream demo                # raw JSONL facts for scripts
```

Use `--limit N` when a run is large:

```bash
fractal events demo --limit 40
```

## Causal chain

Every displayed row has an event number. Ask why one fact happened:

```bash
fractal events demo --event 31
```

For a checkpoint movement, the chain should show the relevant basis head, turn,
model/eval work, snapshot, sealed head, and final ref update. This is the operator
path for understanding a session without dumping message bodies or provider
requests.

## Raw stream

Use `stream` when another program wants compact facts:

```bash
fractal stream demo | jq 'select(.["event/type"] == "head/created")'
```

`stream` emits JSONL. It is intentionally close to the canonical event rows:
event id, type, session, timestamp, row kind/id, status, source ids, and payload
refs. It does not resolve message text, eval code, provider requests, snapshots,
or final values.

## What belongs where

The event log stores compact audit facts:

- event id and append order;
- event type and timestamp;
- typed row identity;
- status;
- row/payload refs;
- source session/head ids when applicable.

BlobStore owns bytes:

- message content;
- eval code and observations;
- provider request/response payloads;
- final values;
- snapshots and vars;
- head-state roots.

Head state owns restore:

- current reads dereference `session/current-head -> head/state-ref`;
- resuming from an older head restores that head's state;
- abandoned branches remain visible in event/history reads;
- folding the event log never re-runs provider calls or evals.

## Agent guidance

Use the cheapest read surface that answers the question:

- `fractal show <run>` when you need the current checkpoint and final value.
- `fractal tree <run>` when you need recursive child shape.
- `fractal events <run>` when you need the audit timeline.
- `fractal events <run> --event N` when you need to explain one checkpoint,
  call, invocation, or ref movement.
- `fractal stream <run>` when a script needs raw JSONL.
- `fractal inspect <run> --json` when you need the full structured dump.

Do not use event replay as a restore plan. The log records results, not recipes.
