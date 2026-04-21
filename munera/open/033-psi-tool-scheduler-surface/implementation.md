# Implementation notes

## Existing infrastructure

Dispatch effects already support delayed scheduling:
- `:runtime/schedule-thread-sleep-send-event` — `{:delay-ms N :event E}` → sleeps on daemon thread, then sends statechart event
- `:runtime/schedule-extension-dispatch` — `{:delay-ms N :event-name S :payload P}` → sleeps on daemon thread, then dispatches extension event

Both use `(:daemon-thread-fn ctx)` to spawn sleeping threads.

Relevant files:
- `components/agent-session/src/psi/agent_session/dispatch_effects.clj` — effect executors
- `components/agent-session/src/psi/agent_session/dispatch_schema.clj` — effect schemas
- `components/agent-session/src/psi/agent_session/tools/psi_tool.clj` — psi-tool surface

## 2026-04-21

- Added pure scheduler state model in `components/agent-session/src/psi/agent_session/scheduler.clj`.
- Seeded session default state with `:scheduler {:schedules {} :queue []}` in `session.clj`.
- Added focused pure tests in `components/agent-session/test/psi/agent_session/scheduler_test.clj` covering create/list, bounds validation, fire queueing, delivery, cancel, and FIFO drain.
- This first slice intentionally stops before dispatch integration so scheduler behavior is locally proven as plain data transitions.
- Added dispatch handlers in `dispatch_handlers/scheduler.clj` for create/cancel/fired/deliver/drain-queue.
- Added session-state path support for scheduler state.
- Idle lifecycle hooks now emit scheduler drain on `:on-agent-done`, `:on-abort`, and `:on-compact-done`.
- Added focused handler tests in `scheduler_handlers_test.clj` to prove create/queue/cancel/deliver behavior and idle drain hook emission.
- Added runtime timer execution in `dispatch_effects.clj` with a runtime-only scheduler timer handle registry and explicit `:scheduler/start-timer` / `:scheduler/cancel-timer` effects.
- Added `psi_tool_scheduler.clj` and extended `psi_tool.clj` so `action: "scheduler"` now supports `create|list|cancel` with relative and absolute delay inputs.
- Added focused psi-tool scheduler tests in `psi_tool_scheduler_test.clj` covering create/list/cancel, past absolute time normalization, bounds rejection, and cap rejection.
- Added scheduler query/runtime projection in `scheduler_runtime.clj` and exposed scheduler state via resolvers in `telemetry_basics.clj`.
- Scheduler-backed items now also appear in the existing background-job query/view surface as `:scheduled-prompt` jobs.
- Added runtime timer cleanup helpers in `dispatch_effects.clj` and made context shutdown cancel all outstanding scheduler timers.
- Added focused effect tests in `scheduler_effects_test.clj` proving timer fire, timer cancel, and shutdown cleanup.

## Review summary

- Implementation quality is strong overall: behavior matches the task design closely, the `psi-tool` surface is coherent, and test coverage is strong across pure model, handlers, effects, projection, and lifecycle slices.
- Follow-up landed: shutdown now routes scheduler cancellation through the dispatch-owned `:scheduler/cancel-all` event per session before final runtime timer cleanup.
- Follow-up landed: `:scheduler/deliver` and `:scheduler/drain-queue` now share extracted scheduled-prompt lifecycle effect construction.
- Follow-up landed: the graph surface now supports entity-seeded single-schedule lookup by `:psi.scheduler/schedule-id` in addition to session-scoped `:psi.scheduler/schedules` and `:psi.scheduler/pending-count`.
- Follow-up landed: queued schedules no longer masquerade as `:pending-cancel` in the background-job projection; they now remain visible under the running/non-terminal job views, which is a truer mapping for “timer fired, awaiting idle delivery”.
