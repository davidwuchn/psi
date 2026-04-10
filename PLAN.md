# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# Active work

## Compatibility scaffold removal

Goal:
- remove internal backward-compatibility scaffolding now that canonical runtime shapes are established
- prioritize internal migration bridges before persisted-data or external-provider compatibility

Planned sequence:
1. remove RPC flat/nested state compatibility scaffolding
2. remove dispatch permission compatibility allow for extensions with missing `:allowed-events`
3. remove remaining dispatch legacy-handler scaffolding
4. remove shared-session executor-era prompt compatibility seams
5. remove adapter/UI fallback payload compatibility once canonical payloads are proven everywhere

### Slice 1 — RPC state compat removal

Target:
- `components/rpc/src/psi/rpc/state.clj`

Remove:
- flat-key read fallbacks
- nested-or-flat migration helpers
- compatibility flat-key writes
- `:focus-session-id*` fallback reads
- duplicate `:rpc-run-fn-registered` / `:rpc-run-fn-registered?` bridging

Keep:
- canonical nested RPC state only:
  - `:transport`
  - `:connection`
  - `:workers`

Acceptance:
- RPC state helpers read/write nested keys only
- RPC tests assert nested state only
- no production code depends on flat RPC state compatibility keys

### Slice 2 — Dispatch permission compat removal

Target:
- `components/agent-session/src/psi/agent_session/dispatch.clj`
- extension manifests / registrations that dispatch extension-origin events

Remove:
- `registered extension with no :allowed-events => compatibility allow`
- `:permission-compat?` compatibility marker

Keep:
- extension-origin dispatch requires:
  - known extension id
  - explicit manifest `:allowed-events`
  - membership of event type in that set

Acceptance:
- extension-origin dispatch without explicit `:allowed-events` is blocked
- extension registrations/tests declare `:allowed-events` explicitly where needed
- no production code depends on permission compatibility allow

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
- ✓ prove initial prompt path logs `prompt-submit -> prompt-prepare-request -> prompt-record-response`
- ✓ prove assistant messages are journaled exactly once through `prompt-record-response`
- ✓ prove tool-use turns route through `:session/prompt-continue`
- ✓ prove continuation re-enters the shared prepare path after tool execution
- ✓ prove terminal prompt result returns session to `:idle` with `is-streaming` false
- ✓ prove migrated RPC prompt flow uses the lifecycle path with streaming/tool-use compatibility

Goals:
- ✓ make prompt lifecycle follow the same architectural transaction shape as tool execution:
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

Current status:
- RPC prompt flow now routes through the prompt lifecycle path.
- app-runtime console and TUI prompt submission now also route through the shared prompt lifecycle path.
- extension run-fn prompt submission now also routes through the shared prompt lifecycle path.
- startup prompt entry now also routes through the shared prompt lifecycle path.
- intentionally isolated workflow/ephemeral runtimes remain outside the shared session lifecycle.
- Lifecycle execution now supports RPC/TUI streaming, extension-initiated prompts, startup prompts, and tool-use continuations.
- Continuation turns preserve effective API key availability via prepared-request fallback.
- Context usage is updated from prompt execution results on the lifecycle path.

Next active slices:
1. converge agent profile / skill injection into request preparation for shared-session prompt paths
2. simplify or remove remaining prompt-path seams once shared-session call paths no longer depend on them

Completed increments:
1. ✓ extract a pure prepared-request projection from canonical session state
2. ✓ introduce dispatch-visible `:session/prompt-prepare-request`
3. ✓ make execution consume the prepared artifact instead of ambient recomputation
4. ✓ introduce deterministic `:session/prompt-record-response`
5. ✓ move continuation / terminalization decisions onto dispatch-visible events
6. ✓ split prompt continuation into dispatch-visible prepare / execute / record follow-on steps
7. ✓ collapse execute -> record into an explicit runtime execute-and-record boundary callable from prepare
8. ~ begin converging agent profile / skill injection into request preparation
9. ✓ migrate RPC prompt flow off `run-agent-loop-in!` onto the lifecycle path

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
- `:session/prompt-finish` ✓
  - handler:
    - dispatches `:on-agent-done` (clears is-streaming, retry state)
    - sends `:session/reset` to statechart → `:idle`
  - effects:
    - `:runtime/dispatch-event` → `:on-agent-done`
    - `:runtime/reconcile-and-emit-background-job-terminals`
    - `:statechart/send-event` → `:session/reset`

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
- proved restart-flow diagnostics continuity by re-syncing after restart and observing fresh initialize + diagnostic requests
- reduced the LSP runtime-path slice's dependence on nullable response stubs by exercising the real managed-service path for trace-oriented flows

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
3. record interceptor enter/exit around the existing dispatch pipeline ✅
4. record handler result + emitted effects summary ✅
5. record effect execution start/finish and associate back to `dispatch-id` ✅
6. record service request/response summaries from the managed-service protocol layer ✅
7. expose trace surface through EQL ✅
8. add focused tests proving one dispatched event yields a coherent trace chain ✅
9. add focused failure/restart-path coverage for dispatch + LSP trace surfaces ✅
10. route post-tool flows through the canonical dispatch pipeline ✅

Current implemented surface:
- bounded canonical trace storage in `psi.agent-session.dispatch`
- stable explicit `dispatch-id` threading without dynamic vars
- trace entry kinds currently recorded:
  - `:dispatch/received`
  - `:dispatch/interceptor-enter`
  - `:dispatch/interceptor-exit`
  - `:dispatch/handler-result`
  - `:dispatch/effects-emitted`
  - `:dispatch/effect-start`
  - `:dispatch/effect-finish`
  - `:dispatch/service-request`
  - `:dispatch/service-response`
  - `:dispatch/service-notify`
  - `:dispatch/completed`
  - `:dispatch/failed`
- post-tool flows now route through dispatch as `:session/post-tool-run` rather than appending ad hoc top-level trace entries
- nested managed-service request/response/notify activity now inherits the enclosing dispatch-owned `dispatch-id` for post-tool LSP flows
- EQL resolver surface:
  - `:psi.dispatch-trace/count`
  - `{:psi.dispatch-trace/recent [...]}`
  - `{:psi.dispatch-trace/by-id [...]}`
  - includes `:psi.dispatch-trace/interceptor-id`
- focused LSP integration tests now prove one dispatch-owned post-tool sync flow yields a coherent trace chain queryable by `dispatch-id`
- focused failure-path tests now cover:
  - handler exception behavior
  - effect execution failure trace recording
  - service error response trace recording
  - post-tool timeout/error shaping without failing the underlying tool result
  - restart-flow initialize/diagnostic continuity

Remaining next increments:
- decide whether LSP findings should remain result-integrated data or become first-class dispatch events of their own
- broaden dispatch-owned tracing to additional slices beyond the current tool/post-tool/service path
- decide whether service lifecycle transitions (`ensure`/`stop`/`restart`) need explicit canonical trace entries

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
