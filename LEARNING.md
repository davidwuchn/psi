# Learning

---

## 2026-03-17 - Anthropic replay failures can come from empty persisted assistant turns, not just from role alternation or tool pairing (commit `8e5da2d`)

### λ Replayed conversation builders should skip structurally empty assistant turns instead of faithfully serializing them

The live Anthropic request capture showed a failure shape that higher-level request diagnostics did not flag: an assistant history entry with no text, no thinking blocks, and no tool calls was being rebuilt as `{:role "assistant" :content [{:type "text" :text ""}]}`. That shape preserves transcript structure mechanically, but it violates Anthropic's wire contract. The reusable rule is:
- when rebuilding provider history from persisted transcript messages
- treat structurally empty assistant turns as absent history, not as empty text blocks
- validate message meaning, not just role alternation

### λ Provider capture is most useful when it is inspected at the exact failing turn, not only through aggregate request-shape diagnostics

The existing request-shape diagnostics correctly reported valid role alternation, matching tool_use/tool_result counts, and no empty top-level message content, yet the actual Anthropic request still failed. The missing signal lived inside a nested content block. The practical lesson is:
- aggregate diagnostics are good for screening common structural faults
- provider request capture is necessary to catch invalid nested wire shapes
- once capture exists, inspect the failing turn before generalizing from summary metrics

### λ OAuth and non-OAuth provider modes should share feature-beta rules unless the provider explicitly documents a divergence

The first Anthropic fix exposed a second lesson: OAuth requests had drifted into a separate beta-composition path where thinking-related beta requirements were easier to omit accidentally. The safer default is:
- derive feature betas from emitted request capabilities like thinking or prompt caching
- compose auth-mode betas on top of that
- avoid making OAuth a separate behavioral branch for feature headers unless the provider protocol requires it

## 2026-03-16 - Emacs rehydrate consumers must replay canonical agent messages, not display projections (commit `98fff62`)

### λ When one backend event serves multiple frontends, its payload must stay at the domain layer rather than at a renderer-specific projection layer

The Anthropic failure survived until the Emacs path was checked because `session/rehydrated` had quietly drifted into carrying TUI-style display rows (`:role` + `:text`) instead of canonical agent-history messages (`:role` + `:content`). Emacs was not wrong; it was faithfully consuming the event contract it was given. The reusable rule is:
- transition/rebuild events should carry domain data
- each frontend should project that data into its own transcript/widget form locally
- display-shaped payloads in shared transition events create hidden coupling and cross-surface regressions

### λ A frontend local echo is safe only if the canonical resumed/rehydrated lifecycle fully owns the final transcript

Tracing `/new` in Emacs showed a useful pattern:
- local user echo happens immediately
- command-result may append temporary assistant feedback
- `session/resumed` clears stale transcript state
- `session/rehydrated` rebuilds the durable transcript

That means local frontend affordances are fine as long as the transition lifecycle is authoritative and explicitly clears/rebuilds afterward. The dangerous case is not temporary UI duplication; it is failing to converge back onto the backend-owned canonical transcript.

### λ Test runners should load new regression files explicitly, or green runs can hide missing coverage

The first Emacs regression pass looked green because `bb emacs:test` only loaded the historical test entrypoints. The new regression files existed but were not part of the task, so the passing suite overstated protection. The reusable lesson is simple: when adding standalone test files, update the canonical task/runner immediately and verify the test count changes in the expected direction.

## 2026-03-16 - Anthropic prompt-cache directives must be coupled to their beta header, and provider errors must preserve wire diagnostics (commit `5f6ec96`)

### λ Emitting Anthropic `cache_control` without the matching beta turns a request-shape refactor into a provider-wide outage

The breakage was not in the cache-control field shape itself; it was in the missing protocol companion. Once prompt caching became session-default policy, even a trivial Anthropic turn inherited `cache_control`, so omitting the prompt-caching beta converted an optimization feature into a hard failure for the whole provider. The reusable rule is:
- if a provider field is feature-gated
- the code path that emits the field should also own the feature-header decision
- do not rely on distant callers to remember the protocol coupling

### λ Prompt-caching detection is better derived from structural prompt units than from session flags alone

The useful boundary was not "did the session enable caching?" but "does this concrete request contain any prompt units with cache directives?" Detecting prompt caching from system prompt blocks, tool definitions, and message blocks keeps the header logic aligned with the actual wire payload. That avoids both false positives and future drift when more request units learn to carry `cache_control`.

### λ Generic provider exceptions erase the debugging signal users need most

The observed `ψ: [error] Error` symptom came from preserving the exception label while dropping the meaningful provider response. For provider integrations, the stable error policy is:
- parse the provider body first
- prefer provider-supplied message text
- append transport metadata like status and request id
- only fall back to generic exception text when the wire gives nothing better

That keeps user-visible failures actionable and makes later API-error introspection far more useful.

## 2026-03-16 - Explicit session-targeting slash commands must emit the same canonical rehydrate events as selector-driven flows (commit `7383729`)

### λ Command wrappers for domain transitions should not bypass the transition event contract

The Emacs bug persisted because explicit `/resume <path>` and `/tree <id>` went through backend `command` handling but did not reliably drive the same canonical `session/resumed` + `session/rehydrated` lifecycle as selector-driven resume/tree flows. The stable rule is:
- if a command performs a real session transition
- it should emit the same domain events as the direct/session-op path for that transition
- command acknowledgement alone is not a sufficient frontend contract

