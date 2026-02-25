# State

Current truth about the Psi system.

---

## Components

| Component       | Status | Notes                                           |
|-----------------|--------|-------------------------------------------------|
| `ai`            | ✓      | Provider streaming, model registry, tested      |
| `engine`        | ✓      | Statechart infra, system state, nullable ctx    |
| `query`         | ✓      | Pathom3 EQL registry, `query-in`, nullable ctx  |
| `agent-core`    | ✓      | LLM agent lifecycle statechart + EQL resolvers  |
| `history`       | ✓      | Git log resolvers, nullable git context         |
| `introspection` | ✓      | Bridges engine + query, self-describing graph   |
| `tui`           | ✓      | Terminal UI components                          |
| `agent-session` | ✗      | **In design** — see PLAN.md                     |

## Architecture Progress

- ✓ Engine (statecharts) substrate
- ✓ Query (EQL/Pathom3) surface
- ✓ AI provider layer
- ✓ Agent core loop
- ✓ Git history resolvers
- ✓ Introspection (engine queries itself)
- ✗ Coding-agent session orchestration (agent-session component)
- ✗ Graph emergence from domain resolvers
- ✗ RPC / HTTP API surface
- ✗ AI COMPLETE

## Specs

| Spec file                  | Component mapping       | Status         |
|----------------------------|-------------------------|----------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented  |
| `agent.allium`             | `agent-core`            | ✓ implemented  |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented  |
| `coding-agent.allium`      | `agent-session`         | ✗ to split → 3 sub-specs, then implement |
| `tui.allium`               | `tui`                   | partial        |

## Open Questions (resolved)

- Extension event ordering → registration order, broadcast (all fire), cancel-return blocks action
- Compaction threshold → global config provides defaults; per-session override allowed
- AgentSession reactivity → session statechart listens to agent events via `:on-event` callback
- RPC surface → deferred (not in agent-session component)

## nREPL

Port: `8888`
