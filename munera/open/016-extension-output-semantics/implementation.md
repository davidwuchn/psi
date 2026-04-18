Implementation update

Completed the initial end-to-end 016 slice.

What landed
- `psi.extension/notify` and `psi.extension/append-message` were already present at the mutation/API/runtime seam; this pass finished the missing persistence and regression proof work.
- Extension-authored `notify` and `append-message` dispatch paths now both append canonical journal message entries in addition to updating runtime agent message state and emitting external-message UI events.
- This means extension-visible output now survives transcript rebuild / rehydration from journal instead of existing only in live agent-core memory.
- `:session/send-extension-message` now forwards `:session-id` explicitly to the chosen semantic handler, preserving session-scoped persistence/effect routing.
- Nullable extension test helpers now understand the explicit `psi.extension/notify` and `psi.extension/append-message` mutations rather than only the legacy `send-message` path.
- `send-message` compatibility still preserves the migration split:
  - with `:custom-type` => UI-only/non-conversation notification
  - without `:custom-type` => conversation-visible synthetic message
- `extensions.work-on` was already migrated to `:notify`; tests now no longer carry a dead local stdout-capture helper.

Why this mattered
- Before this pass, explicit extension output semantics existed live, but journal-backed transcript rehydration could drop extension-authored messages because only runtime agent state was updated.
- Since canonical transcript rebuild and provider request preparation both derive from the journal, persistence was required for the output split to be coherent across replay/resume.

Files changed
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`
- `components/extension-test-helpers/src/psi/extension_test_helpers/nullable_api.clj`
- `extensions/test/extensions/work_on_test.clj`

Verification
- Focused test run passed:
  - `extensions.work-on-test`
  - `psi.agent-session.extension-output-semantics-test`
  - `psi.agent-session.extensions-test`
  - `psi.agent-session.background-jobs-test`
- Result: `72 tests, 300 assertions, 0 failures`

Follow-on candidates
- Migrate additional extensions off compatibility `send-message` onto explicit `notify` / `append-message`.
- Consider whether background-job terminal emission should also move from compatibility `send-extension-message` usage to direct explicit notify semantics in the runtime callback surface.
- Tighten docs/comments that still describe `send-message` as the primary extension transcript API.
