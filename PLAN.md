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

---

## Current: Step 4 — Wire agent-session into global query graph

Register `psi.agent-session.resolvers/all-resolvers` into the global Pathom graph
so `:psi.agent-session/*` attributes are queryable alongside `:psi.system/*`
and `:psi.engine/*`.

Tasks:
- Add `register-resolvers!` / `register-resolvers-in!` to `agent-session.core`
  (matching the pattern in `psi.ai.core`)
- Call `register-resolvers!` from a system startup fn (or `main.clj`)
- Update `introspection` component to include agent-session resolvers in its
  isolated query context
- Add `agent-session` to `introspection/create-context` so isolated contexts
  can wrap a session

---

## Backlog

5. Graph emergence — register domain resolvers, surface capability graph via EQL
6. HTTP API — openapi spec + martian client, surface via Pathom mutations
7. RPC surface — JSON stdio protocol for headless / programmatic control
8. Memory layer — combine query + history + knowledge into queryable memory
9. Feed-forward recursion — AI tooling hooks + FUTURE_STATE
10. AI COMPLETE

### Deferred (agent-session)
- `TreeNavigated` branch tree navigation
- Session persistence to disk (journal currently in-memory only)
- Extension tool wrapping (pre/post hooks on tool calls)
- `RegisteredCommand` / full registry
- `SkillCommandExpanded` / `PromptTemplateExpanded`
- Streaming token printing to stdout (executor currently prints final message only)
