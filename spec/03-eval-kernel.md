# 03 · Eval Kernel (SCI)

The kernel is where the model's Clojure runs. It is **SCI** (`org.babashka/sci`, the
babashka interpreter) embedded **as a library** — not JVM `clojure.core/eval`. The
engine itself is a normal JVM program; only the *model's* code goes through SCI. This
buys capability control for free (interop denied by default — see 04) and keeps the
door open to native-image distribution later. `fractal.engine.kernel` +
`fractal.engine.observe`.

> **Recursion note (so you build the seam right):** the model-calling fns
> (`lm`/`rlm`/…, Phase 3) are **host functions** injected into the ctx, not SCI code.
> When the model calls `(rlm task)`, SCI invokes a host fn that runs the JVM engine to
> spawn a *child session with its own SCI ctx*. Recursion happens *between*
> interpreters, in the host — never inside one. Phase 1 injects only `FINAL` +
> `inspect`; the seam is identical.

---

## 1. One SCI context per session

Each session owns one SCI ctx, held in the handle's `:sci-ctx` atom (02 §5). It is the
durable REPL: vars `def`'d in one eval are visible in every later eval, across steps
**and across turns**, for the session's lifetime.

```clojure
;; built ONCE at session creation. The session id fixes the ns; the capability profile
;; gates the sandbox; engine-fn-impls are the host fns (FINAL/inspect[/lm/rlm]) — passed
;; IN as data so capability never depends on the kernel (see "Layering" below).
(defn new-ctx [session-id capability-profile engine-fn-impls]
  (let [ctx (sci/init (capability/sci-opts capability-profile engine-fn-impls))] ; 04 §2
    (sci/eval-string* ctx (str "(in-ns '" (session-ns-sym session-id) ")"))      ; make the session ns current
    ctx))
;; (session-ns-sym id) ⇒ 'fractal.session.<id>  — one isolated ns per session.
```

> **The handle's `:sci-ctx` is an atom** (GD6 / 02 §5): `new-slot` seeds it `nil`,
> stable across an idempotent re-`create-session!`; `start-session!` `reset!`s it to
> `(new-ctx …)` *after* `create-session!`. `new-ctx` returns the plain ctx **value** —
> every kernel call site **derefs** the atom (`@(:sci-ctx handle)`, §2/§6).

**Layering (avoids a kernel↔capability cycle):** the wiring layer — `session/start-session!`
— assembles the host-fn impl map and passes it down:
```clojure
;; in session/start-session!  (it knows the kernel, observe, and — in Phase 3 — the rlm host fns)
(def engine-fn-impls {:FINAL   (kernel/make-FINAL)
                      :inspect (kernel/make-inspect)})   ; + :lm/:rlm/… in Phase 3
(kernel/new-ctx sid profile engine-fn-impls)
```
Dependency direction is one-way: `session → kernel → capability`; `capability` takes the
impls as **data** and depends on nothing engine-specific. This also matches the build
order (capability before kernel, `11`).

⛔ **API facts (pinned to SCI 0.8.43 — verified; a regression test guards them, see 04):**
- `sci/eval-string*` is **2-arity** `[ctx s]`. There is **no** `{:ns …}` arg. Make the
  session ns current with a one-time `(in-ns '…)`; **re-assert `(in-ns '…)` at the
  start of each step** so a model `(in-ns …)` can't strand later host evals.
- Use `sci/eval-string*` (the `*`), never `sci/eval-string` (which makes a fresh ctx
  each call and would lose all vars).
- The ctx is a plain value wrapping atoms; reusing it across calls accumulates `def`s.

### Host-fn injection

The kernel provides the host-fn **makers**; the session assembles them into the impl
map (above) and hands it to `capability/sci-opts`, which selects per the profile's
`:engine-fns` and places the chosen fns in the session namespace's `:namespaces`:

```clojure
;; FINAL — exception-based signal (see §3)
(defn make-FINAL [] (fn [v] (throw (ex-info "FINAL" {:fractal/final v}))))

;; inspect — bounded value viewer (see §5); prints to the SCI out (NOT JVM *out*) so the
;; §4 capture sees it, returns nil. Bridge by rebinding *out* to @sci/out around the println.
(defn make-inspect [] (fn [x] (binding [*out* @sci/out] (println (observe/inspect-text x))) nil))
```

