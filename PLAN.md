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
- graph derivation convergence completed; active capability-graph consumers now use `psi.graph.analysis`, and `psi.introspection.graph` is now a compatibility wrapper
- RPC action/result thinning slice landed: picker selection side-effects now live in `psi.rpc.session.command-pickers` instead of being split across `command-pickers` and `frontend-actions`

New near-term slice:
- continue thinning the RPC edge around `psi.rpc.session.commands` ↔ `psi.rpc.session.frontend-actions`
  - likely next target: centralize remaining command-result/error/session-snapshot emission decisions so command submission and frontend-action submission share less sibling-local orchestration logic
  - keep navigation handling stable and avoid broad RPC router reshaping

Deeper investigation queue from latest Gordian namespace analysis:

Suggested execution order:
1. `psi.ai.conversation` ↔ `psi.ai.providers.anthropic` — highest current signal because Gordian found hidden coupling in 2 lenses and recommended extraction
2. background-job family seams — medium/high leverage because the same concepts span runtime, telemetry, projection, and UI layers
3. `psi.agent-session` family internal structure — broad architectural check with potential to guide several later thinning decisions
4. adapter-edge family coherence across `psi.rpc`, `psi.app-runtime`, and `psi.tui` — validates that the recent projection convergence work is holding
5. graph/introspection ownership — important but narrower; likely a clarification/refinement slice unless deeper overlap appears

- [x] investigate `psi.ai.conversation` ↔ `psi.ai.providers.anthropic`
  - priority: highest
  - why now: strongest actionable Gordian finding; hidden coupling appears in both conceptual and change lenses
  - question: what provider-neutral conversation/content abstraction is currently implicit between general conversation modeling and Anthropic-specific shaping?
  - commands:
    - `bb gordian explain-pair psi.ai.conversation psi.ai.providers.anthropic`
    - `bb gordian explain psi.ai.conversation`
    - `bb gordian explain psi.ai.providers.anthropic`
  - current findings:
    - Gordian verdict: likely missing abstraction (`conceptual=0.24`, `change=0.33`, `co-changes=6`)
    - `psi.ai.conversation` currently owns canonical conversation/message/content structure and only depends on `psi.ai.schemas`
    - `psi.ai.providers.anthropic` does not depend structurally on `psi.ai.conversation` but reinterprets the same concepts (`message`, `text`, `block`, tool-call/thinking content, system prompt blocks) into Anthropic wire format
    - the overlap is not primarily about transport mechanics; it is about provider-neutral content semantics being encoded ad hoc in provider transformation functions
    - the strongest candidate abstraction is a provider-neutral conversation/content projection layer from canonical conversation data to provider-ready intermediate content/message forms
  - provisional verdict:
    - yes, a shared abstraction is likely warranted
    - likely owner family: `psi.ai`
    - likely shape: extract a small provider-neutral projection/normalization namespace rather than moving Anthropic-specific wire details into `psi.ai.conversation`
    - avoid making `psi.ai.conversation` provider-aware; keep it as the canonical data model + lifecycle surface
  - completed slice:
    - extracted provider-neutral canonical content projection helpers to `components/ai/src/psi/ai/content.clj`
    - migrated shared text/block reading helpers out of `psi.ai.providers.openai.content`
    - migrated Anthropic to use shared canonical content readers for user content, assistant block traversal, and tool-result text extraction
    - added focused tests in `components/ai/test/psi/ai/content_test.clj`
  - namespace fit check:
    - `psi.ai.conversation` is not the right home; it currently owns canonical conversation lifecycle/state mutation and should stay provider-agnostic rather than accumulating projection helpers
    - `psi.ai.schemas` is not the right home; it owns schemas/validation, not operational normalization/projection code
    - `psi.ai.providers.openai.content` is too provider-specific; moving shared canonical helpers there would invert dependency direction and make Anthropic conceptually depend on an OpenAI family
    - `psi.agent-session.message-text` has adjacent text-extraction behavior but lives in a higher-level component and is display-oriented; `psi.ai` should not depend upward on `agent-session`
    - provisional conclusion: no existing namespace is a clean fit for the shared provider-neutral projection helpers
  - concrete namespace proposal:
    - candidate namespace: `psi.ai.content`
    - responsibility: provider-neutral helpers for reading/projecting canonical conversation content and blocks into reusable intermediate forms
    - should own:
      - canonical text extraction from `:kind`-based content maps / structured blocks
      - assistant structured content splitting into text blocks, thinking blocks, and tool-call blocks
      - shared normalization of tool-result text / content-to-text coercion where provider-neutral
    - should not own:
      - conversation lifecycle/state mutation (`psi.ai.conversation`)
      - wire-format serialization for Anthropic/OpenAI payloads
      - provider streaming/parsing logic
  - recommended first extraction slice:
    - target only provider-neutral canonical content projection helpers first
    - specifically start with text/block extraction and assistant structured block partitioning now duplicated conceptually between `psi.ai.providers.anthropic` and `psi.ai.providers.openai.content`
    - leave provider-specific wire shaping of thinking/tool-call/system blocks in provider namespaces for the first slice
  - acceptance outcome:
    - `psi.ai.content` was confirmed as the clean namespace for provider-neutral canonical content projection helpers
    - slice 1 API now covers structured block access, block partitioning, text joining, canonical content text extraction, and assistant content partitioning
    - first migrated callers are `psi.ai.providers.openai.content` and `psi.ai.providers.anthropic`
    - follow-on question remains whether any overlap with higher-level display text helpers should be converged later without inverting component dependencies

