# Plan

## Approach

Treat this as a context-reconciliation task between the harness tool surface and
live psi session state.

Implement from the outside in:
1. discover where harness direct tool calls get their cwd/path base
2. introduce a canonical resolver/helper for "effective harness worktree"
3. route relative `read` / `write` / `edit` / `bash` through that helper
4. add mismatch detection/diagnostics
5. add deterministic tests proving the new behavior

## Slices

### Slice 1: Discover and formalize the harness worktree resolution boundary

- Identify the layer where direct harness file/shell tool calls get their cwd/base
- Add one canonical helper to resolve:
  - live session worktree-path when available
  - otherwise harness cwd fallback
- Avoid scattering psi-tool queries at individual call sites

### Slice 2: Route direct coding tools through the helper

- Make relative `read` use the effective harness/session worktree
- Make relative `write` use the effective harness/session worktree
- Make relative `edit` use the effective harness/session worktree
- Make `bash` default cwd to the effective harness/session worktree
- Preserve absolute-path behavior unchanged

### Slice 3: Mismatch diagnostics

- Add a small diagnostic/projection/helper that reports:
  - live psi session worktree-path
  - harness cwd/worktree
  - mismatch? boolean
- Use this as the basis for preflight checks and future guardrails

### Slice 4: Tests

- Deterministic test: relative file tool resolves against live session worktree
- Deterministic test: bash default cwd follows live session worktree
- Deterministic test: absolute path bypasses default worktree base
- Deterministic test/diagnostic: mismatch is detectable/surfaced

## Risks

- The harness tool routing may live outside the repo boundary or in a thin integration seam,
  which could limit how much of the fix can be done purely inside `psi`
- If multiple possible live sessions exist, implicit session selection must be explicit or at least deterministic
- Over-coupling direct harness tool routing to psi runtime state could make no-session operation awkward if fallback behavior is not preserved

## Decision notes

- Prefer one canonical worktree-resolution helper over duplicating psi-tool queries at every call site
- Prefer deterministic test seams over real cwd/worktree mutation during tests
- Preserve fallback behavior when no live session exists; do not make the coding harness unusable outside a live psi session
