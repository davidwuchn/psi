Goal: fix `/work-on` so it establishes one coherent workspace transition in both transcript and runtime session state.

Context:
- We observed `/work-on psi-tool` recorded as a user slash-command message without a corresponding assistant-visible confirmation message.
- We also observed the live session still reporting the old `:worktree-path` after `/work-on`, causing later orientation and task creation to happen in the wrong worktree.
- Current `work-on` behavior appears to create or switch sessions and print a summary, but the resulting workspace transition is not durably represented in the current session transcript/state contract strongly enough.

Acceptance:
- `/work-on <description>` injects an assistant-visible message describing the resulting worktree/session outcome.
- The active session runtime state reflects the resulting worktree via canonical `:worktree-path`.
- Transcript evidence and runtime introspection agree after `/work-on`.
- Tests cover both transcript visibility and worktree-path state coherence.
