2026-04-18 — Step 1: config + targeting scaffolding

- Added `components/agent-session/src/psi/agent_session/project_nrepl_config.clj`.
- Chose existing config homes rather than inventing a new file:
  - user: `~/.psi/agent/config.edn`
  - project: `<worktree>/.psi/project.edn`
  - shape: `{:agent-session {:project-nrepl ...}}`
- Added explicit helpers for:
  - merged project-nREPL config resolution (system < user < project)
  - target worktree resolution with required explicit semantics
  - absolute-directory validation for target worktrees
  - started-mode command-vector validation
  - attach-mode host/port validation
  - `.nrepl-port` file discovery + parsing
- Added focused tests in `project_nrepl_config_test.clj` covering merge precedence, targeting precedence, validation, and `.nrepl-port` parsing.
- Verified alongside existing config-resolution tests via direct `clojure.test` invocation because the project `:test` alias is wired to Kaocha CLI semantics.

2026-04-18 — Step 2: managed runtime skeleton

- Added `components/agent-session/src/psi/agent_session/project_nrepl_runtime.clj`.
- Introduced a dedicated ctx-owned `:project-nrepl-registry`, separate from generic extension managed services and separate from psi runtime nREPL metadata.
- Registry is keyed by canonical absolute worktree path.
- Added runtime operations for:
  - ensure instance
  - replace instance
  - update instance
  - remove instance
  - list/count/worktree-paths
- Current skeleton enforces:
  - one managed instance slot per worktree
  - matching ensure requests reuse the active slot
  - conflicting acquisition attempts throw and require explicit replace
- Current projected runtime slot shape already carries:
  - acquisition mode
  - lifecycle state
  - transport kind `:nrepl`
  - endpoint / command-vector
  - single-session model metadata
  - active-session-id placeholder
  - last-error placeholder
  - timestamps
- Wired the registry into both production context creation and agent-session test support.
- Added focused runtime tests in `project_nrepl_runtime_test.clj` covering create/reuse/conflict/replace/update/remove behavior.

2026-04-18 — Step 3: started-mode launch + endpoint discovery

- Added `components/agent-session/src/psi/agent_session/project_nrepl_started.clj`.
- Implemented first started-mode acquisition slice:
  - validate command vector
  - launch process in the target worktree
  - poll `.nrepl-port` in that worktree until ready / timeout / process exit
  - update the managed instance slot to `:ready` with discovered endpoint and runtime process handle
  - project failures as `:failed` with structured `:last-error`
- Added focused tests in `project_nrepl_started_test.clj` covering:
  - endpoint discovery after delayed `.nrepl-port` creation
  - early process exit failure
  - successful started acquisition projection
  - startup failure projection to failed state

2026-04-18 — Step 4: started-mode client session establishment

- Added `components/agent-session/src/psi/agent_session/project_nrepl_client.clj`.
- Started-mode acquisition now continues after `.nrepl-port` discovery to:
  - connect to the discovered endpoint with `nrepl.core/connect`
  - build a client with `nrepl.core/client`
  - open a managed single client session with `nrepl.core/client-session`
  - extract and project the active nREPL session id
- Managed instance projection now gains first real capability/session facts:
  - `:active-session-id`
  - `:can-eval? true`
  - `:can-interrupt? true`
  - runtime handle fields for transport/client/client-session/session-id
- Disconnect support now closes the client transport before stopping/removing started instances.
- This completes the first-slice started-mode readiness invariant more faithfully:
  - launched
  - endpoint discovered
  - connected
  - managed client session established
- Added focused tests in `project_nrepl_client_test.clj` for connect/disconnect behavior.
- Updated started-mode tests so the success path includes the client-session establishment step.

2026-04-18 — Step 5: projected query surface

- Added `components/agent-session/src/psi/agent_session/resolvers/project_nrepl.clj`.
- Project nREPL registry/instance state is now queryable through Pathom.
- Added root-level attrs:
  - `:psi.project-nrepl/count`
  - `:psi.project-nrepl/worktree-paths`
  - `:psi.project-nrepl/instances`
- Added session-scoped attr:
  - `:psi.agent-session/project-nrepl`
  - resolves the managed instance bound to the invoking session worktree
- Current projected fields include:
  - identity/worktree/acquisition mode
  - transport kind
  - lifecycle state + readiness
  - endpoint + command-vector
  - single-session metadata + active-session-id
  - eval/interrupt capability flags
  - last-error
  - timestamps
- Added resolver tests in `project_nrepl_resolvers_test.clj` for both root and session-scoped query paths.

2026-04-19 — Step 6: attach-mode acquisition

- Added `components/agent-session/src/psi/agent_session/project_nrepl_attach.clj`.
- Implemented attach-mode endpoint resolution with the required precedence:
  1. explicit port (+ optional host)
  2. worktree-local `.nrepl-port`
- Host defaults to `127.0.0.1` when omitted.
- Attach acquisition now:
  - resolves endpoint deterministically for the target worktree
  - creates/ensures a managed `:attached` instance bound to that worktree
  - establishes the same single managed client session used by started mode
  - projects attach failures into `:failed` state with endpoint + `:last-error`
- Added `detach-instance-in!` to disconnect and remove attached instances cleanly.
- Added focused tests in `project_nrepl_attach_test.clj` covering:
  - explicit endpoint precedence
  - `.nrepl-port` fallback
  - successful attach projection
  - failed attach projection
