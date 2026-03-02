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

### Step 7 — Graph emergence
- Spec: `spec/graph-emergence.allium`
- Register domain resolvers/mutations (`ai`, `history`, `agent-session`, `introspection`) into one query graph
- Surface capability graph via EQL (`:psi.graph/nodes`, `:psi.graph/edges`, `:psi.graph/capabilities`, `:psi.graph/domain-coverage`)
- Step 7 decisions:
  - Attribute links remain implicit on edges (`CapabilityEdge.attribute`), not first-class attribute nodes
  - Mutation side-effects are deferred (`DomainOperation.sideEffects = nil`), IO links only for now

### Step 7a — Session introspection hardening
- Add/repair canonical `:psi.agent-session/*` telemetry resolvers so direct EQL queries are reliable in-session
- Target attrs: `:psi.agent-session/messages-count`, `:psi.agent-session/tool-call-count`, `:psi.agent-session/start-time`, `:psi.agent-session/current-time`
- Define one obvious query path (top-level attrs and/or a stable stats map with aliases), document it in `STATE.md`
- Add resolver tests that assert direct query success for each canonical attr
- Verify via live `eql_query` in an interactive session

8. Step 8 — HTTP API
   openapi spec + martian client, surface via Pathom mutations

9. Step 9 — RPC surface
   EDN stdio protocol for headless / programmatic control (including Emacs frontend parity)

10. Step 10 — Memory layer
    - Spec: `spec/memory-layer.allium`
    - Combine query + history + knowledge into queryable memory
    - Capture memory provenance (session/git/graph/op symbols), graph snapshots + deltas
    - Surface memory via EQL (`:psi.memory/*`) with recovery over session + history + graph

11. Step 11 — Feed-forward recursion
    - Spec: `spec/feed-forward-recursion.allium`
    - AI tooling hooks + FUTURE_STATE
    - Recursion loop: observe → plan → approve → execute → verify → learn
    - Guardrails: approval gate, atomic actions, required verification, rollback on verify failure
    - Decisions: no periodic cadence in Step 11; enabled hooks are config-driven; manual approval default with opt-in low-risk auto-approve in trusted local mode

12. AI COMPLETE

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
