# Plan

## Approach

Perform one cleanup pass over the post-034 manifest activation implementation without changing the intended behavior.

The work should improve ownership, semantics, and test shape in small slices:
- move startup activation orchestration to extension runtime ownership
- resolve failed-activation registry semantics explicitly
- simplify startup tests with helpers
- update task/docs wording if needed

## Slices

### Slice 1 — Startup orchestration extraction
- introduce an extension-runtime-owned helper for startup manifest activation application
- move startup install/dep/activation/summary assembly out of `app_runtime.clj`
- keep the returned data shape sufficient for app-runtime startup wiring

### Slice 2 — Registry semantic cleanup
- choose and implement explicit failed manifest activation registry semantics
- update any related tests/projections to match the chosen semantic
- ensure registry meaning is unsurprising and consistent with startup summary/install state

### Slice 3 — Test shaping and closure notes
- extract reusable helpers/fixtures in startup manifest activation tests
- simplify test setup repetition
- update task/docs wording to reflect the cleaned state accurately

## Risks

- accidentally changing 034 behavior while extracting orchestration
- choosing registry semantics that create new drift with existing extension projections
- over-correcting test shape and losing useful acceptance signal

## Review target

This task should make 034 closure-ready, not broaden the behavior surface.

## Closure outcome

Completed cleanup now establishes:
- startup manifest activation ownership is extension-runtime-local rather than app-runtime-assembled
- failed manifest activation is absent from the live registry and remains represented only through startup summary + persisted install state failure surfaces
- startup acceptance coverage is shaped around reusable helpers rather than repeated procedural setup