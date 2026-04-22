2026-04-22
- Task created after confirming that `complexity-reduction-pr` still could not use `/work-on` because `work-on` exists only as an extension command and not as a model tool.
- Constraint from the user: the new tool and the existing commands should share the same implementation.
- Open design concern: it is not yet clear whether the cleanest implementation can route through current resolvers/effects/dispatch boundaries, or whether the first slice should use a smaller shared extension-local execution function with explicit documentation of the compromise.