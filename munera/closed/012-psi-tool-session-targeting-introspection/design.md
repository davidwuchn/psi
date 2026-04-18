Goal: make `psi-tool` reliable for session-targeted introspection, especially when diagnosing helper/background child sessions such as `auto-session-name` runs.

Context:
- `psi-tool` is the live graph/EQL diagnostic surface intended to answer questions about runtime state without guessing from files or code.
- Recent diagnosis work on `auto-session-name` needed to confirm which model was actually used by helper child sessions.
- The live runtime clearly contains helper child sessions; `:psi.agent-session/context-sessions` returns multiple `auto-session-name` sessions with distinct ids and parent-session relationships.
- However, the session-targeting paths attempted through `psi-tool` did not reliably read those sessions. Queries either failed as unreachable or silently collapsed back to the currently active session.

Problem statement:
`psi-tool` currently cannot be trusted to answer session-scoped questions about non-active sessions, even when those sessions are visible in runtime context state and have stable session ids.

Observed behavior:
1. Runtime session discovery works.
   - `:psi.agent-session/context-sessions` returns helper child sessions, including multiple `auto-session-name` sessions.
   - This proves the sessions exist in canonical runtime state and are visible through the introspection surface at least as discovered entities.

2. Direct session-targeted reads failed.
   - Attempts to query specific helper sessions using ident-style targeting such as:
     - `{[:psi.agent-session/session-id "..."] [...]}`
   - returned attribute-unreachable errors for session-scoped attrs such as:
     - `:psi.agent-session/session-name`
     - `:psi.agent-session/model-provider`
     - `:psi.agent-session/model-id`
     - `:psi.agent-session/provider-request-count`
   - This means the graph surface either does not support the expected entity targeting shape for these attrs through `psi-tool`, or the tool/query wiring is not preserving the intended seed semantics.

3. Placeholder/context injection did not target the requested session.
   - Attempts to inject `:psi.agent-session/session-id` via placeholder/context forms such as `:>/ctx` returned data for the active session instead of the requested helper session.
   - This is worse than a hard error because it produces plausible but incorrect diagnostics.
   - In the observed case, helper-session-targeted queries reported the active session’s model (`openai/gpt-5.4`) regardless of which helper session id was requested.

4. Parameterized attr attempts also collapsed to the active session.
   - Attempts to use parameterized attr forms with `{:session-id "..."}` likewise returned active-session answers rather than the requested helper session.
   - This suggests the issue is not limited to one EQL syntax form; rather, the `psi-tool` path currently lacks a trustworthy way to override active-session scope for session-scoped reads.

5. As a result, `psi-tool` could not confirm the actual model used by `auto-session-name` helper sessions.
   - The extension code path suggests a helper-selected model should be passed explicitly.
   - Separate code-level and model-selection checks suggest a qualifying local Gemma model should win under the current policy.
   - But the live diagnostic question — which model did those actual helper sessions use — could not be answered with confidence from `psi-tool` because targeted session reads were not trustworthy.

Why this matters:
- `psi-tool` is supposed to be the authoritative runtime truth surface for live diagnosis.
- Helper/background workflows increasingly depend on child sessions, delayed events, explicit session routing, and non-active runtime work.
- If `psi-tool` can only answer questions about the active session, or silently rewrites targeted queries back to active-session scope, then it fails as a diagnostic tool precisely where runtime behavior is becoming more complex.
- Silent scope collapse is especially dangerous because it can mislead debugging, make regressions appear elsewhere, and undermine confidence in the introspection surface.

Concrete impact demonstrated in this investigation:
- We could verify that `auto-session-name` helper sessions existed.
- We could not use `psi-tool` to verify their actual `model-provider` / `model-id`.
- The tool instead kept returning the active diagnostic session’s model, which made the result unusable for answering the original question.

Required outcome at the task level:
- `psi-tool` must support a canonical, documented, trustworthy way to read session-scoped attrs for an explicitly requested session id.
- When a targeting form is invalid or unsupported, the tool should fail clearly rather than silently returning data from a different session.
- Session discovery output and session-targeted follow-up queries must compose into a reliable diagnostic workflow for helper/background child sessions.

Non-goals:
- do not broaden this task into changing auto-session-name model policy itself
- do not treat this as a UI/session-tree visibility problem
- do not solve it by telling users to inspect files or code instead of runtime state
- do not preserve ambiguous query forms that can silently read the wrong session if a clearer canonical session-targeting contract is available

Acceptance:
- after discovering session ids via `psi-tool`, a follow-up `psi-tool` query can reliably read session-scoped attrs for those exact sessions
- querying helper/background child sessions returns their own session/model/telemetry data, not the active session’s
- unsupported or malformed targeting forms fail explicitly rather than degrading to active-session answers
- the canonical way to target a session through `psi-tool` is documented and covered by tests
