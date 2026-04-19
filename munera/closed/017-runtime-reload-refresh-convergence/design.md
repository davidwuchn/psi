Goal: define a canonical post-code-reload runtime refresh pass for psi so code reload can be followed by one explicit convergence step that refreshes known stale executable wiring and reports honest limitations.

Context:
- psi now has explicit interest in live runtime code reload through `psi-tool` and related self-repair workflows.
- runtime audit shows that many important live structures survive namespace reload because they live in `defonce` atoms or long-lived `ctx` maps.
- this is useful for preserving sessions, UI focus, process-scoped state, and other durable runtime data.
- however, namespace reload alone does not guarantee runtime coherence, because surviving registries and installed closures may still point at old code.
- the main reload hazard is therefore not loss of state, but stale executable wiring and mixed old/new behavior after reload.

Problem statement:
psi lacks one first-class, explicit runtime refresh pass for post-reload convergence of long-lived registries, installed callbacks, and runtime-owned hooks.

As a result, code reload can leave the runtime in a partially updated state where:
- query env does not match reloaded resolvers/mutations
- dispatch handler registry still points at old handler fns
- extension registries/runtime wiring still point at old code
- ctx-installed closure callbacks still use old logic
- already-running loops continue with old request/dispatch closures
- runtime data survives, but runtime behavior does not fully converge

Design decision:
This concern is a separate architectural task from raw code reload and from direct project nREPL support.

This task defines a single canonical phase that follows code reload:
1. code reload
2. runtime refresh pass

Task `014-psi-tool-code-reload` may invoke this pass, but does not own its design.

Canonical distinction:
There are two separate phases in live reload work:

1. code reload
- reload namespaces/vars/classes
- updates code definitions in the running process

2. runtime refresh pass
- refresh known persistent registries/hooks/callbacks/derived envs that still hold old function values or stale derived state
- re-establish as much coherence as psi can without claiming full process restart semantics

This task owns phase 2.

Canonical problem framing:
Some runtime surfaces are safe to preserve as data, but unsafe to preserve as executable wiring.

Therefore the refresh pass must:
- preserve normal runtime/session data by default
- refresh known executable registries and installed hooks explicitly
- report boundaries that cannot be safely hot-refreshed in place

Known surfaces requiring refresh consideration:

1. query graph globals
- resolver registry
- mutation registry
- current compiled env

2. dispatch globals
- handler registry
- event log
- dispatch trace
- interceptor override state

3. extension/runtime wiring
- extension registry/runtime wiring
- extension-owned prompt/tool/handler/runtime surfaces affected by extension reload

4. installed runtime hooks
- extension run fn atom
- background-job UI refresh fn atom
- other closure-backed installed hooks discovered during implementation if they cannot be safely ignored

5. non-hot-refreshable boundaries
- long-lived request/transport/service loops that still hold old closure captures
- other running threads/loops that cannot be rewritten in place safely

Canonical runtime refresh pass:
The refresh pass is one named operation with a fixed internal order.

Its purpose is not to be a configurable mini-framework.
Its purpose is to make psi runtime coherence explicit and testable after code reload.

Canonical refresh phases:

1. refresh query graph
Purpose:
- ensure the runtime resolver/mutation registration surface and compiled env match the reloaded code

Canonical authority:
- the canonical query registration functions/surfaces are the source of truth for this phase

First-slice semantics:
- refresh resolver registrations
- refresh mutation registrations
- rebuild compiled query env
- replace stale executable registrations rather than merely appending new ones
- if the implementation uses clear-and-re-register semantics followed by env rebuild, that is the intended first-slice behavior
- report success/failure distinctly for this phase

2. refresh dispatch handlers
Purpose:
- replace stale handler fn values in the dispatch handler registry

Canonical authority:
- the canonical dispatch handler registration path is the source of truth for this phase

First-slice semantics:
- refresh handler registrations explicitly
- stale handler entries must not survive silently
- clear-and-re-register semantics for executable handler entries is the intended first-slice behavior
- event log and dispatch trace are preserved by default unless a future explicit mode says otherwise

3. refresh extensions
Purpose:
- refresh extension runtime wiring when extension code/runtime composition is affected

Canonical authority:
- the canonical extension reload path is the source of truth for this phase

First-slice semantics:
- refresh extension registry/runtime wiring explicitly
- avoid stale extension handlers, prompt contributions, tool defs, or runtime hooks persisting unintentionally when extension refresh is requested by the pass
- use the canonical extension reload path rather than ad hoc namespace reload assumptions
- extension-local preserved data/state is not reset by default unless that canonical reload path explicitly does so
- report success/failure distinctly for this phase

4. reinstall known runtime hooks
Purpose:
- reinstall known installed executable hooks that do not automatically follow var reload

This phase must include exactly these first-slice reinstall targets:
- extension run fn reinstall
- background-job UI refresh fn reinstall

Semantics:
- refresh these known installed fn surfaces explicitly
- runtime refresh does not recreate ctx as part of the first slice
- closure-backed ctx-installed callbacks beyond these explicit reinstall targets are out of scope for first-slice refresh and must be reported as limitations unless later design work adds them explicitly
- known session-scoped hook reinstalls must apply across the live sessions that currently depend on those hooks, not just the active session

5. report limitations
Purpose:
- report non-hot-refreshable boundaries that remain outside in-place refresh guarantees

Semantics:
- identify/report boundaries such as already-running loops/threads that still hold old closure captures
- identify/report in-flight work that is not guaranteed to be rebound to refreshed code, including active prompt runs, background jobs, and similar active runtime work when relevant
- this phase is diagnostic, not mutating
- limitations affect the final overall status honestly

