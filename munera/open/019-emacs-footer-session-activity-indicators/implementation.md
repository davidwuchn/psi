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

## Step 3 — implement shared footer activity projection
- `components/app-runtime/src/psi/app_runtime/footer.clj`
  - added backend footer session label + activity-line helpers
  - footer model now accepts `:context-sessions`
  - footer model now exposes `:footer/session-activity {:line ...}` and flattened `:session-activity-line`
  - runtime footer model enriches context sessions with canonical per-session `:is-streaming`
  - preserved fallback `:cwd` support for TUI call sites still passing footer opts under the old key
- `components/rpc/src/psi/rpc/events.clj`
  - `footer/updated` now includes `:session-activity-line`
- `components/emacs-ui/psi-projection.el`
  - footer projection now renders the canonical session activity line between stats and extension status lines

## Step 4 — tests and verification
- `components/app-runtime/test/psi/app_runtime/footer_test.clj`
  - added coverage for compact multi-session activity grouping
- `components/rpc/test/psi/rpc_events_test.clj`
  - added coverage that `footer/updated` emits canonical session activity text
  - stabilized labels by setting an explicit root session name in the test
- `components/emacs-ui/test/psi-extension-ui-test.el`
  - extended canonical footer payload test to assert projection of `:session-activity-line`
- Verification:
  - `clojure -M:test --focus psi.app-runtime.footer-test --focus psi.rpc-events-test --focus psi.tui.app-view-runtime-test`
  - `bb emacs:test`
  - both pass on the touched surfaces
