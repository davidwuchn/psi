# Note

Remove completed plan sections once finished; `PLAN.md` should contain active work only.

# Active work

## Structural refactor plan from Gordian

Sequencing note:
- current worktree is active in prompt lifecycle files, so split the refactor into two waves
- Wave A can proceed now with lower-risk structural extraction
- Wave B should follow once the current prompt lifecycle thread settles
- after each step: keep behavior stable, keep tests green, and rerun Gordian at wave boundaries

Guardrails for every commit:
- run targeted tests first, then broader suites before continuing
- useful commands:
  - `bb lint`
  - `bb clojure:test:unit`
  - `bb clojure:test:extensions`
  - `bb emacs:test` when RPC/app-runtime payloads move
- acceptance criteria for each step:
  - no API behavior change
  - no cycles introduced
  - propagation cost stays near the current baseline
  - public façade namespaces become thinner, not broader

### Wave A — landed structural decomposition

Completed commits:
1. `e5411bdc` — `⚒ mutations: split agent-session mutations by domain`
2. `35715c8a` — `⚒ core: extract context prompt settings and introspection namespaces`
3. `c9e652e0` — `⚒ extensions: split runtime eql ui and delivery helpers`
4. `cca1e910` — `⚒ rpc: split command handling by workflow`

What landed:
- `psi.agent-session.mutations` is now an aggregator over focused mutation namespaces:
  - `mutations.session`
  - `mutations.prompts`
  - `mutations.tools`
  - `mutations.extensions`
  - `mutations.services`
  - `mutations.ui`
- `psi.agent-session.core` is now a thin façade over focused runtime namespaces:
  - `context`
  - `prompt-control`
  - `session-settings`
  - `compaction-runtime`
  - `introspection`
- `psi.agent-session.extension-runtime` now delegates to focused extension runtime helpers:
  - `extensions.runtime-eql`
  - `extensions.runtime-ui`
  - `extensions.runtime-fns`
  - `extensions.runtime-delivery`
- `psi.rpc.session.commands` now delegates to focused RPC command helpers:
  - `command-results`
  - `command-resume`
  - `command-tree`
  - `command-pickers`

Validation completed during Wave A:
- focused namespace compilation checks passed
- `clojure:test:unit` passed after each step
- `clojure:test:extensions` passed after each agent-session step
- `emacs:test` passed after the RPC step

Note:
- `bb lint` remains noisy/red because of pre-existing unrelated lint failures in TUI/spec areas, so Wave A used compile checks + targeted suites as the step gate

### Wave B — after prompt lifecycle work settles

#### 5. Extract shared prompt streaming helpers

Create:
- `components/agent-session/src/psi/agent_session/prompt_stream.clj`
  - move shared helpers from `executor.clj` and `prompt_runtime.clj`:
    - `llm-stream-idle-timeout-ms`
    - `llm-stream-wait-poll-ms`
    - `now-ms`
    - `do-stream!`
    - `chain-callbacks`
    - `wait-for-turn-result`
  - move turn-abort helpers from `prompt_runtime.clj`:
    - `cancelled-stream-handle?`
    - `cancel-stream-handle!`
    - `mark-turn-stream-handle!`
    - `abort-turn!`

Suggested commit:
- `⚒ prompts: extract shared prompt streaming helpers`

Validation:
- `bb lint`
- `bb clojure:test:unit`
- `bb clojure:test:extensions`

#### 6. Split single-turn execution from loop execution

Create:
- `components/agent-session/src/psi/agent_session/prompt_turn.clj`
  - move from `executor.clj`: `session-messages`, `session-tool-defs`, `stream-turn!`, `run-turn!`
- `components/agent-session/src/psi/agent_session/prompt_loop.clj`
  - move from `executor.clj`: `run-agent-loop!`

Reduce:
- `components/agent-session/src/psi/agent_session/executor.clj`
  - keep as a temporary façade to `prompt-turn` / `prompt-loop`, then delete once callers move

Suggested commit:
- `⚒ prompts: split single-turn and loop execution`

