Implementation notes.

2026-04-18

Findings
- The intermittent disappearance was in the Emacs projection state layer, not backend footer semantics.
- The visible "session display" in the footer/projection area is the backend-projected session-tree widget carried on `context/updated` and stored in `psi-emacs-state-projection-widgets`.
- `psi-emacs--handle-context-updated-event` correctly installed that widget.
- But later unrelated `ui/widgets-updated` events replaced the entire local `projection-widgets` collection.
- Because the session-tree widget is not part of generic extension widget refresh payloads, those updates dropped it from frontend state until a later `context/updated` reintroduced it.
- That exactly matches the observed symptom: correct when present, disappears intermittently, later reappears.

Decision
- Preserve the backend-owned session-tree widget across unrelated `ui/widgets-updated` refreshes.
- Keep `context/updated` authoritative for whether the session tree should exist at all.
- Therefore:
  - `context/updated` still adds/removes the session tree based on canonical context semantics
  - `ui/widgets-updated` now preserves any already-installed session-tree widget while replacing other widgets

Implementation
- Added `psi-emacs--session-tree-widget-p` to identify the backend-owned session tree widget.
- Added `psi-emacs--projection-widgets-with-session-tree` to merge/deduplicate the preserved session tree with other widget updates.
- Updated `psi-emacs--handle-context-updated-event` to use the shared merge helper.
- Updated `ui/widgets-updated` event handling to preserve the installed session-tree widget instead of replacing the entire widget collection blindly.

Proof
- Added ERT regression test proving unrelated `ui/widgets-updated` events do not blank the session tree.
- Added ERT test proving a later single-session `context/updated` still removes the tree, so preservation does not override backend authority.
- `bb emacs:test` passes.

Trade-off
- This is a narrow frontend-state fix. It does not broaden backend semantics or duplicate ownership.
- The session tree remains backend-owned in meaning; Emacs now avoids accidentally discarding it during unrelated widget refreshes.
