Goal: define a deterministic coordination facility for multi-agent work in psi, using first-class workflow/statechart runtime primitives instead of prompt-only orchestration.

Context:
- today, multi-step agent coordination can be approximated by prompting a top-level agent to create and coordinate subagents
- that approach leaves stage boundaries, handoff structure, retries, and advancement semantics largely prompt-defined and nondeterministic
- psi already has statechart machinery and an event/dispatch architecture, so workflow/statechart coordination is a plausible native fit
- the desired outcome is not fully deterministic model generation; it is deterministic coordination over bounded agent steps, with runtime-owned progression and introspection

Problem statement:
- prompt-based coordination makes control flow soft and implicit
- handoff contracts are informal rather than validated
- workflow progression is inferred from text rather than enforced by runtime state
- retries, branching, user-decision checkpoints, and failure handling do not yet have a canonical runtime model for agent orchestration
- there is no first-class introspectable runtime surface for workflow runs, step state, or structured outputs

Desired direction:
- represent coordination as data
- use runtime/statechart transitions rather than prose to decide what happens next
- treat agents as bounded workers that produce step results
- validate step outputs before advancing
- make workflow execution queryable and replay-aware

Terminology boundary:
- public/runtime-facing terminology should remain workflow-oriented
  - workflow
  - workflow definition
  - workflow run
  - step
  - step attempt
  - executor
  - input bindings
  - result envelope
  - retry policy
  - capability policy
  - blocked / resume / cancel
- internal execution terminology may use statechart terms
  - statechart
  - state
  - transition
  - event
  - guard
  - action
  - invoke
- prefer `status` for public workflow-run and step-attempt surfaces, and reserve `state` for statechart execution mechanics where possible
- the workflow model is the authoring and introspection surface; the statechart model is the execution mechanism

Initial design scope candidates:
1. workflow definition model
   - steps or states
   - transitions
   - per-step agent profile / execution mode
   - input bindings from workflow input and prior outputs
   - output schema / validation
   - terminal states
2. workflow run model
   - current state / step
   - per-step attempts
   - outputs / artifacts
   - validation reports
   - failure / blocked / awaiting-user-decision states
3. runtime execution semantics
   - create run
   - dispatch step execution
   - capture structured result
   - validate result
   - advance / retry / fail / block
4. introspection/query surface
   - list workflow definitions
   - inspect workflow runs
   - inspect current state, attempts, outputs, and transition history
   - inspect the relationship between workflow runs, workflow steps, and sessions in the canonical sessions map
5. capability / safety model
   - per-step tool and prompt capability restrictions
   - whether child-session creation is allowed and how it is bounded
6. UI / adapter implications
   - minimal required public projection for TUI / Emacs / RPC
   - whether workflow runs are visible as sessions, background jobs, both, or a distinct concept

Explicit non-goals for the first design pass:
- replacing all existing agent/session behavior immediately
- making model generation itself deterministic
- solving every long-running workflow / scheduling / distributed execution concern up front
- designing the full adapter UX before the runtime model is clear

Decisions so far:
1. primary abstraction
   - present this as a workflow model backed by statecharts
   - workflows are the user-facing/runtime-facing coordination abstraction
   - statecharts are the execution mechanism used to enforce transitions and progression semantics
2. first-slice scope
   - slice one supports linear/sequential workflows only
   - no parallelism in slice one
   - no general branching graph in slice one
   - runtime progression should cover pending → running-step → validating → next-step / blocked / failed / completed
   - terminal or stopping states in slice one include completed, failed, cancelled, and blocked
   - blocked/awaiting-user-decision is included in slice one
   - basic runtime-owned retry support is included in slice one
   - step outputs are structured and validated before advancement

Resolved design decisions:
3. execution unit
   - a workflow step is a bounded workflow-step execution unit
   - slice one may implement workflow-step execution using agent/child sessions internally
   - workflow runs are not semantically identical to sessions, even when implemented via sessions
   - all sessions created for workflow execution live in the existing canonical sessions map
   - introspection is a prerequisite rather than a follow-on; workflow runs and their session relationships must be queryable in slice one
