Initialized on 2026-04-21 from user request to let the scheduler invoke fresh top-level sessions with explicit session-config.

2026-04-21 design refinement notes:
- settled on explicit scheduler kinds `:message` and `:session`
- chose fresh top-level session semantics, not child-session creation
- `:session-config` is now a validated v1 subset, not arbitrary config
- created session must never auto-switch active/focused session
- scheduler needs a canonical non-switching top-level creation path rather than reuse of a switching helper
- public projection naming should converge on origin/created session ids and compact config summaries
- canonical terminal scheduler failure status is `:failed`

2026-04-21 implementation slice 1:
- evolved scheduler record/state model to support explicit `:kind`, `:failed`, origin/created session ids, delivery phase, error summary, and optional session-config/session-config-summary payloads
- updated psi-tool scheduler parsing/validation to require explicit `kind` on create and to validate/reject session-config usage by kind
- converged scheduler psi-tool, EQL, and background-job projections on richer kind-aware public fields and compact session-config summaries
- updated focused scheduler tests to cover explicit kind handling, origin-session projection, background-job kind projection, and `:session` fire behavior that bypasses origin busy-state queueing
- intentionally did not yet implement fresh-session creation itself; current `:kind :session` delivery now shapes the scheduler/public model and fire semantics needed for the next slice

2026-04-21 implementation slice 2:
- extracted shared top-level session initialization into `session-lifecycle/initialize-top-level-session!`
- added `create-top-level-session-in!` as a canonical non-switching top-level session creation path
- added dispatch handler `:session/create-top-level` so scheduler-created sessions do not reuse user-facing switching semantics
- extended session state schema and initialization to persist scheduler provenance on created sessions:
  - `:scheduled-origin-session-id`
  - `:scheduled-from-schedule-id`
  - `:scheduled-from-label`
- implemented `:kind :session` scheduler delivery to:
  - create a fresh top-level session in the same worktree/context
  - apply the v1 supported session shaping subset needed so far
  - seed preloaded messages before prompt submission
  - submit the scheduled message through `:session/submit-synthetic-user-prompt`
  - record `:created-session-id` and `:delivery-phase`
- implemented failure recording through canonical scheduler `:failed` status with delivery-phase and error-summary, including partial-failure handling when prompt submission fails after session creation
- kept the origin active session unchanged; focused tests prove non-switching behavior
- focused scheduler tests now pass for both the modeling/projection slice and the non-switching session creation/delivery slice

2026-04-21 verification expansion:
- expanded proof beyond the initial focused scheduler slice
- verified scheduler-focused suites:
  - scheduler-end-to-end
  - scheduler-context-shutdown
  - scheduler-cancel-job
  - scheduler-lifecycle
  - scheduler-timer-seam
  - scheduler-effects
  - scheduler-resolvers
  - scheduler-background-jobs
  - scheduler-tools
  - psi-tool-scheduler
  - scheduler-handlers
  - result: 18 tests, 262 assertions, 0 failures
- verified adjacent lifecycle/prompt suites still pass:
  - session-lifecycle-test
  - prompt-lifecycle-test
  - result: 22 tests, 167 assertions, 0 failures
- verified auto-session-name extension suites still pass after the new scheduler/session behavior:
  - extensions.auto-session-name-runtime-test
  - extensions.auto-session-name-test
  - result: 14 tests, 35 assertions, 0 failures