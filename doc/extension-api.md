# Extension API

Extension-facing runtime/query surfaces and operational notes.

## Extension system

Extensions are Clojure namespaces loaded at runtime. Each extension
receives an API map with:

- Tool registration (`register-tool!`)
- EQL query access (`query`)
- UI hooks (`dialogs`, `widgets`, `status`, `notifications`, `renderers`)

Extensions can also call shared library namespaces directly when they need
common deterministic behavior that should not be reimplemented per extension.
Current example: `psi.ai.model-selection` for role/policy-based model choice.

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
`:chain/display`, or `:agent/display`, then let consumers merge/render that
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

## Session-targeted helpers

When an extension needs to inspect or mutate a specific session rather than the
ambient runtime session, prefer explicit session-targeted helpers:

- `(:query-session api) session-id eql-query`
- `(:mutate-session api) session-id op-sym params`

This is the recommended pattern for delayed/background extension work. It keeps
session routing explicit and avoids relying on implicit adapter focus.

Example:

```clojure
(let [model-ctx ((:query-session api) source-session-id
                 [:psi.agent-session/model-provider
                  :psi.agent-session/model-id])]
  ((:mutate-session api) child-session-id 'psi.extension/run-agent-loop-in-session
   {:prompt "..."
    :model  {:provider (keyword (:psi.agent-session/model-provider model-ctx))
             :id       (:psi.agent-session/model-id model-ctx)}}))
```

## Shared model selection for extensions

Extensions that need to choose a model for helper/background work should prefer
`psi.ai.model-selection` over per-extension fallback chains.

Current shared entrypoint:

- `psi.ai.model-selection/resolve-selection`

Typical usage:

```clojure
(let [model-ctx ((:query-session api) source-session-id
                 [:psi.agent-session/model-provider
                  :psi.agent-session/model-id])
      selection (psi.ai.model-selection/resolve-selection
                 {:request {:role    :auto-session-name
                            :mode    :resolve
                            :context {:session-model {:provider (some-> (:psi.agent-session/model-provider model-ctx)
                                                                        keyword)
                                                      :id       (:psi.agent-session/model-id model-ctx)}}}})]
  (when (= :ok (:outcome selection))
    (:candidate selection)))
```

Current request shape:

```clojure
{:request {:mode :resolve | :explicit | :inherit-session
           :role keyword?
           :required [...]
           :strong-preferences [...]
           :weak-preferences [...]
           :context {...}
           :model {:provider :openai :id "gpt-5"}}}
```

Current result shape:

```clojure
{:outcome :ok | :no-winner
 :candidate candidate?
 :ambiguous? boolean?
 :reason keyword?
 :effective-request {...}
 :filtering {...}
 :ranking {...}
 :trace {:short {...}
         :full {...}}}
```

Guidance:
- express intent via role + constraints/preferences
- pass source-session context explicitly when affinity matters
- treat `:no-winner` as a first-class outcome
- use `:trace` when an extension needs explainability/debug output
- do not silently violate required constraints with caller-local fallbacks
