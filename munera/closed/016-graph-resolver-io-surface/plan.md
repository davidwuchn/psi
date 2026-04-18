# Plan: Graph Resolver I/O Surface

## Approach

Extend `psi.graph.analysis` with pure derivation functions, then wire them
into `query-graph-bridge` and a new `resolver-detail` resolver. All derived
from the live `(session-resolver-surface)` — no new data capture.

## Steps

### 1. Add derivation fns to `psi.graph.analysis`

- `filter-psi-output` — strip internal seed keys (:psi/agent-session-ctx etc)
  from input/output vectors; keep all :psi.* attrs; preserve join map structure
- `derive-resolver-index` — map over resolver-ops, apply filter, return
  `[{:sym :input :output}]` sorted by sym
- `derive-attr-index` — invert resolver-index; for each attr produced, record
  {:produced-by [syms] :reachable-via {join-key [child-attrs]}} where
  reachable-via captures the join path when the attr appears inside a join map

### 2. Extend `query-graph-bridge` output

Add to `::pco/output` and resolver body:
- `:psi.graph/resolver-index`  — full [{:psi.resolver/sym :psi.resolver/input :psi.resolver/output}]
- `:psi.graph/attr-index`      — {attr {:psi.attr/produced-by [...] :psi.attr/reachable-via {...}}}

### 3. Add `resolver-detail` resolver to `resolvers.discovery`

- input:  `[:psi/agent-session-ctx :psi.resolver/sym]`
- output: `[:psi.resolver/sym :psi.resolver/input :psi.resolver/output]`
- looks up sym in resolver-index; returns nil attrs if not found
- add to `discovery-resolvers/resolvers` vec

### 4. Tests

- `psi.graph.analysis-test` (new or extend): unit tests for
  filter-psi-output, derive-resolver-index, derive-attr-index
- `psi.agent-session.eql-introspection-test` (extend): integration tests
  querying :psi.graph/resolver-index, :psi.graph/attr-index,
  and resolver-detail by sym

### 5. Update system prompt

- Add resolver-index/attr-index/resolver-detail to both lambda-graph-discovery
  and prose-graph-discovery so agents know the surface exists and how to use it
- Remove hardcoded `:psi.session-info/id (not :psi.session-info/session-id)` hint
  — now discoverable via resolver-index
- Keep child-sessions query example; replace attr hint with pointer to resolver-index

## Order

1 → 2 → 3 → 4 → 5

Steps 1-2 are pure/testable independently. Step 3 depends on 1. Step 5 last.
