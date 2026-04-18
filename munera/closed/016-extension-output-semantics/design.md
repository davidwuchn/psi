Goal: make extension output semantics explicit by separating user-visible non-conversation messages from conversation-visible synthetic messages, and stop relying on stdout/`println` as a semantic output channel.

Context:
- Recent `/work-on` regression work showed that extension commands can already produce the intended visible outcome through explicit runtime messaging, while adapter/RPC stdout capture adds confusing duplicate or placeholder output.
- Current extension command handling still treats handler stdout as an implicit output surface in CLI, TUI, and RPC.
- The project needs two distinct extension-authored output forms:
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
- the current latent split depends on `:custom-type` conventions rather than an explicit contract

Current latent behavior to preserve conceptually but replace explicitly:
- `psi.extension/send-message` with `:custom-type` currently behaves like a user-visible non-conversation marker
- `psi.extension/send-message` without `:custom-type` currently behaves like a conversation-visible synthetic message
- `psi.extension/send-prompt` is a separate semantic path for causing a real future prompt turn and must remain distinct

Why this matters:
- user-visible notifications and LLM-visible conversation state are different artifacts
- extensions need to be able to communicate to the user without polluting future prompt context
- extensions also need to be able to inject synthetic conversational turns intentionally when workflow semantics require it
- ambiguous output contracts increase the chance of duplicate UI output, hidden context mutation, and adapter-specific behavior differences
- if the distinction is not explicit in runtime/journal semantics, future tooling and introspection will continue to guess wrong

Design decision:
Introduce an explicit two-surface extension message model and treat stdout capture only as compatibility fallback.

Required explicit message surfaces:

1. UI-only notification surface
- purpose: inform the human user
- visible in transcript/UI surfaces
- must not become part of future LLM-visible conversation assembly
- should be represented explicitly in journal/runtime state as a non-conversation artifact
- should support extension-specific rendering metadata where needed

2. Conversation-visible synthetic message surface
- purpose: intentionally mutate the conversation seen by future LLM turns
- visible in transcript/UI surfaces
- must become part of future LLM-visible conversation assembly
- must support explicit role selection at least for `user` and `assistant`
- should use the canonical message shape used by conversation reconstruction rather than side-channel rendering markers

Required separation from prompt execution:
- prompt execution is not the same as appending a synthetic conversation message
- `psi.extension/send-prompt` remains the surface for causing a real queued/deferred/immediate prompt turn
- this task must not blur `notify`, `append synthetic message`, and `run a prompt` into one API

API direction:
- add an explicit UI-only extension API/mutation
- add an explicit conversation-visible extension API/mutation
- keep existing `send-prompt` separate
- ambiguous `send-message` was treated as compatibility-only during migration and has now been removed

Naming direction:
The exact final names may vary, but the semantics are fixed. Preferred conceptual names are:
- `notify` / `send-notification` for UI-only output
- `append-message` for conversation-visible synthetic insertion

The task must choose one canonical naming set and apply it consistently across:
- extension API map
- mutation symbols
- runtime helper names
- docs/tests

Journal/runtime representation requirements:
- UI-only notifications and conversation-visible messages must remain distinguishable in persisted/runtime state
- future LLM conversation reconstruction must include only conversation-visible synthetic messages
- UI-only notifications must remain renderable/replayable without being mistaken for conversation context
- the distinction must not depend on adapters reparsing text

Adapter/runtime contract requirements:
- CLI, TUI, RPC, and Emacs must all observe the same semantic distinction
- adapters may continue rendering visible notifications, but they must not invent the semantic distinction themselves
- adapter-visible command output should no longer depend primarily on `println`
- stdout capture may remain temporarily as backward-compatibility fallback, but not as the preferred or normative extension contract

Migration requirements:
- migrate at least one existing extension path end-to-end to the explicit APIs
- `work_on` is the preferred initial migration target because it currently mixes explicit runtime messages and `println`
- the migration must remove semantic reliance on stdout for at least one slash-command path

Backward-compatibility guidance:
- existing extensions using `send-message` or `println` may need transitional support during migration
- `send-message` compatibility semantics were documented explicitly during the migration window and the compatibility layer has now been removed
- compatibility behavior must not block establishing the new explicit contract

Non-goals:
- do not redesign all extension APIs at once beyond the output seam
- do not broaden this task into general workflow/runtime architecture unless needed for the output split
- do not preserve stdout capture as the preferred contract just for backward familiarity
- do not blur notification semantics with prompt execution semantics
- do not rely on adapter-local heuristics as the long-term definition of extension output meaning

Acceptance:
- extension authors have an explicit API for UI-only messages
- extension authors have an explicit API for synthetic conversation-visible messages
- the difference is preserved in runtime behavior and future LLM-visible message assembly
- journal/runtime representation keeps the two message kinds distinguishable
- adapter-visible command output no longer depends primarily on `println`
- at least one existing extension path is migrated/proven against the new contract
- tests cover the distinction so future regressions cannot silently collapse UI-only and conversation-visible extension output back together
