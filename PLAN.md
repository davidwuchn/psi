# Plan

Ordered steps toward PSI COMPLETE.

---

## Done

### Step 15kh — Canonical-root cleanup docs/tests now match the hosted runtime architecture ✓ complete
- Commit `d037818` adds canonical-root-backed test helpers and updates subsystem docs to describe adapters as root-backed views rather than separate sources of truth.
- Cleanup results:
  - added `components/agent-session/test/psi/agent_session/test_support.clj` with `make-session-ctx`, `set-state!`, and `update-state!`
  - migrated representative agent-session runtime/executor tests to canonical-root-backed fixtures
  - updated `statechart.clj` and `extensions.clj` comments to describe `:session-data-atom` and `:ui-state-atom` as adapter-backed compatibility surfaces
- Converged files:
  - `components/agent-session/src/psi/agent_session/statechart.clj`
  - `components/agent-session/src/psi/agent_session/extensions.clj`
  - `components/agent-session/test/psi/agent_session/test_support.clj`
  - `components/agent-session/test/psi/agent_session/runtime_test.clj`
  - `components/agent-session/test/psi/agent_session/executor_test.clj`
- Verification:
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/core.clj components/agent-session/src/psi/agent_session/statechart.clj components/agent-session/src/psi/agent_session/extensions.clj components/agent-session/src/psi/agent_session/resolvers.clj components/agent-session/test/psi/agent_session/runtime_test.clj components/agent-session/test/psi/agent_session/executor_test.clj components/agent-session/test/psi/agent_session/test_support.clj`
  - `clj-paren-repair` on the same file set

### Step 15kg — Runtime-visible ui/recursion/nrepl/oauth projections now converge on canonical state ✓ complete
- Commit `a110370` hosts extension UI state, recursion state, canonical nREPL metadata, and runtime-visible OAuth projections inside the session canonical state root while preserving runtime handles for opaque integrations.
- Phase 2A/2B/2C/2D results:
  - extension UI state now lives under canonical root state and is surfaced through a compatibility atom adapter
  - recursion state now runs through `recursion/create-hosted-context` backed by the canonical session root
  - nREPL endpoint metadata is written to and resolved from canonical runtime-visible state
  - runtime-visible OAuth state now tracks authenticated providers, pending login, and last login metadata in canonical state while the secure OAuth store remains external
- Converged files:
  - `components/agent-session/src/psi/agent_session/core.clj`
  - `components/agent-session/src/psi/agent_session/main.clj`
  - `components/agent-session/src/psi/agent_session/resolvers.clj`
  - `components/agent-session/src/psi/agent_session/rpc.clj`
  - `components/recursion/src/psi/recursion/core.clj`
- Verification:
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/core.clj components/agent-session/src/psi/agent_session/main.clj components/agent-session/src/psi/agent_session/resolvers.clj components/agent-session/src/psi/agent_session/rpc.clj components/recursion/src/psi/recursion/core.clj`
  - `clj-paren-repair` on the same file set

### Step 15kf — Agent-session mutable runtime state now converges on a canonical root-state model ✓ complete
- Commit `3097239` adds `spec/system-context-unification.allium` and integrates that model into the existing session/runtime specs.
- Agent-session implementation now uses a canonical `:state*` root for session-owned mutable state, with path-based helpers exposed from `components/agent-session/src/psi/agent_session/core.clj`.
- Migrated state domains include:
  - session data
  - context index
  - journal + flush state
  - turn context
  - provider request/reply captures
  - tool-call attempt telemetry
  - tool-output telemetry
  - background job state
- Runtime handles remain outside the canonical mutable root where appropriate:
  - UI integration handles
  - agent-core context
  - extension/workflow registries
  - OAuth runtime/store integration
  - nREPL runtime integration
- Converged files:
  - `components/agent-session/src/psi/agent_session/core.clj`
  - `components/agent-session/src/psi/agent_session/runtime.clj`
  - `components/agent-session/src/psi/agent_session/executor.clj`
  - `components/agent-session/src/psi/agent_session/resolvers.clj`
  - `components/agent-session/src/psi/agent_session/persistence.clj`
- Verification:
  - `allium check spec/system-context-unification.allium spec/session-core.allium spec/coding-agent.allium spec/rpc-edn.allium`
  - `clj-kondo --lint components/agent-session/src/psi/agent_session/core.clj components/agent-session/src/psi/agent_session/runtime.clj components/agent-session/src/psi/agent_session/executor.clj components/agent-session/src/psi/agent_session/resolvers.clj components/agent-session/src/psi/agent_session/rpc.clj components/agent-session/src/psi/agent_session/persistence.clj`
  - `clj-paren-repair` on the same file set

### Step 15kfa — Provider captures now have narrow turn-id lookup resolvers for exact failing-turn inspection ✓ complete
- Commit `2413557` adds focused agent-session resolvers to fetch one provider request or one provider reply by turn id instead of forcing full capture-buffer dumps during live debugging.
- Resolver/query surface changes now:
  - add `provider-request-by-turn-id` and `provider-reply-by-turn-id` helpers in `components/agent-session/src/psi/agent_session/resolvers.clj`
  - expose root-style lookup attrs seeded by `:psi.agent-session/lookup-turn-id`:
    - `:psi.agent-session/provider-request-for-turn-id`
    - `:psi.agent-session/provider-reply-for-turn-id`
- Regression coverage now proves exact request/reply lookup by turn id in `components/agent-session/test/psi/agent_session/core_test.clj`.
- Verification:
  - `clojure -M:test --focus psi.agent-session.core-test`
- Follow-up note:
  - local code/tests now support narrow turn-id lookup, but the currently running live app-query graph still needs a real runtime reload/restart before those attrs become queryable there.

### Step 15kf.1 — Provider error replies now survive stream churn and are discoverable in the live graph ✓ complete
- Commit `231477a` retains provider `:error` reply captures in a dedicated error buffer instead of relying on the shared rolling provider reply stream alone.
- Agent-session capture/runtime changes now:
  - add `:provider-error-replies-atom` alongside the general provider reply atom
  - append provider `:error` events into that dedicated buffer with its own retention cap
  - expose `:psi.agent-session/provider-last-error-reply` and `:psi.agent-session/provider-error-replies` from the dedicated store
- API error diagnostics now:
  - enrich assistant-derived API errors from matching provider reply captures when request ids line up
  - deduplicate assistant-derived and provider-derived views of the same logical failure
- Graph/introspection changes now:
  - root-queryable attr discovery flattens join-map outputs, so nested resolver outputs become visible in `:psi.graph/root-queryable-attrs`
  - the new provider error attrs are now graph-discoverable alongside the broader nested session/query surface
- Verification:
  - `clojure -M:test --focus psi.agent-session.core-test --focus psi.ai.providers.anthropic-test`
  - live reload + graph rebuild confirmed fresh Anthropic errors remain queryable through `:psi.agent-session/provider-last-error-reply`, `:psi.agent-session/provider-error-replies`, and enriched `:psi.agent-session/api-errors`

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
