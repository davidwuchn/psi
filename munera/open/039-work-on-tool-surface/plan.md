Approach:
- keep the change small and extension-local
- refactor `extensions.work-on` around one canonical private `work-on` execution function
- make the existing `/work-on` command wrapper a presenter over that result
- register a new `work-on` extension tool whose `:execute` wrapper is the corresponding tool presenter
- prove parity with focused tests that assert shared operational behavior and side effects

Implementation shape:
1. Inspect and isolate the existing `work-on!` behavior
- review current helper boundaries in `extensions.work-on`
- identify the smallest private execution function that can own the operational path without pulling presentation concerns with it
- keep helper reuse where it already exists rather than broad restructuring

2. Canonicalize the `work-on` operation result
- ensure the shared execution function always returns one canonical result shape for success and failure
- include enough detail for both command summary rendering and tool `:details`
- preserve existing command-visible wording by deriving it from the result instead of duplicating logic

3. Split operation from presentation
- add a command-facing presenter that maps canonical result -> assistant-visible message
- add a tool-facing presenter that maps canonical result -> `{:content ... :is-error ... :details ...}`
- ensure the tool path does not append transcript messages

4. Register the tool
- add a `work-on` extension tool registration in `init`
- use a minimal parameter schema with required `description`
- keep the command registrations unchanged aside from calling the shared presenter/execution path

5. Add focused tests
- extend init coverage to prove `work-on` tool registration exists
- add focused tests for tool invocation covering:
  - happy path
  - usage error
  - worktree/session reuse path
  - active session targeting parity where feasible with existing seams
- keep assertions centered on:
  - shared state mutations
  - session create/switch behavior
  - tool return shape
  - absence of command-style append-message behavior on the tool path

6. Verify and record findings
- run focused `extensions.work-on` tests
- if workflow-facing coverage is naturally available, run the smallest relevant follow-on test set
- append implementation notes describing whether the chosen boundary remained clean and what compromise, if any, was accepted

Key decisions to preserve during implementation:
- only `work-on` becomes a tool in this task
- the shared execution function remains private and extension-local
- parity means the same operational semantics and side effects, not identical transcript behavior
- no broader dispatch/effects redesign in this slice

Risks / watchpoints:
- avoid accidentally changing `/work-on` wording or sequencing beyond what is necessary for deduplication
- avoid making tool invocation append assistant messages
- avoid leaking a second semantic path by shaping command and tool outputs separately from different source logic
- keep tests focused; do not broaden into `work-done`/`work-rebase`/`work-status`
