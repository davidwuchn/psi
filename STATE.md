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
| `tui`           | ✓      | charm.clj Elm Architecture, JLine3 terminal               |
| `agent-session` | ✓      | Session ✓, main REPL ✓, TUI session ✓ (charm.clj)        |

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
- ✓ TUI session (`--tui` flag) — charm.clj Elm Architecture, JLine3
- ✗ Session resolvers wired into global query graph
- ✗ Graph emergence from domain resolvers
- ✗ RPC / HTTP API surface
- ✗ AI COMPLETE

## Runnable

```bash
ANTHROPIC_API_KEY=sk-... clojure -M:run
clojure -M:run --model claude-3-5-sonnet
clojure -M:run --model gpt-4o --tui     # starts but typing does not appear
```

In-session commands (plain mode): `/status`, `/history`, `/new`, `/help`, `/quit`

## TUI Session: Resolved

**Problem**: Custom `ProcessTerminal` with `stty -echo raw` + manual
ANSI differential rendering had cursor position desync on macOS.

**Fix**: Replaced entire TUI layer with charm.clj (Elm Architecture).
charm.clj uses JLine3 (`jline-terminal-ffm`) which handles raw mode,
input parsing, and cursor positioning correctly.

**Architecture**: `psi.tui.app` — init/update/view functions.
Agent runs in future → `LinkedBlockingQueue` → poll command → message.
Spinner driven by poll ticks (no separate timer thread).

**JLine compat note**: charm.clj v0.1.42 has a bug in
`bind-from-capability!` (expects `char[]` but JLine 3.30+ returns
`String`). Patched via `alter-var-root` at namespace load time.
Caught by `jline-terminal-keymap-test` smoke test.

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
| `main.clj`                      | Interactive REPL loop + TUI session (-main)       |

## Test Status

161 tests, 561 assertions, 0 failures. 0 clj-kondo warnings. 0 clojure-lsp diagnostics.

## Specs

| Spec file                  | Component mapping       | Status                                |
|----------------------------|-------------------------|---------------------------------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented                         |
| `agent.allium`             | `agent-core`            | ✓ implemented                         |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented                         |
| `coding-agent.allium`      | `agent-session`         | ✓ split → 3 sub-specs; ✓ implemented  |
| `tui.allium`               | `tui`                   | partial — session loop not yet working|

## Open Questions

- TUI: per-token streaming (currently shows spinner until agent done)
- TUI: tool execution status display during agent loop

## nREPL

Port: `8888`
