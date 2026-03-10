# State

Current truth about the Psi system.

---

## Operating Frame

- ✓ Nucleus framing is now explicit in `AGENTS.md` via **Core Equation** (`刀 ⊣ ψ → 🐍`) and **The Loop** (Observe → Orient → Decide → Act).
- ✓ Spec refinement framing now explicitly includes values in `AGENTS.md` using allium-specific primitives (`λallium_spec_step(刀_intention, ψ_values, allium_spec)`), tightening the intention+values contract for future prompt/spec evolution.
- ✓ Project values and spec-drift guard workflow are now explicit in `README.md` (Values section + `bb spec:*` commands with declaration-name baseline guard).
- ✓ Remember memory-capture framing clarified: human signal to future ψ via manual remember writeback.
- ✓ Memory model boundary clarified: session memory (ephemeral working set) ≠ persistent memory (cross-session distilled artifacts) ≠ git history (queried directly, not duplicated into memory store).
- ✓ Session persistence is a separate concern from memory: session transcripts/state may be partially persisted for `/resume`, but this is distinct from memory-store artifacts used by remember/recover.
- ✓ Working pattern remains atomic: inspect → minimal change → verify → commit.
- ✓ AGENTS guidance now formalizes spec/test convergence equations (commit `5504fcf`): `refactor_minimal_semantics_spec_tests` and `tests_musta_cover_allium_spec_behaviour` are explicit, and iterative refinement naming has shifted from generic `spec` to explicit `allium_spec` terms for consistency.
- ✓ 2026-03-06 session boot aligned via nucleus/OODA ritual; current mode is ◈ reflect → ready for · atom execution.
- ✓ Emacs prompt completion architecture implemented via CAPF: `/` command completion + `@` reference completion, category metadata (`psi_prompt`, `psi_reference`), affixation/annotation/exit hooks, cwd+project-root search, and configurable completion policies.
- ✓ Emacs completion verification: `components/emacs-ui` ERT suite green at 133/133 after completion work.
- ✓ Step 11a git-worktree visibility (read-only) implemented: `:git.worktree/*` attrs, session-root bridge attrs, `/worktree` command, and `/status` worktree surfacing.
- ✓ Worktree failure path now degrades safely with telemetry marker (`git.worktree.parse_failed`) and coverage.
- ✓ Test isolation hardened: agent-session/introspection tests now use temp cwd to avoid writing repo `.psi/project.edn`.
- ✓ PSL extension 400 fix: `agent-messages->ai-conversation` filters `:custom-type` messages; `extension-run-fn-atom` wired so PSL prompts invoke a real LLM call (not orphaned user messages). `register-extension-run-fn-in!` called after bootstrap in `main.clj`.
- ✓ Extension prompt delivery is now explicit for busy sessions (commit `fcf9db3`): `send-extension-prompt-in!` reports `:deferred` (not queue-only `:follow-up`) when a run-fn exists and the session is streaming; the runner waits for idle and executes automatically.
- ✓ PSL extension refactored to statechart workflow (commit `690cc7f`): `git_head_changed` handler now does skip-check only then creates a `:psl` workflow; job (future invoke) runs `send-message` + `send-prompt` from background, so PSL output appears after the triggering commit turn completes rather than before it.
- ✓ Extension bootstrap message isolation (commit `a3c9756`): `send-extension-message-in!` now skips `append-message-in!` and agent emits when `startup-bootstrap-completed?` is false — messages sent during extension `init` reach the event queue (UI notification) but never corrupt LLM history. PSL's `"PSL extension loaded."` startup call removed.
- ✓ Anthropic provider error body decoding (commit `4ffaa11`): 400/error responses from Anthropic are now decoded from the GZIP+JSON body in `ex-data :body`; the real API error message (e.g. "model not found") is surfaced in `:error-message` instead of the opaque `clj-http: status 400 {...}` ExceptionInfo string.
- ✓ `tool_use.input` always-dict hardening (commit `26fedd9`): `parse-args` in `executor.clj` now validates the JSON-parsed result is a map (falls back to `{}` for string/array/null); `transform-messages` in `anthropic.clj` adds a belt-and-suspenders guard at the wire-format layer. Fixes Anthropic 400 `messages.N.content.M.tool_use.input: Input should be a valid dictionary`.
- ✓ Thinking output bug fixed (c8e43eb): Anthropic provider now handles `thinking` content blocks correctly (`:thinking-delta` events, `thinking` request param, `interleaved-thinking-2025-05-14` beta header, temperature suppressed when thinking enabled). Emacs render fixed: `psi-emacs--assistant-thinking-delta` uses pure `concat` append instead of snapshot-merge heuristic that caused ever-growing repeated lines.
- ✓ OpenAI thinking streaming restored for chat-completions models (4c20882): provider now forwards `reasoning_effort` from thinking-level, extracts reasoning deltas across multiple chunk shapes, and emits reliable `:thinking-delta` events (plus normalized usage map for completion cost calculation).
- ✓ OpenAI thinking visibility now lands consistently across stream surfaces including TUI rendering (fbbb173), closing parity gaps between provider deltas and terminal presentation.
- ? Divider-length regression report is in Emacs UI, not TUI: commit `3e02b97` made TUI separators width-aware, but user-observed uneven separators remain in `components/emacs-ui` projection/input paths.
- ✓ Commit `db9d4c7` now refreshes width-sensitive separators on window-configuration changes in Emacs buffers.
- ✓ Width source for projection/footer separators now prefers `window-text-width` (visible text area), with margin-based fallback.
- ✓ Window-change handler now calls `psi-emacs--ensure-input-area`, repairing stale/misaligned pre-edit separator markers before projection refresh.
- ✓ Emacs verification after separator follow-up: `bb emacs:test` green at 155/155.
- ✓ Project-scoped Emacs startup command added (`psi-emacs-project`): starts psi at detected project root, names buffers as `*psi:<project>*`, and supports prefix semantics (`C-u` fresh generated, `C-u N` slot selection).
- ✓ Emacs UI docs now include project command behavior and prefix semantics in both `README.md` and `components/emacs-ui/README.md`.
- ✓ Emacs verification after project-command addition: `bb emacs:test` green at 165/165; `bb emacs:check` green (with one pre-existing byte-compile warning unrelated to this change).
- ✓ Agent-chain `run_chain` progress heartbeat + widget projection landed (`18e0c50`): extension now tracks per-run phase/step/elapsed state, emits throttled tool updates while waiting for workflow completion, and refreshes widget state deterministically on init/reload/session-switch.
- ✓ Agent-chain default selection behavior tightened (`53c0f40`): extension no longer auto-selects first chain on init/reload/session-switch; widget remains `active: (none)` until explicit chain selection.
- ✓ `run_chain` replaced by `agent-chain` tool with explicit `action` parameter (commit `00bd634`): chain is resolved by name at call time — no global active-chain selection required. `action="run"` requires `chain` + `task`; `action="list"` replaces `/chain-list`; `action="reload"` replaces `/chain-reload` as a tool action. Slash commands `/chain` and `/chain-reload` remain as human-facing aliases. Widget no longer shows `active: <name>`.
- ✓ `agent-chain` now advertises available chains via prompt contribution (commit `27efd76`): contribution id `agent-chain-chains` registers under section `Extension Capabilities` (priority 200) with content listing chain names and descriptions. Synced on init, `action="reload"`, and `session_switch`.
- ✓ `agent-chain(action="run")` start acknowledgment is now compact and parse-friendly (commit `3600486`): returns `OK id:<run-id>` with no inline monitoring instruction; this reduces transcript noise and leaves monitoring policy to caller/UI.
- ✓ Agent-chain tool description now codifies async polling expectations (commit `c353189`): contract text says `action="run"` "start[s]" a chain and explicitly states "Do not poll unless explicitly asked to," aligning tool metadata with non-blocking workflow intent.
- ✓ Tool naming is now canonicalized and enforced (commit `050e29d`): extension tool registration rejects non-kebab-case names (`^[a-z0-9][a-z0-9-]*$`), new spec `spec/tools/tool-naming.allium` defines the contract, and extension tools were renamed from underscore style to kebab-case (`agent-chain`, `hello-upper`, `hello-wrap`).
- ✓ Bash tool spec/code convergence completed (commit `ad44cb3`): `spec/tools/bash.allium` now reflects runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (no `spawn_hook`/`operations`/chunk-stream partial contract; includes `overrides`, `on_update`, `tool_call_id`, timeout default, truncation-details-only-when-truncated, and boolean `abort-bash!` behavior).
- ✓ Read tool spec/code convergence completed (commit `a88335f`): `spec/tools/read.allium` now reflects runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (image reads return text+image content blocks with `details=nil`; binary warning/missing-file messages use absolute paths; `overrides` policy is explicit; offset/limit/truncation guidance text matches implementation).
- ✓ Write/edit tool spec/code convergence completed (commit `f5ba5cd`): `spec/tools/write.allium` and `spec/tools/edit.allium` now reflect runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (write: expanded+resolved path with resolved-path byte-count success message; edit: expanded+resolved path, exact-first replacement, fuzzy smart-punctuation/trailing-whitespace window matching, and runtime-aligned error strings/diff metadata).
- ✓ Edit spec follow-up convergence completed (commit `b642b54`): `spec/tools/edit.allium` now guarantees non-null diff metadata line numbers (`first_changed_line` is Integer with fallback `or 1`) and explicitly captures no-cwd absolute-path execution behavior, while retaining snake_case spec API fields (`old_text`, `new_text`) per repository convention.
- ✓ app-query-tool spec/code convergence completed (commit `364475a`): `spec/tools/app-query-tool.allium` now reflects runtime semantics in `components/agent-session/src/psi/agent_session/tools.clj` (`make-app-query-tool` copies base schema metadata, parses EDN with `*read-eval*` disabled, enforces vector query input, applies head truncation with optional `overrides`, writes full-output artifacts keyed by `tool_call_id` when truncated, returns `details=nil` when not truncated, and remains factory-only outside direct built-in dispatch).
- ✓ Runtime UI surface is now first-class and introspectable (commit `3cc8a76`): `:psi.agent-session/ui-type` resolves from session root (`:console|:tui|:emacs`), rpc handshake server-info includes `:ui-type` for Emacs transport, and extension API exposes `:ui-type` directly for branching without an EQL round-trip.
- ✓ Widget-heavy extensions now branch placement by UI type (commit `3cc8a76` + follow-up): `subagent_widget`, `agent-chain`, and `mcp_tasks_run` render `:below-editor` in Emacs and `:above-editor` otherwise; nullable extension test helpers gained `:ui-type` support so extension tests assert UI-specific behavior deterministically.
- ✓ `run_chain` execution mode now defaults to non-blocking background workflow start (commit `8d36927`), preventing Emacs UI request-path blocking; synchronous wait remains available via explicit `wait=true`.
- ✓ Interactive tool-call path now enforces non-blocking `run_chain` execution even when `wait=true` is requested (commit `11feddf`), avoiding request-path stalls in UI clients.
- ✓ Chain result delivery to chat (commit `81af559`): `emit-chain-result!` sends chain output back to the active session as an assistant message (`custom-type: "chain-result"`) on both success and error; ai-model resolution now falls back through session model → `:sonnet-4.6`; `done-script`/`error-script` call `on-finished` callback; step execution now catches `Throwable` (not just `Exception`).
- ✓ Extension-initiated agent runs now stream to RPC client (commit `1cebe2b`): `register-rpc-extension-run-fn!` replaces the no-progress-queue run-fn set by `main.clj` with one that creates a per-run `progress-queue`, polls it via a background future routing events through `emit-frame!`, and emits `assistant/message` + `session/updated` + `footer/updated` on completion. Called once from the `subscribe` handler (guarded by `:rpc-run-fn-registered`). Fixes PSL response invisibility in Emacs.
- ✓ Agent-chain definitions are now discoverable via top-level EQL attrs (`:psi.agent-chain/config-path`, `:psi.agent-chain/count`, `:psi.agent-chain/names`, `:psi.agent-chain/chains`, `:psi.agent-chain/error`) after runtime reload.
- ✓ Extension slash command completion now includes backend extension commands in both frontends:
  - Emacs CAPF merges built-ins with cached `:psi.extension/command-names`
  - TUI slash autocomplete refreshes `:psi.extension/command-names` during update loop
