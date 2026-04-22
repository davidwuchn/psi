Initialized on 2026-04-21 from user request to let the scheduler invoke fresh top-level sessions with explicit session-config.

2026-04-21 design refinement notes:
- settled on explicit scheduler kinds `:message` and `:session`
- chose fresh top-level session semantics, not child-session creation
- `:session-config` is now a validated v1 subset, not arbitrary config
- created session must never auto-switch active/focused session
- scheduler needs a canonical non-switching top-level creation path rather than reuse of a switching helper
- public projection naming should converge on origin/created session ids and compact config summaries
- canonical terminal scheduler failure status is `:failed`