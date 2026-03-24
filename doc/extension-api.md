# Extension API

Extension-facing runtime/query surfaces and operational notes.

## Extension system

Extensions are Clojure namespaces loaded at runtime. Each extension
receives an API map with:

- Tool registration (`register-tool!`)
- EQL query access (`query`)
- UI hooks (`dialogs`, `widgets`, `status`, `notifications`, `renderers`)

## Workflow public-data display convention

For workflow-backed extensions, prefer projecting reusable display/read-model
data from `:public-data-fn` rather than formatting separately in every widget
or command consumer.

Preferred display-map keys:
- `:top-line`
- `:detail-line`
- `:question-lines`
- `:action-line`

Store that map under an extension-specific public key such as `:run/display`,
`:chain/display`, or `:subagent/display`, then let consumers merge/render that
public surface via shared helpers such as `extensions.workflow-display`.

Preferred helper usage:
- widget/UI consumers: `extensions.workflow-display/merged-display` + `display-lines`
- CLI/list consumers: `extensions.workflow-display/text-lines` over the rendered workflow lines

## Memory durability operations

Inspect provider selection/fallback + failure telemetry via EQL:

```clojure
[:psi.memory.store/active-provider-id
 :psi.memory.store/selection
 :psi.memory.store/health
 :psi.memory.store/active-provider-telemetry
 :psi.memory.store/last-failure
 :psi.memory.store/providers]
```

Telemetry fields (per provider map):
- `:telemetry :write-count`
- `:telemetry :read-count`
- `:telemetry :failure-count`
- `:telemetry :last-error`

Operational notes:
- Fallback decisions are visible at `:psi.memory.store/selection` (`:used-fallback`, `:reason`).
- Graph history retention is fixed-window (`snapshots`, `deltas`): newest N kept, oldest trimmed.
- The built-in memory store is in-memory only.
