2026-04-20
- Task created from user request to support `.psi/project.local.edn` as a higher-precedence writable project config layer.
- Requested baseline semantics captured:
  - `.psi/project.local.edn` takes priority over `.psi/project.edn`
  - `/model` writes to `.psi/project.local.edn`
  - `/thinking` writes to `.psi/project.local.edn`
- Initial likely implementation surfaces identified:
  - `components/agent-session/src/psi/agent_session/project_preferences.clj`
  - `components/agent-session/src/psi/agent_session/config_resolution.clj`
  - command/help text and any project-config path reporting that currently names `.psi/project.edn`

2026-04-20
- Scope refined collaboratively to remove ambiguity.
- Confirmed design decisions:
  - project config layering is general, not special-cased to `/model` and `/thinking`
  - effective project config deep-merges `.psi/project.edn` then `.psi/project.local.edn`
  - `.psi/project.local.edn` takes precedence for overlapping keys and nested paths
  - config may live in either file
  - persisted project preference writes target only `.psi/project.local.edn`
  - malformed shared/local config files should use best-effort fallback and emit warnings
  - `.psi/project.local.edn` should be gitignored
- Important consequence:
  - this task must check all project-config consumers, not just the command write path

2026-04-20
- Follow-on forgotten-consumer/edge-case pass completed.
- Additional concrete scope now captured:
  - project nREPL config is a definite project-config consumer and must adopt the layered model
  - docs/help paths currently mentioning only `.psi/project.edn` need review, especially configuration and project nREPL docs/messages
  - deep-merge semantics must be explicit: maps recurse; non-map values are replaced by the later/local value
  - malformed shared + malformed local falls back to defaults with warnings
  - if `.psi/project.local.edn` is malformed during a write, warn and treat its existing content as empty input for the write path
  - warning/fallback behavior should not by itself rewrite or normalize malformed files
