Approach:
- Use helper model selection as the next concrete slice.
- Defer stronger name-source metadata and internal-session contracts until they are justified by the helper-model work.
- Keep the extension’s journal-backed read path as the source of truth for rename inference.

Likely steps:
1. integrate helper model selection for rename inference
2. evaluate whether explicit `:manual` vs `:auto` metadata is needed next
3. evaluate whether helper sessions need stronger non-user-facing runtime contracts
4. assess persistence/reload needs for extension-owned rename state

Risks:
- broadening session metadata/contracts before the selector slice proves the need
- regressing the current overwrite guards
