# Plan

Ordered steps toward PSI COMPLETE.

---

## Reconciled status vs `STATE.md` and `../psi-main/architecture.md`

Current repo status is no longer “build the core architecture.”

The core reference-architecture substrate is now materially present:
- ✓ canonical session-root state owns most runtime-visible session truth
- ✓ dispatch has an explicit interceptor/effect pipeline
- ✓ statechart participation is an explicit dispatch boundary
- ✓ the full single tool-call transaction now enters through a dispatch-owned runtime boundary
- ✓ all dispatch events are replayable (effects suppressed, state applied)
- ✓ all public session functions route agent-core mutations through dispatch-owned effects
- ✓ dispatch has one pure-result shape: `:root-state-update` with `session-update` wrapper
- ✓ workflow/query-first read models are materially stronger than before
- ✓ effect boundary is schema-validated in dev/test (gated on `*assert*`)

So the repo is now in **late convergence / simplification / boundary sharpening**, not early architectural migration.

## Active priorities

1. **Decompose `core.clj`** (4089 → 820 lines, target ≤800 per file) ✓
   - ✓ `state-accessors.clj` extracted (197 lines, 12 readers + 14 mutators)
   - ✓ `dispatch-effects.clj` extracted (207 lines, defmulti effect executor)
   - ✓ `dispatch-handlers.clj` extracted (1015 lines, all handler registration + helpers)
   - ✓ `bootstrap.clj` extracted (137 lines, startup orchestration)
   - ✓ `session-lifecycle.clj` extracted (151 lines, new/resume/fork)
   - ✓ `mutations.clj` extracted (626 lines, all 34 mutations calling dispatch directly)
   - ✓ `session-state.clj` extracted as leaf module; breaks all circular deps
   - ✓ `tool-plan.clj` extracted (247 lines, runtime tool executor + data-driven tool plan)
   - ✓ `background-job-runtime.clj` extracted (209 lines, job tracking/reconciliation/emit/list/cancel)
   - ✓ all architectural requiring-resolve eliminated (core 10→0, mutations 15→0)
   - ✓ all 16 `def` re-exports eliminated; all 21 thin `*-in!` wrappers deleted
   - ✓ forward declarations reduced from 11 to 4 (remaining: extension runtime coupling)
   - ✓ `extension-runtime.clj` extracted (189 lines): loading, messaging, prompt delivery via ctx capability keys
   - ✓ core.clj reduced to 629 lines (4089 → 629, −85%); forward declarations reduced to 1 (`execute-compaction-in!`)

2. **Document intentional external runtime handles** ✓
   - principle documented in `spec/system-context-unification.allium` and `doc/architecture.md`
   - one rule: `:state*` owns queryable session truth; everything else on ctx is a handle to a running subsystem whose observable state is projected into `:state*` through dispatch

3. **Broaden query-first workflow/runtime read models**
   - prefer workflow public-data/display surfaces over local mirrors and ad hoc formatting
   - treat the shared workflow display helper path as the default convention for new consumers

4. **Reassess extension isolation after the above**
   - revisit which remaining extension/runtime handles should stay external
   - tighten permissions or move boundaries only after the simpler convergence work above

5. - change injected prompt time to be session creation time for cache stability
   - add time instant to each request/response so agent can reason about session time
   - set cache breakpoints on last three messages

7. ✓ **Lambda mode for system prompt** (default)
   - ✓ nucleus prelude, configurable at project/system scope
   - ✓ lambda-compiled identity, guidelines, tool descriptions, graph discovery
   - ✓ dual tool descriptions (built-in guarantee both; extension fallback)
   - ✓ lambda skills rendering with `lambda:` frontmatter support
   - ✓ prompt ordering: preamble → skills → contributions → context files → metadata
   - ✓ session/project/system config resolution (session > project > system)
   - ✓ mode switchable at runtime via dispatch, triggers recomposition
   - ✓ EQL queryable via `:psi.agent-session/prompt-mode`
   - spec: `spec/lambda-mode.allium`

8. - configuration has system, project or session scope. config setters need to take the scope.
9. ✓ rename subagent-widget to agent
10. ✓ reverse agent/agent chaining dependency

## Current next implementation seam

Highest-leverage next work:
- broaden query-first workflow/read-model convergence
- reassess extension isolation boundaries

## Intentional external runtime handles

These currently fit the actual repo architecture and should remain outside `:state*` unless requirements change.

- `:agent-ctx`
  - live agent-core loop, queues, event stream, provider/tool runtime mechanics
- extension registry
  - loaded extension runtime registry state
- workflow registry
  - workflow runtime substrate and instance coordination
- oauth runtime/store integration via `:oauth-ctx`
  - secure credentials, provider impls, callback/token mechanics
- nREPL server handle
  - live server object; canonical state keeps only runtime-visible metadata
- memory / query / engine contexts
  - subsystem control objects and global runtime handles

## Completed work — intentionally compressed

Recently completed work that should now be treated as baseline rather than active plan detail:
- mutable-state audit and boundary classification
- RPC `:pending-login` convergence onto canonical oauth projection
- dispatch-owned low-frequency runtime-visible projection routing
- workflow-public progress/display convergence for key extensions
- canonical tool lifecycle event + read-model vertical slice
- clarified executed-tool count vocabulary
- dispatch-owned tool lifecycle side effects and tool-run transaction boundary
- documentation of shared workflow display helper conventions
- all agent-core mutations now dispatch-owned effects (~20 effect types)
- replay classification removed — all events replayable by default
- pure-result shapes unified to single `:root-state-update` with `session-update` wrapper
- duplicate tool/start progress emission fixed
- malli schemas added for effect inventory (34 types, multi-dispatch) and pure-result shape
- effect/pure-result schema validation wired into dispatch pipeline, gated on `*assert*`
- redundant wrappers removed (project-tool-lifecycle-progress!, apply-session-update-in!, tool-call-history resolver)
- effect executor extracted to open defmulti in `dispatch-effects.clj`
- state accessors extracted to `state-accessors.clj`
- dispatch handlers extracted to `dispatch-handlers.clj` via requiring-resolve
- bootstrap orchestration extracted to `bootstrap.clj`
- session lifecycle extracted to `session-lifecycle.clj`
- all 34 mutations extracted to `mutations.clj` calling dispatch directly (no core.clj dep)
- `session-state.clj` extracted as leaf state infrastructure; circular deps eliminated
- 12 callers converted from `*-in!` wrappers to direct dispatch
- 21 thin `*-in!` dispatch wrappers deleted; all callers use direct dispatch
- all 16 `def` re-exports of session-state eliminated; callers require session-state directly
- `tool-plan.clj` extracted (247 lines, runtime tool executor + data-driven tool plan)
- `background-job-runtime.clj` extracted (209 lines, job tracking/reconciliation/emit/list/cancel)
- core.clj reduced from 4089 to 820 lines (−80%), zero re-exports, zero wrappers, zero requiring-resolve
- lambda-mode system prompt with dual-mode build (lambda default, prose fallback)
- lambda skills rendering with `lambda:` frontmatter and compact notation
- prompt section ordering: skills + contributions before context files
- nREPL startup message routed to stderr for RPC protocol safety

See `STATE.md` and git history for the detailed record.

## Success shape

- canonical session-root truth remains the center of runtime-visible state
- dispatch has one result shape, one apply path, one event log
- replay is simple: all events replay, effects suppressed
- effect boundary is schema-validated
- query/public read models become the default path for consumers
- architecture docs describe the real system precisely
