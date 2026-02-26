# Plan

Ordered steps toward AI COMPLETE.

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

## Current: Step 5 — Fix TUI session input

### Problem
`clojure -M:run --model gpt-4o --tui` starts and renders but typing
produces no visible output. Diagnosis in STATE.md.

### Root cause chain
1. `ProcessTerminal` uses `stty -echo raw` via subprocess + `/dev/tty`
   redirect. This correctly puts the tty into raw mode.
2. BUT: with `-opost` (raw mode disables output post-processing) the
   terminal emulator's local echo still echoes keystrokes — stty -echo
   suppresses the tty layer echo but not the PTY/emulator layer.
3. More critically: the differential renderer (`do-render!`) assumes the
   hardware cursor is at the end of the last rendered line. When the
   terminal emulator echoes a character, the cursor advances. The next
   differential render uses `\u001b[NA` (move up N lines) — but the
   cursor is now on a different line/column, so the render overwrites
   the wrong screen region.
4. End result: the screen flickers or overwrites itself incorrectly,
   making typed text invisible.

### Proposed fix: absolute cursor positioning
Replace the differential renderer's relative cursor movement
(`\u001b[NA` move-up) with absolute cursor positioning (`\u001b[R;CH`).
After each render, record the screen row where rendering started.
On next render, move to that absolute row rather than moving up N lines.

**Approach**:
- Track `render-start-row` in TUI state (query it via `\u001b[6n` DSR,
  or maintain a counter from the known terminal height)
- Before differential render: emit `\u001b[{start-row}H` to jump to the
  absolute start row of the TUI block, then erase-down, then write all
  lines
- This is immune to cursor position desync from external echo

### Alternative fix: full clear + rewrite on every tick
Simpler: always do `\u001b[2J\u001b[H` (clear screen, home cursor)
before writing the full content. Eliminates differential rendering
entirely. More flicker but guaranteed correct. Good enough to unblock.

### Alternative fix: use JLine3
Replace `ProcessTerminal` with JLine3 (`org.jline/jline`), which
handles raw mode, resize events, and cursor positioning correctly on
macOS JVM. Well-tested, widely used (Clojure REPL uses it). Adds a
dependency but removes all the raw-mode brittle code.

**Recommended path**: JLine3 for correctness, then revisit if
the dependency is unwanted.

### Decision needed from 刀
Which approach?
- (a) Absolute cursor positioning (fix differential renderer)
- (b) Full clear + rewrite per tick (simplest, some flicker)
- (c) JLine3 (correct, adds dep)

---

## Backlog

6. Step 6 — Graph emergence
   Register domain resolvers, surface capability graph via EQL

7. Step 7 — HTTP API
   openapi spec + martian client, surface via Pathom mutations

8. Step 8 — RPC surface
   JSON stdio protocol for headless / programmatic control

9. Step 9 — Memory layer
   Combine query + history + knowledge into queryable memory

10. Step 10 — Feed-forward recursion
    AI tooling hooks + FUTURE_STATE

11. AI COMPLETE

### Deferred (agent-session)
- `TreeNavigated` branch tree navigation
- Session persistence to disk (journal currently in-memory only)
- Extension tool wrapping (pre/post hooks on tool calls)
- `RegisteredCommand` / full registry
- `SkillCommandExpanded` / `PromptTemplateExpanded`
- Streaming token printing during TUI session
