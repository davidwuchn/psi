Implementation notes.

## Step 1 — inspect current footer/data path
- Footer semantics are backend-owned in `components/app-runtime/src/psi/app_runtime/footer.clj`.
- RPC `footer/updated` is emitted from `components/rpc/src/psi/rpc/events.clj` by flattening the footer model to canonical footer fields.
- Current footer payload shape is session-local and does not yet include any explicit per-session activity summary.
- Emacs footer rendering (`components/emacs-ui/psi-projection.el`) already prefers structured backend fields (`:usage-parts`, `:model-text`) and does not derive activity locally.
- Canonical session activity semantics already exist elsewhere in shared projections:
  - context snapshot slots include `:is-streaming`
  - session tree summaries render `[streaming]`
  - session summary exposes `:is-streaming` in shared session metadata
- Likely minimal direction: extend the shared footer model/payload with a compact backend-derived session activity fragment instead of teaching Emacs to infer activity from unrelated events.
