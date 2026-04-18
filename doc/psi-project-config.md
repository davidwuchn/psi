# psi Project Config

Project-level configuration and runtime query conventions.

## `psi-tool`

`psi-tool` executes EQL queries against the live session graph.

Use it for:
- session/runtime introspection
- extension capability discovery
- querying resolver-backed runtime state

Example:

```clojure
[:psi.graph/resolver-syms]
```

Canonical discovery flow:
1. Query `:psi.graph/resolver-syms`
2. Query discovered attrs directly
3. Use root discovery attrs when needed:
   - `:psi.graph/root-seeds`
   - `:psi.graph/root-queryable-attrs`
