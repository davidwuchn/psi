# Learning

Accumulated discoveries from ψ evolution.

---

## 2026-02-25 - agent-session Component

### λ Statechart Working Memory Data Pattern

`simple/simple-env` uses a **flat** working memory data model.  Guard and script
functions receive `(fn [env data])` where `data` is the flat WM map.  The current
event is stored at `:_event` inside `data` by the v20150901 algorithm.

To pass extra data to guards (e.g. the agent event that triggered a transition),
merge it into the WM before calling `sp/process-event!`:

```clojure
(defn send-event! [sc-env session-id event-kw extra-data]
  (let [wm  (get-working-memory sc-env session-id)
        wm' (if extra-data
              (update wm ::sc/data-model merge extra-data)
              wm)]
    (sp/save-working-memory! ...)
    (sp/process-event! ... wm' evt)))
```

Guards then read `(:pending-agent-event data)` — the key we merged in.

Initial WM is populated via `sp/start!`:
```clojure
(sp/start! processor env :chart-id {::sc/session-id id
                                     :session-data-atom a
                                     :actions-fn f
                                     :config c})
```

### λ Reactive Agent Event Bridge via add-watch

Agent-core's `events-atom` accumulates events (never reset between calls).
Bridge to session statechart using `add-watch` with old/new comparison:

```clojure
(add-watch (:events-atom agent-ctx) ::session-bridge
  (fn [_key _ref old-events new-events]
    (let [new-count (count new-events)
          old-count (count old-events)]
      (when (> new-count old-count)
        (doseq [ev (subvec new-events old-count new-count)]
          (sc/send-event! sc-env sc-session-id :session/agent-event
                          {:pending-agent-event ev}))))))
```

This is simpler than a callback and avoids modifying agent-core's API.

### λ Statechart Script Elements Pattern

Use `(ele/script {:expr (fn [env data] ...)})` inside transitions for side
effects, and `(ele/on-entry {} (ele/script {:expr ...}))` for entry actions.
Guards go in `{:cond (fn [_env data] ...)}` on the transition map.

The `actions-fn` in WM is a dispatcher: `(fn [action-key] ...)`.  Statechart
scripts call `(dispatch! data :action-key)` which is pure (reads from data, no
closures over ctx), keeping the statechart definition portable.

### λ Allium Sub-spec Splitting Pattern

When a monolithic `.allium` spec grows too large, split by orthogonal concern:
- Each sub-spec `use`s its dependencies by path
- Cross-references use `ext/` and `compact/` namespace prefixes
- Open questions follow each spec and reference the parent spec's original questions
- The original spec is retained as reference; sub-specs are the authoritative source

---

## 2026-02-25 - Introspection Component

### λ Introspection = Engine Queries Itself via EQL

The introspection component wires engine + query together so the system
is self-describing via a uniform EQL surface.  Two namespaces:

- `psi.introspection.resolvers` — five Pathom3 resolvers; all accept
  context objects as EQL seed inputs (`:psi/engine-ctx`, `:psi/query-ctx`)
- `psi.introspection.core`      — public API, Nullable pattern throughout

Key design decisions:
1. **Contexts as EQL seeds** — resolvers receive engine/query contexts
   through the EQL input map, not as closed-over globals.  This makes
   every resolver testable in isolation with `create-context`.
2. **Self-describing graph** — `query-graph-summary-in` queries the graph
   for its own resolver list, so introspection resolvers appear in their
   own output (`graph-self-describes-test`).
3. **Derived properties live in engine** — `has-interface?`, `is-ai-complete?`
   etc. are computed by `psi.engine.core`; the resolver just surfaces them.

### λ EQL Attribute Namespace Convention for Cross-Component Queries

Use `psi.X/Y` namespaces for attributes that cross component boundaries:

| Prefix          | Domain                          |
|-----------------|---------------------------------|
| `:psi/`         | top-level system context inputs |
| `:psi.engine/`  | engine entity attributes        |
| `:psi.system/`  | system state attributes         |
| `:psi.graph/`   | query graph attributes          |

