# 035 — Post-034 manifest activation cleanup

## Goal

Finish the remaining architectural and code-quality follow-up identified during review of task 034 so the manifest activation implementation is clean, locally comprehensible, and closure-ready.

This task does **not** add new manifest activation behavior. It reshapes the post-034 implementation so the existing behavior is owned in the right places and the remaining semantic ambiguities are resolved.

## Problem statement

Task 034 now implements the intended startup and reload/apply behavior for manifest-backed extension activation, but review found remaining cleanup issues:

1. startup extension activation orchestration is still assembled largely in `components/app-runtime/src/psi/app_runtime.clj`
2. failed manifest-backed activation can still leave a manifest identity present in extension registry registration order, which makes registry semantics ambiguous
3. startup acceptance tests still carry procedural setup noise and should be shaped into a clearer reusable fixture/helper surface
4. task notes should reflect the true remaining cleanup rather than implying perfect architectural closure

These are post-034 cleanup issues. They are real quality issues, but they should be resolved without broadening the behavior or reopening unrelated runtime threads.

## In scope

- extract startup manifest extension application orchestration out of `app_runtime.clj` into an extension-runtime-owned helper
- define one clear semantic for failed manifest activation in the live extension registry
- align registry-backed projections/tests with that semantic
- simplify startup manifest activation tests with reusable helpers/fixtures
- update docs/task notes if wording still overstates closure quality

## Out of scope

- changing manifest schema
- changing user-facing install UX
- changing scheduler/statechart/runtime behavior unrelated to manifest activation
- broad architectural redesign of extension installs beyond the cleanup needed for 034

## Required design decisions

### 1. Startup orchestration ownership

`app_runtime.clj` must no longer assemble manifest extension startup application from low-level install/runtime primitives inline.

A dedicated helper in extension runtime (or a directly adjacent extension-runtime-owned namespace) must own:
- install state computation for startup extension application
- dependency realization for startup activation
- manifest activation result assembly
- finalized install-state persistence inputs or result packaging
- startup summary projection inputs or result packaging

`app_runtime.clj` may still invoke that helper and consume its result, but it must not remain the semantic assembly point.

### 2. Failed activation registry semantic must be explicit

The implementation must choose one semantic and apply it consistently:

#### Option A — preferred
A failed manifest activation must **not** remain registered as a live extension in the extension registry.

#### Option B — acceptable only if explicit
A failed manifest activation may remain represented in the registry, but then registry data/projections must explicitly distinguish failed activation from successfully loaded activation using a first-class status surface.

The task is only complete when registry semantics are unsurprising and consistent with introspection/tests.

### 3. Test shape

Startup manifest activation tests must use helper functions or fixtures so each test reads as:
- setup manifest
- optionally define init var behavior
- bootstrap runtime
- assert summary / registry / persisted state

The tests must avoid repeated boilerplate and avoid encoding accidental implementation details when a behavior-level assertion is available.

## Acceptance criteria

This cleanup task is complete only when all of the following are true:

1. `app_runtime.clj` no longer owns the detailed manifest startup activation assembly logic inline
2. startup manifest activation behavior still matches task 034 acceptance behavior after extraction
3. failed manifest activation registry semantics are explicit and consistent
4. registry/introspection/tests no longer need to work around an ambiguous failed-activation registry meaning
5. startup manifest activation tests are simplified with shared helpers/fixtures
6. docs/task notes accurately describe the remaining closure state
7. no unrelated scheduler/statechart/runtime work is pulled into this task

## Notes

This task is a post-034 cleanup/follow-up task. It exists to address implementation review feedback before treating the manifest activation thread as fully closed.