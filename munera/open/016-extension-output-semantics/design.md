Goal: make extension output semantics explicit by separating user-visible non-conversation messages from conversation-visible synthetic messages, and stop relying on stdout/`println` as a semantic output channel.

Context:
- Recent `/work-on` regression work showed that extension commands can already produce the intended visible outcome through explicit runtime messaging, while adapter/RPC stdout capture adds confusing duplicate or placeholder output.
- Current extension command handling still treats handler stdout as an implicit output surface in CLI, TUI, and RPC.
- The project now needs two distinct extension-authored output forms:
  - a simple message for the user that does not become part of the LLM-visible conversation
  - a simulated user message or assistant response that does become part of the LLM-visible conversation
- These are semantically different and should not share one ambiguous API.

Problem statement:
Extension output semantics are currently underspecified and partially conflated.

Observed issues:
- `println`/stdout capture acts like an accidental cross-adapter output protocol
- extension authors cannot tell from the current API naming whether a message is UI-only or conversation-visible
- runtime and adapter behavior become harder to reason about when visible output can come from both explicit mutations and incidental stdout
- transcript visibility and model-visible history are not clearly separated at the extension API boundary

Why this matters:
- user-visible notifications and LLM-visible conversation state are different artifacts
- extensions need to be able to communicate to the user without polluting future prompt context
- extensions also need to be able to inject synthetic conversational turns intentionally when workflow semantics require it
- ambiguous output contracts increase the chance of duplicate UI output, hidden context mutation, and adapter-specific behavior differences

Required outcome at the task level:
- define explicit extension output semantics for:
  - UI-only/user-visible messages
  - conversation-visible synthetic messages
- align runtime/journal/adapter behavior to those semantics
- reduce or remove stdout capture as a semantic extension output mechanism
- provide a clear migration path for existing extensions such as `work_on`

Non-goals:
- do not redesign all extension APIs at once beyond the output seam
- do not broaden this task into general workflow/runtime architecture unless needed for the output split
- do not preserve stdout capture as the preferred contract just for backward familiarity
- do not blur notification semantics with prompt execution semantics

Acceptance:
- extension authors have an explicit API for UI-only messages
- extension authors have an explicit API for synthetic conversation-visible messages
- the difference is preserved in runtime behavior and future LLM-visible message assembly
- adapter-visible command output no longer depends primarily on `println`
- at least one existing extension path is migrated/proven against the new contract
