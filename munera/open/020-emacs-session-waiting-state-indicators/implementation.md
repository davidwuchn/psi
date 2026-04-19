Implementation notes:

- Task created to refine session-state semantics across Emacs footer/session-tree surfaces.
- Canonical user-facing vocabulary is now implemented in shared/runtime-facing projections:
  - `← current` for focused session identity
  - `[waiting]` for canonical phase `:idle`
  - `[running]` for canonical phase `:streaming`
  - `[retrying]` for canonical phase `:retrying`
  - `[compacting]` for canonical phase `:compacting`
- Shared app-runtime context snapshot now exposes per-session `:runtime-state`.
- Shared footer projection now groups all sessions by canonical runtime state and preserves all canonical phase buckets.
- RPC footer payload now exposes structured `:session-activity-buckets` in addition to the canonical text line.
- Shared session-tree widget lines now carry structured per-line metadata (`:runtime-state`, `:is-current`) alongside canonical text.
- Emacs now renders `← current` and runtime-state labels with dedicated faces.
- Waiting is visually emphasized over running.
- Clickable session-tree rows preserve fragment-level faces instead of overwriting them with a generic widget face.
- Current known limitation/work left:
  - footer structured activity faces are implemented from structured buckets
  - session-tree fragment faces are implemented from structured line metadata
  - other adapters have not yet adopted comparable fragment-level styling