That keeps frontends from having to special-case "selector path" versus "explicit argument path" for the same user intent.

### λ Frontend transcript clearing should key off canonical transition events, not transport-specific success callbacks

The robust boundary is:
- send command/request
- wait for `session/resumed` to clear stale transcript/render state
- wait for `session/rehydrated` to replay the canonical messages

When clearing depends on callback-local success handling instead, widget actions and explicit slash forms can drift from picker-driven flows even though they represent the same session change.

### λ Session switches need a visual hard-redraw policy in TUIs even when state replacement is already correct

The TUI `/tree` bug was not that session state failed to switch; the state had already changed. The missing piece was a one-shot hard redraw so stale terminal rows disappeared when the new render was shorter than the previous one. The reusable lesson is that terminal UIs need both:
- correct state replacement
- explicit screen-clearing policy for view contractions after large transcript changes

## 2026-03-16 - Direct provider replay is the fastest way to separate abstraction bugs from provider instability in multi-provider reasoning flows

### λ If the exact captured wire request still fails under replay, the conversation abstraction is probably not the root cause

The strongest discriminator in the Anthropic failure investigation was replaying the captured Anthropic `/v1/messages` body directly with the stored OAuth token. Once the same extracted request body produced intermittent `200` and `500` responses, the current bug stopped looking like a deterministic conversation-rebuild defect. A replay that bypasses the abstraction entirely is a high-value test: if it still fails, focus on provider/runtime behavior before blaming message/history normalization.

### λ Cross-provider reasoning should stay transient unless explicitly promoted into conversation history

OpenAI reasoning/thinking deltas and Anthropic thinking blocks can share one transient progress channel (`:thinking-delta`) without becoming part of persisted conversation state. The important guardrail is that final assistant messages and rebuilt provider conversations must be assembled only from durable text/tool/error content, not from streamed reasoning buffers. The useful regression shape is therefore cross-provider:
- emit OpenAI-style thinking deltas
- persist the assistant turn
- rebuild an Anthropic request
- prove the prior reasoning text is absent from the later request body

### λ The unstable Anthropic axis was current-request thinking with prior tool history, not prompt caching alone

Live bisect replay showed three useful facts:
- removing current-request `:thinking` made the replay succeed in that run
- removing prior tool history also made the replay succeed
- removing `cache_control` did not eliminate the intermittent `500`

That does not prove a full Anthropic root cause, but it narrows the next move: treat `thinking + prior tool history` as the primary risk combination, and treat prompt caching as orthogonal until new evidence says otherwise.

## 2026-03-16 - Anthropic prompt caching should isolate volatile runtime metadata into a separate uncached tail (commit `f28c93f`)

### λ Visible runtime metadata and cacheable prompt identity should not share the same Anthropic cache unit

The captured Anthropic request made the real failure mode obvious: the system prompt contained a volatile `Current date and time:` line, so any rebuild of the prompt at a later second would change the bytes of the cached system block and defeat prompt-cache reuse. The right fix was not to remove runtime metadata, but to split the system prompt into two ordered units:
- stable prompt body
- runtime metadata tail

For Anthropic, only the stable body should carry `{:cache-control {:type :ephemeral}}`; the runtime tail should stay visible but uncached.

### λ The useful split point is after extension prompt contributions, not before them

Extension prompt contributions are part of the durable instruction surface and belong with the stable cached body. The volatile cutoff is after those contributions and before runtime metadata like current time and cwd/worktree lines. That preserves the existing prompt reading order while keeping cache identity tied to the instruction content rather than to session-clock noise.

### λ Shared prompt assembly should own volatile-tail detection so executor policy stays structural

Once the prompt was split in `system_prompt.clj`, the executor no longer needed to know how to parse runtime metadata lines itself. It could just ask for Anthropic system prompt blocks and apply cache policy structurally. The reusable lesson is to keep provider/executor cache logic working over explicit shared prompt units rather than over ad hoc string surgery at the call site.

## 2026-03-16 - Provider simplification holds better when the file is split by runtime phase, not by arbitrary helper extraction (commit `d06c475`)

### λ The useful provider boundaries are message shaping, request assembly, stream-event translation, and terminal accounting

The Anthropic provider became easier to reason about once the extracted helpers matched the actual runtime phases of the provider rather than miscellaneous local subexpressions. The stable phase boundaries were:
- message transformation into provider wire format
- request/header/body assembly
- SSE content-block event translation
- usage accumulation and API-error extraction

That shape reduced inline branching in the top-level functions without obscuring the streaming contract.

### λ Simplification is stronger when repeated wire literals become named protocol constants

Pulling values like the Anthropic API version and beta header fragments into named constants did more than shorten lines: it made the protocol surface visible in one place and removed repeated string decisions from request-building branches. For provider code, repeated protocol literals are often hidden control flow and worth naming even when they are used only a few times.

### λ Provider refactors should preserve the observable event contract while moving complexity behind phase helpers

The right simplification target was not to invent a new abstraction over provider streaming, but to keep `transform-messages`, `build-request`, and `stream-anthropic` as the public shape while pushing repetitive detail behind small helpers. That keeps provider-focused tests meaningful: they still verify the same observable request/event behavior while the internal branching becomes easier to inspect and change.
