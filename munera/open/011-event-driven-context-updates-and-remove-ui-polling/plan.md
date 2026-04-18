Approach:
- Introduce canonical dispatch/runtime effects for runtime-owned projection changes.
- Keep session/runtime responsible for signaling semantic projection invalidation, not for building transport payloads.
- Keep app-runtime responsible for shared projection models.
- Keep RPC responsible for per-connection subscription-aware fanout and payload emission.
- Replace request-handler-local and polling-based projection refresh paths with one canonical delivery mechanism that works for both request-scoped and out-of-band runtime changes.

Execution slices:
1. Inventory and boundary map
   - identify every current producer of public projection refresh for:
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
   - identify exactly what payload shaping depends on connection-local focus state

2. Canonical signaling schema
   - define the dispatch/runtime effect schema for projection invalidation
   - require at least separate semantic classes for:
     - context projection changed
     - shared UI projection changed
   - decide what narrow routing hints belong on effects (for example relevant session-id), while keeping payload construction out of handlers
   - define replay behavior for these effects explicitly

3. Canonical fanout boundary
   - define the single subscriber-aware runtime/RPC fanout mechanism that consumes projection-change effects
   - ensure it works for:
     - request-scoped changes
     - extension-scheduled/out-of-band runtime changes
     - background/login/service completion paths
   - ensure per-connection payload recomputation applies focus state at emission time

4. Context projection convergence
   - wire context-change effect production into all context membership transitions in scope:
     - new session
     - child session creation
     - resume
     - fork
     - any in-scope removal/closure path that exists today
   - route `context/updated` emission through the canonical fanout boundary
   - remove or delegate old ad hoc `context/updated` emit sites to the canonical mechanism

5. Shared UI projection convergence
   - wire ui-change effect production into runtime-owned shared UI state mutations
   - route UI public events through the canonical fanout boundary
   - remove RPC UI watch-loop polling once equivalent evented delivery exists
   - if any UI polling cannot be removed during the task, document the exact remaining boundary and why

6. Duplicate-emission cleanup
   - audit prompt/command/navigation/login/frontend-action paths for explicit snapshot emits
   - converge them so steady-state behavior uses one canonical mechanism
   - preserve existing public payload shapes and ordering guarantees while removing duplicate event delivery

7. Proof and regression safety
   - add failing tests first where possible
   - prove child-session creation updates subscribed clients immediately without `/tree`
   - prove out-of-band runtime changes also deliver projection updates
   - prove new/resume/fork still produce correct context updates
   - prove UI widget/status/notification/dialog updates no longer require UI polling
   - prove no duplicate steady-state public events for one logical change

Risks:
- leaking transport/subscription concerns into session handlers
- converging only child-session creation and leaving other projection producers inconsistent
- breaking ordering guarantees between rehydration/session/footer/context events
- leaving request-local emit code and canonical fanout active simultaneously, causing duplicate events
- removing polling before evented delivery fully covers out-of-band runtime changes
