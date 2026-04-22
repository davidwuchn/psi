# Implementation notes

## Intent

Post-close shaping follow-up for tasks `033` and `034`.

This task exists to tighten non-blocking concerns found in review:
- ambient scheduler session fallback in `psi_tool_scheduler.clj`
- projection/helper ownership spread across nearby scheduler modules
- resolver ownership clarity

## 2026-04-21

- Tightened scheduler `psi-tool` session targeting by removing the ambient `first` context-session fallback from `psi_tool_scheduler.clj`.
- Scheduler actions now require the invoking session to be supplied through the existing `psi-tool` runtime options; when absent, scheduler reports a validation error instead of silently selecting an arbitrary context session.
- Applied the same targeting rule to `psi_tool_workflow.clj` so session-scoped `psi-tool` actions are aligned instead of scheduler-specific.
- Consolidated scheduler projection ownership in `scheduler_runtime.clj`:
  - canonical session-state read helpers
  - psi-tool schedule summary projection
  - background-job projection
  - existing EQL projection helpers
- Made scheduler resolver ownership singular and obvious by registering `psi.agent-session.resolvers.scheduler` in the top-level resolver assembly and removing duplicate scheduler resolvers from `resolvers.telemetry-basics`.
- Added focused scheduler targeting tests covering the missing-session validation path and the canonical invoking-session path.
- Verification:
  - `bb clojure:test:unit --focus ...` was attempted for focused coverage, but the task wrapper ran the unit suite broadly
  - full unit result remained green: `1289 tests, 9989 assertions, 0 failures`
  - `clj-kondo --lint` on the changed files is clean apart from a pre-existing warning in `psi_tool_workflow.clj` for unused `chain-name`

## Review outcome

- Outcome: approved.
- The task stayed narrow and shaping-oriented.
- Scheduler behavior was preserved while session targeting and projection ownership became more explicit.
