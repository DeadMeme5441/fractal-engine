# 04 · Capability Sandbox

The model writes arbitrary Clojure and the host evaluates it inside **SCI**, not JVM
`eval`. Capability is therefore a **language-layer contract**: deny by default, then
grant only what a per-session **capability profile** explicitly allows.
`fractal.engine.capability`.

Two implementation facts matter for the rest of the spec:

- the **kernel** owns the per-session SCI namespace and re-binds `sci/ns` on every eval;
- **capability** owns the injected host vars (`FINAL`, `inspect`, gated IO, recursion fns)
  and the deny set.

That split is deliberate: capability never depends on the kernel, and the gated vars
survive a model `(in-ns …)` because they live in injected `clojure.core` /
`clojure.java.io` / `clojure.java.shell` namespaces, not in a one-off scratch ns.

> The core sandbox thesis is pinned to **SCI 0.8.43**. CI runs a dedicated regression
> file (`test/fractal/engine/sci_sandbox_test.clj`) that guards the verified facts after
> every SCI bump.

---

## 1. A capability profile is data

```clojure
{:capability/name  :default
 :cap/fs-read      {:paths ["/work"]}     ; :deny | :allow | {:paths [canonical-prefixes]}
 :cap/fs-write     :deny                  ; :deny | :allow | {:paths [...]}
 :cap/shell        {:commands #{"grep" "cat" ...}} ; :deny | :allow | {:commands #{...}}
 :cap/network      :deny                  ; :deny | :allow
 :ns/granted       #{clojure.core clojure.string clojure.edn ...}
 :cap/java-classes {}                     ; explicit finite whitelist only
 :engine-fns       #{:FINAL :inspect :lm :map-lm :rlm :map-rlm :attach-rlm}}
```

The gates are independent dimensions. There is no single scalar "level";
`:locked-down`, `:default`, and `:trusted` are just named profile values.

---

## 2. Mapping a profile onto SCI (`sci-opts`)

`(capability/sci-opts profile engine-fn-impls)` returns the map passed to `sci/init`.
`engine-fn-impls` is supplied by the wiring layer as plain data.

```clojure
{:namespaces
 {'clojure.core
  (merge (engine-fn-vars profile engine-fn-impls) ; FINAL/inspect[/lm/...], filtered by :engine-fns
         (gated-io-vars profile))                 ; slurp/spit/file-seq/sh

  'clojure.java.io
  (gated-io-ns profile)                           ; file/reader/input-stream/as-url/copy

  'clojure.java.shell
  {'sh (gated-sh profile)}

  ;; plus the copy-ns catalog entries that are granted by :ns/granted
  ...}

 :classes (:cap/java-classes profile)
 :deny    deny-set}
```

The **session namespace is not built here**. `kernel/new-ctx` creates the session ns
after `sci/init`, and every eval re-binds `sci/ns` to that ns. Capability only defines
what symbols are reachable once execution is in that session.

### Construction rules pinned by the code and tests

- **`slurp`/`spit`/`file-seq`/`sh` are always injected host vars.**
  SCI has no built-in `slurp` to "fall back to", so even `:allow` mode still goes
  through the engine's gated host fns.
- **The read gate classifies the resolved target, not the raw argument string.**
  `File`, `URL`, `file:` URLs, uppercase schemes, and plain strings are all normalized
  the same way `clojure.java.io` would normalize them. This closes confused-deputy
  holes such as a `file:` URL bypassing a string-only check.
- **The path gate is canonical path-boundary logic, never string-prefix logic.**
  A request is allowed only when the canonical requested path equals an allowed prefix or
  sits under it as a real descendant. `/work` must not admit `/work-secret`.
- **`clojure.java.io/file` is intentionally ungated.**
  It is only a constructor. Reads and writes are gated when code actually uses
  `slurp`, `reader`, `input-stream`, `copy`, `spit`, or shell.
- **Network is a separate gate from file access.**
  `http`, `https`, `ftp`, `jar`, and hosted `file://…` URLs are treated as network.
  Local `file:/abs/path` and `file:///abs/path` URLs are treated as file reads/writes.
- **Shell is a command-name allowlist, not a shell parser sandbox.**
  `clojure.java.shell/sh` uses `ProcessBuilder` without a shell, so `|`, `>`, `;`, and
  `$()` are inert. The meaningful boundary is therefore the allowed executable set.
  `:default` allows only genuinely non-exec / non-net / non-write tools:
  `#{grep cat head tail wc sort uniq cut tr comm ls stat file diff jq md5sum sha256sum date echo}`.
- **Namespace reachability is split between SCI defaults and copy-ns extras.**
  `clojure.string`, `clojure.edn`, `clojure.set`, and `clojure.walk` are SCI defaults.
  `clojure.pprint`, `clojure.data`, `clojure.zip`, and `clojure.core.protocols` are
  compiled into the catalog with `sci/copy-ns` and emitted only when granted.
- **Java interop is an explicit finite whitelist.**
  `validate-profile!` rejects non-map class whitelists, rejects non-symbol keys, rejects
  dangerous classes unless `:capability/unsafe true` is present, and rejects the unsafe
  marker entirely on `:default` and `:locked-down`.

