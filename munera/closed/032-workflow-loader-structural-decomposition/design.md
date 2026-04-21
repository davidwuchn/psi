Goal: structurally decompose the `workflow-loader` implementation so the post-029/030/031 unified delegation model is reflected in code shape as well as behavior.

Context:
- task 029 unified agent and agent-chain concepts behind:
  - `.psi/workflows/`
  - `workflow-loader`
  - `delegate`
  - canonical deterministic workflow runtime
- task 030 removed remaining legacy surface drift from tests and projections
- task 031 converged delegated async tracking onto canonical background-job-backed state
- the resulting architecture is conceptually much simpler than the current `extensions.workflow-loader` namespace shape
- `extensions.workflow-loader` still accumulates several distinct responsibilities:
  - definition discovery / registration
  - prompt contribution generation
  - delegate tool command parsing / dispatch
  - async run orchestration
  - sync waiting
  - continuation / removal behavior
  - result injection and transcript entry shaping
  - widget/list projection and rendering
- this weakens local comprehensibility and makes future changes riskier than necessary

Problem statement:
- the unified delegation model is now correct at the system level, but not yet well-factored at the namespace/module level
- `extensions.workflow-loader` acts as a convergence magnet containing policy, orchestration, projection, and delivery concerns together
- background-job coupling details are still visible in extension-local orchestration code
- text shaping for notifications, entries, list lines, and widgets is not sufficiently centralized
- unused or stale helpers may remain after the convergence work

In scope:
1. decompose `extensions.workflow-loader` into smaller namespaces with clearer responsibility boundaries
2. separate pure shaping/projection logic from orchestration/effectful flow control where practical
3. reduce direct background-job metadata knowledge in the top-level delegate orchestration path
4. centralize delegate result/message/widget/list text shaping helpers where that improves consistency
5. remove stale or now-redundant helpers left behind by 029/031 convergence
6. preserve current user-visible behavior for:
   - definition loading
   - `delegate` tool semantics
   - `/delegate` command semantics
   - async/sync execution behavior
   - continue/remove behavior
   - include_result_in_context behavior
   - widget/list output semantics

Out of scope:
- redesigning the canonical deterministic workflow runtime
- changing workflow file format or loader semantics
- changing the canonical `delegate` surface
- broad background-job architecture redesign outside what decomposition requires
- introducing new user-facing delegation features

Desired decomposition direction:
- likely split responsibilities along lines such as:
  - workflow definition loading / registration
  - delegate runtime orchestration
  - delegate result delivery / transcript shaping
  - delegate UI/list/widget projection
- exact namespace boundaries should follow the simplest stable factoring discovered during implementation
- prefer pure helper namespaces where possible
- top-level extension entrypoint should become thin assembly / wiring

Constraints:
- preserve one obvious delegation model
- keep canonical workflow runtime as execution substrate
- keep canonical background-job-backed async visibility from 031
- improve local comprehensibility, consistency, and robustness
- avoid broadening into unrelated architecture work

Acceptance:
- `extensions.workflow-loader` no longer carries the bulk of orchestration, projection, and delivery logic in one file
- responsibility boundaries between definition loading, delegate runtime flow, result delivery, and UI/projection are clearer and locally comprehensible
- delegate orchestration code has less direct knowledge of low-level background-job shaping details, or that knowledge is isolated behind a narrower helper seam
- text shaping for delegate completion/list/widget output is more centralized and consistent
- stale/unused helpers related to pre-decomposition convergence are removed
- focused tests remain green and continue to prove current delegate behavior
