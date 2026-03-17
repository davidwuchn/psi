# Plan

Ordered steps toward PSI COMPLETE.

---

## Done

### Step 15kc — Emacs Anthropic `/new` rehydrate flow now rides the canonical RPC message shape end-to-end ✓ complete
- Commit `98fff62` closes the remaining Emacs-specific Anthropic failure path by aligning session rehydrate payloads with canonical agent history instead of TUI display rows.
- Backend/RPC changes:
  - `session/rehydrated` now emits canonical `msgs` / `:agent-messages` payloads for `new_session` and `/new` flows instead of display-only `{:role :assistant :text ...}` rows
  - the emitted payload shape now matches what the agent/executor/provider path expects when rebuilding conversation history
- Emacs coverage added:
  - canonical `session/rehydrated` payloads with `:role` + `:content` render correctly in transcript replay
  - `/new` local echo + `command-result` + `session/resumed` + `session/rehydrated` rebuild path does not leave duplicate assistant turns behind
- Agent-session coverage added:
  - RPC regression tests lock canonical rehydrate payloads for both `new_session` and `/resume`
- Verification:
  - `bb clojure:test:unit --focus psi.agent-session.rpc-test --focus psi.agent-session.rpc-anthropic-regression-test`
  - `bb emacs:test`

### Step 15kb — Anthropic prompt caching now declares its beta contract and preserves provider diagnostics ✓ complete
- Commit `5f6ec96` closes the gap between emitted Anthropic `cache_control` fields and the required request headers.
- Provider request shaping now:
  - adds `prompt-caching-2024-07-31` to `anthropic-beta` whenever any prompt unit carries `cache_control`
  - composes that beta with the existing OAuth and interleaved-thinking betas instead of replacing them
  - keeps prompt-caching detection structural by deriving it from system prompt blocks, tools, and messages
- Provider error handling now:
  - reads error bodies from either strings or input streams
  - prefers provider-supplied error text over generic exception labels
  - appends HTTP status and Anthropic request id when present
- Regression coverage added in `components/ai/test/psi/ai/providers/anthropic_test.clj` for:
  - prompt-caching beta header presence when `cache_control` is emitted
  - rich error text including status and request id
- Verification:
  - `clj-kondo --lint components/ai/src/psi/ai/providers/anthropic.clj components/ai/test/psi/ai/providers/anthropic_test.clj`
  - `bb clojure:test:unit --focus psi.ai.providers.anthropic-test`

### Step 15k — Explicit Emacs `/resume <path>` and `/tree <id>` now use canonical rehydrate events ✓ complete
- Commit `7383729` converges explicit session-targeting slash commands with the same canonical lifecycle already used by `/new`, bare `/resume`, and bare `/tree`.
- Backend/RPC changes:
  - `command` handling for `/resume <path>` now resumes immediately and emits `session/resumed` + `session/rehydrated`
  - `command` handling for `/tree <session-id>` now switches immediately and emits `session/resumed` + `session/rehydrated`
  - explicit session-targeting command flows still emit the normal trailing `session/updated`, `footer/updated`, and `context/updated` snapshots
- Emacs impact:
  - explicit `/resume <path>` now clears stale transcript/tool state through the canonical rehydrate event path instead of depending on command-only acknowledgement
  - `/tree <id>` widget/direct command activation now clears and redraws transcript state through the same canonical event contract
- TUI follow-up included:
  - `/tree` session switches now request a hard redraw so the screen clears like `/new`
- Regression coverage added:
  - RPC command tests for explicit `/resume <path>` and `/tree <session-id>` canonical event emission
  - Emacs transcript-clear/replay tests for explicit resume and tree-widget session switches
  - TUI redraw assertions for `/tree` switching paths
- Verification:
  - `bb clojure:test:unit --focus psi.agent-session.rpc-test`
  - `bb emacs:test`

### Step 15j — Anthropic cache should exclude volatile runtime metadata ✓ complete
- Commit `f28c93f` separates the prompt into a stable body and a runtime metadata tail so Anthropic system caching applies only to the stable body.
- The runtime tail still appears after extension prompt contributions and still includes:
  - current date/time
  - current working directory
  - current worktree directory
- `components/agent-session/src/psi/agent_session/executor.clj` now emits Anthropic system prompt blocks as:
  - cached stable block
  - uncached runtime metadata block
