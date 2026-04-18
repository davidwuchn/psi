Approach:
- Treat this as a focused naming-convergence task.
- First inventory every surface where `app-query-tool` appears: tool definitions, prompts, runtime dispatch/invocation, docs, tests, and extension capability projections.
- Then change the canonical tool name to `psi-tool` and decide whether a temporary alias is needed during migration.
- Finish by converging tests and documentation so the new name is the only intended public contract.

Likely steps:
1. inventory all references to `app-query-tool`
2. identify the canonical tool-definition/source-of-truth location
3. rename the canonical tool to `psi-tool`
4. update invocation/routing surfaces and any capability projections
5. update tests and docs
6. remove or explicitly bound any compatibility alias

Risks:
- renaming only the prompt/docs surface while leaving runtime/tool registration inconsistent
- leaving accidental compatibility branches that prolong dual naming
- missing extension or adapter surfaces that display or invoke the tool by name
