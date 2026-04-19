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

## Step 2 — decide source and representation
- Canonical source: backend-owned context session metadata plus canonical per-session runtime `:is-streaming` state.
- Semantic mapping for this task:
  - `active` ≡ session `:is-streaming true`
  - `idle` ≡ session present in context but not streaming
- Chosen footer representation: one compact backend-rendered line grouped by state, e.g. `sessions: active main · idle helper, notes`.
- Chosen transport shape: extend canonical footer payload with a dedicated `:session-activity-line` field rather than overloading extension `:status-line` text.
- Reasoning:
  - keeps activity semantics backend-owned
  - keeps Emacs projection-only
  - avoids mixing session activity with extension statuses
  - remains legible with multiple sessions while still concise
