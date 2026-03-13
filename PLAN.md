# Plan

Ordered steps toward PSI COMPLETE.

---

## Done

### Step 11h — TUI tmux integration harness baseline ✓ complete
- Added Allium contract: `spec/tui-tmux-integration-harness.allium`.
  - Scope includes tmux harness lifecycle and one baseline end-to-end scenario.
  - Scenario contract: launch TUI → wait ready → `/help` marker assertion → `/quit` clean-exit assertion.
- Implemented reusable test harness helpers:
  - `components/tui/test/psi/tui/test_harness/tmux.clj`
  - Shared primitives: tmux preflight, detached session start, send-line, pane capture/sanitize, wait/assert helpers, cleanup.
- Added baseline integration test:
  - `components/tui/test/psi/tui/tmux_integration_harness_test.clj` (`^:integration`)
  - Uses detached tmux with unique session names and always-on cleanup.
- Baseline assertions stabilized to runtime-observable markers:
  - readiness marker: `刀:` / `Type a message`
  - help marker: `(anything else is sent to the agent)`
  - quit marker: pane process transitions away from `java`.
- Implementation commit: `1613f5f`

### Step 11g — Subagent widget UI spec distillation + elicitation ✓ complete
- Added dedicated UI contract: `spec/subagent-widget-ui.allium` (commit `480e2f6`).
- Distilled widget/result-message behavior from implementation + tests, then refined by elicitation decisions:
  - terminal widgets persist until explicit remove
  - widget ordering policy is most-recent-first
  - TUI remains text-only for widget action bindings
  - render truncation/preview limits are per-request configurable (with defaults)
  - result heading includes optional `@agent` and `[fork]` markers
  - error summary precedence remains `error-message` then last non-blank result line
  - visibility policy is scoped to subagent sessions that are children of current session
- Follow-up convergence remains: align extension/runtime/tests to this new UI contract.

### Step 11e — AGENTS alpha + multi-file Allium constraints ✓ complete
- Prompt-memory contract in `AGENTS.md` now explicitly states alpha posture: `In alpha; no backward compatibility`.
- Added explicit structural Allium invariants for specs:
  - `spec_consists_of_multiple_connected_allium_files`
  - `spec_has_no_isolated_allium_file`
- Captured in commit `8bd0d58` to anchor future spec-shape decisions in prompt memory.

### Step 11f — PSL follow-up prompt/result refinement ✓ complete
- `extensions/src/extensions/plan_state_learning.clj` PSL prompt now includes an explicit instruction to avoid compliance commentary in final responses.
- PSL workflow status messaging now only emits an extension transcript message on failure (success-path status message removed to reduce transcript noise).
- Captured in commit `0fcdf3b`.

### Step 11c — Session spec consolidation + naming normalization ✓ complete
- Added core session domain spec: `spec/session-core.allium` (Allium 2)
- Added session forking API/contract spec: `spec/session-forking.allium` (Allium 2)
- Aligned session specs to Allium 2 and core naming:
  - `spec/session-management.allium`
  - `spec/session-persistence.allium`
  - `spec/session-startup-prompts.allium`
- Normalized naming across `spec/*.allium`: `sessionId/session_id -> id`, `cwd -> worktree_path`
- Follow-up semantic repair pass completed for high-risk specs (`session-core`, `session-management`, `git-worktrees`, `remember-capture`)
- Outcome: session/worktree vocabulary is now consistently query-surface oriented in specs

### Step 11d — Fork persistence semantics stabilization ✓ complete
- Runtime fork behavior in `components/agent-session/src/psi/agent_session/core.clj` now converges with `spec/session-persistence.allium` and `spec/session-forking.allium`.
- `fork-session-in!` now eagerly creates/writes the child session file, persists branched entries up to `entry-id`, and records lineage via header `:parent-session` when parent file exists.
- Added regression in `components/agent-session/test/psi/agent_session/core_test.clj`:
  - `fork-session-persists-child-file-with-parent-lineage-test`
- Added explicit spec guarantee in `spec/session-forking.allium`:
  - `ForkPersistsChildLineageEagerly`
- Implementation commit: `8e36668`

### Step 1 — Split allium specs  ✓
- `spec/session-management.allium`
- `spec/extension-system.allium`
- `spec/compaction.allium`

### Step 2 — Implement `agent-session` component  ✓
- 10 namespaces: core, statechart, session, compaction, extensions, persistence,
  resolvers, tools, executor, main
- 139 tests, 509 assertions, 0 failures

### Step 3 — Runnable entry point  ✓
- `executor.clj` bridges ai streaming → agent-core loop protocol
- `tools.clj` implements read/bash/edit/write
- `main.clj` provides interactive REPL prompt loop
- `:run` alias in root `deps.edn`

### Step 4 — Wire agent-session into global query graph  ✓
- `agent-session.core`: `register-resolvers!` / `register-resolvers-in!`
- `introspection.core`: `create-context {:agent-session-ctx …}`,
  `query-agent-session-in`, single-rebuild registration
- `main.clj`: calls `register-resolvers!` at startup

---

### Step 5 — Fix TUI session input  ✓
- Replaced custom ProcessTerminal + differential renderer with charm.clj
- charm.clj uses JLine3 (FFM) for correct raw mode + input + rendering
- `psi.tui.app`: Elm Architecture (init/update/view)
- Agent integration via `LinkedBlockingQueue` + poll command
- Patched charm.clj JLine compat bug (`bind-from-capability!`)
- JLine smoke test catches API compat issues
- 161 tests, 561 assertions, 0 failures

### Step 6 — Statechart-driven tool calling  ✓
- `turn_statechart.clj`: per-turn statechart definition, context, events, queries
- States: idle → text-accumulating ⇄ tool-accumulating → done | error
- `executor.clj`: `make-turn-actions` bridges agent-core lifecycle → statechart events
- EQL resolver for `:psi.turn/*` attributes (state, text, tool-calls, error)
- Wired through session context via `:turn-ctx-atom`, observable from nREPL
- 18 tests, 76 assertions covering statechart + executor
- 179 tests, 637 assertions, 0 failures total

### Step 6b — Extension UI points  ✓
- `spec/ui-extension-points.allium`: spec for dialogs, widgets, status, notifications, renderers
- `psi.tui.extension-ui`: 469-line implementation in tui component
  - Promise bridge: blocking dialogs for extensions, resolved by TUI update loop
  - Dialog queue: FIFO, one active at a time
  - Widgets: keyed by `[ext-id widget-id]`, above/below editor placement
  - Status: single-line footer entries per extension
  - Notifications: auto-dismiss, overflow cap, level-based styling
  - Render registry: tool renderers + message renderers
  - UI context map: `:ui` key in extension API (nil when headless)
  - EQL resolver: 8 `:psi.ui/*` attributes (read-only introspection)
