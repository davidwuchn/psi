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

```bash
# Bare console
clojure -M:run

# Terminal UI
clojure -M:run --tui
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

For built-in extension docs (`extensions/src`), see:
- [`doc/extensions.md`](doc/extensions.md)

## Architecture

For architecture overview, components, EQL introspection guidance, and roadmap, see:
- [`doc/architecture.md`](doc/architecture.md)

## ψ Psi project config

Project query/config tool details:
- [`doc/psi-project-config.md`](doc/psi-project-config.md)

## References

- [pi-mono](https://github.com/badlogic/pi-mono) — inspiration
- [charm.clj](https://codeberg.org/timokramer/charm.clj) — TUI framework
- [Fulcrologic statecharts](https://github.com/fulcrologic/statecharts)
- [Pathom3](https://pathom3.wsscode.com/)
- [nucleus](https://github.com/michaelwhitford/nucleus)
