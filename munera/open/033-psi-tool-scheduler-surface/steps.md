Slice 1 — State model and dispatch handlers:
- [x] Define schedule record schema (malli)
- [x] Add scheduler state paths to session-state helpers
- [x] Implement `:scheduler/create` handler (validate, store, emit timer effect)
- [x] Implement `:scheduler/cancel` handler (mark cancelled, emit cancel effect)
- [x] Implement `:scheduler/fired` handler (idle → deliver, busy → queue)
- [x] Implement `:scheduler/deliver` handler (inject prompt, mark delivered)
- [x] Unit tests for all handlers

Slice 2 — Effects and timer:
- [x] Add `:scheduler/start-timer` effect schema
- [x] Add `:scheduler/cancel-timer` effect schema
- [x] Implement `:scheduler/start-timer` effect executor (daemon thread → dispatch `:scheduler/fired`)
- [x] Implement `:scheduler/cancel-timer` effect executor (interrupt thread)
- [x] Runtime atom for timer thread handles, keyed by schedule-id
- [x] Unit tests for effect execution

Slice 3 — Idle delivery hook:
- [x] Add `:scheduler/drain-queue` effect type
- [x] Emit `:scheduler/drain-queue` from `:on-agent-done`, `:on-abort`, `:on-compact-done`
- [x] Implement drain effect: pop first queued item → dispatch `:scheduler/deliver`
- [x] Integration test: busy session queues, idle transition delivers

Slice 4 — psi-tool surface:
- [x] Create `psi_tool_scheduler.clj` module
- [x] Implement delay parsing (`:delay-ms` relative, `:at` absolute UTC)
- [x] Implement bounds validation (min 1s, max 24h)
- [x] Implement per-session cap check (~50)
- [x] Implement `op: "create"` — parse, validate, dispatch, return summary
- [x] Implement `op: "list"` — read state, return schedule summaries
- [x] Implement `op: "cancel"` — dispatch cancel, return confirmation
- [x] Add `"scheduler"` to psi-tool action enum, op enum, parameters, description
- [x] Add delegation path in psi-tool execute
- [x] Unit tests for psi-tool scheduler ops

Slice 5 — EQL resolvers:
- [x] `:psi.scheduler/schedules` resolver (session-scoped, all records)
- [x] `:psi.scheduler/pending-count` resolver
- [x] `:psi.scheduler/schedule` resolver (entity-seeded, single record by id)
- [x] Unit tests for resolvers

Slice 6 — Background-job integration:
- [x] Project schedules into the existing background-job surface
- [x] Ensure projected background-job status/display derives from scheduler state
- [x] Route background-job cancel to `:scheduler/cancel`
- [x] Clean up timer handles on session close
- [x] Cancel pending/queued schedules on session close
- [x] Integration test: schedule appears in background jobs widget
- [x] Integration test: session close cancels owned schedules and cleans up timer handles

Slice 7 — End-to-end tests:
- [x] Full lifecycle: create → fire → deliver → agent responds → idle
- [x] Busy session: create → fire → queue → idle → deliver
- [x] Cancel pending schedule
- [x] Cancel queued schedule
- [x] Past absolute time → immediate fire
- [x] Bounds rejection (too short, too long)
- [x] Cap rejection (51st schedule)