Canonical limitation entry shape:
- `:boundary`
- `:reason`
- `:remediation` describing the likely next action such as restart, reconnect, or recreate-ctx when applicable

Canonical refresh order:
The pass always runs phases in this order:
1. query graph
2. dispatch handlers
3. extensions
4. known runtime hooks
5. limitations

Why this order:
- query surfaces should be current before higher-level runtime flows depend on them
- dispatch handler refresh should happen before extension/runtime flows depend on dispatch behavior
- extension refresh should happen before reinstallation of known runtime hooks that may depend on extension/runtime wiring
- limitations are reported after active refresh work completes

Canonical request model:
This task does not define the final user-facing tool or mutation, but it does define the runtime request semantics.

For now, the canonical request model is intentionally simple:
- request a full runtime refresh pass
- no selective unit picking
- no custom refresh ordering

If finer-grained refresh requests are ever needed later, they can be added after the full-pass behavior is proven useful.

Canonical result model:
A runtime refresh result is a structured report.
It is not a boolean.

It must include:
- `:psi.runtime-refresh/status`
- `:psi.runtime-refresh/steps`
- `:psi.runtime-refresh/limitations`
- `:psi.runtime-refresh/duration-ms`

Canonical status values:
- `:ok`
  - refresh phases succeeded and no reported limitation invalidates the claimed convergence
- `:partial`
  - one or more phases failed partially or fully
  - or one or more reported limitations mean psi cannot honestly claim full in-place convergence for the requested pass
- `:error`
  - refresh request was invalid or the pass could not perform meaningful refresh work

Canonical step result shape:
Each step result must include:
- `:step`
- `:status` = `:ok` | `:partial` | `:error` | `:skipped`
- `:summary`
- optional `:details`
- optional structured `:error`

Structured error summary:
- `:message`
- `:class`
- `:phase`
- sanitized `:data` when available

Best-effort / non-atomic semantics:
- runtime refresh is best-effort and non-atomic
- successful earlier phases are not rolled back because a later phase fails
- partial convergence is expected and must be surfaced honestly
- preserving process continuity is preferred over pretending refresh is transactional

Preservation policy:
The refresh pass preserves normal runtime/session data by default.
This includes, unless a future explicit mode says otherwise:
- canonical root `:state*` data
- app-runtime `session-state` atom contents
- runtime nREPL metadata atom contents
- dispatch event log
- dispatch trace
- TUI global ctx identity
- extension-local preserved data/state atoms unless extension refresh explicitly replaces an executable registry surface

Important clarification:
Preserving these data surfaces does not imply executable wiring inside or around them is current.
That is the reason this refresh pass exists.

Non-hot-refreshable boundary policy:
The refresh pass must not claim that already-running loops/threads are always rewritten in place.

Explicit current boundary class:
- long-lived request/transport loops with closure-captured handlers
- active background or service loops whose closures were installed before reload
- in-flight prompt/background/service work still executing against pre-refresh closures
- any other installed runnable whose function identity is not replaced by the refresh pass

Protocol requirement:
- report these boundaries explicitly in `:psi.runtime-refresh/limitations`
- do not silently treat them as refreshed
- do not require full process restart for all refresh operations, but do not falsely claim full convergence where restart/reconnect is still required

Relationship to task `014-psi-tool-code-reload`:
- task 014 may call this refresh pass as its post-code-reload runtime refresh layer
- task 014 should report code reload and runtime refresh as distinct phases
- this task owns the runtime refresh pass and its result semantics

Relationship to task `015-direct-project-repl-support`:
- project nREPL may become one way to trigger code reload or invoke refresh logic
- this task is not about project REPL transport/lifecycle
- it is about psi runtime coherence after code reload inside the psi process

Non-goals:
- do not redefine this as a full process restart mechanism
- do not promise transparent in-place replacement of every running thread/loop
- do not make this a configurable refresh-unit framework yet
- do not blur preserved data with refreshed executable wiring
- do not silently rely on namespace reload alone for correctness
- do not make `psi-tool` the only conceptual home of runtime refresh

Docs requirements:
The task is not complete until docs explain:
- the difference between code reload and runtime refresh
- the fixed refresh phases and their order
- default preservation policy
- partial convergence semantics and limitation reporting
- relationship to `014-psi-tool-code-reload`

Test/design-proof requirements:
The task is not complete until tests prove at least:

1. query convergence
- refreshed resolver/mutation registrations are effective
- rebuilt query env reflects the refreshed graph
- stale query env behavior does not persist after successful query refresh

2. dispatch convergence
- refreshed dispatch uses newly registered handler fns rather than stale ones
- logs/traces are preserved by default while executable handler entries are refreshed

3. extension convergence
- requested refresh updates extension runtime wiring explicitly
- stale extension-owned executable behavior does not silently persist when refresh is requested

4. installed-hook convergence
- extension run fn and background-job UI refresh fn are reinstalled or explicitly reported if not refreshable in place

5. honest limitation reporting
- boundaries that remain old-closure-driven after refresh are surfaced in `:psi.runtime-refresh/limitations`
- overall status becomes `:partial` when full in-place convergence cannot honestly be claimed

Acceptance:
- a separate task exists for runtime refresh convergence after code reload
- the task clearly distinguishes code reload from post-reload runtime refresh
- the design defines one canonical runtime refresh pass with fixed phases, preservation policy, and result semantics
- the design identifies the main persistent registries/hooks/callbacks that require explicit refresh consideration
- the design frames task 014 as a consumer of this runtime refresh pass rather than the owner of the concern
- placeholder planning/implementation surfaces exist for future refinement