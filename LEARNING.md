# Learning

---

## 2026-03-23 - core.clj decomposition: defmulti + ctx-key callbacks + requiring-resolve break circular deps

### λ A defmulti effect executor enables open dispatch without circular ns dependencies

Replacing the 170-line `case` statement in `execute-dispatch-effect-in!` with a `defmulti` dispatching on `:effect/type` broke the coupling that prevented extracting effect handlers. Back-references to core.clj private functions (compaction, journal-append, background jobs) route through ctx callback keys — the same pattern already established for `:run-tool-call-fn`. New effect types can now be added from any namespace via `defmethod` without modifying core.clj.

Key gotcha: `defmulti` returns a `MultiFn` which does NOT satisfy `(fn? ...)`. The dispatch pipeline's `(fn? execute-fn)` guard silently skipped all effects. Fix: wrap in `(fn [ctx effect] (execute-effect! ctx effect))` at the ctx wiring point.

### λ requiring-resolve breaks ns cycles for registration-time-only calls

`create-context` calls handler registration functions that live in `dispatch-handlers.clj`, which in turn requires `core.clj` for state readers. Direct `:require` would be circular. `requiring-resolve` defers the require to first call (runtime), when all vars are bound. This is clean for registration-time-only calls where the resolved fn is called once during setup, not on every dispatch.

Same gotcha: `requiring-resolve` returns a Var. Vars satisfy `ifn?` but not `fn?`. For ctx keys checked with `fn?`, deref with `@` before storing.

### λ ctx-key callbacks make with-redefs work when function values are captured at context-creation time

When a ctx key stores a function value (`execute-tool-runtime-in!`), `with-redefs` in tests has no effect because the value was captured before the redef. Storing the Var instead (`#'execute-tool-runtime-in!`) makes the ctx key deref through the var at call time, so `with-redefs` works transparently.

### λ Mutations calling dispatch directly breaks the core.clj coupling — the EQL surface doesn't need the implementation ns

The 34 EQL mutations in core.clj were entwined with core because each mutation called a `*-in!` wrapper function defined in core. But most `*-in!` wrappers are just `dispatch! + read-back` — the mutation can call dispatch directly and derive the return from handler `:return` or input params. This means mutations.clj requires only `dispatch.clj` + external domain nses, NOT core.clj. Core.clj can then safely require mutations.clj for `all-mutations` registration.

For mutations that need core-private orchestration (tool execution, background job tracking), `requiring-resolve` bridges the gap without a compile-time ns cycle. Making the resolved functions public is cleaner than keeping them private and using var reflection.

### λ Extracting state infrastructure as a leaf module eliminates circular deps without requiring-resolve

The root cause of all requiring-resolve calls was that core.clj was both the state infrastructure provider (get-session-data-in, session-update, state-path) AND the top-level consumer of dispatch-handlers, mutations, lifecycle. Every downstream module needed core's state infra, and core needed every downstream module.

Extracting `session-state.clj` as a leaf module that owns state paths, primitives, session-update, journal, context-index, and phase queries broke every cycle. dispatch-handlers and session-lifecycle now require session-state (not core), so core can require them directly. This eliminated 9 of 10 requiring-resolve calls in core.clj.

The key insight: when a hub module is both infrastructure provider and orchestration consumer, split the infrastructure into a leaf. The orchestration hub shrinks to just orchestration, and the leaf has no upward dependencies.

### λ The coupling hub that formerly required requiring-resolve is now just orchestration

After session-state extraction, core.clj (1515 lines) contains only: context creation, extension management, prompting, query registration, re-exported API, and global wrappers. It requires all downstream modules directly. The only remaining requiring-resolve is the executor boundary (a separate, pre-existing cycle).

## 2026-03-23 - Gate dispatch schema validation on `*assert*` at compile time, not a runtime config flag

### λ Schema validation of dispatch effects/pure-results is an invariant check, not a feature toggle

