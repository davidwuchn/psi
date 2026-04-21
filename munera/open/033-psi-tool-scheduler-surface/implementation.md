# Implementation notes

## Existing infrastructure

Dispatch effects already support delayed scheduling:
- `:runtime/schedule-thread-sleep-send-event` — `{:delay-ms N :event E}` → sleeps on daemon thread, then sends statechart event
- `:runtime/schedule-extension-dispatch` — `{:delay-ms N :event-name S :payload P}` → sleeps on daemon thread, then dispatches extension event

Both use `(:daemon-thread-fn ctx)` to spawn sleeping threads.

Relevant files:
- `components/agent-session/src/psi/agent_session/dispatch_effects.clj` — effect executors
- `components/agent-session/src/psi/agent_session/dispatch_schema.clj` — effect schemas
- `components/agent-session/src/psi/agent_session/psi_tool.clj` — psi-tool surface

## Implemented slices

### State / dispatch / timer
- added scheduler schema and default session state in `session.clj`
- added scheduler state-path helpers in `session_state.clj`
- added handlers in `dispatch_handlers/session_mutations.clj`:
  - `:scheduler/create`
  - `:scheduler/cancel`
  - `:scheduler/fired`
  - `:scheduler/deliver`
  - `:scheduler/drain-queue`
- added effects in `dispatch_schema.clj` + `dispatch_effects.clj`:
  - `:scheduler/start-timer`
  - `:scheduler/cancel-timer`
  - `:scheduler/drain-queue`
- timer handles now live in runtime-only `ctx[:scheduler-timers*]`
- `shutdown-context!` interrupts any remaining scheduler timer threads

### Idle drain hook
- `:on-agent-done`, `:on-abort`, and `:on-compact-done` now emit `:scheduler/drain-queue`
- queue draining delivers a single oldest queued schedule per idle transition

### psi-tool surface
- new `psi_tool_scheduler.clj`
- `psi-tool` now supports `action: "scheduler"`
- supported ops:
  - `create`
  - `list`
  - `cancel`
- validation covers:
  - `delay-ms` vs `at`
  - min 1000ms
  - max 24h
  - past absolute timestamps normalize to immediate fire time
  - max 50 pending/queued schedules per session

### EQL + background jobs
- new pure helper namespace `scheduler.clj`
- new resolvers in `resolvers/scheduler.clj`
- scheduler items project into background-job runtime as synthetic jobs with:
  - `:job-kind :scheduled-prompt`
  - `:tool-name "scheduler"`
  - `:job-id == schedule-id`
- background-job cancellation now routes scheduler-projected jobs to canonical `:scheduler/cancel`
- `session/cancel-job` also routes scheduler schedule ids to canonical scheduler cancellation so normal `/cancel-job` and UI flows work

## Verification performed
- targeted namespace tests passed via `clojure -M:test-paths -e ...` for:
  - `psi.agent-session.session-test`
  - `psi.agent-session.scheduler-dispatch-test`
  - `psi.agent-session.scheduler-tools-test`
  - `psi.agent-session.scheduler-resolvers-test`
  - `psi.agent-session.scheduler-background-jobs-test`
  - `psi.agent-session.scheduler-cancel-job-test`

## Remaining gaps
- no wall-clock sleep-based live timer integration test; current coverage drives canonical `:scheduler/fired` deterministically instead of waiting on real elapsed time
- close semantics are runtime detachment only; persisted session files are intentionally preserved
