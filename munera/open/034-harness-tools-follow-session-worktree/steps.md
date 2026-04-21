Slice 1 — Harness worktree resolution boundary:
- [ ] Identify where direct harness coding tools derive cwd/path base
- [ ] Add canonical helper for effective harness worktree resolution
- [ ] Cover live-session-present vs no-session fallback behavior

Slice 2 — Route direct coding tools:
- [ ] Make relative `read` follow effective harness/session worktree
- [ ] Make relative `write` follow effective harness/session worktree
- [ ] Make relative `edit` follow effective harness/session worktree
- [ ] Make `bash` default cwd follow effective harness/session worktree
- [ ] Preserve absolute path behavior

Slice 3 — Mismatch diagnostics:
- [ ] Add helper/diagnostic for live session worktree vs harness cwd mismatch
- [ ] Make mismatch queryable or otherwise inspectable in deterministic tests

Slice 4 — Tests:
- [ ] Deterministic test: relative file read follows live session worktree
- [ ] Deterministic test: relative write/edit follows live session worktree
- [ ] Deterministic test: bash default cwd follows live session worktree
- [ ] Deterministic test: absolute paths bypass worktree default
- [ ] Deterministic test: mismatch is detectable/surfaced
