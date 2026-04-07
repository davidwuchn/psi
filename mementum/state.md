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
- Active work has shifted from the earlier LSP/service slice to adapter convergence around shared runtime projections.
- Recent convergence work centered on making `app-runtime` own shared session navigation semantics used by RPC/TUI.
- Completed in this slice:
  - unified RPC session navigation emission through `psi.rpc.session.emit/emit-navigation-result!`
  - expanded explicit RPC session routing so more ops carry `session-id` through the request-handler path instead of relying on focus inference
  - extracted canonical context snapshot projection to `components/app-runtime/src/psi/app_runtime/context.clj`
  - extracted canonical transcript reconstruction to `components/app-runtime/src/psi/app_runtime/messages.clj`
  - made `psi.rpc.events/context-updated-payload` delegate to the app-runtime context projection
  - removed `app-runtime.navigation` dependence on `psi.rpc.session.message-source`
  - kept `psi.rpc.session.message-source` only as a backward-compatible alias to the app-runtime message projection
- Explicit-routing change in this slice:
  - `targetable-rpc-ops` now includes additional ops so request dispatch passes `session-id` wherever possible, including:
    - `new_session`
    - `switch_session`
    - `list_sessions`
    - `subscribe`
    - `login_begin`
    - `resolve_dialog`
    - `cancel_dialog`
- Canonical app-runtime projection ownership now includes:
  - navigation result maps
  - context snapshot maps
  - transcript rehydration message reconstruction
  - selector model
  - footer model
  - shared UI action model

## Focused proof now in tests
- app-runtime tests now prove:
  - context snapshot projection preserves canonical display-name and updated-at semantics
  - context snapshot marks active/streaming sessions correctly
  - context snapshot falls back to the current session when the context index is empty
  - transcript messages are rebuilt canonically from the session journal
  - navigation results use the shared app-runtime context + message projections
- RPC tests now prove:
  - new-session / resume / tree / fork flows still emit canonical `session/resumed` + `session/rehydrated`
  - context updates still reflect the correct active session after new/fork flows
  - callback-driven `new_session` rehydrate payloads still work under the unified emission path
  - explicit `session-id` routing works in the newer navigation paths

## Architectural note
- Strong current direction: explicit session routing over inferred focus.
- When an RPC operation can reasonably carry `session-id`, prefer passing it explicitly.
- Adapter-local focus still exists, but should be fallback/adapter state rather than the primary semantic input for shared runtime projections.

## Recent relevant commits
- `9829e79` — ⚒ tests: prove shared navigation context and rehydration projections
- `24b710d` — ⚒ app-runtime: own canonical transcript message projection
- `f899fc3` — ⚒ app-runtime: extract canonical context snapshot projection
- `e5a2c5b` — ⚒ rpc: unify navigation emission with explicit session routing

## Suggested next step
- Continue adapter convergence cleanup by removing remaining adapter-local semantic duplication around frontend action result handling and remaining RPC/TUI summary projections.
- Then update user-facing/docs architecture notes if needed so `README.md` / `doc/architecture.md` reflect app-runtime ownership of shared navigation/context/rehydration semantics.

## Notes for future ψ
- `mementum/state.md` was stale relative to current commits before this update; use this version as the new orientation baseline.
- `PLAN.md` remains the main active-work tracker for adapter convergence.
- For this current architectural thread, recent commits are more trustworthy than `STATE.md`.
