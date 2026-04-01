# Plan: Remove implicit initial session creation from `create-context`

## Problem

`psi.agent-session.core/create-context` currently creates a live session as a side effect:

- builds context via `create-context*`
- immediately calls `lifecycle/new-session-in!`
- returns `[ctx session-id]`

That makes context construction and session lifecycle inseparable, and it allows boot paths to accidentally create multiple startup sessions.

Concrete consequence already observed:

- `app_runtime/create-runtime-session-context` calls `session/create-context`
- then calls `session/new-session-in!` again
- Emacs RPC startup therefore begins with more than one live session in the backend context

## Goal

Make session creation explicit.

Target shape:

- `create-context` → returns context only
- `new-session-in!` → explicit lifecycle operation owned by callers
- startup paths create exactly one session, intentionally

## Design

### API split

Change:

- `psi.agent-session.core/create-context`
  - from: returns `[ctx session-id]`
  - to: returns `ctx`

Optional migration helper during rollout:

- `psi.agent-session.core/create-context-with-session`
  - returns `[ctx session-id]`
  - implements old behavior for transitional callers/tests

### Ownership rule

Only runtime/test code that needs a session should create one.

Examples:

- app runtime boot creates one initial session explicitly
- RPC/TUI/CLI startup use that explicit session
- tests that only need a context stop paying for a hidden session
- tests that need a session create one deliberately

## Steps

### Step 1 — Change `create-context` semantics

File:
- `components/agent-session/src/psi/agent_session/core.clj`

Changes:
- update `create-context` so it returns only `(create-context* opts)`
- remove implicit `(lifecycle/new-session-in! ctx nil {})`
- update docstring to say context-only
- optionally add `create-context-with-session` with the old `[ctx session-id]` behavior as a migration bridge

Target result:
- context construction no longer mutates session registry

### Step 2 — Update runtime boot to create one explicit initial session

Primary file:
- `components/app-runtime/src/psi/app_runtime.clj`

Changes:
- in `create-runtime-session-context`, change destructuring from `[ctx _]` to `ctx`
- keep the explicit `session/new-session-in!` there
- verify this becomes the single source of initial session creation for runtime boot

Expected result:
- CLI/TUI/RPC boot creates exactly one live session

### Step 3 — Migrate all call sites of `create-context`

Search targets:
- `session/create-context`
- `core/create-context`

Changes:
- callers expecting `[ctx session-id]` must be rewritten
- if a caller needs only context, use returned `ctx`
- if a caller needs a live session, explicitly call `new-session-in!`
- if using the migration bridge, temporarily swap to `create-context-with-session`

Likely affected areas:
- runtime code
- RPC tests
- agent-session tests
- app-runtime tests
- helper/test-support namespaces

### Step 4 — Normalize tests around explicit lifecycle

Goal:
- make tests reflect the intended model: context first, session second

Patterns:
- context-only tests:
  - `(let [ctx (session/create-context ...)] ...)`
- session behavior tests:
  - `(let [ctx (session/create-context ...)
           sd (session/new-session-in! ctx nil {})
           sid (:session-id sd)] ...)`

If needed during migration:
- use `create-context-with-session` to reduce churn, then remove later

### Step 5 — Add regression coverage for startup session count

Add focused tests that prove startup creates one session.

Suggested coverage:
- app-runtime boot path:
  - create runtime session context
  - assert `(count (ss/list-context-sessions-in ctx)) == 1`
- RPC startup path:
  - after bootstrap/handshake context snapshot, assert only one live session is exposed
- optionally assert `/new` increases count from 1 to 2

This protects the exact startup bug reported in Emacs.

### Step 6 — Verify adapter behavior

Manual/automated verification:

- Emacs RPC startup
  - session tree shows one session on first connect
- TUI startup
  - one session initially
  - `/new` adds a second
- CLI startup
  - works unchanged from user perspective
- resume/fork flows
  - unaffected except for explicit initial session ownership

### Step 7 — Remove migration bridge (if introduced)

After callers are migrated and tests are green:
- remove `create-context-with-session`
- keep only explicit context creation + explicit session lifecycle

