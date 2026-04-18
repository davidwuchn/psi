Implementation notes

- Rejected approach: custom ident-root handling / tool-level target emulation. It introduced new semantics beyond ordinary EQL seeding and made debugging harder.
- Adopted canonical contract: `psi-tool(query, entity?)`, where `entity` is an EDN root entity map.
- Canonical session-targeting form is:
  - `query: "[:psi.agent-session/session-name :psi.agent-session/model-id]"`
  - `entity: "{:psi.agent-session/session-id \"sid\"}"`
- `make-psi-tool` now supports query functions of arity 1 or 2:
  - `(fn [q] ...)`
  - `(fn [q entity] ...)`
- `app-runtime` and tool-plan runtime now pass seeded entities through unchanged to normal query execution.
- `support/session-data` now throws explicitly when `:psi.agent-session/session-id` is missing or unknown, preventing silent scope collapse.
- Focused tests prove:
  - `session/query-in` can read non-active child-session attrs when seeded with explicit session id
  - `psi-tool` can do the same via `entity`
  - malformed `entity` input is rejected
  - missing targeting yields a tool error rather than silently reading some other session
- During debugging, one failure turned out to be test setup, not query seeding: creating the child with `{:session-name ...}` was more stable than mutating the name later for the focused non-active-session query proof.
