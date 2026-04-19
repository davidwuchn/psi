Goal: fix the bug where selecting an agent session via the `/tree` picker in Emacs switches the active session and updates footer/session display, but does not restore that session's chat transcript.

Context:
- The reported symptom is specific to the bare `/tree` picker flow in Emacs.
- After selecting the agent session, the footer/session display correctly reflects the selected session and its message count, but the transcript area remains empty.
- Direct session switching paths already have dedicated rehydration behavior in Emacs (`switch_session` followed by explicit transcript/tool-state rehydration), while the bare `/tree` picker path currently routes through backend frontend-action handling and canonical rehydrate events.
- This suggests a divergence between tree-picker selection rehydration and direct switch-session rehydration.

Required behavior:
- Selecting a session from the bare `/tree` picker restores the selected session's visible transcript in Emacs.
- Agent-session transcripts are restored reliably, including when the selected session was created by the `agent` tool.
- Session selection semantics remain correct: active session changes, footer/session summaries stay accurate, and no transcript from the previously selected session leaks through.
- The fix preserves canonical ownership boundaries unless a deliberate exception is justified.

Acceptance:
- reproducing the reported `/tree` picker selection against an agent session yields the selected session's transcript in the main Emacs buffer
- focused proof covers the failing picker-selection rehydration case
- direct `/tree <id>` or equivalent explicit switch flows continue to work
- no regression to footer/session-summary correctness during switch
- investigation identifies the concrete divergence/root cause between picker selection and transcript rehydration

Constraints:
- keep scope limited to this navigation/rehydration bug
- prefer a fix that reduces semantic divergence between session-switch paths
- avoid broad UI reshaping unless investigation shows it is necessary
