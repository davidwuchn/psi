Goal: replace the Emacs session display and `/tree` picker with a dedicated session-tree buffer rendered with `magit-section`.

Context:
- Emacs currently exposes session navigation through existing session display surfaces and the `/tree` picker.
- The project direction prefers app-runtime ownership of canonical session/context semantics, with adapters responsible primarily for rendering and interaction.
- The new interaction should make session structure and message-level branching easier to browse and act on in Emacs.

Required behavior:
- Emacs provides a separate buffer for browsing sessions and their tree structure.
- That buffer renders the tree using `magit-section`.
- Selecting a session in the tree buffer focuses that session in the main psi buffer.
- Selecting a chat message in the tree buffer forks the session at that message and focuses the resulting fork in the main psi buffer.
- The tree buffer replaces the current session display surface and the `/tree` picker path for this workflow.

Acceptance:
- there is a dedicated Emacs buffer for session-tree browsing
- session and message hierarchy is rendered via `magit-section`
- selecting a session focuses that session in the psi buffer
- selecting a chat message forks from that message and focuses the new session in the psi buffer
- the old `/tree` picker path is no longer the primary interaction for this workflow
- behavior is covered by focused Emacs-facing proof

Constraints:
- preserve canonical backend ownership of session/context/tree semantics
- avoid rebuilding adapter-local semantics that already exist in shared runtime projections
- keep the change localized to this navigation workflow rather than broad UI reshaping