The effect and pure-result malli schemas describe the system's actual contract — violations are bugs, not configuration choices. That makes `*assert*` the right gate: it is a compile-time binding that is true during dev/test and false in production AOT builds, matching the semantics of "catch contract violations during development, pay zero cost in production." A runtime config flag would imply the validation is optional behavior, invite it being left off during testing, and add a code path that is never exercised in production. `*assert*` avoids all three problems.

The implementation pattern: `(def validator (when *assert* (fn ...)))` compiles to `nil` when `*assert*` is false. The existing validate-interceptor already checks `(fn? validate-fn)` before calling, so a nil validator is skipped with no conditional logic at the wiring site.

## 2026-03-23 - Replay classification was unnecessary when replay already suppresses effects

### λ If replay skips effects, every dispatch event is safe to replay — classification solves a non-problem

The replay classification system (`:replayable` / `:projection` / `:diagnostic`) was built on the assumption that some events are unsafe to replay. But since replay already suppresses effects and only applies pure state transforms, the safety concern doesn't exist. Removing 261 lines of classification infrastructure, separate replayable stores, per-handler annotations, and EQL surfaces simplified the dispatch pipeline with no behavioral loss.

The reusable rule: before building a classification/policy system, check whether the mechanism it gates already handles the concern. If replay = "apply state, skip effects," then every event is already replay-safe by construction.

### λ Stream-done and lifecycle tool-start were a latent duplicate that classification obscured

The duplicate `tool/start` progress emission (one from stream-done, one from the lifecycle system) went unnoticed partly because the classification ceremony made it easy to focus on replay policy rather than simple event flow. Once classification was removed and the test assertion tightened, the duplicate was obvious. Simpler systems expose bugs faster.

## 2026-03-23 - Unify result shapes by asking "why are there two?" not "which one wins?"

### λ When two code paths do the same thing, the question isn't which to keep — it's whether the distinction is real

`:session-update` and `:root-state-update` both called `swap!` on the same atom. The only difference was that `:session-update` wrapped the transform to target the session-data slice and sync the context index. That wrapping is a helper concern, not a result-shape concern. Moving it to a `session-update` wrapper function collapsed three apply-interceptor branches to one.

The reusable rule: when multiple code paths exist, check whether they differ in *mechanism* or just in *convenience*. If it's convenience, extract a helper and unify the mechanism.

### λ Bulk regex-style replacement on Clojure forms needs paren repair immediately — don't accumulate

The `replace_all` approach for converting `{:session-update #(assoc %` to `{:root-state-update (session-update #(assoc %` left multiline `#(assoc % ...)` forms with broken parens because the tool couldn't see the closing context. Running paren repair once after all replacements then mis-closed the anonymous functions. The better approach: replace in smaller batches and repair after each, or manually convert the multiline forms.

## 2026-03-23 - Effect migration works best by exhaustive audit then batch conversion

### λ Audit all direct mutations first, classify as strong-candidate vs acceptable, then convert in one pass

The agent-core effect migration was most efficient when preceded by a thorough audit that classified every direct `agent/` call as either a strong dispatch candidate or an acceptable runtime boundary call. That gave a clear scope (6 functions, 9 mutations) and avoided incremental discovery during implementation. The acceptable calls (executor streaming, infrastructure setup, effect executor internals) were identified once and left alone.

---

## 2026-03-22 - Replay classification belongs with handler intent, not only event-name convention (SUPERSEDED)

### λ Replay semantics get clearer when handler registration declares them directly

A static event-type table is a useful bridge, but it leaves replay meaning adjacent to the behavior instead of attached to it. Moving replay class onto handler registration makes the architectural intent local to the mutation boundary:
- `:replayable` for canonical mutations intentionally admitted to replay
- `:projection` for runtime/telemetry projection writes
- `:diagnostic` for retained history without replay guarantee

That makes the replay contract easier to review during handler changes and reduces the chance that event naming drifts away from replay semantics.

A useful next refinement is to admit replayable events by small coherent families instead of one-offs. Expanding from `set-session-name` to a compact session-config family worked well because the handlers are still simple session-state transforms with low semantic ambiguity.

Once replay classification exists, a replayable-only read model is worth exposing explicitly. Otherwise every query consumer has to re-filter the full dispatch log and duplicate the replay-substrate boundary in UI or tooling code.

