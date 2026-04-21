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
