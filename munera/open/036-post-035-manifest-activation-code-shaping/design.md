# 036 — Post-035 manifest activation code shaping

## Goal

Perform a narrow code-shaping pass on the manifest activation implementation that landed across tasks 034 and 035 so the remaining orchestration density is reduced without changing behavior.

This task is not about adding new manifest activation semantics. It is about improving simplicity, consistency, and local comprehensibility in the code that now implements the correct behavior.

## Problem statement

Tasks 034 and 035 corrected the startup/reload manifest activation model and resolved the failed-activation registry semantic. The implementation is now behaviorally correct, but review found a few remaining shape issues:

1. `components/agent-session/src/psi/agent_session/extension_runtime.clj` still concentrates several responsibilities in `bootstrap-manifest-extensions-in!`
2. startup and reload/apply still duplicate parts of the manifest apply orchestration shape
3. the startup bootstrap summary merge policy in `app_runtime.clj` is correct but implicit as an inline `merge-with`
4. the live-registry rollback invariant is implemented but not yet crystallized as an explicitly named design/code concept near the seam that enforces it
5. there is no small focused test proving rollback behavior directly at the loader/registry seam independent of higher-level startup tests

These are code quality issues rather than behavior gaps. They should be addressed without reopening the manifest activation feature surface.

## In scope

- reduce orchestration density in `extension_runtime.clj`
- extract small internal helpers where they improve local comprehensibility
- reduce duplicated manifest apply orchestration shape between startup and reload/apply where that can be done without obscuring policy differences
- make startup summary merge semantics more explicit if a named helper improves clarity
- add a focused test for the rollback invariant at the activation seam
- add a concise nearby note/docstring/comment that preserves the failed-activation live-registry invariant as an intentional design rule

## Out of scope

- changing startup or reload/apply behavior
- changing manifest schema or install UX
- changing the canonical registry identity format `manifest:{lib}`
- broad re-architecture of extension runtime
- task 034/035 doc rewrites beyond a small invariant-preserving note if needed

## Required design decisions

### 1. Behavior must remain unchanged

This task is complete only if the resulting code preserves all behavior established by tasks 034 and 035.

No success/failure/restart-required semantics may change.

### 2. Extraction must clarify ownership, not blur it

Any helper extraction must preserve the current architecture:
- `app_runtime` invokes startup manifest activation
- `extension_runtime` owns runtime orchestration
- `extensions` owns registry operations
- `extensions.loader` owns activation/loading mechanics
- `extension_installs` owns install/effective-state derivation and finalization

This task must not pull low-level loading mechanics back upward into app-runtime.

### 3. Rollback invariant must remain explicit

The following invariant must remain true and should be made easier to understand from local source:

- failed activation is absent from the live extension registry
- failure is represented through startup summary and persisted install state

### 4. Helper extraction must stay small

This task should prefer small private helpers over introducing a new abstraction layer unless the new layer is clearly simpler than the current code.

## Acceptance criteria

1. `extension_runtime.clj` is more locally comprehensible than before, with reduced orchestration density in the manifest startup path
2. duplicated manifest apply orchestration shape between startup and reload/apply is reduced where useful
3. startup summary merge semantics are explicit and easy to understand
4. the failed-activation live-registry invariant is documented or named near the seam that enforces it
5. a focused low-level test proves failed activation rollback from the live registry
6. all existing manifest activation behavior from 034/035 remains unchanged
7. no new manifest activation behavior or UX is introduced

## Notes

This is a follow-up shaping task only. If a proposed extraction does not make the code simpler to understand from local source, it should not be done.
