# Project nREPL

Direct project REPL support lets psi start, attach to, and interact with a
worktree-bound project-local nREPL as a first-class runtime capability.

This is distinct from psi's own runtime nREPL.

## Two different nREPLs

### Runtime nREPL

The CLI flag:

```bash
clojure -M:psi --nrepl 7888
```

starts an nREPL for **psi itself**.

Use that when you want live introspection/debugging of the psi process.

### Project nREPL

`/project-repl ...` targets a **project/worktree runtime** that psi manages for
actual development work in the current session worktree.

Use that when you want to evaluate code in the target project.

## Targeting semantics

Project nREPL instances are keyed by canonical absolute `worktree-path`.

Target precedence is:

1. explicit target worktree/path when an internal API supplies one
2. invoking session `:worktree-path`
3. otherwise: explicit error

Project nREPL targeting does **not** use process cwd fallback or hidden frontend
focus inference as the primary semantic input.

## Acquisition modes

Psi supports two acquisition modes.

### Started mode

Psi launches a configured start command vector in the target worktree.

Configuration lives in existing psi config files:

- user: `~/.psi/agent/config.edn`
- project: `<worktree>/.psi/project.edn`

Canonical shape:

```clojure
{:agent-session
 {:project-nrepl
  {:start-command ["bb" "nrepl-server"]}}}
```

Rules:

- `:start-command` must be a non-empty vector of strings
- first element is the command path/name
- remaining elements are args in order
- psi starts the process in the effective target worktree
- psi discovers the endpoint from `<worktree>/.nrepl-port`
- the older nested `:started :command-vector` shape is no longer the canonical or supported config surface

### Attach mode

Psi connects to an already-running nREPL endpoint.

Config shape:

```clojure
{:agent-session
 {:project-nrepl
  {:attach {:host "127.0.0.1"
            :port 7888}}}}
```

Rules:

- `:port` is explicit when provided
- `:host` is optional and defaults to `127.0.0.1`
- if explicit port is absent, psi falls back to `<worktree>/.nrepl-port`
- attach remains explicitly bound to the target worktree in psi state

## First-slice session model

Current direct project REPL support uses:

- one managed project nREPL instance per worktree
- one managed nREPL client session per instance
- single-flight eval per worktree instance
- interrupt targeted at the active eval operation id

## Commands

Current shared command surface:

- `/project-repl` — show current project nREPL status for the session worktree
- `/project-repl start` — start configured project nREPL for the session worktree
- `/project-repl attach` — attach using configured endpoint or `.nrepl-port`
- `/project-repl stop` — stop/detach the managed project nREPL for the session worktree
- `/project-repl eval <code>` — evaluate code in the managed project nREPL
- `/project-repl interrupt` — interrupt the active eval when one exists

## `psi-tool` machine surface

`psi-tool` also exposes the managed project REPL directly through:

- `action: "project-repl"`
- `op: "status" | "start" | "attach" | "stop" | "eval" | "interrupt"`

Examples:

```clojure
psi-tool(action: "project-repl", op: "status")
psi-tool(action: "project-repl", op: "start")
psi-tool(action: "project-repl", op: "attach", host: "127.0.0.1", port: 7888)
psi-tool(action: "project-repl", op: "eval", code: "(+ 1 2)")
psi-tool(action: "project-repl", op: "interrupt")
```

Targeting precedence is:

1. explicit `worktree-path`
2. invoking session `worktree-path`
3. otherwise explicit error

`/project-repl` remains the human-oriented command layer.
`psi-tool(action: "project-repl", ...)` is the structured machine-oriented layer.
Both surfaces should share the same underlying project nREPL behavior.

## Queryable state

The managed project nREPL is queryable through EQL.

Root attrs:

- `:psi.project-nrepl/count`
- `:psi.project-nrepl/worktree-paths`
- `:psi.project-nrepl/instances`

Session-scoped attr:

- `:psi.agent-session/project-nrepl`

Projected fields include:

- worktree path
- acquisition mode
- transport kind
- lifecycle state
- readiness
- endpoint
- command vector
- session mode
- active session id
- `can-eval?`
- `can-interrupt?`
- `last-error`
- `last-eval`
- `last-interrupt`
- timestamps

## Example query

```clojure
[{:psi.agent-session/project-nrepl
  [:psi.project-nrepl/worktree-path
   :psi.project-nrepl/acquisition-mode
   :psi.project-nrepl/lifecycle-state
   :psi.project-nrepl/readiness
   :psi.project-nrepl/active-session-id
   :psi.project-nrepl/last-eval
   :psi.project-nrepl/last-interrupt]}]
```

## Current limits

Current slice does not yet provide:

- richer transcript-native rendering of streamed eval output
- broader adapter-specific UI affordances beyond shared text commands
- full debugger/editor-middleware feature coverage

But it does provide the canonical managed lifecycle + eval + interrupt + query
surface needed for direct project REPL support.
