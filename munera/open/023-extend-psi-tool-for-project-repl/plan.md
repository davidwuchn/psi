Approach:
- Treat this as a canonical API extension of `psi-tool`, using the existing managed project REPL runtime as the behavior source.
- Reuse current project REPL lifecycle/eval primitives rather than routing through command text.
- Design the new `psi-tool` surface around explicit actions, explicit targeting, and structured outputs.

Likely steps:
1. inspect current `psi-tool` action design and current project REPL APIs
2. define the canonical `psi-tool` surface for project REPL control and eval
3. implement explicit targeting and structured result shaping
4. align `/project-repl` command behavior and docs with the new tool surface
5. add focused tests for lifecycle, eval, and error paths

Risks:
- duplicating logic between command parsing and tool operations instead of sharing primitives
- under-specifying structured result shapes for eval and lifecycle operations
- introducing ambiguous targeting between session-scoped and worktree-scoped invocation