A second useful follow-on is to pair that read model with an explicit replay helper. A queryable replay subset without a canonical way to replay it leaves every caller reinventing re-dispatch semantics. The cleaner split is:
- resolvers expose replayable retained entries
- dispatch owns replay of those entries with `:replaying? true`
- session core provides the context-shaped entrypoint for replaying them into canonical state

Once replay is executable, retention policy becomes architectural rather than incidental. If replayable and non-replayable entries share one bounded log, the log should prefer evicting `:projection` / `:diagnostic` entries first. Otherwise the system can claim a replay substrate while allowing projection noise to evict the very entries that substrate depends on.

An even simpler next step is to stop making the two classes compete in one store at all. A separate bounded replayable-only log removes mixed-priority trimming logic and makes the replay substrate contract more legible: replay reads from the replay log, observability reads from the mixed log.

### λ Keep fallback classification only as migration scaffolding

Once handler registration can declare replay class, the older event-type mapping should be treated as compatibility scaffolding, not the destination. The simpler end-state rule is:
- handler registration declares replay class explicitly
- retained log records that class
- replay consumes only entries classified `:replayable`

Reducing the fallback tables to empty compatibility scaffolding is a useful cleanup checkpoint: once the meaningful production families are annotated, an unclassified handler should default to `:diagnostic` instead of silently inheriting replay semantics from naming convention.

The next useful cleanup after that is to annotate obvious non-replayable production families explicitly too, especially effect-only runtime/orchestration handlers. Even when the default is already `:diagnostic`, explicit annotation still improves reviewability because replay intent is visible at the registration site instead of inferred from absence.

## 2026-03-21 - State unification gets easier once the remaining non-canonical state is classified by role

### λ The useful question is not “what atoms are left?” but “which remaining state is runtime handle, projection, or true canonical domain state?”

After converging most session-visible mutable state into the canonical `:state*` root, the remaining external state stopped being one homogenous cleanup bucket. The more durable framing is:
- runtime handles stay external
- runtime-visible/queryable state should be projected or hosted canonically
- subsystem-local transport/control state may remain separate if it is truly boundary-local

That classification makes next steps smaller and prevents trying to force opaque control objects into the same storage model as queryable session state.

### λ RPC transport state should be split by connection-local policy versus duplicated session truth

A useful audit result in `rpc.clj` is that most of the transport atom is not architectural debt at all. It is legitimate per-connection state:
- handshake readiness
- in-flight request bookkeeping
- event sequencing
- worker/thread ownership
- subscription preferences
- route-lock enforcement

The meaningful smell is the part that duplicates session truth. In the current transport atom, that was primarily `:pending-login`, because canonical OAuth pending-login projection already exists in the session root. Converging `login_begin`, `login_complete`, and manual `/login` continuation on canonical oauth projection proved the narrower rule:
- keep connection-local transport coordination external
- project diagnostics if needed
- converge only the fields that duplicate canonical runtime-visible state
- remove the duplicate source of truth before reconsidering the rest of the transport boundary

### λ The next dispatch migration family should be chosen by behavioral value and log volume, not just by which setter remains direct

The adjacent-namespace audit showed two different kinds of remaining direct canonical writes:
- low-frequency runtime-visible projections such as nREPL runtime metadata
- high-volume executor/telemetry plumbing such as turn context, provider captures, tool-call attempts, and tool-output stats

Only the first group is a strong immediate dispatch candidate. The second group is behavior-significant but would flood the dispatch event log unless logging/replay policy becomes more selective. Migrating canonical nREPL runtime metadata through dispatch validated the rule in practice: low-frequency lifecycle-visible projection state is a good dispatch fit and gains useful event-log visibility with little downside.

The practical rule is:
- move low-frequency, queryable projection state into dispatch first
- defer high-churn telemetry families until the event-log boundary can absorb them intentionally

### λ RPC transport state and extension-owned run state are the highest-signal remaining convergence candidates

