Goal: extend `psi-tool` from a query-only live introspection tool into a canonical live runtime self-modification tool, so psi can both inspect and deliberately modify the running system from inside a session without leaving the session.

Context:
- `psi-tool` is already the canonical live runtime inspection surface for session graph/EQL data.
- psi increasingly works in a REPL-like, self-diagnostic, self-repair loop.
- Current workflows can inspect the runtime from inside a session, but cannot canonically:
  - request psi code reload
  - execute arbitrary in-process psi/Clojure code
- Repo learning already shows that code reload and live graph refresh are related but distinct concerns.
- Session directory semantics have already converged on canonical session `:worktree-path`; reload operations must follow that ownership model rather than guessing from process cwd.

Problem statement:
`psi-tool` is currently read-only. It can execute EQL queries, but it cannot perform runtime self-modification operations, and it has no explicit contract for code reload scope.

Design decision:
`psi-tool` becomes a multi-operation runtime tool with one explicit operation discriminator.

Canonical public contract:
- `psi-tool` accepts `action`.
- Supported canonical actions in this task:
  1. `query`
  2. `eval`
  3. `reload-code`
- Existing query-only calls of the form `{query: "...", entity: "..."}` remain accepted only as a compatibility alias for `action: "query"` during migration.
- Canonical docs, examples, and all new tests must use the action-based form.
- Compatibility support is transitional and must not be expanded to `eval` or `reload-code`.

Canonical request shapes:

1. Query
- purpose: read from the live graph
- request:
  - `action: "query"`
  - `query: <EDN vector string>`
  - `entity: <optional EDN map string>`
- semantics:
  - preserves current `psi-tool` query behavior
  - remains pure/read-only relative to runtime state mutation
  - returns the same serialized query result payload as legacy query-only `psi-tool`
  - does not wrap the query result in an action envelope

2. Eval
- purpose: execute arbitrary in-process psi/Clojure code in the running JVM
- request:
  - `action: "eval"`
  - `ns: <namespace string>`
  - `form: <Clojure form string>`
- semantics:
  - parses the supplied form with `*read-eval*` disabled
  - evaluates in the named namespace
  - runs inside the live psi process, not in a shell and not in an external REPL subprocess
  - is for deliberate runtime inspection/repair/migration code, not for filesystem command execution
  - may mutate runtime state if the evaluated form does so; this is intentional and explicit
  - `ns` is required for canonical eval requests; there is no implicit namespace selection in the canonical contract
  - `ns` must name an already loaded namespace
  - if `ns` is not already loaded, eval returns an error
  - eval does not auto-require namespaces and does not auto-create namespaces

3. Reload code
- purpose: reload already loaded psi code in the running runtime, with explicit reload scope
- request supports exactly one targeting mode:
  - namespace mode:
    - `action: "reload-code"`
    - `namespaces: <non-empty vector of distinct non-blank namespace strings>`
  - worktree mode:
    - `action: "reload-code"`
    - optional `worktree-path: <absolute path string>`
- semantics:
  - exactly one of `namespaces` or worktree mode must be selected
  - worktree mode is selected when `namespaces` is absent and worktree targeting is present via explicit `worktree-path` or invoking session `:worktree-path`
  - if `namespaces` and `worktree-path` are both supplied, the request returns an error
  - namespace mode reloads exactly the named already loaded namespaces, in request order
  - namespace mode may target any already loaded namespace, including extension namespaces
  - worktree mode reloads all already loaded namespaces whose canonical source path resolves under the effective target worktree-path
  - extension namespaces are included only in worktree mode, and only when their loaded source file resolves under the effective target worktree-path
  - `reload-code` does not reload namespaces whose canonical source path does not resolve under the selected worktree-path
  - `reload-code` does not discover brand new namespaces solely from files on disk
  - then performs a runtime-owned live graph/runtime refresh step to make post-reload runtime surfaces coherent with the reloaded code
  - reports code reload and graph refresh as distinct sub-results
