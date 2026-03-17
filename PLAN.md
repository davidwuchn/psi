# Plan

Ordered steps toward PSI COMPLETE.

---

## Done

### Step 15ke — Anthropic error capture now preserves raw reply bodies and normalized request ids ✓ complete
- Commit `0bc6fb5` extends Anthropic failure diagnostics so live provider capture retains the actual reply payload, not just the summarized status/request-id string.
- Provider error handling now:
  - parses JSON error bodies when present
  - keeps raw body text alongside parsed body data
  - preserves response headers on emitted `:error` events
  - still appends HTTP status and request id onto the normalized user-facing error message
- Session diagnostics now:
  - retain a deeper provider reply capture history (`1000` replies, `100` requests) so the failing Anthropic turn is less likely to fall off the tail during investigation
  - parse request ids from the normalized `Error ... [request-id req_xxx]` suffix as well as the older raw header-map text format
- Regression coverage added:
  - Anthropic provider tests for captured error body/headers and emitted error event body/headers
  - agent-session core test for request-id parsing from normalized provider error suffix
- Verification:
  - `clojure -M:test --focus psi.agent-session.core-test --focus psi.agent-session.executor-test --focus psi.ai.providers.anthropic-test`

### Step 15kea — Agent-session runtime creation and bootstrap are now split by responsibility ✓ complete
- Commit `2368583` separates UI-specific session context creation from shared runtime bootstrap in `components/agent-session/src/psi/agent_session/main.clj`.
- `create-runtime-session-context` now owns:
  - OAuth context creation
  - effective model/thinking-level resolution
  - `session/create-context`
  - first `session/new-session-in!`
  - UI-specific inputs like `:ui-type` and `:event-queue`
- `bootstrap-runtime-session!` now owns:
  - template/skill/extension discovery
  - system prompt construction and enrichment
  - tool + resolver + extension bootstrap
  - memory sync
  - startup prompt rehydrate
- Runtime entrypoints now compose the two stages explicitly:
  - `run-session` chooses `:console`
  - `run-tui-session` chooses `:tui`
  - `run-rpc-edn-session!` chooses `:emacs`
- Result:
  - bootstrap logic is independent of UI choice
  - future UI additions can reuse bootstrap without widening its responsibility
  - tests can target context creation and bootstrap as separate concerns
- Verification:
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/main.clj`
  - `clj-paren-repair components/agent-session/src/psi/agent_session/main.clj`
