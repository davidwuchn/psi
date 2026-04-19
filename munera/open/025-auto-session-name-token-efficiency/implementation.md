Initialized on 2026-04-19.

2026-04-19 — Step 1: revise task design and plan
- Sharpened `design.md` to make the task architectural rather than extension-local.
- Explicitly required child-session prompt-component control plus one optional additional system prompt.
- Clarified that auto-session-name should disable AGENTS/context prompt contributions, tools, and skills for its helper run.
- Clarified default-preserving behavior for existing child-session callers.
- Updated `plan.md` and `steps.md` to sequence general child-session capability work before the auto-session-name changes.

2026-04-19 — Step 2: initial implementation of reduced helper prompt path
- Extended system prompt assembly with `:include-preamble?` and `:include-runtime-metadata?` so a child helper can use a minimal appended system prompt without inheriting the normal psi-authored preamble or runtime metadata tail.
- Added child-session `:prompt-component-selection` storage and propagation through mutation/dispatch/state initialization.
- Made prompt request assembly suppress extension prompt contributions when the child prompt-component selection explicitly provides an extension allowlist (including the empty list used by auto-session-name).
- Kept default behavior unchanged when no prompt-component selection is supplied.
- Updated auto-session-name to:
  - build a minimal helper system prompt plus a single user prompt
  - tail-truncate the assembled sanitized conversation text to 4000 characters
  - create the helper child with empty tool defs, no cache breakpoints, and an empty prompt-component selection
- Added/updated focused tests for:
  - system-prompt omission of preamble/runtime metadata
  - child-session storage of prompt-component selection
  - auto-session-name helper child creation params
  - helper prompt truncation and runtime prompt shape

2026-04-19 — Step 3: verification and documentation follow-through
- Ran focused unit coverage for the shared prompt-building and child-session mutation surfaces via `bb clojure:test:unit --focus psi.agent-session.system-prompt-test --focus psi.agent-session.child-session-mutation-test`.
- Ran focused auto-session-name tests via direct Clojure test invocation because the aggregate extension suite currently contains unrelated LSP failures.
- Fixed two edge mismatches discovered during focused verification:
  - helper transcript truncation should preserve newline-separated assembled lines rather than re-squishing the whole transcript
  - extension test assertions for `create-child-session` needed to ignore runtime-injected routing keys like `:session-id` / `:ext-path`
- Updated `doc/extension-api.md` to document the new child-session prompt-shaping controls and the reduced helper-run pattern.

2026-04-19 — Step 4: complete the general child prompt-component control surface
- Added canonical prompt-component normalization in `system_prompt.clj` so child selection now derives explicit effective semantics rather than remaining declarative-only metadata.
- Added shared filtering helpers for:
  - extension prompt contributions
  - tool definitions
  - skills
- Wired prompt contribution filtering through a single path used by both prompt refresh and prepared-request assembly so allowlist semantics no longer diverge.
- Extended base prompt assembly to support `:include-context-files?` so AGENTS/context-file suppression is now real rather than merely documented.
- Updated child-session state initialization so prompt-component selection now drives:
  - normalized child selection storage
  - filtered child tool defs
  - filtered child skills
  - child-specific system-prompt build opts
  - rebuilt reduced child base-system-prompt when prompt controls are supplied
- Updated the auto-session-name namespace docstring to match current behavior.
- Added focused proof for:
  - context-file omission in reduced prompt assembly
  - extension contribution allowlist behavior
  - child prompt rebuild/tool filtering coherence
- Re-ran focused unit coverage for `system-prompt-test` and `child-session-mutation-test`; both are green.

Remaining known limitation after this step:
- `:prompt-component-selection` now concretely controls standard prompt layers, extension contribution filtering, and child tool/skill filtering for the child-session flow added here. If future callers need broader lifecycle-wide prompt-component control beyond child-session creation/rebuild paths, that can build on the now-canonical normalization/filtering helpers rather than inventing a second contract.
