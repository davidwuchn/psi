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

### Step 5 — Fix TUI session input  ✓
- Replaced custom ProcessTerminal + differential renderer with charm.clj
- charm.clj uses JLine3 (FFM) for correct raw mode + input + rendering
- `psi.tui.app`: Elm Architecture (init/update/view)
- Agent integration via `LinkedBlockingQueue` + poll command
- Patched charm.clj JLine compat bug (`bind-from-capability!`)
- JLine smoke test catches API compat issues
- 161 tests, 561 assertions, 0 failures

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
