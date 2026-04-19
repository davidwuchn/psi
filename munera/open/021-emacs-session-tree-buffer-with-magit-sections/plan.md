Approach:
- Treat this as an Emacs navigation-surface replacement, not a backend semantic redesign.
- Reuse existing canonical session/context/tree data where possible, and add backend support only if message-level fork targeting needs a clearer contract.
- Introduce a dedicated tree buffer with `magit-section` rendering and explicit actions for session focus and message-fork selection.

Likely steps:
1. inspect the current Emacs session display and `/tree` picker flow
2. define the dedicated buffer responsibilities, commands, and section structure
3. wire session selection to focus the selected session in the psi buffer
4. wire message selection to fork from the selected message and focus the resulting session
5. retire or demote the old `/tree` picker path for this workflow
6. add focused Emacs tests around rendering and interaction

Risks:
- coupling the new buffer too tightly to adapter-local reconstructed state
- introducing a fork-from-message contract that is underspecified or inconsistent with existing runtime routing
- letting the new tree buffer become a second source of truth for session semantics
