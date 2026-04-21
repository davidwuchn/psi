Slice 1 — State model and dispatch handlers:
- [ ] Define schedule record schema (malli)
- [ ] Add scheduler state paths to session-state helpers
- [ ] Implement `:scheduler/create` handler (validate, store, emit timer effect)
- [ ] Implement `:scheduler/cancel` handler (mark cancelled, emit cancel effect)
- [ ] Implement `:scheduler/fired` handler (idle → deliver, busy → queue)
- [ ] Implement `:scheduler/deliver` handler (inject prompt, mark delivered)
- [ ] Unit tests for all handlers

Slice 2 — Effects and timer:
- [ ] Add `:scheduler/start-timer` effect schema
- [ ] Add `:scheduler/cancel-timer` effect schema
- [ ] Implement `:scheduler/start-timer` effect executor (daemon thread → dispatch `:scheduler/fired`)
- [ ] Implement `:scheduler/cancel-timer` effect executor (interrupt thread)
- [ ] Runtime atom for timer thread handles, keyed by schedule-id
- [ ] Unit tests for effect execution

Slice 3 — Idle delivery hook:
- [ ] Add `:scheduler/drain-queue` effect type
- [ ] Emit `:scheduler/drain-queue` from `:on-agent-done`, `:on-abort`, `:on-compact-done`
- [ ] Implement drain effect: pop first queued item → dispatch `:scheduler/deliver`
- [ ] Integration test: busy session queues, idle transition delivers

Slice 4 — psi-tool surface:
- [ ] Create `psi_tool_scheduler.clj` module
- [ ] Implement delay parsing (`:delay-ms` relative, `:at` absolute UTC)
- [ ] Implement bounds validation (min 1s, max 24h)
- [ ] Implement per-session cap check (~50)
- [ ] Implement `op: "create"` — parse, validate, dispatch, return summary
- [ ] Implement `op: "list"` — read state, return schedule summaries
- [ ] Implement `op: "cancel"` — dispatch cancel, return confirmation
- [ ] Add `"scheduler"` to psi-tool action enum, op enum, parameters, description
- [ ] Add delegation path in psi-tool execute
- [ ] Unit tests for psi-tool scheduler ops

Slice 5 — EQL resolvers:
- [ ] `:psi.scheduler/schedules` resolver (session-scoped, all records)
- [ ] `:psi.scheduler/pending-count` resolver
- [ ] `:psi.scheduler/schedule` resolver (entity-seeded, single record by id)
- [ ] Unit tests for resolvers

Slice 6 — Background-job integration:
- [ ] Project schedules into the existing background-job surface
- [ ] Ensure projected background-job status/display derives from scheduler state
- [ ] Route background-job cancel to `:scheduler/cancel`
- [ ] Clean up timer handles on session close
- [ ] Cancel pending/queued schedules on session close
- [ ] Integration test: schedule appears in background jobs widget
- [ ] Integration test: session close cancels owned schedules and cleans up timer handles

Slice 7 — End-to-end tests:
- [ ] Full lifecycle: create → fire → deliver → agent responds → idle
- [ ] Busy session: create → fire → queue → idle → deliver
- [ ] Cancel pending schedule
- [ ] Cancel queued schedule
- [ ] Past absolute time → immediate fire
- [ ] Bounds rejection (too short, too long)
- [ ] Cap rejection (51st schedule)
