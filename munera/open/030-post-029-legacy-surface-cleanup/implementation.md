2026-04-21
- Task created as the explicit post-029 cleanup follow-on.
- Added `munera/open/030-post-029-legacy-surface-cleanup/` and linked it in `munera/plan.md` immediately after `029-unified-workflow-loader/`.

2026-04-21
- First cleanup slice landed: background-job vocabulary normalization.
- Updated app-runtime and Emacs background-job rendering tests to stop describing canonical delegated work as `agent-chain` / `agent-run`.
- Normalized those test fixtures to the canonical `delegate` tool vocabulary.

Touched files:
- `components/app-runtime/test/psi/app_runtime/background_job_ui_test.clj`
- `components/app-runtime/test/psi/app_runtime/background_job_view_test.clj`
- `components/emacs-ui/test/psi-background-job-terminal-test.el`

2026-04-21
- Second cleanup slice landed: obsolete workflow identity fixture cleanup.
- Replaced retired workflow fixture identities such as `workflow/agent` and `extensions/agent.clj` in resolver-facing tests with current workflow-loader/delegate-aligned values.

Touched files:
- `components/agent-session/test/psi/agent_session/resolvers_test.clj`

2026-04-21
- Third cleanup slice landed: agent-session background-job fixture cleanup.
- Replaced remaining `agent-chain` job fixtures in background-job tests with canonical `delegate` naming.
- Left generic non-workflow placeholders like `tool-z` unchanged where the test intent is generic background-job mechanics rather than delegation surface naming.

Touched files:
- `components/agent-session/test/psi/agent_session/background_jobs_test.clj`

2026-04-21
- Fourth cleanup slice landed: Emacs/UI command and generic query-example cleanup.
- Replaced stale `/chain*` examples in generic UI tests with `/delegate ...` equivalents.
- Replaced generic `:psi.agent-chain/*` attr examples in widget-projection formatting/query tests with current canonical workflow attrs such as `:psi.workflow/definitions`, `:psi.workflow/runs`, and `:psi.workflow/run-count`.
- Updated a generic widget-id parsing example away from `agent-chain` to a neutral `delegate-run` style identifier.

Touched files:
- `components/emacs-ui/test/psi-capf-test.el`
- `components/emacs-ui/test/psi-extension-ui-test.el`
- `components/emacs-ui/test/psi-widget-projection-test.el`

2026-04-21
- Final audit completed for the targeted retired surfaces.
- Repo search over touched test areas no longer reports the targeted legacy references:
  - `agent-chain`
  - `agent-run`
  - `workflow/agent`
  - `extensions/agent.clj`
  - `/chain-rm`
  - `/chain-list`
  - `:psi.agent-chain/*`
- One remaining unrelated hit was observed in `child_session_mutation_test.clj` for `:psi.agent-session/agent-run-*`; this was intentionally treated as out of scope because it is not retired `agent` / `agent-chain` extension vocabulary.

2026-04-21
- Verification completed.
- Focused repository tests covering the touched areas are green:
  - Clojure unit suites via `bb clojure:test:unit` → `1251 tests, 9584 assertions, 0 failures`
  - Emacs suites via `bb emacs:test` → `289/289` passed

Outcome:
- Post-029 user-facing secondary surfaces and generic test fixtures now present one obvious delegation model: `workflow-loader` + `delegate`.
- No remaining work is currently recorded for this cleanup slice.
