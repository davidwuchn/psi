Concrete implementation target

Chosen canonical names
- mutation/API for UI-only messages: `psi.extension/notify` / `:notify`
- mutation/API for conversation-visible synthetic messages: `psi.extension/append-message` / `:append-message`
- keep `psi.extension/send-prompt` unchanged as the execution path for real prompt turns

Expected touched files

Runtime + dispatch
- `components/agent-session/src/psi/agent_session/extension_runtime.clj`
- `components/agent-session/src/psi/agent_session/dispatch_handlers/session_mutations.clj`

Mutations + session-scoped extension op list
- `components/agent-session/src/psi/agent_session/mutations/extensions.clj`
- `components/agent-session/src/psi/agent_session/extensions/runtime_eql.clj`

Extension API
- `components/agent-session/src/psi/agent_session/extensions/api.clj`

Conversation/persistence proofs
- `components/agent-session/src/psi/agent_session/conversation.clj`
- relevant tests in `components/agent-session/test`

Initial extension migration target
- `extensions/src/extensions/work_on.clj`

Possible follow-on migration targets
- `extensions/src/extensions/lsp.clj`
- `extensions/src/extensions/agent_chain.clj`

Compatibility notes
- `psi.extension/send-message` remains temporarily as compatibility-only
- its current latent behavior should be preserved during migration so existing extensions do not silently change semantics
- new code should not be written against `send-message`
- stdout capture in CLI/TUI/RPC remains fallback-only until migrations complete

Test strategy
- add focused tests for `notify` vs `append-message` semantics in conversation reconstruction
- add tests for new extension API helpers
- update or add `work_on` tests proving command-visible output comes from explicit extension messaging instead of stdout semantics
- preserve the existing RPC regression proof that blank extension stdout does not create fake messages

Unresolved implementation choice to settle while coding
- whether UI-only notifications should internally remain represented as messages carrying `:custom-type`, or move to a distinct journal/runtime artifact type

Current recommendation:
- allow internal reuse of `:custom-type`-based representation initially if it minimizes risk
- but keep that detail internal and do not expose it as the public semantic contract
