Approach:
- Treat this as a post-029 convergence cleanup rather than new feature work.
- Prioritize references that affect user-facing vocabulary and architectural understanding over purely cosmetic string replacements.
- Classify each remaining legacy reference before changing it:
  1. still-supported compatibility behavior -> keep and document why
  2. generic infrastructure/example test -> replace with neutral/current terminology
  3. obsolete user-facing or architectural fixture -> replace with canonical workflow-loader/delegate terminology

Recommended execution order:
1. background-job vocabulary cleanup
   - update background-job test fixtures and rendering expectations that still use `agent-chain`, `agent-run`, or similar retired names
   - prefer `delegate` or workflow-specific names that match the current model
   - verify app-runtime, agent-session, and Emacs rendering expectations stay aligned

2. obsolete workflow identity fixture cleanup
   - replace fixtures such as `workflow/agent` and `extensions/agent.clj`
   - use current workflow-loader/delegate identities or neutral values appropriate to the test intent

3. widget/command example cleanup
   - replace `/chain`, `/chain-rm`, `/chain-list` examples in Emacs/UI tests when they are only standing in for generic commands
   - use `/delegate ...` or obviously generic extension commands depending on the test purpose

4. generic query/example attr cleanup
   - replace `:psi.agent-chain/*` examples in generic widget/query-formatting tests with current canonical workflow attrs or neutral namespaced attrs
   - avoid touching tests that are intentionally validating still-supported compatibility paths

5. final audit
   - rerun a repo search for `agent-chain`, `extensions/agent.clj`, `workflow/agent`, `/chain`, `/chain-rm`, and `:psi.agent-chain/`
   - classify any remaining hits as intentional or accidental
   - update docs or comments only where they still communicate obsolete surfaces

Suggested file set to inspect/update first:
- `components/app-runtime/test/psi/app_runtime/background_job_ui_test.clj`
- `components/app-runtime/test/psi/app_runtime/background_job_view_test.clj`
- `components/agent-session/test/psi/agent_session/background_jobs_test.clj`
- `components/agent-session/test/psi/agent_session/resolvers_test.clj`
- `components/emacs-ui/test/psi-background-job-terminal-test.el`
- `components/emacs-ui/test/psi-extension-ui-test.el`
- `components/emacs-ui/test/psi-capf-test.el`
- `components/emacs-ui/test/psi-widget-projection-test.el`

Risks:
- accidentally rewriting tests that are about generic tool-plan `chain` behavior rather than retired `agent-chain`
- losing signal in tests by replacing domain-specific names with overly abstract placeholders
- removing a legacy reference that still corresponds to a supported compatibility path without first verifying the production behavior
