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
| `tui`           | ✓      | charm.clj Elm Architecture, JLine3, extension UI state     |
| `agent-session` | ✓      | Session ✓, extensions ✓, extension UI ✓, main REPL ✓, TUI ✓, OAuth ✓ |

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
- ✓ Turn statechart (per-turn streaming state, EQL queryable)
- ✓ Runnable entry point (`clojure -M:run`)
- ✓ TUI session (`--tui` flag) — charm.clj Elm Architecture, JLine3
- ✓ Extension system (Clojure extensions, loader, API, tool wrapping, EQL introspection)
- ✓ Extension UI (dialogs, widgets, status, notifications, render registry, EQL introspection)
- ✓ OAuth module (PKCE, callback server, credential store, provider registry, Anthropic)
- ✗ OAuth wired into main.clj (replace env-var-only auth)
- ✗ /login and /logout commands
- ✗ Session resolvers wired into global query graph
- ✗ Graph emergence from domain resolvers
- ✗ RPC / HTTP API surface
- ✗ AI COMPLETE

## Runnable

```bash
ANTHROPIC_API_KEY=sk-... clojure -M:run
clojure -M:run --model claude-3-5-sonnet
clojure -M:run --model gpt-4o --tui
clojure -M:run --nrepl                   # random port, printed at startup
clojure -M:run --nrepl 7888              # specific port
clojure -M:run --tui --nrepl             # TUI + nREPL for live introspection
```

In-session commands: `/status`, `/history`, `/new`, `/help`, `/quit`, `/exit`, `/skills`, `/prompts`, `/skill:<name>`, plus extension commands

nREPL introspection (from connected REPL):
```clojure
@psi.agent-session.main/session-state   ;; → {:ctx ... :ai-model ...}
(require '[psi.agent-session.core :as s])
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.agent-session/phase :psi.agent-session/session-id])

;; Extension UI state
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.ui/widgets :psi.ui/statuses :psi.ui/visible-notifications
   :psi.ui/dialog-queue-empty? :psi.ui/tool-renderers])

;; Live turn state (during streaming)
(require '[psi.agent-session.turn-statechart :as turn])
(when-let [a (:turn-ctx-atom (:ctx @psi.agent-session.main/session-state))]
  (turn/query-turn @a))
;; → {:psi.turn/state :text-accumulating :psi.turn/text "..." ...}
```

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
| `skills.clj`                    | Skill discovery, validation, prompt formatting    |
| `system_prompt.clj`             | System prompt assembly (tools, context, skills)   |
| `compaction.clj`                | Compaction algorithm (stub, injectable fn)        |
| `extensions.clj`                | Extension registry, loader, API, tool wrapping    |
| `tui/extension_ui.clj`         | Extension UI: dialogs, widgets, status, notifications, renderers |
| `persistence.clj`               | Append-only journal                               |
| `resolvers.clj`                 | EQL resolvers (:psi.agent-session/*, :psi.skill/*, :psi.ui/*) |
| `tools.clj`                     | Built-in tool implementations                     |
| `turn_statechart.clj`           | Per-turn streaming statechart (idle→text⇄tool→done) |
| `executor.clj`                  | ai ↔ agent-core streaming bridge (statechart-driven) |
| `main.clj`                      | Interactive REPL loop + TUI session (-main)       |
| `oauth/pkce.clj`                | PKCE verifier + S256 challenge (JDK crypto)       |
| `oauth/callback_server.clj`     | Local HTTP callback server (Nullable: null server) |
| `oauth/store.clj`               | Credential storage, priority chain (Nullable: in-memory) |
| `oauth/providers.clj`           | Provider registry + Anthropic OAuth impl          |
| `oauth/core.clj`                | Top-level OAuth API (Nullable: stub providers)    |

## Test Status

290 tests, 1243 assertions, 0 failures. 0 clj-kondo errors.

## Specs

| Spec file                  | Component mapping       | Status                                |
|----------------------------|-------------------------|---------------------------------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented                         |
| `agent.allium`             | `agent-core`            | ✓ implemented                         |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented                         |
| `coding-agent.allium`      | `agent-session`         | ✓ split → 3 sub-specs; ✓ implemented  |
| `skills.allium`            | `agent-session/skills`  | ✓ implemented                         |
| `tui.allium`               | `tui`                   | partial — session loop not yet working|
| `ui-extension-points.allium` | `tui/extension_ui`    | ✓ implemented                         |
| `oauth-auth.allium`        | `agent-session/oauth`   | ✓ implemented (Anthropic provider)    |

## Open Questions

- TUI: per-token streaming (currently shows spinner until agent done)
- TUI: tool execution status display during agent loop
- Extension UI: should dialogs support auto-dismiss timeout?
- Extension UI: widget ordering when multiple extensions contribute to same placement
- Extension UI: should render fns receive a theme map for consistent styling?
- Extension UI: editor text injection (set-editor-text, paste-to-editor)
- Extension UI: working message override during streaming ("Analyzing..." vs "thinking…")

## nREPL

Port: `8888`