The most meaningful remaining state outside the canonical session root is not local scratch atoms. It is:
- RPC transport loop state that partly overlaps with session-visible behavior
- extension-owned run/progress registries that may need canonical/queryable projection
- workflow runtime that now sits adjacent to canonical background-job projections

These surfaces are worth reviewing next because they are both observable and architecturally significant.

## 2026-03-19 - Dispatch migration works best by mutation family, not by individual setter (commits `788b63a`, `f942dcc`, `40c81cb`, `6775102`, `dbe8d07`, `601e047`, `3cd0de2`)

### λ Migrate coherent behaviour clusters, then record the new boundary explicitly

The dispatch pipeline broadened cleanly when changes were grouped by cohesive behaviour families:
- config flags
- model/thinking
- session metadata (name/worktree/cache breakpoints)
- system prompt recomposition
- prompt contribution mutations
- active tools

Trying to migrate one setter at a time risks losing the behaviour boundary and scattering tiny commits that are hard to reason about. Migrating a family lets tests prove the whole cluster still behaves coherently and makes STATE/PLAN updates easier because the new architectural boundary is obvious.

### λ Pure handler `:return` payloads are necessary for command-style mutations

Prompt contribution mutations return domain payloads like `{:registered? ...}` / `{:updated? ...}` / `{:removed? ...}`. Once they move into the pure/effects model, the dispatch layer needs to preserve a return channel distinct from `:session-update` and `:effects`. Without `:return`, migrating command-style mutations would force awkward post-dispatch recomputation at the call site.

### λ Queryable event log turns the pipeline from abstraction into capability

The event log became materially more useful once exposed via EQL. Before that, it was mainly an internal debugging aid. After exposing `:psi.agent-session/dispatch-event-log*`, the pipeline became introspectable from the same session root query surface as the rest of the system. That is the first concrete step toward replay/time-travel being a user-visible capability rather than just an implementation aspiration.

---

## 2026-03-19 - Dispatch pipeline: start with the bridge, not the destination (commits `1ec0f94`, `3196165`, `11bcf5a`)

### λ Introduce dispatch as a thin passthrough first, then thicken incrementally

The architecture doc describes a full pipeline: interceptor chain → pure handlers → effects-as-data → event log. Implementing all of that at once would require touching every state mutation in the system simultaneously.

Instead: create `dispatch!` as the single coordination point that just calls handlers directly (same behavior as before). Then register each statechart action as a named handler. `make-actions-fn` shrinks from 90 lines of case dispatch to 3 lines delegating to `dispatch!`. The pipeline shape exists but the pipeline is a passthrough.

This means interceptors, logging, and purity can be added one at a time without any behavioral change to the existing system. Each step is independently verifiable.

### λ Global handler registry needs test isolation strategy

`defonce` + global atom for handler registry means tests share state. The `clear-handlers!` escape hatch must be used in test fixtures. Better: make the registry per-context (on the ctx map) rather than global. Deferred to a later step to keep this change minimal.

### λ Spec before code clarifies the migration boundary

Writing `event-dispatch.allium` before any code forced explicit decisions about: what events exist, what the interceptor stack ordering is, how effects feed back as events, and what the migration path looks like (`dispatch_pipeline_active` flag). The spec then guided the code directly — the handler registration pattern matches the spec's `EventHandler` entity.

---

## 2026-03-19 - UI shim removal finishes cleanly when naming and implementation collapse separately (commits `6c8d90a`, `45f09c7`, `2d33693`, `f3a910c`, `99a8db9`, `460c0e9`)

### λ First collapse duplicated implementation, then collapse compatibility naming

Phase 4 only finished cleanly once the work was split into two distinct reductions:
- centralize all UI mutation behavior in one implementation (`psi.ui.state`)
- then remove or rename compatibility surfaces (`psi.tui.extension-ui`, `:ui-state-atom`) afterward

Trying to do both at once made it easy to lose track of whether a change was semantic or just naming. The reliable sequence was:
1. remove session-context storage of the shim
2. migrate readers/writers to canonical/shared state
3. collapse duplicated implementation namespaces into wrappers
4. finally rename the last bootstrap handle to reflect its real role

