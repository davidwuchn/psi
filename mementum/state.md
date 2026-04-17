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
- Compatibility scaffold removal has advanced materially.
- Adapter/UI compatibility cleanup now landed in Emacs:
  - removed footer `:stats-line` fallback parsing; footer now relies on canonical structured `:usage-parts` + `:model-text`
  - removed frontend session-tree label reconstruction; backend `:label` is now authoritative
  - removed `/tree` action payload compatibility forms; canonical action payloads are now used
  - removed targeted-event "missing session-id is ok" compatibility; session-targeted events now require canonical `:session-id` once session identity is known
  - removed remaining RPC payload camelCase/alternate key-shape fallbacks across Emacs event/projection/session-command handling
- Prompt-path compatibility cleanup now landed:
  - removed prompt-runtime timeout/abort sentinel compatibility handling; canonical internal sentinels only
  - removed prompt request runtime-model fallback scan of built-in `ai-models/all-models`; shared-session resolution now uses `model-registry` only
  - removed a few leftover prompt seam/test-hook comments and the `wait-fn` seam from prompt runtime waiting
  - moved `/skill:` and template invocation expansion canonically into request preparation
  - removed the remaining caller-local preview expansion path from app-runtime, RPC, and extension run-fn submission paths
  - prompt text memory recovery now runs from dispatch-owned prepared-request effects instead of caller-local pre-submit hooks
- Follow-on test cleanup landed so the full unit suite is green again (`1112 tests, 6425 assertions, 0 failures`):
  - extension API tests now match the richer list-services query shape and current explicit-session mutate behavior
  - git default-branch fallback test now stubs symbolic-ref + config lookup explicitly so `:fallback` is deterministic
- The adapter-convergence cleanup thread has now landed the remaining targeted ownership shifts for shared interactive semantics.
- New follow-on fix landed in Emacs tool rows: expanded tool output now renders on lines below the tool summary/status header instead of inline; tool body text now gets an explicit de-emphasized baseline face (`psi-emacs-tool-output-face`) so summary/status faces do not bleed into output; and a `ψ:` prefix overlay boundary bug was fixed so tool rows inserted at assistant/tool boundaries no longer inherit the assistant prefix face. Toggle rerenders still preserve adjacent row boundaries so rows no longer disappear.
- Tool schema convergence follow-on is now landed:
  - canonical tool definitions now live in `components/agent-session/src/psi/agent_session/tool_defs.clj`
  - built-in tools now use structured `:parameters` data instead of canonical `pr-str` strings
  - extension tool registration now normalizes tool defs at registration time
  - session state now stores canonical `:tool-defs`
  - provider conversation/tool projection now consumes canonical tool defs directly
  - agent-core tool schema now accepts structured parameters during migration
  - child-session creation/runtime paths now use `:tool-defs` instead of `:tool-schemas`
  - the `/new` regression caused by richer tool defs crossing into the stricter runtime boundary was fixed
- Focused failing-test cleanup after the convergence work also landed:
  - deferred extension prompt test now drives the session statechart explicitly instead of depending on prompt timing
  - interrupt dispatch test now drives streaming state explicitly instead of depending on prompt timing
  - service protocol mutation tests now match the current trace-opts arity
  - dispatch/LSP tests now account for injected `:dispatch-id`
  - full suite is green again (`1219 tests, 6903 assertions, 0 failures`)
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
- New resolver/introspection follow-on now landed in `agent-session`:
  - removed `psi.agent-session.resolvers.support/*session-id*`
  - removed resolver-time hidden session selection
  - session-scoped resolver reads now require explicit `:psi.agent-session/session-id`
  - direct Pathom/qctx callers now pass session-id in entities instead of binding dynamic vars
  - session git branch/context resolution is now explicitly session-scoped from session worktree/cwd
  - graph introspection now advertises `:psi.agent-session/session-id` as a root seed because session-scoped attrs require it explicitly
  - unit + extension + Emacs test suites are green after the change
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
- `a946fe8` — ⚒ custom-providers: reload-models command + session bootstrap refresh
- `97a66aa` — ⚒ custom-providers: wire model-registry init into bootstrap
- `5d98edf` — ⊘ extensions: guard dispatch-in contains? against non-map handler returns
- `a3f61cf0` — ⊘ tests: align dispatch-id expectations
- `b10667f4` — ⊨ tools: remove tool-schemas child-session compatibility

