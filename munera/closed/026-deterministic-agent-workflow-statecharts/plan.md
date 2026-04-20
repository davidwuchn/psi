Approach:
- Build a first-class workflow runtime primitive with a workflow-facing authoring/query/tool surface and a statechart-backed execution core.
- Keep slice one intentionally narrow: sequential workflows, agent-backed executors only, mandatory structured result envelopes, simple retry semantics, blocked/user-decision handling, and strong introspection from day one.
- Use `psi-tool` workflow operations as a primary proving surface, while also making the runtime suitable to back `agent-chain` behavior.
- Reuse existing canonical sessions and dispatch/statechart machinery rather than introducing a parallel execution substrate.

Implementation slices:
1. establish workflow domain model and canonical state placement
   - add workflow definition/run entities to canonical root state
   - define stable ids, status enums, step/attempt nesting, and workflow↔session linkage attrs
   - keep sessions in the existing sessions map; mark workflow-owned execution sessions explicitly

2. define workflow schemas and validation contracts
   - add malli schemas for workflow definitions, step definitions, runs, attempts, result envelopes, retry policy, and capability policy
   - keep the workflow authoring surface workflow-oriented rather than exposing raw statechart structures
   - ensure schemas are suitable for inline/ad-hoc definitions captured as effective run snapshots

3. compile workflow definitions to statechart-backed execution semantics
   - define the translation from sequential workflow definitions into runtime statechart progression
   - model explicit events and transitions for run creation, start, attempt start, result receipt, validation, retry, block, resume, cancel, completion, and failure
   - keep validation and transition decisions pure where possible; reserve effects for session creation and execution invocation

4. implement execution-session orchestration for workflow step attempts
   - create one canonical execution session per step attempt
   - bind workflow metadata onto execution sessions for introspection and queryability
   - materialize execution input from canonical input bindings rather than prompt-copy handoffs
   - constrain slice one to agent-backed executors

5. implement structured result submission, validation, and progression
   - accept mandatory structured result envelopes with at least `:ok` and `:blocked` outcomes
   - validate result envelopes against the declared step result schema before advancement
   - implement simple runtime-owned retry behavior for validation and execution failure cases
   - ensure blocked workflows resume via new attempts rather than mutating prior attempts

6. expose workflow definitions and runs through introspection
   - add Pathom/EQL support for workflow definitions, runs, steps, attempts, statuses, history, retry configuration, result envelopes, validation outcomes, blocked payloads, and workflow↔session relationships
   - make session-centric queries able to discover workflow ownership/context via linkage attrs
   - ensure the query surface is sufficient for deterministic debugging without requiring adapter-specific reconstruction

7. expose workflows through `psi-tool`
   - extend `psi-tool` with `action: "workflow"`
   - implement at least `list-definitions`, `create-run`, `read-run`, `list-runs`, `resume-run`, and `cancel-run`
   - support both registered workflow definitions and inline ad-hoc definitions when creating runs
   - keep advancement runtime-owned; `psi-tool` should not offer a low-level manual advance op

8. prove the slice with a concrete chain-like workflow
   - add focused tests for a representative sequential workflow such as plan→build→review
   - prove run creation, attempt/session creation, structured result acceptance, validation failure, retry, blocked/resume, cancellation, terminal completion, and introspection visibility
   - use this proof as the basis for reimplementing `agent-chain` behavior on top of the workflow runtime

9. integrate cautiously with existing surfaces
   - decide whether to add a minimal built-in workflow definition registry in slice one or start with inline definitions plus a small registered set
   - connect the new runtime to `agent-chain` only after the lower-level primitive and `psi-tool` surface are proven
   - avoid broad UI redesign in this slice; keep adapter changes minimal unless required for visibility/debugging

Risks:
- leaking statechart implementation concerns into the workflow authoring/API surface
- over-generalizing beyond sequential agent-backed workflows before the first slice is proven
- duplicating session semantics instead of reusing canonical session infrastructure cleanly
- under-specifying workflow↔session linkage and making introspection weaker than intended
- allowing `psi-tool` workflow operations to bypass runtime-owned progression semantics
- letting `agent-chain` migration pressure expand the first slice beyond the agreed scope
