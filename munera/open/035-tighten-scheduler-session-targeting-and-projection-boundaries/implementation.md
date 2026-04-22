# Implementation notes

## Intent

Post-close shaping follow-up for tasks `033` and `034`.

This task exists to tighten non-blocking concerns found in review:
- ambient scheduler session fallback in `psi_tool_scheduler.clj`
- projection/helper ownership spread across nearby scheduler modules
- resolver ownership clarity

## Notes

- Keep this task narrow.
- Preserve scheduler behavior.
- Prefer existing canonical `psi-tool` and resolver patterns over new scheduler-specific rules.
