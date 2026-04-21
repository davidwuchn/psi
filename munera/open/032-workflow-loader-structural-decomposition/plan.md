Approach:
- Treat this as a code-shaping refactor driven by simplicity, consistency, and robustness, not as a behavior change.
- Preserve the converged 029/030/031 semantics while improving local code structure.
- Prefer extracting pure/helper namespaces before changing effectful orchestration paths.

Recommended execution order:
1. characterize the current responsibility clusters
   - identify which functions in `extensions.workflow-loader` belong to:
     - definition loading/registration
     - delegate runtime orchestration
     - result delivery and transcript shaping
     - UI/list/widget projection
   - identify stale helpers or helpers whose names no longer match authority/ownership

2. extract low-risk pure seams first
   - move text shaping and projection helpers into smaller namespaces first
   - move workflow list/widget formatting and completion message shaping behind pure helper functions
   - keep top-level behavior unchanged while reducing local density

3. extract orchestration seams
   - isolate delegate run/continue/remove orchestration from extension initialization/wiring
   - isolate sync wait handling from canonical async execution ownership
   - if useful, introduce a narrower helper seam around delegate/background-job interaction

4. thin the top-level extension entrypoint
   - make `extensions.workflow-loader` primarily assemble state, register tools/commands, and delegate behavior to extracted namespaces
   - ensure each extracted namespace has one obvious responsibility

5. remove stale helpers and tighten naming
   - remove unused helpers left behind by 029/031
   - rename helpers where current names no longer describe their role precisely
   - ensure naming reflects authoritative vs non-authoritative state clearly

6. verify and document
   - run focused workflow-loader tests and any adjacent projection/runtime tests needed for confidence
   - record decomposition decisions and any notable trade-offs in `implementation.md`

Suggested starting files:
- `extensions/workflow-loader/src/extensions/workflow_loader.clj`
- `extensions/workflow-loader/test/extensions/workflow_loader_delegate_test.clj`
- `extensions/workflow-loader/test/extensions/workflow_loader_test.clj`
- any newly extracted namespaces under `extensions/workflow-loader/src/extensions/workflow_loader/`

Risks:
- accidental behavior drift while moving intertwined helper logic
- extracting namespaces that mirror current file layout but do not actually improve responsibility boundaries
- over-factoring small helpers while leaving the main orchestration complexity intact
- broadening into background-job/runtime redesign rather than focused decomposition