### λ Compatibility wrappers are useful only when they stop containing logic

`psi.tui.extension-ui` became much safer once it was reduced to a pure forwarding wrapper over `psi.ui.state`. The reusable lesson is:
- duplicated compatibility namespaces accumulate behavioral drift
- wrapper namespaces are acceptable when they preserve call shape only
- once the wrapper contains real mutation logic, it becomes a second subsystem and blocks cleanup

### λ Renaming the final handle matters because names preserve architecture

The last bootstrap surface still needed to pass a shared UI-state handle into the TUI, but the name `:ui-state-atom` kept implying a compatibility shim. Renaming it to `:ui-state*` clarified that it is:
- shared mutable UI state used intentionally by the TUI bootstrap
- not session-context storage
- not a legacy escape hatch that callers should continue spreading

The practical rule: once a migration is mechanically complete, rename surviving handles so future work does not rebuild the old architecture by habit.

## 2026-03-18 - Circular Dependencies Resolved via Architectural Dependency Inversion (commit `f8a7116`)

### λ The exact circular dependency path: introspection ↔ agent-session at import level

The circular dependency that prevented kaocha from loading all test namespaces was:
```
introspection.core → (direct imports) → agent-session.{core,resolvers}
agent-session.main → (function call) → introspection.core/register-resolvers!
```

When kaocha loaded both `psi.introspection.agent-session-test` and `psi.agent-session.main-test`, it triggered this cycle.

### λ Solution: Common dependency extraction (proper architectural fix)

**Better approach than dynamic loading**: Extract common bootstrap component:
- Created `psi/system-bootstrap` component that coordinates all resolver domains
- Clean dependency graph: `system-bootstrap → all domains`, `agent-session + introspection → system-bootstrap`
- Single Responsibility: bootstrap owns "wire up the whole system"
- Explicit compile-time dependencies instead of runtime resolution

Key insight: When A → B and B → A, extract common C where **A → C ← B**. This is dependency inversion via common abstraction extraction.

### λ Verification pattern for circular dependency fixes

1. Test individual namespace loading: `(require 'ns1 'ns2)` 
2. Test namespace combination that previously failed
3. Verify functionality still works end-to-end
4. Update any tests that depended on removed functions (architectural changes)

### λ Architectural pattern: Common Dependency Extraction for Circular Dependencies

**Pattern**: When component A needs component B, and B needs A, extract common component C:

```
BEFORE (circular):     AFTER (acyclic):
A → B                  A → C
B → A                  B → C
                       C → A + B
```

**Implementation**:
- Create `system-bootstrap` component that imports all resolver domains
- `agent-session` + `introspection` both depend on `system-bootstrap` 
- `system-bootstrap` coordinates registration without either depending on the other
- Clean, explicit, testable architecture

**Benefits over dynamic loading**:
- ✅ Explicit compile-time dependencies (vs. runtime resolution)
- ✅ Single place for system-wide coordination  
- ✅ Testable registration logic
- ✅ Clear architectural intent

---

## 2026-03-17 - Live query graph reloads must be treated as a separate checkpoint from code reloads (commit `b84f60f`)

### λ Re-registering resolvers in one JVM does not prove that the live app-query graph has ingested them

During the Anthropic investigation, the new turn-id lookup resolvers passed focused tests and local re-registration code ran successfully, yet `app-query-tool` still reported the old resolver set and root-queryable attrs. The reusable lesson is:
- code reload success is not the same as live graph reload success
- query-surface debugging needs an explicit checkpoint in the active runtime that serves the user-facing graph
- when the live graph still reports the old surface, assume a stale or different JVM until proven otherwise

### λ Future debugging guidance should encode the real reload workflow, not the idealized one

The session-debug skill needed to capture the actual sequence that emerged from this investigation:
- reload changed namespaces
- rebuild the query env after resolver registration changes
- verify the live graph advertises the new attrs/resolvers
- if it does not, restart the actual running psi process

The important rule is that debugging instructions should preserve the failure mode we just hit, so future loops avoid mistaking local REPL work for a live-session graph update.

