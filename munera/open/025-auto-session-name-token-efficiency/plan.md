Approach:
- Implement this as a general child-session prompt-composition capability first, preserving existing behavior for callers that do not supply prompt controls.
- Extend child-session creation so callers can explicitly choose which standard prompt components are included and can optionally append one additional system prompt.
- Have auto-session-name consume that capability to create a minimal naming helper with no standard prompt components, no helper tools/skills, and one naming-specific system prompt.
- Tighten helper input shaping by truncating the final sanitized assembled conversation text to the trailing 4000 characters.
- Prove both the new child-session seam and the auto-session-name behavior in tests, then record implementation trade-offs.

Likely steps:
1. inspect the current child-session creation and helper prompt assembly path
2. add prompt-component controls with default-preserving behavior
3. add optional additional system prompt support with deterministic ordering
4. wire auto-session-name helper creation to disable standard prompt components and helper capabilities
5. truncate sanitized assembled conversation text to the trailing 4000 characters
6. update or add tests for child-session prompt controls, reduced helper environment, truncation, and empty-input behavior
7. record implementation notes/trade-offs

Risks:
- accidentally changing prompt behavior for existing child-session callers
- leaving an unmodelled prompt source implicitly inherited into the helper run
- reducing helper capability availability in a way that leaks into unrelated child-session use
- over-truncating context so inferred names regress in quality
- implementing the control surface too narrowly for the prompt components that already exist
