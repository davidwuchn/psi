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

## Post-close review note

- Review outcome: approved.
- Design/implementation alignment is precise: the task asked for one canonical dispatch-owned synthetic prompt submission entry point, and the implementation added `:session/submit-synthetic-user-prompt` and migrated scheduler delivery/drain to use it.
- Architectural fit is strong: feature code no longer owns prompt lifecycle wiring, and synthetic prompt submission semantics are centralized in the prompt lifecycle boundary rather than copied into feature handlers.
- This follow-up improves convergence rather than introducing a new parallel pattern.
- Non-blocking follow-up concerns are adjacent rather than local to this task: keep synthetic prompt submission ownership centralized, and let any remaining scheduler surface tightening happen in a dedicated shaping task rather than re-expanding feature-local lifecycle knowledge.
