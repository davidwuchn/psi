# Mementum State

Bootstrapped on 2026-04-02.

## Current orientation
- Project: psi (`/Users/duncan/projects/hugoduncan/psi/refactor`)
- Runtime: JVM Clojure

## Key files
- `README.md` — top-level user documentation
- `META.md` — project meta model
- `PLAN.md` — current implementation plan
- `STATE.md` — project-local state file
- `AGENTS.md` — bootstrap/system instructions

## Current work state
- Active work is on adapter convergence: moving shared interactive semantics out of RPC/TUI/Emacs duplication and into `app-runtime`.
- Recent completed convergence work now includes:
  - unified RPC session navigation emission through `psi.rpc.session.emit/emit-navigation-result!`
  - expanded explicit RPC session routing so more ops carry `session-id` through request handling instead of relying on adapter focus inference
  - extracted canonical context snapshot projection to `components/app-runtime/src/psi/app_runtime/context.clj`
  - extracted canonical transcript reconstruction to `components/app-runtime/src/psi/app_runtime/messages.clj`
  - made `psi.rpc.events/context-updated-payload` delegate to the app-runtime context projection
  - removed `app-runtime.navigation` dependence on `psi.rpc.session.message-source`
  - removed the redundant RPC message-source alias entirely
  - centralized frontend action result normalization in `app-runtime.ui_actions`
  - centralized selector submitted-value normalization in `app-runtime.ui_actions`
  - centralized model/thinking submitted-value normalization in `app-runtime.ui_actions`
  - centralized cancelled/failed frontend action result messaging in `app-runtime.ui_actions`
  - canonicalized frontend action names across adapters (`select-session`, `select-resume-session`, `select-model`, `select-thinking-level`)
  - canonicalized frontend action ids to match canonical action names
  - removed legacy action-name compatibility branches from app-runtime + Emacs
  - removed legacy payload duplication from `ui/frontend-action-requested`; `:ui/action` is now the canonical contract

## Explicit routing direction
- Strong current direction: explicit session routing over inferred focus.
- When an RPC operation can reasonably carry `session-id`, pass it explicitly.
- `targetable-rpc-ops` was expanded so explicit routing now includes additional ops such as:
  - `new_session`
  - `switch_session`
  - `list_sessions`
  - `subscribe`
  - `login_begin`
  - `resolve_dialog`
  - `cancel_dialog`
- Adapter-local focus still exists, but should be fallback/adapter state rather than the primary semantic input for shared runtime projections.

## Canonical app-runtime ownership now includes
- selector model
- footer model
- navigation result maps
- context snapshot maps
- transcript rehydration message reconstruction
- shared UI action model
- shared frontend action result normalization

## Focused proof now in tests
- app-runtime tests now prove:
  - context snapshot projection preserves canonical display-name and updated-at semantics
  - context snapshot marks active/streaming sessions correctly
  - context snapshot falls back to the current session when the context index is empty
  - transcript messages are rebuilt canonically from the session journal
  - navigation results use the shared app-runtime context + message projections
  - frontend action result normalization works for canonical action names and canonical submitted values
- RPC tests now prove:
  - new-session / resume / tree / fork flows still emit canonical `session/resumed` + `session/rehydrated`
  - context updates still reflect the correct active session after new/fork flows
  - callback-driven `new_session` rehydrate payloads still work under the unified emission path
  - explicit `session-id` routing works in the newer navigation paths
  - frontend action flows use canonical action names
- Emacs tests now prove:
  - `ui/frontend-action-requested` is consumed from canonical `:ui/action`
  - Emacs submits canonical frontend action names back on `frontend_action_result`
  - no legacy payload duplication is required for selector/picker completion flows

## Recent relevant commits
- `930df9b` — ⚒ frontend-actions: remove payload fallback and document ui/action contract
- `5a93918` — ⚒ frontend-actions: remove legacy action-name compatibility
- `d6b1b92` — ⚒ ui-actions: canonicalize action ids
- `01a7b5e` — ⚒ tests: rename frontend action cases to canonical labels
- `5e8acda` — ⚒ tests: prefer canonical frontend action names in ui-actions
- `518380d` — ⚒ emacs: normalize frontend action keys to canonical names
- `c36f8a7` — ⚒ frontend-actions: prefer canonical action names across adapters
- `b733e69` — ⚒ ui-actions: drop legacy action-name from emitted action models
- `535f201` — ⚒ tests: prefer canonical frontend action names in rpc flows
- `12534cd` — ⚒ ui-actions: centralize frontend action result normalization

## Suggested next step
- Continue adapter convergence by targeting Phase 5 shared public summaries:
  - background jobs
  - context/status public summaries
  - extension UI/status summary projections where both adapters need the same answer
- Then refresh docs again if ownership shifts further.

## Notes for future ψ
- `PLAN.md` is the main active-work tracker for adapter convergence.
- For this current architectural thread, recent commits are more trustworthy than `STATE.md`.
- `ui/frontend-action-requested` should now be treated as a canonical `:ui/action` contract; do not reintroduce payload duplication or legacy action-name compatibility without a deliberate compatibility decision.
