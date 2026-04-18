2026-04-18
- Task created after verifying a live regression-shaped behavior that turned out to be stale UI context projection rather than failed auto-session-name execution.
- Live debugging established:
  - auto-session-name was creating helper child sessions successfully
  - helper child sessions existed in runtime context state and were visible via context introspection
  - the footer/session name had already updated reasonably, so rename execution was not the issue
  - `/tree` immediately made the helper child sessions visible in the session tree
- Current interpretation:
  - child-session creation updates runtime context membership
  - but that change is not propagated immediately to subscribed clients via canonical `context/updated`
  - command handling (`/tree`) forces a fresh snapshot emission, masking the missing automatic propagation
- Architectural direction chosen with the user:
  - prefer evented propagation from session-model/runtime changes
  - do not rely on UI polling for context/session-tree changes
  - create a full task covering both canonical context-change signaling and removal/narrowing of UI polling

2026-04-18 — inventory and architecture findings
- Public `context/updated` delivery currently comes from ad hoc/request-local paths rather than a canonical runtime invalidation boundary.
- Concrete current `context/updated` producers found so far:
  - subscribe-time explicit emit in `components/rpc/src/psi/rpc/session/ops.clj` (`handle-subscribe`)
  - command-time explicit refresh through `components/rpc/src/psi/rpc/session/commands.clj` → `emit-session-snapshots!` with `:context? true`
  - navigation-time direct emit through `components/rpc/src/psi/rpc/session/emit.clj` → `emit-navigation-result!`
- This explains the live symptom: `/tree` forces the stale projection to refresh because command handling explicitly emits `context/updated` after the command path.
- Shared UI projection delivery currently relies on RPC polling:
  - `components/rpc/src/psi/rpc/session.clj` → `maybe-start-ui-watch-loop!`
  - polling compares `events/ui-snapshot` over time and emits public UI events via `emit-ui-snapshot-events!`
- Canonical context visibility is not the problem surface:
  - session tree/context snapshots are derived from canonical session state via `ss/list-context-sessions-in`
  - therefore the bug is projection delivery staleness, not failed child creation and not a separate context index drift bug

2026-04-18 — lifecycle producer mapping
- The in-scope context membership transitions are already isolated cleanly in dispatch handlers:
  - `:session/new-initialize`
  - `:session/resume-loaded`
  - `:session/fork-initialize`
  - `:session/create-child`
- These handlers are the correct insertion point for semantic context invalidation effects because they are where the canonical state mutation is declared.
- `:session/create-child` is the sharpest immediate gap:
  - it mutates canonical session membership
  - it returns no effects today
  - this makes it the most plausible direct cause of the stale child-session tree regression
- `:session/new-initialize`, `:session/resume-loaded`, and `:session/fork-initialize` already return effects, so adding semantic context invalidation alongside those existing effects fits the current dispatch architecture naturally.

2026-04-18 — fanout boundary finding
- RPC currently has no runtime-wide connection registry or subscriber fanout component.
- Each RPC stdio runtime creates exactly one connection-local RPC `state` plus one `emit-frame!` closure.
- Public event emission currently requires both of those connection-local objects, which dispatch/runtime code does not know about directly.
- Therefore adding `:projection/context-changed` / `:projection/ui-changed` effects is not sufficient by itself.
- The task also requires a new ctx-owned runtime projection listener/publisher bridge that RPC connections can register with.
- Best-fit direction chosen:
  - add a ctx-owned projection listener registry in `psi.agent-session.context`
  - expose callbacks on ctx to register, unregister, and publish semantic projection invalidations
  - make dispatch effect execution call the publish callback
  - make each RPC connection register one listener that closes over `ctx`, `emit-frame!`, and connection-local `state`
  - make that listener recompute public payloads at delivery time using canonical state plus connection-local focus
- This preserves the desired ownership split:
  - dispatch/session layer emits semantic invalidation only
  - app-runtime continues to own projection models
  - RPC continues to own per-connection subscription-aware payload emission
  - adapters continue to render backend-owned payloads

2026-04-18 — likely implementation shape
- Add new dispatch effect schema entries:
  - `:projection/context-changed`
  - `:projection/ui-changed`
- Add new ctx callbacks in `psi.agent-session.context`:
  - `:register-projection-listener-fn`
  - `:unregister-projection-listener-fn`
  - `:publish-projection-change-fn`
- Add RPC-local tracking for projection listener identity so transport shutdown can unregister cleanly.
- Likely extract RPC listener registration and invalidation handling into a dedicated helper namespace rather than inflating request handlers.
- For UI delivery, current diff-based polling helpers are not a complete fit for event-driven invalidation; likely add a helper that emits current canonical UI public events from the current snapshot without requiring previous/current poll snapshots.

2026-04-18 — anti-generalization boundary
- The new ctx-owned bridge should remain projection-specific for this task.
- It is intended for runtime-owned public projection invalidation only:
  - context projection changed
  - shared UI projection changed
- It should not be introduced as a general runtime pub/sub mechanism yet.
- Existing mechanisms keep their current roles:
  - extension/domain events continue to use `ext/dispatch-in` (for example `git_commit_created`)
  - queue-boundary transport/runtime events continue to use the event queue where appropriate
  - RPC remains responsible for public per-connection event emission
- Naming should stay projection-scoped to prevent accidental broadening of scope:
  - projection listener
  - projection change
  - `:projection/context-changed`
  - `:projection/ui-changed`
- Revisit generalization only if later non-projection tasks present the same structure strongly enough to justify a wider runtime event substrate.
