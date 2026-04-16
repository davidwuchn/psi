# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# Active work

## Command-dispatch convergence

Spec: `spec/command-dispatch-convergence.allium`

Goal: every state-mutating slash command executes through the dispatch pipeline,
sharing the same dispatch event as the corresponding Pathom mutation.

Architecture:
```
text → parse (commands.clj)
     → state mutation? → *-in! → dispatch! → handler → effects
     → read query?     → EQL → format
     → adapter signal? → return type marker
```

`*-in!` functions are the canonical shared execution point. Both commands and
mutations call them. They call `dispatch/dispatch!`.

### Already converged
- `/model <p> <id>` → `set-model-in!` → `dispatch! :session/set-model`
- `/thinking <level>` → `set-thinking-level-in!` → `dispatch! :session/set-thinking-level`
- `/tree name` → `set-session-name-in!` → `dispatch! :session/set-session-name`
- `/new` → lifecycle dispatch events
- `/status` → `session/query-in` (EQL resolvers)
- `/worktree` → `session/query-in` (EQL resolvers)

### Write slices (state-mutating → dispatch pipeline)

Each: dispatch handler + effect type + `*-in!` function + mutation + update command.

1. `/reload-models` — dispatch `:session/reload-models`, effect `:model-registry/reload`
2. `/cancel-job` — dispatch `:session/cancel-job`, effect `:background-job/cancel`
3. `/remember` — dispatch `:session/remember`, effect `:memory/capture`
4. `/login` — dispatch `:session/login-begin`, effect `:oauth/begin-login`
5. `/logout` — dispatch `:session/logout`, effect `:oauth/logout`

### Read slices (direct atom reads → EQL resolvers)

Each: switch from `ss/get-session-data-in` / `agent/get-data-in` / `bg-rt/*` to `session/query-in`.

6. `/help`, `/prompts`, `/skills` — resolvers exist (`:psi.agent-session/prompt-templates`, `:psi.agent-session/skills`, `:psi.extension/command-names`)
7. `/model` (show), `/thinking` (show) — resolvers exist (`:psi.agent-session/model`, `:psi.agent-session/thinking-level`)
8. `/jobs`, `/job` — resolver exists (`:psi.agent-session/background-jobs`); filter for single job
9. `/history` — **new resolver** `:psi.agent-session/message-history` (returns truncated message list)

### Cleanup

10. Verify `commands.clj` has no direct atom reads or side-effect calls

## Post-Wave-B Gordian follow-on

Wave B is complete.

Landed commits:
1. `da746cef` — `⚒ prompts: extract shared prompt streaming helpers`
2. `177a3551` — `⚒ prompts: split single-turn and loop execution`
3. `7221fa57` — `⚒ prompts: narrow prompt-runtime to prepared-request execution`
4. `1530e0b9` — `⚒ dispatch: consolidate prompt lifecycle handler registration`
5. `5ea34cc0` — `⚒ background-jobs: extract canonical view model`
6. `0614907f` — `⚒ openai: separate transport content and reasoning helpers`

Wave-boundary check results:
- Gordian `src`: propagation cost `13.7%`, cycles `none`, namespaces `181`
- Gordian `src+test`: propagation cost `12.9%`, cycles `none`, namespaces `306`
- prompt, background-job, and OpenAI slices all moved toward thinner focused namespaces without introducing cycles

Active follow-on slices from Gordian:
1. keep an eye on prompt-lifecycle / session-lifecycle conceptual overlap while avoiding new structural coupling
2. fold child-session runtime allocation semantics into the session lifecycle boundary so mutation-time child creation remains obviously aligned with spec/tests

Completed follow-on slices:
- OpenAI migration completed; `psi.ai.providers.openai.common` has been deleted
- background-job façade retirement completed; `psi.app-runtime.background-jobs` has been deleted

Guardrails for follow-on commits:
- keep behavior stable
- keep tests green
- do not introduce cycles
- prefer thinning façades over adding new indirection

## Compatibility scaffold removal

Goal:
- remove remaining internal backward-compatibility scaffolding now that canonical runtime shapes are established
- prioritize internal migration bridges before persisted-data or external-provider compatibility

Active slices:
1. remove remaining shared-session prompt-path seams
2. remove adapter/UI fallback payload compatibility once canonical payloads are proven everywhere

## Prompt lifecycle architectural convergence

Active prompt lifecycle shape:
- `prompt-in!`
- `:session/prompt-submit`
- `:session/prompt`
- `:session/prompt-prepare-request`
- `:runtime/prompt-execute-and-record`
- `:session/prompt-record-response`
- `:session/prompt-continue` | `:session/prompt-finish`

Still-active goals:
- make request preparation the explicit home for:
  - prompt layer assembly
  - cache breakpoint projection
  - provider request shaping
  - skill / profile prelude injection
- reduce any remaining prompt semantics still split across:
  - `system_prompt.clj`
  - `conversation.clj`
  - extension-local string composition
- simplify remaining prompt-path seams once shared-session call paths no longer depend on them

Current status:
- RPC prompt flow now routes through the prompt lifecycle path.
- app-runtime console and TUI prompt submission now also route through the shared prompt lifecycle path.
- extension run-fn prompt submission now also routes through the shared prompt lifecycle path.
- startup prompt entry now also routes through the shared prompt lifecycle path.
- shared-session prompt-path executor loop helpers / override seams have been removed.
- intentionally isolated workflow/ephemeral runtimes remain outside the shared session lifecycle.
- lifecycle execution now supports RPC/TUI streaming, extension-initiated prompts, startup prompts, and tool-use continuations.
- continuation turns preserve effective API key availability via prepared-request fallback.
- context usage is updated from prompt execution results on the lifecycle path.

Next active slices:
1. converge agent profile / skill injection into request preparation for shared-session prompt paths
2. simplify or remove remaining prompt-path seams, comments, and test hooks once they are no longer needed

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
