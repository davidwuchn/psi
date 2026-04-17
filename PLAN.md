# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# Active work

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

Checklist:
- shared-session prompt-path seams
  - [x] remove prompt-runtime targeted-test compatibility sentinels from `prompt_runtime.clj`
  - [x] update focused prompt tests to use only canonical prompt-stream timeout/aborted sentinels
  - [x] remove built-in `ai-models/all-models` fallback scan from `prompt_request.clj/resolve-runtime-model`
  - [x] make `model-registry` the only shared-session model resolution path
  - [x] remove remaining prompt-path migration hooks/comments in `prompt_request.clj`, `prompt_runtime.clj`, `prompt_turn.clj`, `system_prompt.clj`, and `conversation.clj`
  - [x] verify request preparation is the explicit home for prompt layer assembly, cache breakpoint projection, and provider request shaping
  - [~] remove any remaining shared-session prompt semantics still split across `system_prompt.clj`, `conversation.clj`, or extension-local string composition
    - base system prompt assembly no longer appends prompt contributions directly
    - prompt contribution application is now centralized in prompt handlers + request preparation
    - developer layer assembly now happens in request preparation instead of bootstrap fallback composition
    - agent profiles now flow through child-session `:developer-prompt` / `:developer-prompt-source` instead of being merged into base system prompts
    - fallback developer-prompt mirroring of base system prompt has been removed
    - `/skill:` and template invocation now expand canonically during request preparation
    - memory recovery for submitted prompt text now runs from the prepared-request path instead of caller-local preview hooks
    - next follow-on is to converge skill-prelude composition into request preparation
- adapter/UI fallback payload compatibility
  - [x] remove Emacs "no session id means accept event" compatibility from `psi-events.el`
  - [x] decide and enforce canonical per-event session targeting expectations
  - [x] remove non-canonical event key-shape support where RPC already proves canonical payloads
  - [x] remove frontend session-tree label reconstruction fallback; require backend `:label`
  - [x] remove backward-compatible `/tree` action payload alist handling; standardize on canonical action payloads
  - [x] remove legacy footer `:stats-line` fallback; require structured `:usage-parts` + `:model-text`
  - [x] remove any now-dead Emacs compatibility helpers once backend-owned projections are fully authoritative
- proof and cleanup
  - [~] strengthen RPC tests for canonical-only payload shapes on `session/updated`, `context/updated`, `footer/updated`, `ui/frontend-action-requested`, and `/tree`
    - canonical footer payload assertions now prefer structured fields (`:usage-parts`, `:model-text`, `:status-line`)
    - canonical `ui/frontend-action-requested` assertions now require backend-owned `:ui/action` and no top-level `:action-name`
    - `session/updated` and `context/updated` tests now assert tighter canonical key sets
  - [x] strengthen Emacs tests so compat branches can be deleted confidently
  - [x] remove tests that exist only to preserve legacy internal payload shapes
  - [ ] run `bb clojure:test`
  - [x] run `bb emacs:check`
  - [ ] run `bb lint`
  - [ ] run `bb fmt:check`
  - [x] update `mementum/state.md` when slices land

Suggested order:
1. remove footer fallback
2. remove tree label fallback
3. remove tree action payload fallback
4. remove session-id + key-shape cleanup branches
5. remove prompt sentinel compatibility
6. remove runtime model fallback bridge
7. do final prompt seam sweep and delete dead compatibility helpers

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
  - skill prelude / profile injection
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
- effective system prompt assembly in request preparation now composes:
  - base system prompt
  - optional developer layer
  - prompt contributions
- agent profile prompts now flow as explicit developer layers rather than being merged into base system prompts.
- bootstrap no longer creates a fallback developer layer mirroring the base system prompt.

Next active slices:
1. converge skill-prelude injection into request preparation for shared-session prompt paths
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

Completed so far:
- added a `:skill` argument to the `agent` tool
- when used without fork, the named skill now seeds the spawned child session with a synthetic prelude sequence:
  - a synthetic "use the skill" user message
  - an assistant message containing the raw skill content
  - a synthetic user message containing the task prompt
  - a synthetic assistant acknowledgement
- child-session creation now accepts preloaded messages and explicit cache-breakpoint overrides so the prelude can be seeded before execution starts
- the agent extension now sets `#{:system :tools}` cache breakpoints for this prelude-seeded flow

Remaining follow-on:
- make the cache-breakpoint split more explicit so the reusable skill prelude is separated from the variable tail of the conversation with the intended 2-breakpoint shape
- decide whether the assistant acknowledgement should become a more structured/canonical skill-prelude marker rather than plain text
- consider exposing prelude seeding / source metadata through session introspection if needed for debugging

## Auto session name extension

Completed so far:
- extension MVP landed in `extensions.auto-session-name`
- prompt lifecycle now emits canonical extension event `session_turn_finished`
- extensions can request delayed extension dispatch via `psi.extension/schedule-event`
- extension API now supports explicit session targeting via:
  - `:query-session`
  - `:mutate-session`
- rename flow now:
  - reads journal-backed source session entries
  - sanitizes visible user/assistant text
  - creates a helper child session
  - runs one sync helper turn
  - applies validated inferred titles to the source session
- extension-local guards now prevent:
  - recursive helper-session checkpoint scheduling
  - stale checkpoint overwrite
  - overwrite when the current name diverges from the last auto-applied name

Next active slices:
1. helper model selection for rename inference
2. formalize session-name source metadata (`:manual` vs `:auto`) beyond extension-local heuristics
3. make helper sessions/internal runs explicitly non-user-facing by runtime contract
4. consider persistence/reload semantics for extension-owned rename state if needed

## Model selection hierarchy

Design note landed: `doc/design-model-selection-hierarchy.md`

Next active slices:
1. introduce task-class-based model selection requests for helper/background work
2. separate hard capability filtering from soft preference ranking
3. use the selector for auto-session-name helper execution before broader agent adoption
4. expose explainable selection results / fallback reasons
