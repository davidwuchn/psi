Initialized from the prior active planning tracker on 2026-04-17.

2026-04-20 — post-turn git-head sync ownership + commit-check trigger debugging

- Reproduced that normal `session/prompt-in!` did not run post-turn git-head sync.
- Added focused regression proof in `components/agent-session/test/psi/agent_session/prompt_lifecycle_test.clj`.
- Restored post-turn sync in `components/agent-session/src/psi/agent_session/prompt_control.clj` so canonical prompt turns call `runtime/safe-maybe-sync-on-git-head-change!` after request execution.
- Reproduced a deeper bug in memory runtime: `sync-memory-layer!` eagerly advanced the per-cwd git-head baseline cache, causing later `maybe-sync-on-git-head-change!` calls to miss fresh external commits as `:head-unchanged`.
- Added focused regression proof in `components/memory/test/psi/memory/runtime_test.clj`.
- Fixed `components/memory/src/psi/memory/runtime.clj` so `sync-memory-layer!` no longer mutates the git-head baseline cache; baseline ownership remains with `maybe-sync-on-git-head-change!`.
- Used a managed project nREPL to validate the live app-runtime path end-to-end with an instrumented `extensions.commit-checks/handle-git-commit-created`.
- Verified that after the fixes the live prompt path emits `git_commit_created` with the expected `:previous-head`/`:head` transition and reaches the commit-checks extension.
- Verified the extension returned `nil` in the probe because configured commit checks passed/skipped; absence of follow-up prompt was no longer due to missed event dispatch.
- Identified duplicate sync ownership after restoring `prompt_control/prompt-in!`: app-runtime and RPC prompt wrappers were each performing their own follow-up sync after already routing through `prompt-in!`.
- Removed redundant post-prompt sync calls from `components/app-runtime/src/psi/app_runtime.clj` and `components/rpc/src/psi/rpc/session/prompt.clj`.
- Added app-runtime regression proof in `components/app-runtime/test/psi/app_runtime_test.clj` so prompt wrapper paths continue to prove exactly one sync-owned post-turn call through the shared prompt lifecycle.
- Cleaned temporary probe commits used during live debugging and returned branch history to the pre-probe head before reapplying the permanent source changes.
- Wider focused verification is green:
  - `psi.memory.runtime-test`
  - `psi.agent-session.prompt-lifecycle-test`
  - `psi.agent-session.runtime-test`
  - `psi.app-runtime-test`
  - `psi.rpc-prompt-test`

