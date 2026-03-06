# Plan

Ordered steps toward PSI COMPLETE.

---

## Done

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

### Step 11 — Session startup prompts (global + project) ◇ in progress
- Spec: `spec/session-startup-prompts.allium`
- Add configurable startup prompts loaded from `~/.psi/agent/startup-prompts.edn` and `.psi/startup-prompts.edn`
- Deterministic merge/order with precedence `global < project`
- Execute prompts at new session start as visible transcript turns (startup-tagged user msgs + agent responses)
- No session override source; no token budget guard
- Expose startup telemetry attrs on EQL and ensure discoverability via `:psi.graph/*` introspection attrs
- Definition of done:
  - Global/project prompt sets merge deterministically with tested ordering and enable rules
  - Startup prompts execute exactly once on new session start and remain visible in transcript/UI
  - Startup prompt telemetry attrs are top-level, queryable, and appear in graph introspection surfaces
  - Fork/new-session behavior is explicit and covered by tests

### Step 11a — Git worktree visibility (read-only) ◇ planned
- Spec: `spec/git-worktrees.allium`
- Goal: make worktree context first-class and queryable before adding mutation/switch flows
- Acceptance checklist:
  - [ ] History layer exposes canonical attrs: `:git.worktree/list`, `:git.worktree/current`, `:git.worktree/count`, `:git.worktree/inside-repo?`
  - [ ] Worktree parsing handles git porcelain output for main + linked worktrees, detached heads, and non-git cwd
  - [ ] Agent-session root bridge exposes: `:psi.agent-session/git-worktrees`, `:psi.agent-session/git-worktree-current`, `:psi.agent-session/git-worktree-count`
  - [ ] `/worktree` built-in slash command returns deterministic text output (header + summary + entries)
  - [ ] `/status` includes current worktree identity (path + branch/detached state)
  - [ ] New attrs are discoverable through graph introspection surfaces (`:psi.graph/nodes`, `:psi.graph/edges`, resolver symbols)
  - [ ] Failure path is non-fatal: parse/command errors degrade to empty list + telemetry marker
  - [ ] Tests cover: inside-repo, outside-repo, linked worktree listing, detached head, and command rendering

### Step 12 — Emacs UI ◇ in progress
- Spec: `spec/emacs-frontend.allium`
- Current: rpc-edn frontend and core interaction model implemented
- Definition of done:
  - Startup hydration + `/new` + reconnect flows are stable and tested
  - Tool output rendering modes and theme-aware faces are stable
  - Interactive command parity with RPC loop is documented and verified
  - `bb emacs:test` and `bb emacs:byte-compile` remain green

### Step 13 — Terminal UI (TUI) ◇ in progress
- Spec: `spec/tui.allium`
- Current: charm.clj/JLine3 session loop is operational
- Definition of done:
  - Per-token streaming render is available (not spinner-only)
  - Tool execution status is visible during active turns
  - Extension UI ordering/theming decisions are finalized and documented
  - TUI regressions are covered by tests/smoke checks

### Step 14 — HTTP API ‖ deferred
- openapi spec + martian client, surface via Pathom mutations
- Deferred until memory + recursion stabilization is complete

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
