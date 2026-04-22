Approach:
- treat this as a local shaping pass, not a behavior change
- keep `execute-work-on!` as the orchestration entrypoint
- extract a small number of private helpers around the main domain branches
- rely on the existing `extensions.work-on` test coverage from `039` to guard semantics

Implementation shape:
1. Inspect the current `execute-work-on!` flow and identify the main branch boundaries worth naming.
2. Extract helpers for the highest-signal domain steps, likely around:
   - precondition failure shaping
   - worktree add/retry
   - new worktree/session success result
   - existing worktree/session reuse result
3. Rewrite `execute-work-on!` to read as a short orchestration function over those helpers.
4. Keep result maps, command rendering, tool rendering, and side effects unchanged.
5. Run focused `extensions.work-on` tests.
6. Record what was simplified and confirm no semantic changes were intended.

Risks / watchpoints:
- avoid renaming or reshaping result fields
- avoid changing call ordering for side effects
- avoid broad extraction that obscures the work-on domain flow instead of clarifying it
