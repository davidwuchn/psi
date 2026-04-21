2026-04-21

- Started 031 execution by converging `workflow-loader` async run tracking onto canonical background-job state.
- Added extension-facing mutations in `components/agent-session/src/psi/agent_session/mutations/session.clj`:
  - `psi.extension/start-background-job`
  - `psi.extension/mark-background-job-terminal`
- Reworked `extensions/workflow-loader/src/extensions/workflow_loader.clj`:
  - removed extension-local `active-runs` authority
  - introduced minimal `inflight-runs` registry only for sync waiting / future handles
  - async runs now create canonical background jobs with:
    - `tool-name = "delegate"`
    - `job-kind = :workflow`
    - `workflow-ext-path = "extensions/workflow-loader"`
    - `workflow-id = canonical workflow run-id`
  - async completion now marks canonical background jobs terminal before local cleanup
  - delegate list and widget refresh now derive async visibility from canonical background-job query data instead of extension-private tracking
- Updated `extensions/workflow-loader/test/extensions/workflow_loader_delegate_test.clj` scaffolding and mocks toward the new model.
- Next step: run focused tests, repair failures, then tighten remove/continue/background-job projection behavior if needed.
