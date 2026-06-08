# 04 · Capability Sandbox

The model writes arbitrary Clojure that the host evaluates. Because the kernel is SCI
(03), capability is **denied by default** — no Java interop, no file IO, no shell, no
network — and granted **explicitly** by a per-session **capability profile**. This
replaces v1's "full JVM + an OS seatbelt as the only line" with first-class,
language-layer capability control. `fractal.engine.capability`.

> The core sandbox thesis was **empirically verified against SCI 0.8.43**: with
> `:classes {}`, static *and* instance interop are denied; there is no built-in
> `slurp`/`sh`; var shadows hold; `#=` reader-eval is blocked. These facts are
> **version-dependent** — a pinned regression test (§7) guards them; re-run it on every
> SCI bump.

---

## 1. A capability profile is data

```clojure
{:capability/name   :default            ; for audit/recording
 :cap/fs-read       {:paths ["/work"]}  ; :deny | :allow | {:paths [canonical-prefixes]}
 :cap/fs-write      :deny               ; :deny | :allow | {:paths [...]}
 :cap/shell         {:commands #{"grep" "cat" …}} ; :deny | :allow | {:commands #{names}}
 :cap/network       :deny               ; :deny | :allow  (URL schemes in reads/sh)
 :ns/granted        #{clojure.string clojure.edn …}  ; a FILTER over the engine ns catalog
 :cap/java-classes  {}                  ; explicit class whitelist (NEVER {:allow :all})
 :engine-fns        #{:FINAL :inspect}} ; which host fns are injected (lm/rlm/attach gated here)
```

Gates are independent **dimensions**; a profile is their product. There is no single
"level" — `:locked-down`/`:default`/`:trusted` are just **named profile values** (§3).

---

## 2. Mapping a profile onto SCI (`sci-opts`)

`(capability/sci-opts profile engine-fn-impls)` → the map passed to `sci/init` (03).
`engine-fn-impls` is `{:FINAL fn :inspect fn …}`, supplied by the wiring layer
(`session`, 03) — capability takes it as **data** and never depends on the kernel.

```clojure
{:namespaces { 'fractal.session.<id>
               (merge (select-keys engine-fn-impls (:engine-fns profile)) ; FINAL/inspect[/lm/rlm/attach], gated by profile
                      (gated-io-fns profile))                             ; capability's own slurp/spit/sh/file-seq
               'clojure.pprint …, 'clojure.data …, … }                   ; copy-ns'd catalog ns ∩ :ns/granted (string/edn/set/walk are SCI defaults — free)
 :classes    (build-class-map profile)                                   ; explicit whitelist; never :all
 :deny       deny-set                                                    ; §5
 :ns-aliases { … }}
```

> `:engine-fns` (the names) gates which host fns are injected — so `:locked-down`
> dropping `:lm`/`:rlm` simply omits them from the `select-keys`. The gated IO fns
> (`slurp`/`spit`/`sh`/`file-seq`) are capability's own (the gates live here, §2).

Key construction rules (each fixes a verified hole):

- **`slurp`/`spit` are ALWAYS host-injected and gated** — SCI has **no built-in
  `slurp`** (probed: `Could not resolve symbol: slurp`). There is no "allow mode passes
  through to the built-in" — that built-in does not exist. `:cap/fs-read :allow` injects
  an unrestricted-local-file `slurp` that still applies the network gate; `{:paths …}`
  adds a path check; `:deny` injects a `slurp` that throws.
- **Network-aware read gate.** The injected `slurp`/`io/reader`/`io/input-stream`/
  `io/as-url`/`io/copy` reject any arg whose URI scheme ∈ `{http https ftp jar file://host}`
  **unless `:cap/network` allows** — independent of the fs path gate. (Closes
  `slurp`-of-URL exfil; `:default` (`network :deny`) refuses `(slurp "http://…")`.)
