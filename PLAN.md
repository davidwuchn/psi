# Plan

## Completed — remove the temporary ctx current-session bridge

The agent-session refactor is complete.

### Final outcome

- ✓ `:active-session-id` removed from root state atom
- ✓ RPC focus moved to endpoint-local atom
- ✓ TUI focus moved to TUI-local state
- ✓ `:psi.agent-session/context-active-session-id` removed from resolver surface
- ✓ service made explicit-only
- ✓ shared ctx `:current-session-id*` removed
- ✓ core now routes only through explicit `session-id` or scoped `:target-session-id`
- ✓ tests green

## Implemented design

> Core has no shared current session concept.
> RPC/UI layers own focus.
> Core logic uses explicit `session-id` or scoped child-session ctx only.

## Completed steps

### Step 1 — Move RPC focus to endpoint-local atom  ✓
- RPC runtime state owns `focus-session-id*`
- request routing scopes from explicit `:session-id` or RPC-local focus
- lifecycle flows update RPC-local focus

### Step 2 — Move TUI focus to TUI-local state only  ✓
- TUI owns `:focus-session-id`
- tree selector no longer depends on core-projected active-session attr
- session switching updates TUI-local focus directly

### Step 3 — Remove `:psi.agent-session/context-active-session-id` from core resolver surface  ✓
- attr removed from resolver outputs
- graph surface/tests updated

### Step 4 — Remove implicit current-session fallback from service  ✓
- service APIs now require explicit `session-id`
- no fallback to shared ctx current session

### Step 5 — Remove `:current-session-id*` from shared ctx  ✓
- ctx construction no longer includes `:current-session-id*`
- `ss/active-session-id-in` now reads only `:target-session-id`
- lifecycle flows re-scope locally instead of mutating shared ctx focus
- tests/helpers updated to use explicit re-scoping

## Success criteria achieved

- ✓ no `:active-session-id` in root state
- ✓ no `:current-session-id*` in shared ctx
- ✓ RPC owns focus locally
- ✓ TUI owns focus locally
- ✓ core resolvers/service no longer expose or depend on shared current-session state
- ✓ all tests green

## Deferred / next

### ExtensionInstanceState storage
- `:extension-instance-state` path in `:state*`
- get/set operations + EQL resolvers
- steering-mode/follow-up-mode stored there (not session data)

### Retire agent-core from remaining consumers
- dispatch-effects still sync to agent-core (RPC resume, session switch, bootstrap)
- core.clj still creates agent-ctx in `create-context`
- migrate each consumer to session-data reads, then remove agent-ctx from session entries

### Configuration scope
- config setters need to take scope: session > project > system

### Cache stability
- change injected prompt time to session creation time
- add time instant to each request/response
- set cache breakpoints on last three messages

## Active — remove `ui-state-view-in` anomaly via dispatch-owned UI state

### Decision

- **Phase 4 uses Option A now**: keep TUI behavior stable, stop relying on `ui-state-view-in`, and read canonical UI state via root-state accessors.
- **Plan B (full TUI API redesign) is deferred to the end** after stabilization.

### Why

`ui-state-view-in` is an atom-compat shim over `[:ui :extension-ui]`. It introduces an architectural anomaly by preserving an atom façade instead of routing UI state mutation through the dispatch pipeline.

### Target outcome

- single source of truth remains canonical `:state*`
- UI writes become dispatch-visible events
- `ui-state-view-in` removed
- TUI works via canonical UI state reads (Option A)
- Plan B captured for end-state redesign

### Scope (current callsites to migrate)

- `components/agent-session/src/psi/agent_session/extension_runtime.clj`
- `components/agent-session/src/psi/agent_session/main.clj`
- `components/agent-session/src/psi/agent_session/mutations.clj`
- `components/agent-session/src/psi/agent_session/state_accessors.clj`
- `components/agent-session/test/psi/agent_session/rpc_test.clj`
- `components/agent-session/src/psi/agent_session/session_state.clj` (remove shim)

### Phased checklist

#### Phase 0 — Baseline + guardrails
- [x] Add migration tracking (this section)
- [ ] Add search guard for final removal (`rg "ui-state-view-in" components/agent-session`)

#### Phase 1 — Introduce dispatch-owned UI events (parity)
- [x] Add handlers in `dispatch_handlers.clj` for:
  - [x] `:session/ui-set-widget-spec`
  - [x] `:session/ui-clear-widget-spec`
  - [x] `:session/ui-resolve-dialog`
  - [x] `:session/ui-cancel-dialog`
  - [x] `:session/ui-set-status`
  - [x] `:session/ui-clear-status`
  - [x] `:session/ui-notify`
  - [x] (optional) renderer registration / tools-expanded events

#### Phase 2 — Route current writers through dispatch
- [x] Update `mutations.clj` widget-spec mutations to dispatch UI events
- [x] Update `state_accessors.clj` dialog resolve/cancel to dispatch UI events
- [x] Update extension runtime UI context write methods to dispatch UI events

#### Phase 3 — Make UI state transforms pure
- [x] Add pure `state -> state'` reducers in `components/ui-state/src/psi/ui/state.clj`
- [x] Keep existing atom `...!` wrappers temporarily for compatibility
- [x] Have dispatch handlers apply pure reducers to `[:ui :extension-ui]`

#### Phase 4A — TUI integration now (selected)
- [x] Remove dependency on `ss/ui-state-view-in` from session-side integration
- [x] Pass canonical root state handle / accessor path to TUI integration boundary
- [x] Keep TUI behavior and rendering semantics unchanged

#### Phase 5 — Remove anomaly boundary
- [x] Delete `state-backed-atom-view` from `session_state.clj`
- [x] Delete `ui-state-view-in` from `session_state.clj`
- [x] Remove remaining references and dead requires

#### Phase 6 — Verification
- [x] Agent-session tests green (mutations + state accessors)
- [x] RPC dialog resolve/cancel flows green
- [x] TUI UI rendering smoke green (widgets/status/dialog/notifications)
- [x] Dispatch log includes UI events for UI mutations
- [x] `rg "ui-state-view-in"` returns zero matches

#### Phase 7 — Docs sync
- [x] Update `doc/architecture.md` to reflect dispatch-owned UI mutation model
- [x] Add concise migration note to `CHANGELOG.md`
- [x] Record key tradeoffs/lessons in `LEARNING.md`

#### Phase 8B — Deferred end-state (Plan B)
- [ ] Redesign TUI API to avoid atom assumptions entirely:
  - [ ] `:ui-read-fn`
  - [ ] `:ui-dispatch-fn` (or command fn)
  - [ ] optional subscription/event bridge
- [ ] Remove any residual direct shared-atom assumptions from adapter boundaries

### Commit strategy (small, reversible)

1. add dispatch UI event handlers
2. route existing UI writers to dispatch
3. introduce pure UI reducers and switch handlers to reducers
4. execute Phase 4A (TUI canonical read path, no shim)
5. remove `ui-state-view-in`
6. verify + docs sync
7. (later) implement Phase 8B

### Done criteria

- [ ] `ui-state-view-in` removed
- [ ] UI mutations are dispatch-owned and visible in dispatch log
- [ ] canonical UI query surfaces remain intact
- [ ] TUI/RPC behavior preserved under Option A
- [ ] Plan B retained as explicit deferred architecture task
