# Implementation notes

- Task created from observed mismatch between manifest effective state and startup activation state.
- Repro shape:
  - manifest shows enabled/effective extensions
  - startup summary shows zero loaded, zero errors
  - live extension details are empty
- Expected root cause: startup currently activates only `activation-plan` `:extension-paths`, which only covers `:local/root` entries.
- Likely implementation center:
  - `components/app-runtime/src/psi/app_runtime.clj`
  - `components/agent-session/src/psi/agent_session/extension_runtime.clj`
  - `components/agent-session/src/psi/agent_session/extension_installs.clj`
  - `components/agent-session/src/psi/agent_session/extensions.clj`
  - `components/agent-session/src/psi/agent_session/extensions/loader.clj`
- Desired end state: startup summary, live registry, and extension introspection all agree on what actually activated.

## Review note

Review outcome: **partial implementation; do not close task yet**.

What is implemented:
- startup now activates non-local manifest extensions via `:psi/init`
- non-file-backed extensions use the canonical registry identity `manifest:{lib}`
- reload/apply also supports init-var-backed activation
- targeted tests cover startup local-root success, startup git success, and reload git success

What remains before the task matches the design:
- startup still persists pre-activation install state rather than a truthful post-activation finalized apply state
- startup and reload/apply still do not share one fully unified activation path; bootstrap contains direct init-var activation logic
- acceptance coverage is incomplete:
  - startup mvn success test missing
  - startup dependency realization failure test missing
  - startup init resolution failure test missing
  - startup init execution failure test missing
  - explicit startup summary vs live registry convergence test missing
- docs update required by the task is still missing

Architectural concern:
- `bootstrap.clj` now contains special-case init-var extension activation logic rather than consuming one canonical manifest-aware activation layer used by both startup and reload/apply

Recommendation:
- keep the task open
- finish the missing acceptance tests
- unify activation behind one shared path
- persist truthful startup apply state after activation
