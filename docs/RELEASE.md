# Release and Repository Operations

This document captures the public GitHub setup for fractal-engine. The files in this
repo can define CI, release automation, templates, and docs. A few protections still
need to be enabled in GitHub repository settings because GitHub does not read them from
tracked files.

## Pull Request Path

Every PR runs `.github/workflows/ci.yml`:

```bash
git diff --check
clojure -M:test
clojure -M:evals-test
clojure -T:build jar
clojure -T:build uber
java -jar target/fractal.jar help
```

The workflow is offline and should not require provider keys.

## Tag Release Path

Push an annotated tag named `vX.Y.Z`:

```bash
git tag -a vX.Y.Z -F notes.md
git push origin vX.Y.Z
```

`.github/workflows/release.yml` then:

1. derives `X.Y.Z` from the tag,
2. runs the offline validation suite,
3. builds `target/fractal-engine-X.Y.Z.jar`,
4. publishes the library jar to Clojars when secrets are configured,
5. builds `target/fractal.jar`,
6. creates a GitHub release containing both jars.

Required repository secrets for Clojars publishing:

- `CLOJARS_USERNAME`
- `CLOJARS_PASSWORD`

If those secrets are absent, the workflow skips Clojars with a notice and still creates
the GitHub release assets.

## Recommended GitHub Settings

Enable these in GitHub after the workflow file has landed on `main`:

- Settings -> Branches or Rulesets -> protect `main`
- Require a pull request before merging
- Require status checks to pass before merging
- Select the `test, evals, and jars` status check from the `ci` workflow
- Require branches to be up to date before merging
- Require conversation resolution before merging
- Block force pushes and branch deletion
- Enable Dependabot alerts and security updates
- Enable private vulnerability reporting if available

Keep direct pushes to `main` for maintainer release/setup work only. Normal changes
should arrive through PRs so CI is visible before merge.

## Public-Safe Release Notes

Release notes are public. Do not include private repository names, local machine paths,
API keys, workplace details, customer names, ticket IDs, or proprietary excerpts.