- Wired through: `extensions.clj` → `:ui` in API, `core.clj` → `:ui-state-atom` in ctx
- TUI integration: `app.clj` renders dialogs, widgets, status, notifications
- 13 tests, 104 assertions covering UI state, dialogs, queue, renderers
- 251 tests, 1070 assertions, 0 failures total

---

### Step 13 — User-doc surface split from README to `doc/` ✓ complete
- `README.md` now acts as a concise entry surface (quick start + links), while detailed user docs moved into `doc/` (`cli.md`, `tui.md`, `emacs-ui.md`, `architecture.md`, `extension-api.md`, `extensions.md`, `psi-project-config.md`).
- Built-in tools list remains in `README.md`; `app-query-tool` details moved to `doc/psi-project-config.md`.
- Architecture and roadmap now live together in `doc/architecture.md`.
- Docs path is now unified on `doc/` (not mixed `doc/` + `docs/`).
- Emacs user docs now include straight.el GitHub installation and `psi-emacs-command` customization in `doc/emacs-ui.md`; autoload stubs for `psi-emacs-start` and `psi-emacs-project` are declared in `components/emacs-ui/psi.el`.
- README references now include nucleus and link to built-in extension documentation in `doc/extensions.md`.
- Quick Start + CLI docs now standardize user-local invocation via a `~/.clojure/deps.edn` `:psi` alias (`:local/root` clone path) and command examples use `clojure -M:psi` instead of `-M:run`.

### Step 14 — OpenAI model catalog refresh (GPT-5.4) ✓ complete
- Added `:gpt-5.4` to `components/ai/src/psi/ai/models.clj` with Codex Responses transport (`:openai-codex-responses`) and ChatGPT backend base URL.
- Synced catalog metadata to current upstream values used in `~/src/pi-mono` baseline:
  - context window `272000`, max tokens `128000`
  - costs `input 2.5`, `output 15.0`, `cache-read 0.25`, `cache-write 0.0`
- Extended model-registration coverage in `components/ai/test/psi/ai/core_test.clj` to include `:gpt-5.4` in the GPT-5 Codex family assertions.
- Updated runtime docs example in `components/agent-session/src/psi/agent_session/main.clj` (`PSI_MODEL` example now includes `gpt-5.4`).
- Implementation commit: `bc67c9b`.

## Next

### Step 10 — Remember memory capture ✓ complete
- Spec: `spec/remember-capture.allium`
- Current: command exists; spec now narrowed to manual capture semantics
- Focus now: simplify runtime surface to capture/recover behavior
- Acceptance checklist:
  - [x] `/remember [text]` performs exactly one manual capture per invocation (no background/automatic capture)
  - [x] Command behavior is consistent across REPL, RPC, and Emacs surfaces
  - [x] Capture writes a minimal context snapshot (session-id, cwd, git-branch when available, summary/evidence)
  - [x] Capture is persisted via active memory provider and recoverable in later sessions
  - [x] Recovery ranking operates over memory artifacts (not over mirrored git-history summaries)
  - [x] If memory prerequisites are not ready, capture fails with explicit `memory_capture_prerequisites_not_ready`
  - [x] EQL remember telemetry attrs are stable/queryable (status, captures, last-capture-at, last-error)
  - [x] Session persistence (`/resume`) remains separate from remember memory semantics
  - [x] End-to-end tests cover manual signal → memory write → recovery visibility
  - [x] Negative tests cover blocked capture paths and unavailable provider behavior
  - [x] Store-write fallback warning contract is stable across REPL/RPC/Emacs (`⚠ Remembered with store fallback …`)
- Test matrix: `doc/remember-capture-test-matrix.md` (U/I/E coverage IDs U1–U6, I1–I8, E1–E7)

### Step 11 — Session startup prompts (global + project) ✓ complete
- Spec: `spec/session-startup-prompts.allium`
- Config sources implemented: `~/.psi/agent/startup-prompts.edn` + `.psi/startup-prompts.edn`
- Deterministic merge/order implemented with precedence `global < project`
- Startup prompts execute as visible transcript turns at new session bootstrap
- Startup telemetry attrs are top-level EQL attrs and discoverable via graph introspection
- Behavior decisions now explicit in implementation/tests:
  - prompts run sequentially as independent turns
  - new-session bootstrap runs startup prompts
  - fork resets startup telemetry and does not implicitly re-run bootstrap
- Verification:
  - `psi.agent-session.startup-prompts-test`
  - `psi.agent-session.runtime-startup-prompts-test`
  - `psi.agent-session.resolvers-startup-prompts-test`
  - (latest run: 9 tests, 35 assertions, 0 failures)

### Step 11a — Git worktree visibility (read-only) ✓ complete
- Spec: `spec/git-worktrees.allium`
- Goal: make worktree context first-class and queryable before adding mutation/switch flows
- Acceptance checklist:
  - [x] History layer exposes canonical attrs: `:git.worktree/list`, `:git.worktree/current`, `:git.worktree/count`, `:git.worktree/inside-repo?`
  - [x] Worktree parsing handles git porcelain output for main + linked worktrees, detached heads, and non-git cwd
  - [x] Agent-session root bridge exposes: `:psi.agent-session/git-worktrees`, `:psi.agent-session/git-worktree-current`, `:psi.agent-session/git-worktree-count`
  - [x] `/worktree` built-in slash command returns deterministic text output (header + summary + entries)
  - [x] `/status` includes current worktree identity (path when resolvable)
  - [x] New attrs are discoverable/queryable through session graph surfaces and covered by resolver/introspection tests
  - [x] Failure path is non-fatal: parse/command errors degrade to empty list + telemetry marker (`git.worktree.parse_failed`)
  - [x] Tests cover: inside-repo, outside-repo, linked worktree parsing, detached head parsing, command rendering, and failure telemetry
- Follow-up (deferred to Step 11b):
  - worktree mutation/switch semantics (`/worktree use`, create/remove)
  - explicit branch+path selector UX for session rebinding

### Step 11c — nREPL runtime discovery via EQL graph ✓ complete
- Spec: `spec/nrepl-discovery.allium`
- Runtime now exposes canonical attrs from session root:
  - `:psi.runtime/nrepl-host`
  - `:psi.runtime/nrepl-port`
  - `:psi.runtime/nrepl-endpoint`
