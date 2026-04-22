Goal: make the `work-on` extension provide a tool in addition to its existing slash commands, with the tool and command surfaces sharing one canonical implementation.

Context:
- today `extensions.work-on` registers only extension commands:
  - `work-on`
  - `work-done`
  - `work-rebase`
  - `work-status`
- it does not register any tool surface
- this blocks workflows and other tool-only execution paths from using `work-on`, even when the extension is loaded
- the desired direction is not two diverging implementations; commands and tools should route through the same underlying behavior
- there is architectural uncertainty about the cleanest boundary for this in the current system, especially with respect to resolvers, effects, and the dispatch pipeline

Problem:
- command-only extension capabilities are not available to tool-only execution environments
- workflow prompts that need `work-on` cannot rely on slash-command execution
- naively adding a parallel tool implementation risks semantic drift between command and tool behavior
- forcing the implementation through an ill-fitting resolver/effect path could increase architectural debt

Desired behavior:
- `extensions.work-on` exposes a tool surface for at least the current `work-on` capability
- the tool and the existing command use the same core implementation path
- behavior, validation, session targeting, worktree mutation, and user-visible summaries stay coherent across command and tool invocation
- the chosen implementation boundary should be explicit about whether it is:
  - acceptable as-is within the current extension/runtime model, or
  - a temporary adapter pending cleaner dispatch/effect integration

Scope:
- design and implement a shared `work-on` execution path usable from both extension command and extension tool entrypoints
- register an extension tool for `work-on`
- preserve existing command behavior
- add focused tests proving command/tool parity for the shared implementation
- document any architectural compromise around dispatch/effects/runtime ownership

Non-goals:
- redesigning the full extension architecture in this slice
- migrating all extension commands to tools
- forcing all git/worktree side effects under dispatch-owned effects unless that falls out naturally from a small clean change
- adding broad workflow capability redesign beyond enabling `work-on` as a tool

Key design questions to resolve:
1. shared implementation boundary
- should `extensions.work-on` factor a pure or mostly-pure core operation that both command and tool wrappers call?
- what parts must remain runtime/IO-bound today?

2. invocation shape
- should the tool expose only `work-on`, or also `work-done`, `work-rebase`, and `work-status` as one multi-action tool?
- what parameter shape best matches current command behavior while remaining tool-friendly?

3. runtime ownership
- is the current extension API sufficient for a clean shared implementation?
- if not, what minimal adapter/seam is acceptable without broad redesign?

4. session targeting and user-visible output
- how should tool invocation surface the same assistant-visible summaries currently appended by commands?
- what result shape should the tool return versus what it appends into session history?

Acceptance:
- `extensions.work-on` provides a callable tool surface
- command and tool entrypoints share one canonical implementation
- focused tests prove parity for the shared implementation path
- the task records whether the current implementation boundary is architecturally clean or an intentional interim compromise