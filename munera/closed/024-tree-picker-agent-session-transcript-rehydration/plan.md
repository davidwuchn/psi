Approach:
- Start by reproducing the failure in code-level proof rather than relying only on manual Emacs behavior.
- Compare the bare `/tree` picker submit flow with direct `/tree <id>`/`switch_session` flow across Emacs, RPC, and app-runtime.
- Determine whether the bug is caused by:
  1. frontend-action selection taking a weaker rehydration path than direct switching, or
  2. backend `session/rehydrated` using the wrong transcript source for these sessions.
- Prefer the smallest fix that restores transcript correctness and reduces semantic divergence between switch paths.

Risks:
- There may already be tests asserting the current picker path shape, so fixing the behavior may require adjusting proof rather than only production code.
- Agent-created sessions may differ from ordinary child sessions in when/how journal state is available.