- important nuance:
  - `reload-code` is one user-facing operation, but its result must distinguish:
    - code reload status
    - graph/runtime refresh status
  - success of one phase must not silently imply success of the other

Worktree targeting semantics for `reload-code`:
- in worktree mode, the effective target worktree-path is resolved by this precedence:
  1. explicit `worktree-path` argument
  2. the invoking tool call session’s canonical `:worktree-path`
  3. otherwise: error
- explicit `worktree-path` must:
  - be a non-blank absolute path string
  - resolve to an existing directory
- a worktree-path is reloadable only when the runtime can enumerate one or more already loaded namespaces whose canonical source path resolves under that worktree-path
- if the current runtime cannot reload from that worktree-path, the operation returns an explicit error

Explicitly not allowed as semantic fallback for `reload-code` worktree mode:
- process cwd is not the canonical target when no explicit/session worktree is available
- adapter-local cwd is not the canonical target
- hidden dynamic binding or ambient focus inference is not the canonical target

Clarifications:
- query operations do not require a worktree-path unless the queried attrs themselves require explicit session/entity targeting
- query `entity` seeding does not affect `reload-code` targeting
- `reload-code` namespace mode does not use worktree targeting
- `reload-code` worktree mode must always report the effective target worktree-path it used
- if explicit `worktree-path` is supplied, the result must report `:psi-tool/worktree-source = :explicit`
- if the invoking session worktree-path is used, the result must report `:psi-tool/worktree-source = :session`

Why `worktree-path` is part of the reload contract:
- psi sessions already own canonical worktree identity
- multi-worktree development is a first-class workflow in this repo
- path-sensitive reload behavior must not silently act on the wrong checkout
- making targeting explicit is more important than preserving convenience fallbacks

Action validation contract:
- if `action` is absent and `query` is present, the request is treated as the compatibility alias for `action: "query"`
- if `action` is absent and `query` is absent, the request returns an error
- unknown `action` returns an error listing the canonical supported actions
- missing required arguments for the selected action return an error
- invalid argument values for the selected action return an error
- extra unrelated arguments are ignored

Canonical result semantics:
Tool output must remain visible from the live tool surface and diagnosable without external manual steps.

For all actions, `psi-tool` returns:
- `:content` — serialized textual result suitable for transcript/tool rendering
- `:is-error` — boolean
- `:details` — truncation/full-output metadata when needed

For `eval` and `reload-code`, `:content` is the `pr-str` serialization of the structured operation report.
For `query`, `:content` remains the serialized query result payload.

Canonical structured result for `eval`:
- `:psi-tool/action` = `:eval`
- `:psi-tool/ns` = evaluated namespace string
- `:psi-tool/value` = `pr-str` serialization of the successful evaluation result after psi-tool sanitization
- `:psi-tool/value-type` = runtime class name string when available
- `:psi-tool/duration-ms` = wall-clock elapsed milliseconds from validated execution start to final result construction
- `:psi-tool/error` = structured error summary when failed

Canonical structured result for `reload-code`:
- `:psi-tool/action` = `:reload-code`
- `:psi-tool/reload-mode` = `:namespaces` | `:worktree`
- when namespace mode:
  - `:psi-tool/namespaces-requested` = full ordered vector of requested namespace names
- when worktree mode:
  - `:psi-tool/worktree-path` = effective target worktree-path
  - `:psi-tool/worktree-source` = `:explicit` or `:session`
- `:psi-tool/code-reload`
  - `:status` = `:ok` | `:error`
  - `:namespace-count`
  - `:namespaces` = full ordered vector of namespace names actually reloaded
  - `:summary` = terse human-readable summary
  - `:error` = structured error summary when failed
- `:psi-tool/graph-refresh`
  - `:status` = `:ok` | `:error`
  - `:summary`
  - `:steps` = ordered vector of mandatory refresh step results
  - `:error` when failed