## 2026-03-17 - Narrow lookup surfaces beat giant capture dumps when debugging one failing provider turn (commit `2413557`)

### λ Provider telemetry needs a precise lookup path once retention is deep enough to make full-buffer queries unwieldy

The Anthropic investigation reached a point where the relevant failing turn was definitely retained, but querying the entire provider request/reply capture tail through the live graph produced unusably large results. The reusable rule is:
- broad capture buffers are good for retention and timelines
- failing-turn investigation needs a narrow lookup surface keyed by a stable identifier like turn id
- exact lookup resolvers reduce both tool output volume and cognitive load during debugging

### λ Code-level queryability and live-session queryability are separate states whenever a long-running runtime owns the active graph

The new turn-id lookup resolvers worked immediately in focused tests and local registration paths, yet the active `app-query-tool` graph still did not advertise them after in-repo reload attempts. The practical lesson is:
- proving a resolver in unit tests only establishes repository truth
- proving it in the live graph requires the actual long-running runtime to ingest the updated registrations and rebuild its env
- when those differ, treat "repo updated" and "live query surface updated" as separate checkpoints in the debugging loop

## 2026-03-17 - Join-map graph discovery and dedicated error retention matter as much as the original error capture (commit `231477a`)

### λ A general rolling provider-event buffer is the wrong retention tier for rare provider failures when normal traffic is dominated by delta events

The live investigation showed that Anthropic error replies were captured correctly and then disappeared after subsequent OpenAI debugging turns. The failure was not explicit clearing; it was eviction by a shared capped buffer full of high-volume stream deltas. The reusable rule is:
- keep the broad provider event stream for nominal telemetry
- retain provider `:error` events in a dedicated error buffer with its own cap
- treat debugging-critical failures as a separate memory tier from routine stream chatter

### λ API error enrichment should join assistant-visible failures back to provider captures, but only one logical error should survive that join

Once assistant-side error messages and provider-side error captures both existed, the same Anthropic failure appeared twice in `:psi.agent-session/api-errors`. The practical lesson is:
- enrich the assistant-visible error from provider captures using a stable key like request id
- prefer the enriched version because it keeps the provider payload attached
- deduplicate the assistant/provider views so downstream diagnostics reason about one failure, not two parallel representations

### λ Graph discovery that ignores join-map outputs under-advertises real query surface area even when the attrs are fully queryable

The new provider error attrs were live and queryable, but they did not appear in `:psi.graph/root-queryable-attrs` because reachability only considered flat keyword outputs. The reusable lesson is:
- introspection/discovery logic must flatten nested resolver IO shapes, not just top-level keywords
- otherwise the live graph becomes richer than the advertised graph and tool-guided exploration drifts from reality
- join-map outputs are part of the graph contract, not a special case to omit from discovery

## 2026-03-17 - Error capture is only useful when provider diagnostics survive normalization and retention pressure (commit `0bc6fb5`)

### λ Provider adapters should preserve the raw error payload even when they also emit a normalized user-facing error string

The reproduced Anthropic 400 still surfaced to the session as a compact `Error (status 400) [request-id ...]` string, which is good for UI display but too lossy for root-cause work. The reusable rule is:
- keep the normalized summary string for user-visible flow
- also retain raw headers, raw body text, and parsed structured body when available
- let later diagnostics decide how much detail to inspect rather than forcing the adapter to collapse the evidence up front

### λ Request-id extraction should follow the canonical emitted error shape, not only historical transport formatting

Once provider adapters started emitting normalized `... [request-id req_xxx]` suffixes, the old request-id parser that expected raw header-map text stopped seeing the identifier even though the id was still present. The practical lesson is:
- when an error surface is normalized intentionally
- downstream diagnostics must parse the normalized form as a first-class contract
- compatibility with older raw formats can remain as fallback, but the canonical emitted shape should lead

### λ Provider capture tails must be sized for live debugging, not just for nominal streaming volume

The live session had already rotated far enough that the earlier Anthropic failure body was no longer recoverable from the current visible reply tail. That means retention limits are part of the debugging surface, not just a memory footnote. The reusable rule is:
- size provider request/reply capture history for the realistic investigation loop
- assume several later turns may happen before someone inspects the failing provider event
- if a captured failure falls off the tail too quickly, the system effectively did not remember it