- [x] investigate background-job family seams
  - priority: high
  - why now: repeated background-job concepts span runtime state, resolver/telemetry reads, shared projections, and UI rendering
  - scope:
    - `psi.agent-session.background-job-runtime`
    - `psi.agent-session.background-jobs`
    - `psi.agent-session.resolvers.telemetry-basics`
    - `psi.app-runtime.background-job-ui`
    - `psi.app-runtime.background-job-view`
    - `psi.app-runtime.background-job-widgets`
  - question: do repeated background-job concepts indicate a missing canonical public model/boundary, or are they the expected result of a clean runtime → projection → rendering split?
  - commands:
    - `bb gordian explain-pair psi.agent-session.background-job-runtime psi.app-runtime.background-job-widgets`
    - `bb gordian explain-pair psi.agent-session.resolvers.telemetry-basics psi.app-runtime.background-job-ui`
    - `bb gordian explain psi.agent-session.background-jobs`
    - `bb gordian explain psi.app-runtime.background-job-ui`
  - current findings:
    - Gordian shows hidden conceptual coupling between runtime/resolver and app-runtime background-job slices, but no change coupling and no direct structural breakage
    - `psi.agent-session.background-jobs` is the stable canonical runtime state owner with no project dependencies and multiple dependents
    - `psi.app-runtime.background-job-view` / `background-job-widgets` are already the canonical projection/render-summary layer for adapter-facing UI state
    - `psi.app-runtime.background-job-ui` is an orchestration/projection installer that currently depends directly on runtime job maps and projection helpers
    - duplication still exists in the public background-job shape: EQL attr projection lives in `psi.agent-session.resolvers.telemetry-basics`, while commands/RPC re-shape the same job maps again for view and transport needs
  - provisional verdict:
    - this mostly looks like the expected runtime → projection → rendering split, not a missing brand-new domain namespace
    - the highest-leverage consolidation is to centralize the canonical public/EQL background-job projection in `psi.agent-session.background-jobs`
    - no rename or new cross-layer namespace appears necessary yet
  - completed slice:
    - moved canonical background-job EQL attr list and runtime↔EQL projection helpers into `psi.agent-session.background-jobs`
    - updated telemetry resolvers and command-layer runtime conversion to use the shared projection helpers
  - next minimal slice:
    - continue consolidating repeated public-shape conversion at the RPC boundary if it still duplicates summary/public field selection after the EQL projection centralization
  - ownership map:
    - runtime state owner: `psi.agent-session.background-jobs`
      - owns canonical job state, status semantics, terminal/non-terminal classification, retention, and canonical public/EQL projection helpers
    - runtime orchestration owner: `psi.agent-session.background-job-runtime`
      - owns workflow-backed reconciliation, terminal-message emission, cancellation orchestration, and runtime-triggered UI refresh hooks
    - public summary/projection owner: `psi.app-runtime.background-job-view`
      - owns adapter-neutral rendered summaries (`job-summary`, `jobs-summary`, `job-detail`, `cancel-job-summary`) over runtime job maps
    - adapter widget/status projection owner: `psi.app-runtime.background-job-widgets`
      - owns widget/status payload projection from background-job view summaries
    - runtime UI installer owner: `psi.app-runtime.background-job-ui`
      - owns writing projected background-job widget/status data into shared extension UI state
    - transport formatting owner: `psi.rpc.session`
      - owns RPC response envelope shaping for list/inspect/cancel ops
  - final verdict:
    - keep RPC transport shaping local; it is transport-envelope work rather than a missing domain/public-model namespace
    - after projection centralization, the remaining duplication is acceptable layering rather than a new extraction target
    - no further namespace extraction is warranted for background jobs right now

