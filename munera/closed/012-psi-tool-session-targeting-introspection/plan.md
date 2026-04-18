Plan

1. Define one canonical explicit targeting contract for `psi-tool` using ordinary EQL root entity seeding, not custom query semantics.
2. Prove the underlying `session/query-in` path can read non-active sessions when seeded with `{:psi.agent-session/session-id ...}`.
3. Make `psi-tool` accept an optional EDN `entity` map and pass it directly to the query function.
4. Harden session-scoped resolver reads so missing or unknown session ids fail explicitly.
5. Add focused tests and prompt/docs for the supported form.
