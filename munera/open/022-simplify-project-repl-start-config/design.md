Goal: simplify project-repl started-mode configuration by replacing nested `:started :command-vector` with a simpler `:start-command` shape.

Context:
- The current project nREPL started-mode config uses `:agent-session :project-nrepl :started :command-vector`.
- This shape is more structured than needed for the current capability and creates extra user-facing configuration complexity.
- A simpler command surface should make `/project-repl start` easier to understand and configure.

Required behavior:
- project-repl started-mode configuration uses a simpler `:start-command` field
- user-facing guidance, docs, and command errors point to the simplified config shape
- runtime/config validation remains explicit and friendly
- migration behavior is intentional: either compatibility is preserved temporarily with a clear canonical preference, or the old shape is removed deliberately

Acceptance:
- canonical project-repl start configuration is `:agent-session :project-nrepl :start-command`
- `/project-repl start` guidance and errors mention the canonical simplified config
- docs and tests align with the canonical config shape
- any compatibility decision is explicit and covered by proof

Constraints:
- keep targeting and runtime behavior unchanged apart from config shape simplification
- prefer one obvious config path over multiple overlapping shapes
- preserve clear validation errors for missing or invalid start commands
