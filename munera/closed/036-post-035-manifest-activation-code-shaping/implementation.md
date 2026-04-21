# Implementation notes

- Created from a post-034/post-035 code-shaper review.
- Motivation is code shaping only: simplicity, consistency, robustness, and local comprehensibility.
- This task must not expand the manifest activation behavior surface.

## 2026-04-21

- Reduced orchestration density in `components/agent-session/src/psi/agent_session/extension_runtime.clj` by extracting small private helpers for:
  - install-plan enrichment (`install-plan-for-activation`)
  - dependency-failure error projection (`deps-failure-errors`)
  - install-state finalization/persistence (`finalize-manifest-apply!`)
  - startup summary projection (`startup-summary-updates`)
  - reload result merging (`merge-manifest-apply-results`)
- Kept ownership unchanged: app-runtime still invokes startup bootstrap; extension-runtime still owns manifest apply orchestration.
- Made startup summary merge policy explicit in `components/app-runtime/src/psi/app_runtime.clj` with `merge-startup-summary`.
- Documented the failed-activation live-registry invariant directly at the loader seam in `components/agent-session/src/psi/agent_session/extensions/loader.clj` for both file-backed and init-var-backed activation.
- Added focused rollback tests in `components/agent-session/test/psi/agent_session/extensions_io_test.clj` proving failed file-backed and init-var-backed activation do not leave live registry entries behind.
- Verified targeted manifest activation coverage remains green:
  - `psi.agent-session.extensions-io-test`
  - `psi.agent-session.extension-manifest-activation-test`
  - `psi.extension-install-startup-test`