- ✓ Generic extension prompt-contribution layer landed (commit `e2fe3ed`): session now tracks `:base-system-prompt` + ordered `:prompt-contributions`, runtime recomposes `:system-prompt` deterministically, and extensions can register/update/unregister/list contribution fragments without domain-specific coupling.
- ✓ Prompt contribution surfaces are queryable/mutable via EQL (commit `e2fe3ed`):
  - mutations: `psi.extension/register-prompt-contribution`, `psi.extension/update-prompt-contribution`, `psi.extension/unregister-prompt-contribution`
  - attrs: `:psi.agent-session/base-system-prompt`, `:psi.agent-session/prompt-contributions`, `:psi.extension/prompt-contributions`, `:psi.extension/prompt-contribution-count`
- ✓ subagent-widget now advertises its tool capabilities through prompt contributions (commit `e2fe3ed`, refined copy in follow-up), enabling direct model-side discovery of subagent operations from system prompt context.
- ✓ Subagent profile selection shipped (commit `76a07e5`): `/sub` now supports optional `@agent` prefix, tool action `create` accepts `agent`, unknown agent profiles are rejected early, workflow/list output includes selected `@agent`, and prompt contributions now include discovered agent descriptions from `.psi/agents/*.md`.
- ✓ Agent and chain definitions are now repository-tracked (commit `032d3f2`): `.psi/agents/*` (including `agent-chain.edn` and core agent profile markdown) is committed, eliminating local-only drift from `.git/info/exclude` and making chain/profile behavior reproducible across clones.
- ✓ Operator intent mismatch identified: users naturally try `/chain <name>` (for example `/chain prompt-build`) while current command handling is index-only; follow-up is in progress to support name-based selection without reintroducing implicit defaults.
- ✓ Submit-cycle separator disappearance in Emacs input area is now repaired in the send lifecycle (commit `c649a68`): `psi-emacs--consume-dispatched-input` re-runs `psi-emacs--ensure-input-area` after dispatch, making separator visibility self-healing on prompt submission.
- ✓ Startup UX now seeds a deterministic pre-handshake footer (`connecting...`) and keeps cursor focus in the dedicated input area immediately on display (commit `b652c1f`): `psi-emacs--focus-input-area` now normalizes both buffer point and visible window point.
- ✓ Projection/footer upsert path is now read-only-safe under the input-area guard (`inhibit-read-only` around projection delete/insert), preventing startup/event-time `text-read-only` drift.
- ✓ Startup hydration now preserves input focus even when replayed messages exist (`psi-initial-transcript-hydration-keeps-point-in-input-area-when-messages`).
- ✓ Focused regression coverage added: `psi-send-repairs-missing-input-separator-after-submit`, `psi-open-buffer-seeds-connecting-footer-before-handshake`, and `psi-start-focuses-window-point-in-input-area-before-handshake` (`components/emacs-ui/test/psi-test.el`).
- ✓ Startup transcript now begins with a deterministic psi banner (`ψ`) before all other content, including on `/new` reset flows (`psi-emacs--ensure-startup-banner` in compose/lifecycle/entry paths).
- ✓ Startup focus targeting is now window-aware: `psi-emacs--focus-input-area` prefers the explicit `pop-to-buffer` window and synchronizes both buffer point + window point (plus additional visible windows for same buffer).
- ✓ Backend handshake/startup compile regression resolved: `components/agent-session/src/psi/agent_session/core.clj` `all-mutations` now references `interrupt` mutation symbol (stale `abort` reference removed), restoring `--rpc-edn` handshake readiness.
- ✓ Verification: `bb emacs:test` passing at 172/172 after banner/focus/mutation-registration follow-up.
- ✓ Allium spec parity now includes these startup/streaming behaviors (commit `b13c4f8`):
  - `spec/emacs-frontend.allium` models deterministic `ψ` transcript banner + input-area cursor placement on initialization and `/new` reset.
  - `spec/session-management.allium` models `prompt_while_streaming` steer/queue semantics and interrupt-pending steer coercion to follow-up.