## Risks / watchpoints

### Hidden assumption: every context already has a session

Some code may call helpers like:
- `default-session-id-in`
- `list-context-sessions-in` then take first
- session query/prompt helpers without first creating a session

This is acceptable only if those paths always run after explicit session creation.
Tests that instantiate a bare context may need updates.

### Resolver/support code

Some resolver/test helpers may rely on `create-context` having pre-populated state.
Those must either:
- create a session explicitly, or
- be rewritten to operate on pure context

## Done criteria

- [ ] `create-context` no longer creates a session implicitly
- [ ] runtime startup creates exactly one live session
- [ ] all callers updated to explicit session creation semantics
- [ ] regression test protects startup session count
- [ ] Emacs UI shows one session on fresh startup

## Commit strategy

1. change `create-context` semantics (+ optional bridge)
2. update app runtime boot path
3. migrate failing callers/tests
4. add regression coverage for startup session count
5. verify Emacs/TUI/RPC startup behavior

### Candidate commit message

`⚒ Δ Remove implicit initial session from create-context`

# Plan: Explicit session-id on dispatch events

## Problem

Session-id is implicit — carried as `ctx[:target-session-id]`, read via
`ss/active-session-id-in` inside every handler. Adapters work around this
with `focus-ctx = fn [] (assoc ctx :target-session-id @atom)`.

## Goal

session-id explicit on every dispatch event → ctx is a pure capability bundle.

## Steps

### Step 1 — `dispatch!` propagates session-id from event-data into interceptor context

In `dispatch.clj`:
- `normalize-event` extracts `:session-id` from event-data and lifts it to
  the canonical event map as `:event/session-id`
- `dispatch!` projects `:session-id` onto the interceptor context from the event
- Handler interceptor passes `(:session-id ictx)` alongside ctx to handler-fn
  (handlers receive `[ctx event-data]` — no signature change yet, session-id
  is available on ictx and threaded into ctx for backward compat)

**Approach**: thread `:session-id` through ictx; in handler-interceptor,
before calling handler-fn, `assoc :target-session-id` onto ctx from event.
This makes existing handlers work without change (they still call
`active-session-id-in` which reads `:target-session-id`).

Lint check: ✓

### Step 2 — All `dispatch!` call sites pass explicit `:session-id`

Files to update:
- `core.clj` — prompt-in!, steer-in!, follow-up-in!, abort-in!, etc.
- `session_lifecycle.clj` — new-session, resume, fork
- `executor.clj` — tool recording, agent-end events
- `commands.clj` — set-model, set-thinking-level
- `rpc.clj` — set-model, set-thinking-level, set-session-name, set-auto-compaction
- `dispatch_handlers.clj` — internal dispatch! calls

Session-id source: `(ss/active-session-id-in ctx)` at each call site.

Lint check: ✓

### Step 3 — Remove `focus-ctx` / `target-session-id` from adapters

In `main.clj`:
- CLI `run-session`: replace `(focus-ctx)` with `ctx` + explicit session-id
  threaded through `cmd-opts`
- TUI: same
- RPC: same

`target-session-id` on ctx and `focus-ctx` / `focus-atom` vars removed.

Lint check: ✓

### Step 4 — Remove `active-session-id-in` from `session_state.clj`

- Remove `active-session-id-in` and `with-session-id-in` (or demote to
  internal use only for resolvers/non-dispatch reads)
- Remove `:target-session-id` from ctx construction in `create-context`

Lint check: ✓

### Step 5 — Commit

`⚒ Δ Make session-id explicit on dispatch events`

### Follow-up — RPC request session routing cleanup

Completed:
- removed RPC dynamic request-session binding (`psi.rpc.transport/*request-session-id*`)
- request handler now resolves session-id once per targetable request
- session-id is passed explicitly into RPC handlers/event payload builders
- handshake bootstrap context now uses canonical `context-updated-payload`
- `psi.rpc.events/focused-session-id` removed

## Active — Emacs UI tracking migration (offsets/ranges -> IDs + properties + markers)

