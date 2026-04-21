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