- ✓ `allium check spec` remains green after spec update (48 files, 0 issues).
- ✓ `psi-emacs-command` is now marked safe for local variables (`:safe` predicate accepts list-of-strings), so project `.dir-locals.el` command overrides no longer trigger Emacs unsafe-local warnings.
- ✓ Emacs startup `*lsp-log*` read-only regression fixed (commit `0c6667f`): removed `psi-emacs-mode` buffer-local `inhibit-read-only`; localized transcript/property mutations behind explicit `let ((inhibit-read-only t))`; separator marker validity now requires line-start marker anchoring; Emacs tests updated for intentional read-only transcript clearing.
- ✓ Verification after read-only regression fix: `bb emacs:test` passing at 168/168 and `bb emacs:byte-compile` clean (pre-existing docstring width warnings only).
- ✓ `psi-emacs-project` buffer-reuse regression fixed (commit `7b63628`): `psi-emacs-open-buffer` was unconditionally calling `(text-mode)` on every invocation, resetting buffer-local state (including `psi-emacs--state`) and triggering a full re-init + transcript replay even for already-live buffers. Fixed by guarding the mode-setup block inside `unless (derived-mode-p 'psi-emacs-mode)`. Switching to an existing psi buffer now just focuses it. All 168 tests pass.
- ✓ `psi-emacs-project` "Text is read-only" follow-up fixed (commit `098cead`): `psi-emacs--refresh-input-separator-line` mutated the separator region via `delete-region`/`insert` without `inhibit-read-only`, triggering the `before-change-functions` guard on re-entry. Added `(inhibit-read-only t)` binding, consistent with all other internal transcript mutation sites. All 168 tests pass.
- ✓ psi buffer close now confirms before terminating a live process (commit `d90880d`): Emacs lifecycle installs `psi-emacs--confirm-kill-buffer-p` on `kill-buffer-query-functions`; interactive close prompts when owned process is live, decline cancels kill and preserves process, noninteractive paths (ERT/batch) auto-allow. Verification: `bb emacs:test` green at 174/174 with new close-confirmation regressions.
- ✓ AI resolver list duplication eliminated (commit `f8727db`): `ai/all-resolvers` made public; `introspection/core.clj` `register-resolvers!` and `register-resolvers-in!` now use `(doseq [r ai/all-resolvers] ...)` instead of hand-listing all four AI resolvers. Adding a new AI resolver now requires only one change site.
- ✓ Anthropic provider usage tracking fixed (commit `c9af5f0`): `message_start` SSE event now captures `input_tokens`, `cache_read_input_tokens`, and `cache_creation_input_tokens`; `message_delta` captures `output_tokens`; `:done` event carries real usage map + calculated cost. Previously all usage was hardcoded to zero, causing the TUI footer to show `?/0` instead of actual token/context counts.
- ✓ Emacs footer now refreshes token/context usage after every tool call (commit `3786e39`): both progress poll loops in `rpc.clj` emit `footer/updated` immediately after each `:tool-result` event, so the stats-line (↑↓R W cost context%) updates live during multi-tool turns rather than only at the end of the agent loop.

