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
- Progress: commit `b652c1f` smooths startup UX before handshake completes — Emacs now seeds a deterministic `connecting...` footer placeholder, keeps projection upserts read-only-safe, and preserves immediate cursor focus in the dedicated input area (`psi-emacs--focus-input-area` sets both buffer point and window point).
- Progress: startup hydration now keeps point in input (`psi-initial-transcript-hydration-keeps-point-in-input-area-when-messages`) instead of jumping to transcript top; startup focus behavior is covered by new pre-handshake tests.
- Progress: commit `b0f83c3` adds a deterministic `ψ` banner at transcript start for both initial startup and `/new` reset path (rendered before replayed transcript/messages), tightens startup focus by preferring the explicit `pop-to-buffer` window target, and fixes a backend startup compile break by aligning agent-session mutation registration (`all-mutations` now references `interrupt`, not stale `abort`).
- Progress: commit `b13c4f8` updates Allium contracts to match shipped startup/interrupt behavior: `spec/emacs-frontend.allium` now models banner-first transcript + input-area cursor invariants (startup and `/new` reset), and `spec/session-management.allium` now explicitly models `prompt_while_streaming` steer/queue semantics including interrupt-pending steer→follow-up coercion.
- Progress: commit `f92dc0f` verified interrupt behavior convergence between `spec/agent-turn-interrupts.allium`, the agent-session runtime, and Emacs frontend surfaces (deferred interrupt, pending-state projection, steering-drop/follow-up preservation, and turn-boundary apply/reset semantics).
- Progress: commit `0c6667f` fixed Emacs startup `*lsp-log*` read-only regression by removing buffer-local `inhibit-read-only` from `psi-emacs-mode`, localizing transcript mutations behind explicit `let ((inhibit-read-only t))` boundaries, tightening separator marker validity checks, and updating ERT transcript-clearing tests for read-only semantics.
- Progress: commit `7b63628` fixed `psi-emacs-project` re-running all prompts when switching to an existing buffer — `psi-emacs-open-buffer` now only activates modes on a fresh buffer (`unless derived-mode-p`), making the function idempotent and preventing buffer-local state wipe on re-entry.
- Progress: commit `098cead` fixed "Text is read-only" error on buffer switch — `psi-emacs--refresh-input-separator-line` now binds `inhibit-read-only` around its `delete-region`/`insert` pair, matching all other internal transcript mutation sites.
- Progress: commit `d90880d` adds close confirmation for live psi buffers by installing `psi-emacs--confirm-kill-buffer-p` on `kill-buffer-query-functions`; declining keeps buffer/process alive, and noninteractive test runs bypass prompts. Regression tests added for decline + noninteractive behavior.
- Progress: commit `3cc8a76` made runtime UI surface introspectable (`:psi.agent-session/ui-type`) and exposed `:ui-type` on extension API; extension widgets now branch placement by UI (`:console|:tui|:emacs`) in `subagent_widget`, `agent-chain`, and `mcp_tasks_run`.
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
- Progress: commit `9f55a9f` also extends workflow mutation job tracking to support both `psi.extension.workflow/create` and opt-in `psi.extension.workflow/send-event` via `track-background-job?`, returning `:psi.extension.background-job/id` for `/jobs` + `/job` visibility parity.
- Progress: commit `032d3f2` now tracks `.psi/agents/*` in git (including `agent-chain.edn` and core agent profiles), making chain/profile behavior reproducible across clones and no longer dependent on local `.git/info/exclude` state.
- Progress: commit `00bd634` eliminates the active-chain selection concept entirely — `run_chain` is replaced by `agent-chain(action, chain, task)`. The tool resolves the chain by name at call time; no prior `/chain` selection step is required. `action="list"` and `action="reload"` consolidate the former `/chain-list` and slash-based reload into the tool API. `/chain` and `/chain-reload` remain as human-facing aliases. Widget `active:` display removed.
- Progress: commit `27efd76` adds prompt contribution for agent-chain: chain catalog (name + description) advertised in system prompt under `Extension Capabilities`; synced on init, reload, and session_switch so the model always has current chain inventory without a prior `action="list"` call.
- Progress: commit `050e29d` canonically enforces kebab-case tool names at extension registration (`^[a-z0-9][a-z0-9-]*$`), adds `spec/tools/tool-naming.allium`, and renames extension tool identifiers from underscore style to kebab-case (`agent-chain`, `hello-upper`, `hello-wrap`).
- ~~Follow-up queued: align `/chain` command UX with user intent by supporting both index and name selection (`/chain <number|name>`), while keeping no-default-active semantics.~~
- Progress: commit `93a517e` adds explicit delivery-specific PSL transcript status messaging (`queued via deferred; will auto-run when idle` / `queued via prompt` / `queued via follow-up`) with extension tests and spec updates.
- Progress: commit `51b9192` hardens PSL delivery-status fallback formatting: unknown/non-keyword delivery values now render safely via `str` coercion instead of `name`, preventing runtime type errors in status-message emission.
- Progress: commit `a3c9756` hardened extension isolation during bootstrap — `send-extension-message-in!` now guards history append behind `startup-bootstrap-completed?`; PSL startup noise message removed.
- Progress: commit `c9af5f0` fixed TUI footer token/context display — Anthropic SSE provider was hardcoding usage to zeros; now reads real `input_tokens`/`output_tokens`/cache tokens from `message_start` and `message_delta` events. Footer now shows actual context fraction (e.g. `12.3%/200k`) instead of `?/0`.
- Progress: commit `bd45ada` fixed footer usage aggregation boundaries in `session-usage-totals`: usage now remains session-scoped while still counting legacy journal rows with missing `:session-id`, preventing prior-session token carryover after `new_session`.
- Progress: commit `3600486` tightened agent-chain/operator UX: async chain start response is now compact and machine-friendly (`OK id:<run-id>`), with monitor guidance removed from tool output; lambda/prompt compiler agent definitions were also refined for stricter minimal output contracts.
- Progress: commit `c353189` clarifies `agent-chain` tool contract text for async runs: `action="run"` language now says "start" (not "execute"), and description explicitly states "Do not poll unless explicitly asked to." to discourage default status polling loops.
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
