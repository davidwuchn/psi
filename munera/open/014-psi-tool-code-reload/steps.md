- [x] Slice 1 — action-dispatched psi-tool contract + validation
  - [x] Update psi-tool schema/description to expose canonical `action`-based usage
  - [x] Add a single psi-tool action validator/dispatcher
  - [x] Preserve legacy query-only input as compatibility alias to `action: "query"`
  - [x] Return explicit structured validation errors for unknown/missing/invalid action inputs
  - [x] Add tests for action validation, legacy query aliasing, unknown action, and invalid action-specific argument combinations

- [x] Slice 2 — canonical query mode + compatibility proof
  - [x] Route `action: "query"` through existing query execution
  - [x] Keep existing query result/truncation behavior unchanged
  - [x] Add tests proving legacy `{query ...}` input still works
  - [x] Add tests proving canonical action-based query is the preferred equivalent

- [x] Slice 3 — eval runtime operation
  - [x] Add namespace-scoped in-process eval execution
  - [x] Require already loaded namespace and reject unknown namespaces
  - [x] Wrap eval results in canonical structured report shape
  - [x] Reuse psi-tool sanitization, truncation, and error shaping for eval results
  - [x] Add tests for eval success, invalid form, unknown ns, and structured failures

- [ ] Slice 4 — reload target resolution
  - [ ] Add namespace-mode validation: non-empty, distinct, non-blank namespace strings
  - [ ] Add namespace-mode reload candidate selection in request order
  - [ ] Add worktree-mode target resolution from explicit path or invoking session worktree
  - [ ] Add worktree-mode canonical source-path containment candidate selection
  - [ ] Reject unreloadable or invalid worktree targets explicitly
  - [ ] Add focused tests for namespace-mode and worktree-mode target selection rules

- [ ] Slice 5 — reload execution
  - [ ] Add deterministic reload execution over resolved candidate set
  - [ ] Stop on first namespace reload failure
  - [ ] Report successfully reloaded prefix in `:psi-tool/code-reload :namespaces`
  - [ ] Preserve explicit best-effort/non-atomic semantics in result shape
  - [ ] Add tests for success path and partial failure path

- [ ] Slice 6 — post-reload graph/runtime refresh
  - [ ] Add resolver registration refresh step
  - [ ] Add mutation registration refresh step
  - [ ] Add live tool definition refresh step
  - [ ] Add worktree-mode extension rediscovery/reload step
  - [ ] Add namespace-mode extension-registry preservation step
  - [ ] Report ordered refresh step results in `:psi-tool/graph-refresh :steps`
  - [ ] Add tests proving graph-refresh success/failure is reported distinctly from code reload

- [ ] Slice 7 — telemetry + transcript-visible diagnostics
  - [ ] Record canonical action arguments in tool lifecycle telemetry
  - [ ] Ensure truncated eval/reload output preserves required visible metadata
  - [ ] Add tests for telemetry capture and truncation-visible metadata preservation

- [ ] Slice 8 — docs + proof
  - [ ] Update tool-facing docs/examples to canonical action-based psi-tool contract
  - [ ] Document namespace-mode reload vs worktree-mode reload
  - [ ] Document eval as namespace-scoped only
  - [ ] Add/align proof tests for compatibility aliasing, reload scope, refresh behavior, and docs examples
