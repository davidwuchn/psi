Slice 1 — Scheduler state and public model:
- [x] Evolve scheduler record/state model for explicit `:kind`, `:failed`, origin/created session ids, delivery phase, and error summary
- [x] Validate and parse kind-aware `psi-tool` scheduler create requests
- [x] Converge psi-tool, EQL, and background-job/public projections on the richer scheduler model
- [x] Add focused tests for validation, projection naming/shape, and `:session` busy-state bypass semantics

Slice 2 — Non-switching fresh-session delivery:
- [x] Add canonical non-switching top-level session creation support
- [x] Route `:kind :session` fire-time delivery through canonical creation + prompt submission
- [x] Persist scheduler provenance on created sessions
- [x] Add focused tests for created-session config carriage, prompt submission routing, provenance persistence, partial failure, and unchanged active-session behavior

Slice 3 — Verification and docs:
- [x] Expand verification across scheduler, lifecycle, and adjacent affected suites
- [x] Add/update user-facing docs for scheduler `kind: message|session`, supported `session-config`, status model, and introspection attrs
- [x] Record implementation notes and close remaining shape gaps discovered during the work
