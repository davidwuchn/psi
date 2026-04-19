Goal: reduce token usage of the auto-session-name helper inference flow without changing checkpoint timing, safety semantics, title validation, or title application behavior.

Scope:
- improve general child-session creation so it allows explicit control over which standard system-prompt components are included
- allow child-session creation to optionally accept one caller-supplied additional system prompt
- make auto-session-name use that capability to run naming inference with much lower prompt overhead and bounded recent conversation content

This task does not change:
- checkpoint scheduling
- recursion protection
- stale checkpoint suppression
- manual-name overwrite protection
- journal-backed source-session read behavior
- title validation/application semantics
- broader naming behavior beyond prompt/input efficiency

Design direction:
- implement the naming optimization by extending general child-session creation rather than introducing an auto-session-name-specific bypass
- expose prompt-composition controls so callers can choose which standard prompt components are included in a child run
- allow one optional caller-supplied additional system prompt string
- have auto-session-name use this general facility to create a minimal helper session
- when no prompt-composition controls are supplied, child-session creation must preserve current prompt assembly behavior

Child-session prompt composition control:
- general child-session creation must allow explicit control over the standard system-prompt components used for the child run
- prompt composition must be explicit enough that every child-session system-prompt source is either:
  - represented in the composition input
  - or proven absent
- at minimum, callers must be able to control inclusion of:
  - `AGENTS.md`
  - extension prompt contributions, including which extensions if any are included
  - tool definitions exposed to the model, including which tools if any are included
  - skill prompt/prelude content, including which skills if any are included
- if other standard prompt layers are assembled as part of child-session bootstrap, those should also be controllable through the same prompt-composition mechanism rather than remaining implicitly inherited
- in addition to component selection, child-session creation must allow one optional caller-supplied additional system prompt to be included
- this design intentionally separates:
  - prompt composition control for standard system layers
  - task-specific instruction via a single additional system prompt
- ordering must be deterministic:
  - selected standard prompt components are assembled in canonical order first
  - the optional caller-supplied additional system prompt is appended last

Auto-session-name helper prompt environment:
- auto-session-name should create its helper child session with a minimal prompt environment
- for the naming helper run, child-session creation should be configured to include:
  - no `AGENTS.md`
  - no extension prompt contributions
  - no tool definitions
  - no skill prompt/prelude content
  - one additional system prompt containing the minimal title-inference instruction
- for the naming helper run, tools and skills should also not be available as helper capabilities, not merely omitted from prompt text
- the resulting helper run should contain only the prompt surfaces explicitly requested for naming
- the naming helper run must not include extra synthetic seed turns beyond the single user message carrying the bounded conversation text

Naming helper prompt contract:
- the naming helper run should use:
  - the selected child-session prompt composition with all standard components disabled
  - one minimal additional system prompt
  - one user message
- the additional system prompt should narrowly define the task, in terms equivalent to:
  - infer a concise session title from the supplied conversation excerpt
  - return title text only
  - no explanation
  - no quotes
  - no markdown
- the user message should contain:
  - bounded recent conversation text
  - any terse output constraint wording not already covered by the additional system prompt
- no other prompt surfaces should be present

Conversation window:
- the helper should consider only the canonical sanitized visible user and assistant text already used by the naming flow
- construction rule:
  1. assemble the canonical sanitized conversation text
  2. if its length is at most 4000 characters, use it unchanged
  3. otherwise keep only the trailing 4000 characters
- this is a string-length budget, not token counting
- truncation happens after sanitization and final text assembly

Truncation semantics:
- truncation applies to the final assembled sanitized conversation text
- this task explicitly chooses tail truncation of the final text over:
  - tokenizer-aware budgeting
  - summarization
  - complex message-boundary-aware packing
- message-boundary preservation is not required
- rationale:
  - deterministic
  - simple
  - cheap
  - recent conversation is most likely to reflect current session focus

Empty-input handling:
- if the sanitized assembled conversation text is empty after sanitization, the helper naming flow should preserve the current skip/no-op behavior rather than introducing a new fallback path in this task

Invariants to preserve:
- helper-session recursion avoidance
- stale checkpoint suppression
- manual-name overwrite protection based on divergence from the last auto-applied name
- journal-backed source-session read path as the naming source
- title validation and application semantics after helper output is produced

Acceptance:
- child-session creation supports explicit control over standard system-prompt components, including:
  - `AGENTS.md`
  - extension prompt contributions
  - tool definitions
  - skill prompt/prelude content
- child-session creation supports one optional caller-supplied additional system prompt
- when no prompt-composition controls are supplied, existing child-session behavior remains unchanged
- auto-session-name uses that capability to create a minimal naming helper run
- the naming helper run includes:
  - no `AGENTS.md`
  - no extension prompt contributions
  - no tool definitions
  - no skill prompt/prelude content
  - no tool availability
  - no skill availability
  - one minimal additional system prompt
  - one user message
  - no extra synthetic seed turns
- helper conversation input is bounded to at most the last 4000 characters of canonical sanitized conversation text
- tests prove:
  - child-session creation honors prompt-component inclusion controls
  - child-session creation honors the additional system prompt input
  - existing child-session callers retain current behavior by default
  - auto-session-name helper creation uses the reduced prompt environment
  - long conversation text is tail-truncated to the 4000-character limit
  - short conversation text is unchanged
  - empty sanitized conversation input preserves the current skip/no-op behavior
- implementation notes capture:
  - why this was implemented as a general child-session capability
  - why the naming helper disables standard prompt components and helper capabilities
  - why character-based tail truncation was chosen

Non-goals:
- add tokenizer-aware budgeting
- redesign the full child-session architecture
- broaden auto-session-name behavior
- introduce user-facing configuration unless separately requested
