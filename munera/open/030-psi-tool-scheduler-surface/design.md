# 030 — Expose scheduler through psi-tool

## Goal

Let the agent schedule things for itself via `psi-tool`, so it can set up
delayed or recurring actions without relying on extensions or external tooling.

## Context

The dispatch effect layer already has scheduling primitives:
- `:runtime/schedule-thread-sleep-send-event` — fire a statechart event after a delay
- `:runtime/schedule-extension-dispatch` — fire an extension event after a delay

These are internal-only. The agent has no way to say "remind me in 5 minutes"
or "run this workflow step after a delay" or "poll this condition periodically".

## What the agent needs

1. **One-shot delayed action** — "do X after N ms/seconds/minutes"
2. **Recurring/periodic action** — "do X every N seconds until condition"
   (stretch; may defer to follow-on)
3. **List scheduled items** — introspect what's pending
4. **Cancel a scheduled item** — remove a pending scheduled action

## Constraints

- Must go through dispatch so scheduling is auditable and replayable
- Must scope to the agent session that created the schedule
- Must not allow arbitrary code execution — actions should be constrained
  to known safe operations (dispatch events, extension events, tool invocations,
  prompt submissions, workflow actions)
- Scheduled items should survive session state but NOT persist across process restarts
  (volatile scheduling is fine for v1)
- Timer threads should use the existing `daemon-thread-fn` infrastructure

## Open questions

- What action types should be schedulable? Candidates:
  - dispatch event (internal)
  - extension event dispatch
  - tool invocation
  - prompt/message injection ("remind" use case)
  - workflow run creation
- Should recurring schedules have a max-iterations safety cap?
- Should the agent see scheduled items through the EQL graph (resolver) or
  only through `psi-tool` action responses?
- How does this interact with background jobs? Should scheduled items appear
  as background jobs?

## Acceptance criteria

- Agent can schedule a delayed action via `psi-tool`
- Agent can list its pending scheduled actions via `psi-tool`
- Agent can cancel a pending scheduled action via `psi-tool`
- Scheduled actions execute after the specified delay
- Scheduled actions are scoped to the creating session
- Scheduling is auditable through the dispatch event log
