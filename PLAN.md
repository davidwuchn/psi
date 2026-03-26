# Plan

## Completed — remove the temporary ctx current-session bridge

The agent-session refactor is complete.

### Final outcome

- ✓ `:active-session-id` removed from root state atom
- ✓ RPC focus moved to endpoint-local atom
- ✓ TUI focus moved to TUI-local state
- ✓ `:psi.agent-session/context-active-session-id` removed from resolver surface
- ✓ service made explicit-only
- ✓ shared ctx `:current-session-id*` removed
- ✓ core now routes only through explicit `session-id` or scoped `:target-session-id`
- ✓ tests green

## Implemented design

> Core has no shared current session concept.
> RPC/UI layers own focus.
> Core logic uses explicit `session-id` or scoped child-session ctx only.

## Completed steps

### Step 1 — Move RPC focus to endpoint-local atom  ✓
- RPC runtime state owns `focus-session-id*`
- request routing scopes from explicit `:session-id` or RPC-local focus
- lifecycle flows update RPC-local focus

### Step 2 — Move TUI focus to TUI-local state only  ✓
- TUI owns `:focus-session-id`
- tree selector no longer depends on core-projected active-session attr
- session switching updates TUI-local focus directly

### Step 3 — Remove `:psi.agent-session/context-active-session-id` from core resolver surface  ✓
- attr removed from resolver outputs
- graph surface/tests updated

### Step 4 — Remove implicit current-session fallback from service  ✓
- service APIs now require explicit `session-id`
- no fallback to shared ctx current session

### Step 5 — Remove `:current-session-id*` from shared ctx  ✓
- ctx construction no longer includes `:current-session-id*`
- `ss/active-session-id-in` now reads only `:target-session-id`
- lifecycle flows re-scope locally instead of mutating shared ctx focus
- tests/helpers updated to use explicit re-scoping

## Success criteria achieved

- ✓ no `:active-session-id` in root state
- ✓ no `:current-session-id*` in shared ctx
- ✓ RPC owns focus locally
- ✓ TUI owns focus locally
- ✓ core resolvers/service no longer expose or depend on shared current-session state
- ✓ all tests green

## Deferred / next

### ExtensionInstanceState storage
- `:extension-instance-state` path in `:state*`
- get/set operations + EQL resolvers
- steering-mode/follow-up-mode stored there (not session data)

### Retire agent-core from remaining consumers
- dispatch-effects still sync to agent-core (RPC resume, session switch, bootstrap)
- core.clj still creates agent-ctx in `create-context`
- migrate each consumer to session-data reads, then remove agent-ctx from session entries

### Configuration scope
- config setters need to take scope: session > project > system

### Cache stability
- change injected prompt time to session creation time
- add time instant to each request/response
- set cache breakpoints on last three messages
