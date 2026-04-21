# Implementation notes

- Task created from observed mismatch between manifest effective state and startup activation state.
- Repro shape:
  - manifest shows enabled/effective extensions
  - startup summary shows zero loaded, zero errors
  - live extension details are empty
- Root cause confirmed: startup had been limited to `activation-plan` `:extension-paths`, so non-local manifest extensions were configured but not actually activated.

## Clean implementation slices completed

### Slice 1 — shared activation layer
- extracted a shared manifest-aware activation layer in:
  - `components/agent-session/src/psi/agent_session/extensions/loader.clj`
  - `components/agent-session/src/psi/agent_session/extensions.clj`
  - `components/agent-session/src/psi/agent_session/extension_runtime.clj`
- activation now supports exactly two forms under one shared abstraction:
  - path-backed activation
  - init-var-backed activation
- non-file-backed manifest installs use the canonical stable registry identity `manifest:{lib}`
- reload/apply now routes through the shared activation layer rather than bespoke activation-target handling

### Slice 2 — startup integration
- startup now uses the shared manifest-aware activation path
- startup now realizes non-local deps before activation attempts
- startup summary now reflects actual activation results
- startup now persists finalized post-activation install/apply state instead of pre-activation install state

### Slice 3 — verification and docs
- startup acceptance coverage now includes:
  - local-root success
  - git success
  - mvn success
  - dependency realization failure
  - init resolution failure
  - init execution failure
  - convergence between startup summary, live registry, and persisted install state
- docs updated to describe startup/reload activation semantics for local-root, git, and mvn manifest installs

## Validation notes

- 034 behavior is now aligned with the task design on this clean replay from the review-point base
- startup now reports activation truth rather than configuration intent
- remaining red tests in focused runs were unrelated scheduler/statechart failures already present in the branch and outside 034 scope
