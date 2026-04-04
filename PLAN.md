# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# Active work

## Prompt lifecycle architectural convergence

Active prompt lifecycle shape:
- `prompt-in!`
- `:session/prompt-submit`
- `:session/prompt`
- `:session/prompt-prepare-request`
- `:runtime/prompt-execute-and-record`
- `:session/prompt-record-response`
- `:session/prompt-continue` | `:session/prompt-finish`

Testing priorities:
- prove initial prompt path logs `prompt-submit -> prompt-prepare-request -> prompt-record-response`
- prove assistant messages are journaled exactly once through `prompt-record-response`
- prove tool-use turns route through `:session/prompt-continue`
- prove continuation re-enters the shared prepare path after tool execution

Goals:
- make prompt lifecycle follow the same architectural transaction shape as tool execution:
  - prepare -> execute -> record
- make request preparation the explicit home for:
  - prompt layer assembly
  - cache breakpoint projection
  - provider request shaping
  - skill / profile prelude injection
- reduce prompt semantics currently split across:
  - `system_prompt.clj`
  - `conversation.clj`
  - `executor.clj`
  - extension-local string composition

Planned increments:
1. extract a pure prepared-request projection from canonical session state
2. introduce dispatch-visible `:session/prompt-prepare-request`
3. make execution consume the prepared artifact instead of ambient recomputation
4. introduce deterministic `:session/prompt-record-response`
5. move continuation / terminalization decisions onto dispatch-visible events
6. split prompt continuation into dispatch-visible prepare / execute / record follow-on steps
7. collapse execute -> record into an explicit runtime execute-and-record boundary callable from prepare
8. converge agent profile / skill injection into request preparation

Proposed handler / effect split:
- `:session/prompt-submit`
  - handler:
    - validate / normalize submitted user message
    - return journal-append effect and submitted-turn metadata
  - effects:
    - `:persist/journal-append-message-entry`
- `:session/prompt-prepare-request`
  - handler:
    - read canonical session state + journal
    - project prepared request artifact
    - return prepared request in `:return` and optionally store a bounded projection in session state for introspection
  - effects:
    - `:runtime/prompt-execute-and-record`
- `:runtime/prompt-execute-and-record`
  - runtime boundary:
    - perform provider streaming against the prepared request artifact
    - capture request/response telemetry
    - dispatch `:session/prompt-record-response`
- `:session/prompt-record-response`
  - handler:
    - accept shaped execution result
    - append assistant message / usage / turn metadata deterministically
    - derive continuation decision from recorded result
  - effects:
    - journal append effects
    - follow-on dispatch effects for continue / finish
- `:session/prompt-continue`
  - handler:
    - route tool execution continuation from canonical recorded state
  - effects:
    - runtime tool continuation boundary
    - next prompt preparation event
- `:session/prompt-finish`
  - handler:
    - finalize turn lifecycle visibility in canonical state
  - effects:
    - background-job reconciliation / terminal emission
    - statechart completion event if needed

Proposed code scaffold:
- new namespace: `psi.agent-session.prompt-request`
  - `journal->provider-messages`
  - `session->request-options`
  - `build-prompt-layers`
  - `build-prepared-request`
- new namespace: `psi.agent-session.prompt-runtime`
  - `execute-prepared-request!`
- new namespace: `psi.agent-session.prompt-recording`
  - `extract-tool-calls`
  - `classify-execution-result`
  - `build-record-response`
- new ctx callbacks:
  - `:build-prepared-request-fn`
  - `:execute-prepared-request-fn`
  - `:build-record-response-fn`
- new dispatch handlers:
  - `:session/prompt-prepare-request`
  - `:session/prompt-record-response`
  - `:session/prompt-continue`
  - `:session/prompt-finish`
- new runtime effects:
  - `:runtime/prompt-execute-and-record`
  - `:runtime/prompt-continue-chain`

