Implementation plan

Chosen canonical API names

Use the following explicit extension output surfaces:

1. `psi.extension/notify`
- semantic role: UI-only, user-visible notification
- visible to transcript/UI surfaces
- must not enter future LLM-visible conversation assembly
- may carry `:custom-type` for extension-specific rendering or grouping
- preferred extension API function name: `:notify`

2. `psi.extension/append-message`
- semantic role: synthetic conversation-visible message insertion
- visible to transcript/UI surfaces
- must enter future LLM-visible conversation assembly
- requires explicit `:role`
- supported roles for this task: `"user"` and `"assistant"`
- preferred extension API function name: `:append-message`

3. keep `psi.extension/send-prompt`
- semantic role: trigger a real future prompt turn
- not equivalent to notification or synthetic message insertion

Compatibility policy

- `psi.extension/send-message` was kept during migration only and has now been removed
- migration compatibility meaning was:
  - if `:custom-type` is present, preserve current UI-only behavior semantics
  - otherwise preserve current synthetic conversation-visible insertion semantics
- new code uses only `notify` and `append-message`
- stdout capture remains fallback-only during migration and is not part of the preferred contract

Concrete implementation slices

Slice 1 — runtime helper split
Files:
- `components/agent-session/src/psi/agent_session/extension_runtime.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`

Changes:
- split current extension message helper into two helpers:
  - `notify-extension-in!`
  - `append-extension-message-in!`
- split current dispatch behavior into two semantic handlers/events:
  - one path for UI-only notification
  - one path for conversation-visible append-message
- UI-only path must emit user-visible external message delivery without appending into agent-core history
- conversation-visible path must append into agent-core history and emit the same visible delivery

Slice 2 — extension mutations
Files:
- `components/agent-session/src/psi/agent_session/mutations/extensions.clj`
- `components/agent-session/src/psi/agent_session/extensions/runtime_eql.clj`

Changes:
- add mutation `psi.extension/notify`
- add mutation `psi.extension/append-message`
- include both in session-scoped extension mutation op allowlist
- remove `psi.extension/send-message` after migration is complete
- update mutation docs so semantics are explicit and unambiguous

Expected mutation shapes:
- `psi.extension/notify`
  - params: `:session-id`, `:content`, optional `:role`, optional `:custom-type`
  - role defaults to `"assistant"`
- `psi.extension/append-message`
  - params: `:session-id`, `:role`, `:content`
  - validate role ∈ {`"user"`, `"assistant"`}

Slice 3 — extension API map
Files:
- `components/agent-session/src/psi/agent_session/extensions/api.clj`

Changes:
- add API fns:
  - `:notify`
  - `:append-message`
- keep `:mutate` and `:send-prompt` paths intact
- optionally keep a backward-compatible helper if `:send-message` currently appears as a convenience path elsewhere, but the preferred public surface should become `:notify` and `:append-message`

Slice 4 — conversation assembly proof
Files:
- `components/agent-session/src/psi/agent_session/conversation.clj`
- relevant tests under `components/agent-session/test`

Changes:
- preserve the invariant:
  - notify/UI-only messages are excluded from LLM-visible conversation assembly
  - append-message synthetic conversation entries are included
- if current implementation still relies on `:custom-type` to exclude notifications, retain that only as internal representation detail, not as API contract
- add tests proving the explicit APIs preserve the distinction

Slice 5 — initial extension migration
Preferred target:
- `extensions/src/extensions/work_on.clj`

Changes:
- replace extension-owned visible message paths to use `:notify`
- remove semantic reliance on `println` for:
  - `/work-done`
  - `/work-rebase`
  - `/work-status`
- keep fallback printing only if needed for non-runtime contexts, but prefer the explicit mutation/API when runtime is available

Possible follow-on migration targets after the first slice lands:
- `extensions/src/extensions/lsp.clj`
- `extensions/src/extensions/agent_chain.clj`

Slice 6 — adapter/runtime compatibility tests
Files:
- `components/rpc/test/...`
- `components/tui/test/...`
- `components/agent-session/test/...`
- extension tests for `work_on`

Required tests:
1. `notify` emits visible output but does not alter future LLM conversation assembly
2. `append-message` emits visible output and does alter future LLM conversation assembly
3. migrated `work_on` command paths no longer depend on stdout for semantic output
4. RPC/TUI no longer need placeholder semantics for migrated explicit-output paths
5. removal of `send-message` does not break migrated explicit-output paths

Ambiguities resolved for implementation

1. Should `notify` appear in transcript?
Yes.
- It is user-visible and transcript-visible.
- It must not be model-visible.

2. Should `notify` be stored at all?
Yes.
- It should be representable in runtime/journal state so replay/rendering/introspection stay coherent.
- It must be distinguishable from conversation-visible messages.

3. Should `append-message` support arbitrary roles?
Not in this task.
- restrict to `"user"` and `"assistant"`
- avoid reopening tool-message semantics here

4. Should `send-prompt` be collapsed into append-message with execution?
No.
- prompt execution remains a separate path

5. Should stdout capture be removed immediately?
No.
- keep it as compatibility fallback during migration
- do not treat it as normative contract

Definition of done for implementation

- explicit `notify` and `append-message` APIs exist end-to-end
- runtime behavior distinguishes them correctly
- conversation assembly includes only append-message synthetic messages
- `work_on` uses the explicit output API instead of stdout for semantic messages
- tests prove the distinction and prevent regression
