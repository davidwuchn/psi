# Changelog

## 2026-03-06

- λ Δ Added `spec/git-worktrees.allium` and Step 11a acceptance checklist in `PLAN.md`.
- λ Δ Implemented git worktree query surface:
  - history attrs: `:git.worktree/list`, `:git.worktree/current`, `:git.worktree/count`, `:git.worktree/inside-repo?`
  - session bridge attrs: `:psi.agent-session/git-worktrees`, `:psi.agent-session/git-worktree-current`, `:psi.agent-session/git-worktree-count`
- Δ Added `/worktree` slash command and `/status` worktree identity surfacing.
- Δ Added graph/introspection coverage tests for worktree resolvers/attrs.
- Δ Added worktree parse-failure telemetry marker (`git.worktree.parse_failed`) with degradation-to-empty behavior and tests.
- Δ Hardened tests to avoid writing repo `.psi/project.edn` by using temp cwd in agent-session/introspection test contexts.