### Current progress snapshot

- Status: **Phase 7 complete** (Phases 0–7 done; legacy tracking fields now removed or cache-only)
- Baseline captured: `bb emacs:test` green (346/346)
- Current verification: `bb emacs:test` green (349/349)
- Next focus: optionally remove remaining cache-only marker/range fields if a stricter end-state is desired

### Decision

- Move Emacs UI tracking to **stable IDs + text properties** as primary identity.
- Keep **markers** for moving anchors and efficient local updates.
- Treat absolute offsets as display detail only (not source-of-truth identity).

### Why

- Existing Emacs UI has multiple marker/range drift guard paths and regression tests around boundary collisions.
- Text properties let interactions resolve by identity at point (`psi-region-id`, `psi-region-kind`) rather than transient positions.
- Markers remain useful for append/stream anchors, but property recovery improves robustness when marker state drifts.

### Target outcome

- Robust region identity for assistant/thinking/tool rows/projection/input separator.
- Fewer marker-collision repair branches.
- No behavior change in user-facing commands or backend RPC contract.

### Interface boundary

External behavior unchanged:
- slash commands, tool actions, session actions, RPC payloads

Internal contract shift:
- from `offset/range -> thing`
- to `stable-id <-> thing` with property/marker-backed lookup

### Scope

Primary files:
- `components/emacs-ui/psi.el`
- `components/emacs-ui/psi-globals.el`
- `components/emacs-ui/psi-assistant-render.el`
- `components/emacs-ui/psi-tool-rows.el`
- `components/emacs-ui/psi-compose.el`
- `components/emacs-ui/psi-projection.el`
- `components/emacs-ui/psi-lifecycle.el`
- `components/emacs-ui/test/psi-test.el`

New module:
- `components/emacs-ui/psi-regions.el` (region registry + lookup helpers)

### Phased checklist

#### Phase 0 — Baseline + guardrails
- [x] Run and record baseline: `bb emacs:test`
- [x] Add migration search guard notes:
  - [x] `rg "assistant-range|thinking-range|projection-range|input-separator-marker" components/emacs-ui`
  - [x] `rg "psi-region-id|psi-region-kind" components/emacs-ui`

#### Phase 1 — Introduce region/property layer (no behavior change)
- [x] Add `psi-regions.el` with helpers:
  - [x] `region-key`, `region-register`, `region-bounds`, `region-delete`, `region-annotate`, `region-find-by-property`
- [x] Extend frontend state with region index + active ids:
  - [x] `regions`
  - [x] `active-assistant-id`
  - [x] `active-thinking-id`
- [x] Ensure lifecycle teardown/reset clears region index and markers

#### Phase 2 — Dual-write properties on existing inserts
- [x] Assistant render paths annotate assistant spans with:
  - [x] `psi-region-kind=assistant`
  - [x] `psi-region-id=<assistant-id>`
- [x] Thinking paths annotate thinking spans similarly
- [x] Tool row inserts annotate spans with `tool-row` + `tool-id`
- [x] Projection/separator inserts annotate spans with dedicated kinds/ids

#### Phase 3 — Migrate assistant/thinking lookups to region helpers
- [x] `psi-assistant-render.el` resolves bounds property-first, marker-fallback
- [x] Keep legacy range fields in sync during compatibility period
- [x] Preserve existing streaming/finalize semantics and ordering

#### Phase 4 — Migrate tool rows (highest value)
- [x] `psi-tool-rows.el` row updates resolve by region id first
- [ ] Reduce/remove marker-collision repair branches once stable
- [x] Keep `tool-id` as canonical key
- [x] Tool row assistant-boundary handling now reads assistant start/end through region-backed recovery helpers (no direct state range reads)

#### Phase 5 — Migrate input separator + projection lookups
- [x] `psi-compose.el` separator validity uses property+marker checks
- [x] `psi-projection.el` projection block identity anchored by region id
- [x] Preserve current width-refresh and footer behavior

