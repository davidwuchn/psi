Goal: remove the remaining post-029 legacy `agent` / `agent-chain` surface drift from tests, fixtures, and user-facing secondary projections so the repository consistently reflects the unified `workflow-loader` / `delegate` model.

Context:
- task 029 converged the primary implementation surface on:
  - `.psi/workflows/`
  - `workflow-loader`
  - `delegate`
  - canonical workflow runtime
- the core implementation now matches that design
- a follow-up audit found residual legacy references outside the core 029 implementation, mainly in tests and projection examples
- these references are uneven in significance:
  - some are harmless generic placeholders
  - some encode obsolete user-facing vocabulary or obsolete extension identities

Problem statement:
- several tests and fixtures still describe background jobs, widget actions, and workflow identities using retired `agent`, `agent-chain`, `/chain`, `/chain-rm`, or `extensions/agent.clj` vocabulary
- this preserves an obsolete mental model even when runtime behavior is now correct
- user-facing secondary surfaces such as background-job rendering and widget actions should not continue to validate against retired naming once `delegate` is canonical
- generic helper tests should avoid retired `:psi.agent-chain/*` attrs where the test intent is formatting/query plumbing rather than compatibility behavior

In scope:
1. normalize background-job and workflow-run naming in tests/fixtures to the unified delegation vocabulary
2. normalize Emacs/UI command examples away from `/chain*` commands when the test is about generic projection behavior rather than explicit backward compatibility
3. replace obsolete extension identity fixtures such as `extensions/agent.clj` and `workflow/agent`
4. replace stale `:psi.agent-chain/*` examples in generic query/widget-formatting tests when they no longer represent a supported public surface
5. preserve intentionally generic or compatibility-oriented tests only when they still assert a real supported behavior

Out of scope:
- changing the core workflow-loader implementation or delegate semantics
- reintroducing compatibility bridges for retired `agent` or `agent-chain` surfaces
- broad background-job architecture redesign
- changing generic tool-plan `chain` behavior when it is unrelated to the retired `agent-chain` extension

Acceptance:
- no user-facing secondary test fixtures describe canonical delegated workflow activity as `agent-chain`, `agent-run`, or `workflow/agent`
- no widget-action tests use retired `/chain*` commands unless the test is explicitly about backward compatibility that still exists in production
- obsolete extension identity fixtures (`extensions/agent.clj`, similar) are removed or replaced with current equivalents
- generic formatting/query tests no longer use retired `:psi.agent-chain/*` attrs when current canonical attrs or neutral names would do
- any intentionally retained legacy references are clearly justified by still-supported behavior rather than test inertia
- docs and tests present one obvious delegation model: workflow-loader + delegate
