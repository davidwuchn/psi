Created to refine a deterministic multi-agent coordination design before implementation planning.

Initial intent:
- replace prompt-only coordination with runtime-owned workflow/statechart coordination
- collaborate with the user to remove ambiguity before writing `plan.md`

Current status:
- task created with design questions and first-pass scope candidates
- design direction is settled and several implementation slices are now landed

2026-04-19 — canonical workflow runtime foundation
- Added `components/agent-session/src/psi/agent_session/workflow_model.clj`
- Established canonical workflow root state under:
  - `[:workflows :definitions]`
  - `[:workflows :runs]`
  - `[:workflows :run-order]`
- Added Malli schemas for:
  - workflow definitions
  - step definitions
  - retry and capability policies
  - result envelopes
  - step attempts
  - step runs
  - workflow runs
  - workflow root-state slice
- Wired workflow root-state initialization into `context.clj`
- Exposed workflow root-state paths through `session.clj` and `session_state.clj`
- Added focused tests:
  - `workflow_model_test.clj`
  - `workflow_session_integration_test.clj`

2026-04-19 — workflow run statechart model
- Added `components/agent-session/src/psi/agent_session/workflow_statechart.clj`
- Defined slice-one workflow-run phases:
  - `:pending`
  - `:running`
  - `:validating`
  - `:blocked`
  - `:completed`
  - `:failed`
  - `:cancelled`
- Defined explicit workflow control events:
  - `:workflow/start`
  - `:workflow/attempt-started`
  - `:workflow/result-received`
  - `:workflow/step-succeeded`
  - `:workflow/block`
  - `:workflow/resume`
  - `:workflow/retry`
  - `:workflow/fail`
  - `:workflow/complete`
  - `:workflow/cancel`
- Added `compile-definition` for sequential workflow execution metadata
- Added focused tests in `workflow_statechart_test.clj`

2026-04-19 — workflow run creation
- Added `components/agent-session/src/psi/agent_session/workflow_runtime.clj`
- Implemented pure canonical-root operations for:
  - definition registration
  - run creation from a registered definition id
  - run creation from an inline definition
- Workflow runs now capture immutable effective-definition snapshots
- Workflow runs initialize:
  - `:status :pending`
  - `:current-step-id` from compiled step order
  - per-step `:step-runs`
  - `:workflow/run-created` history entry
- Added focused tests in `workflow_runtime_test.clj`

2026-04-19 — workflow step-attempt session linkage
- Added `components/agent-session/src/psi/agent_session/workflow_attempts.clj`
- Implemented one canonical execution child session per workflow step attempt
- Workflow-owned child sessions now carry explicit linkage attrs:
  - `:workflow-run-id`
  - `:workflow-step-id`
  - `:workflow-attempt-id`
  - `:workflow-owned?`
- Extended child-session creation flow so workflow linkage moves through the existing child-session runtime path
- Added focused tests in `workflow_attempts_test.clj`

2026-04-19 — result validation and progression
- Added `components/agent-session/src/psi/agent_session/workflow_progression.clj`
- Implemented pure progression operations for:
  - starting the latest attempt
  - result-envelope submission
  - generic envelope validation
  - step-schema validation
  - success advancement to next step
  - terminal completion on final step
  - blocked-state transition
  - validation-failure retry/fail behavior
  - execution-failure retry/fail behavior
  - blocked-run resume
- Added focused tests in `workflow_progression_test.clj`

Notes:
- The canonical deterministic workflow substrate now exists as a pure state/runtime layer covering definitions, runs, attempt session linkage, and result progression.
- Remaining work is primarily integration and exposure:
  - Pathom/EQL read surface
  - `psi-tool` workflow ops
  - orchestration that combines run creation, attempt creation, and progression into a full executable lifecycle
  - representative chain-like proof and `agent-chain` follow-on
- Existing extension workflow runtime in `workflows.clj` remains separate from this new deterministic workflow-run substrate.