#### Phase 6 — Verification + regression coverage
- [x] Existing drift regressions remain green
- [x] Add tests for property-first recovery when markers are nil/drifted:
  - [x] assistant span recovery
  - [x] tool row recovery
  - [x] input separator recovery
  - [x] projection identity stability
  - [x] thinking visibility when provider emits reasoning boundaries without textual deltas
  - [x] empty non-slash compose input is blocked locally (prevents backend `request/invalid-params` noise)
- [x] Run full suite: `bb emacs:test`

#### Phase 7 — Decommission legacy range-first paths
- [x] Remove/deprecate direct use of:
  - [x] `assistant-range` (cache only)
  - [x] `thinking-range` (cache only)
  - [x] `projection-range`
  - [x] `input-separator-marker` (cache only)
- [x] Remove compatibility fallback branches once tests pass consistently

### Commit strategy (small, reversible)

1. add region/property helper module + state fields
2. dual-write properties on existing renders
3. migrate assistant/thinking lookups
4. migrate tool-row lookups
5. migrate projection/separator lookups
6. expand tests + verify
7. remove legacy range-first code

### Done criteria

- [x] interactions resolve by region IDs/properties, not raw offsets
- [x] marker drift no longer causes content identity loss
- [x] user-visible behavior unchanged
- [x] emacs test suite green (`bb emacs:test`)
- [x] legacy range-first paths removed or explicitly cache-only

## Planned — True parallel tool execution

## Problem

The executor supports multiple tool calls in a single assistant turn, but
executes them sequentially with `mapv`. This preserves the correct
conversation shape (many tool results → one follow-up assistant reply), but
wastes latency and can unnecessarily lengthen agent turns.

## Goal

Execute independent tool calls concurrently while preserving the current turn
semantics:
- one assistant turn may emit many tool calls
- all tool results are recorded
- all tool results are sent back in the next provider request together
- one follow-up assistant reply is produced for the batch

## Constraints

- Preserve deterministic journal semantics as far as possible.
- Keep provider-facing behavior unchanged:
  - Anthropic: consecutive `tool_result` blocks still collapse into one user message
  - OpenAI: tool messages still appear together in the next request context
- Tool lifecycle telemetry must remain attributable per tool-id.
- Parallel execution must not reorder provider turn boundaries.
- Errors remain per-tool; one failing tool must still yield a recorded tool result.

## Steps

### Step 1 — Separate execution policy from continuation semantics

In `executor.clj`:
- keep `continue-after-tool-use!` owning batch semantics
- extract `run-tool-calls!` helper returning `tool-results` for a vector of tool calls
- preserve current output shape: `{:turn/continuation :turn.continue/next-turn ...}`

### Step 2 — Add bounded parallel execution for tool batches

In `executor.clj`:
- run tool calls concurrently via a bounded executor / futures
- preserve result association by original tool-call id and input order
- make max parallelism explicit and configurable
- default to a conservative limit (e.g. small fixed pool), not unbounded parallelism

### Step 3 — Preserve stable recording order

- Allow execution to complete out of order internally
- Record canonical `toolResult` messages in deterministic batch order
  (assistant-emitted order by content-index / tool-call order)
- Keep lifecycle/progress events attributable to the real tool-id even when
  completion order differs

### Step 4 — Verify runtime effect/tool parity

Review dispatch/runtime boundaries touched by parallel tool execution:
- `:session/tool-run`
- tool lifecycle event recording
- tool output stats
- background workflow/tool job tracking

Ensure no hidden shared mutable state assumes sequential tool execution.

### Step 5 — Add tests

Add/extend tests for:
- multiple tool calls execute concurrently but yield one follow-up assistant turn
- deterministic journal ordering despite out-of-order completion
- per-tool error isolation in a parallel batch
- telemetry correctness (`tool-call-attempts`, lifecycle events, summaries)
- bounded parallelism (no unbounded fan-out)

### Step 6 — Evaluate policy surface

Decide whether parallel tool execution should be:
- always on
- provider/model/session configurable
- disabled for tools that are not concurrency-safe

Likely follow-up: add per-tool or per-session concurrency policy metadata.

### Commit

`⚒ executor: add true parallel tool execution with single-turn continuation`