- `main.clj` now stores effective nREPL runtime info in a shared runtime atom and wires it into session context creation for console/TUI/RPC modes.
- Graph discovery contract verified in resolver tests (`:psi.graph/root-queryable-attrs`, `:psi.graph/edges`).
- Live lifecycle verification added (commit `1a1c044`): `main_test` now starts/stops a real nREPL server and asserts EQL reflects effective bound port while running and nil attrs after stop.
- Verification:
  - `clojure -M:test --focus psi.agent-session.resolvers-test/nrepl-runtime-resolver-test`
  - `clojure -M:test --focus psi.agent-session.main-test/bootstrap-runtime-session-wires-nrepl-runtime-atom-test`
  - `clojure -M:test --focus psi.agent-session.main-test/nrepl-runtime-eql-reflects-live-start-stop-test`

### Step 11f — Multi-session host runtime (non-UI) ✓ complete
- Goal: one psi process hosts multiple sessions concurrently, with active-session default routing and explicit session-id targeting.
- Spec/meta elicitation completed and captured:
  - `META.md` updated with SessionHost + active-session routing semantics
  - `spec/session-core.allium` / `spec/session-management.allium` / `spec/session-forking.allium`
  - `spec/session-persistence.allium` / `spec/session-startup-prompts.allium`
- Implemented (runtime foundation):
  - in-process host registry (`components/agent-session/src/psi/agent_session/session_host.clj`)
  - core wiring (`:session-host-atom`, host upsert on session mutation, active-session pointer)
  - `ensure-session-loaded-in!` + `set-active-session-in!` in `core.clj`
  - RPC `switch_session` supports `:session-id` (in addition to legacy `:session-path`)
  - RPC added `list_sessions` op (host snapshot)
  - targetable RPC ops now accept optional `:session-id` and load/switch before execution
- Implemented (supporting convergence):
  - persistence header v4 (`:parent-session-id` + `:parent-session`), v3→v4 read migration
  - session schema/runtime now tracks `:parent-session-id`, `:parent-session-path`, `:spawn-mode`
  - startup prompt policy now spawn-aware (`:new-root` default run, spawned defaults skip)
- Remaining:
  - (closed 2026-03-13) background-job gating and api-key routing regressions re-verified:
    - `clojure -M:test --focus psi.agent-session.core-test/send-workflow-event-track-background-job-gated-test --focus psi.agent-session.rpc-test/rpc-prompt-passes-resolved-api-key-to-agent-loop-test`
    - result: 2 tests, 7 assertions, 0 failures
- Progress (2026-03-13, commit `395d036`): cross-process session persistence locking + spec-decision convergence
  - Session write path now enforces exclusive sidecar file locking (`<session-file>.lock`) in `persistence.clj` for header writes, full flushes, and append writes.
  - Lock acquisition is bounded-retry and fails explicitly with contextual `ExceptionInfo` when contention does not clear.
  - Session spec open questions were resolved and encoded as explicit decisions:
    - fork prompt default inherits parent system prompt (`spec/session-core.allium`)
    - fork isolation = soft workspace isolation, merge-back separate capability, fork budgets optional (`spec/session-forking.allium`)
    - cross-process file locking required (`spec/session-persistence.allium`)
  - Verification snapshot:
    - `clojure -M:test --focus psi.agent-session.persistence-test` → 12 tests, 75 assertions, 0 failures
    - `clojure -M:test --focus psi.agent-session.core-test` → 32 tests, 272 assertions, 0 failures
- Progress (2026-03-13): multi-session test hardening completed for routing + introspection
  - RPC coverage added in `rpc_test.clj` for `list_sessions`, `switch_session(:session-id)`, targetable `:session-id` routing, and invalid `:session-id` rejection.
  - Resolver coverage added in `resolvers_test.clj` for host-index process view + persisted session list view.
  - Graph introspection coverage added for host-index attrs (`:psi.agent-session/host-active-session-id`, `:psi.agent-session/host-session-count`, `:psi.agent-session/host-sessions`) across `:psi.graph/root-queryable-attrs`/`:psi.graph/edges` surfaces.
  - Verification snapshot:
    - `clojure -M:test --focus psi.agent-session.rpc-test` → 32 tests, 316 assertions, 0 failures
    - `clojure -M:test --focus psi.agent-session.resolvers-test` → 32 tests, 304 assertions, 0 failures
  - Contract nuance locked by tests: `:psi.agent-session/host-sessions` is a join attr and should be validated via graph edges (it is not guaranteed to appear as a scalar in `:psi.graph/root-queryable-attrs`).
  - PSL follow-up for `fc1aa93` converged docs + tests so this slice is now explicitly tracked in plan memory.
- Progress (2026-03-13, commit `b1fef75`): session-work sweep committed as one converged delta (runtime + specs + tests + UI)
  - Added `session_host.clj` and wired host registry/routing through session core, resolvers, and RPC surfaces.
  - Added/updated Allium contracts for session core/forking/management/persistence/startup and UI interaction surfaces.
  - Stabilized host/session tests by persisting switch targets explicitly in fixtures where lazy flush would otherwise make session-id resume nondeterministic.
  - Regression suites are green for the session slice:
    - `core-test`, `persistence-test`, `rpc-test`, `resolvers-test`, `runtime-startup-prompts-test`, `startup-prompts-test`
    - aggregate verification run: 116 tests, 1000 assertions, 0 failures.
- Progress (2026-03-13, commit `b3517dd`): route-lock enforcement and graph resolver surface hardening
  - `with-target-session!` extended with optional route-lock guard (`enforce-session-route-lock?`); conflicts surface structured error payload with inflight/target session ids.
  - `run-prompt-async!` acquires/releases `:session-route-lock` scoped to request-id + target session.
  - `session-resolver-surface` extracted in `resolvers.clj` so graph introspection uses a stable locally-composed set independent of global registry state.
- Progress (2026-03-13, commit `62f46cd`): multi-session UI spec landed
  - `session-management.allium`: `SessionHostSnapshot` + `SessionSlot` values; `host/updated` event rules (on subscribe + host state change); slot active/streaming projection rules; guidance in `AgentSessionApi` surface.
  - `emacs-frontend.allium`: session tree widget rules (`psi-session/session-tree`, placement `left`); display name fallback rules; parent-child indent rule; `/tree` completing-read picker rules; switch dispatch + connecting affordances + transcript rehydrate rules; `EmacsFrontendSessionTreeApi` surface.
