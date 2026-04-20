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
  - runtime-owned cancellation helper for workflow runs
- Added focused tests in `workflow_progression_test.clj`

2026-04-19 — Pathom/EQL workflow read surface
- Added `components/agent-session/src/psi/agent_session/resolvers/workflows.clj`
- Exposed workflow root attrs from session root:
  - `:psi.workflow/definition-count`
  - `:psi.workflow/definition-ids`
  - `:psi.workflow/definitions`
  - `:psi.workflow/run-count`
  - `:psi.workflow/run-ids`
  - `:psi.workflow/run-statuses`
  - `:psi.workflow/runs`
- Exposed entity-targeted workflow detail attrs:
  - `:psi.workflow.definition/detail` from `{:psi.workflow.definition/id ...}`
  - `:psi.workflow.run/detail` from `{:psi.workflow.run/id ...}`
- Added session-side workflow linkage attrs in `resolvers/session.clj`:
  - `:psi.agent-session/workflow-run-id`
  - `:psi.agent-session/workflow-step-id`
  - `:psi.agent-session/workflow-attempt-id`
  - `:psi.agent-session/workflow-owned?`
  - `:psi.workflow.run/id` as session→workflow reference
- Wired workflow resolvers into the assembled Pathom surface in `resolvers.clj`
- Added focused resolver tests in `workflow_resolvers_test.clj`
- Verified focused workflow + resolver tests are green:
  - `clojure -M:test --focus psi.agent-session.workflow-resolvers-test --focus psi.agent-session.workflow-attempts-test --focus psi.agent-session.workflow-progression-test --focus psi.agent-session.resolvers-test`

2026-04-19 — `psi-tool` workflow ops
- Extended `components/agent-session/src/psi/agent_session/psi_tool.clj` with `action: "workflow"`
- Added slice-one workflow ops:
  - `list-definitions`
  - `create-run`
  - `read-run`
  - `list-runs`
  - `resume-run`
  - `cancel-run`
- `create-run` supports:
  - registered definitions via `definition-id`
  - inline definitions via EDN `definition`
  - EDN `workflow-input`
- `resume-run` resumes blocked runs through runtime-owned progression
- `cancel-run` applies runtime-owned terminal cancellation
- Added focused workflow psi-tool coverage in `components/agent-session/test/psi/agent_session/tools_test.clj`
- Verified focused workflow/runtime/query/tool tests are green:
  - `clojure -M:test --focus psi.agent-session.tools-test --focus psi.agent-session.workflow-resolvers-test --focus psi.agent-session.workflow-attempts-test --focus psi.agent-session.workflow-progression-test --focus psi.agent-session.resolvers-test`

2026-04-19 — lifecycle proof + representative chain proof
- Added `components/agent-session/test/psi/agent_session/workflow_lifecycle_test.clj`
- Proved a representative sequential `plan -> build -> review` workflow over the canonical workflow runtime by exercising:
  - workflow run creation
  - per-step attempt/session creation
  - structured `:ok` result submission
  - deterministic advancement across ordered steps
  - terminal completion with accepted per-step outputs preserved
  - introspection of step runs, attempts, history, and execution-session linkage via `:psi.workflow.run/detail`
- Added a blocked/resume proof covering:
  - blocked result-envelope handling
  - runtime-owned resume semantics
  - explicit creation of a fresh attempt after resume
  - preservation of prior blocked attempt audit history and session linkage
- Verified focused workflow lifecycle + runtime + resolver + psi-tool coverage is green:
  - `clojure -M:test --focus psi.agent-session.workflow-lifecycle-test --focus psi.agent-session.workflow-runtime-test --focus psi.agent-session.workflow-attempts-test --focus psi.agent-session.workflow-progression-test --focus psi.agent-session.workflow-resolvers-test --focus psi.agent-session.tools-test`

2026-04-19 — `agent-chain` follow-on integration assessment
- Reviewed current `agent-chain` shape in `.psi/agents/agent-chain.edn` and the current discovery surface in `components/agent-session/src/psi/agent_session/resolvers/extensions.clj`
- Current state:
  - `agent-chain` definitions are currently discovered from static EDN config only
  - chain steps are order-only and use `{ :agent ... :prompt ... }` with `$INPUT` / `$ORIGINAL` substitution conventions
  - chain discovery is introspectable, but chain execution is not yet implemented on top of canonical workflow runs
  - existing `workflow_mutations.clj` / `workflows.clj` are extension-workflow facilities and remain separate from the new canonical deterministic workflow runtime
- Integration conclusion:
  - the next slice should keep `agent-chain` as a thin compatibility/authoring surface over canonical workflow definitions rather than as a separate execution runtime
  - chain config should compile into canonical workflow definitions with stable step ids, explicit `step-order`, agent-backed `:executor`, explicit input bindings, and a standard result-envelope contract
  - the existing `:psi.agent-chain/*` discovery surface can remain for adapter continuity, but should eventually expose compiled workflow-definition identity alongside legacy chain summary data
  - avoid migrating the old extension workflow runtime into this path; `agent-chain` should target the canonical `:workflows` state introduced for 026