Seed inputs (`:psi/engine-ctx`, `:psi/query-ctx`) are opaque Clojure
records — Pathom treats them as plain values in the entity map.

### λ trigger stored as (str keyword) — contains colon

`engine/trigger-engine-event-in!` stores `(str event)` on each transition.
For a keyword `:configuration-complete` this yields `":configuration-complete"`
(with leading colon).  Tests must match the stringified form, not the bare name.

## 2025-02-24 23:34 - Bootstrap Testing

### λ Testing Infrastructure Works

**Test Command**: `clojure -M:test` (not `-X:test`)
- `-X:test` fails (no :exec-fn defined) 
- `-M:test` succeeds (uses :main-opts with kaocha.runner)

**AI Component Status**: ✓ All tests passing
- psi.ai.core-test: 5 tests, 23 assertions, 0 failures
- Core functionality verified:
  - Stream options validation
  - Message handling 
  - Usage calculation
  - Model validation (Claude/OpenAI)
  - Conversation lifecycle

**Test Configuration**:
- Kaocha runner with documentation reporter
- Test paths: test/ + components/ai/test/
- Integration tests skipped (marked with :integration meta)
- Colorized output enabled

### λ System State Understanding

**Runtime**: JVM Clojure ready
- deps.edn: Polylith AI component + pathom3 + statecharts
- tests.edn: Kaocha configuration
- Latest: AI component integrated (commit 8663e14)

**Architecture Progress**:
- ✓ AI component implemented & tested
- ✓ Allium specs defined  
- ? Engine (statecharts integration)
- ? Query interface (pathom3 integration)
- ? Graph emergence from resolvers

**Next**: System integration beyond component tests

---

## 2026-02-25 - EQL Query Component

### λ psi/query Component Built and Clean

**Component**: `components/query/` — Pathom3 EQL query surface

Three namespaces, one responsibility each:
- `psi.query.registry` — additive resolver/mutation store (atoms, malli-validated)
- `psi.query.env`      — Pathom3 environment construction (`build-env`, `process`)
- `psi.query.core`     — public API: `register-resolver!`, `query`, `query-one`,
                          `rebuild-env!`, `graph-summary`, `defresolver`, `defmutation`

**Status**: 10 tests, 32 assertions, 0 failures. 0 kondo errors/warnings. 0 LSP diagnostics.

### λ clj-kondo Config Import

Run this after adding new deps or components — imports hook/type configs from jars:
```bash
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```
Run at **root** and in **each component dir** separately.

New configs gained this session: pathom3, promesa, guardrails, potemkin, prismatic/schema.

### λ Two Separate Lint Systems

**clj-kondo** (`.clj-kondo/config.edn`) and **clojure-lsp** (`.lsp/config.edn`) are distinct:

| Concern | Config file | Linter key |
|---------|-------------|------------|
| clj-kondo unused public var | `.clj-kondo/config.edn` | `:unused-public-var` |
| clojure-lsp unused public var | `.lsp/config.edn` | `:clojure-lsp/unused-public-var` |

**✗ Do not** put `:unused-public-var` or `:clojure-lsp/*` keys in `.clj-kondo/config.edn`
— clj-kondo will warn "Unexpected linter name".

**Authoritative check**: `clojure-lsp diagnostics --project-root .` — not the pi tool
(which caches stale results).

### λ Test Isolation Pattern

Polylith components use `defonce` atoms — state bleeds between tests in the same JVM.
`use-fixtures :each` resets between *test functions* but not between `testing` blocks.

**Pattern** — use a `with-clean-*` macro:
```clojure
(defmacro with-clean-registry [& body]
  `(do (registry/reset-registry!)
       (try ~@body (finally (registry/reset-registry!)))))
