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
