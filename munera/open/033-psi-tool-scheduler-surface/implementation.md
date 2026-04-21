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

## Review summary

- Implementation quality is strong overall: behavior matches the task design closely, the `psi-tool` surface is coherent, and test coverage is strong across pure model, handlers, effects, projection, and lifecycle slices.
- Follow-up landed: shutdown now routes scheduler cancellation through the dispatch-owned `:scheduler/cancel-all` event per session before final runtime timer cleanup.
- Follow-up landed: `:scheduler/deliver` and `:scheduler/drain-queue` now share extracted scheduled-prompt lifecycle effect construction.
- Follow-up landed: the graph surface now supports entity-seeded single-schedule lookup by `:psi.scheduler/schedule-id` in addition to session-scoped `:psi.scheduler/schedules` and `:psi.scheduler/pending-count`.
- Follow-up landed: queued schedules no longer masquerade as `:pending-cancel` in the background-job projection; they now remain visible under the running/non-terminal job views, which is a truer mapping for “timer fired, awaiting idle delivery”.