4. output contract
   - workflow step completion is based on a mandatory structured result envelope
   - runtime validates the envelope against the step contract before advancement
   - transcripts/messages remain available as supporting evidence and debugging context, but are not the coordination contract
   - slice one supports at least `:ok` structured outputs and `:blocked` structured user-decision/request outcomes
   - execution failure, validation failure, and cancellation are runtime-owned workflow/run states rather than ordinary successful step outputs
5. dataflow model
   - canonical workflow dataflow is expressed via explicit runtime references, not prompt copying
   - step input bindings may reference workflow input, prior step result envelopes, and a small explicit workflow runtime metadata surface
   - execution-time prompt/input materialization is a derived projection from canonical bindings
   - prompt text is not the authoritative handoff surface
   - slice one excludes arbitrary binding from unrelated root-state/session state outside explicitly declared workflow/session refs
6. session relationship
   - workflow runs are distinct entities that reference step runs and step attempts
   - slice one uses one canonical execution session per step attempt
   - retries create a new step attempt and a new execution session
   - all execution sessions live in the existing sessions map
   - execution sessions are explicitly marked as workflow-owned step sessions so introspection and UI projections can distinguish them from ordinary sessions
   - workflow/run ↔ step ↔ attempt ↔ session relationships are first-class and queryable
7. state ownership
   - workflow definitions and workflow runs live in canonical root state as first-class runtime entities
   - sessions remain in the existing canonical sessions map
   - slice one stores step runs and attempts under their parent workflow run, with stable ids and explicit session references
   - workflow and session linkage is represented in data on both sides as needed for query/projection convenience
   - dispatch owns workflow state transitions
   - statecharts define and enforce legal workflow/run transitions
   - runtime effects own impure execution concerns such as starting execution sessions, invoking agents, validating results if impure, and surfacing blocked/user-decision requests
   - agents do not mutate workflow state directly; they only produce bounded step execution results that the runtime validates and commits
8. event/effect boundaries
   - workflow execution is driven by explicit dispatch events and statechart-governed transitions
   - slice one models result receipt and result validation as distinct lifecycle stages
   - pure workflow state updates, transition decisions, and contract validation should remain in dispatch/statechart space wherever possible
   - runtime effects are reserved for impure operations such as creating execution sessions, invoking agent execution, and surfacing blocked/user-decision requests externally
   - step execution completion re-enters the system as an explicit event carrying the structured result envelope and associated execution/session metadata
   - workflow advancement occurs only after runtime/statechart validation and commit of the step attempt outcome
   - retries, failure transitions, blocked transitions, and cancellation are runtime-owned state transitions, not prompt-authored control flow
9. failure semantics
   - slice one distinguishes execution failure, validation failure, blocked, cancelled, and workflow failed as distinct runtime states and semantics
   - step-produced structured outcomes are limited to at least `:ok` and `:blocked`
   - validation failure means a structured result was received but did not satisfy the declared step contract
   - execution failure means the attempt could not produce a valid structured result envelope due to runtime/session/tool/provider failure
   - blocked means execution produced a structured user-decision/request outcome and the workflow pauses in a non-terminal blocked state
   - cancelled is externally initiated and distinct from failed
   - workflow failed is the terminal workflow/run state reached after unrecoverable failure or retry exhaustion
   - slice one retry policy is simple and runtime-owned: per-step max attempts plus explicit retryability for execution failure and validation failure
   - resuming from blocked creates a new attempt rather than mutating the prior attempt
10. observability
   - introspection is a slice-one prerequisite, not a follow-on
   - slice one must expose workflow definitions, workflow runs, ordered steps, attempts, statuses, retry configuration, structured results, validation failures, blocked payloads, and transition/audit information through the canonical query surface
   - slice one must expose first-class relationships among workflow runs, steps, attempts, and execution sessions
   - execution sessions must carry workflow linkage attributes so session-centric queries can discover workflow ownership and context
   - Pathom/EQL introspection is mandatory in slice one
   - adapter/UI projection may be minimal in slice one, but the runtime/query surface must be sufficient to inspect and debug workflow execution deterministically
