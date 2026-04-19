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
- Current started-mode readiness means:
  - process launched successfully
  - `.nrepl-port` appeared and parsed successfully
- It does **not yet** establish the nREPL socket/client session; that remains the next sub-slice needed to finish the design’s full readiness invariant.
- Added focused tests in `project_nrepl_started_test.clj` covering:
  - endpoint discovery after delayed `.nrepl-port` creation
  - early process exit failure
  - successful started acquisition projection
  - startup failure projection to failed state