Phase 3 adds `lm`/`map-lm`/`rlm`/`map-rlm` (and Phase 4 `attach-rlm`) the *same way* —
host closures over the engine, injected into `:namespaces`. The capability profile may
withhold them (`:locked-down` drops `lm`/`rlm`, 04), so injection is profile-driven.

---

## 2. Extracting and evaluating a step's code

`run-step!` (07) hands the assistant text to the kernel:

```clojure
(defn extract-blocks [assistant-text]
  ;; ordered vector of code strings from ```clojure … ``` / ```clj … ``` fences.
  ;; No fence → empty vector; run-step! then appends ONE observation = the pinned
  ;; `observe/no-fence-nudge` (GD36) and the turn stays open:
  ;;   "No clojure block found in your reply. Emit a ```clojure …``` fenced block to run
  ;;    code, or call (FINAL v) inside one to end the turn."
  …)
```

Evaluation is a **batch** (one assistant message → N blocks → one observation), with
strict semantics:

```clojure
(defn eval-batch [handle turn-id blocks]
  ;; FIRST action (GD35): re-assert the session ns is current, so a model `(in-ns …)` in a
  ;; prior step/block can't strand these host evals (regression test, 04 §7 / 10 §3).
  (assert-session-ns! handle)
  ;; → {:eval-records [raw-rec …]          ; raw recs KEEP transient :eval/raw-value (live)
  ;;    :final {:final? bool :value v}      ; the FINAL value, when a block called FINAL
  ;;    :status :ok | :error | :final}
  (let [store (:store handle), sid (:session-id handle)]
    (loop [i 0, recs []]
      (if-let [code (nth blocks i nil)]
        (let [eid (store/peek-next-id store sid :eval)         ; the id this eval WILL get (02 §4)
              rec (binding [*current-turn-id* turn-id          ; *current-step-id* bound by run-step! (§7)
                            *current-eval-id* eid]
                    (eval-block handle code i))]                ; one fenced block → a RAW rec
          (append-eval! handle rec)                            ; kernel↔store edge: intern+strip+append :eval/added
          (cond
            (= :final (:eval/status rec)) {:eval-records (conj recs rec)
                                            :final {:final? true :value (:eval/raw-final rec)}
                                            :status :final}
            (= :error (:eval/status rec)) {:eval-records (conj recs rec) :status :error} ; STOP batch
            :else (recur (inc i) (conj recs rec))))
        {:eval-records recs :status :ok}))))

;; GD35 — idempotent re-assertion of the session ns (eval-batch's first action).
(defn- assert-session-ns! [handle]
  (sci/eval-string* @(:sci-ctx handle)
                    (str "(in-ns '" (session-ns-sym (:session-id handle)) ")")))

;; GD14/GD15 — the kernel↔store edge for ONE raw eval rec: intern the raw value, compute
;; the inline preview, STRIP the transient raw fields, then append the durable :eval/added
;; (the store stamps :eval/id == the peeked eid; 02 §4).
(defn- append-eval! [handle raw-rec]
  (let [store (:store handle), sid (:session-id handle)
        v       (:eval/raw-value raw-rec)                                  ; nil on an :error block
        durable (-> raw-rec
                    (dissoc :eval/raw-value :eval/raw-final)               ; transient — NEVER persisted
                    (assoc :eval/result-ref     (payload-io/maybe-intern store v {:payload/kind :eval-result})
                           :eval/result-preview (observe/value-display v observe/ok-fit)))]
    (store/append-event! store sid {:event/type :eval/added :eval durable})))
```

**Batch rules (frozen):**
- A block that **errors stops the batch** — remaining blocks are not evaluated. The
  model sees the error and repairs from existing state.
- A block that calls **`FINAL` stops the batch and ends the turn**; the FINAL value is
  the reply. Code after `FINAL` in the same block never runs.
- Otherwise continue to the next block. All clean → one combined observation.

### `eval-block` — REPL semantics

Evaluate the block's **top-level forms interleaved (read then eval, one at a time)** —
so a `require`/`in-ns`/macro-def in form 1 affects form 2 (true REPL semantics, not
read-all-then-eval). ⛔ **You do not hand-roll this:** `sci/eval-string*` **already**
REPL-interleaves a multi-form string and returns the **last** form's value (GD24, pinned
to SCI 0.8.43; the regression test in 04 §7 / 10 §3 guards it). So the block's value is
simply `(sci/eval-string* ctx code)` (a live SCI value). `count-forms` counts top-level
forms with SCI's **own** parser (loop `sci/parse-next` over `(sci/reader code)` to
`::sci.core/eof`) — never a custom reader, so it sees exactly what eval reads.

