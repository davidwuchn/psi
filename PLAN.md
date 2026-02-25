# Plan

Ordered steps toward AI COMPLETE.

---

## Current: agent-session component

Split `coding-agent.allium` into three sub-specs, then implement.

### Step 1 — Split allium specs  ✓

Produce from `spec/coding-agent.allium`:

- `spec/session-management.allium`  — AgentSession lifecycle, model/thinking,
                                       queued messages, session branching, forking,
                                       tree navigation, persistence
- `spec/extension-system.allium`    — Extension registration, event dispatch,
                                       tool wrapping, commands, flags, shortcuts
- `spec/compaction.allium`          — Context compaction algorithm, branch
                                       summarisation, auto-compaction triggers

### Step 2 — Implement `agent-session` component  ✓

**Polylith-style, no interface.clj**

Namespace layout:
```
components/agent-session/
  deps.edn
  src/psi/agent_session/
    core.clj          — public API, create-context, global wrappers
    statechart.clj    — session statechart (idle/streaming/compacting/retrying)
    session.clj       — AgentSession data model, malli schemas, derived predicates
    compaction.clj    — CompactionPreparation, stub execute-compaction
    extensions.clj    — extension registry, event dispatch (registration order,
                         broadcast, cancel-return blocks)
    resources.clj     — skill/template loading (filesystem discovery deferred)
    persistence.clj   — session entry journal (append-only atom; disk write deferred)
    resolvers.clj     — EQL resolvers: :psi.agent-session/* attributes
  test/psi/agent_session/
    core_test.clj
    session_test.clj
    compaction_test.clj
    extensions_test.clj
    resolvers_test.clj
```

**Reactivity design**: session statechart accepts agent events via an `:on-event`
callback registered into the agent-core context at session creation. Agent fires
callback on each `emit-in!`; session statechart routes `agent-end` through its
own guards (auto-compact threshold, retry config) to decide next state.

**In scope**:
- AgentSession data + session statechart (idle/streaming/compacting/retrying)
- `UserPrompts`, `UserPromptsWhileStreaming`
- `NewSessionStarted`, `SessionResumed`, `SessionForked`
- `ModelSet`, `ModelCycled`, `ThinkingLevelSet`, `ThinkingLevelCycled`
- `ManualCompaction` (stub algorithm, injectable compaction-fn)
- `AutoCompactionTriggered` (reactive via session statechart guard on agent-end)
- `AutoRetryOnTransientError` (retrying state with exponential backoff)
- Extension event dispatch (broadcast, registration order, cancel-return)
- EQL resolvers for all of the above
- Full Nullable pattern (`create-context`, all public fns have `-in` variants)

**Deferred**:
- `TreeNavigated` (branch tree navigation)
- Full resource loading (skill/template filesystem discovery)
- Session persistence to disk
- RPC / JSON stdio surface
- Extension tool wrapping (pre/post hooks on tool calls)
- `RegisteredCommand` / `RegisteredTool` full registry
- `SkillCommandExpanded` / `PromptTemplateExpanded` expansion

### Step 3 — Wire agent-session into system  ✗ (next)

- Add `psi/agent-session` to root `deps.edn` and `tests.edn`
- Register `agent-session` resolvers into global query graph at startup
- Update `introspection` component to surface `:psi.agent-session/*` attributes

---

## Backlog

4. Graph emergence — register domain resolvers, surface capability graph via EQL
5. HTTP API — openapi spec + martian client, surface via Pathom mutations
6. RPC surface — JSON stdio protocol for headless / programmatic control
7. Memory layer — combine query + history + knowledge into queryable memory
8. Feed-forward recursion — AI tooling hooks + FUTURE_STATE
9. AI COMPLETE
