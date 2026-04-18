Goal: rename the `app-query-tool` tool to `psi-tool` as the canonical user-facing and runtime-facing name.

Context:
- `app-query-tool` is the live session graph/EQL query tool.
- The current name is implementation-shaped and does not match the project identity.
- The rename should converge the canonical tool name across definitions, prompts, docs, tests, and any extension/runtime projections.
- Compatibility behavior, if any, should be treated explicitly rather than left as accidental aliasing.

Acceptance:
- `psi-tool` is the canonical tool name everywhere tool definitions are surfaced and consumed.
- Runtime/tool invocation paths accept and route the canonical `psi-tool` name correctly.
- Docs, prompts, and tests refer to `psi-tool` consistently.
- Any temporary compatibility alias from `app-query-tool` is either removed or intentionally bounded/documented.
