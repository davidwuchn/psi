Approach:
- Treat this as a semantic clarification slice, not a broad footer redesign.
- The textual/state contract is now settled in design; implementation should preserve that contract rather than reopen naming decisions.
- Implement shared/backend semantics before adapter rendering tweaks.
- Ensure the footer accounts for all sessions and all canonical phases in scope.
- Use color/faces as reinforcement, with waiting visually stronger than running.
- Avoid frontend reparsing of rendered text by carrying structured semantics through shared/backend projections.

Implementation strategy:
1. expose canonical per-session runtime state for all sessions in shared projections
   - extend shared context/session projection data so every session row can carry canonical phase or a canonical derived user-facing state label
   - ensure tree/footer semantics draw from the same canonical source so they cannot drift
2. update shared footer semantics to bucket all sessions by canonical state
   - grouped bucket order: waiting, running, retrying, compacting
   - preserve parent-first/stable ordering within each bucket
   - omit only empty buckets, never non-empty session states
3. update shared context/session-tree semantics
   - rename `← active` to `← current`
   - ensure each session row has an explicit runtime badge for the canonical phase vocabulary in scope
4. update Emacs rendering
   - consume structured backend/shared semantics
   - apply dedicated faces to current/runtime-state fragments
   - ensure waiting is visually stronger than current, and current stronger than or comparable to running orientation-wise
5. add tests across layers
   - shared/backend tests for canonical state classification and all-session coverage
   - RPC tests for any payload shape/semantic additions
   - Emacs tests for wording, ordering, and fragment rendering/face application
6. verify readability/accessibility
   - representative multi-session examples
   - text-only legibility without color
   - theme-friendly face inheritance

Concrete implementation notes:
- user-facing terms are fixed for this slice:
  - `current`
  - `waiting`
  - `running`
  - `retrying`
  - `compacting`
- internal/runtime term `streaming` remains internal; UI should say `running`
- single-session omission of the grouped footer activity line is intentional and should remain unless implementation evidence shows it is insufficient

Risks:
- introducing too much visual noise in compact footer/session-tree surfaces
- adding structured payloads in a way that duplicates or fragments existing shared projection ownership
- weakening alignment between tree/footer semantics if one surface is updated without the other
- relying too much on color and weakening accessibility/text-only clarity
