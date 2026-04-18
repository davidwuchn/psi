Goal: replace polling and incidental/manual refreshes for runtime-owned interactive projections with canonical event-driven propagation from session/runtime changes through app-runtime/RPC to adapters.

Status of the problem:
- helper child sessions created by `auto-session-name` can exist in runtime context state before the visible session tree reflects them
- `/tree` currently forces a fresh `context/updated`, which makes those already-existing child sessions appear
- this proves the session model is correct while projection delivery is stale
- therefore the immediate bug is missing evented propagation for context membership changes, not failed child-session creation and not a tree visibility predicate bug

Non-goals:
- do not move adapter rendering into RPC
- do not make session handlers emit RPC transport frames directly
- do not introduce more polling as the fix
- do not couple session-model state transitions to adapter-specific notions of focus or layout
- do not treat this as a child-session-only patch; the scope is canonical runtime-owned projection delivery
- do not attempt to replace unrelated polling for external systems/provider streams in this task

Design decisions

1. Ownership boundaries
- `agent-session` / dispatch owns canonical knowledge that runtime/session state changed
- `app-runtime` owns canonical public projection models derived from that state
- `rpc` owns transport delivery of those projections to subscribed clients
- adapters own rendering only
- in-process adapters may continue to read app-runtime/state directly, but the semantic change boundary must still be defined once in the runtime

2. Canonical projection classes in scope
This task covers two explicit runtime-owned projection classes:
- **context projection changes**
  - changes to the set/structure of sessions in context
  - examples: new session, child session creation, resume, fork, future close/remove
  - public projection: `context/updated`
- **UI projection changes**
  - changes to shared extension/UI projection state already owned by runtime state
  - examples: widgets, widget specs, statuses, notifications, dialogs
  - current delivery partially relies on RPC UI polling
  - target: event-driven delivery from canonical state changes rather than polling snapshots

Not in scope as projection classes for this task:
- provider token streaming
- external service polling
- arbitrary non-stateful deltas that are not shared runtime-owned projections

3. Canonical signaling primitive
Projection changes must be signaled as **dispatch/runtime effects**, not inferred by adapter polling and not emitted as transport frames directly from handlers.

Required semantic effect classes:
- `:projection/context-changed`
- `:projection/ui-changed`

The exact implementation key names may differ, but the semantics are fixed:
- effects are emitted by the runtime/session layer when canonical state changes in ways that affect a projection class
- effects are semantic invalidations, not payload carriers
- effects may include narrow routing hints such as relevant session ids, but they do not contain precomputed adapter payloads

Rejected alternatives for this task:
- UI polling as the primary detection mechanism
- direct RPC/frame emission from session handlers
- payload construction inside session handlers
- a child-session-specific side channel rather than a general projection-change mechanism

4. Projection payload rule
Projection-change effects do not carry final public payloads.

Instead:
- handlers mutate canonical state
- handlers return projection-change effects
- public payloads are recomputed from updated canonical state at the app-runtime/RPC emission boundary

This preserves ownership:
- state change semantics stay in session/runtime
- projection shape stays in app-runtime
- per-connection transport shaping stays in RPC

5. Delivery boundary
RPC delivery of projection changes must work for both:
- request-scoped changes
- out-of-band runtime changes

Examples of out-of-band runtime changes:
- scheduled extension events
- extension-triggered mutations
- background workflows
- login/background completion paths
- future service callbacks

Therefore delivery cannot rely only on explicit emits inside request handlers.

This task requires one canonical subscriber-aware delivery boundary that:
- receives projection-change effects from runtime/dispatch-owned changes
- fans them out to subscribed RPC connections
- recomputes per-connection payloads at delivery time

This is the key convergence requirement: request handlers may trigger state changes, but they must not remain the only place where projection delivery happens.

6. Subscriber and focus semantics
Projection delivery is per RPC connection, not global process broadcast.

Implications:
- one runtime projection-change effect may yield different payloads per connection
- especially for `context/updated`, because RPC-local focus session id is connection-local state
- therefore projection-change effects cannot be reused as public payloads
- payloads must be recomputed per connection using canonical state plus connection-local focus

This rule applies even when the originating state change is global/runtime-owned.

7. Projection production rules
### Context projection
A context-changed effect must be produced whenever context membership or parent/child structure changes.

In-scope producers include:
- `:session/new-initialize`
- `:session/create-child`
- `:session/resume-loaded`
- `:session/fork-initialize`
- any existing or future removal/closure path that changes context membership

It must not be produced for ordinary prompt/message/telemetry changes that leave context membership unchanged.

### UI projection
A ui-changed effect must be produced whenever canonical runtime-owned UI projection state changes.

In-scope producers include mutations/events that already own:
- widgets
- widget specs
- statuses
- notifications
- dialogs
- renderer registration if it affects visible shared projection

The goal is to replace polling of `events/ui-snapshot` with event-driven emission from those state changes.

