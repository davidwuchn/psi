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

Open follow-up after this step:
- verify whether child prompt-component selection should actively filter skill/tool/session prompt layers in request preparation beyond the already enforced empty tool defs and explicit extension suppression
- run focused tests and fix any edge mismatches discovered there
