# Extension API

Extension-facing runtime/query surfaces and operational notes.

## Extension system

Extensions are Clojure namespaces loaded at runtime. Each extension
receives an API map with:

- Tool registration (`register-tool!`)
- EQL query access (`query`)
- UI hooks (`dialogs`, `widgets`, `status`, `notifications`, `renderers`)

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
