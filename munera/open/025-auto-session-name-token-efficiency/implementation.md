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

Remaining known limitation after this task:
- `:prompt-component-selection` is currently exercised concretely for reduced preamble/runtime-metadata assembly plus suppression of extension prompt contributions; the auto-session-name path also disables tool definitions/capabilities by passing empty tool defs. If future callers need fully general skill/tool allowlist enforcement directly from `:prompt-component-selection`, that can be layered on without changing the core naming behavior implemented here.