- `:psi-tool/duration-ms` = wall-clock elapsed milliseconds from validated execution start to final result construction
- `:psi-tool/overall-status` = `:ok` only when both code reload and graph refresh succeed; otherwise `:error`

Canonical structured result for `query`:
- preserves current behavior of serializing the query result itself
- does not wrap normal query payloads in an extra envelope
- this preserves existing ergonomics for graph reads while new runtime actions return explicit operation reports

Why results are asymmetric:
- query is fundamentally a read surface and existing callers expect the queried EDN directly
- eval/reload are imperative runtime operations and require explicit operation reports
- forcing query into the same envelope would add migration cost without improving clarity

Structured error summary:
- a structured error summary is a map containing:
  - `:message`
  - `:class`
  - `:phase`
  - `:data` containing sanitized `ex-data` when present
- raw throwable objects must not be embedded in the result

Error semantics:
- parse/validation errors return `:is-error true`
- runtime exceptions during eval/reload return `:is-error true`
- reload failures must still report partial phase information when available
- a graph refresh failure after a successful code reload is an error, not a silent success
- the error text in `:content` must still be readable in plain transcript views
- the structured report must preserve the failing phase/action so diagnosis does not depend on log spelunking

Truncation semantics:
- `psi-tool` keeps its existing truncation/full-output spill behavior
- large eval results and large reload reports use the same truncation policy machinery already used by `psi-tool`
- visible truncated output must preserve the action and phase statuses in the visible prefix
- visible truncated output for worktree mode reload must preserve the effective target worktree-path in the visible prefix
- if truncation occurs for reload reports, the full spill artifact preserves the full ordered namespace list and full step results

Sanitization semantics:
- eval values, structured reload reports, and structured error `:data` must pass through the same recursive/root-context sanitization strategy already used by `psi-tool` query output before serialization

Execution model for eval:
- in-process only
- no shell escape
- no external REPL dependency
- eval is namespace-scoped, not worktree-scoped
- the operation is not sandboxed; it is an intentional privileged runtime capability exposed through the selected tool surface

Canonical source-path resolution for worktree mode reload:
- `reload-code` worktree mode uses canonical source-path resolution for already loaded namespaces
- a namespace canonical source path is the canonical absolute filesystem path of the source file from which that namespace was loaded into the current runtime
- if a loaded namespace has no resolvable canonical source path, that namespace is not a reload candidate for worktree mode
- a namespace is in-scope for worktree mode reload only when its canonical source path is under the effective target worktree-path
- source-path membership is determined by canonical filesystem path containment, not by namespace prefix matching, classpath order guessing, or process cwd

Reload candidate set:
- in namespace mode, the reload candidate set is exactly the requested namespace vector, in request order
- each namespace in namespace mode must already be loaded; otherwise the request returns an error
- in worktree mode, the reload candidate set is the full ordered sequence of already loaded namespaces whose canonical source path is under the effective target worktree-path
- worktree mode candidate ordering follows the runtime reload mechanism’s deterministic reload order
- if the runtime reload mechanism does not define a richer order, worktree mode candidate ordering is ascending by namespace name string
- if the worktree mode reload candidate set is empty, `reload-code` returns an explicit unreloadable-target error

Execution model for reload-code:
- runtime-owned reload, not adapter-owned and not shell-owned
- the public contract is:
  1. validate exactly one reload targeting mode
  2. resolve the reload candidate set for that mode
  3. reload that candidate set in deterministic order
  4. stop at the first namespace reload failure and report the successfully reloaded prefix in `:psi-tool/code-reload :namespaces`
  5. run the mandatory graph/runtime refresh steps after the reload phase completes, even when the reload phase ended in error
  6. report both phases distinctly
- `reload-code` is best-effort and non-atomic
- a failure after one or more namespaces have been reloaded does not roll back prior reload effects
- the contract guarantees diagnosis, not transactional rollback

