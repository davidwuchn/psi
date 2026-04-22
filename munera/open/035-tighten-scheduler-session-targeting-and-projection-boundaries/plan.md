# Plan

## Approach

Treat this as a shaping task, not a behavior-expansion task.

First make scheduler session targeting explicit at the `psi-tool` boundary. Then factor scheduler projection helpers so each public shape has one obvious owner. Finally, ensure resolver exposure lives in one obvious namespace path and update tests/documentation to match.

## Slices

### Slice 1: session targeting tightening
- inspect how `psi-tool` invocations canonically know the invoking session
- remove the ad hoc `first` context-session fallback from `psi_tool_scheduler.clj`
- replace it with either:
  - required explicit `session-id`, or
  - canonical invoking-session derivation already used elsewhere in `psi-tool`
- add focused tests for no-session / explicit-session / canonical-inferred-session behavior

### Slice 2: projection boundary shaping
- inventory scheduler projection/summary helpers across `scheduler.clj`, `psi_tool_scheduler.clj`, and `scheduler_runtime.clj`
- factor them so ownership is clearer and duplication is reduced
- preserve distinct external shapes where they are intentionally different (internal record, psi-tool summary, EQL projection, background-job projection)

### Slice 3: resolver ownership cleanup
- confirm scheduler resolver ownership lives in one obvious namespace/module path
- remove or redirect any leftover parallel/compat exposure that makes ownership ambiguous
- keep tests aligned with the chosen canonical resolver namespace

### Slice 4: verification and docs
- rerun focused scheduler, resolver, and psi-tool tests
- record the shaping outcome in task implementation notes

## Risks

- accidentally broadening scope from shaping into scheduler behavior changes
- over-factoring projection helpers and obscuring intentionally different public views
- tightening targeting in a way that conflicts with the canonical `psi-tool` invocation model

## Decisions

- prefer canonical existing session-targeting patterns over new scheduler-specific inference rules
- keep one owner per projection surface where practical
- preserve behavior; improve explicitness and boundary clarity