11. replay expectations
   - slice one defines deterministic coordination as deterministic workflow/run state evolution given the same workflow definition, workflow input, external control events, accepted step result envelopes, validation outcomes, and retry/cancel/resume decisions
   - slice one does not attempt to make model or tool execution itself deterministic
   - replay should reproduce workflow state transitions from recorded events and accepted outcomes without re-invoking agent execution effects
   - step result receipt, validation outcome, blocked decisions, retries, cancellations, and terminal transitions must be recorded in a form sufficient for audit and replay
   - the runtime contract is deterministic control plane over probabilistic execution plane
12. migration target
   - the primary deliverable is a new lower-level workflow runtime primitive modeled as workflows backed by statecharts
   - the first concrete consumer and proving ground is replacement or reimplementation of `agent-chain` behavior on top of that primitive
   - a key motivating consumer is `psi-tool`, which should expose workflow operations so psi can create and run ad-hoc workflows under deterministic runtime coordination
   - slice one does not attempt to migrate all agent/session behavior onto workflows
   - slice one only needs enough workflow capability to support deterministic multi-step orchestration for chain-like use cases and nearby bounded helper workflows
13. `psi-tool` workflow exposure
   - slice one includes `psi-tool` workflow operations
   - workflows are exposed through `psi-tool` as a new `action: "workflow"` family with explicit `op` values
   - `psi-tool` can create workflow runs from registered workflow definitions and from inline ad-hoc workflow definitions
   - inline definitions become part of canonical workflow/run state so they are introspectable and replayable
   - slice one supports at least the following workflow ops: `list-definitions`, `create-run`, `read-run`, `list-runs`, `resume-run`, and `cancel-run`
   - workflow advancement remains runtime-owned rather than being directly driven by a low-level `psi-tool` advance operation

Additional design decisions:
- slice one workflow-run statuses are `:pending`, `:running`, `:blocked`, `:completed`, `:failed`, and `:cancelled`
- slice one step-attempt statuses are `:pending`, `:running`, `:validating`, `:succeeded`, `:blocked`, `:validation-failed`, `:execution-failed`, and `:cancelled`
- result receipt is modeled as an event, not necessarily a distinct persisted status
- step-run status should be derived from attempts and accepted outcome where practical, rather than introducing unnecessary duplicate state in slice one

Acceptance for design completion:
- the core abstraction is chosen and named clearly
- first-slice scope is explicit, including non-goals
- runtime/state ownership is specified clearly enough to implement without hidden decisions
- step/run/result/failure semantics are unambiguous
- relationship to existing agent sessions, dispatch, effects, and statecharts is clear
- required introspection surface is identified and included in slice one
- `psi-tool` exposure is included in slice one
- open questions are reduced enough that a concrete plan can be written without ambiguity

Initial collaboration checklist:
- [x] choose abstraction framing: workflow, statechart, or workflow-on-statecharts
- [x] choose first-slice scope boundaries
- [x] define execution unit and output envelope contract
- [x] define relationship between workflow runs and agent sessions
- [x] define failure/blocking/retry semantics
- [x] define minimum introspection surface
- [x] define whether this is an `agent-chain` replacement substrate, a new primitive, or both

Additional design decisions:
- workflow definitions should use a step-id keyed map plus explicit execution order, rather than an order-only vector of anonymous steps
- this preserves stable step identities for introspection, references, and future evolution beyond strictly linear execution
- workflow step definitions should use a general execution slot such as `executor`
- slice one supports only agent-backed executors in that slot, keeping the model extensible without requiring non-agent execution yet
- slice-one workflow step definitions include a minimal capability restriction field
- that field constrains the bounded execution environment for the step
- slice one should at least support tool capability restriction, with broader policy surface only if needed by implementation
- every workflow run stores an immutable effective workflow definition snapshot
- runs created from registered definitions may also keep a reference to the source definition id
- the effective definition snapshot is the authoritative definition for introspection, replay, and execution of that run

