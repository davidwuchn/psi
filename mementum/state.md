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
  - extracted canonical context/session-tree public summary projection to `components/app-runtime/src/psi/app_runtime/context_summary.clj`
  - extracted canonical transcript reconstruction to `components/app-runtime/src/psi/app_runtime/messages.clj`
  - extracted canonical extension UI/status projection to `components/app-runtime/src/psi/app_runtime/projections.clj`
  - extracted canonical background-job summaries to `components/app-runtime/src/psi/app_runtime/background_jobs.clj`
  - extracted canonical background-job widget/status projections to `components/app-runtime/src/psi/app_runtime/background_job_widgets.clj`
  - installed runtime-owned background-job UI refresh via `components/app-runtime/src/psi/app_runtime/background_job_ui.clj`
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
  - made RPC delegate extension UI snapshots to app-runtime projections
  - made TUI consume canonical extension UI snapshots instead of raw ui-state
  - made Emacs preserve backend-owned widget/status ordering instead of re-sorting it locally
  - made Emacs tree candidate labels reuse the same context summary line-label logic as the session-tree widget

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
- context/session-tree public summary maps
- transcript rehydration message reconstruction
- shared UI action model
- shared frontend action result normalization
- extension UI/status public projection
- background-job public summary maps
- background-job widget/status public projection

## Focused proof now in tests
- app-runtime tests now prove:
  - context snapshot projection preserves canonical display-name and updated-at semantics
  - context snapshot marks active/streaming sessions correctly
  - context snapshot falls back to the current session when the context index is empty
  - context/session-tree summary projection builds canonical labels, actions, and visibility rules
  - transcript messages are rebuilt canonically from the session journal
  - navigation results use the shared app-runtime context + message projections
  - frontend action result normalization works for canonical action names and canonical submitted values
  - background-job summaries build canonical text and summary lines
  - background-job widget/status projections build canonical widget rows and per-job statuses
  - background-job live UI projection updates and clears shared extension UI state correctly
- RPC tests now prove:
  - new-session / resume / tree / fork flows still emit canonical `session/resumed` + `session/rehydrated`
  - context updates still reflect the correct active session after new/fork flows
  - callback-driven `new_session` rehydrate payloads still work under the unified emission path
  - explicit `session-id` routing works in the newer navigation paths
  - frontend action flows use canonical action names
  - background-job RPC ops expose canonical summary fields
- Emacs tests now prove:
  - `ui/frontend-action-requested` is consumed from canonical `:ui/action`
  - Emacs submits canonical frontend action names back on `frontend_action_result`
  - no legacy payload duplication is required for selector/picker completion flows
  - context/session-tree widget labels and tree candidate labels reuse the same centralized summary logic
  - projected background-job widgets/statuses render correctly
- TUI tests now prove:
  - projected extension UI ordering is preserved unchanged
  - projected background-job widgets render from shared UI projection state

## Recent relevant commits
- `8a90c18` — ◈ plan: record tree candidate summary reuse
- `57aad1a` — ⚒ emacs: reuse context summary labels in tree candidates
- `819c99b` — ◈ plan: record context summary convergence
- `c50d1b8` — ⚒ emacs: centralize context summary labeling
- `ca4b6f7` — ⚒ context: add canonical public summary projections
- `5e20297` — ◈ plan: record background job ui projection convergence
- `97c934e` — ⚒ adapters: prefer background job ui projection over transcript text
- `8a2390c` — ⚒ background-jobs: refresh live job ui from runtime
- `80e799b` — ⚒ background-jobs: project live job ui widgets
- `c68bb1e` — ⚒ background-jobs: add canonical widget and status projections

## Suggested next step
- Finish the remaining adapter-convergence cleanup around transport/adapter-local semantic duplication:
  - inspect remaining footer/status diagnostics shaping for shared-vs-local ownership
  - inspect RPC handshake/bootstrap shaping for any duplicate summary semantics versus subscribed event projections
- Then refresh docs so ownership changes are reflected in `README.md` / `doc/` / Emacs frontend docs.

## Notes for future ψ
- `PLAN.md` is the main active-work tracker for adapter convergence.
- For this current architectural thread, recent commits are more trustworthy than `STATE.md`.
- `ui/frontend-action-requested` should now be treated as a canonical `:ui/action` contract; do not reintroduce payload duplication or legacy action-name compatibility without a deliberate compatibility decision.
- Background jobs should now be understood in three layers:
  - canonical summary text/maps in `app_runtime.background_jobs`
  - canonical widget/status projections in `app_runtime.background_job_widgets`
  - runtime projection into shared extension UI state in `app_runtime.background_job_ui`
- Context/session tree should now be understood in two layers:
  - canonical snapshot data in `app_runtime.context`
  - canonical public/session-tree summary labels + widget lines in `app_runtime.context_summary`
- Emacs still has some frontend-specific status/footer composition; inspect that before claiming all public summary duplication is gone.
