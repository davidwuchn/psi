# Architecture

```text
Engine (statecharts) → substrate
Query  (EQL/Pathom3) → capability surface
AI     (providers)   → streaming LLM layer
Agent  (statechart)  → per-turn lifecycle
TUI    (charm.clj)   → Elm Architecture terminal UI
```

## Components

| Component       | Role                                              |
|-----------------|---------------------------------------------------|
| `engine`        | Statechart infrastructure, system state           |
| `query`         | Pathom3 EQL registry, `query-in`                  |
| `ai`            | Provider streaming, model registry (Anthropic, OpenAI) |
| `agent-core`    | LLM agent lifecycle statechart + EQL resolvers    |
| `agent-session` | Full coding-agent session: tools, extensions, OAuth, TUI |
| `history`       | Git log resolvers                                 |
| `introspection` | Engine queries itself — self-describing graph     |
| `tui`           | JLine3 + charm.clj terminal UI, extension UI points |

## EQL Introspection Tips

- Query only attributes that exist in the graph; unknown attrs can cause the whole `app-query-tool` request to fail.
- For the active system prompt, use:
  - `[:psi.agent-session/system-prompt]`
- For runtime UI surface detection (extension/UI branching), use:
  - `[:psi.agent-session/ui-type]`  ; `:console` | `:tui` | `:emacs`
- For prompt sizing (chars + estimated tokens), use:
  - `[{:psi.agent-session/request-shape [:psi.request-shape/system-prompt-chars :psi.request-shape/estimated-tokens :psi.request-shape/total-chars]}]`
- Anthropic prompt caching is session policy projected into request shape:
  - session state stores `:cache-breakpoints` such as `:system` and `:tools`
  - executor projects those into conversation `:system-prompt-blocks` / tool `:cache-control`
  - the Anthropic provider emits `cache_control` only for supported directives (`{:type :ephemeral}`)
- Avoid non-existent attrs like `:psi.agent-session/prompt`, `:psi.agent-session/instructions`, `:psi.agent-session/messages` unless resolvers are added for them.

## State boundary: canonical root vs runtime handles

`:state*` owns queryable session truth — one atom, one root. Everything
else on `ctx` is a handle to a running subsystem.

**Principle:** when a subsystem has observable status worth querying
(OAuth login state, nREPL endpoint, workflow progress), that status is
projected into `:state*` as canonical data through dispatch. The handle
itself stays external.

A runtime handle is any object that:
- owns internal mutable lifecycle (atoms, watches, threads)
- performs side-effecting I/O (disk, network, locks)
- is infrastructure machinery (compiled envs, registries, engines)

Current runtime handles on ctx:

| Handle | What it is | Projection in `:state*` |
|--------|-----------|------------------------|
| `:agent-ctx` | agent-core loop, queues, event stream | turn context, provider captures |
| extension registry | loaded extensions, flags, event bus | extension prompt contributions |
| workflow registry | workflow instances, pump thread, statechart env | background jobs, workflow public data |
| `:oauth-ctx` | credential store, token refresh, file locks | authenticated providers, login status |
| nREPL server | live server object | `[:runtime :nrepl]` endpoint metadata |
| query context | Pathom3 registry, compiled env | (is the query infrastructure itself) |
| engine context | statechart engines, system state, transition log | (is the engine infrastructure itself) |
| memory context | memory stores, store registry | (is the memory infrastructure itself) |

These are all the same kind of thing: opaque subsystems with their own
internal mutable lifecycle. They are not queryable domain state.

## Dispatch migration status

- `dispatch!` is active and queryable via the retained dispatch event log.
- Current dispatch ownership is partial, not full-system.
- Migrated families include:
  - statechart action handlers
  - auto flags / ui type
  - model / thinking
  - session name / worktree / cache breakpoints
  - active tools
  - system prompt recomposition
  - prompt contribution mutations
  - startup/bootstrap lifecycle + summary writes
  - context usage / extension prompt telemetry / runtime prompt retargeting
  - rpc trace / oauth projection / recursion projection setters
  - extension UI mutations (widget/widget-spec/status/notify/dialog + renderer registration)
- Remaining direct mutation pockets still exist outside those migrated slices.
- Treat `dispatch_pipeline_active` as "dispatch active for migrated slices"
  during migration, not yet "all mutations converge through dispatch".

## Dispatch sequencing contract

