# Architecture

```text
Engine (statecharts) → substrate
Query  (EQL/Pathom3) → capability surface
AI     (providers)   → streaming LLM layer
Agent  (statechart)  → per-turn lifecycle
TUI    (charm.clj)   → Elm Architecture terminal UI
```

## Components

| Component       | Role                                              |
|-----------------|---------------------------------------------------|
| `engine`        | Statechart infrastructure, system state           |
| `query`         | Pathom3 EQL registry, `query-in`                  |
| `ai`            | Provider streaming, model registry (Anthropic, OpenAI) |
| `agent-core`    | LLM agent lifecycle statechart + EQL resolvers    |
| `agent-session` | Full coding-agent session: tools, extensions, OAuth, TUI |
| `history`       | Git log resolvers                                 |
| `introspection` | Engine queries itself — self-describing graph     |
| `tui`           | JLine3 + charm.clj terminal UI, extension UI points |

## EQL Introspection Tips

- Query only attributes that exist in the graph; unknown attrs can cause the whole `app-query-tool` request to fail.
- For the active system prompt, use:
  - `[:psi.agent-session/system-prompt]`
- For runtime UI surface detection (extension/UI branching), use:
  - `[:psi.agent-session/ui-type]`  ; `:console` | `:tui` | `:emacs`
- For prompt sizing (chars + estimated tokens), use:
  - `[{:psi.agent-session/request-shape [:psi.request-shape/system-prompt-chars :psi.request-shape/estimated-tokens :psi.request-shape/total-chars]}]`
- Anthropic prompt caching is session policy projected into request shape:
  - session state stores `:cache-breakpoints` such as `:system` and `:tools`
  - executor projects those into conversation `:system-prompt-blocks` / tool `:cache-control`
  - the Anthropic provider emits `cache_control` only for supported directives (`{:type :ephemeral}`)
- Avoid non-existent attrs like `:psi.agent-session/prompt`, `:psi.agent-session/instructions`, `:psi.agent-session/messages` unless resolvers are added for them.

## Roadmap

- ✓ Engine + Query substrate
- ✓ AI provider layer (Anthropic, OpenAI)
- ✓ Agent core loop
- ✓ Coding-agent session
- ✓ TUI (charm.clj / JLine3)
- ✓ Extension system + Extension UI
- ✓ OAuth (PKCE, Anthropic, OpenAI)
- ✓ Git history resolvers
- ✓ Session persistence
- ◇ HTTP API (openapi + martian)