- **`sh` is gated by an allowlist of genuinely non-exec/non-net/non-write commands.**
  `:default` allows: `#{grep cat head tail wc sort uniq cut tr comm ls stat file diff
  jq md5sum sha256sum date echo}`. **EXCLUDE** `find awk sed git python3 ruby node
  clojure clj tee cp mv rm dd xargs env` — each grants arbitrary exec/write/network and
  defeats the gate. Use Clojure `file-seq` (gated io), not `find`. Interpreters live
  only in `:trusted` (`:cap/shell :allow`).
  > Mitigating fact: `clojure.java.shell/sh` uses `ProcessBuilder` with **no shell**, so
  > `|`/`>`/`;`/`$()` in args are inert — only programs that *themselves* exec/network
  > are the vector. The command-NAME allowlist is therefore meaningful *iff* every
  > interpreter/`-exec` tool is excluded.
- **Path gate = canonicalized path-boundary**, never string-prefix: canonicalize the
  requested path and each allowed prefix, admit iff `requested == prefix` OR
  `requested startsWith (prefix + File/separator)`. (`/work` must not admit
  `/work-secret`.) Store prefixes canonical/absolute.
- **`:ns/granted` is a filter over a fixed engine catalog** of injectable namespaces.
  `require` resolves a name iff it is **in the catalog AND in `:ns/granted`**. The
  always-injected `clojure.java.io`/`clojure.java.shell` are implicitly granted (gated
  at the *var* level, not the ns level). The catalog has **two tiers**:
  `clojure.string`/`clojure.edn`/`clojure.set`/`clojure.walk` are **SCI defaults** (built
  in — resolvable without injection); `clojure.pprint`/`clojure.data`/`clojure.zip`/
  `clojure.core.protocols` are **not** SCI defaults and are made injectable by compiling
  them into `:namespaces` via SCI's **`copy-ns`** (`clojure.core` is always present).
  `sci-opts` selects `:ns/granted ∩ catalog`: copy-ns'd entries are emitted into
  `:namespaces`; default entries cost nothing. `:default` catalog grant:
  `#{clojure.core clojure.string clojure.edn clojure.set clojure.walk clojure.pprint
  clojure.data clojure.zip clojure.core.protocols}` + implicit io/shell.
- **Dangerous `:cap/java-classes` THROW, not warn.** `validate-profile!` throws if the
  whitelist contains `java.net.*`, `java.lang.ProcessBuilder`, `java.lang.Runtime`,
  `java.lang.Thread`, `java.lang.reflect.*`, `java.lang.ClassLoader`, `jdk.*`, `sun.*` —
  unless an explicit `:capability/unsafe true` co-marker is present. `:default`/
  `:locked-down` reject the unsafe marker entirely. `build-class-map` may **never** emit
  `{:allow :all}` (which would defeat the whole sandbox); assert a finite explicit map.

---

## 3. The named profiles (a lattice)

```clojure
:locked-down  ; max sandbox: fs-read :deny (or a tight :paths), fs-write :deny,
              ; shell :deny, network :deny, java-classes {}, engine-fns #{:FINAL :inspect}
:default      ; the RLM workhorse: fs-read {:paths [workdir]}, fs-write :deny,
              ; shell {:commands <the safe set>}, network :deny, the :default ns grant,
              ; engine-fns #{:FINAL :inspect :lm :map-lm :rlm :map-rlm :attach-rlm}
:trusted      ; fs-read :allow, fs-write {:paths [workdir]}, shell :allow, network :allow,
              ; broader ns grant, engine-fns (all)
```

