Investigation notes
===================

Initial report:
- Selecting an agent session through bare `/tree` in Emacs updates footer/session display but shows no transcript messages.
- The same agent session appears to have the expected message count in session display/footer.

Initial hypothesis:
- Bare `/tree` picker selection and direct session switching use different rehydration paths.
- The picker path likely depends on backend `session/resumed` + `session/rehydrated` events, while direct switch flow in Emacs explicitly runs `switch_session` followed by `get_messages` and `query_eql`.
- The divergence may cause transcript rehydration to read from an incomplete/stale source for agent-created sessions.

Findings so far:
- The divergence is real:
  - direct `/tree <id>` / widget switch in Emacs goes through `switch_session` then explicit `get_messages` + `query_eql`
  - bare `/tree` picker goes through `ui/frontend-action-requested` → `frontend_action_result` → backend navigation event emission
- Backend picker-submit navigation currently emits `session/resumed`, `session/rehydrated`, `session/updated`, and `footer/updated` via `emit-navigation-result!`, but not `context/updated`.
- Agent child sessions are expected to have canonical journal state available:
  - `create-child-session` seeds child persistence journal from `:preloaded-messages`
  - later prompt execution records assistant replies through `:persist/journal-append-message-entry`
  - canonical `session-messages` / `get_messages` both rebuild from the in-memory journal, not agent-core message state
- There is already RPC proof that `switch_session` and `get_messages` both derive transcript from journal rather than stale in-memory agent messages.
- There is currently RPC proof for `frontend_action_result select-session` with fork payloads, but no corresponding proof for `frontend_action_result select-session` switching to an existing session and rehydrating transcript.

Current narrowed hypothesis:
- The bug is more likely in the picker selection switch path itself (or its frontend handling/proof gap) than in child-session journal persistence.
- The highest-value next step is to add focused proof for `frontend_action_result select-session` switching to an existing agent-created child session and observe whether backend rehydration already drops messages before they reach Emacs.
