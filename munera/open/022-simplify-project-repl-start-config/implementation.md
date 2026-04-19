Initialized from user request on 2026-04-19.

2026-04-19 — Canonical shape decision
- Canonical started-mode config is `:agent-session :project-nrepl :start-command`.
- `:start-command` remains a command vector: a non-empty vector of strings whose first element is a non-blank command path/name and remaining entries are ordered args.
- Compatibility stance: remove the old nested `:started :command-vector` shape deliberately rather than support both shapes.
- Rationale: this task is a surface simplification, project direction prefers one obvious path, and dual-shape support would preserve needless user-facing complexity.

2026-04-19 — Runtime/config switch
- Replaced started-mode config resolution with `resolved-start-command` reading only `:project-nrepl :start-command`.
- Updated `/project-repl start` missing-config guidance to point only at `:agent-session :project-nrepl :start-command` with the simplified example.
- Updated focused config/command tests; proof green via `clojure -M:test --focus psi.agent-session.project-nrepl-config-test --focus psi.agent-session.project-nrepl-commands-test`.