Mandatory graph/runtime refresh steps:
After code reload, `reload-code` must run these refresh steps in order and report them in `:psi-tool/graph-refresh :steps`:
1. resolver registration refresh
2. mutation registration refresh
3. live tool definition refresh used by session tool exposure
4. worktree mode: extension rediscovery and reload limited to extensions whose discovered source file resolves under the effective target worktree-path; namespace mode: preserve the current extension registry without extension rediscovery

Mandatory refresh boundaries:
- extension refresh success or failure contributes to `graph-refresh` status
- namespace mode does not infer a worktree-path from the named namespaces for extension rediscovery
- `reload-code` must not implicitly reload model definitions
- `reload-code` must not implicitly reset OAuth state
- `reload-code` must not implicitly rewrite session persistence state

Scope of reload in this task:
- explicit namespace-scoped reload or explicit worktree-scoped reload of already loaded namespaces
- not a general-purpose arbitrary classpath hot reload framework
- not a full restart replacement
- not a guarantee that every possible mutable singleton/stateful subsystem becomes reversible
- the task promises a canonical runtime reload path with explicit diagnostics

Relationship to graph/runtime refresh:
This design explicitly separates two truths:
- code reload changes loaded vars/classes/namespaces
- graph/runtime refresh updates the live resolver/mutation/discovery/tool/extension surface that psi exposes

Therefore:
- `reload-code` must not claim success unless the required refresh work is also accounted for
- if the code reload succeeds and graph refresh fails, the result must say exactly that
- `graph-refresh` is not optional in this task and must not report `:skipped`

Relationship to EQL mutations:
- this task does not add a public EQL mutation as the primary user-facing contract
- the user-facing contract is the `psi-tool` operation surface
- internal implementation may route through runtime helpers, dispatch-owned effects, or mutations as needed
- the design remains runtime-capability-first, not graph-write-surface-first

Runtime/source-tree targeting boundary:
- worktree mode may target only a worktree-path that the live runtime can reload from
- targeting a different checkout than the process startup checkout is allowed only when the current runtime already has one or more namespaces loaded from source files under that requested worktree-path
- worktree mode does not discover brand new namespaces solely from the requested worktree-path
- worktree mode reloads only namespaces that are already loaded in the current runtime and in-scope by canonical source-path containment
- if the runtime cannot do so, `reload-code` returns an explicit error rather than silently reloading from some other tree

Security / permission posture:
- this task does not attempt to sandbox arbitrary in-process eval
- it is a privileged developer/runtime capability
- it should only be available through the same session capability model that already governs access to `psi-tool`
- future finer-grained capability splitting (`psi-query` vs `psi-eval` vs `psi-reload`) is allowed, but not required for this task

Observability requirements:
The operation must be diagnosable from inside psi.

Required visibility:
- tool transcript output shows the operation result
- tool lifecycle/telemetry records the invoked action and canonical arguments
- worktree mode results show explicit target worktree-path
- reload results show distinct code-reload and graph-refresh outcomes
- failures are visible without requiring external REPL access

Telemetry requirements:
- tool lifecycle telemetry must record:
  - `action`
  - `ns` for eval
  - `namespaces` for namespace mode reload subject to existing tool-argument size limits
  - requested `worktree-path` for worktree mode reload when supplied
  - effective `worktree-path` for worktree mode reload
  - `query` for query actions subject to existing tool-argument size limits
  - `form` for eval subject to the same tool-argument size limits
- telemetry records the textual request arguments, not parsed/evaluated object graphs

Docs requirements:
The task is not complete until docs explain:
- canonical action-based `psi-tool` contract
- compatibility note for legacy query-only calls
- reload-code targeting modes and the rule that exactly one mode must be selected
- effective worktree-path precedence for worktree mode reload
- canonical source-path resolution and worktree mode reload candidate selection rules
- the difference between `query`, `eval`, and `reload-code`
- the difference between namespace mode reload and worktree mode reload
- the difference between code reload success and graph refresh success
- eval namespace existence requirements
- the fact that eval is namespace-scoped and does not accept worktree targeting
- the fact that worktree mode reload only reloads already loaded namespaces and does not discover new namespaces from disk
- at least one example for:
  - action-based query
  - eval in a named already-loaded namespace
  - reload-code in namespace mode
  - reload-code in worktree mode using session-derived worktree-path
  - reload-code in worktree mode using explicit worktree-path

