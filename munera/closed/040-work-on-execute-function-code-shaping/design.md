Goal: simplify the internal structure of `extensions.work-on/execute-work-on!` without changing its externally visible behavior.

Context:
- task `039-work-on-tool-surface` introduced a shared private execution function, `execute-work-on!`, as the canonical command/tool operation boundary for `work-on`
- review of `039` concluded that the implementation is correct and review-passing, but identified one minor cleanup opportunity:
  - `execute-work-on!` remains structurally dense and could be decomposed into smaller helpers
- the function currently mixes several concerns in one nested control flow:
  - input validation
  - current-session/worktree precondition checks
  - target branch/worktree derivation
  - initial worktree creation attempt
  - branch-already-exists retry path
  - existing-worktree/session reuse handling
  - success/error result shaping
- the implementation already has good behavior and test coverage, so this task is shaping-oriented rather than behavior-oriented

Problem:
- `execute-work-on!` is harder to scan and locally reason about than the rest of the surrounding extension code
- the nested branching increases the chance of future drift when `work-on` behavior changes
- the current structure makes it less obvious which parts are operational steps versus result shaping

Intent:
- improve local comprehensibility and maintainability of the `work-on` operation path
- preserve the command/tool shared boundary introduced by `039`
- reduce structural density without changing semantics

Scope:
- refactor `extensions.work-on/execute-work-on!` into smaller private helpers
- keep the shared command/tool behavior exactly as it is today
- preserve existing command wording, tool result shape, and session/worktree side effects
- keep changes local to `extensions.work-on` and its focused tests unless a tiny adjacent test update is needed

Non-goals:
- changing `work-on` behavior
- changing tool parameters or tool return shape
- changing `/work-on` wording
- redesigning the broader extension/runtime/dispatch/effects architecture
- refactoring `work-done!`, `work-rebase!`, or `work-status`

Minimum concepts:
- precondition validation helper(s)
- worktree creation-attempt helper
- new-worktree success shaping helper
- existing-worktree/session reuse helper
- canonical result shaping remains shared and unchanged

Preferred implementation shape:
- keep `execute-work-on!` as the canonical private orchestration entrypoint
- extract small private helpers for the main branches so the top-level function reads as a short orchestration flow
- prefer helpers aligned to domain steps over generic utility extraction
- preserve current result maps and side effects exactly

Architecture alignment:
- keep the extension-local shared execution boundary from `039`
- do not introduce a new runtime pattern
- do not move git/worktree effects into a new dispatch-owned effect layer in this slice

Acceptance:
- `execute-work-on!` is decomposed into smaller private helpers with clearer local responsibilities
- `/work-on` command behavior is unchanged
- `work-on` tool behavior and return shape are unchanged
- focused existing tests remain green
- task notes record that this was a code-shaping follow-on to the `039` review feedback