- Expected next implementation slice:
  1. decide whether chain runs are launched through `agent-chain` tooling by delegating to workflow `create-run`, or by first registering compiled definitions in canonical root state
  2. keep adapter/UI changes minimal until run creation and readback are working against canonical workflow runs

2026-04-19 — `agent-chain` compiler groundwork
- Added `components/agent-session/src/psi/agent_session/workflow_agent_chain.clj`
- Implemented a pure compiler from legacy `agent-chain` maps to canonical workflow definitions:
  - stable step ids derived from step order + agent name
  - workflow `:step-order` + step-id keyed `:steps`
  - agent-backed `:executor` with `:profile`
  - preserved legacy prompt text under `:prompt-template`
  - explicit `:input-bindings` for first-step workflow input and later-step prior-step output
  - explicit `:original` binding from workflow input for legacy `$ORIGINAL` semantics
  - default canonical result schema + retry policy for compiled chain steps
- Added `components/agent-session/test/psi/agent_session/workflow_agent_chain_test.clj`
- Extended canonical workflow model/resolver surfaces to include step `:prompt-template` for introspection of compiled chain definitions
- Updated workflow model/resolver tests to cover the new field
- Verified focused chain-compiler coverage is green in the 026 worktree:
  - `clojure -M:test --focus psi.agent-session.workflow-agent-chain-test --focus psi.agent-session.workflow-model-test --focus psi.agent-session.workflow-resolvers-test`

Notes:
- The deterministic workflow substrate now covers state model, statechart compilation, run creation, attempt/session linkage, result progression, Pathom/EQL read exposure, `psi-tool` control operations, a representative chain-like proof, and pure `agent-chain` compilation into canonical workflow definitions.
2026-04-19 — runtime registration + direct launch path for compiled agent chains
- Added `components/agent-session/src/psi/agent_session/workflow_agent_chain_runtime.clj`
- Implemented runtime registration helpers that:
  - read `.psi/agents/agent-chain.edn`
  - compile legacy chains into canonical workflow definitions
  - register those definitions into canonical workflow root state
  - return a structured registration report (`config-path`, `definition-ids`, `registered-count`, `error`)
- Extended `psi-tool` workflow ops with:
  - `register-agent-chains`
  - `create-run-from-agent-chain`
- `create-run-from-agent-chain` now:
  - registers compiled chain definitions into canonical root state
  - validates the requested `chain-name`
  - creates a canonical workflow run from the registered definition id
  - returns both the registration report and workflow run summary
- Added focused tests in:
  - `workflow_agent_chain_runtime_test.clj`
  - `tools_test.clj`
- Verified focused green set:
  - `clojure -M:test --focus psi.agent-session.workflow-agent-chain-runtime-test --focus psi.agent-session.workflow-agent-chain-test --focus psi.agent-session.tools-test`

- Remaining work is no longer deciding the runtime launch/registration path; that path now exists through `psi-tool` workflow ops.
2026-04-19 — execution-time orchestration helpers
- Added `components/agent-session/src/psi/agent_session/workflow_execution.clj`
- Implemented a first execution bridge for canonical workflow runs that can:
  - materialize step inputs from canonical `:input-bindings`
  - render legacy-compatible `:prompt-template` strings using `$INPUT` and `$ORIGINAL`
  - create a canonical step-attempt child session for the current step
  - prompt that child session
  - record a canonical `{:outcome :ok :outputs {:text ...}}` result envelope from the child session's assistant output
  - advance the workflow run through existing pure progression logic
- Added focused tests in `workflow_execution_test.clj` proving:
  - step input materialization + prompt rendering
  - bounded current-step execution that creates an attempt session and advances the workflow

- Added `execute-run!` to `workflow_execution.clj`
- `execute-run!` now loops canonical sequential workflow execution until the run becomes:
  - `:completed`
  - `:failed`
  - `:cancelled`
  - `:blocked`
- Added focused tests proving:
  - multi-step sequential execution reaches terminal completion
  - downstream prompts consume prior-step canonical outputs
- Added blocked-loop proof in `workflow_execution_test.clj`
- The sequential execution loop is now proven to stop and report blocked status rather than only proving terminal completion.
- Extended `workflow_execution.clj` with `resume-and-execute-run!` and made `execute-current-step!` return structured error info while recording execution failures.
- Added focused retry/resume proofs in `workflow_execution_test.clj` showing:
  - retryable execution failure can be followed by a successful later attempt within the sequential loop
  - blocked runs can be resumed and then continued to completion with a fresh attempt
- Remaining likely follow-on is shaping these helpers into a broader runtime-facing execution control surface and integrating them into `psi-tool`/named chain execution flows if desired.
- Existing extension workflow runtime in `workflows.clj` remains separate; `workflow_runtime.clj` and related files are for the new canonical deterministic workflow-run state.