- [x] investigate `psi.agent-session` family internal structure
  - priority: medium/high
  - why now: this is the codebase's dominant family and the likeliest place for gradual mesh growth
  - question: do prompt, extensions, mutations, resolvers, runtime, and session slices still form crisp strata, or is the family flattening into one broad mesh?
  - commands:
    - `bb gordian subgraph psi.agent-session`
    - `bb gordian explain psi.agent-session.core`
    - `bb gordian explain psi.agent-session.context`
    - `bb gordian explain psi.agent-session.resolvers`
  - current findings:
    - Gordian subgraph output is noisy but points consistently at `psi.agent-session.context` as the widest aggregation point and at `core`, `commands`, `bootstrap`, and `mutations*` as high-reach peripheral hubs
    - `psi.agent-session.core` behaves like a façade/peripheral API surface over narrower slices (`context`, `prompt-control`, `session-lifecycle`, `session-settings`, `compaction-runtime`, `introspection`)
    - `psi.agent-session.context` is the dominant composition root; it owns resolver/mutation registration and context assembly, and is appropriately unstable/high-Ce for that role
    - `psi.agent-session.resolvers` is a shared integration surface bundling discovery/extensions/services/session/telemetry plus cross-component registrations; it looks like an intentional aggregate, but one that should stay narrow and registration-focused
    - many Gordian "remove dependency" suggestions inside the family appear to be false positives caused by coordination namespaces whose edges are justified by architectural role rather than by shared vocabulary
  - family map:
    - façade/API surface:
      - `psi.agent-session.core`
      - `psi.agent-session.commands`
      - `psi.agent-session.bootstrap`
    - composition roots / assembly:
      - `psi.agent-session.context`
      - `psi.agent-session.resolvers`
      - `psi.agent-session.mutations`
    - canonical state + persistence foundation:
      - `psi.agent-session.session-state`
      - `psi.agent-session.persistence`
      - `psi.agent-session.session`
      - `psi.agent-session.state-accessors`
      - `psi.agent-session.statechart`
      - `psi.agent-session.turn-statechart`
    - dispatch coordination:
      - `psi.agent-session.dispatch`
      - `psi.agent-session.dispatch-effects`
      - `psi.agent-session.dispatch-handlers*`
      - `psi.agent-session.dispatch-schema`
    - prompt/tool execution:
      - `psi.agent-session.prompt-*`
      - `psi.agent-session.tool-*`
      - `psi.agent-session.conversation`
      - `psi.agent-session.system-prompt`
      - `psi.agent-session.message-text`
    - extension/service/workflow integration:
      - `psi.agent-session.extensions*`
      - `psi.agent-session.extension-runtime`
      - `psi.agent-session.services*`
      - `psi.agent-session.service-protocol*`
      - `psi.agent-session.workflows`
      - `psi.agent-session.background-job*`
    - lifecycle/settings/introspection:
      - `psi.agent-session.session-lifecycle`
      - `psi.agent-session.session-settings`
      - `psi.agent-session.introspection`
      - `psi.agent-session.compaction-runtime`
  - verdict on dependency direction:
    - current dependency direction still broadly matches the intended architecture: façades and assembly layers depend inward on state/dispatch/foundation layers, and adapters/app-runtime sit outside the family
    - `context` is intentionally a composition root rather than a reusable low-level abstraction; its high instability is expected
    - `core` is intentionally a façade rather than a pure foundation namespace; its high reach is expected but should remain thin
  - overloaded / façade-like namespaces:
    - intentionally façade-like: `psi.agent-session.core`
    - intentionally assembly-heavy: `psi.agent-session.context`
    - watchlist for gradual mesh growth: `psi.agent-session.resolvers`, `psi.agent-session.commands`, `psi.agent-session.mutations`
  - highest-leverage thinning candidate:
    - `psi.agent-session.commands`
    - reason: it is a high-reach peripheral hub mixing multiple command families; further extraction by command family would likely reduce surface area without disturbing core state/dispatch architecture
  - final verdict:
    - no urgent structural breakage inside `psi.agent-session`
    - the family still forms intelligible strata
    - the best next structural improvement, if chosen, is thinning peripheral hub namespaces rather than reorganizing the foundation