---

## 3. Named profiles

```clojure
:locked-down
;; no fs read/write, no shell, no network, no interop, no recursion/model egress
;; :engine-fns #{:FINAL :inspect}

:default
;; reads only the current workdir, denies writes and network, allows the safe shell set,
;; grants the default namespace catalog, :engine-fns includes FINAL/inspect/lm/map-lm/rlm/map-rlm/attach-rlm,
;; :surface/fns #{}

:trusted
;; fs-read :allow, fs-write {:paths [workdir]}, shell :allow, network :allow,
;; same namespace catalog as :default, same :engine-fns as :default, :surface/fns #{}
```

Important composition truth:

- `:trusted` is broader in **IO/network**, not in namespace catalog or Java interop.
- `:locked-down` removes `lm`/`map-lm`/`rlm`/`map-rlm`/`attach-rlm` because those are
  unfilterable provider egress. A maximum-sandbox session cannot also delegate.

Harness mode and capability compose cleanly:

- `:harness :clojure` assembles only `FINAL` and `inspect` before capability filtering.
- `:harness :rlm` assembles the recursion fns too, then `:engine-fns` filters them.
- Therefore a `:locked-down` session running in `:harness :rlm` still exposes only
  `FINAL` and `inspect`.
- SDK surface functions are gated separately by `:surface/fns`, a finite set of
  qualified symbols. Default deny means configured surfaces do not appear in SCI
  until the capability profile allows individual functions.
- `clamp` intersects `:surface/fns`, and override validation rejects any child
  or per-session override that tries to gain a surface function the parent did
  not have.

---

## 4. Clamp and inheritance

`clamp(a, b)` is the per-gate **meet**: the more restrictive of the two profiles.

- `:deny` annihilates.
- `:allow` is identity.
- two `{:paths ...}` gates intersect by mutual containment of canonical prefixes.
- two `{:commands ...}` gates intersect by set intersection.
- `:ns/granted`, `:engine-fns`, and Java-class keys intersect by set/key intersection.
- network is binary: if either side is `:deny`, the result is `:deny`.

### Where the engine applies that meet today

- **Root session override**: `start-session!` uses `resolve-override`.
  A root/session override that loosens any gate beyond the configured base is rejected.
- **Fresh child spawn (`rlm` / `map-rlm`)**: `spawn-child!` uses the parent profile as the
  default child profile, or `clamp(parent, override)` when an override is supplied.
  A loosening override is therefore **silently tightened** to the parent boundary rather
  than rejected; a stricter override narrows the child further.
- **Attach (`attach-rlm`)**:
  1. resolve the source session/head;
  2. reject the attach if the source profile is more privileged than the caller
     (`:fractal/attach-capability-rejected`);
  3. compute `base = clamp(caller, source)`;
  4. apply any attach override via `resolve-override`, so loosening beyond `base` is
     rejected here.

Attach is therefore stricter than plain child spawn: it must satisfy both the caller's
boundary and the source session's boundary.

---

## 5. Configuration and audit

- `make-config` validates the configured default profile and stores the validated profile
  value under `:capability`, plus its name under `:capability/name`.
- `start-session!`, `resume-session!`, `spawn-child!`, and `spawn-attached!` keep the
  full resolved profile on the **live handle** as `:capability`.
- The durable session entity records only the **resolved profile name** at
  `:session/capability`.

So the durable audit trail tells you which named posture a session ran under, while the
live handle carries the fully resolved gate map the runtime is enforcing.

---

## 6. The deny set

```clojure
#{eval clojure.core/eval resolve ns-resolve requiring-resolve
  find-ns find-var intern load-string load-file load}
```

Additional pinned truths:

- `*read-eval*` stays false, so `#=` reader-eval is already blocked.
- there is no `read-string-with-eval` symbol to deny.
- `binding` is intentionally **not** denied; rebinding SCI dynamic vars such as
  `*print-length*` is legitimate and tested.

---

## 7. The pinned regression tests

`test/fractal/engine/sci_sandbox_test.clj` and `test/fractal/engine/capability_test.clj`
pin the current behaviour:

- instance interop on a host-leaked `File` is denied when `:classes {}`;
- static interop (`System`, `Runtime`) is unresolvable;
- `#=` reader-eval is blocked;
- the deny set blocks `eval`, `load-string`, and `requiring-resolve` escapes;
- the gated `slurp` shadow survives `(in-ns …)`;
- a multi-form `sci/eval-string*` block reads/evals one form at a time and returns the
  last form's value;
- `binding` works;
- `:default` can read a local file in the work area, but rejects URL reads, out-of-tree
  file reads, `git`, and interpreters such as `python3`;
- `clojure.pprint` and `clojure.data` are reachable when granted; `clojure.zip` is not
  reachable from `:locked-down`.

These are load-bearing, version-sensitive facts. CI should continue to block SCI upgrades
unless those tests pass and the spec is intentionally updated with the new truth.