- Progress (2026-03-13, commit `16ce8ed`): multi-session UI implementation complete
  - Backend: `host/updated` added to event-topics + required-payload-keys; `host-updated-payload` builds `SessionHostSnapshot` with per-slot `{id name is-streaming is-active parent-session-id}`; emitted on subscribe, `new_session`, and both `switch_session` branches.
  - Emacs (`psi-events.el`): `psi-emacs--session-display-name` (name or id-prefix fallback); `psi-emacs--session-tree-widget-lines` (active marker, streaming badge, parent-child indent, `/tree <id>` actions); `psi-emacs--handle-host-updated-event` injects `psi-session/session-tree` widget (hidden when ≤1 session); `host/updated` wired into `handle-rpc-event`.
  - Emacs (`psi-session-commands.el`): `psi-emacs--request-switch-session-by-id` (`:session-id` path); `psi-emacs--handle-idle-tree-command` (completing-read picker from host snapshot); `/tree <id>` direct dispatch for widget click; `/tree` wired into idle slash handler and help text.
  - Emacs (`psi-completion.el`): `/tree` added to slash CAPF specs.
  - Emacs (`psi.el`): `host-snapshot` slot added to `psi-emacs-state`.
  - Tests: 12 new ERT tests; 204/204 passing.
- Progress (2026-03-13, commit `7ab1277`): fork gap closed
  - `fork` RPC op now emits `host/updated` after `fork-session-in!`; session tree widget reflects forked session immediately.
  - Subagent creation intentionally excluded — subagents use isolated contexts and are not host peers.
  - Three new rpc_test assertions: subscribe, fork, and new_session `host/updated` coverage.
- Progress (2026-03-13, commit `92fc518`): TUI multi-session surface landed (`/tree` + host-backed picker)
  - Commands: `/tree` added to central dispatch with runtime gating (`:supports-session-tree?`), direct switch via session id/prefix (`:tree-switch`), and consistent no-op/error messaging.
  - TUI: selector now supports `:session-selector-mode` (`:resume|:tree`), host-backed initialization from `:psi.agent-session/host-sessions` + `:psi.agent-session/host-active-session-id`, tree-aware rendering (active highlight, parent indent, streaming badge, session-id suffix), and mode-specific key hints/Tab behavior.
  - Runtime wiring: TUI `run-tui-session` now passes `switch-session-fn!` (calls `session/ensure-session-loaded-in!` + rehydrate), plus `:supports-session-tree? true`; console/RPC explicitly pass `:supports-session-tree? false` for deterministic fallback text.
  - Slash UX: `/tree` included in builtin slash candidates and help text.
  - Verification snapshot:
    - `clojure -M:test --focus psi.agent-session.commands-test --focus psi.tui.app-test` → 115 tests, 338 assertions, 0 failures
    - `clojure -M:test --focus psi.agent-session.main-test --focus psi.agent-session.rpc-test` → 51 tests, 398 assertions, 0 failures
- Progress (2026-03-13, commit `3c1c385`): TUI `/tree` hierarchy + alignment polish complete
  - Row model now computes explicit tree order from host lineage (stable siblings; cycle/duplicate guard) instead of flat insertion order.
  - Tree iconography added: root `●`, branch connectors `├─` / `└─`, and carry-through `│` for nested sibling context.
  - Right-side status cells are fixed-slot aligned (`[active] [stream]`) with consistent session-id suffix column alignment.
  - Verification snapshot:
    - `clojure -M:test --focus psi.tui.app-test` → 77 tests, 210 assertions, 0 failures
- Progress (2026-03-13, commit `115c6ab`): multi-session route-lock isolation tightened for exclusive session-lifecycle ops
  - Added explicit exclusive route-lock op class in RPC (`new_session`, `switch_session`, `fork`) so they fail fast with `request/session-routing-conflict` when a prompt route-lock is active, even when targeting the same session.
  - Route-lock guard refactored (`valid-target-session-id!`, `maybe-assert-route-lock!`) and reused across targetable dispatch + exclusive-op gate path.
  - Added RPC regression: `exclusive ops are rejected while prompt is in-flight when lock enforcement is enabled`.
  - Verification snapshot:
    - `clojure -M:test --focus psi.agent-session.rpc-test` → 35 tests, 336 assertions, 0 failures
    - `clojure -M:test --focus psi.agent-session.core-test/send-workflow-event-track-background-job-gated-test --focus psi.agent-session.rpc-test/rpc-prompt-passes-resolved-api-key-to-agent-loop-test` → 2 tests, 7 assertions, 0 failures
- Progress (2026-03-13, commit `d869843`): changelog memory synced for `/tree` rollout
  - Added explicit `CHANGELOG.md` entry documenting command semantics (`/tree`, `/tree <id|prefix>`), runtime gating (`supports-session-tree?`), TUI host-backed selector mode, switch callback wiring, and validation snapshot.
  - Keeps internal plan/state narrative aligned with user-facing release memory for the same feature delta.
- Progress (2026-03-13, commit `1613f5f`): TUI tmux integration harness baseline added for live-surface verification
  - Added reusable tmux harness utilities (`components/tui/test/psi/tui/test_harness/tmux.clj`) and one detached-session end-to-end scenario test (`components/tui/test/psi/tui/tmux_integration_harness_test.clj`).
  - Baseline scenario verifies boot readiness marker, `/help` marker, and `/quit` clean process exit with pane snapshot capture on failure.
  - Added contract spec `spec/tui-tmux-integration-harness.allium` and user doc entry in `doc/tui.md`.
  - Scope is verification infrastructure for TUI interaction boundaries; it does not change multi-session routing semantics.
- Remaining (multi-session UI):
  - optional follow-up: add richer collapsed/expandable subtree interactions in TUI row model (current model is fully expanded tree-only)

