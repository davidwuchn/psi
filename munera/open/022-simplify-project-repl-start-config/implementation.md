Initialized from user request on 2026-04-19.

2026-04-19 — Canonical shape decision
- Canonical started-mode config is `:agent-session :project-nrepl :start-command`.
- `:start-command` remains a command vector: a non-empty vector of strings whose first element is a non-blank command path/name and remaining entries are ordered args.
- Compatibility stance: remove the old nested `:started :command-vector` shape deliberately rather than support both shapes.
- Rationale: this task is a surface simplification, project direction prefers one obvious path, and dual-shape support would preserve needless user-facing complexity.