## 2026-03-17 - Canonical-root cleanup is easiest when tests and docs converge on compatibility adapters as views, not storage (commit `d037818`)

### λ A refactor is not really finished until tests stop teaching the old shape

Even after production code converged on canonical root state, several tests still built synthetic contexts around `:session-data-atom` and similar legacy fields. Adding a small canonical-root fixture helper (`make-session-ctx`, plus focused state update helpers) created a better default for future tests and reduced the chance that new regressions would silently re-entrench the pre-refactor architecture.

### λ Compatibility adapters need explicit documentation or future edits will mistake them for primary state holders

Once `:session-data-atom` and `:ui-state-atom` become adapter-backed views, comments that still describe them as the architecture's real storage become actively misleading. Updating docs/comments in the same refactor pass matters because future changes often follow prose before code. The useful rule is: when a compatibility shim remains by design, document it as a view over canonical state, not as an independent mutable source of truth.

### λ A small test-support namespace is a high-leverage way to lock in a new architectural shape

The most efficient post-refactor cleanup was not converting every old test immediately. It was introducing one helper namespace that encodes the new shape and converting representative tests first. That gives the repository a preferred pattern future tests can copy, which is often enough to bend the rest of the suite gradually toward the new architecture.

## 2026-03-17 - Canonical state can absorb UI, recursion, nREPL metadata, and OAuth projections if adapters preserve existing call shapes (commit `a110370`)

### λ A compatibility adapter is the clean bridge when an existing subsystem API is atom-shaped but its source of truth should move into canonical state

The most effective move for UI state and background jobs was not rewriting every consumer at once. It was to keep the public call shape stable and back it with a state-root adapter. That let existing functions keep accepting an atom-like value while the source of truth moved under canonical root paths. The pattern is especially useful when the subsystem API is already stable and widely used.

### λ Recursion state is easier to host in canonical runtime state when the recursion context is treated as an access strategy, not as a mandatory storage container

Adding a hosted recursion context worked because `RecursionContext` was allowed to represent either:
- standalone storage via `:state-atom`
- hosted storage via `:host-ctx` + `:host-path`

That kept the recursion API stable while making canonical hosting possible. The useful lesson is to separate the controller interface from the storage location.

### λ OAuth and nREPL are best split into runtime-visible state versus opaque runtime handles

For both subsystems, the valuable distinction is:
- runtime-visible/queryable state belongs in canonical root
- opaque/effectful objects stay outside it

For nREPL, endpoint metadata belongs in canonical state while the actual server object does not. For OAuth, authenticated-provider/pending-login/last-login metadata belongs in canonical state while credential stores, token refresh logic, and provider implementations remain runtime-owned.

## 2026-03-17 - Canonical root-state migrations are safest when code first converges on shared path helpers before removing old shapes completely (commit `3097239`)

### λ A single mutable-root refactor goes smoother when the first stable API is path-based access, not immediate atom elimination

The useful pivot was not "delete every atom-shaped field at once". The stable move was to introduce one canonical `:state*` root and then expose small shared helpers from `core.clj`:
- read state by path
- assoc/update state by path
- expose named path lookup through one function

That gave `runtime.clj`, `executor.clj`, and `resolvers.clj` one migration surface and avoided pushing raw nested-path knowledge into every caller.

### λ Runtime handles should remain outside the canonical mutable root unless they are true queryable state

The simplification target is one canonical mutable state root, not one storage location for every runtime object. Things like agent-core contexts, extension registries, UI integration hooks, OAuth store integrations, and nREPL/server handles are better treated as runtime handles. Trying to force them into the canonical state tree would mix queryable domain state with opaque side-effecting process objects.

### λ Resolver migration is an especially good proving ground for state unification

Resolvers touch almost every observable session/runtime field but mostly read rather than mutate. That makes them a strong convergence surface:
- if the canonical root can satisfy resolver reads cleanly
- the external query contract stays stable
- and many stale internal storage assumptions are exposed quickly