### Step 12 — Emacs UI ◇ in progress
- Spec: `spec/emacs-frontend.allium`
- Current: rpc-edn frontend and core interaction model implemented
- Recent stabilization: OpenAI chat-completions reasoning stream restored (`:thinking-delta` visibility + `reasoning_effort` request forwarding, commit `4c20882`) and stream-to-TUI thinking visibility parity landed (`fbbb173`).
- New follow-up: divider/separator length mismatch reported in Emacs UI transcript/projection area; commit `3e02b97` adjusted TUI separators but did not address the user-reported Emacs path.
- Progress: commit `db9d4c7` added resize-driven refresh hooks for width-sensitive separators in Emacs UI.
- Progress: commit `d36fe3d` switched projection width to prefer `window-text-width` and made resize handler run input-area repair (`psi-emacs--ensure-input-area`).
- Progress: commit `bfa604a` added `psi-emacs-project` for current-project startup with project-scoped buffer naming (`*psi:<project>*`) plus explicit prefix behavior (`C-u` fresh generated name, `C-u N` slot selection).
- Progress: Emacs docs/tests now cover project command behavior (`README.md`, `components/emacs-ui/README.md`, and new ERT coverage in `components/emacs-ui/test/psi-test.el`).
- Active fix focus: user reported submit-cycle separator disappearance in Emacs input area; commit `c649a68` now enforces post-submit input-area repair (`psi-emacs--ensure-input-area`) and adds a focused regression test (`psi-send-repairs-missing-input-separator-after-submit`).
- Progress: commit `854e419` hardens submit transcript semantics and frontend loadability — fixed `psi-compose.el` unmatched-paren parse failure in `psi-emacs-interrupt`, made `psi-emacs--default-send-request` return strict dispatch-confirmed boolean (non-empty request id), and added regression `psi-send-does-not-copy-input-when-dispatch-not-confirmed` so user prompt echo only happens on confirmed dispatch.
- Progress: commit `6e39269` fixes stale-anchor first-send contamination in Emacs compose flow — input start now prefers first editable position after separator line (`psi-emacs--input-separator-draft-start-position`), and regression `psi-send-omits-input-separator-from-first-prompt-when-anchor-drifts` locks behavior.
- Verification: `bb emacs:test` → 186/186 passing after compose follow-up updates (`psi-send-omits-input-separator-from-first-prompt-when-anchor-drifts` included).
- Progress: commit `a533fa8` fixes `/new` clear/replay UX gap where input area + footer could disappear until later events; `/new` success path now re-seeds `connecting...` footer and re-focuses the compose input immediately after reset before `get_messages` replay.
- Progress: commit `dd99d2e` applies the same rehydrate UX guard to `/resume` success path; `switch_session` now reseeds `connecting...` and refocuses compose input immediately after reset and before `get_messages` replay.
- Progress: commit `1421b46` unifies startup + `/new` + `/resume` connecting affordance behavior via shared helper `psi-emacs--show-connecting-affordances` (seed footer + focus input), reducing duplicated rehydrate/UI-gap repair code.
- Verification: `bb emacs:test` → 188/188 passing, including `/new` regression `psi-idle-new-slash-restores-input-area-and-footer-after-reset` and strengthened `/resume` success-path assertions.
- Progress: commit `b652c1f` smooths startup UX before handshake completes — Emacs now seeds a deterministic `connecting...` footer placeholder, keeps projection upserts read-only-safe, and preserves immediate cursor focus in the dedicated input area (`psi-emacs--focus-input-area` sets both buffer point and window point).
- Progress: startup hydration now keeps point in input (`psi-initial-transcript-hydration-keeps-point-in-input-area-when-messages`) instead of jumping to transcript top; startup focus behavior is covered by new pre-handshake tests.
- Progress: commit `b0f83c3` adds a deterministic `ψ` banner at transcript start for both initial startup and `/new` reset path (rendered before replayed transcript/messages), tightens startup focus by preferring the explicit `pop-to-buffer` window target, and fixes a backend startup compile break by aligning agent-session mutation registration (`all-mutations` now references `interrupt`, not stale `abort`).
- Progress: commit `b13c4f8` updates Allium contracts to match shipped startup/interrupt behavior: `spec/emacs-frontend.allium` now models banner-first transcript + input-area cursor invariants (startup and `/new` reset), and `spec/session-management.allium` now explicitly models `prompt_while_streaming` steer/queue semantics including interrupt-pending steer→follow-up coercion.
- Progress: commit `f92dc0f` verified interrupt behavior convergence between `spec/agent-turn-interrupts.allium`, the agent-session runtime, and Emacs frontend surfaces (deferred interrupt, pending-state projection, steering-drop/follow-up preservation, and turn-boundary apply/reset semantics).
- Progress: commit `0c6667f` fixed Emacs startup `*lsp-log*` read-only regression by removing buffer-local `inhibit-read-only` from `psi-emacs-mode`, localizing transcript mutations behind explicit `let ((inhibit-read-only t))` boundaries, tightening separator marker validity checks, and updating ERT transcript-clearing tests for read-only semantics.
- Progress: commit `7b63628` fixed `psi-emacs-project` re-running all prompts when switching to an existing buffer — `psi-emacs-open-buffer` now only activates modes on a fresh buffer (`unless derived-mode-p`), making the function idempotent and preventing buffer-local state wipe on re-entry.
- Progress: commit `098cead` fixed "Text is read-only" error on buffer switch — `psi-emacs--refresh-input-separator-line` now binds `inhibit-read-only` around its `delete-region`/`insert` pair, matching all other internal transcript mutation sites.
- Progress: commit `d90880d` adds close confirmation for live psi buffers by installing `psi-emacs--confirm-kill-buffer-p` on `kill-buffer-query-functions`; declining keeps buffer/process alive, and noninteractive test runs bypass prompts. Regression tests added for decline + noninteractive behavior.
- Progress: commit `505380f` hardens `psi-mode` keymap lifecycle by extracting `psi-emacs--install-mode-keybindings` and reinstalling bindings on each mode activation to self-heal stale keymap mutations in long-lived Emacs sessions; adds focused ERT coverage `psi-interrupt-keybinding-is-installed` for `C-c C-c` interrupt wiring.
- Progress: commit `3cc8a76` made runtime UI surface introspectable (`:psi.agent-session/ui-type`) and exposed `:ui-type` on extension API; extension widgets now branch placement by UI (`:console|:tui|:emacs`) in `subagent_widget`, `agent-chain`, and `mcp_tasks_run`.
- Progress: commit `f23e38f` expands Emacs slash CAPF built-in candidates to include common backend/server commands (`/history`, `/prompts`, `/skills`, `/login`, `/logout`, `/remember`, `/skill:`), adds focused completion regression coverage, and updates `components/emacs-ui/README.md` to document the broader slash surface.
- PSL follow-up: commit `50c9d59` syncs user-facing docs in `doc/emacs-ui.md` with the same slash/backend completion behavior so top-level docs match component-level frontend docs.
- Verification: `bb emacs:test` green at 192/192 after slash-candidate expansion follow-up.
- Progress: commit `cdfadda` fixes Emacs session-tree widget action routing so projected `/tree <id>` commands follow idle slash interception (`psi-emacs--dispatch-idle-compose-message`) instead of raw `prompt` dispatch; this restores `switch_session` behavior and avoids TUI-only `/tree` fallback text on widget click/RET.
- Verification: `bb emacs:test` green at 205/205 with new regression `psi-projection-tree-widget-action-uses-idle-slash-routing`.
- Completed in this cycle:
  - [x] Prompt completion architecture added via CAPF (`components/emacs-ui/psi-completion.el`)
  - [x] `/` completion (slash commands) + `@` completion (file references)
  - [x] Category metadata (`psi_prompt`, `psi_reference`) + annotation/affixation/exit hooks
  - [x] Reference search roots include cwd + project root (when distinct)
  - [x] Completion policy knobs exposed via defcustoms (limits, match style, hidden/excluded paths)
  - [x] Completion behavior documented in `components/emacs-ui/README.md`
  - [x] ERT coverage added for completion and policy toggles
  - [x] Extension slash command completion now merges backend command names (`:psi.extension/command-names`) with built-in CAPF command specs (commit `8d36927`)
