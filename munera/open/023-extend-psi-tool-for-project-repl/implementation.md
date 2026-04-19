2026-04-19
- Captured design decision for task 023: extend `psi-tool` with a dedicated `project-repl` action rather than introducing generic mutation execution.
- Refined the public request surface to stay worktree-oriented: explicit `worktree-path` when present, otherwise invoking session worktree from bound tool context, otherwise explicit error.
- Chose status-tagged result payloads for project-REPL operations and added concrete request/result/error examples in `design.md`.
- Updated `plan.md` and `steps.md` to reflect the chosen implementation slices.
