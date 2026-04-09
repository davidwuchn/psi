# ψ Psi — A Clojure AI Agent

A self-evolving AI coding agent built in Clojure. Statechart-driven,
EQL-queryable, extensible. Inspired by
[pi-mono](https://github.com/badlogic/pi-mono).


## Values

- Extensions can completely customise the agent.
- Everything is introspectable
- AI provider agnostic
- Minimal builtin behaviour

---

## Quick Start

Define a user alias in `~/.clojure/deps.edn` that points to your local psi clone:

```clojure
{:aliases
 {:psi {:replace-deps {psi/psi {:local/root "/path/to/your/psi-main"}}
        :main-opts ["-m" "psi.main"]}}}
```

Then run psi with that alias:

```bash
# Bare console
clojure -M:psi

# Terminal UI
clojure -M:psi --tui
```

For CLI flags, environment variables, and switch behavior, see:
- [`doc/cli.md`](doc/cli.md)

### Emacs UI usage

For keybindings, rendering behavior, reconnect semantics, and developer checks, see:
- [`doc/emacs-ui.md`](doc/emacs-ui.md)

### TUI usage

For TUI login flow, in-session commands, and runtime behavior, see:
- [`doc/tui.md`](doc/tui.md)

### Built-in Tools

`read` `bash` `edit` `write`

### Extension API

For extension-facing runtime/query details (including memory durability operations), see:
- [`doc/extension-api.md`](doc/extension-api.md)

This includes the preferred workflow public-data display convention for
workflow-backed extensions.

For built-in extension docs (`extensions/src`), see:
- [`doc/extensions.md`](doc/extensions.md)

## Architecture

For architecture overview, components, EQL introspection guidance, and roadmap, see:
- [`doc/architecture.md`](doc/architecture.md)

Current adapter-convergence direction:
- `app-runtime` owns shared selector, footer, session-summary, navigation, context snapshot, and transcript rehydration semantics
- CLI/TUI/RPC/extension-run-fn/startup-default prompt submission now routes through the shared prompt lifecycle (`prompt-in!` or equivalent dispatch-visible submit → prepare → execute-and-record → continue/finish)
- intentionally isolated workflow/ephemeral runtimes may remain executor-owned rather than being forced through the shared session lifecycle
- RPC adapts those shared models to the wire protocol
- RPC handshake is transport-focused; initial `session/updated` / `footer/updated` / `context/updated` event snapshots come through subscribed event paths, with `context/updated` carrying both the snapshot and canonical session-tree widget projection
- explicit `session-id` routing is preferred over adapter-focus inference whenever an operation can carry it

## Graph discovery

For the session-root graph discovery surface (`:psi.graph/*`), canonical discovery
workflow, and graph semantics, see:
- [`doc/graph-surface.md`](doc/graph-surface.md)

For prompt lifecycle introspection summaries and normalized prompt-turn attrs, see:
- [`doc/architecture.md`](doc/architecture.md)

## Configuration

Config file locations, precedence (session > project > user > system), settings
reference, and runtime scoped setters:
- [`doc/configuration.md`](doc/configuration.md)

## ψ Psi project config

Project query/config tool details:
- [`doc/psi-project-config.md`](doc/psi-project-config.md)

## References

- [pi-mono](https://github.com/badlogic/pi-mono) — inspiration
- [charm.clj](https://codeberg.org/timokramer/charm.clj) — TUI framework
- [Fulcrologic statecharts](https://github.com/fulcrologic/statecharts)
- [Pathom3](https://pathom3.wsscode.com/)
- [nucleus](https://github.com/michaelwhitford/nucleus)