- Definition of done:
  - Startup hydration + `/new` + reconnect flows are stable and tested
  - Tool output rendering modes and theme-aware faces are stable
  - Interactive command parity with RPC loop is documented and verified
  - dedicated input area (persistent cursor focus while streaming + multiline compose + history traversal) is stable and tested
  - `bb emacs:test` and `bb emacs:byte-compile` remain green

### Step 12b — Background tool jobs (spec + tests) ✓ complete
- Spec: `spec/background-tool-jobs.allium`
- Test matrix: `doc/background-tool-jobs-test-matrix.md`
- Progress: commit `b7ac6f4` landed in-memory background job runtime + terminal injection plumbing
  - `components/agent-session/src/psi/agent_session/background_jobs.clj`
  - `components/agent-session/src/psi/agent_session/core.clj`
  - focused contract tests in `background_jobs_test.clj` + integration coverage in `core_test.clj`
- Progress: commit `6f321af` completed remaining matrix TODO slice (`N7`, `E7`, `E10`, `E13`, `B3`) and wired explicit list/inspect/cancel surfaces across RPC/TUI/Emacs for parity.
- Progress: commit `8185869` added session-root background-job EQL attrs (`:psi.agent-session/background-job-count`, `:psi.agent-session/background-job-statuses`, `:psi.agent-session/background-jobs`) plus nested `:psi.background-job/*` entities and graph-introspection coverage tests.
- Regression fix: commit `0064213` — `psi.extension/send-message` mutation (used by agent-chain `emit-chain-result!`) now triggers workflow-job terminal detection; background jobs were stuck `:running` until next user prompt.
- Regression hardening: commit `95f70b1` adds a delayed terminal re-check in `send-message` to handle async workflow completion races where workflow `:done` lands just after message injection.
- Regression hardening: commit `4a1eb5a` adds read-time self-healing reconciliation for workflow-backed background jobs on `/jobs` list + inspect and `:psi.agent-session/background-jobs` resolver output, normalizing stale `:running` entries when linked workflows are already terminal.
- Acceptance checklist:
  - [x] Dual-mode tool behavior is implemented (`sync` result vs `background` start with `job-id`)
  - [x] `job-id` is returned only for async/background starts
  - [x] Background jobs are tracked in memory only (no restart recovery)
  - [x] Thread-scoped list/inspect/cancel behavior is enforced
  - [x] Terminal outcomes inject exactly one synthetic assistant message per job
  - [x] Injection occurs at turn boundaries and in completion-time order
  - [x] Delivery semantics are at-most-once under concurrent emit attempts
  - [x] Oversized terminal payloads spill to temp file with message reference
  - [x] Default `list jobs` returns non-terminal statuses only
  - [x] Manual retry is rejected with canonical error
  - [x] Retention is bounded to 20 terminal jobs/thread with oldest-terminal eviction
  - [x] Cross-surface visibility parity holds (REPL/TUI/Emacs/RPC)
  - [x] Session-root EQL background-job introspection attrs are available and graph-discoverable (`:psi.agent-session/background-job-count`, `:psi.agent-session/background-job-statuses`, `:psi.agent-session/background-jobs`, nested `:psi.background-job/*`)

