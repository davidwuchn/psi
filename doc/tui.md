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

`/status` `/history` `/new` `/resume` `/tree [session-id]` `/help` `/quit` `/skills` `/prompts` `/remember [text]`
`/skill:<name>` plus any extension commands

### Multi-session commands

- `/resume` opens the persisted-session picker (session files on disk).
- `/tree` opens the live host session picker (in-process multi-session view).
- `/tree <session-id|prefix>` switches directly to a live host session by id.
- In `/tree` picker mode, sessions render in parent/child order with explicit tree
  glyphs (root `●`, branch connectors `├─` / `└─` / `│`).
- Right-side status cells are column-aligned across rows (`[active] [stream]`) to
  keep deep trees and mixed session states visually stable.

`/tree` is TUI-only; console/RPC surfaces return a deterministic guidance message.

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
