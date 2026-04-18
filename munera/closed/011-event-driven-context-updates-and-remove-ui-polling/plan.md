Approach:
- Introduce canonical dispatch/runtime effects for runtime-owned projection changes.
- Keep session/runtime responsible for signaling semantic projection invalidation, not for building transport payloads.
- Keep app-runtime responsible for shared projection models.
- Keep RPC responsible for per-connection subscription-aware fanout and payload emission.
- Replace request-handler-local and polling-based projection refresh paths with one canonical delivery mechanism that works for both request-scoped and out-of-band runtime changes.

What the inventory established:
- `context/updated` is currently emitted from request-local/manual/navigation paths, not from a canonical runtime invalidation boundary.
- Shared UI projection events are currently delivered by RPC polling over `events/ui-snapshot` via `maybe-start-ui-watch-loop!`.
- The visible context/session tree is derived from canonical session state (`ss/list-context-sessions-in`) rather than a separate authoritative context index, so the bug is stale projection delivery, not missing context mutation.
- The in-scope context membership transitions are already clearly isolated in dispatch handlers:
  - `:session/new-initialize`
  - `:session/resume-loaded`
  - `:session/fork-initialize`
  - `:session/create-child`
- `:session/create-child` is the sharpest immediate producer gap: it mutates canonical state but currently returns no effects.
- RPC currently has no runtime-wide connection registry or subscriber fanout component; each stdio runtime owns exactly one connection-local `state` and one `emit-frame!` closure.
- Therefore this task needs both:
  - semantic projection invalidation effects in dispatch/runtime
  - a ctx-owned projection listener/publisher bridge that RPC connections can register with

Execution slices:
1. Inventory and boundary map
   - record the current producers of public projection refresh for:
     - `context/updated`
     - `ui/widgets-updated`
     - `ui/widget-specs-updated`
     - `ui/status-updated`
     - `ui/notification`
     - `ui/dialog-requested`
   - distinguish:
     - request-local explicit emits
     - navigation emits
     - command emits
     - prompt/login emits
     - polling-driven emits
     - out-of-band runtime paths with no current emit
   - record that per-connection payload shaping depends on RPC-local focus state
   - record the main concrete emit sites discovered so far:
     - subscribe-time explicit emits in `psi.rpc.session.ops/handle-subscribe`
     - command-time explicit `context/updated` via `psi.rpc.session.commands/emit-session-snapshots!`
     - navigation-time direct emits via `psi.rpc.session.emit/emit-navigation-result!`
     - polling-driven UI emits via `psi.rpc.session/maybe-start-ui-watch-loop!`

2. Canonical signaling schema
   - add dispatch/runtime effect schema entries for at least:
     - `:projection/context-changed`
     - `:projection/ui-changed`
   - keep them as semantic invalidations only; no final public payloads on effects
   - allow only narrow routing hints such as:
     - `:session-id`
     - `:reason`
     - optional provenance / dispatch id metadata if useful
   - define replay semantics explicitly: projection invalidation effects are runtime-derived and suppressed during replay unless replay explicitly requests reconstruction

3. Runtime projection publisher bridge
   - add a ctx-owned projection listener registry in `psi.agent-session.context`
   - expose callbacks on ctx for:
     - register projection listener
     - unregister projection listener
     - publish projection change
   - keep this bridge runtime-wide and transport-agnostic
   - make dispatch effect execution call the publish callback, not RPC directly
   - keep this mechanism intentionally projection-specific for this task:
     - it is for runtime-owned public projection invalidation only
     - it is not a general replacement for extension dispatch, the event queue, or arbitrary runtime pub/sub
     - use projection-scoped naming and event classes to avoid premature generalization

4. RPC subscriber-aware delivery boundary
   - add a per-connection projection listener that RPC registers into the shared runtime bridge
   - the listener must close over:
     - `ctx`
     - `emit-frame!`
     - connection-local RPC `state`
   - on projection invalidation, recompute payloads at emit time using canonical state plus connection-local focus/session subscriptions
   - likely extract this into a dedicated RPC helper namespace so request handlers stay small
   - store listener identity in RPC-local state so transport shutdown can unregister cleanly

5. Context projection convergence
   - wire `:projection/context-changed` into all in-scope context membership transitions:
     - `:session/new-initialize`
     - `:session/resume-loaded`
     - `:session/fork-initialize`
     - `:session/create-child`
     - any existing or future close/remove transition that changes membership
   - start with `:session/create-child` as the first concrete failing-path fix
   - route `context/updated` emission through the RPC projection listener boundary
   - remove or delegate old ad hoc `context/updated` emit sites to the canonical mechanism once coverage is proven

6. Shared UI projection convergence
   - wire `:projection/ui-changed` into runtime-owned shared UI state mutations
   - add a non-polling RPC path that can emit current canonical UI public events from the current UI snapshot without requiring previous/current polling diffs
   - route widgets/widget-specs/status/dialog/notification delivery through the projection listener boundary
   - remove `maybe-start-ui-watch-loop!` once equivalent evented delivery exists
   - if any UI polling remains, document the exact boundary and why it is outside this task

7. Duplicate-emission cleanup
   - audit prompt/command/navigation/login/frontend-action paths for explicit snapshot emits
   - converge them so steady-state behavior uses one canonical projection mechanism
   - preserve existing payload shapes and ordering guarantees while removing duplicate delivery
   - pay special attention to:
     - `emit-session-snapshots!`
     - `emit-navigation-result!`
     - subscribe-time bootstrap snapshot behavior

8. Proof and regression safety
   - add failing tests first where possible
   - prove child-session creation updates subscribed clients immediately without `/tree`
   - prove new/resume/fork still produce correct context updates
   - prove out-of-band runtime changes also deliver projection updates
   - prove UI widget/status/notification/dialog updates no longer require the RPC UI watch loop
   - prove no duplicate steady-state public events for one logical change
   - prove adapter-visible payload shapes remain backend-owned and unchanged

Risks:
- leaking transport/subscription concerns into session handlers
- adding semantic invalidation effects without also adding the runtime projection publisher bridge, leaving effects with nowhere canonical to go
- converging only child-session creation and leaving other projection producers inconsistent
- breaking ordering guarantees between rehydration/session/footer/context events
- leaving request-local emit code and canonical fanout active simultaneously, causing duplicate events
- removing polling before evented delivery fully covers out-of-band runtime changes
