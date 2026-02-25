# State

Current truth about the Psi system.

---

## Components

| Component       | Status | Notes                                                      |
|-----------------|--------|------------------------------------------------------------|
| `ai`            | ✓      | Provider streaming, model registry, tested                 |
| `engine`        | ✓      | Statechart infra, system state, nullable ctx               |
| `query`         | ✓      | Pathom3 EQL registry, `query-in`, nullable ctx             |
| `agent-core`    | ✓      | LLM agent lifecycle statechart + EQL resolvers             |
| `history`       | ✓      | Git log resolvers, nullable git context                    |
| `introspection` | ✓      | Bridges engine + query, self-describing graph              |
| `tui`           | ✓      | Terminal UI components                                     |
| `agent-session` | ✓      | Session statechart + EQL resolvers + main entry point      |

## Architecture Progress

- ✓ Engine (statecharts) substrate
- ✓ Query (EQL/Pathom3) surface
- ✓ AI provider layer
- ✓ Agent core loop
- ✓ Git history resolvers
- ✓ Introspection (engine queries itself)
- ✓ Coding-agent session orchestration (agent-session component)
- ✓ Built-in tools (read, bash, edit, write)
- ✓ Executor (bridges ai streaming → agent-core loop protocol)
- ✓ Runnable entry point (`clojure -M:run`)
- ✗ Session resolvers wired into global query graph
- ✗ Graph emergence from domain resolvers
- ✗ RPC / HTTP API surface
- ✗ AI COMPLETE

## Runnable

```bash
ANTHROPIC_API_KEY=sk-... clojure -M:run
clojure -M:run --model claude-3-5-sonnet
PSI_MODEL=gpt-4o clojure -M:run
```

In-session commands: `/status`, `/history`, `/new`, `/help`, `/quit`

## agent-session namespaces

| Namespace                       | Role                                              |
|---------------------------------|---------------------------------------------------|
| `core.clj`                      | Public API, create-context, global wrappers       |
| `statechart.clj`                | Session statechart (idle/streaming/compacting/retrying) |
| `session.clj`                   | AgentSession data model, malli schemas            |
| `compaction.clj`                | Compaction algorithm (stub, injectable fn)        |
| `extensions.clj`                | Extension registry + broadcast dispatch           |
| `persistence.clj`               | Append-only journal                               |
| `resolvers.clj`                 | EQL resolvers (:psi.agent-session/*)              |
| `tools.clj`                     | Built-in tool implementations                     |
| `executor.clj`                  | ai ↔ agent-core streaming bridge                  |
| `main.clj`                      | Interactive REPL prompt loop (-main)              |

## Test Status

139 tests, 509 assertions, 0 failures. 0 clj-kondo warnings. 0 clojure-lsp diagnostics.

## Specs

| Spec file                  | Component mapping       | Status                                |
|----------------------------|-------------------------|---------------------------------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented                         |
| `agent.allium`             | `agent-core`            | ✓ implemented                         |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented                         |
| `coding-agent.allium`      | `agent-session`         | ✓ split → 3 sub-specs; ✓ implemented  |
| `tui.allium`               | `tui`                   | partial                               |

## Open Questions

None currently.

## nREPL

Port: `8888`
