# 035 — Tighten scheduler session targeting and projection boundaries

## Goal

Tighten the remaining non-blocking architectural looseness around the scheduler surface without changing scheduler behavior.

## Context

Tasks `033` and `034` successfully delivered the `psi-tool` scheduler surface and converged synthetic prompt submission onto a canonical dispatch-owned entry point. Post-close review found the implementation sound overall, but identified a few minor concerns worth addressing in a focused shaping task:

1. `psi_tool_scheduler.clj` still falls back to the first context session when `session-id` is omitted
2. schedule summary/projection shaping is spread across multiple nearby modules
3. scheduler resolver ownership should remain singular and obvious as resolver cleanup continues

These are not behavior bugs. They are architectural tightening work.

## Intent

Make scheduler targeting and public projection boundaries more explicit and less ambient, while preserving the current scheduler semantics and user-visible behavior.

## In scope

- tighten or eliminate ambient `psi-tool` scheduler session fallback behavior
- align scheduler session targeting with the project's explicit-session preference
- reduce unnecessary duplication in scheduler summary/projection shaping
- make scheduler resolver ownership singular and obvious
- update tests and task docs to reflect the tightened shape

## Out of scope

- changing scheduler semantics
- changing prompt lifecycle semantics
- adding new scheduler ops
- adding persistence or recurring schedules
- changing background-job behavior beyond projection shaping/refactoring

## Desired shape

After this task:

- `psi-tool` scheduler targeting is explicit or derived from a canonical invoking-session source, not from "first session in context"
- scheduler public/projection shapes have a clearer ownership boundary
- scheduler resolver ownership is obvious from the namespace structure
- no user-visible regression in `create | list | cancel` behavior

## Acceptance criteria

- scheduler `psi-tool` execution no longer relies on `first` context-session fallback for target selection
- if target inference remains supported, it uses a canonical invoking-session source and is documented/tested explicitly
- duplicated scheduler summary/projection shaping is reduced or clearly factored by ownership
- scheduler resolver ownership is singular and obvious in code structure
- existing scheduler tests stay green
- focused tests cover the tightened targeting behavior
- the task remains a shaping/refinement task rather than a behavior-expansion task
