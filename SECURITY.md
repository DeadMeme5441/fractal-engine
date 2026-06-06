# Security Policy

fractal-engine is early-stage software that evaluates model-emitted Clojure in a
trusted local JVM. Treat it as a trusted-local engine, not a sandbox boundary.

## Supported Versions

Security fixes target `main` and the latest tagged release. Older tags may receive
fixes when the patch is small and low risk.

## Reporting a Vulnerability

Use GitHub's private vulnerability reporting or draft security advisory flow for this
repository when available:

https://github.com/DeadMeme5441/fractal-engine/security/advisories/new

If private reporting is unavailable, open a public issue with a minimal description
that does not include exploit details, secrets, private hostnames, customer data, or
proprietary code. A maintainer can move the discussion to a private channel.

For ordinary hardening suggestions that do not expose a vulnerability, use a normal
GitHub issue.

## Scope

Useful reports include:

- unsafe handling of secrets or local files
- vulnerabilities in release artifacts or CI publishing
- surprising behavior in the best-effort OS sandbox wrapper
- bugs that can corrupt canonical session storage

Out of scope:

- prompt injection against arbitrary live models without a concrete engine bug
- issues that require committing secrets or proprietary content to reproduce
- denial-of-wallet scenarios from intentionally unbounded live-provider runs
