# Contributing

Thanks for helping improve fractal-engine. This project is public and open source, so
all tracked files, commit messages, issue text, PR text, and CI logs must be safe to
publish.

## Public-Safe Boundary

Do not commit or paste:

- API keys, tokens, `.env` contents, or secret paths
- private workplace, customer, repository, hostname, or ticket names
- copied proprietary code or documents
- local run stores such as `.fractal/`, `runs/`, or `.beads/`

Use synthetic examples or the offline fake/scripted provider for repros whenever
possible.

## Development Setup

Requirements:

- JDK 21+
- Clojure CLI 1.12+

Useful commands:

```bash
clojure -M:test
clojure -M:evals-test
clojure -T:build uber
java -jar target/fractal.jar help
```

Live provider runs can cost money and hang. Use explicit limits such as
`--call-timeout-ms`, `--max-turns`, and `--max-fanout` for ad-hoc runs.

## Pull Requests

Keep PRs narrow and explain the behavior change. Before asking for review, run:

```bash
clojure -M:test
clojure -M:evals-test
git diff --check
git status --short --branch
```

The CI workflow runs the same offline tests and builds the CLI uberjar for every PR
and every push to `main`.

## Release Process

Maintainers cut releases with annotated `v*` tags:

```bash
git tag -a vX.Y.Z -F notes.md
git push origin vX.Y.Z
```

The release workflow derives the version from the tag, validates the repo, builds the
thin Clojure library jar, publishes it to Clojars when `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD` repository secrets are configured, builds the CLI uberjar, and
creates a GitHub release.

See [docs/RELEASE.md](docs/RELEASE.md) for the repository settings checklist.
