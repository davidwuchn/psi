# State

## Now (2026-03-25)

### vui branch — declarative widget-spec system complete

18 commits ahead of master. Core widget-spec pipeline is fully implemented
and spec-compliant. 339 ERT tests, 0 failures.

#### What exists

**Spec** (`spec/emacs-widget-spec.allium`) — full behavioural spec authored
before implementation; all rules now have corresponding code.

**Server**
- `psi.ui.widget-spec` — WidgetSpec + WidgetNode data model, constructors,
  validation, traversal
- `psi.ui.state` — `set-widget-spec!`, `clear-widget-spec!`, `all-widget-specs`,
  `make-extension-ui` exposes `:set-widget-spec` / `:clear-widget-spec`
- `psi.agent-session.mutations` — `psi.ui/set-widget-spec`,
  `psi.ui/clear-widget-spec` mutations
- `psi.agent-session.resolvers` — `:psi.ui/widget-specs` resolver;
  `query-in` extended with optional `extra-entity` arity
- `psi.agent-session.core` — `query-in` extra-entity arity exposed
- `psi.agent-session.rpc` — `query_eql` accepts optional `:entity` EDN param;
  `ui/widget-specs-updated` event emitted on `:widget-specs` change

**Emacs**
- `psi-widget-renderer.el` — pure recursive renderer; all node types
  (text/newline/hstack/vstack/heading/strong/muted/code/success/warning/error/
  button/collapsible/list); lstate (collapsed/in-flight/event-state); faces
- `psi-widget-projection.el` — query, per-spec data fetch (with `:entity`
  param for seeded Pathom queries), lstate sync, render, interaction handlers
  (button activate + collapsible toggle), event subscription dispatch,
  mutation timeout watchdog, global error handler
- `psi-events.el` — handles `ui/widget-specs-updated`; calls
  `psi-widget-projection-handle-event` for every RPC event
- `psi.el` / `psi-lifecycle.el` — `projection-widget-data` slot added

**Extensions**
- `agent-chain` — pushes declarative spec on every `refresh-widget!`
  (static pre-computed content)
- `agent` — pushes declarative spec with `:query` + `:entity` + subscriptions;
  content is live via `{:psi.extension.workflow/detail [...]}` join query
  resolved per tool event via `tool/result` + `tool/update` subscriptions

#### Spec coverage

| Rule | ✓ |
|---|---|
| Widget registration / validation | ✓ |
| Per-spec query resolution (flat + join + entity) | ✓ |
| All node types | ✓ |
| LocalState preserved across requery | ✓ |
| Button activation + in-flight tracking | ✓ |
| Collapsible toggle | ✓ |
| `ui/widget-specs-updated` event | ✓ |
| Event subscriptions → event-state + clear in-flight | ✓ |
| Mutation timeout watchdog | ✓ |
| Global error handler | ✓ |
| Query failure → error handler | ✓ |

#### Open (deferred in spec)
- Per-button in-flight correlation via event payload
- Cross-widget composition
- Conditional widget visibility
- Placement vocabulary beyond above/below-editor

#### Agent widget node tree
- Currently minimal: id + last-line via `:content-path`
- Full top-line (status icon, phase, turns, elapsed) not yet composed from
  query fields — polish pass deferred
