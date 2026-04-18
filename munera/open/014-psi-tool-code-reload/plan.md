Approach:
- Implement `psi-tool` as an action-dispatched runtime tool while preserving legacy query-only compatibility as a thin alias to canonical `action: "query"`.
- Keep `eval` simple and namespace-scoped.
- Implement `reload-code` with two explicit targeting modes:
  1. namespace mode (`namespaces`)
  2. worktree mode (`worktree-path` or invoking session worktree)
- Build the feature in vertical slices so each slice leaves a testable, coherent contract in place.

Planned slices:

1. Tool contract + validation layer
- Extend the `psi-tool` schema/docs in code to expose `action`, `eval`, and `reload-code` request forms.
- Introduce a single validator/dispatcher for psi-tool actions.
- Preserve legacy `{query ...}` handling as compatibility-only aliasing to canonical query mode.
- Make validation errors explicit and structured.

2. Query migration preservation
- Route canonical `action: "query"` through existing query behavior.
- Keep existing result/truncation semantics unchanged.
- Add compatibility tests proving legacy query-only input still works while new action-based query is canonical.

3. Eval runtime operation
- Add namespace-scoped in-process eval execution.
- Require already loaded namespace; reject unknown namespaces.
- Reuse psi-tool sanitization/truncation/error-reporting for eval results.
- Add structured eval result envelope and telemetry capture.

4. Reload candidate resolution
- Add canonical resolution for reload targets:
  - namespace mode: exact requested namespace vector, request order
  - worktree mode: already loaded namespaces selected by canonical source-path containment under effective worktree-path
- Add worktree-mode target resolution from explicit path or invoking session worktree.
- Make unreloadable targets explicit errors.

5. Reload execution
- Add deterministic namespace reload execution.
- Stop on first reload failure and report the successfully reloaded prefix.
- Keep reload best-effort/non-atomic and surface that explicitly in results/tests.

6. Post-reload graph/runtime refresh
- Implement the mandatory refresh sequence after reload phase:
  1. resolver registration refresh
  2. mutation registration refresh
  3. live tool definition refresh
  4. extension handling
     - worktree mode: rediscover/reload extensions under target worktree
     - namespace mode: preserve current extension registry without rediscovery
- Report refresh step results explicitly in the reload result envelope.

7. Telemetry + transcript-visible diagnostics
- Ensure tool lifecycle captures canonical action arguments.
- Ensure visible output preserves action metadata and worktree metadata when truncation occurs.
- Keep eval/reload results diagnosable from normal tool transcript output.

8. Docs + proof
- Update tool descriptions/spec/docs/examples to the canonical action-based contract.
- Cover:
  - action-based query
  - eval in loaded namespace
  - namespace-mode reload
  - worktree-mode reload via session-derived worktree
  - worktree-mode reload via explicit worktree-path
- Add focused tests for validation, reload scope selection, partial failure behavior, refresh behavior, and compatibility aliasing.

Implementation notes:
- Prefer introducing small internal helpers rather than broad reshaping of existing tool execution paths.
- Keep query behavior stable while layering new actions alongside it.
- Treat reload candidate selection and reload execution as separate helpers so tests can prove selection rules independently of actual reload mechanics.
- Treat graph/runtime refresh as a named post-reload phase, not an implicit side effect folded into reload.

Risks / decision points to watch during implementation:
- How canonical source-path discovery is derived for already loaded namespaces.
- How existing reload machinery determines deterministic reload order.
- Whether current extension reload paths cleanly support worktree-bounded rediscovery without broader side effects.
- Whether tool-definition refresh should rebuild from session state, runtime factories, or both.

Verification strategy:
- land validation/query preservation first
- then eval
- then reload candidate selection
- then reload execution
- then graph/runtime refresh
- finish with docs + compatibility proof
