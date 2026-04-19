# TUI Guide

This document covers interactive terminal usage for psi (`--tui`).

## Integration harness (tmux)

A baseline tmux-backed integration harness test exists at:
- `components/tui/test/psi/tui/tmux_integration_harness_test.clj`

Reusable harness helpers live at:
- `components/tui/test/psi/tui/test_harness/tmux.clj`

What it validates:
1. start psi TUI in detached tmux (`exec clojure -M:psi --tui`)
2. wait until prompt is ready (`刀:` / `Type a message`)
3. send `/help` and assert help output marker appears
4. send `/quit` and assert pane process exits the Java TUI process

Run it explicitly (integration tests are skipped by default in `tests.edn`):

```bash
clojure -M:test --focus psi.tui.tmux-integration-harness-test --skip-meta foo
```

## Start

```bash
# Basic TUI start
clojure -M:run --tui

# OpenAI example
clojure -M:run --model gpt-4o --tui

# TUI with live nREPL introspection
clojure -M:run --tui --nrepl 8888
```

## OAuth Login (no env var needed)

```text
/login    # browser-based OAuth flow
/logout
```

## In-session commands

`/status` `/history` `/new` `/resume` `/tree [session-id]` `/worktree` `/help` `/quit` `/skills` `/prompts` `/remember [text]`
`/project-repl` `/project-repl start` `/project-repl attach` `/project-repl stop` `/project-repl eval <code>` `/project-repl interrupt`
`/skill:<name>` plus any extension commands such as `/work-on`, `/work-done`, `/work-rebase`, `/work-status`

### Multi-session commands

- `/resume` dispatches to the backend; when selection is needed, the backend requests
  a frontend action and TUI renders the persisted-session picker (session files on disk).
- `/tree` dispatches to the backend; when selection is needed, the backend requests
  a frontend action and TUI renders the live context session picker (in-process multi-session view).
- `/tree <session-id|prefix>` switches directly to a live context session by id.
- `/tree name <session-id|prefix> <name>` assigns an explicit human name to a live context session.
- In `/tree` picker mode, sessions render in parent/child order with explicit tree
  glyphs (root `●`, branch connectors `├─` / `└─` / `│`).
- Right-side status cells are column-aligned across rows (`[active] [stream]`) to
  keep deep trees and mixed session states visually stable.

The selector UI is frontend-native, but candidate lists and command semantics are backend-owned.

### Worktree commands

- `/worktree` shows git worktree context for the current session worktree.
- `/work-on <description>` creates a git worktree + branch at a path derived from the
  current/main worktree layout and moves you into a distinct new context session bound to
  that worktree.
- Worktree extension commands invoked after `/new` operate on the active session that
  issued the command, rather than a previously loaded session.
- `/work-done` completes the current linked worktree onto the default branch.
- It uses the cached default branch for the context.
- If the current branch is not yet fast-forwardable onto the default branch, `/work-done`
  runs a forked sync agent to rebase first.
- If that rebase fails, `/work-done` stops and preserves the worktree.
- On success, `/work-done` fast-forward merges from the main worktree context, removes the
  linked worktree, deletes the branch, and switches back to an existing main-worktree
  session when possible.
- If no main-worktree session exists, `/work-done` creates one and switches to it.
- `/work-rebase` rebases the current linked-worktree branch onto the default branch.
- `/work-status` shows the active linked worktree overview.

Operational notes:
- Worktree placement is derived from the relationship between the current worktree path
  and the main worktree path.
- `/work-on` uses a mechanical slug from the description.
- `/work-done` caches the default branch on init/session switch.
- Tool operations in a worktree session run with that session's `worktree-path`/`worktree_path` as cwd.
- After `/work-done`, the transcript of the linked-worktree session is preserved in the
  context session tree even though the branch/worktree are removed.

`/remember` performs a single manual memory capture from the runtime command surface.
It writes one memory artifact with the provided text (or a default note when blank).

If provider write-through fails but in-memory fallback succeeds, `/remember` returns a
visible warning (not silent success):
- `⚠ Remembered with store fallback`
- includes `provider` when available
- includes `store-error` and `detail` when available

Example warning output:

```text
⚠ Remembered with store fallback
  record-id: 3b31b4d2-4a4b-4fb9-87e2-5f66a8f8f0b1
  provider: failing-store
  store-error: boom
  detail: write failed
```
