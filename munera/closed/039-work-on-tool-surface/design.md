Goal: make the `work-on` extension provide an LLM-callable extension tool for `work-on` in addition to its existing slash command, with the tool and command surfaces sharing one canonical implementation.

Context:
- today `extensions.work-on` registers only extension commands:
  - `work-on`
  - `work-done`
  - `work-rebase`
  - `work-status`
- it does not register any tool surface
- this blocks workflows and other tool-only execution paths from using `work-on`, even when the extension is loaded
- the desired direction is not two diverging implementations; command and tool should route through the same underlying behavior
- there is architectural uncertainty about the cleanest boundary for this in the current system, especially with respect to resolvers, effects, and the dispatch pipeline

Problem:
- command-only extension capabilities are not available to tool-only execution environments
- workflow prompts that need `work-on` cannot rely on slash-command execution
- naively adding a parallel tool implementation risks semantic drift between command and tool behavior
- forcing the implementation through a broad dispatch/effect redesign in this slice would add scope and delay the enabling change

Decision:
- this slice adds a single extension tool: `work-on`
- it does not add tool surfaces for `work-done`, `work-rebase`, or `work-status`
- `extensions.work-on` will expose one extension-local canonical execution function for the `work-on` operation
- both entrypoints will call that same execution function:
  - `/work-on` command wrapper
  - `work-on` tool `:execute` wrapper
- the shared execution function owns operational behavior and canonical result shaping
- wrappers differ only in presentation:
  - command wrapper appends assistant-visible text to the session transcript
  - tool wrapper returns a tool result payload
- tool invocation does not also append an assistant message; parity here means shared operational behavior and shared result semantics, not identical transcript side effects

Shared implementation boundary:
- introduce or refactor toward a canonical extension-local `work-on` operation function
- that function should remain private to `extensions.work-on`
- that function may remain runtime/IO-bound in this slice
- it is responsible for:
  - validating input
  - reading current session/worktree state
  - deriving branch/worktree target
  - creating or reusing the git worktree
  - updating the active session worktree-path
  - creating or switching to the appropriate session
  - returning a canonical success/error result map
- this boundary is intentionally extension-local for now rather than a larger architectural migration into new dispatch-owned effects

Canonical operation result:
- success shape should be a single result map equivalent to:
  - `{:ok? true`
  - ` :action :work-on`
  - ` :worktree-path ...`
  - ` :branch-name ...`
  - ` :session-id ...`
  - ` :session-name ...`
  - ` :reused? true|false}`
- failure shape should be a single result map equivalent to:
  - `{:ok? false`
  - ` :action :work-on`
  - ` :error ...}`
- exact field names may vary slightly if needed for local consistency, but one canonical result contract must be shared by both wrappers

Tool surface:
- register one extension tool named `work-on`
- parameter shape should be tool-friendly and minimal:
  - object with required `description` string
- tool return contract should be explicit:
  - success returns `{:content <summary-text> :is-error false :details <canonical-result>}`
  - failure returns `{:content <error-text> :is-error true :details <canonical-result>}`
- tool result text should be derived from the canonical operation result rather than from separate logic
- structured `:details` should mirror the canonical operation result so tool callers and tests can inspect the same shared semantics

Command surface:
- preserve existing `/work-on` behavior from the user’s point of view
- existing `/work-on` user-facing behavior and success/error wording should remain unchanged unless a small wording adjustment is necessary to remove duplication cleanly
- command wrapper should become a presenter over the canonical operation result
- assistant-visible message text should be derived from that result rather than re-implementing logic separately

Session side-effect parity:
- tool invocation performs the same session/worktree side effects as `/work-on`
- if an existing worktree session is found, tool invocation switches to it just as the command path does
- if no corresponding session exists, tool invocation creates the corresponding session just as the command path does
- parity for this task includes these side effects, not only returned content

Runtime ownership and architectural stance:
- the current extension API is sufficient for a small clean enabling slice
- this task does not attempt to redesign the full extension/runtime/dispatch/effects architecture
- the chosen implementation boundary is an intentional interim compromise:
  - acceptable for now because it preserves one canonical behavior path
  - explicitly documented as extension-local runtime ownership rather than a final system-wide pattern for all side effects

Scope:
- design and implement a shared `work-on` execution path usable from both extension command and extension tool entrypoints
- register an extension tool for `work-on`
- preserve existing `/work-on` command behavior
- add focused tests proving command/tool parity through the shared implementation path
- document the architectural compromise around runtime/effect ownership

Non-goals:
- redesigning the full extension architecture in this slice
- migrating all extension commands to tools
- adding tool surfaces for `work-done`, `work-rebase`, or `work-status`
- forcing all git/worktree side effects under dispatch-owned effects unless that falls out naturally from a small clean change
- adding broad workflow capability redesign beyond enabling `work-on` as a tool

Focused test expectations:
- tool registration exists for `work-on`
- command registration remains intact
- command and tool wrappers both call the same shared execution path
- parity is proven for representative cases such as:
  - happy path
  - usage/validation error
  - existing worktree reuse
  - active-session targeting after session changes
- tests should assert shared operational outcomes, session/worktree side effects, and result/message shaping, not that tool invocation appends command-style transcript entries

Acceptance:
- `extensions.work-on` provides a callable `work-on` tool surface
- `/work-on` and tool `work-on` share one canonical execution implementation
- the canonical execution path owns validation, session targeting, worktree mutation/reuse, session create/switch behavior, and result shaping
- the command wrapper owns assistant-message presentation only
- the tool wrapper owns tool-return presentation only
- focused tests prove command/tool parity for the shared implementation path, including session/worktree side effects
- the task records that the current implementation boundary is an intentional extension-local interim compromise rather than a full dispatch/effects redesign