**`:locked-down` ⇒ `:engine-fns #{:FINAL :inspect}` (no `lm`/`rlm`/`attach-rlm`).** This is a
deliberate security boundary: `lm`/`map-lm`/`rlm`/`map-rlm` are **unfilterable egress to
the provider** — the capability profile cannot constrain *what they send*. So the
maximum-sandbox profile closes that channel by removing them. Consequence (accept it):
"max sandbox" and "can recurse/delegate" are mutually exclusive. (A middle "lm/rlm via
an approval path" profile may be added later; not now.)

> The RLM thesis **requires easy file reads** (the model reads its own input). So
> `:default` must keep `fs-read` open to the work area even while gating writes/network/
> shell. Do not lock reads down by default.

### Clamp + inheritance (composition)

- **`clamp(a, b)` = the meet** over every gate (the more-restrictive of each), computed
  per dimension: **`:deny` annihilates** (meet with anything ⇒ `:deny`); **`:allow` is
  identity** (meet with anything ⇒ the other operand); two **`{:paths …}`** meet by
  **prefix-intersection** (keep each canonical prefix admitted by *both* boundaries — a
  prefix of one that lies within the other); two **`{:commands …}`** (and the `:ns/granted`
  grant-sets) meet by **set-intersection**; two **`:cap/java-classes`** maps meet by
  **key-intersection** (keep only classes whitelisted in both). Ordering:
  `:deny < {subset of paths/commands} < :allow`; grant-sets ordered by ⊆ (smaller =
  more restrictive); java-classes ⊆.
- **Universal inherit-and-clamp for ALL child spawns** (Phase 3/4: `rlm`/`map-rlm`/fork/
  branch/`attach-rlm`): `child = clamp(parent-resolved, child-override)`. Engine-default
  applies **only to root sessions**. (Closes the escalation: a `:locked-down` parent
  cannot `(rlm "read /etc/secret")` into a `:default` child.)
- **`attach-rlm` rejects** when the caller profile `<` the target-session profile on any
  gate (a low-priv caller may not drive a high-priv session).
- **Override = REPLACE per gate, never union grant-sets.** `validate-profile!` rejects
  any override that is not ≤ base on **every** gate (the per-gate restrictiveness
  lattice). The same predicate gates child clamping.

---

## 4. Configuration + audit

- Engine config carries a **default profile**; a session may pass a **per-session
  override** (clamped/validated against the default). See `make-config` (07).
- The resolved profile **name** is recorded on the session (`:session/capability`, 02)
  and the full profile value is available for audit — a run's capability posture is
  inspectable. (⚠ denied paths/args may flow into the audit log via error ex-data; note
  for log handling.)

## 5. The `:deny` set

```clojure
#{eval clojure.core/eval resolve ns-resolve requiring-resolve find-ns find-var intern
  load-string load-file load}
```
Plus keep `*read-eval*` **false** (SCI default; blocks `#=`). (There is **no**
`read-string-with-eval` symbol to deny — reader-eval is already shut off by `*read-eval*`
false, so do not add it back to the set.) ⛔ Do **not** put
`binding` in `:deny` — SCI's `binding` only rebinds SCI dynvars (it cannot escape the
sandbox), and the model legitimately rebinds `*print-length*`/`*out*`. (Probed:
denying `binding` breaks `(binding [*print-length* 3] …)` for zero security gain.)

## 6. The OS sandbox is a BACKSTOP

Now that the language layer gates capability, an OS-level sandbox (seatbelt on macOS,
Landlock/seccomp on Linux) is a **defense-in-depth backstop**, not the primary
boundary, and is **out of Phase-1 scope** (note it for later). Residual truths to
record (not solve now): the OS sandbox does **not** filter network — true network
isolation needs a netns/container/proxy at deploy; and `lm`/`rlm` remain unfilterable
provider egress regardless of OS sandboxing (a deployment trust assumption).

## 7. The pinned regression test (REQUIRED)

A test (`10`) that asserts, against the pinned SCI version, with `:classes {}`:
- instance interop on a host-leaked `File` throws (`Method … on class java.io.File not allowed`);
- static interop (`System`/`Runtime`) is unresolvable;
- `#=` reader-eval throws; `read-string` of `#=(…)` does not execute;
- `requiring-resolve` cannot reach an un-injected namespace;
- the **gated `slurp` shadow survives an `in-ns`** (a model `(in-ns …)` must not revert
  `slurp` to a built-in — there is none, but the test pins the behavior);
- the **`sci/eval-string*` REPL-interleaving guarantee** (03 §2): a multi-form block
  reads-then-evals one form at a time, so a `(require …)`/`(in-ns …)`/`(def …)` in form 1
  is visible to form 2, and the block's reported value is the **last form's value**
  (`last-form-value`);
- a model **`(in-ns …)` does not strand later host evals** (03 §2, the per-step in-ns
  re-assertion): a block that switches ns still leaves the *next* step's host evals
  landing back in the session ns (eval-batch re-asserts `(in-ns '<session-ns>)` as its
  first action each step);
- `binding` of a dynvar **works** (it is not denied).

These facts are load-bearing and version-dependent. **CI must run this test and block
release on failure after any `org.babashka/sci` bump.**
