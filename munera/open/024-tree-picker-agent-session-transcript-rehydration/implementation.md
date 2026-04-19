Investigation notes
===================

Initial report:
- Selecting an agent session through bare `/tree` in Emacs updates footer/session display but shows no transcript messages.
- The same agent session appears to have the expected message count in session display/footer.

Initial hypothesis:
- Bare `/tree` picker selection and direct session switching use different rehydration paths.
- The picker path likely depends on backend `session/resumed` + `session/rehydrated` events, while direct switch flow in Emacs explicitly runs `switch_session` followed by `get_messages` and `query_eql`.
- The divergence may cause transcript rehydration to read from an incomplete/stale source for agent-created sessions.
