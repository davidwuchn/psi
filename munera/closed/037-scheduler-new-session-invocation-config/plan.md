Approach:
- Treat this as an additive scheduler evolution, not a scheduler rewrite.
- Keep the existing scheduler op model (`create|list|cancel`) and extend only `create` with explicit kind-aware behavior.
- Implement the new capability around a canonical non-switching top-level session creation path, reusing shared fresh-session initialization logic rather than reimplementing top-level session bootstrapping inside scheduler code.
- Converge scheduler state, psi-tool, EQL, and background-job/public projections on the same explicit public concepts: `:kind`, origin session, created session, delivery phase, failure summary, and compact config summaries.

Implementation slices:
1. introduce the schedule kind/state model evolution
   - add explicit `:kind` support to scheduler records
   - add `:failed` as a canonical terminal scheduler status
   - extend schedule state to carry `:origin-session-id`, optional `:created-session-id`, optional `:delivery-phase`, optional `:error-summary`, and session-config payload/summary data as needed
2. validate and parse the new psi-tool scheduler create surface
   - require explicit `:kind`
   - support `:kind :message` and `:kind :session`
   - validate `:session-config` against the v1 subset
   - reject unsupported keys explicitly
3. add canonical non-switching top-level session creation support
   - extract/reuse the shared fresh-session initialization/runtime logic from the current switching path
   - introduce a non-switching contract for top-level creation suitable for scheduler use
   - ensure the origin active session remains unchanged
4. route `:kind :session` fire-time delivery through canonical session creation + canonical prompt submission
   - seed `:preloaded-messages` before prompt submission
   - persist scheduler provenance on the created session
   - record partial failure correctly when creation succeeds but prompt submission fails
5. converge public projections and introspection
   - update psi-tool summaries to include `:kind`
   - expose compact `:session-config-summary` for `:kind :session`
   - evolve EQL/public attrs toward `:psi.scheduler/origin-session-id`, `:psi.scheduler/created-session-id`, `:psi.scheduler/delivery-phase`, `:psi.scheduler/error-summary`, and `:psi.scheduler/session-config-summary`
   - keep background-job projection consistent with the richer scheduler model
6. prove behavior with focused tests
   - validation and kind-aware psi-tool tests
   - scheduler handler/runtime tests for `:session` delivery and `:failed`
   - active-session non-switching proof
   - provenance persistence proof
   - projection naming/shape proof
   - non-regression of existing `:message` scheduling behavior

Likely implementation shape options:
- preferred: add a canonical non-switching top-level creation path and share underlying initialization logic with the existing switching new-session flow
- acceptable only if it converges to the same shape: extract shared helpers first, then wrap them with switching vs non-switching lifecycle entrypoints
- not desired: scheduler-local top-level session creation logic, or using the switching path and trying to undo/restore focus afterward

Risks:
- copying fresh-session initialization logic instead of reusing it
- letting scheduler-specific internal record shape drift away from public projection naming
- leaking oversized config/prompt payloads into default scheduler summaries
- accidentally preserving ambiguous `session-id` naming where `origin-session-id` is now required for clarity
- under-specifying partial failure and ending up with `:delivered` + error instead of canonical `:failed`

Decisions already fixed:
- fresh top-level session, not child session
- explicit `:kind`
- nested `:session-config`
- no active-session/focus switching
- validated v1 `:session-config` subset
- public summaries use compact config summary rather than full config
- canonical terminal failure status is `:failed`
- public naming should converge on origin/created session ids rather than ambiguous `session-id`