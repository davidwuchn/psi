Goal: replace `workflow-loader`'s extension-local async run management with a more canonical runtime/background-job-integrated pattern so delegated workflow execution uses existing repo patterns for long-running work, tracking, and UI projection.

Context:
- task 029 converged workflow delegation on:
  - `.psi/workflows/`
  - `workflow-loader`
  - `delegate`
  - canonical deterministic workflow runtime
- the core workflow execution model is now canonical, but async orchestration in `extensions.workflow-loader` still uses:
  - `future`
  - an extension-local `active-runs` atom
  - extension-local completion callbacks for notification, widget refresh, and result injection
- this works and is tested, but introduces a secondary tracking model beside canonical workflow run state
- the repo already has stronger patterns for long-running work and projection:
  - background-job state and summaries
  - canonical state queried/projected into UI
  - runtime/dispatch-owned coordination patterns

Problem statement:
- `workflow-loader` currently duplicates async execution tracking outside canonical state
- extension-local `active-runs` weakens the single-source-of-truth model and makes projection/state ownership less clear
- widget/list/status output for running delegated workflows should prefer canonical/background-job-backed state over extension-private bookkeeping
- completion behavior (result injection, notification, cleanup) should be driven by the established runtime/job pattern where practical rather than ad hoc `future` lifecycle handling in the extension

In scope:
1. remove or minimize extension-local `active-runs` tracking for delegated workflow runs
2. integrate async delegated workflow execution with existing background-job and/or canonical runtime tracking patterns
3. make widget/list/projection output derive from canonical/background-job-backed state rather than private extension tracking where feasible
4. preserve current user-facing delegate semantics:
   - async default
   - sync mode with timeout
   - continue semantics
   - include_result_in_context behavior
5. preserve canonical workflow runtime as the execution substrate

Out of scope:
- redesigning the deterministic workflow runtime itself
- changing unified workflow file format or loader semantics
- broad dispatch/effects architecture work beyond what is needed for delegated async execution
- changing generic background-job architecture outside what delegated workflow execution needs

Acceptance:
- delegated async runs no longer depend on extension-local `active-runs` as the authoritative tracking model
- running delegated workflows are visible through canonical/background-job-backed state consistent with existing long-running work patterns
- delegate widget/list surfaces are driven from canonical/background-job-backed data or a clearly canonicalized projection layer
- async completion behavior remains correct for notifications, result injection, and cleanup
- sync mode still works by waiting on the canonical async execution path rather than a separate ad hoc path
- focused tests cover run creation, async progress visibility, completion, removal/continue interactions, and UI projections under the new tracking model
