# 030 — Expose scheduler through psi-tool

## Goal

Let the agent schedule things for itself via `psi-tool`, so it can set up
delayed actions without relying on extensions or external tooling.

## Context

The dispatch effect layer already has scheduling primitives:
- `:runtime/schedule-thread-sleep-send-event` — fire a statechart event after a delay
- `:runtime/schedule-extension-dispatch` — fire an extension event after a delay

These are internal-only. The agent has no way to say "remind me in 5 minutes"
or "run this workflow step after a delay".

## What the agent needs

1. **One-shot delayed prompt injection** — "inject this message into my session after N ms/seconds/minutes"
2. **List scheduled items** — introspect what's pending
3. **Cancel a scheduled item** — remove a pending scheduled action

## Resolved design decisions

### Action type: prompt injection only

The sole schedulable action is prompt/message injection into the creating session.
This is the most general primitive — a scheduled message wakes the agent up with
context, and the agent can then do whatever it wants (run tools, create workflows,
dispatch events, etc.).

Other action types (tool invocation, workflow creation, extension dispatch) all
decompose into: wake the agent with a prompt → agent acts. This avoids modeling
tool result routing, workflow parameter shapes, or internal dispatch events in
the scheduler surface.

### No recurring schedules in v1

One-shot only. The agent can re-schedule on wake if it needs periodicity.
This avoids runaway risk entirely and keeps the scheduler simple. The agent's
natural control loop (wake → decide → optionally re-schedule) is more flexible
than a built-in repeat mechanism. Recurring schedules are a candidate follow-on.

### Introspection via both psi-tool and EQL graph

Scheduled items are visible through:
- `psi-tool` action responses (list/cancel for agent self-management)
- EQL resolvers (for extensions, other resolvers, UI, and agent graph queries)

This makes scheduled items first-class in the introspection surface.

### Scheduled items appear as background jobs

Scheduled items surface through the existing background-job infrastructure.
A scheduled item is a "pending" job with an estimated fire time. When it fires
(injects the prompt), it transitions through the normal prompt lifecycle.
This gives free UI widget visibility without new projection code.

## Constraints

- Must go through dispatch so scheduling is auditable and replayable
- Must scope to the agent session that created the schedule
- Actions constrained to prompt injection (no arbitrary code execution)
- Scheduled items survive in session state but NOT across process restarts
  (volatile scheduling is fine for v1)
- Timer threads use the existing `daemon-thread-fn` infrastructure

## Acceptance criteria

- Agent can schedule a delayed prompt injection via `psi-tool`
- Agent can list its pending scheduled items via `psi-tool`
- Agent can cancel a pending scheduled item via `psi-tool`
- Scheduled items are discoverable through EQL graph resolvers
- Scheduled items appear as background jobs in the UI
- Scheduled prompt injection executes after the specified delay
- Scheduled items are scoped to the creating session
- Scheduling is auditable through the dispatch event log
