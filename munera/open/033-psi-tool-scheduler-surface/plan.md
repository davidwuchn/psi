# Plan

## Approach

Build bottom-up: state model → dispatch handlers → effects → idle-delivery hook →
psi-tool surface → EQL resolvers → background-job integration → tests at each layer.

Follow the `psi-tool-workflow` pattern: a dedicated `psi_tool_scheduler.clj` module
owns parsing, validation, and op dispatch for `action: "scheduler"`. The psi-tool
main module delegates to it the same way it delegates to `psi-tool-workflow`.

## Slices

### Slice 1: State model and dispatch handlers

Add scheduler state to session state:
- `[:scheduler :schedules]` — map of schedule-id → schedule record
- `[:scheduler :queue]` — ordered vector of schedule-ids waiting for idle delivery

Schedule record shape:
```clojure
{:schedule-id   "sch-xxx"
 :label         "check-build"     ;; optional
 :message       "Check build status"
 :source        :scheduled
 :created-at    #inst "..."
 :fire-at       #inst "..."       ;; computed absolute instant
 :status        :pending          ;; :pending | :queued | :delivered | :cancelled
 :session-id    "sid"}
```

Dispatch events:
- `:scheduler/create` — validate, store record, emit timer effect
- `:scheduler/cancel` — mark cancelled, emit cancel effect
- `:scheduler/fired` — timer expired: if idle → deliver, else → queue
- `:scheduler/deliver` — inject prompt into session (from queue drain)

All handlers are pure (return `{:root-state-update ... :effects [...]}`).

### Slice 2: Effects and timer

New effects:
- `:scheduler/start-timer` — spawn daemon thread, sleep, then dispatch `:scheduler/fired`
- `:scheduler/cancel-timer` — interrupt the sleeping thread (needs a handle)

Timer handle storage: the effect executor stores a `future` or `Thread` reference
in an atom keyed by schedule-id, outside session state (runtime-only, not replayable).

Add to `dispatch_schema.clj` effect schemas.

### Slice 3: Idle delivery hook

When the session transitions to `:idle` (`:on-agent-done`, `:on-abort`, `:on-compact-done`),
check the scheduler queue and deliver at most the oldest queued schedule.

The delivery dispatches `:session/prompt-submit` with provenance metadata on the
user message so it flows through the normal prompt lifecycle.

Decision: hook into the existing `:on-agent-done` / `:on-abort` / `:on-compact-done`
handlers via an additional effect (`:scheduler/drain-queue`) rather than modifying
those handlers directly. The effect checks for queued items and delivers the first one
(which will trigger a prompt cycle; subsequent queued items deliver on the next idle).

### Slice 4: psi-tool surface

New module: `psi_tool_scheduler.clj`

Ops:
- `op: "create"` — parse delay (`:delay-ms` or `:at`), validate bounds/cap, dispatch
  `:scheduler/create`, return `{:schedule-id ... :label ... :fire-at ...}`
- `op: "list"` — read scheduler state, return pending/queued schedule summaries
- `op: "cancel"` — dispatch `:scheduler/cancel`, return confirmation

Add `"scheduler"` to psi-tool:
- `:action` enum
- `:op` enum for scheduler (`"create" | "list" | "cancel"`)
- parameter properties: `:message`, `:label`, `:delay-ms`, `:at`, `:schedule-id`
- tool description update
- delegation in the main execute path

### Slice 5: EQL resolvers

Session-scoped resolvers:
- `:psi.scheduler/schedules` — all schedule records for the session
- `:psi.scheduler/pending-count` — number of pending + queued items
- entity-seeded single-schedule lookup keyed by `:psi.scheduler/schedule-id`

Makes scheduled items discoverable through standard graph queries.

### Slice 6: Background-job integration

Project schedules into the existing background-job surface:
- job-type: `:scheduled-prompt`
- status derives from schedule status
- display derives from label + fire-at + status
- cancel through background-job cancel routes to `:scheduler/cancel`

Background-job visibility is derived from scheduler state rather than maintained
as an independent source of truth.

### Slice 7: Tests

- Unit: state model helpers, delay parsing/validation, bounds enforcement, cap enforcement
- Unit: dispatch handlers (create, cancel, fired-when-idle, fired-when-busy, deliver)
- Unit: psi-tool scheduler ops (create, list, cancel, error cases)
- Integration: full lifecycle — create → timer fires → prompt delivered → session responds → idle
- Integration: busy-session path — create → fire → queue → idle → deliver
- Integration: cancel paths — cancel pending, cancel queued
- Integration: past absolute time → immediate fire

## Risks

- **Thread handle management**: cancelling sleeping daemon threads requires storing
  thread references outside session state. Must ensure cleanup on session close.
- **Queue drain ordering**: delivering one queued prompt triggers a full prompt cycle;
  subsequent queued items must wait for the next idle transition. Must not attempt
  to deliver all queued items synchronously.
- **Namespace load cycle**: the workflow surface hit a cycle through psi-tool → execution →
  prompt-control → psi-tool. The scheduler must avoid the same trap. `psi_tool_scheduler`
  should not require `prompt_control`; delivery should go through dispatch effects.

## Decisions

- Delivery goes through dispatch (`:scheduler/deliver` → effect that enters the
  canonical prompt submission lifecycle), not a parallel prompt injection path.
  This avoids the namespace cycle risk and keeps handlers pure.
- One queued item delivered per idle transition. The delivered prompt triggers a cycle;
  the next idle transition delivers the next queued item. This is naturally self-pacing.
- Timer thread handles stored in a runtime atom (not session state). Cleaned up on
  session close via a lifecycle hook.
- Pending and queued schedules are cancelled when the owning session runtime shuts down; if a distinct per-session close/unload lifecycle exists or is later introduced, scheduler teardown should route through that lifecycle as well.
