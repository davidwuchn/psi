# 034 — Make harness file/shell tools follow the live session worktree

## Goal

Ensure the coding/file/shell tool surface used by the agent in this harness
follows the live psi session's canonical `:psi.agent-session/worktree-path` by
default, so task/session worktree intent and actual file mutation execution do
not silently diverge.

## Context

A recent failure mode exposed a split between two execution contexts:

1. **Live psi runtime session context**
   - reports `:psi.agent-session/worktree-path`
   - internally scopes runtime tool execution to that worktree

2. **Harness direct tool context**
   - direct `read` / `write` / `edit` / `bash` use by the agent in this coding harness
   - currently operates from harness cwd / explicit paths rather than being
     automatically aligned to the live psi session worktree

This allows the agent to correctly observe one worktree through psi runtime
state while actually editing another worktree through direct harness tools.

## Problem to solve

When the agent uses direct harness coding tools, relative path resolution and
shell cwd should default to the live session worktree, not the ambient harness
cwd, unless an explicit absolute path or explicit override says otherwise.

## What the agent needs

1. **Default alignment**
   - `read`, `write`, `edit`, and `bash` should use the live session worktree as
     their default base/cwd when a live session exists

2. **Preserve explicit targeting**
   - absolute file paths remain authoritative
   - explicit cwd/worktree overrides remain authoritative where supported

3. **Mismatch visibility**
   - when live psi session worktree and harness execution cwd differ, that should
     be detectable and preferably surfaced explicitly rather than silently ignored

4. **Safe fallback**
   - when no live psi session worktree is available, existing harness behavior
     may fall back to current cwd rather than hard-failing

## Resolved design decisions

### Live session worktree is the default authority for relative coding operations

For direct coding/file/shell operations in this harness, the default base for
relative paths and shell execution should come from the live psi session's
canonical `:psi.agent-session/worktree-path`.

### Absolute paths remain authoritative

If a tool call targets an absolute path, it should continue to use that path as
specified. The worktree default only affects relative path/cwd resolution.

### Fallback remains available when no live session exists

This task should not require a live session in all circumstances. If no session
worktree can be resolved, fallback to the ambient harness cwd is acceptable.

### Surface the mismatch rather than silently relying on one side

The harness should gain a way to detect/report when:
- live psi session worktree-path != harness cwd/worktree

This may be via diagnostics, preflight checks, or a dedicated helper, but the
system should no longer behave as if the two are always identical.

## Constraints

- Must preserve existing explicit absolute-path behavior
- Must not break psi runtime's own internal use of `:worktree-path`
- Must not require every tool call site to manually query psi-tool first
- Must keep the common coding path ergonomic for the agent
- Should minimize coupling drift between harness tool routing and live psi runtime state

## Out of scope

- Changing scheduler semantics
- Reworking psi runtime internal tool execution
- Enforcing task-specific branches/worktrees at git level
- New cross-session execution semantics

## Acceptance criteria

- Direct harness `read` resolves relative paths against the live session worktree by default
- Direct harness `write` resolves relative paths against the live session worktree by default
- Direct harness `edit` resolves relative paths against the live session worktree by default
- Direct harness `bash` runs in the live session worktree by default
- Absolute file paths continue to work unchanged
- If no live session worktree is available, harness tools fall back safely to ambient cwd behavior
- There is a deterministic test proving relative tool execution follows the live session worktree rather than ambient harness cwd
- There is a deterministic test or diagnostic proving a worktree mismatch can be detected/surfaced