8. App-runtime and RPC responsibilities
`app-runtime` remains the owner of shared projection models, including:
- context snapshot
- session-tree widget projection
- shared UI projection models/state summaries where applicable

`rpc` remains the owner of:
- subscriptions
- per-connection focus application
- event framing
- subscriber-aware fanout of public events

Required public event mapping:
- context change → `context/updated` using shared app-runtime context snapshot + session-tree widget projection
- UI change → existing UI events (`ui/widgets-updated`, `ui/widget-specs-updated`, `ui/status-updated`, `ui/notification`, `ui/dialog-requested`) emitted from canonical runtime-owned state changes

9. Ordering requirements
When one logical action changes both domain state and public projections:
- canonical state mutation must happen first
- projection-change effect is produced by that mutation path
- public payloads are computed from the updated canonical state
- projection delivery for one logical change must be causally downstream of the state mutation that just happened

Specific consequences:
- for child-session creation, `context/updated` must observe the new child in the same causal wave as the creation completes
- for navigation flows, existing ordering guarantees for rehydration/session/context events must remain coherent
- evented propagation must not race against stale snapshots built from pre-change state

10. Duplicate-emission convergence rule
Existing ad hoc emission paths must converge on the canonical projection-change delivery mechanism.

This means:
- old explicit `context/updated` and UI snapshot emits must either be removed or delegate to the same canonical mechanism
- steady-state delivery must not depend on both polling and evented emission simultaneously
- one logical change must not produce duplicate public events in steady state merely because old and new paths both fire

During migration, temporary overlap is acceptable only if explicitly staged and tested; the end state is one canonical mechanism.

11. Polling removal scope
This task removes polling used to discover **runtime-owned projection changes for public adapter delivery**.

That means:
- remove polling for context/session-tree refresh
- remove RPC UI watch-loop polling for runtime-owned UI projection delivery once equivalent evented propagation exists

This task does not prohibit all polling everywhere in the system.
It does not target polling that exists for:
- external systems
- provider streams
- unrelated boundaries that are not runtime-owned projection delivery

The target end state of this task is:
- no polling for runtime-owned context projection delivery
- no polling for runtime-owned shared UI projection delivery

12. Coalescing and event volume
Projection-change effects may become noisy when many state changes happen close together.

For v1 of this task:
- correctness and explicit ownership take precedence over optimal coalescing
- one logical state change may emit one projection-change effect for each affected projection class

But implementation must preserve a clean slot for future coalescing/batching if event volume becomes problematic.
The design must not preclude:
- per-dispatch-cycle coalescing
- per-connection debouncing
- projection-class batching

13. Replay and persistence semantics
Projection-change effects are derived runtime effects, not source-of-truth state.

Implications:
- canonical state remains the source of truth
- projection-change effects are not themselves durable domain facts
- replay must preserve state mutation semantics
- transport fanout from projection-change effects should be suppressed during replay unless replay explicitly requests UI/event reconstruction

This keeps projection delivery aligned with the existing “state is truth, effects are runtime boundary” architecture.

14. TUI implications
This task is not RPC-only, even though the immediate bug is most visible via RPC/Emacs.

Rules:
- the semantic projection-change boundary must be runtime-wide
- in-process adapters such as TUI may consume app-runtime/state directly instead of RPC frames
- but the existence of in-process rendering does not justify polling-based discovery of runtime-owned projection changes
- shared app-runtime projection ownership must remain the same for both RPC and in-process adapters

15. Tests required by the design
Minimum proof set:
- child-session creation emits fresh `context/updated` to subscribed clients without `/tree`
- new/resume/fork still emit correct context updates through the converged mechanism
- UI widget/status/notification/dialog changes emit their public events without the RPC UI watch loop
- adapters continue to render backend-owned payloads without local reconstruction
- no duplicate public events are emitted in steady state for one logical change
- out-of-band runtime changes also reach subscribed clients through the canonical delivery boundary

16. Architectural implications that implementation must settle explicitly
Implementation must review and settle:
- the exact effect schema used for projection-change signaling
- the exact runtime/connection fanout component that consumes projection-change effects for subscribed RPC clients
- whether existing prompt/login/command/navigation explicit emits are migrated immediately or delegated through a staged compatibility layer
- whether any remaining polling exists after convergence, and if so whether it is truly outside this task’s stated scope

Acceptance
- child session creation causes subscribed clients to receive `context/updated` without requiring `/tree` or any manual refresh
- context/session-tree updates are driven by canonical runtime effects, not polling
- shared runtime-owned UI projection updates are delivered without the RPC UI watch loop
- request-scoped and out-of-band runtime changes both use the same canonical projection-change delivery boundary
- app-runtime remains the owner of shared context/session-tree/UI projection models
- RPC remains the owner of transport/subscription delivery, not domain-state inference
- adapters remain renderers of backend-owned payloads
- tests cover context and UI evented propagation, out-of-band delivery, and prove steady-state removal of polling
