Implementation notes:

- Task created to refine session-state semantics across Emacs footer/session-tree surfaces.
- Initial design direction:
  - `← current` for focused session identity
  - runtime badge text separate from focus identity
  - waiting-for-input should be phase-derived, not inferred from `not streaming`
  - color/faces allowed only as secondary reinforcement
