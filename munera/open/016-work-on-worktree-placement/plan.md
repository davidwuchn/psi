Approach:
- Update the formal surfaces first so spec/docs/test intent matches the accepted placement design before code changes.
- Then implement the placement helper in `extensions.work-on` as a small pure function using canonicalized main/current paths.
- Finally update focused tests to cover both supported layouts and the explicitly defined nested-layout-from-main behavior.

Execution slices:
1. Update spec surfaces that hard-code sibling-of-main placement
   - `spec/work-on-extension.allium`
   - `spec/git-worktree-mutations.allium`
2. Update user/developer-facing docs and command description text
   - `doc/tui.md`
   - `extensions/src/extensions/work_on.clj` command description/help text
   - `CHANGELOG.md` only if the user-visible wording change is significant enough to merit an entry
3. Add/reshape tests around a dedicated pure placement helper
   - direct unit tests for the path-derivation rule
   - update `/work-on` extension tests to cover sibling-main and nested-linked layouts
4. Implement the helper and route `/work-on` through it
5. Run focused verification for updated tests and any touched spec/doc coherence checks

Implementation constraints:
- keep placement derivation narrow and deterministic
- use only `main-checkout-path`, `current-worktree-path`, and parent-directory relationships
- canonicalize paths before comparison
- do not add broader repo-container inference heuristics

Risks:
- updating code before specs/docs/tests converge on the accepted rule
- accidentally broadening behavior beyond the agreed narrow rule
- preserving old sibling-only assumptions in tests or help text after code changes