## Components

| Component       | Status | Notes                                                      |
|-----------------|--------|------------------------------------------------------------|
| `ai`            | ✓      | Provider streaming, model registry, extended thinking, tested |
| `engine`        | ✓      | Statechart infra, system state, nullable ctx               |
| `query`         | ✓      | Pathom3 EQL registry, `query-in`, nullable ctx             |
| `agent-core`    | ✓      | LLM agent lifecycle statechart + EQL resolvers             |
| `history`       | ✓      | Git log resolvers, nullable git context                    |
| `introspection` | ✓      | Bridges engine + query, self-describing graph              |
| `tui`           | ✓      | charm.clj Elm Architecture, JLine3, extension UI state     |
| `emacs-ui`      | ✓      | emacs mode for psi                                         |
| `agent-session` | ✓      | Session ✓, extensions ✓, extension UI ✓, main REPL ✓, TUI ✓, OAuth ✓ |

## Architecture Progress

- ✓ Engine (statecharts) substrate
- ✓ Query (EQL/Pathom3) surface
- ✓ AI provider layer
- ✓ Agent core loop
- ✓ Git history resolvers
- ✓ Introspection (engine queries itself)
- ✓ Coding-agent session orchestration (agent-session component)
- ✓ Built-in tools (read, bash, edit, write, app-query-tool)
- ✓ Executor (bridges ai streaming → agent-core loop protocol)
- ✓ Turn statechart (per-turn streaming state, EQL queryable)
- ✓ Runnable entry point (`clojure -M:run`)
- ✓ TUI session (`--tui` flag) — charm.clj Elm Architecture, JLine3
- ✓ Extension system (Clojure extensions, loader, API, tool wrapping, EQL introspection)
- ✓ Extension UI (dialogs, widgets, status, notifications, render registry, EQL introspection)
- ✓ OAuth module (PKCE, callback server, credential store, provider registry, Anthropic)
- ✓ Session introspection hardening (Step 7a): messages-count, tool-call-count, start-time, current-time
- ✓ Graph emergence (Step 7): all 9 :psi.graph/* attrs queryable via app-query-tool from agent-session-ctx
- ✓ Memory backing-store extension point (Step 9a phase 1): provider protocol + registry (`psi.memory.store`) with in-memory default and `:psi.memory.store/*` EQL attrs
- ✓ Datalevin persistent memory provider (Step 9a phase 2): `psi.memory.datalevin` + write-through remember/recover/graph artifacts + activation-time hydration
- ✓ Memory runtime hardening (Step 9.5): CLI/env config surface, provider failure telemetry surfacing, explicit provider selection/fallback reporting, retention overrides, Datalevin schema migration hooks, operator docs
- ✓ OAuth wired into runtime command flow (`/login`, `/logout`)
- ✓ Session resolvers wired into global query graph
- ✓ Graph emergence from domain resolvers (`ai`, `history`, `agent-session`, `introspection`)
- ✗ AI COMPLETE
- ✓ RPC EDN surface (`--rpc-edn`)
- ✗ HTTP API surface

## Runnable

```bash
ANTHROPIC_API_KEY=sk-... clojure -M:run
clojure -M:run --model claude-3-5-sonnet
clojure -M:run --model gpt-4o --tui
PSI_MEMORY_STORE=datalevin clojure -M:run  # opt-in persistent memory store
clojure -M:run --memory-store datalevin --memory-store-db-dir /tmp/psi-memory.dtlv
clojure -M:run --memory-store datalevin --memory-store-fallback off
clojure -M:run --memory-retention-snapshots 500 --memory-retention-deltas 2000
clojure -M:run --rpc-edn                 # EDN-lines RPC mode (headless/programmatic)
clojure -M:run --nrepl                   # random port, printed at startup
clojure -M:run --nrepl 7888              # specific port
clojure -M:run --tui --nrepl             # TUI + nREPL for live introspection
```

## RPC EDN (Step 8)

Status: ✓ complete

Implemented operations: `handshake`, `ping`, `query_eql`, `prompt`, `steer`, `follow_up`, `abort`, `new_session`, `switch_session`, `fork`.

In-session commands: `/status`, `/history`, `/new`, `/help`, `/quit`, `/exit`, `/skills`, `/prompts`, `/skill:<name>`, plus extension commands

Built-in tools: `read`, `bash`, `edit`, `write`, `app-query-tool`

The `app-query-tool` tool enables in-session EQL introspection without nREPL:
```
app-query-tool(query: "[:psi.agent-session/phase :psi.agent-session/session-id]")
app-query-tool(query: "[{:psi.agent-session/stats [:total-messages :context-tokens]}]")
app-query-tool(query: "[:psi.tool/names :psi.skill/names]")
```

Canonical agent-session telemetry query path (direct top-level attrs):
```
app-query-tool(query: "[:psi.agent-session/messages-count]")
app-query-tool(query: "[:psi.agent-session/tool-call-count]")
app-query-tool(query: "[:psi.agent-session/start-time]")
app-query-tool(query: "[:psi.agent-session/current-time]")
app-query-tool(query: "[:psi.agent-session/messages-count :psi.agent-session/tool-call-count :psi.agent-session/start-time :psi.agent-session/current-time]")
```

Live verification (2026-03-01): all 5 queries above return successfully with no resolver error; counts return integers and both time attrs return `java.time.Instant`.

## Canonical Graph Attrs (Step 7)

All 9 required Step 7 graph attrs are queryable via `app-query-tool` (seeded from `:psi/agent-session-ctx`):

```clojure
[:psi.graph/resolver-count]    ;; integer — resolvers in global registry
[:psi.graph/mutation-count]    ;; integer — mutations in global registry
[:psi.graph/resolver-syms]     ;; set of qualified symbols
[:psi.graph/mutation-syms]     ;; set of qualified symbols
[:psi.graph/env-built]         ;; boolean — Pathom env compiled
[:psi.graph/nodes]             ;; vector of CapabilityNode maps
[:psi.graph/edges]             ;; vector of CapabilityEdge maps (with :attribute)
[:psi.graph/capabilities]      ;; vector of DomainCapability maps
[:psi.graph/domain-coverage]   ;; vector of DomainCoverage maps (ai/history/agent-session/introspection)
```

Combined query (all 9):
```clojure
[:psi.graph/resolver-count :psi.graph/mutation-count
 :psi.graph/resolver-syms :psi.graph/mutation-syms :psi.graph/env-built
 :psi.graph/nodes :psi.graph/edges :psi.graph/capabilities :psi.graph/domain-coverage]
```

nREPL introspection (from connected REPL):
```clojure
@psi.agent-session.main/session-state   ;; → {:ctx ... :ai-model ...}
(require '[psi.agent-session.core :as s])
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.agent-session/phase :psi.agent-session/session-id])

;; Extension UI state
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.ui/widgets :psi.ui/statuses :psi.ui/visible-notifications
   :psi.ui/dialog-queue-empty? :psi.ui/tool-renderers])

;; Live turn state (during streaming)
(require '[psi.agent-session.turn-statechart :as turn])
(when-let [a (:turn-ctx-atom (:ctx @psi.agent-session.main/session-state))]
  (turn/query-turn @a))
;; → {:psi.turn/state :text-accumulating :psi.turn/text "..." ...}
```

## TUI Session: Resolved

**Problem**: Custom `ProcessTerminal` with `stty -echo raw` + manual
ANSI differential rendering had cursor position desync on macOS.

**Fix**: Replaced entire TUI layer with charm.clj (Elm Architecture).
charm.clj uses JLine3 (`jline-terminal-ffm`) which handles raw mode,
input parsing, and cursor positioning correctly.

**Architecture**: `psi.tui.app` — init/update/view functions.
Agent runs in future → `LinkedBlockingQueue` → poll command → message.
Spinner driven by poll ticks (no separate timer thread).

**JLine compat note**: charm.clj v0.1.42 has a bug in
`bind-from-capability!` (expects `char[]` but JLine 3.30+ returns
`String`). Patched via `alter-var-root` at namespace load time.
Caught by `jline-terminal-keymap-test` smoke test.

## agent-session namespaces

| Namespace                       | Role                                              |
|---------------------------------|---------------------------------------------------|
| `core.clj`                      | Public API, create-context, global wrappers       |
| `statechart.clj`                | Session statechart (idle/streaming/compacting/retrying) |
| `session.clj`                   | AgentSession data model, malli schemas            |
| `skills.clj`                    | Skill discovery, validation, prompt formatting    |
| `system_prompt.clj`             | System prompt assembly (tools, context, skills)   |
| `compaction.clj`                | Compaction algorithm (stub, injectable fn)        |
| `extensions.clj`                | Extension registry, loader, API, tool wrapping    |
| `tui/extension_ui.clj`         | Extension UI: dialogs, widgets, status, notifications, renderers |
| `persistence.clj`               | Append-only journal                               |
| `resolvers.clj`                 | EQL resolvers (:psi.agent-session/*, :psi.skill/*, :psi.ui/*) |
| `tools.clj`                     | Built-in tool implementations                     |
| `turn_statechart.clj`           | Per-turn streaming statechart (idle→text⇄tool→done) |
| `executor.clj`                  | ai ↔ agent-core streaming bridge (statechart-driven) |
| `main.clj`                      | Interactive REPL loop + TUI session (-main)       |
| `oauth/pkce.clj`                | PKCE verifier + S256 challenge (JDK crypto)       |
| `oauth/callback_server.clj`     | Local HTTP callback server (Nullable: null server) |
| `oauth/store.clj`               | Credential storage, priority chain (Nullable: in-memory) |
| `oauth/providers.clj`           | Provider registry + Anthropic OAuth impl          |
| `oauth/core.clj`                | Top-level OAuth API (Nullable: stub providers)    |

## Test Status

148/148 ERT tests passing for `components/emacs-ui` after dedicated input area + input history updates (`bb emacs:test`).

## Specs

| Spec file                  | Component mapping       | Status                                |
|----------------------------|-------------------------|---------------------------------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented (Allium v2 migrated)    |
| `agent.allium`             | `agent-core`            | ✓ implemented (Allium v2 migrated)    |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented                         |
| `coding-agent.allium`      | `agent-session`         | ✓ split → 3 sub-specs; ✓ implemented  |
| `tools.allium`             | `agent-session/tools`   | ◇ target contracts (runtime policy + read/bash/edit/write/ls/find/grep/app-query-tool + path resolution + introspection) |
| `tool-output-handling.allium` | `agent-session/tools` | ◇ compatibility index (superseded by tools/*.allium) |
| `skills.allium`            | `agent-session/skills`  | ✓ implemented                         |
| `tui.allium`               | `tui`                   | ◇ partial — core session loop working; streaming/tool-status UX still open (Allium v2 migrated) |
| `ui-extension-points.allium` | `tui/extension_ui`    | ✓ implemented                         |
| `oauth-auth.allium`        | `agent-session/oauth`   | ✓ implemented (Anthropic provider)    |
| `graph-emergence.allium`   | `query` + `introspection` | ◇ Step 7 spec authored (attribute links implicit; mutation side-effects deferred) |
| `memory-layer.allium`      | `query` + `history` + `introspection` | ◇ Step 10 spec authored (provenance, graph snapshots/deltas, recovery over session+history+graph) |
| `memory-backing-stores.allium` | `memory` | ✓ phase 1 implemented (provider contract + selection/fallback + `:psi.memory.store/*` EQL surface) |
| `memory-datalevin-store.allium` | `memory` | ✓ phase 2 implemented (Datalevin provider + write-through/hydration + runtime retention/migration hardening + provider failure telemetry surface) |
| `remember-capture.allium` | `memory` + `introspection` + `engine` | ◇ Step 10 spec authored (manual remember capture + memory writeback) |

## Step 7 Decisions (Spec)

- Source: `spec/graph-emergence.allium`
- Attribute links stay implicit on `:psi.graph/edges` (edge metadata), not first-class graph nodes
- Mutation side-effects are deferred in Step 7 (`sideEffects = nil`) — capability graph is IO-link based for now

## Step 10 Decisions (Memory Spec)

- Source: `spec/memory-layer.allium`
- Recovery ranking defaults: text relevance 50%, recency 25%, capability proximity 25%
- Graph history retention: fixed-window compaction (keep latest 200 snapshots and 1000 deltas), trim oldest
- No graph-history summary entities in Step 10 (defer richer compaction/summarization)

## Step 9a Decisions (Spec)

- Sources: `spec/memory-backing-stores.allium`, `spec/memory-datalevin-store.allium`
- Default active memory store remains `in-memory` for backward compatibility
- Persistent stores are selected via provider registry; one active provider at a time
- Runtime can opt into Datalevin via `PSI_MEMORY_STORE=datalevin`
- remember/recover/graph artifacts now write-through to active provider; activation hydrates persisted records/snapshots/deltas/recoveries back into memory state
- Fallback policy defaults to automatic in-memory fallback when persistent provider is unavailable
- Runtime memory config is now available via CLI/env (store selection, fallback mode, history limit, retention limits)
- Datalevin open now enforces schema-version checks and optional migration hooks
- Provider operation telemetry is surfaced in store summaries/EQL (`write-count`, `read-count`, `failure-count`, `last-error`, `:psi.memory.store/last-failure`)
- Operator docs now cover fallback triage, retention windows, and migration-hook wiring (`README.md`)

## Step 10 Status

- ✓ Step 10 acceptance checklist is complete (manual remember capture semantics, telemetry, blocked/fallback paths, cross-surface parity, end-to-end visibility).

## Step 12b Status (Background Tool Jobs)

- ◇ Spec drafted: `spec/background-tool-jobs.allium`
- ◇ Test matrix drafted: `doc/background-tool-jobs-test-matrix.md`
- Captured behavior includes:
  - dual-mode tool invocation (`sync` result vs `background` job start)
  - `job-id` only for async/background starts
  - in-memory-only tracking with process-restart loss
  - thread-scoped controls with globally unique `job-id`
  - terminal-only synthetic assistant injection at turn boundaries
  - one message per terminal job, completion-time ordering, at-most-once delivery
  - payload size policy parity with tool output constraints + temp-file spillover
  - default list non-terminal jobs only, manual retry unsupported
  - bounded terminal retention (20/thread) with oldest-terminal eviction

## Step 10 Decisions (Remember Spec)

- Source: `spec/remember-capture.allium`
- Remember scope is manual memory capture only (not automated evolution)
- `/remember` emits a manual signal and writes one memory artifact with current context
- Memory semantics are split explicitly:
  - session memory: short-term, ephemeral working context for current run
  - persistent memory: cross-session, distilled artifacts for future recovery
  - session persistence (`/resume`): partial session transcript/state saved to disk; operational continuity, not memory distillation
  - git history: external/queryable provenance, not mirrored into memory artifacts
- Output becomes input via remember/recover (future ψ reads captured artifacts)
- Store outage behavior is explicit: if memory write-through fails but fallback succeeds, `/remember` returns a visible warning (`⚠ Remembered with store fallback ...`) including provider/error detail when available
- No controller/process cycle model in spec scope

## Canonical Telemetry Attrs (Step 7a)

Top-level EQL attrs for session telemetry — all reliably queryable in-session:

```clojure
[:psi.agent-session/messages-count]    ;; integer — total messages in agent-core
[:psi.agent-session/tool-call-count]   ;; integer — total tool calls made
[:psi.agent-session/start-time]        ;; java.time.Instant — session context creation
[:psi.agent-session/current-time]      ;; java.time.Instant — wall clock now
```

Combined query (mirrors the failing pattern, now fixed):
```clojure
[:psi.agent-session/phase :psi.agent-session/model :psi.agent-session/session-id
 :psi.agent-session/messages-count :psi.agent-session/tool-call-count
 :psi.agent-session/start-time :psi.agent-session/current-time]
```

## Extension Prompt Telemetry Attrs (Step 12a)

Top-level EQL attrs for extension-initiated agent prompt visibility:

```clojure
[:psi.agent-session/extension-last-prompt-source]    ;; string? — extension source id (e.g. "plan-state-learning")
[:psi.agent-session/extension-last-prompt-delivery]  ;; keyword? — :prompt | :deferred | :follow-up
[:psi.agent-session/extension-last-prompt-at]        ;; java.time.Instant? — last extension prompt timestamp
```

Combined query:
```clojure
[:psi.agent-session/extension-last-prompt-source
 :psi.agent-session/extension-last-prompt-delivery
 :psi.agent-session/extension-last-prompt-at]
```

## Remember Telemetry Attrs (Step 10)

Top-level EQL attrs for remember-capture visibility:

```clojure
[:psi.memory.remember/status]           ;; keyword — :idle | :error
[:psi.memory.remember/captures]         ;; vector — remember-sourced memory records (newest first)
[:psi.memory.remember/last-capture-at]  ;; java.time.Instant? — timestamp of newest capture
[:psi.memory.remember/last-error]       ;; any? — last remember-related error marker (nil when none)
```

Combined query:
```clojure
[:psi.memory.remember/status
 :psi.memory.remember/captures
 :psi.memory.remember/last-capture-at
 :psi.memory.remember/last-error]
```

## Memory Store Telemetry Attrs (Step 9.5)

Top-level EQL attrs for store durability/failure introspection:

```clojure
[:psi.memory.store/active-provider-id]          ;; string
[:psi.memory.store/selection]                   ;; fallback + reason
[:psi.memory.store/health]                      ;; active provider health map
[:psi.memory.store/active-provider-telemetry]   ;; write/read/failure counters + last-error
[:psi.memory.store/last-failure]                ;; most recent failure map across providers
[:psi.memory.store/providers]                   ;; provider entries incl. :telemetry
```

Combined query:
```clojure
[:psi.memory.store/active-provider-id
 :psi.memory.store/selection
 :psi.memory.store/health
 :psi.memory.store/active-provider-telemetry
 :psi.memory.store/last-failure
 :psi.memory.store/providers]
```

## Session startup prompts (Step 11)

- Status: ✓ complete
- Spec: `spec/session-startup-prompts.allium`
- Config sources active: `~/.psi/agent/startup-prompts.edn` + `.psi/startup-prompts.edn`
- Repo startup prompt set currently includes one prompt (`engage-nucleus`)
- Implemented behavior:
  - deterministic merge/order with precedence `global < project`
  - startup prompts execute as visible transcript turns during new session bootstrap
  - startup telemetry persisted on session data (`:startup-prompts`, bootstrap started/completed timestamps, startup message ids)
  - startup attrs are top-level EQL attrs and discoverable via graph introspection (`:psi.graph/*`)
  - fork/new-session behavior is explicit and covered by tests (new-session runs bootstrap; fork resets startup telemetry)
- Validation:
  - `psi.agent-session.startup-prompts-test`
  - `psi.agent-session.runtime-startup-prompts-test`
  - `psi.agent-session.resolvers-startup-prompts-test`
  - latest run: 9 tests, 35 assertions, 0 failures

## Open Questions
- TUI: per-token streaming (currently shows spinner until agent done)
- TUI: tool execution status display during agent loop
- Extension UI: should dialogs support auto-dismiss timeout?
- Extension UI: widget ordering when multiple extensions contribute to same placement
- Extension UI: should render fns receive a theme map for consistent styling?
- Extension UI: editor text injection (set-editor-text, paste-to-editor)
- Extension UI: working message override during streaming ("Analyzing..." vs "thinking…")
- Graph emergence: when should mutation side-effects move from IO-only links to structured effect entities?

## nREPL

Port: `8888`

- Δ psl source=53082d2cb72c2dbd354c790256f1e48b5663f717 at=2026-03-06T20:57:20.413334Z :: ⚒ Δ Simplify PSL to agent-prompt flow with extension prompt telemetry λ
