# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# Active work

## Custom providers — local model support

Goal: load user-defined model providers from `models.edn` config files so users
can target local OpenAI-compatible servers (Ollama, vLLM, LM Studio, llama.cpp).

Spec: `spec/custom-providers.allium`
Doc: `doc/configuration.md` (Custom models section)
Meta: `META.md` (model catalog bullets)

### Implementation slices

1. **`psi.ai.user-models`** — new namespace in `components/ai/src/`
   - Read + parse `models.edn` from a given path
   - Validate against malli schema (mirrors the allium `ModelsConfig` shape)
   - Resolve API key specs (`"env:VAR"` / literal)
   - Expand provider + model defs into fully-formed model maps
   - Return `{:models [...] :auth {provider-key → ProviderAuth}}`
   - Log warnings and return empty on invalid file

2. **`psi.ai.model-registry`** — new namespace in `components/ai/src/`
   - Owns the merged model catalog atom: built-in + user + project custom
   - `(all-models)` → replaces current `psi.ai.models/all-models` map
   - `(find-model provider-key model-id)` → lookup
   - `(get-auth provider-key)` → ProviderAuth or nil
   - `(load-user-models! path)` — called at startup
   - `(load-project-models! path)` — called at session bootstrap
   - `(refresh!)` — reload from disk

3. **Schema changes** in `psi.ai.schemas`
   - `Provider`: `[:enum :anthropic :openai]` → `keyword?`
   - `Api`: add no changes (`:openai-completions` already covers local)
   - Model schema: stays closed but Provider constraint relaxes

4. **Provider dispatch fallback** in `psi.ai.core`
   - `resolve-provider` gains api-key fallback:
     `(or (get @registry (:provider model)) (get @registry (:api model)))`
   - Register built-in providers also under their api keys:
     `{:openai-completions openai/provider, :anthropic-messages anthropic/provider, ...}`

5. **Auth injection** in `psi.agent-session.prompt-request`
   - `resolve-api-key` gains model-registry auth lookup as fallback source
   - `session->request-options` propagates `:headers` from custom provider auth
   - When `auth-header?` is false, set sentinel so transport skips `Authorization`

6. **Transport auth-header control** in `psi.ai.providers.openai.chat-completions`
   - `build-request`: skip `Authorization` header when options signal auth-header disabled
   - Merge custom `:headers` from options into request headers

7. **Callers of `ai-models/all-models`** — migrate to `model-registry/all-models`
   - `psi.agent-session.prompt-request/resolve-runtime-model`
   - `psi.ai.core/ai-model-list-resolver`
   - `psi.ai.core/ai-provider-models-resolver`
   - Any other direct refs

8. **Tests**
   - `psi.ai.user-models-test`: parse valid/invalid EDN, env var resolution, defaults
   - `psi.ai.model-registry-test`: merge precedence, no shadow built-ins, auth lookup
   - `psi.ai.core-test`: provider dispatch api-key fallback
   - `psi.agent-session.prompt-request-test`: auth injection for custom providers
   - Integration test: end-to-end with a mock local server (optional / later)

### Dependencies between slices

```
1 (user-models) → 2 (model-registry) → 3 (schema)
                                       → 4 (dispatch)
                                       → 5 (auth injection)
                                       → 6 (transport)
                                       → 7 (caller migration)
8 (tests) follows each slice
```

### Guardrails

- Built-in models unchanged — zero regression risk
- Invalid `models.edn` → warn + empty, never block startup
- No new dependencies between components
- `model-registry` is in `ai` component alongside `models` — same dependency scope
- `auth-header?` defaults to `true` — existing behavior unchanged

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
