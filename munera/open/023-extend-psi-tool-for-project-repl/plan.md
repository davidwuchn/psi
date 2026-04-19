Approach:
- Extend `psi-tool` with a dedicated `project-repl` action rather than introducing generic mutation execution.
- Keep the public request surface worktree-oriented: explicit `worktree-path` when provided, otherwise invoking session worktree from the bound tool context, otherwise explicit error.
- Reuse the existing managed project nREPL lifecycle/eval/config helpers directly; do not route through `/project-repl` command parsing or command-formatted text.
- Return structured status-tagged payloads in the existing `#:psi-tool{...}` style so tool/programmatic consumers can branch reliably on operation status.
- Keep `/project-repl` as the human-facing command layer, but align it on the same shared domain helpers so behavior stays coherent.

Planned slices:
1. Extend `psi-tool` request validation/schema/docs for `action: "project-repl"` with explicit `op` and operation-specific fields.
2. Extract or define shared project-REPL domain helpers for status/start/attach/stop/eval/interrupt result shaping so commands and tool can share canonical behavior.
3. Implement `psi-tool` project-REPL execution, including target worktree resolution and structured report shaping.
4. Align `/project-repl` command handlers to reuse the same underlying helpers where practical, keeping only command parsing and text formatting in the command layer.
5. Add focused tests for validation, targeting, absent/present status, lifecycle operations, eval, interrupt, and representative error/configuration paths.
6. Update tool descriptions/system prompt/docs so the canonical project-REPL surface is discoverable and its relationship to `/project-repl` is clear.

Key design decisions to preserve:
- no public `session-id` in the request surface
- no process cwd or adapter-focus fallback
- no generic mutation/invocation mode in this task
- status-tagged operation payloads instead of `nil`-based presence signaling

Risks:
- duplicating project-REPL behavior between `psi_tool.clj` and `project_nrepl_commands.clj` instead of sharing helpers
- leaking command-oriented string formatting into the tool result surface
- accidental fallback to ambient cwd/focus instead of canonical worktree targeting
- under-specifying error/result shapes so tests lock in ambiguous behavior
