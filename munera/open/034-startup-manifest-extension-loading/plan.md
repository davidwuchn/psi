# Plan

## Approach

Implement one shared manifest-aware extension activation layer with exactly two activation forms:
- path-backed activation for source-file-backed extensions
- init-var-backed activation for manifest-installed library extensions

Refactor both startup and reload/apply to call that shared activation layer.

## Slices

### Slice 1 — Shared activation layer
- extract a shared activation entry point used by startup and reload/apply
- support activation from resolved source file path
- support activation from manifest library symbol + `:psi/init`
- register non-file-backed manifest extensions under `manifest:{lib}`

### Slice 2 — Startup integration
- realize required non-local deps during startup
- activate all enabled manifest extensions through the shared activation layer
- record truthful loaded/error results in startup summary

### Slice 3 — Reload/apply convergence
- replace any remaining reload/apply-specific activation behavior with calls to the shared activation layer
- preserve existing restart-required behavior where dependency realization cannot complete safely in-process

### Slice 4 — Verification and docs
- add targeted success and failure tests for startup activation
- add convergence tests comparing startup summary and live registry state
- update docs to describe startup activation semantics for local-root, git, and mvn manifest installs

## Risks

- preserving compatibility with existing surfaces that assume extension identities are file paths
- avoiding duplicate activation when the same extension could otherwise be discovered both by manifest activation and source-file discovery
- ensuring failure reporting stays truthful when dependency realization partially succeeds