In this pass, moving resolver reads for session data, journal state, provider captures, tool telemetry, turn context, and background jobs was a high-signal way to validate the new root-state model without changing the public EQL surface.

## 2026-03-17 - Split UI-specific session creation from shared runtime bootstrap in entrypoint code (commit `2368583`)

### λ Context creation and runtime bootstrap are different responsibilities even when they happen back-to-back

`main.clj` had one helper that both created the session context and bootstrapped the live runtime. That made `bootstrap-runtime-session!` appear transport-neutral while it still owned `:ui-type` and event-queue concerns. The better shape is:
- context creation decides UI-specific inputs and establishes the first session
- bootstrap enriches an existing context with prompts, tools, extensions, memory sync, and startup rehydrate

When those stages are split, each runtime entrypoint can choose its UI explicitly while still reusing one bootstrap path.

### λ A shared helper is simpler when callers provide the already-specialized context instead of passing specialization knobs through it

The previous helper accepted `:ui-type`, `:event-queue`, and session config alongside bootstrap concerns. That widened the helper's responsibility and made UI choice an ambient option inside shared setup. A cleaner rule is:
- if a concern changes how the context is constructed
- make callers decide it before invoking the shared bootstrap
- keep the shared bootstrap operating on an already-created `ctx`

That reduces option threading and makes future UI additions cheaper.

### λ Entry-point refactors are safer when the split follows the existing runtime order rather than inventing a new abstraction layer

This change stayed small because it preserved the existing sequence:
- resolve model
- create context
- bootstrap runtime
- run UI/transport loop

The refactor only cut the boundary between steps two and three. The useful lesson is that entrypoint simplification often works best by exposing the real phase boundary that already exists, rather than by introducing a larger orchestration abstraction first.

## 2026-03-17 - Canonical root-state migration works best when runtime-visible mutable state and runtime handles are split intentionally (commit `3097239`)

### λ A single mutable root becomes tractable when the migration boundary is runtime-visible state, not every object in the runtime

The successful convergence point was not “put absolutely everything in one atom”. The durable simplification came from moving queryable/runtime-visible mutable state into one canonical root while leaving effectful handles outside it. The reusable rule is:
- canonical root owns state that should be inspected, queried, or updated atomically
- runtime handles own opaque integrations like provider clients, queues, registries, and live servers
- do not force non-serializable control objects into the same state model just to satisfy a slogan

### λ Root-state migrations are safer when old atom-oriented APIs are preserved as adapters during convergence

The agent-session refactor did not require every consumer to change shape at once. Instead, path-based helpers in `core.clj` became the new source of truth and atom-like views were retained where callers still expected them. The practical lesson is:
- introduce canonical path helpers first
- migrate core read/write paths onto those helpers
- keep adapters for legacy atom-shaped surfaces until the surrounding code catches up

That preserves behavior while letting the architecture change underneath.

### λ Specs become more useful when they name the intended state boundary before the code fully converges on it

Adding `spec/system-context-unification.allium` first gave the implementation pass a stable target: one canonical mutable root for runtime-visible state, compatibility projections allowed during migration, runtime handles explicitly out of scope. The reusable lesson is:
- if the change is architectural, capture the target boundary in spec first
- then use code changes to converge on that target incrementally
- use the spec to decide what may stay as a compatibility shim and what must become canonical

## 2026-03-17 - Anthropic replay failures can come from empty persisted assistant turns, not just from role alternation or tool pairing (commit `8e5da2d`)

### λ Replayed conversation builders should skip structurally empty assistant turns instead of faithfully serializing them

The live Anthropic request capture showed a failure shape that higher-level request diagnostics did not flag: an assistant history entry with no text, no thinking blocks, and no tool calls was being rebuilt as `{:role "assistant" :content [{:type "text" :text ""}]}`. That shape preserves transcript structure mechanically, but it violates Anthropic's wire contract. The reusable rule is:
- when rebuilding provider history from persisted transcript messages
- treat structurally empty assistant turns as absent history, not as empty text blocks
- validate message meaning, not just role alternation