Validation:
- `bb lint`
- `bb clojure:test:unit`
- `bb clojure:test:extensions`

#### 7. Narrow the prepared-request runtime boundary

Reduce:
- `components/agent-session/src/psi/agent_session/prompt_runtime.clj`
  - keep only: `execute-prepared-request!`, `abort-active-turn-in!`
  - consume `prompt_stream.clj` instead of owning duplicate stream helpers

Suggested commit:
- `⚒ prompts: narrow prompt-runtime to prepared-request execution`

Validation:
- `bb lint`
- `bb clojure:test:unit`
- `bb clojure:test:extensions`

#### 8. Consolidate prompt dispatch registration

Create:
- `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_lifecycle.clj`
  - start by moving the current contents of `dispatch_handlers/prompt_handlers.clj`
  - then move any prompt-lifecycle registrations currently split across `session_lifecycle.clj` and `statechart_actions.clj`

Update:
- `components/agent-session/src/psi/agent_session/dispatch_handlers.clj`
  - require/register `prompt-lifecycle` instead of `prompt-handlers`

Delete when stable:
- `components/agent-session/src/psi/agent_session/dispatch_handlers/prompt_handlers.clj`

Suggested commit:
- `⚒ dispatch: consolidate prompt lifecycle handler registration`

Validation:
- `bb lint`
- `bb clojure:test:unit`
- `bb clojure:test:extensions`

#### 9. Extract a canonical background-job view model

Create:
- `components/app-runtime/src/psi/app_runtime/background_job_view.clj`
  - move from `background_jobs.clj`: `default-list-statuses`, `status-order`, `sort-jobs`, `job-summary`, `jobs-summary`, `job-detail`, `cancel-job-summary`

Reduce:
- `components/app-runtime/src/psi/app_runtime/background_jobs.clj`
  - keep as façade only during migration
- `components/app-runtime/src/psi/app_runtime/background_job_widgets.clj`
  - consume `background-job-view` directly

Suggested commit:
- `⚒ background-jobs: extract canonical view model`

Validation:
- `bb lint`
- `bb clojure:test:unit`
- `bb emacs:test`

#### 10. Finish the OpenAI internal split

Create:
- `components/ai/src/psi/ai/providers/openai/transport.clj`
- `components/ai/src/psi/ai/providers/openai/content.clj`
- `components/ai/src/psi/ai/providers/openai/reasoning.clj`

Move:
- transport/error/capture helpers out of `common.clj` to `transport.clj`
- content/tool/message-shaping helpers out of `common.clj` to `content.clj`
- reasoning helpers out of `common.clj` to `reasoning.clj`

Reduce:
- `components/ai/src/psi/ai/providers/openai/common.clj`
  - keep as a temporary façade during migration, then delete when callers are updated

Suggested commit:
- `⚒ openai: separate transport content and reasoning helpers`

Validation:
- `bb lint`
- provider-focused unit tests
- `bb clojure:test:unit`

### Recommended commit order

1. `⚒ mutations: split agent-session mutations by domain`
2. `⚒ core: extract context prompt settings and introspection namespaces`
3. `⚒ extensions: split runtime eql ui and delivery helpers`
4. `⚒ rpc: split command handling by workflow`
5. `⚒ prompts: extract shared prompt streaming helpers`
6. `⚒ prompts: split single-turn and loop execution`
7. `⚒ prompts: narrow prompt-runtime to prepared-request execution`
8. `⚒ dispatch: consolidate prompt lifecycle handler registration`
9. `⚒ background-jobs: extract canonical view model`
10. `⚒ openai: separate transport content and reasoning helpers`

### Wave-boundary checks

After Wave A:
- rerun Gordian on src
- verify `psi.agent-session.core` is thinner, `psi.agent-session.mutations` is near-trivial, and `extension-runtime` no longer mixes unrelated concerns

After Wave B:
- rerun Gordian on src and src+test
- compare prompt-subsystem, background-job, and OpenAI coupling against the current baseline

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
