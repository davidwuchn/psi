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

## 2026-03-17 - Canonical root-state migrations are safest when code first converges on shared path helpers before removing old shapes completely (commit `3097239`)

### λ A single mutable-root refactor goes smoother when the first stable API is path-based access, not immediate atom elimination

The useful pivot was not "delete every atom-shaped field at once". The stable move was to introduce one canonical `:state*` root and then expose small shared helpers from `core.clj`:
- read state by path
- assoc/update state by path
- expose named path lookup through one function

That gave `runtime.clj`, `executor.clj`, and `resolvers.clj` one migration surface and avoided pushing raw nested-path knowledge into every caller.

### λ Runtime handles should remain outside the canonical mutable root unless they are true queryable state

The simplification target is one canonical mutable state root, not one storage location for every runtime object. Things like agent-core contexts, extension registries, UI integration hooks, OAuth store integrations, and nREPL/server handles are better treated as runtime handles. Trying to force them into the canonical state tree would mix queryable domain state with opaque side-effecting process objects.

### λ Resolver migration is an especially good proving ground for state unification

Resolvers touch almost every observable session/runtime field but mostly read rather than mutate. That makes them a strong convergence surface:
- if the canonical root can satisfy resolver reads cleanly
- the external query contract stays stable
- and many stale internal storage assumptions are exposed quickly

In this pass, moving resolver reads for session data, journal state, provider captures, tool telemetry, turn context, and background jobs was a high-signal way to validate the new root-state model without changing the public EQL surface.

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

## 2026-03-17 - Canonical root-state migration works best when runtime-visible mutable state and runtime handles are split intentionally (commit `3097239`)

### λ A single mutable root becomes tractable when the migration boundary is runtime-visible state, not every object in the runtime

The successful convergence point was not “put absolutely everything in one atom”. The durable simplification came from moving queryable/runtime-visible mutable state into one canonical root while leaving effectful handles outside it. The reusable rule is:
- canonical root owns state that should be inspected, queried, or updated atomically
- runtime handles own opaque integrations like provider clients, queues, registries, and live servers
- do not force non-serializable control objects into the same state model just to satisfy a slogan

### λ Root-state migrations are safer when old atom-oriented APIs are preserved as adapters during convergence

The agent-session refactor did not require every consumer to change shape at once. Instead, path-based helpers in `core.clj` became the new source of truth and atom-like views were retained where callers still expected them. The practical lesson is:
- introduce canonical path helpers first
- migrate core read/write paths onto those helpers
- keep adapters for legacy atom-shaped surfaces until the surrounding code catches up

That preserves behavior while letting the architecture change underneath.

### λ Specs become more useful when they name the intended state boundary before the code fully converges on it

Adding `spec/system-context-unification.allium` first gave the implementation pass a stable target: one canonical mutable root for runtime-visible state, compatibility projections allowed during migration, runtime handles explicitly out of scope. The reusable lesson is:
- if the change is architectural, capture the target boundary in spec first
- then use code changes to converge on that target incrementally
- use the spec to decide what may stay as a compatibility shim and what must become canonical

## 2026-03-17 - Anthropic replay failures can come from empty persisted assistant turns, not just from role alternation or tool pairing (commit `8e5da2d`)

### λ Replayed conversation builders should skip structurally empty assistant turns instead of faithfully serializing them

The live Anthropic request capture showed a failure shape that higher-level request diagnostics did not flag: an assistant history entry with no text, no thinking blocks, and no tool calls was being rebuilt as `{:role "assistant" :content [{:type "text" :text ""}]}`. That shape preserves transcript structure mechanically, but it violates Anthropic's wire contract. The reusable rule is:
- when rebuilding provider history from persisted transcript messages
- treat structurally empty assistant turns as absent history, not as empty text blocks
- validate message meaning, not just role alternation