```
Wrap each isolated scenario in its own `with-clean-*` call.

### λ Inline defs in Tests

`pco/defresolver` / `pco/defmutation` inside a `deftest` body triggers
clj-kondo `inline-def` warning and confuses clojure-lsp symbol resolution.

**Fix**: define resolvers/mutations at **top-level** in the test namespace.
If the test needs a clean registry, re-register the top-level var inside
`with-clean-*` rather than redefining.

### λ Kaocha --focus Syntax

`--focus psi.query` does not match test namespaces (needs exact ns name).
Use: `--focus psi.query.core-test --focus psi.query.registry-test`

### λ Architecture Progress

- ✓ AI component implemented & tested
- ✓ Engine (statecharts) component implemented & tested
- ✓ Query (EQL/Pathom3) component implemented & tested
- ✓ AI integrated with engine + query — resolvers registered, core.async removed
- ? Graph emergence from resolvers (next: add domain resolvers)
- ? Introspection (engine queries engine via EQL)
- ? History / Knowledge resolvers (git + knowledge graph)

---

## 2026-02-25 - AI ↔ Engine/Query Integration

### λ Callback > Channel for blocking I/O

Provider HTTP streaming is purely blocking I/O.  `core.async/go` +
`async/chan` added scheduler complexity with no benefit.  Replacing with:

- **`consume-fn` callback** — provider calls it synchronously per event
- **`future` + `LinkedBlockingQueue`** — bridges background thread to a
  lazy seq when callers prefer pull-style consumption

Pattern:
```clojure
;; Push style (callback)
(stream-response provider conv model opts
  (fn [ev] (when (= :text-delta (:type ev)) (print (:delta ev)))))

;; Pull style (lazy seq)
(let [{:keys [events]} (stream-response-seq provider conv model opts)]
  (doseq [ev events] ...))
```

### λ AI Resolvers in EQL Graph

Register AI capabilities as Pathom resolvers so the whole system can query
them via a uniform EQL surface:

```clojure
(core/register-resolvers!)
(query/query {} [:ai/all-models])
(query/query {:ai.model/key :gpt-4o} [:ai.model/data])
(query/query {:ai/provider :anthropic} [:ai/provider-models])
```

### λ clj-kondo Hooks Must Be Imported Per Component

Each Polylith component has its own `.clj-kondo/` dir.  When a component
gains a new dependency whose macros need kondo hooks (e.g. pathom3's
`pco/defresolver`), the hooks must be imported **in that component's dir**:

```bash
cd components/<name>
clj-kondo --lint "$(clojure -Spath)" --copy-configs --skip-lint
```

The component `deps.edn` must already declare the dep for it to appear
on the classpath.  Symptom of missing import: "Unresolved symbol" for
every var/binding the macro generates.

### λ Stub Provider Pattern for Tests

Use a stub provider closure to drive streaming tests without HTTP:

```clojure
(defn stub-provider [text]
  {:name   :stub
   :stream (fn [_conv _model _opts consume-fn]
             (consume-fn {:type :start})
             (consume-fn {:type :text-delta :delta text})
             (consume-fn {:type :done :reason :stop ...}))})
```

Swap it into the registry for the test, restore afterward.

---

## 2026-02-25 - Nullable Pattern / Testing Without Mocks

### λ Nullable Pattern in Clojure — Isolated Context Factory

The Nullable pattern replaces global-atom resets and mock/spy setups with
isolated context factories.  Every component that owns mutable state gets a
`create-context` (or `create-registry`, `create-query-context`) factory that
returns a plain map of fresh atoms:

```clojure
(defn create-context []
  {:engines           (atom {})
   :system-state      (atom nil)
   :state-transitions (atom [])
   :sc-env            (atom nil)})
```

All mutable functions gain a `*-in` context-aware variant taking the context
as first arg.  The global (singleton) API becomes thin wrappers via a
`global-context` helper that returns the `defonce` atoms:

```clojure
(defn- global-context []
  {:engines engines :system-state system-state ...})

