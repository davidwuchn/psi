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
- Progress: commit `0c6667f` fixed Emacs startup `*lsp-log*` read-only regression by removing buffer-local `inhibit-read-only` from `psi-emacs-mode`, localizing transcript mutations behind explicit `let ((inhibit-read-only t))` boundaries, tightening separator marker validity checks, and updating ERT transcript-clearing tests for read-only semantics.
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
- Follow-up queued: align `/chain` command UX with user intent by supporting both index and name selection (`/chain <number|name>`), while keeping no-default-active semantics.
- Follow-up queued: add explicit extension transcript status for deferred PSL prompt delivery (`queued via deferred; will auto-run when idle`) so operator feedback distinguishes queued-follow-up from auto-deferred execution.
- Progress: commit `a3c9756` hardened extension isolation during bootstrap — `send-extension-message-in!` now guards history append behind `startup-bootstrap-completed?`; PSL startup noise message removed.
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

- Δ psl source=53082d2cb72c2dbd354c790256f1e48b5663f717 at=2026-03-06T20:57:20.413334Z :: ⚒ Δ Simplify PSL to agent-prompt flow with extension prompt telemetry λ
- Δ psl source=93632bc at=2026-03-08T12:19:31Z :: ⚒ Mark `psi-emacs-command` safe for `.dir-locals.el` local customization λ