- [x] investigate adapter-edge family coherence across `psi.rpc`, `psi.app-runtime`, and `psi.tui`
  - priority: medium
  - why now: recent convergence work moved ownership into app-runtime; this check verifies the adapters remain thin
  - question: do backend-owned projections and edge rendering responsibilities remain thin, or are selector/tree/session-summary concepts drifting into parallel abstractions across adapter families?
  - commands:
    - `bb gordian subgraph psi.rpc`
    - `bb gordian subgraph psi.app-runtime`
    - `bb gordian subgraph psi.tui`
    - `bb gordian explain-pair psi.app-runtime.selectors psi.tui.session-selector`
    - `bb gordian explain-pair psi.app-runtime.context-summary psi.rpc.session.command-tree`
  - current findings:
    - `psi.rpc` remains a high-reach peripheral family, but the strongest cross-family signal is inside RPC itself: `psi.rpc.session.commands` ↔ `psi.rpc.session.frontend-actions` shows hidden coupling in 2 lenses and is the best local extraction candidate
    - `psi.app-runtime` remains the semantic projection owner; Gordian still sees conceptual overlap with TUI selector/tree/widget rendering, but the overlap appears to be expected shared vocabulary rather than duplicated ownership
    - `psi.tui` is cleanly outbound-only with no incoming project edges; it behaves like a leaf rendering adapter family
    - `psi.app-runtime.selectors` ↔ `psi.tui.session-selector` and `psi.app-runtime.context-summary` ↔ `psi.rpc.session.command-tree` are conceptually close, but neither pair currently indicates change coupling or a missing new shared namespace
  - responsibility map:
    - `psi.rpc`
      - owns transport, request routing, RPC response envelopes, and session-scoped command handling
      - should stay adapter/transport-facing rather than becoming the owner of shared semantic projection models
    - `psi.app-runtime`
      - owns canonical shared projection semantics for context snapshots, tree/session summaries, selectors, extension UI snapshots, background-job widgets, and transcript reconstruction
      - is the correct home for adapter-neutral UI/data projections reused across frontends
    - `psi.tui`
      - owns rendering, local interaction mechanics, terminal protocol details, and adapter-local state transitions
      - should consume app-runtime/shared projections rather than reconstructing backend semantics
  - duplicated concept read:
    - selector/tree/session-summary concepts still appear in all three families, but most of the duplication is naming/vocabulary rather than duplicated ownership
    - the one strongest local duplication signal is within RPC command handling, especially `psi.rpc.session.commands` and `psi.rpc.session.frontend-actions`
    - no evidence currently justifies extracting a new cross-family selector/tree namespace beyond the existing `psi.app-runtime` projection layer
  - final verdict:
    - current adapter-edge layering is stable enough to leave alone
    - `app-runtime` remains the right semantic center, `rpc` the right transport edge, and `tui` the right render edge
    - the best next slice, if chosen, is to thin the RPC edge internally rather than restructure the cross-family layering
  - minimal next slice:
    - investigate and possibly extract the shared action/result handling seam between `psi.rpc.session.commands` and `psi.rpc.session.frontend-actions`