(defn create-engine [engine-id config]
  (create-engine-in (global-context) engine-id config))
```

Tests create their own context — no shared state, no cleanup fixtures:

```clojure
(deftest engine-lifecycle-test
  (let [ctx (engine/create-context)
        eng (engine/create-engine-in ctx "test" {})]
    (is (= :initializing (:engine-status eng)))))
```

### λ Isolated Query Context (QueryContext record)

`query/create-query-context` returns a `QueryContext` record with its own
registry + env atom.  Tests register resolvers into it and query against it:

```clojure
(let [ctx (query/create-query-context)]
  (query/register-resolver-in! ctx my-resolver)
  (query/rebuild-env-in! ctx)
  (query/query-in ctx {:user/id 1} [:user/name]))
```

This replaces the `with-clean-query` macro that reset global atoms.

### λ Isolation Tests Are Worth Adding

Adding an explicit test that two contexts are independent catches regressions
if the factory accidentally shares state:

```clojure
(deftest context-isolation-test
  (let [ctx-a (query/create-query-context)
        ctx-b (query/create-query-context)]
    (query/register-resolver-in! ctx-a greeting-resolver)
    (is (= 1 (:resolver-count (query/graph-summary-in ctx-a))))
    (is (= 0 (:resolver-count (query/graph-summary-in ctx-b))))))
```

### λ Nullable Pattern for External Process Infrastructure (git)

When infrastructure is an external process (not mutable state), the Nullable
pattern uses a **context record + embedded temp environment** rather than a
stub closure:

```clojure
;; GitContext — the infrastructure wrapper
(defrecord GitContext [repo-dir])

(defn create-context
  "Production: points at a real repo dir."
  ([] (create-context (System/getProperty "user.dir")))
  ([repo-dir] (->GitContext repo-dir)))

(defn create-null-context
  "Test: builds an isolated temp git repo with seeded commits.
   Real git, controlled data, no shared state, no mocking."
  ([] (create-null-context default-seed-commits))
  ([commits]
   (let [tmp (make-temp-dir)]
     (git-init! tmp)
     (doseq [{:keys [message files]} commits]
       (write-files! tmp files)
       (git-commit! tmp message))
     (->GitContext tmp))))
```

Key points:
- **Real git subprocess** — not a stub. Tests exercise the same code path as production.
- **Seeded data** — commits carry controlled messages with vocabulary symbols.
- **Isolated per test** — each `create-null-context` call gets a fresh temp dir.
- **mkdirs before spit** — files in subdirs need parent dirs created first.
- **No cleanup needed** — JVM temp dirs are cleaned on exit.

Two-context isolation test verifies independence:
```clojure
(deftest two-null-contexts-are-independent
  (let [ctx-a (git/create-null-context [{:message "only in A" :files {"a.txt" "a"}}])
        ctx-b (git/create-null-context [{:message "only in B" :files {"b.txt" "b"}}])]
    (is (not (some #(str/includes? (:git.commit/subject %) "B")
                   (git/log ctx-a {}))))))
```

### λ clj-kondo Cache Goes Stale After Refactors

After adding new public vars to a namespace, clj-kondo's `.cache/` still
holds the old snapshot → LSP reports "Unresolved var" for the new fns even
though they compile fine.

**Fix**: re-lint the source directories to rebuild the cache:
```bash
clj-kondo --lint components/query/src components/ai/src ...
```
No flags needed — linting source updates the cache in place.

### λ Avoid Redundant `let` for clj-kondo

clj-kondo warns "Redundant let expression" when a `let` has a single binding
(even if it's map destructuring).  Merge the inner `let` into the outer one
to silence it:

```clojure
;; ✗ triggers warning
(let [ctx (create-context)]
  (let [{:keys [future session]} (stream-response-in ctx ...)]
    @future ...))

;; ✓ merge bindings
(let [ctx                       (create-context)
      {bg :future session :session} (stream-response-in ctx ...)]
  @bg ...)
```
