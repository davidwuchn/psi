Initialized from user request on 2026-04-19.

2026-04-19
- Captured design decision for task 023: extend `psi-tool` with a dedicated `project-repl` action rather than introducing generic mutation execution.
- Refined the public request surface to stay worktree-oriented: explicit `worktree-path` when present, otherwise invoking session worktree from bound tool context, otherwise explicit error.
- Chose status-tagged result payloads for project-REPL operations and added concrete request/result/error examples in `design.md`.
- Updated `plan.md` and `steps.md` to reflect the chosen implementation slices.
- Implemented shared machine-oriented project nREPL ops in `components/agent-session/src/psi/agent_session/project_nrepl_ops.clj` so `psi-tool` and `/project-repl` can share the same domain behavior.
- Extended `psi-tool` schema, validation, telemetry args, truncation metadata, and execution flow to support `action: "project-repl"` with explicit `op` and structured `#:psi-tool{...}` reports.
- Aligned `/project-repl` command handlers to call the shared ops helpers while keeping command-specific text formatting local to the command layer.
- Updated system-prompt examples, README, and `doc/project-nrepl.md` so the new `psi-tool(action: "project-repl", ...)` surface is discoverable.
- Added focused tests for project-repl validation, targeting, structured result shapes, error shaping, and command-layer reuse of shared ops helpers.