Test requirements:
The task is not complete until tests cover:

1. Query compatibility
- existing query behavior still works
- action-based `query` works
- explicit entity seeding still works
- query action returns the same unwrapped query payload shape as legacy query-only input

2. Eval contract
- missing `ns` is rejected
- unknown `ns` is rejected
- invalid form text is rejected safely
- eval runs in the named namespace
- eval errors return structured failure output
- eval result serialization uses sanitized `pr-str` value output
- eval ignores unrelated extra arguments

3. Reload contract — namespace mode
- namespace mode requires `namespaces`
- namespace mode rejects empty namespace vectors
- namespace mode rejects duplicate namespace names
- namespace mode rejects blank namespace names
- namespace mode rejects unknown/unloaded namespaces
- namespace mode may target loaded extension namespaces
- namespace mode reloads exactly the requested namespaces in request order
- namespace mode rejects simultaneous `worktree-path`
- result reports `:psi-tool/reload-mode = :namespaces`
- result distinguishes code-reload from graph-refresh status
- graph refresh failure after code reload is surfaced as an error
- reload stops at the first namespace failure and reports the successful prefix
- reload is reported as best-effort/non-atomic on partial failure
- namespace mode preserves the current extension registry without extension rediscovery

4. Reload contract — worktree mode
- worktree mode can target the current session worktree-path when explicit `worktree-path` is absent
- worktree mode can target an explicit worktree-path differing from the session worktree-path when the runtime already has loaded namespaces sourced from that worktree-path
- explicit non-absolute worktree-path is rejected
- explicit non-existent worktree-path is rejected
- explicit unreloadable worktree-path is rejected with an explicit error
- canonical source-path containment decides worktree mode reload candidate membership
- namespaces without resolvable canonical source paths are excluded from worktree mode reload candidates
- extension rediscovery/reload contributes to graph-refresh status in worktree mode
- result reports effective worktree-path and worktree source
- worktree mode does not discover brand new namespaces solely from files on disk

5. Tool-surface diagnostics
- large eval/reload results preserve action/target metadata in visible truncated output
- telemetry/tool lifecycle captures the canonical action arguments subject to existing argument-size limits

Non-goals:
- do not redesign every runtime subsystem for perfect hot reload semantics
- do not broaden this into full project REPL support; that is task `015-direct-project-repl-support`
- do not treat shell execution as the canonical implementation path
- do not keep cwd fallback as an invisible convenience rule
- do not collapse query, eval, and reload into one ambiguous implicit behavior
- do not require manual external REPL steps as the primary intended workflow after this lands

Out of scope but compatible with this design:
- finer-grained reload scopes such as path-root mode or namespace-pattern mode
- stronger capability subdivision between query/eval/reload actions
- richer introspection attrs exposing last reload/eval summaries in the graph
- adapter UX improvements around rendering eval/reload reports

Acceptance:
- `psi-tool` has a canonical action-based runtime contract
- `psi-tool` can perform live graph query, in-process eval, and code reload
- eval is namespace-scoped and requires an already loaded namespace
- reload-code has explicit targeting modes and explicit reload scope
- worktree mode reload uses explicit worktree targeting semantics with no ambiguous cwd fallback
- worktree mode candidate selection is defined by canonical source-path containment over already loaded namespaces
- namespace mode reload order, validation, and failure behavior are explicit
- reload reports code reload and graph/runtime refresh as separate outcomes
- mandatory graph/runtime refresh steps are explicit and covered by tests
- results are visible and diagnosable from the live tool surface
- docs and tests cover namespace mode and worktree mode reload semantics
