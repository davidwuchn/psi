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
- The adapter-convergence cleanup thread has now landed the remaining targeted ownership shifts for shared interactive semantics.
- New follow-on fix landed in Emacs tool rows: expanded tool output now renders on lines below the tool summary/status header instead of inline; tool body text now gets an explicit default-face baseline so summary/status faces do not bleed into output; and a `ψ:` prefix overlay boundary bug was fixed so tool rows inserted at assistant/tool boundaries no longer inherit the assistant prefix face. Toggle rerenders still preserve adjacent row boundaries so rows no longer disappear.
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
  - extracted shared session-summary/header-diagnostics projection to `components/app-runtime/src/psi/app_runtime/session_summary.clj`
  - made `psi.rpc.events/context-updated-payload` delegate to app-runtime context + context-summary projections
  - made `psi.rpc.events/session-updated-payload` delegate to shared session-summary projection
  - made `psi.rpc.events/footer-updated-payload` expose structured footer stats parts (`:usage-parts`, `:model-text`) in addition to canonical lines
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
  - made Emacs footer alignment prefer structured backend footer semantics instead of reparsing `:stats-line` in the common path
  - made Emacs header model label and `/status` session-summary line consume backend-shared session summary fragments
  - made `context/updated` carry both the canonical context snapshot and the canonical backend-projected session-tree widget payload
  - removed the handshake bootstrap `context/updated` event path so handshake is transport-only again
  - removed obsolete handshake/tree-label compatibility branches that were superseded by the converged projections

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
- session-summary model for header/diagnostic reuse
- navigation result maps
- context snapshot maps
- context/session-tree public summary maps
- context/session-tree widget projection payloads when adapters need the same rendered structure
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
  - context/session-tree summary projection builds canonical labels, actions, widget lines, and visibility rules
  - session-summary projection builds canonical header/status fragments
  - transcript messages are rebuilt canonically from the session journal
  - navigation results use the shared app-runtime context + message projections
  - frontend action result normalization works for canonical action names and canonical submitted values
  - background-job summaries build canonical text and summary lines
  - background-job widget/status projections build canonical widget rows and per-job statuses
  - background-job live UI projection updates and clears shared extension UI state correctly
- RPC tests now prove:
  - new-session / resume / tree / fork flows still emit canonical `session/resumed` + `session/rehydrated`
  - `footer/updated` exposes structured footer fields alongside canonical lines
  - `session/updated` exposes shared session-summary fragments for header/status reuse
  - `context/updated` exposes active-session-id, sessions, and backend-projected session-tree widget payload
  - handshake emits transport/server info only (no bootstrap `context/updated` event)
  - explicit `session-id` routing works in the newer navigation paths
  - frontend action flows use canonical action names
  - background-job RPC ops expose canonical summary fields
- Emacs tests now prove:
  - `ui/frontend-action-requested` is consumed from canonical `:ui/action`
  - Emacs submits canonical frontend action names back on `frontend_action_result`
  - no legacy payload duplication is required for selector/picker completion flows
  - footer alignment prefers structured backend footer fields, with `:stats-line` parsing retained only as compatibility fallback
  - header model label and `/status` session-summary text consume backend-owned shared session summary fragments
  - `context/updated` renders the backend-projected session-tree widget instead of rebuilding widget structure locally
  - tree candidate labels prefer backend-provided canonical labels when present
  - projected background-job widgets/statuses render correctly
- TUI tests now prove:
  - projected extension UI ordering is preserved unchanged
  - projected background-job widgets render from shared UI projection state

## Recent relevant commits
- `b568aa5c` — ⊘ emacs: restore bare /tree picker flow
- `a3326fe5` — ◈ state: refresh post-convergence handoff
- `a9b637d1` — ⚒ cleanup: remove obsolete handshake and tree label compatibility
- `6e48a54a` — ⚒ docs: describe context widget projection ownership
- `a09cee36` — ⚒ context: project canonical session-tree widget from backend

## Suggested next step
- The targeted adapter-convergence cleanup thread is done, including the bare `/tree` Emacs regression follow-on.
- `PLAN.md` has now been pruned so active work starts at `Prompt lifecycle architectural convergence`; the completed convergence plan was removed per the file's own rule.
- Best next move is to begin the prompt lifecycle convergence slice rather than continue cleanup work.
- Immediate highest-leverage target:
  - make prompt flow fully explicit as `prepare -> execute -> record`
- First concrete slice to take from `PLAN.md`:
  - extract a pure prepared-request projection from canonical session state
  - route prompt execution through dispatch-visible `:session/prompt-prepare-request`
  - make execution consume the prepared artifact instead of ambient recomputation
  - record the assistant/tool outcome deterministically through `:session/prompt-record-response`
- Keep dispatch trace and LSP follow-on work as secondary threads unless prompt lifecycle is blocked.

## Notes for future ψ
- `PLAN.md` is the main active-work tracker and now begins with prompt lifecycle work; treat that as the current primary thread.
- For the just-finished adapter-convergence thread, trust the recent commits and the current docs over older state notes.
- `ui/frontend-action-requested` should be treated as a canonical `:ui/action` contract; do not reintroduce payload duplication or legacy action-name compatibility without a deliberate compatibility decision.
- Background jobs should now be understood in three layers:
  - canonical summary text/maps in `app_runtime.background_jobs`
  - canonical widget/status projections in `app_runtime.background_job_widgets`
  - runtime projection into shared extension UI state in `app_runtime.background_job_ui`
- Context/session tree should now be understood in three layers:
  - canonical snapshot data in `app_runtime.context`
  - canonical public/session-tree summaries in `app_runtime.context_summary`
  - backend-projected session-tree widget payload carried on `context/updated`
- Header/status/footer ownership is now split as:
  - backend/app-runtime owns shared semantic fragments
  - adapters own only rendering/layout and transport/process/local-run-state concerns