Current `agent-session` dispatch sequencing for pure handler results is:
1. handler computes a pure result
2. apply writes state and surfaces declared effects onto interceptor context
3. validate checks the post-apply interceptor context
4. replay trimming may suppress effects
5. effects execute last

Current scaffold semantics:
- validation is post-apply, not pre-commit
- invalid validation suppresses effects but does not roll back already-applied state
- replay suppresses effects but preserves state application and return values

Current default interceptor ids:
- `:permission`
- `:log`
- `:statechart`
- `:handler`
- `:effects`
- `:trim-effects-on-replay`
- `:validate`
- `:apply`

Because after fns run in reverse order, the effective after-order is:
- `:apply -> :validate -> :trim-effects-on-replay -> :effects`

## Dispatch event-log observability

The retained dispatch log now exposes more architectural debugging signal than
just event type and timing. Current log entries include:
- event identity:
  - event type
  - event data
  - origin
  - ext id
- control flow:
  - blocked?
  - block reason
  - replaying?
  - statechart-claimed?
  - validation error
- pure-result/effect shape:
  - pure-result kind (`:db`, `:root-state-update`, `:session-update`, etc.)
  - declared effects
  - applied effects
- bounded state summaries:
  - db-summary-before
  - db-summary-after
- timing:
  - timestamp
  - duration-ms

Retention/volume tradeoff:
- the log keeps bounded summaries rather than full root-state snapshots
- all entries are replay-safe by construction: replay suppresses effects and applies
  only pure state transforms, so no classification is needed to determine safety
- the single bounded log serves both observability/debugging and replay substrate use

## Conforming vertical slice — manual compaction

The first explicit conforming vertical slice target is manual compaction.

Current intended slice flow:
1. public API entry via `manual-compact-in!`
2. dispatch-routed statechart transition via `:session/compact-start`
3. synchronous dispatch-owned compaction execution via `:session/manual-compaction-execute`
4. dispatch-visible session-data cleanup via `:session/compaction-finished`
5. dispatch-routed statechart completion via `:session/compact-done`

Current intentional boundary:
- the compaction execution step itself is still synchronous so the caller can
  receive the compaction result directly
- the surrounding control flow is dispatch-visible and statechart-visible

Current proof surface for the slice:
- focused core tests now prove dispatch-visible event sequences for:
  - default stub compaction
  - custom compaction function
  - extension-cancelled compaction
  - extension-supplied compaction result
- the dispatch event log is the primary slice observability surface; no bespoke
  local-only debug hooks are required to understand the slice flow

This slice is the proving ground for broader convergence from partial dispatch
ownership toward more reference-architecture-conforming vertical behavior.

## Next vertical slice — prompt / turn lifecycle

The next target slice after manual compaction is prompt / turn lifecycle.

Current first convergence step:
1. public API entry via `prompt-in!`
2. dispatch-visible prompt submission via `:session/prompt-submit`
3. dispatch-routed statechart transition via `:session/prompt`
4. dispatch-owned execution step via `:session/prompt-execute`
5. prompt-execute emits runtime effects for:
   - agent start-loop with the submitted user message
   - background-job reconciliation / terminal emission

Current intentional boundary:
- prompt journal append is now dispatch-visible, but deeper turn streaming behavior
  still lives across executor/turn-statechart paths and is the next major
  candidate for broader dispatch convergence
- tool execution is now dispatch-owned end-to-end:
  - `:session/tool-run` composes two dispatch-owned phases:
    1. `:session/tool-execute-prepared` — may run concurrently, emits start/executing
       lifecycle, performs runtime tool execution through `:runtime/tool-execute`, and
       returns a shaped result without final recording
    2. `:session/tool-record-result` — records the final tool result in deterministic
       tool-call order, including lifecycle projection, telemetry, journal append,
       and agent-core tool-result recording
  - the executor now owns only batch scheduling and deterministic ordered recording,
    not tool transaction semantics

## Roadmap

- ✓ Engine + Query substrate
- ✓ AI provider layer (Anthropic, OpenAI)
- ✓ Agent core loop
- ✓ Coding-agent session
- ✓ TUI (charm.clj / JLine3)
- ✓ Extension system + Extension UI
- ✓ OAuth (PKCE, Anthropic, OpenAI)
- ✓ Git history resolvers
- ✓ Session persistence
- ◇ HTTP API (openapi + martian)