### Step 13 — Terminal UI (TUI) ◇ in progress
- Spec: `spec/tui.allium`
- Current: charm.clj/JLine3 session loop is operational
- Progress: commit `18e0c50` fixed agent-chain `run_chain` progress heartbeat + widget projection (tracked run state, throttled tool updates, and deterministic widget refresh on init/reload/session switch).
- Progress: commit `53c0f40` removed implicit default chain activation; widget now stays `active: (none)` until explicit chain selection (init/reload/session-switch parity).
- Progress: commit `8d36927` made `run_chain` non-blocking by default (background workflow start), with explicit opt-in `wait` flag for synchronous completion.
- Progress: commit `11feddf` keeps interactive `run_chain` tool calls non-blocking even when `wait=true` is requested, preserving UI responsiveness while workflow execution continues in background.
- Progress: commit `8d36927` added graph-discoverable agent-chain config attrs (`:psi.agent-chain/config-path`, `:psi.agent-chain/count`, `:psi.agent-chain/names`, `:psi.agent-chain/chains`, `:psi.agent-chain/error`).
- Progress: commit `4ffaa11` decodes Anthropic 400/error response bodies from GZIP+JSON, surfacing the real API error message rather than opaque ExceptionInfo; aids diagnosis of chain run failures (e.g. invalid model IDs).
- Progress: commit `26fedd9` hardens `tool_use.input` to always be a JSON dict: `parse-args` validates parsed type; `transform-messages` guards at wire layer. Fixes 400 `tool_use.input: Input should be a valid dictionary` from non-map tool argument JSON.
- Progress: commit `81af559` delivers chain results back to chat — `emit-chain-result!` sends final output as an assistant message (`custom-type: "chain-result"`) on done/error; model resolution falls back session model → `:sonnet-4.6`; step errors now catch `Throwable`.
- Progress: commit `e2fe3ed` adds a generic extension prompt-contribution layer to agent-session (register/update/unregister/list), deterministic contribution ordering, system-prompt recomposition (`base-system-prompt` + contributions), and EQL introspection attrs for contribution state.
- Progress: commit `e2fe3ed` wires subagent-widget to publish a compact capability contribution so the assistant can discover/invoke subagent tools directly from system prompt context.
- Progress: commit `76a07e5` upgrades subagent profile routing: `/sub [@agent] <task>` and `subagent(action="create", task, agent?)` now accept optional agent profiles from `.psi/agents/*.md`, reject unknown profiles early, surface active `@agent` in workflow/status output, and publish agent descriptions in the subagent prompt contribution.
- Progress: commit `9f55a9f` adds dual-mode subagent create execution (`mode=async|sync`, default async) with strict validation (`mode` allowed only for `action=create`), sync terminal return semantics (workflow id preserved, no job id), optional `timeout_ms` for sync waits, and async job-id surfacing in tool/command output.
- Progress: commit `e224736` adds optional subagent conversation forking on create: `fork_session=true|false` (default false), validation (`fork_session` must be boolean and create-only), and fork-tag visibility in widget/list output.
- Progress: commit `e224736` extends slash UX to `/sub [--fork|-f] [@agent] <task>` and keeps tool parity by forwarding the same fork flag semantics through workflow create input.
- Progress: commit `bd58191` adds optional parent-context reinjection for subagent terminal output: `include_result_in_context=true|false` on `action=create|continue`; when enabled, subagent completion injects a deterministic `user` job-id marker + `assistant` result pair with alternation guard.
- Progress: commit `578a2a7` aligns subagent-widget with agent-chain clickable removal UX in Emacs projection: terminal subagent rows now emit structured widget lines with command action (`/subrm <id>`) and display `✕ remove`; extension coverage added for clickable action emission.
- PSL follow-up (commit `578a2a7`): remove affordance is now explicitly standardized as command-backed widget action text (not bespoke per-row UI controls), preserving projection parity with existing `/subrm` lifecycle semantics.
- Progress: commit `9f55a9f` also extends workflow mutation job tracking to support both `psi.extension.workflow/create` and opt-in `psi.extension.workflow/send-event` via `track-background-job?`, returning `:psi.extension.background-job/id` for `/jobs` + `/job` visibility parity.
- Progress: commit `032d3f2` now tracks `.psi/agents/*` in git (including `agent-chain.edn` and core agent profiles), making chain/profile behavior reproducible across clones and no longer dependent on local `.git/info/exclude` state.
- Progress: commit `00bd634` eliminates the active-chain selection concept entirely — `run_chain` is replaced by `agent-chain(action, chain, task)`. The tool resolves the chain by name at call time; no prior `/chain` selection step is required. `action="list"` and `action="reload"` consolidate the former `/chain-list` and slash-based reload into the tool API. `/chain` and `/chain-reload` remain as human-facing aliases. Widget `active:` display removed.
- Progress: commit `27efd76` adds prompt contribution for agent-chain: chain catalog (name + description) advertised in system prompt under `Extension Capabilities`; synced on init, reload, and session_switch so the model always has current chain inventory without a prior `action="list"` call.
- Progress: commit `050e29d` canonically enforces kebab-case tool names at extension registration (`^[a-z0-9][a-z0-9-]*$`), adds `spec/tools/tool-naming.allium`, and renames extension tool identifiers from underscore style to kebab-case (`agent-chain`, `hello-upper`, `hello-wrap`).
- ~~Follow-up queued: align `/chain` command UX with user intent by supporting both index and name selection (`/chain <number|name>`), while keeping no-default-active semantics.~~
- Progress: commit `93a517e` adds explicit delivery-specific PSL transcript status messaging (`queued via deferred; will auto-run when idle` / `queued via prompt` / `queued via follow-up`) with extension tests and spec updates.
- Progress: commit `2f2cf5a` reworks PSL execution path to run follow-up work through `subagent(action=create, mode=sync, fork_session=true)` via tool-plan chain, so PSL updates/commits execute in a forked subagent context rather than direct extension prompt delivery.
- Progress: commit `51b9192` hardens PSL delivery-status fallback formatting: unknown/non-keyword delivery values now render safely via `str` coercion instead of `name`, preventing runtime type errors in status-message emission.
- Progress: commit `a3c9756` hardened extension isolation during bootstrap — `send-extension-message-in!` now guards history append behind `startup-bootstrap-completed?`; PSL startup noise message removed.
- Progress: commit `c9af5f0` fixed TUI footer token/context display — Anthropic SSE provider was hardcoding usage to zeros; now reads real `input_tokens`/`output_tokens`/cache tokens from `message_start` and `message_delta` events. Footer now shows actual context fraction (e.g. `12.3%/200k`) instead of `?/0`.
- Progress: commit `bd45ada` fixed footer usage aggregation boundaries in `session-usage-totals`: usage now remains session-scoped while still counting legacy journal rows with missing `:session-id`, preventing prior-session token carryover after `new_session`.
- Progress: commit `3600486` tightened agent-chain/operator UX: async chain start response is now compact and machine-friendly (`OK id:<run-id>`), with monitor guidance removed from tool output; lambda/prompt compiler agent definitions were also refined for stricter minimal output contracts.
- Progress: commit `c353189` clarifies `agent-chain` tool contract text for async runs: `action="run"` language now says "start" (not "execute"), and description explicitly states "Do not poll unless explicitly asked to." to discourage default status polling loops.
- Progress: commit `2629a73` normalizes runtime model resolution in `agent-chain`: provider aliases/strings are canonicalized, `:id` and `:model-id` are both accepted, fallback can resolve from `:psi.agent-session/model-provider` + `:psi.agent-session/model-id`, and unresolved cases warn before defaulting to `:sonnet-4.6`.
- Progress: commit `8bc8eda` adds chain-run removal controls to `agent-chain`: new tool action `action="remove"` (with `id`) and slash command `/chain-rm <run-id>` remove workflow runs and refresh widget state; extension tests now cover both tool and command removal paths.
- Progress: commit `56d5a78` enforces final-stage-only parent output for `agent-chain`: removed `on-update` progress streaming and per-step console prints, final delivery is a normal assistant message (no custom-type marker), and workflow create now sets `:track-background-job? false` to prevent background-job terminal injections from surfacing intermediate chain plumbing in parent transcript.
- Progress: commit `8a48c7a` adds explicit bb task entrypoints for Clojure test scopes (`clojure:test:unit`, `clojure:test:extensions`) plus composed `clojure:test`, reducing command-discovery friction for unit/extension verification.
- Definition of done:
  - Per-token streaming render is available (not spinner-only)
  - Tool execution status is visible during active turns
  - Extension UI ordering/theming decisions are finalized and documented
  - TUI regressions are covered by tests/smoke checks

### Step 14 — HTTP API ‖ deferred
- openapi spec + martian client, surface via Pathom mutations
- Deferred until memory + recursion stabilization is complete

