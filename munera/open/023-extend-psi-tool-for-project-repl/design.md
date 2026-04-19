Goal: extend `psi-tool` so it can control and use the managed project REPL.

Context:
- `psi-tool` is the canonical runtime control/query surface for live psi operations.
- Managed project REPL support already exists on the shared command surface as `/project-repl`, including status/start/attach/stop/eval/interrupt behavior.
- Exposing project REPL operations through `psi-tool` would make them available to tool-driven and programmatic workflows without routing through chat command parsing.

Required behavior:
- `psi-tool` can access managed project REPL capabilities through explicit actions or modes
- the tool can control lifecycle operations for the managed project REPL
- the tool can evaluate code in the managed project REPL
- targeting is explicit and consistent with existing session/worktree semantics
- error and result shapes are intentional and structured for tool consumption

Acceptance:
- `psi-tool` exposes canonical project REPL operations for status/start/attach/stop/eval/interrupt or an explicitly chosen subset
- project REPL targeting is explicit by session and/or worktree where required
- user/tool-facing results are structured and stable enough for programmatic use
- docs and tests cover the new surface
- overlap between `/project-repl` commands and `psi-tool` is understood and kept coherent

Constraints:
- preserve explicit targeting semantics; do not reintroduce hidden cwd/focus fallback
- prefer structured tool results over command-formatted text
- keep the extension additive and localized rather than broadening `psi-tool` into an unstructured command proxy
