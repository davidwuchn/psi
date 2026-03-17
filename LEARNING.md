# Learning

---

## 2026-03-17 - Error capture is only useful when provider diagnostics survive normalization and retention pressure (commit `0bc6fb5`)

### λ Provider adapters should preserve the raw error payload even when they also emit a normalized user-facing error string

The reproduced Anthropic 400 still surfaced to the session as a compact `Error (status 400) [request-id ...]` string, which is good for UI display but too lossy for root-cause work. The reusable rule is:
- keep the normalized summary string for user-visible flow
- also retain raw headers, raw body text, and parsed structured body when available
- let later diagnostics decide how much detail to inspect rather than forcing the adapter to collapse the evidence up front

### λ Request-id extraction should follow the canonical emitted error shape, not only historical transport formatting

Once provider adapters started emitting normalized `... [request-id req_xxx]` suffixes, the old request-id parser that expected raw header-map text stopped seeing the identifier even though the id was still present. The practical lesson is:
- when an error surface is normalized intentionally
- downstream diagnostics must parse the normalized form as a first-class contract
- compatibility with older raw formats can remain as fallback, but the canonical emitted shape should lead

### λ Provider capture tails must be sized for live debugging, not just for nominal streaming volume

The live session had already rotated far enough that the earlier Anthropic failure body was no longer recoverable from the current visible reply tail. That means retention limits are part of the debugging surface, not just a memory footnote. The reusable rule is:
- size provider request/reply capture history for the realistic investigation loop
- assume several later turns may happen before someone inspects the failing provider event
- if a captured failure falls off the tail too quickly, the system effectively did not remember it

## 2026-03-17 - Split UI-specific session creation from shared runtime bootstrap in entrypoint code (commit `2368583`)

### λ Context creation and runtime bootstrap are different responsibilities even when they happen back-to-back

`main.clj` had one helper that both created the session context and bootstrapped the live runtime. That made `bootstrap-runtime-session!` appear transport-neutral while it still owned `:ui-type` and event-queue concerns. The better shape is:
- context creation decides UI-specific inputs and establishes the first session
- bootstrap enriches an existing context with prompts, tools, extensions, memory sync, and startup rehydrate

When those stages are split, each runtime entrypoint can choose its UI explicitly while still reusing one bootstrap path.

### λ A shared helper is simpler when callers provide the already-specialized context instead of passing specialization knobs through it

The previous helper accepted `:ui-type`, `:event-queue`, and session config alongside bootstrap concerns. That widened the helper's responsibility and made UI choice an ambient option inside shared setup. A cleaner rule is:
- if a concern changes how the context is constructed
- make callers decide it before invoking the shared bootstrap
- keep the shared bootstrap operating on an already-created `ctx`

That reduces option threading and makes future UI additions cheaper.

### λ Entry-point refactors are safer when the split follows the existing runtime order rather than inventing a new abstraction layer

This change stayed small because it preserved the existing sequence:
- resolve model
- create context
- bootstrap runtime
- run UI/transport loop

The refactor only cut the boundary between steps two and three. The useful lesson is that entrypoint simplification often works best by exposing the real phase boundary that already exists, rather than by introducing a larger orchestration abstraction first.

## 2026-03-17 - Anthropic replay failures can come from empty persisted assistant turns, not just from role alternation or tool pairing (commit `8e5da2d`)

### λ Replayed conversation builders should skip structurally empty assistant turns instead of faithfully serializing them

The live Anthropic request capture showed a failure shape that higher-level request diagnostics did not flag: an assistant history entry with no text, no thinking blocks, and no tool calls was being rebuilt as `{:role "assistant" :content [{:type "text" :text ""}]}`. That shape preserves transcript structure mechanically, but it violates Anthropic's wire contract. The reusable rule is:
- when rebuilding provider history from persisted transcript messages
- treat structurally empty assistant turns as absent history, not as empty text blocks
- validate message meaning, not just role alternation