- [x] investigate graph/introspection ownership
  - priority: medium/low
  - why now: strong conceptual overlap exists, but current evidence suggests a clarification slice before a structural move
  - scope:
    - `psi.graph.analysis`
    - `psi.graph.core`
    - `psi.introspection.graph`
    - `psi.introspection.resolvers`
  - question: should graph analysis and introspection share an extracted concept namespace, add a more explicit dependency edge, or remain separate with clarified ownership?
  - commands:
    - `bb gordian explain-pair psi.graph.analysis psi.introspection.graph`
    - `bb gordian explain-pair psi.graph.core psi.introspection.graph`
    - `bb gordian explain psi.graph.analysis`
    - `bb gordian explain psi.introspection.graph`
  - current findings:
    - there is very strong conceptual overlap between `psi.graph.analysis` and `psi.introspection.graph`, and moderate overlap between `psi.graph.core` and `psi.introspection.graph`
    - inspection shows `psi.graph.analysis` and `psi.introspection.graph` now duplicate the same capability-graph derivation logic almost verbatim
    - current call sites are split:
      - `psi.introspection.resolvers`, `psi.memory.runtime`, and `psi.agent-session.resolvers` use `psi.introspection.graph`
      - `psi.introspection.core` also uses `psi.introspection.graph`
      - `psi.graph.core` remains a stale simplified compatibility namespace that is not the active owner of current capability-graph derivation semantics
    - this is no longer just a conceptual boundary question; it is concrete duplicate implementation
  - ownership decision:
    - graph derivation should live in `psi.graph.analysis`
    - introspection should consume graph derivation, not duplicate it
    - `psi.introspection.graph` should not remain a second implementation of the same capability-graph logic
  - extracted/shared namespace decision:
    - yes, the shared namespace already effectively exists: `psi.graph.analysis`
    - no new namespace is needed
    - the right move is convergence onto `psi.graph.analysis`, followed by retirement or redirection of duplicated `psi.introspection.graph` logic
  - conceptual boundary sentence:
    - `psi.graph.analysis` owns provider-neutral/query-neutral capability-graph derivation; `psi.introspection` owns exposing that derived graph through introspection surfaces.
  - minimal next slice:
    - migrate active consumers from `psi.introspection.graph` to `psi.graph.analysis`
    - then retire the duplicated implementation in `psi.introspection.graph` or reduce it to a compatibility wrapper if needed

Guardrails for follow-on commits:
- keep behavior stable
- keep tests green
- do not introduce cycles
- prefer thinning façades over adding new indirection

## Compatibility scaffold removal

Goal:
- remove remaining internal backward-compatibility scaffolding now that canonical runtime shapes are established
- prioritize internal migration bridges before external-provider compatibility

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
- session directory invariant
  - [x] require runtime sessions to carry explicit `:worktree-path`
  - [x] remove runtime fallback from session worktree to context `:cwd`
  - [x] remove persisted-session fallback from legacy header `:cwd` to `:worktree-path`
  - [x] switch canonical helper usage to `session-worktree-path-in`
  - [~] collapse persisted header shape so `:worktree-path` is authoritative and duplicated session `:cwd` is retired where possible
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
- request preparation now owns canonical prompt expansion metadata (`:prepared-request/input-expansion`) and dispatch-visible prompt memory recovery.
- effective system prompt assembly in request preparation now composes:
  - base system prompt
  - optional developer layer
  - prompt contributions
- agent profile prompts now flow as explicit developer layers rather than being merged into base system prompts.
- bootstrap no longer creates a fallback developer layer mirroring the base system prompt.
- non-fork `agent` tool runs can now seed child sessions with a synthetic skill prelude before execution starts.

Next active slices:
1. refine cache-breakpoint shaping for agent skill-prelude flows so reusable prelude content is separated from the variable tail with the intended 2-breakpoint shape
2. simplify or remove remaining prompt-path seams, comments, and test hooks once they are no longer needed
3. consider whether agent skill-prelude/source metadata should be exposed through session introspection for debugging

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
- if the current synthetic prelude shape remains, document it as the canonical child-session seeding contract for agent tool runs

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