Minimum conceptual schemas for slice one:
1. workflow definition
   - identity
     - workflow definition id for registered definitions
     - inline/ad-hoc definitions may begin without a pre-existing registered id, but the effective definition captured on a run must have a stable identity within that run
   - structure
     - explicit `step-order` as an ordered vector of step ids
     - `steps` as a step-id keyed map
   - optional metadata
     - human-oriented name/summary/description as needed for introspection and tooling
   - semantics
     - definition is declarative runtime coordination data, not prompt prose

2. step definition
   - `executor`
     - general execution slot
     - slice one supports only agent-backed executors
   - `input-bindings`
     - explicit references from workflow input, prior accepted step outputs, and small workflow runtime metadata
   - `result-schema`
     - contract for the mandatory structured result envelope / outputs
   - `retry-policy`
     - simple runtime-owned retry configuration for validation and execution failure cases
   - `capability-policy`
     - bounded execution restrictions, at minimum tool capability restriction in slice one
   - optional metadata
     - step label/description for introspection and debugging

3. workflow run
   - `run-id`
     - stable workflow run identity
   - `status`
     - one of the slice-one workflow-run statuses
   - `effective-definition`
     - immutable snapshot used for execution, introspection, and replay
   - optional `source-definition-id`
     - reference to the registered workflow definition when applicable
   - `workflow-input`
     - canonical run input used by input bindings
   - `current-step-id`
     - current active step when running/blocked, absent when terminal if appropriate
   - `step-runs`
     - per-step execution state nested under the workflow run
   - `history`
     - transition/audit information sufficient for inspection and replay
   - terminal outcome metadata
     - reason/details for completion, failure, cancellation, or blocking as appropriate

4. step run
   - `step-id`
     - stable reference to the step definition within the effective definition
   - derived or convenience status
     - preferably derived from attempts plus accepted outcome in slice one
   - `attempts`
     - ordered attempt records for that step
   - accepted outcome summary
     - accepted result envelope and/or terminal blocking/failure summary as needed for convenience

5. step attempt
   - `attempt-id`
     - stable identity within the workflow run
   - `status`
     - one of the slice-one step-attempt statuses
   - `execution-session-id`
     - canonical session id in the existing sessions map
   - `result-envelope`
     - structured envelope received from execution, if any
   - `validation-outcome`
     - accept/reject details sufficient for audit and retry reasoning
   - failure metadata
     - execution-failure details when applicable
   - blocked metadata
     - structured blocked/user-decision payload when applicable

6. result envelope
   - `outcome`
     - at minimum `:ok` or `:blocked`
   - `outputs`
     - structured outputs for `:ok`
   - blocked payload
     - structured decision/input request for `:blocked`
   - optional diagnostics
     - additional bounded execution metadata that may aid introspection/debugging without becoming the coordination contract

7. `psi-tool` workflow operation shapes
   - `action`
     - `"workflow"`
   - `op`
     - one of the slice-one workflow ops: `list-definitions`, `create-run`, `read-run`, `list-runs`, `resume-run`, `cancel-run`
   - create-run request conceptually includes
     - either a registered workflow definition id or an inline workflow definition
     - workflow input
     - any required execution/context targeting fields needed by the runtime boundary
   - read-run / resume-run / cancel-run conceptually include
     - workflow run identity
     - resume also includes any structured user decision/input required to leave the blocked state
   - responses conceptually include
     - workflow run identity and current status
     - enough run summary/detail to support deterministic inspection and follow-up operations
     - error responses should distinguish invalid request shape, missing run/definition, invalid transition, and validation/policy rejection cases

Notes:
- The high-level design is now materially refined.
- A key motivating use case is exposing workflow creation/execution through `psi-tool` so psi can create and run ad-hoc workflows under deterministic runtime coordination rather than prompt-only coordination.
- The remaining work before `plan.md` is primarily to turn this agreed design into an implementation approach and execution sequence, rather than to resolve major architectural ambiguity.
