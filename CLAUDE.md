# CLAUDE.md — working in fractal-engine

`fractal-engine` is a small recursive compute engine: a model drives a persistent
Clojure REPL, the host evaluates fenced Clojure, returns compact observations, and
loops until `(FINAL value)`. Read `README.md` for the user view and `AGENTS.md`
for the public-safe boundary and design discipline — **`AGENTS.md` rules apply;
this file complements it.**

## Orient fast

Everything is `input -> processing -> output`; only the *kind* of processing
varies:
- ordinary Clojure = deterministic (exact, cheap)
- `lm` / `map-lm` = probabilistic (a leaf is a pure function whose body is a model)
- `rlm` / `map-rlm` = recursive (a child runs this whole loop on a sub-problem)

Model-facing surface is exactly: **`FINAL lm map-lm rlm map-rlm attach-rlm`** plus
ordinary Clojure. Root and child run the *same* behavior; a leaf is the
non-recursive base case. Key namespaces: `process.clj` (loop, fanout, child/attach,
leaf prompt), `runtime.clj` (eval kernel, snapshots), `prompt.clj` (behavior),
`artifacts.clj` (trace/blobs/fingerprints), `provider.clj` (SDK adapter).

## Invariants you must not break

- **No `context` var.** The surface is the six functions above and nothing else.
  Do not add convenience functions or revive retired names — minimalism is the
  point. New patterns belong in the model's own session via `defn`, not the API.
- **The prompt system prompt must not contain the substrings** `context`,
  `product`, `storage`, or `workflow` (enforced by the `prompt-contract` test).
- **The `prompt-contract` test pins many exact phrases** and the `prompt-version`.
  If you change `prompt.clj` or `leaf-system-prompt`, update the test in lockstep
  and bump `prompt-version`.
- **Only the compute engine belongs in core namespaces.** No storage, memory,
  workflow, product, or domain concepts in the kernel (see AGENTS.md anti-goals).
- **Public repo.** No company/customer/ticket names, secrets, or proprietary
  content in tracked files, ever.

## Develop & validate

- Run tests before claiming anything is done: `clojure -M:test`. State results
  plainly; if something failed, show the output.
- Offline development uses the fake/scripted provider (`--fake-script ...`); no
  keys needed. Inspect runs with `clojure -M -m fractal-engine inspect --dir
  runs/<id>`.
- Live providers go through `clojure-llm-sdk`. Two gotchas that will waste your
  time if you miss them:
  - The Codex OAuth provider keyword is **`:codex-backend`** (plain `:codex` is the
    API-key path). It reads `~/.codex/auth.json`.
  - Vertex Gemini is **`:vertex-gemini`** and needs `GOOGLE_CLOUD_PROJECT` /
    `GOOGLE_CLOUD_LOCATION` **exported into the JVM env** — the `.env` loader does
    NOT push them to `System/getenv`. ADC supplies the token.
- **Root-model strength is decisive.** Strong roots follow the behavior; weak
  (mini) roots confabulate past their own correct observations. Don't put a mini
  model at the root when validating behavior.
- **Live runs cost money and can hang** (a provider call hung ~25 min once).
  Always leash them: timeout + background + monitor. There is no engine-level
  budget/timeout governor yet, and the engine is **not sandboxed** (it runs
  trusted local Clojure).

## How the maintainer collaborates (adapt to this)

- **Direct, no hedging.** Push back on weak premises; disagree when he's wrong;
  name procrastination/yak-shaving. (Full voice/conduct is in the global
  `~/.claude/CLAUDE.md`.)
- **Concede fast when corrected** — drop the prior framing and adapt; don't defend
  or cling. He's often right and tests your claims.
- **Read the mode.** He toggles between "strategize with me / don't touch code
  yet" and "go execute, don't hack this together." Honor the signal literally:
  think when he wants thinking, execute thoroughly when he says go.
- **Verify, don't assert.** Back claims with real runs, real numbers, real diffs.
  No spin.
- **High craft bar.** When he asks for comprehensive, go full-send (worked
  examples, not hand-waving). He will catch "good enough."

## Task tracking (Beads)

This repo uses **Beads** (`bd`) for task tracking — not TodoWrite or markdown
files. Create an issue before non-trivial work, claim it when you start, close it
when done.

```bash
bd prime              # recover context (after compaction / new session)
bd ready              # issues ready to work (no blockers)
bd show <id>          # details + dependencies
bd update <id> --claim
bd close <id>
bd create --title="..." --description="..." --type=task|feature|bug --priority=2
```

Use `bd remember "insight"` for durable knowledge across sessions (`bd memories
<keyword>` to search). Avoid `bd edit` — it opens an editor and blocks.

## Working agreements

- **No git operations unless asked** (no commit/push/branch). Keep changes narrow
  and intentional; if a file is already dirty, understand whether the change is
  yours before editing.
- Never commit `.env` or `runs/` (both gitignored). Don't add tracked caches.
