# Plan

## Approach

Do one small code-shaping pass over the manifest activation runtime without changing behavior.

Prefer narrow, local extractions that improve readability and preserve the existing architecture.

## Slices

### Slice 1 — Runtime orchestration shaping
- identify the densest parts of `bootstrap-manifest-extensions-in!`
- extract only the smallest private helpers that improve local comprehensibility
- keep startup-specific policy readable at the call site

### Slice 2 — Shared apply-shape shaping
- reduce duplicated manifest apply orchestration shape between startup and reload/apply where helpful
- preserve the policy distinction between startup dep realization and reload/apply safety checks

### Slice 3 — Invariant and seam verification
- add a focused low-level rollback invariant test
- add a nearby note/docstring/comment preserving the failed-activation live-registry invariant
- name the startup summary merge policy if doing so improves clarity

## Risks

- over-abstracting and making the runtime harder to read
- accidentally changing 034/035 behavior while refactoring
- introducing a helper layer that merely moves complexity rather than removing it

## Review target

This task should leave the manifest activation code easier to understand from local source while keeping behavior identical.
