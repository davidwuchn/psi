# Plan: Explicit session-id on dispatch events

## Problem

Session-id is implicit — carried as `ctx[:target-session-id]`, read via
`ss/active-session-id-in` inside every handler. Adapters work around this
with `focus-ctx = fn [] (assoc ctx :target-session-id @atom)`.

## Goal

session-id explicit on every dispatch event → ctx is a pure capability bundle.

## Steps

### Step 1 — `dispatch!` propagates session-id from event-data into interceptor context

In `dispatch.clj`:
- `normalize-event` extracts `:session-id` from event-data and lifts it to
  the canonical event map as `:event/session-id`
- `dispatch!` projects `:session-id` onto the interceptor context from the event
- Handler interceptor passes `(:session-id ictx)` alongside ctx to handler-fn
  (handlers receive `[ctx event-data]` — no signature change yet, session-id
  is available on ictx and threaded into ctx for backward compat)

**Approach**: thread `:session-id` through ictx; in handler-interceptor,
before calling handler-fn, `assoc :target-session-id` onto ctx from event.
This makes existing handlers work without change (they still call
`active-session-id-in` which reads `:target-session-id`).

Lint check: ✓

### Step 2 — All `dispatch!` call sites pass explicit `:session-id`

Files to update:
- `core.clj` — prompt-in!, steer-in!, follow-up-in!, abort-in!, etc.
- `session_lifecycle.clj` — new-session, resume, fork
- `executor.clj` — tool recording, agent-end events
- `commands.clj` — set-model, set-thinking-level
- `rpc.clj` — set-model, set-thinking-level, set-session-name, set-auto-compaction
- `dispatch_handlers.clj` — internal dispatch! calls

Session-id source: `(ss/active-session-id-in ctx)` at each call site.

Lint check: ✓

### Step 3 — Remove `focus-ctx` / `target-session-id` from adapters

In `main.clj`:
- CLI `run-session`: replace `(focus-ctx)` with `ctx` + explicit session-id
  threaded through `cmd-opts`
- TUI: same
- RPC: same

`target-session-id` on ctx and `focus-ctx` / `focus-atom` vars removed.

Lint check: ✓

### Step 4 — Remove `active-session-id-in` from `session_state.clj`

- Remove `active-session-id-in` and `with-session-id-in` (or demote to
  internal use only for resolvers/non-dispatch reads)
- Remove `:target-session-id` from ctx construction in `create-context`

Lint check: ✓

### Step 5 — Commit

`⚒ Δ Make session-id explicit on dispatch events`
