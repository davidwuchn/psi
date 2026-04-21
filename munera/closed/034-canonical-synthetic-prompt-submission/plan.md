# Plan

## Approach

Make the change at the dispatch boundary, not in scheduler internals.

Introduce one canonical dispatch-owned event for synthetic user prompt submission. That event should accept a prepared user message plus session-id and translate into the existing prompt lifecycle sequence. Then update scheduler delivery/drain to emit that event instead of building the lifecycle bundle directly.

## Slices

### Slice 1: canonical synthetic submission entry point
- add a dispatch event/handler for synthetic user prompt submission
- move the low-level prompt lifecycle event/effect sequence behind that handler
- ensure provenance-bearing user messages pass through unchanged

### Slice 2: scheduler migration
- replace local scheduler lifecycle bundling with the canonical synthetic submission entry point
- keep scheduler behavior and return values unchanged

### Slice 3: tests
- add focused tests for the canonical synthetic submission entry point
- update scheduler handler/lifecycle tests to assert use of the new entry point at the right level
- keep end-to-end scheduler behavior proofs green

## Risks

- introducing a new event that is too scheduler-specific rather than truly reusable
- accidentally changing prompt lifecycle ordering while centralizing the logic

## Decisions

- prefer one dispatch event over a local helper hidden in scheduler code
- preserve current prompt lifecycle semantics exactly; this is a shaping task, not a behavior-change task
