# Implementation notes

## 2026-04-21

- Added canonical dispatch-owned synthetic prompt submission entry point in `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`:
  - `:session/submit-synthetic-user-prompt`
- Centralized the low-level prompt lifecycle event bundle behind that entry point.
- Migrated scheduler delivery and drain paths in `dispatch_handlers/scheduler.clj` to emit the canonical synthetic submission event instead of assembling the prompt lifecycle sequence directly.
- Also migrated prompt-finish follow-up chaining to use the same canonical synthetic submission event, so synthetic prompt submission semantics are now shared across scheduler and follow-up delivery.
- Added focused prompt lifecycle test coverage for the new canonical entry point.
- Updated scheduler handler tests to assert the new shaping boundary.
- Re-ran focused tests covering prompt lifecycle, scheduler handlers, and scheduler lifecycle; all passed.

## Notes

- This is a shaping change, not a behavior change.
- Prompt lifecycle ordering remains the same behind the new entry point:
  - `:session/prompt-submit`
  - `:session/prompt`
  - `:session/prompt-prepare-request`
