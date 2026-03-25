# Plan

## Active — vui branch

### 1. Polish agent widget node tree (small)
Compose full top-line from query data:
- status icon, phase badge, turns, elapsed-s, agent-name, fork tag, task preview
- all fields available in `{:psi.extension.workflow/detail [...]}` result
- replace pre-computed text with composed `:content-path` + `:hstack` nodes
- action line (cont/remove) conditioned on done?/error? — needs
  conditional visibility or always-present muted text

### 2. Merge vui → master
- rebase clean (done once already)
- run full test suite
- merge

### 3. ExtensionInstanceState storage (from master PLAN item 12)
- `:extension-instance-state` path in `:state*`
- get/set operations + EQL resolvers
- steering-mode/follow-up-mode stored there (not session data)

### 4. Retire agent-core from remaining consumers (from master PLAN item 11)
- dispatch-effects still sync to agent-core (RPC resume, session switch, bootstrap)
- core.clj still creates agent-ctx in `create-context`
- migrate each consumer to session-data reads, then remove agent-ctx from session entries

### 5. Configuration scope (from master PLAN item 8)
- config setters need to take scope: session > project > system

### 6. Cache stability (from master PLAN item 5)
- change injected prompt time to session creation time
- add time instant to each request/response
- set cache breakpoints on last three messages

## Deferred widget-spec items
- Per-button in-flight correlation via event payload
- Cross-widget composition
- Conditional widget visibility
- Placement vocabulary extension

## Success shape
- widget-spec system in production use across agent + agent-chain
- agent widget shows live progress (phase/turns/elapsed) without polling
- vui branch merged to master
- ExtensionInstanceState enables clean extension state isolation
