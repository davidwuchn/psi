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
