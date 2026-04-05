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

Achieved so far:
- LSP ownership now lives in the extension layer, not core
- `extensions.lsp` now owns:
  - default `clojure-lsp` config
  - nearest-`.git` workspace root detection
  - workspace-keyed services via `[:lsp workspace-root]`
  - extension-owned `ensure-service` / `stop-service` use
  - JSON-RPC initialize / initialized flow
  - document sync via `didOpen` and `didChange`
  - document close on workspace restart via `didClose`
  - diagnostics requests via `textDocument/diagnostic`
  - additive diagnostics enrichments + provider-facing appended content
  - non-fatal error shaping for LSP/runtime failures
  - command surfaces: `lsp-status` and `lsp-restart`
- managed service runtime now supports protocol-tagged stdio JSON-RPC attachment
- live stdio JSON-RPC correlation is proven with a babashka fixture subprocess
- the LSP extension service spec now explicitly opts into `:protocol :json-rpc`
- focused tests now cover:
  - extension service spec
  - runtime attachment through mutation path
  - live JSON-RPC roundtrip correlation
  - diagnostics projection
  - command/status/restart behavior

Active goals:
- increase confidence that the LSP extension uses the real runtime path wherever practical
- reduce reliance on nullable response stubs for diagnostics-oriented extension tests
- decide how much JSON-RPC adapter debug instrumentation should remain as permanent observability surface
- expose enough service/telemetry state to debug LSP startup and request flow

Planned increments:
1. decide whether adapter debug atoms remain test-only helpers or become queryable runtime telemetry
2. simplify overlapping live/debug tests now that babashka-backed roundtrip proof exists

Completed in this slice:
- added runtime-backed LSP extension tests using a babashka LSP fixture subprocess
- proved initialize -> initialized -> didOpen -> diagnostic flow through the live managed-service mutation path
- proved subsequent sync uses `didChange` for already-open tracked documents
- proved workspace restart closes tracked documents, clears initialization state, and respawns the service
- proved post-tool handler adds diagnostics enrichments/content append and shapes non-fatal failure output
- proved workspace status rendering reflects live service state and tracked documents

Likely namespaces to add/change next:
- `extensions.lsp`
- `psi.agent-session.service-protocol`
- `psi.agent-session.service-protocol-stdio-jsonrpc`
- `psi.agent-session.post-tool`
- `psi.agent-session.services`
- LSP extension tests using real mutation/runtime path

Acceptance criteria:
- `clojure-lsp` can be ensured as a managed service for a workspace key
- protocol-tagged stdio services attach live JSON-RPC runtime automatically through the mutation path
- `write` / `edit` can trigger LSP-backed diagnostics within timeout
- diagnostics appear as structured enrichments and usable provider-facing content
- timeout or LSP failure does not fail the underlying tool result by default
- service state and telemetry are queryable enough to debug LSP behavior

## Canonical dispatch pipeline trace / observability

Problem:
- dispatch is the architectural coordination boundary, but observability is still split across lifecycle events, service telemetry, post-tool telemetry, debug atoms, and ad hoc runtime hooks
- there is no single canonical, queryable, end-to-end trace linking:
  - dispatch event receipt
  - interceptor stages
  - handler result
  - emitted effects
  - effect execution
  - service request/response
  - final completion/failure

Goal:
- make dispatch observability consistent with the current architecture by introducing one canonical, queryable dispatch trace model tied to the dispatch pipeline itself

Desired properties:
- one `dispatch-id` / trace id per dispatched event
- bounded queryable runtime state
- trace entries correlated by:
  - `dispatch-id`
  - `session-id`
  - `event-type`
  - `tool-call-id` where relevant
  - service key / request id where relevant
- useful for debugging, replay gaps, and architectural introspection

Trace entry candidates:
- `:dispatch/received`
- `:dispatch/interceptor-enter`
- `:dispatch/interceptor-exit`
- `:dispatch/handler-result`
- `:dispatch/effects-emitted`
- `:dispatch/effect-start`
- `:dispatch/effect-finish`
- `:dispatch/service-request`
- `:dispatch/service-response`
- `:dispatch/completed`
- `:dispatch/failed`

Planned increments:
1. define canonical trace entry schema + bounded runtime storage ✅
2. generate a `dispatch-id` at dispatch entry and thread it through opts/context ✅
3. record interceptor enter/exit around the existing dispatch pipeline
4. record handler result + emitted effects summary
5. record effect execution start/finish and associate back to `dispatch-id` ✅
6. record service request/response summaries from the managed-service protocol layer ✅
7. expose trace surface through EQL ✅
8. add focused tests proving one dispatched event yields a coherent trace chain ✅

Current implemented surface:
- bounded canonical trace storage in `psi.agent-session.dispatch`
- stable explicit `dispatch-id` threading without dynamic vars
- trace entry kinds currently recorded:
  - `:dispatch/received`
  - `:dispatch/effect-start`
  - `:dispatch/effect-finish`
  - `:dispatch/service-request`
  - `:dispatch/service-response`
  - `:dispatch/service-notify`
  - `:dispatch/completed`
  - `:dispatch/failed`
- EQL resolver surface:
  - `:psi.dispatch-trace/count`
  - `{:psi.dispatch-trace/recent [...]}`
  - `{:psi.dispatch-trace/by-id [...]}`
- focused LSP integration tests now prove one post-tool sync flow yields a coherent trace chain queryable by `dispatch-id`

Remaining next increments:
- interceptor enter/exit entries
- handler result / effects-emitted summary entries
- broaden tests for failure/restart flows and more dispatch-owned slices

Likely namespaces to add/change next:
- `psi.agent-session.dispatch`
- `psi.agent-session.dispatch-effects`
- `psi.agent-session.dispatch-handlers`
- `psi.agent-session.service-protocol`
- `psi.agent-session.service-protocol-stdio-jsonrpc`
- new resolver namespace for dispatch trace introspection

Acceptance criteria:
- every dispatched event gets a stable `dispatch-id`
- interceptor/handler/effect/service stages can be queried by `dispatch-id`
- one tool/prompt/service flow can be followed end-to-end from the query surface
- trace storage is bounded and safe for long-running sessions
- the new trace surface reduces dependence on ad hoc debug atoms for normal diagnosis

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