```clojure
(defn eval-block [handle code block-index]
  (let [ctx (deref (:sci-ctx handle))               ; GD6: :sci-ctx is an atom — deref here
        sw  (java.io.StringWriter.)
        t0  (System/nanoTime)
        out (try
              (sci/binding [sci/out sw]                 ; capture model stdout (see §4)
                {:value (sci/eval-string* ctx code)     ; REPL-interleaves; value = LAST form (GD24)
                 :forms (count-forms code)})
              (catch clojure.lang.ExceptionInfo e
                (if (contains? (ex-data e) :fractal/final)
                  {:final true :value (:fractal/final (ex-data e))}
                  {:error (err->map e)}))
              (catch Throwable e {:error (err->map e)}))]
    (build-eval-record handle code block-index out sw (elapsed-ms t0)))) ; → a RAW rec (below)
```

`err->map` (GD13) emits the **uniform namespaced error map** `{:error/type … :error/message
… :error/data …}` — the same shape `:eval/error` stores, `:turn/error`/`:session/error`
carry, and an `ex-info` throws (02/06).

**The eval record is built in two shapes (GD15).** `build-eval-record` returns a **raw
rec**: the durable 02 §1 fields (`:eval/turn-id`←`*current-turn-id*`,
`:eval/step-id`←`*current-step-id*`, `:eval/block-index`, `:eval/code-or-ref`,
`:eval/status`, `:eval/stdout`/`:eval/stderr` capped, `:eval/forms-count`,
`:eval/elapsed-ms`, `:eval/error`) **plus two transient, REC-ONLY fields that are never
persisted**: `:eval/raw-value` (the live return value) and, on a FINAL block,
`:eval/raw-final` (the FINAL value). `append-eval!` (above) is the kernel↔store edge: it
interns the raw value → `:eval/result-ref` (`payload-io`, kind `:eval-result`), computes
the inline `:eval/result-preview` (`observe/value-display`), **strips** the two raw
fields, and appends the stripped durable `:eval/added`. `eval-batch` **returns the raw
recs** (raw values intact) so `render-observation` (§5) and `commit-turn!` (07) read the
live value — never the durable projection.

---

## 3. `FINAL` is exception-based

`(FINAL v)` throws `(ex-info "FINAL" {:fractal/final v})`. `eval-block` catches
`ExceptionInfo`, checks for `:fractal/final`, and reports `{:final true :value v}`.
This is why code after `FINAL` in a block never executes, and why a bare expression
value is *only an observation* — only `FINAL` returns to the caller. `ex-info`/`throw`
cross the SCI→host boundary as a real `clojure.lang.ExceptionInfo`, so the host catch
is ordinary.

On `:final`, `run-step!` → `commit-turn!` (in `session-loop`, 07 / GD4): intern the FINAL
value (`:turn/final-ref`, content-addressed), snapshot the REPL vars (§6) and append
`:session/vars-snapshotted` carrying the `:vars-ref` (GD3 — a **distinct** event, just
before the final `:turn/put`); then append `:turn/put` (status `:final`, ended-at,
final-ref, with `:turn/usage`/`:turn/cost`/`:turn/cache` rolled over the turn's steps
`:unknown`-aware, 08 / GD12); hydrate the value; return it.

---

## 4. stdout / stderr capture

SCI has its **own** `sci/out` dynamic var, *separate* from the JVM `*out*`. Capture the
model's `println` output by binding `sci/out` to a `StringWriter` around the eval
(above). ⛔ **Host fns that themselves print** (e.g. `inspect`) must bridge: write to
`@sci/out`, not the JVM `*out*`, or their output won't be captured. Cap stdout/stderr
at **4000 chars** in the eval record (truncate with a `… [truncated N chars]` marker).

---

## 5. Observations — fit-or-stub + deliberate peek (`fractal.engine.observe`)

This is the v24 model. The model does **not** get values dumped at it. After a batch it
gets, per block: the captured stdout, the form status, and the return value rendered
as **fit-or-stub** from the *raw* value:

- **Fit:** if the value's canonical EDN fits a cap, show it **whole**. Caps: `ok-fit`
  = **400** chars for `:ok` values, `final-fit` = **1200** for a FINAL value.