- Shared runtime metadata refresh logic now lives in `components/agent-session/src/psi/agent_session/system_prompt.clj` and is reused by session retargeting in `core.clj`.
- Verification:
  - `clojure -M:test --focus psi.agent-session.core-test`
  - focused `bb test` slices for system prompt / executor / Anthropic provider
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/system_prompt.clj components/agent-session/src/psi/agent_session/core.clj components/agent-session/src/psi/agent_session/executor.clj components/agent-session/test/psi/agent_session/system_prompt_test.clj components/agent-session/test/psi/agent_session/executor_test.clj components/agent-session/test/psi/agent_session/core_test.clj`

### Step 15ja — Anthropic replay diagnostics now have structural regressions for tool-history + cross-provider thinking ✓ complete
- Commit `ac0efb5` adds two focused regression tests around the live Anthropic failure investigation.
- Provider request-shape coverage added in `components/ai/test/psi/ai/providers/anthropic_test.clj`:
  - Anthropic request body with prior `tool_use` / `tool_result`
  - extended `:thinking`
  - Anthropic `cache_control`
  - normalized tool ids and matching `tool_result.tool_use_id`
- Cross-provider executor coverage added in `components/agent-session/test/psi/agent_session/executor_test.clj`:
  - OpenAI-style `:thinking-delta` remains transient
  - persisted assistant messages exclude prior thinking text
  - rebuilt Anthropic requests do not replay prior OpenAI thinking
- Verification:
  - `bb clojure:test:unit --focus psi.ai.providers.anthropic-test`
  - `bb clojure:test:unit --focus psi.agent-session.executor-test`

### Step 15i — Emacs session rehydrate lifecycle clears stale buffer state ✓ complete
- Commit `b3a5769` converges `/new`, `/tree`, and `/resume` on the canonical `session/resumed` + `session/rehydrated` lifecycle.
- Emacs changes:
  - subscribes to `session/resumed` and `session/rehydrated`
  - clears stale transcript/render state on `session/resumed`
  - replays rehydrated messages directly on `session/rehydrated`
  - handles RPC event mutations under read-only-safe buffer mutation context
- Backend changes:
  - `/new` via backend `command` now emits `session/resumed` and `session/rehydrated`
  - `/new` command-result type is normalized to `new_session`
- Regression coverage added:
  - Emacs topic subscription locks
  - Emacs transcript-clear + replay event tests
  - backend RPC test for `/new` command canonical rehydrate events
- Verification:
  - `bb emacs:test`
  - `bb emacs:e2e`
  - `bb clojure:test:unit`

### Step 15h — Emacs `/tree` shows session age from canonical session timestamps ✓ complete
- Commit `51b669f` adds stable session creation timestamps to the live `context/updated` payload and uses them to render compact relative age labels in Emacs `/tree`.
- Backend/runtime changes:
  - context session index now preserves session `:created-at` when present
  - session-root context projections expose `:psi.session-info/created`
  - RPC `context/updated` session slots now include `:created-at`
- Emacs changes:
  - `/tree` labels now include name-or-fallback, worktree path, and compact age (`now`, `53m`, `2h`, ...)
  - frontend age rendering is derived from raw timestamps rather than preformatted backend strings
  - `/tree` selector candidates reuse the same richer label helper as the projection widget
- Spec surfaces synchronized:
  - `spec/rpc-edn.allium`
  - `spec/emacs-frontend.allium`
  - `spec/session-core.allium`
- Verification:
  - `bb emacs:test`
  - `clojure -M:test --focus unit --skip-meta integration --fail-fast`

### Step 15g — Emacs slash-command routing becomes run-state independent ✓ complete
- Commit `1bd1f17` removes the frontend concept of idle-only slash commands.
- Slash-prefixed compose input now routes to backend `command` in both idle and streaming states.
- Non-slash streaming input still routes through `prompt_while_streaming` with steer/queue behavior.
- Spec/docs/code/test surfaces were converged:
  - `spec/emacs-frontend.allium` now models slash-first compose routing explicitly.
  - `spec/prompt-slash-commands.allium` now states slash routing is run-state independent in the frontend.
  - Emacs compose dispatch now uses one slash-first dispatcher for send/queue.
  - Emacs helper/test/doc vocabulary was renamed away from `idle-slash-*`.
  - `/tree` preserves backend fallback when no local context snapshot exists.
- Verification:
  - `allium check spec/emacs-frontend.allium spec/prompt-slash-commands.allium`
  - `bb emacs:test`

---

### Step 15f — `/work-done` linear-history workflow ✓ complete
- Commit `7757920` replaces `/work-merge` with `/work-done` as the user-facing worktree completion command.
- PSL follow-up `d7e2e28` removed the temporary `:psi.agent-session/git-*` compatibility layer after all runtime consumers had moved to the canonical session-root attrs `:git.worktree/*` and `:git.branch/default-branch`.
- The workflow now:
  - caches the default branch on init/session switch
  - reads the default branch through the queryable attr `:git.branch/default-branch`
  - checks whether the current linked branch already contains the default-branch HEAD
  - runs a forked sync subagent rebase when that precondition is not yet true
  - stops with an informative message if the rebase fails
  - fast-forward merges from the main worktree context, then removes the worktree and deletes the branch
- User docs/spec/tests were synchronized to `/work-done`.
- Verification:
  - `bb clojure:test:extensions --focus extensions.work-on-test`
  - `bb clojure:test:unit`
  - `bb lint`

### Step 15e — Emacs slash E2E frontend-action roundtrip ✓ complete
- Live Emacs slash verification now covers a backend-requested frontend action, not just text/quit paths.
- Commit `3c0e668` extends `components/emacs-ui/test/psi-e2e-test.el` to drive:
  - `/history` for backend-owned slash text output
  - `/thinking` for `ui/frontend-action-requested` → `frontend_action_result` roundtrip
  - `/quit` for terminal backend-owned slash behavior
- The harness now auto-selects a deterministic thinking level (`high`) through `completing-read` and waits for:
  - visible confirmation text (`Thinking level set to high`)
  - cleared pending frontend-action state
  - preserved input focus
  - preserved read-only transcript/projection boundaries after the action flow
- `components/emacs-ui/README.md` now documents the expanded `bb emacs:e2e` coverage.
- Verification:
  - `bb emacs:e2e` ✓ green

### Step 15d — Turn-state provider boundary telemetry ✓ complete
- Executor turn data now records provider boundary lifecycle, not just assembled text/tool results.
- New turn-state fields:
  - `:last-provider-event`
  - `:content-blocks`
- Boundary events now update turn state for:
  - `:text-start` / `:text-end`
  - `:thinking-start` / `:thinking-delta` / `:thinking-end`
  - existing tool-call / done / error events
- Result: a stalled or malformed provider turn can now be diagnosed from executor-local turn telemetry without changing RPC/TUI surfaces.
- EQL turn resolver now exposes:
  - `:psi.turn/last-provider-event`
  - `:psi.turn/content-blocks`
- Focused verification green:
  - `clojure -M:test --focus psi.agent-session.executor-test --focus psi.agent-session.turn-statechart-test`
- Implementation commit: `77f0bb7`

### Step 15c — Startup host bootstrap should create exactly one real session ✓ complete
- Removed phantom startup host entries by aligning session creation with actual runtime intent.
- `create-context` now starts with an empty context session index instead of seeding it from `initial-session`.
- `bootstrap-in!` (renamed from `bootstrap-session-in!`) now applies startup wiring to the current context without creating a new session.
- `bootstrap-runtime-session!` now creates one real session up front, then runs startup prompts in that same session instead of creating a second startup-only branch.
- Result: startup context snapshot contains exactly one real active session rather than seed/bootstrap/startup artifacts.
- Coverage updated:
  - core context-index tests now assert empty context index on fresh context and first real registration on `new-session-in!`
  - runtime bootstrap regression asserts single-session context state
  - resolver/introspection call sites updated to `bootstrap-in!`
- Implementation commit: `87a5e77`

### Step 15b — Prompt-memory principle update (root cause over workaround) ✓ complete
- Added explicit decision rule to prompt memory (`AGENTS.md`):
  - `λf. f (prefer (fix_root_cause) (over workaround))`
- This follows the same shape as existing operational principles and makes bug-fix preference explicit for future ψ loops.
- Implementation commit: `859515c`

### Step 15a — RPC handshake context snapshot bootstrap + `/tree` simplification ✓ complete
- RPC handshake path now emits a context snapshot event (`context/updated`) during bootstrap in rpc-edn mode.
  - Wiring: `:handshake-context-updated-payload-fn` in rpc runtime state
  - Handler: handshake emits `context/updated` before handshake response when configured
- Emacs `/tree` now relies on bootstrapped context snapshot state and no longer queries `list_sessions` as a fallback.
- Coverage updated:
  - RPC handshake tests now assert context snapshot emission when configured.
  - Emacs suite remains green with `/tree` operating from context snapshot state.
- Implementation commit: `a639f3e`

### Step 15 — Emacs e2e harness assertions + transport timeout stabilization ✓ complete
- Added live Emacs end-to-end harness tasking and docs surface:
  - `components/emacs-ui/test/psi-e2e-test.el`
  - `bb emacs:e2e` in `bb.edn`
  - docs sync in `components/emacs-ui/README.md` and `doc/emacs-ui.md`
- E2E assertions now cover startup/session boundary guarantees:
  - compose input remains focused
  - footer/projection is visible
  - non-input regions reject direct edits
- Stabilized e2e readiness in real runtime conditions:
  - timeout widened to 60s for handshake/startup variance
  - projection rendering marks interior projection/footer region read-only while preserving writable boundary insertion for transcript/tool rows
- Verification snapshot (commit `ada2c32`):
  - `bb emacs:test` green
  - `bb emacs:e2e` green (`psi-emacs-e2e:ok`)

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
