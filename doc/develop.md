# Development Guide

## Prerequisites

Install [pipx](https://pipx.pypa.io/) and then install pre-commit:

```bash
pipx install pre-commit
```

> **Note:** macOS ships a broken Python 2 `pre-commit` stub at
> `/usr/local/bin/pre-commit`. The pipx-installed version at
> `~/.local/bin/pre-commit` takes precedence once your shell PATH
> includes `~/.local/bin` (pipx ensures this).

## Setting up the git hooks

After cloning, install the pre-commit hooks once:

```bash
pre-commit install
```

## Pre-commit hooks

### `cljfmt-fix`

Runs `cljfmt fix` on every staged Clojure file (`.clj`, `.cljs`, `.cljc`)
before each commit.

- Files that are reformatted are automatically restaged.
- The hook exits with a non-zero status when it reformats anything, so
  pre-commit reports what changed. Re-run `git commit` to proceed.
- `cljfmt` must be on your `PATH` (e.g. installed via
  [bbin](https://github.com/babashka/bbin): `bbin install io.github.weavejester/cljfmt`).

#### Manual run

```bash
# Run against all files in the repo
pre-commit run --all-files

# Run only the cljfmt hook
pre-commit run cljfmt-fix

# Run against specific files
pre-commit run cljfmt-fix --files src/foo/bar.clj
```

## Formatting

Check formatting without modifying files:

```bash
bb fmt:check
```

Fix formatting across the whole repo:

```bash
cljfmt fix bb.edn deps.edn .lsp/config.edn .psi/startup-prompts.edn \
  components extensions spec specs test tests.edn extensions/tests.edn
```

## Linting

```bash
bb lint
```

## Tests

```bash
# All tests
bb test

# Clojure unit tests only
bb clojure:test:unit

# Clojure extension tests only
bb clojure:test:extensions

# Emacs frontend tests
bb emacs:check
```
