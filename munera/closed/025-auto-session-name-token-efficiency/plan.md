Approach:
- Complete the architectural intent of child-session prompt composition so `:prompt-component-selection` becomes a real execution-time control surface rather than partial metadata.
- Keep existing caller behavior unchanged when no prompt-component controls are supplied.
- Unify prompt-component interpretation across prompt refresh, prepared-request assembly, and runtime-visible session prompt state.
- Have auto-session-name continue consuming the general child-session capability, but now backed by fully enforced component selection for standard prompt layers and helper capabilities.
- Retain the 4000-character tail truncation and minimal helper prompt contract already landed.

Likely steps:
1. add a canonical normalization/interpreter for `:prompt-component-selection`
2. unify extension prompt-contribution filtering semantics across prompt refresh and prepared-request assembly
3. wire child-session prompt rebuilding so component selection actually controls standard prompt layers:
   - preamble
   - context files / AGENTS.md
   - skills
   - runtime metadata
4. make `:tool-names` and `:skill-names` drive real filtering rather than remaining declarative-only fields
5. align runtime-available child tool defs with prompt-visible tool selection when explicit tool-name selection is supplied
6. add coherence tests proving child session prompt state and prepared request agree under prompt-component selection
7. fix stale/misleading docs and notes once the general capability is truly implemented

Risks:
- accidentally changing default prompt behavior for existing child-session callers
- introducing divergence between stored session prompt, prepared request prompt, and runtime-visible provider prompt
- implementing overlapping controls (`:agents-md?` and `:components`) inconsistently
- overfitting the control surface to auto-session-name instead of making it genuinely reusable
- leaking partial allowlist semantics where one code path filters and another still suppresses or inherits implicitly
