Goal: extend `psi-tool` with runtime self-modification capabilities so psi can reload itself and execute arbitrary in-process psi code from the live runtime, including when the target context should be resolved from a worktree path that differs from the current process cwd.

Context:
- `psi-tool` is the live runtime introspection tool and is the natural place for self-diagnostic and self-directed runtime operations.
- Current guidance already treats `psi-tool` and nREPL as core introspection/debug surfaces.
- There is existing support for reloading model definitions from disk (`/reload-models`), but not an equivalent runtime-facing way for psi to reload its own code through `psi-tool`.
- There is also no canonical `psi-tool` capability for deliberately executing arbitrary psi in-process code as a runtime operation.
- Current work often spans multiple worktrees and child sessions whose canonical `:worktree-path` may differ from the process cwd.
- Prior learning in the repo explicitly notes that code reload and live graph reload are distinct concerns; a successful code reload does not automatically prove that the active runtime graph surface has been refreshed coherently.

Problem statement:
`psi-tool` currently cannot trigger psi code reload as a runtime operation, cannot execute arbitrary psi in-process code as a first-class runtime capability, and has no canonical self-modification path that is explicitly aware of worktree-path context when that context differs from the process cwd.

Why this matters:
- psi increasingly needs to diagnose and repair itself from within a live session.
- A self-reload path is high leverage for iterative development, debugging, and live verification.
- Direct in-process execution is also high leverage for inspection, repair, migration, and recovery workflows that are more surgical than a full reload.
- When working across multiple worktrees, using the process cwd as the implicit reload or execution root is not sufficient; the intended target may need to come from the session’s canonical `:worktree-path` or another explicitly supplied worktree path.
- Without a canonical tool-level runtime execution surface, self-modification behavior remains indirect, manual, and prone to targeting the wrong source tree or the wrong running context.

Required capability at the task level:
- `psi-tool` must gain a way to request psi code reload from the running system.
- `psi-tool` must also gain a way to request arbitrary psi in-process code execution as an explicit runtime operation.
- Reload and execution requests must be able to operate against a target worktree that is not merely the current process cwd when path-sensitive loading/resolution matters.
- The runtime must expose a clear contract for what path is used as reload/execution context:
  - current session worktree-path
  - explicitly requested worktree-path
  - or other canonical runtime targeting semantics
- The result must be visible and diagnosable from the live tool surface rather than hidden behind external manual steps.

Important nuance:
- This task is not only about reloading source files.
- It also covers explicit in-process code execution as a runtime capability.
- The runtime-facing contract must account for the difference between:
  - code execution succeeding
  - code reload succeeding
  - live graph/runtime surfaces reflecting the resulting changes coherently
- The eventual solution must therefore be shaped as a psi runtime capability, not merely a shell escape or undocumented developer shortcut.

Observed gap in the current system:
- `psi-tool` currently executes EQL queries only.
- It has no runtime operation for psi code reload.
- It has no runtime operation for arbitrary psi in-process code execution.
- Existing reload support in the repo is focused on model definitions and external/manual development flows, not on a canonical live self-reload capability exposed through `psi-tool`.
- Current session/worktree semantics have already been tightened elsewhere in the system, so reload/execution targeting should follow canonical worktree-path ownership rather than falling back ambiguously to cwd.

Desired user-visible behavior at the task level:
- psi can request its own reload from a live session without leaving the session
- psi can request arbitrary psi in-process code execution from a live session
- when needed, psi can target reload/execution against a different worktree-path than the process cwd
- the result reports enough information to tell what was targeted and whether the runtime accepted/performed the requested operation
- the behavior is explicit enough that psi does not silently execute or reload against the wrong tree or runtime context

Non-goals:
- do not treat this as only a shell-command convenience wrapper
- do not assume cwd is always the correct reload or execution target
- do not collapse arbitrary in-process execution, code reload, and graph reload into one undocumented implicit behavior unless the contract explicitly says so
- do not broaden this task into a full hot-reload redesign for every runtime subsystem
- do not require manual external REPL operations as the primary intended workflow once this capability exists

Acceptance:
- `psi-tool` has a canonical way to request psi code reload from the live runtime
- `psi-tool` has a canonical way to request arbitrary psi in-process code execution from the live runtime
- reload/execution targeting can use a worktree-path other than the current process cwd
- the runtime contract makes the target path and operation type explicit and diagnosable
- the resulting behavior is documented and covered by tests, including cross-worktree-path targeting semantics