- **Stub:** otherwise a single line `«type, size»` with **no contents** (kind + size
  only). Pinned labels (GD36): `vector`/`list`/`lazy-seq` → `«<kind>, N items»`; `map` →
  `«map, N entries»`; `set` → `«set, N items»`; `string` → `«string, N chars»`; any other
  type → `«<type>»` (type only, no size); `nil` → `nil` (never a stub).

```clojure
(defn value-display [v cap]
  (or (try-fit v cap)          ; build EDN into a StringBuilder, abort if it exceeds cap → nil
      (value-stub v)))         ; "«vector, 600 items»" etc.  (nil → "nil", not a stub)
```

To look inside a stub the model **acts**: `(inspect x)` or slices the live var
(`(nth v i)`, `(:k m)`, `(subs s a b)`, `(take n v)`). The value is always live in the
session's vars.

**`inspect` is orchard-backed** (call + segment shape **verified against orchard
0.41.0**). `(observe/inspect-text x)`:
```clojure
(def inspect-config {:page-size 25 :max-atom-length 120 :max-value-length 3000
                     :max-coll-size 20 :max-nested-depth 4})   ; bump max-coll-size vs v1's 6
(defn inspect-text [x]
  (-> (orchard.inspect/start inspect-config x) :rendered render-segments
      (strip-after "\n--- View mode") str/trimr))
```
`orchard.inspect/start` is 2-arity `[config value]` (all five config keys above are valid
0.41.0 keys); it returns an inspector map whose `:rendered` is a flat seq of segments:
bare strings (labels), `(:newline)`, and `(:value display-string nav-idx)` (consume
`display-string`). `render-segments` walks them (string → emit; `(:newline)` → `"\n"`;
`(:value d _)` → `d`). Strip the trailing `--- View mode (press 'v' …)` key-binding chrome
(the `strip-after "\n--- View mode"`).

**One observation per batch.** Format: per block, the stdout (capped) + status + the
fit-or-stub value; on no-`FINAL`, a trailer `"No FINAL was called; the turn is still
open."`; error blocks show the error message. The combined text is appended as one
`:message/appended` with `:message/role :observation` (02). At the adapter boundary
(05) `:observation` → `:user` + `"Observation:\n"`.

> Keep the persisted row and the observation separate (GD15): `render-observation` reads
> the **raw** value off the rec `eval-batch` returns (`:eval/raw-value`, live), while the
> stored `:eval/result-ref`/`:eval/result-preview` are the durable projection that
> `append-eval!` (§2) wrote from that same raw value. Do not derive one from the other.

---

## 6. Snapshot / restore (Merkle-aligned; restore is resume/fork ONLY)

The REPL var snapshot is a first-class Merkle leaf (02 §9): it must be **content-addressed
and canonical**.

> **All ns enumeration/clearing goes THROUGH SCI** (GD23, pinned to SCI 0.8.43), never
> JVM reflection — the vars live in the *interpreter's* ns, so `snapshot-vars`/
> `restore-vars!` thread the **`session-id`** to address `fractal.session.<id>`:
> ```clojure
> (defn- the-session-ns [ctx session-id]          ; the SCI ns object
>   (sci/find-ns ctx (session-ns-sym session-id)))
> (defn- ns-var-values [ctx session-id]           ; {"name" → host-value …}, via a SCI eval
>   (sci/eval-string* ctx (str "(into {} (for [[s v] (ns-interns '" (session-ns-sym session-id)
>                              ")] [(name s) (deref v)]))")))
> (defn- clear-ns-vars! [ctx session-id]          ; ns-unmap each interned sym, IN-ctx
>   (sci/eval-string* ctx (str "(doseq [s (keys (ns-interns '" (session-ns-sym session-id)
>                              "))] (ns-unmap '" (session-ns-sym session-id) " s))")))
> ```
> (`sci/find-ns`, `sci/intern`, `sci/eval-string*` + in-ctx `ns-interns`/`ns-unmap` are
> all real SCI 0.8.43 API — verified.)