## Prompt lifecycle convergence — current status
- The prepare → execute → record → finish scaffold is now fully wired end-to-end.
- `prompt-finish` drives terminal statechart completion: dispatches `:on-agent-done`, sends `:session/reset` to statechart, reconciles background jobs. Session returns to `:idle` with `is-streaming false`.
- Tests prove: submit → prepare → execute → record → finish → idle, including tool-use continuation paths.
- All planned dispatch handlers and runtime effects exist: `prompt-submit`, `prompt-prepare-request`, `prompt-record-response`, `prompt-continue`, `prompt-finish`.
- Request preparation now owns canonical prompt expansion metadata (`:prepared-request/input-expansion`) and dispatch-visible prompt memory recovery.
- Follow-on cleanup also updated local docs/comments so the old preview-expansion ownership story is no longer described as current behavior.

## Custom providers — complete (`46bc655..a946fe8`)

All 8 slices landed:

1. `psi.ai.user-models` — parse `models.edn`, validate, resolve api-key specs
2. `psi.ai.model-registry` — merged catalog (built-in + user + project), auth lookup
3. `psi.ai.schemas/Provider` — relaxed from `[:enum :anthropic :openai]` to `keyword?`
4. `psi.ai.core/resolve-provider` — dispatch fallback by `:api` protocol key
5. `psi.agent-session.prompt-request` — custom provider auth + headers injection
6. Transport (openai/anthropic) — `:no-auth-header` and custom `:headers` support
7. All callers migrated from `ai-models/all-models` → `model-registry`
8. `/reload-models` command + session bootstrap refresh (`a946fe8`)

Config files:
- `~/.psi/agent/models.edn` — user-global custom providers
- `.psi/models.edn` — project-local custom providers

Usage:
- `/model local test-llm` — switch to a custom local model
- `/reload-models` — reload models.edn from the current session's cwd after editing

2 pre-existing failures in unit suite (unrelated: git-test, extensions-test).

## Current work update
- Auto session name extension is now landed as a real vertical slice.
- New runtime/extension seams landed to support it:
  - prompt lifecycle emits canonical extension event `session_turn_finished`
  - extensions can request delayed extension dispatch via `psi.extension/schedule-event`
  - extension API now supports explicit source-session targeting via `:query-session` and `:mutate-session`
- `extensions.auto-session-name` now:
  - counts completed turns per session
  - schedules checkpoints every 2 turns
  - reads journal-backed source session entries rather than agent-core message history
  - sanitizes visible user/assistant text only
  - creates a helper child session and runs one sync helper turn
  - applies inferred validated titles to the original session
- Extension-local safety guards now exist for:
  - helper-session recursion avoidance
  - stale checkpoint suppression
  - preserving manually changed current names by comparing against the last auto-applied name

## Suggested next step
- Next active threads are now:
  1. **Prompt lifecycle**: converge agent profile / skill injection into request preparation
  2. **Auto session name**: add helper model selection using the new model-selection-hierarchy thread
  3. **Model selection hierarchy**: task-class helper/background model resolution
  4. **Compatibility scaffold removal**: remove shared-session prompt-path seams, adapter/UI fallback payload compat
  5. **LSP**: decide debug atom telemetry permanence; simplify overlapping live/debug tests
- Decision taken: keep extension/workflow-local ephemeral sessions owned by their isolated workflow runtimes.
- For auto-session-name specifically, the next highest-leverage step is helper model selection, not more title plumbing.

## Notes for future ψ
- `PLAN.md` is still the main active-work tracker, but auto-session-name and model-selection-hierarchy are now live active threads rather than just design notes.
- For auto-session-name, trust the journal-backed source-session read path over agent-core `message-history`; the latter was the wrong surface for rename inference.
- The explicit session-targeting extension API (`:query-session`, `:mutate-session`) now exists because delayed/scheduled extension handlers must not rely on ambient session scope.
- The current manual-vs-auto protection is intentionally extension-local and heuristic:
  - last auto-applied name is remembered in extension state
  - if current name diverges from that remembered value, auto overwrite is blocked
  - this is not yet first-class runtime metadata
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
