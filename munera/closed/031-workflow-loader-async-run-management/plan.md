Approach:
- Treat this as an architectural convergence follow-on to 029, not as a user-facing feature expansion.
- Reuse existing long-running work patterns before inventing new tracking mechanisms.
- Preserve current `delegate` semantics while moving async execution ownership away from extension-local bookkeeping.

Recommended execution order:
1. characterize the current async path
   - inspect how `workflow-loader` currently launches futures, tracks active runs, refreshes widgets, and injects results
   - inspect how existing background-job state/projection is modeled and what delegated execution can reuse directly
   - identify the smallest canonical seam that can own delegated async tracking without broad architecture churn

2. choose the canonical tracking model
   - decide whether delegated workflow execution should:
     - register background jobs explicitly,
     - extend canonical workflow run metadata/projection to cover async visibility,
     - or bridge both with one clear source of truth
   - prefer one obvious model over mixed extension-private + canonical tracking

3. move async execution ownership off extension-local state
   - replace `future` + `active-runs` authority with canonical/background-job-backed tracking
   - keep only minimal extension-local transient state if strictly necessary and non-authoritative
   - ensure completion cleanup does not require private run bookkeeping to remove stale UI

4. rework widget/list/projection surfaces
   - make delegate list/widget output derive from canonical/background-job-backed data or a shared projection helper
   - align background-job and workflow-run naming/identity with the unified `delegate` model

5. preserve sync/continue/include-result behavior
   - keep sync mode implemented as waiting on the canonical async path
   - preserve blocked/terminal continue semantics
   - keep `include_result_in_context` targeting correct origin sessions
   - verify remove/cancel interactions remain coherent under the new tracking model

6. verify and document
   - add focused tests for async creation, visibility, completion, timeout, removal, and continue
   - update docs/comments/implementation notes where ownership semantics changed

Suggested starting files:
- `extensions/workflow-loader/src/extensions/workflow_loader.clj`
- `components/agent-session/src/psi/agent_session/background_jobs.clj`
- `components/app-runtime/src/psi/app_runtime/background_job_ui.clj`
- `components/app-runtime/src/psi/app_runtime/background_job_widgets.clj`
- `components/agent-session/src/psi/agent_session/mutations/canonical_workflows.clj`
- `components/agent-session/src/psi/agent_session/workflow_execution.clj`
- `extensions/workflow-loader/test/extensions/workflow_loader_delegate_test.clj`

Risks:
- introducing a third tracking model instead of removing one
- overcoupling delegate execution to background-job internals when a thinner canonical workflow projection would suffice
- regressing sync timeout, continue semantics, or origin-session result injection while changing ownership
- broadening the task into general dispatch/effects refactoring rather than a focused convergence slice