### Spec track — Allium contract hardening ◇ in progress
- Current status:
  - `allium check spec` passes (49 files, 0 issues)
  - Remaining top-level legacy specs migrated to Allium v2 syntax (`spec/bootstrap-system.allium`, `spec/agent.allium`, `spec/tui.allium`; commit `29dcb18`)
  - Dependency ordering pass complete (`no-use` roots → dependents)
  - Spec drift guard workflow is now documented in `README.md` (`bb spec:ci`, `bb spec:check`, `bb spec:check:full`, `bb spec:guard`, `bb spec:baseline`) with baseline file path and declaration-name guard scope.
- Bash tool contract convergence landed (`ad44cb3`): `spec/tools/bash.allium` now matches runtime behavior (`overrides`, `on_update`, `tool_call_id`, timeout default, final-result update semantics, and boolean abort contract).
- Read tool contract convergence landed (`a88335f`): `spec/tools/read.allium` now matches runtime behavior (content-block image returns, absolute-path missing-file/binary messaging, `overrides`-driven output policy, and implemented offset/limit guidance text).
- Write/edit tool contract convergence landed (`f5ba5cd`): `spec/tools/write.allium` and `spec/tools/edit.allium` now match runtime behavior (path expansion before cwd resolution, resolved-path success/error messaging, and exact-first + fuzzy-window edit replacement semantics).
- Edit spec follow-up convergence landed (`b642b54`): `spec/tools/edit.allium` now aligns with current tests for non-null `first_changed_line` metadata (`>= 1`) and no-cwd absolute-path handling, while preserving repository snake_case spec field conventions (`old_text`, `new_text`).
- Prompt-guidance formalization landed (`5504fcf`): `AGENTS.md` now encodes explicit allium-spec/test convergence equations (`refactor_minimal_semantics_spec_tests`, `tests_musta_cover_allium_spec_behaviour`) and renames iterative refinement primitives to `allium_spec` terminology for clearer spec/runtime alignment.
- app-query-tool contract convergence landed (`364475a`): `spec/tools/app-query-tool.allium` now matches runtime behavior (factory schema metadata parity, `overrides` + `tool_call_id` options, truncation details only when truncated, and direct-dispatch exclusion).
- OpenAI provider contract distillation landed (`6e39269`): `spec/openai-provider.allium` now captures provider dispatch, request/auth requirements, SSE normalization, tool-call streaming edge cases, and request/reply capture callbacks. Runtime is now aligned via request/reply capture hooks in `openai.clj` + executor callback chaining/bounded capture persistence; next slice should add `traces:` coverage and tighten canonical reason mapping against `ai-abstract-model.allium` if needed.
- OpenAI provider thinking pipeline distillation landed (`905aac5`): 9 new rules added covering completions/codex delta extraction, statechart-bypass progress routing, RPC wire translation, TUI accumulation, and Emacs live-region/archive/clear lifecycle. Pre-existing string-keyed map literal parse errors in `CompletionsRequestBuilt`, `CodexRequestBuilt`, and `thinking_level_to_effort` config resolved by moving to `@guidance`.
- Anthropic provider spec distilled and verified (`f8523cb`): `spec/anthropic-provider.allium` covers tool ID normalisation, message transformation (user/assistant/tool-result, consecutive-merge), OAuth vs API-key auth, extended thinking (level→budget, beta headers, temperature exclusion), SSE event normalisation, usage accumulation, URL construction, and HTTP error extraction. 6 gaps corrected vs code/tests during verification pass.
- Emacs thinking marker leak fixed (`62cdd65`): drift branch in `psi-emacs--assistant-before-tool-event` now properly detaches markers before clearing `thinking-range`; multi-delta regression test added.
- `META.md` created and `AGENTS.md` spec lambdas tightened (`15f7b3b`): `λspec. language(spec) = Allium` axiom added; `allium_spec` → `spec` throughout equations; four-artifact agreement invariant `λ(model, spec, tests, code)` supersedes tri-artifact form; `META.md` added to files section.
- Remaining work:
  - Recover richer behavioral detail where specs were intentionally simplified during parser migration (session/rpc/tui/emacs/memory flows)
  - Align event vocabulary end-to-end (command vs emitted event pairs) and keep surface `provides` lists strictly synchronized with rule triggers
  - Normalize naming/style consistency across all specs (snake_case fields, deterministic event names, guidance coverage)
  - Add a CI guard for spec validity (`allium check spec`) and a drift check for cross-spec referenced operations
  - Triage and resolve open questions; promote settled answers into normative rules
  - Add focused acceptance matrices for critical contracts (rpc-edn, session-management, tools/runtime, tui/emacs boundary)

### AI COMPLETE
- System-level milestone reached after Steps 10–14 are complete and stable


### Deferred (agent-session)
- `TreeNavigated` branch tree navigation
- Session persistence to disk (journal currently in-memory only)
  - Spec: `spec/session-persistence.allium` ✓
  - `persistence.clj`: NDEDN write (lazy flush, bulk + append), read, migration, listing ✓
  - `core.clj`: `resume-session-in!` loads from disk, `new-session-in!` creates file ✓
  - Session directory layout + discovery + listing ✓
  - 70 new assertions, 286 total, 0 failures ✓
- `SkillCommandExpanded` / `PromptTemplateExpanded` events
- Streaming token printing during TUI session
- Extension UI: dialog timeouts, widget ordering, theme maps for renderers,
  editor text injection, working message override (see spec open questions)
- Extension UI: screen takeover / custom component injection

- Δ psl source=53082d2cb72c2dbd354c790256f1e48b5663f717 at=2026-03-06T20:57:20.413334Z :: ⚒ Δ Simplify PSL to agent-prompt flow with extension prompt telemetry λ
- Δ psl source=93632bc at=2026-03-08T12:19:31Z :: ⚒ Mark `psi-emacs-command` safe for `.dir-locals.el` local customization λ
- Δ psl source=3786e39 at=2026-03-10T16:35:00Z :: ⚒ · Emit footer/updated after each tool/result in progress poll loops λ
- Δ psl source=af2282f at=2026-03-10T23:58:00-04:00 :: ⚒ λ Add global code→spec invariant equation to AGENTS.md
- Δ psl source=c98e310 at=2026-03-12T23:18:00-04:00 :: ⚒ λ Add tri-artifact agreement invariant to AGENTS.md
- Δ psl source=f8523cb at=2026-03-12T23:18:00-04:00 :: ⊨ λ Distil anthropic-provider.allium spec from code and tests