Prepared-request v1 shape:
```clojure
{:prepared-request/id turn-id
 :prepared-request/session-id session-id
 :prepared-request/user-message user-message
 :prepared-request/session-snapshot {:model ...
                                     :thinking-level ...
                                     :prompt-mode ...
                                     :cache-breakpoints ...
                                     :active-tools ...}
 :prepared-request/prompt-layers [{:id :system/base
                                   :kind :system
                                   :stable? true
                                   :content ...}]
 :prepared-request/system-prompt ...
 :prepared-request/system-prompt-blocks ...
 :prepared-request/messages ...
 :prepared-request/tools ...
 :prepared-request/model ...
 :prepared-request/ai-options ...
 :prepared-request/cache-projection {:cache-breakpoints ...
                                     :system-cached? ...
                                     :tools-cached? ...
                                     :message-breakpoint-count ...}
 :prepared-request/provider-conversation ...}
```

Execution-result v1 shape:
```clojure
{:execution-result/turn-id ...
 :execution-result/session-id ...
 :execution-result/prepared-request-id ...
 :execution-result/assistant-message ...
 :execution-result/usage ...
 :execution-result/provider-captures {:request-captures ...
                                      :response-captures ...}
 :execution-result/turn-outcome ...
 :execution-result/tool-calls ...
 :execution-result/error-message ...
 :execution-result/http-status ...
 :execution-result/stop-reason ...}
```

## LSP integration on top of managed services + post-tool processing

Completed foundation:
- structured `write` and `edit` tool results now include `:meta`, `:effects`, and `:enrichments`
- ctx-owned managed subprocess service registry exists
- additive timeout-bounded post-tool processor runtime exists
- tool execution now applies post-tool processing before result recording/provider return
- services, post-tool processors, and telemetry are queryable through EQL
- extension API exposes post-tool processor registration and managed service hooks
- mutation layer supports post-tool and service operations
- shared stdio / JSON-RPC helper layer exists for managed services

Active goals:
- build an LSP integration on top of the new runtime primitives, starting with `clojure-lsp`
- key LSP services by logical workspace identity (initially nearest `.git` root)
- inject lint/diagnostic findings into `write` / `edit` tool results through post-tool processors
- keep timeout-bounded synchronous behavior so diagnostics can be included before provider return
- expose enough service/telemetry state to debug LSP startup and request flow

Planned increments:
1. define LSP extension/runtime configuration shape, with default `clojure-lsp`
2. implement workspace keying and nearest-`.git` root detection for LSP service reuse
3. add LSP-specific service ensure/start logic on top of managed subprocesses
4. implement JSON-RPC initialize / notification / request flow for LSP servers
5. add a post-tool processor for `write` and `edit` that syncs documents and requests diagnostics within timeout
6. shape diagnostics into tool enrichments and provider-facing appended content
7. add slash/status surfaces for inspecting and restarting LSP services
8. add focused tests for LSP routing, timeout behavior, and diagnostics projection

Likely namespaces to add/change next:
- new extension or runtime namespace for LSP orchestration
- `psi.agent-session.service-protocol`
- `psi.agent-session.post-tool`
- `psi.agent-session.services`
- extension-facing glue where LSP commands/status are surfaced

Acceptance criteria:
- `clojure-lsp` can be ensured as a managed service for a workspace key
- `write` / `edit` can trigger LSP-backed post-tool diagnostics within timeout
- diagnostics appear as structured enrichments and usable provider-facing content
- timeout or LSP failure does not fail the underlying tool result by default
- service state and telemetry are queryable enough to debug LSP behavior

## Agent tool skill prelude follow-on

- Add a `:skill` argument to the `agent` tool.
- When used without fork, the specified skill should be read and injected as a synthetic sequence in the spawned session context:
  - a synthetic "use the skill" user message
  - the skill content
  - the effective prompt
  - the corresponding assistant reply
- Insert a cache breakpoint so the reusable skill prelude is separated from the variable tail of the conversation.
- Goal: reduce end-of-conversation breakpoints from 3 to 2 for this flow.
- Expected benefit: better caching for repeated prompts that reuse the same skill.
