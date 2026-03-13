# Changelog

## 2026-03-13

- λ Δ Added tmux-backed TUI integration harness baseline:
  - Added new spec: `spec/tui-tmux-integration-harness.allium`.
    - Captures harness lifecycle (preflight/start/send/wait/assert/cleanup).
    - Includes one baseline scenario: launch TUI → `/help` marker assertion → `/quit` exit assertion.
  - Added integration test: `components/tui/test/psi/tui/tmux_integration_harness_test.clj`.
    - `^:integration` test uses detached tmux session with unique session names.
    - Readiness assertion waits for prompt marker (`刀:`/`Type a message`).
    - Help assertion checks stable marker: `(anything else is sent to the agent)`.
    - Quit assertion checks pane process transition away from `java`.
    - Always performs best-effort tmux session cleanup and captures pane snapshot on failure.
  - Verification:
    - `clj-kondo --lint components/tui/test/psi/tui/tmux_integration_harness_test.clj`
    - `clojure -M:test --focus psi.tui.tmux-integration-harness-test --skip-meta foo`


- λ Δ TUI live multi-session surface (`/tree`) landed:
  - Added `/tree` command to central dispatcher with two modes:
    - `/tree` opens a session picker.
    - `/tree <session-id|prefix>` performs direct session switch.
  - Runtime gating added via `:supports-session-tree?`:
    - TUI enables `/tree`.
    - Console/RPC return deterministic guidance text.
  - TUI selector now supports `:session-selector-mode` (`:resume|:tree`):
    - `:resume` uses persisted session listing.
    - `:tree` uses live host snapshot attrs (`:psi.agent-session/host-active-session-id`, `:psi.agent-session/host-sessions`).
  - Added TUI switch callback path (`switch-session-fn!`) wired to `session/ensure-session-loaded-in!` + transcript/tool rehydrate.
  - Added/updated tests across commands + TUI + runtime + RPC for `/tree` behavior and gating.
  - Verification:
    - `clojure -M:test --focus psi.agent-session.commands-test --focus psi.tui.app-test --focus psi.agent-session.main-test --focus psi.agent-session.rpc-test`
    - 166 tests, 736 assertions, 0 failures.

- λ Δ TUI `/tree` hierarchy polish shipped:
  - Host session picker now orders rows by parent/child tree structure (not raw host insertion order).
  - Tree rows now use explicit glyphs: root marker `●`, branch connectors `├─` / `└─`, and carry-through `│` for nested siblings.
  - Right-side status cells are now column-aligned (`[active] [stream]`) to keep mixed-state rows visually stable.
  - Added focused TUI regression coverage to lock hierarchy order, connector rendering, and status-column alignment.
  - Verification:
    - `clojure -M:test --focus psi.tui.app-test`
    - 77 tests, 210 assertions, 0 failures.

- λ Δ Multi-session route-lock isolation hardened for exclusive session lifecycle ops:
  - Added explicit exclusive route-lock op class in RPC: `new_session`, `switch_session`, `fork`.
  - When `:enforce-session-route-lock?` is enabled and a prompt is in-flight, these lifecycle ops now fail fast with canonical `request/session-routing-conflict` (including inflight/target session ids), even for same-session targets.
  - Refactored route-lock path into reusable helpers (`valid-target-session-id!`, `maybe-assert-route-lock!`) and applied guard at dispatch boundary for exclusive ops.
  - Added RPC regression test: `exclusive ops are rejected while prompt is in-flight when lock enforcement is enabled`.
  - Verification:
    - `clojure -M:test --focus psi.agent-session.rpc-test` (35 tests, 336 assertions, 0 failures)
    - `clojure -M:test --focus psi.agent-session.core-test/send-workflow-event-track-background-job-gated-test --focus psi.agent-session.rpc-test/rpc-prompt-passes-resolved-api-key-to-agent-loop-test` (2 tests, 7 assertions, 0 failures)

- λ Δ Multi-session persistence locking + session decision convergence:
  - Updated session specs with elicited decisions:
    - `spec/session-core.allium`: fork default prompt inheritance is explicit.
    - `spec/session-forking.allium`: soft isolation, merge-back-as-separate-capability, optional fork budgets.
    - `spec/session-persistence.allium`: cross-process file locking required.
  - Implemented cross-process locking in session persistence write path (`components/agent-session/src/psi/agent_session/persistence.clj`):
    - added exclusive sidecar lock strategy (`<session-file>.lock`) with bounded retry for all writes (`write-header!`, `flush-journal!`, append path).
    - lock acquisition failure now throws explicit `ExceptionInfo` with lock/session context.
  - Added persistence regression coverage (`components/agent-session/test/psi/agent_session/persistence_test.clj`):
    - `session-file-locking-test` verifies write fails while lock is externally held.
  - Verification:
    - `clojure -M:test --focus psi.agent-session.persistence-test` (12 tests, 75 assertions, 0 failures)
    - `clojure -M:test --focus psi.agent-session.core-test` (32 tests, 272 assertions, 0 failures)

## 2026-03-12

- λ Δ Added optional subagent session-forking capability:
  - `subagent` tool `action=create` now accepts `fork_session` (boolean, default `false`)
  - when `fork_session=true`, subagent starts with invoking session conversation history (`user`/`assistant`/`toolResult` messages)
  - validation added: `fork_session` must be boolean and is rejected for non-`create` actions
- Δ Added slash command fork flag support: `/sub [--fork|-f] [@agent] <task>`
- Δ Updated subagent extension spec (`spec/subagent-widget-extension.allium`) with fork-session args/rules and surface guidance.
- Δ Updated `META.md` to record subagent optional fork inheritance in session semantics.

## 2026-03-06

- λ Δ Added `spec/git-worktrees.allium` and Step 11a acceptance checklist in `PLAN.md`.
- λ Δ Implemented git worktree query surface:
  - history attrs: `:git.worktree/list`, `:git.worktree/current`, `:git.worktree/count`, `:git.worktree/inside-repo?`
  - session bridge attrs: `:psi.agent-session/git-worktrees`, `:psi.agent-session/git-worktree-current`, `:psi.agent-session/git-worktree-count`
- Δ Added `/worktree` slash command and `/status` worktree identity surfacing.
- Δ Added graph/introspection coverage tests for worktree resolvers/attrs.
- Δ Added worktree parse-failure telemetry marker (`git.worktree.parse_failed`) with degradation-to-empty behavior and tests.
- Δ Hardened tests to avoid writing repo `.psi/project.edn` by using temp cwd in agent-session/introspection test contexts.