**Snapshot** (`fractal.engine.kernel/snapshot-vars`) — at a turn boundary and at
compaction:
```clojure
(defn snapshot-vars [ctx session-id]
  ;; enumerate the session ns's vars + host values via a SCI eval (ns-var-values, above);
  ;; for each, attempt a canonical round-trip.
  (binding [*print-length* nil *print-level* nil *print-namespace-maps* false *print-meta* false]
    {:vars/version 1
     :vars (into (sorted-map)                        ; sorted ⇒ canonical (stable hash)
                 (for [[nm v] (ns-var-values ctx session-id)]
                   [nm
                    (if (restorable? v)               ; round-trip check: (= v (read-string (pr-str v)))
                      {:status :ok :value v}
                      {:status :unrestorable :reason (unrestorable-reason v)})]))}))
```
The snapshot is a deterministic value → `intern-payload!` content-addresses it into
`:vars-ref` (02). Unrestorable vars (fns, atoms, lazy-seqs, host objects) are recorded
*inside* the snapshot (as `:unrestorable` entries), so the snapshot is a faithful,
hashable record and equal REPL states dedup.

**Restore** (`restore-vars!`) — **Phase 2/4 ONLY**, on **resume/fork** (not built in
Phase 1; specced here so the snapshot shape is fixed up front):
```clojure
(defn restore-vars! [ctx session-id snapshot]
  (clear-ns-vars! ctx session-id)
  (doseq [[nm {:keys [status value]}] (:vars snapshot) :when (= :ok status)]
    (sci/intern ctx (the-session-ns ctx session-id) (symbol nm) value)))  ; ⛔ sci/intern, NOT eval-string*
```

⛔ **Two rules that are easy to get wrong:**
1. **Restore via `sci/intern`, NOT `sci/eval-string* (str "(def " name " " (pr-str value) ")")`.**
   A list/symbol value (e.g. `(1 2 3)`) passes the round-trip *data* check but, eval'd
   as a `def` body, tries to **call `1`** / resolve a symbol → corruption. `intern`
   binds the value directly, no eval.
2. **Compaction does NOT restore vars.** The live SCI ctx *keeps* every var (including
   `:unrestorable` ones, which are perfectly usable live); compaction only *snapshots*
   for durability and rewrites the transcript (07). Restore (clear+intern) happens only
   when materializing a *different* session (resume/fork). Restoring during compaction
   would silently drop all unrestorable vars.

---

## 7. Dynamic context

`fractal.engine.kernel` defines host dynamic vars `*current-turn-id*`,
`*current-step-id*`, and `*current-eval-id*`, all bound *inside the loop* (not at spawn):
`run-step!` binds `*current-step-id*` (it appends `:step/started` first and so holds the
store-assigned step id, GD1), and `eval-batch` binds `*current-turn-id*` +
`*current-eval-id*` per block. Phase 1 uses them to stamp records (`build-eval-record`
reads `*current-turn-id*`/`*current-step-id*` for `:eval/turn-id`/`:eval/step-id`; the
store stamps `:eval/id`); Phase 3's host fns (`lm`/`rlm`) read them to record which eval
invoked them. They are **host** vars (the model cannot bind them; SCI code can't reach
them) — so the engine sets them around the eval and the host fns read them on the same
thread. (`*current-eval-id*` comes from `store/peek-next-id`, valid on the single writer
thread — 02 §4.)

## 8. What the kernel exposes to the loop

```clojure
(new-ctx       [session-id capability-profile engine-fn-impls])  ; → an SCI ctx (§1)
(make-FINAL    [])                                 ; → the FINAL host fn (§1)
(make-inspect  [])                                 ; → the inspect host fn (§1)
(extract-blocks [assistant-text])                  ; → [code …]
(eval-batch    [handle turn-id blocks])            ; → {:eval-records :final :status}; APPENDS each :eval/added
(snapshot-vars [ctx session-id])                   ; → a canonical snapshot value (intern → :vars-ref, §6)
(restore-vars! [ctx session-id snapshot])          ; Phase 2/4 ONLY — resume/fork (§6)
(observe/render-observation [eval-records opts])   ; → the observation string
(observe/value-display [v cap]) / (observe/value-stub [v])       ; fit-or-stub (§5)
(observe/inspect-text [x])                          ; orchard viewer (also wired as the `inspect` host fn)
```

Append split (GD14): the **loop** owns the turn/step/message/observation appends, the
turn lifecycle, and the deadline; the **kernel** owns the per-block `:eval/added` append
— the kernel↔store edge (§2): `peek-next-id` → eval → intern raw (`:eval/result-ref`) +
preview → append the stripped entity. Everything else the kernel does is pure: turn this
assistant text into eval records + an observation + a maybe-FINAL, inside the session's
sandboxed SCI ctx.
