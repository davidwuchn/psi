# State

Current truth about the Psi system.

---

## Components

| Component       | Status | Notes                                                      |
|-----------------|--------|------------------------------------------------------------|
| `ai`            | ✓      | Provider streaming, model registry, tested                 |
| `engine`        | ✓      | Statechart infra, system state, nullable ctx               |
| `query`         | ✓      | Pathom3 EQL registry, `query-in`, nullable ctx             |
| `agent-core`    | ✓      | LLM agent lifecycle statechart + EQL resolvers             |
| `history`       | ✓      | Git log resolvers, nullable git context                    |
| `introspection` | ✓      | Bridges engine + query, self-describing graph              |
| `tui`           | ✓      | charm.clj Elm Architecture, JLine3, extension UI state     |
| `emacs-ui`      | ✓      | emacs mode for psi                                         |
| `agent-session` | ✓      | Session ✓, extensions ✓, extension UI ✓, main REPL ✓, TUI ✓, OAuth ✓ |

## Architecture Progress

- ✓ Engine (statecharts) substrate
- ✓ Query (EQL/Pathom3) surface
- ✓ AI provider layer
- ✓ Agent core loop
- ✓ Git history resolvers
- ✓ Introspection (engine queries itself)
- ✓ Coding-agent session orchestration (agent-session component)
- ✓ Built-in tools (read, bash, edit, write, eql_query)
- ✓ Executor (bridges ai streaming → agent-core loop protocol)
- ✓ Turn statechart (per-turn streaming state, EQL queryable)
- ✓ Runnable entry point (`clojure -M:run`)
- ✓ TUI session (`--tui` flag) — charm.clj Elm Architecture, JLine3
- ✓ Extension system (Clojure extensions, loader, API, tool wrapping, EQL introspection)
- ✓ Extension UI (dialogs, widgets, status, notifications, render registry, EQL introspection)
- ✓ OAuth module (PKCE, callback server, credential store, provider registry, Anthropic)
- ✓ Session introspection hardening (Step 7a): messages-count, tool-call-count, start-time, current-time
- ✓ Graph emergence (Step 7): all 9 :psi.graph/* attrs queryable via eql_query from agent-session-ctx
- ✓ Memory backing-store extension point (Step 9a phase 1): provider protocol + registry (`psi.memory.store`) with in-memory default and `:psi.memory.store/*` EQL attrs
- ✓ Datalevin persistent memory provider (Step 9a phase 2): `psi.memory.datalevin` + write-through remember/recover/graph artifacts + activation-time hydration
- ✓ Memory runtime hardening (Step 9.5): CLI/env config surface, provider failure telemetry surfacing, explicit provider selection/fallback reporting, retention overrides, Datalevin schema migration hooks, operator docs
- ✓ OAuth wired into runtime command flow (`/login`, `/logout`)
- ✓ Session resolvers wired into global query graph
- ✓ Graph emergence from domain resolvers (`ai`, `history`, `agent-session`, `introspection`)
- ✗ AI COMPLETE
- ✓ RPC EDN surface (`--rpc-edn`)
- ✗ HTTP API surface

## Runnable

```bash
ANTHROPIC_API_KEY=sk-... clojure -M:run
clojure -M:run --model claude-3-5-sonnet
clojure -M:run --model gpt-4o --tui
PSI_MEMORY_STORE=datalevin clojure -M:run  # opt-in persistent memory store
clojure -M:run --memory-store datalevin --memory-store-db-dir /tmp/psi-memory.dtlv
clojure -M:run --memory-store datalevin --memory-store-fallback off
clojure -M:run --memory-retention-snapshots 500 --memory-retention-deltas 2000
clojure -M:run --rpc-edn                 # EDN-lines RPC mode (headless/programmatic)
clojure -M:run --nrepl                   # random port, printed at startup
clojure -M:run --nrepl 7888              # specific port
clojure -M:run --tui --nrepl             # TUI + nREPL for live introspection
```

## RPC EDN (Step 8)

Status: ✓ complete

Implemented operations: `handshake`, `ping`, `query_eql`, `prompt`, `steer`, `follow_up`, `abort`, `new_session`, `switch_session`, `fork`.

In-session commands: `/status`, `/history`, `/new`, `/help`, `/quit`, `/exit`, `/skills`, `/prompts`, `/skill:<name>`, plus extension commands

Built-in tools: `read`, `bash`, `edit`, `write`, `eql_query`

The `eql_query` tool enables in-session EQL introspection without nREPL:
```
eql_query(query: "[:psi.agent-session/phase :psi.agent-session/session-id]")
eql_query(query: "[{:psi.agent-session/stats [:total-messages :context-tokens]}]")
eql_query(query: "[:psi.tool/names :psi.skill/names]")
```

Canonical agent-session telemetry query path (direct top-level attrs):
```
eql_query(query: "[:psi.agent-session/messages-count]")
eql_query(query: "[:psi.agent-session/tool-call-count]")
eql_query(query: "[:psi.agent-session/start-time]")
eql_query(query: "[:psi.agent-session/current-time]")
eql_query(query: "[:psi.agent-session/messages-count :psi.agent-session/tool-call-count :psi.agent-session/start-time :psi.agent-session/current-time]")
```

Live verification (2026-03-01): all 5 queries above return successfully with no resolver error; counts return integers and both time attrs return `java.time.Instant`.

## Canonical Graph Attrs (Step 7)

All 9 required Step 7 graph attrs are queryable via `eql_query` (seeded from `:psi/agent-session-ctx`):

```clojure
[:psi.graph/resolver-count]    ;; integer — resolvers in global registry
[:psi.graph/mutation-count]    ;; integer — mutations in global registry
[:psi.graph/resolver-syms]     ;; set of qualified symbols
[:psi.graph/mutation-syms]     ;; set of qualified symbols
[:psi.graph/env-built]         ;; boolean — Pathom env compiled
[:psi.graph/nodes]             ;; vector of CapabilityNode maps
[:psi.graph/edges]             ;; vector of CapabilityEdge maps (with :attribute)
[:psi.graph/capabilities]      ;; vector of DomainCapability maps
[:psi.graph/domain-coverage]   ;; vector of DomainCoverage maps (ai/history/agent-session/introspection)
```

Combined query (all 9):
```clojure
[:psi.graph/resolver-count :psi.graph/mutation-count
 :psi.graph/resolver-syms :psi.graph/mutation-syms :psi.graph/env-built
 :psi.graph/nodes :psi.graph/edges :psi.graph/capabilities :psi.graph/domain-coverage]
```

nREPL introspection (from connected REPL):
```clojure
@psi.agent-session.main/session-state   ;; → {:ctx ... :ai-model ...}
(require '[psi.agent-session.core :as s])
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.agent-session/phase :psi.agent-session/session-id])

;; Extension UI state
(s/query-in (:ctx @psi.agent-session.main/session-state)
  [:psi.ui/widgets :psi.ui/statuses :psi.ui/visible-notifications
   :psi.ui/dialog-queue-empty? :psi.ui/tool-renderers])

;; Live turn state (during streaming)
(require '[psi.agent-session.turn-statechart :as turn])
(when-let [a (:turn-ctx-atom (:ctx @psi.agent-session.main/session-state))]
  (turn/query-turn @a))
;; → {:psi.turn/state :text-accumulating :psi.turn/text "..." ...}
```

## TUI Session: Resolved

**Problem**: Custom `ProcessTerminal` with `stty -echo raw` + manual
ANSI differential rendering had cursor position desync on macOS.

**Fix**: Replaced entire TUI layer with charm.clj (Elm Architecture).
charm.clj uses JLine3 (`jline-terminal-ffm`) which handles raw mode,
input parsing, and cursor positioning correctly.

**Architecture**: `psi.tui.app` — init/update/view functions.
Agent runs in future → `LinkedBlockingQueue` → poll command → message.
Spinner driven by poll ticks (no separate timer thread).

**JLine compat note**: charm.clj v0.1.42 has a bug in
`bind-from-capability!` (expects `char[]` but JLine 3.30+ returns
`String`). Patched via `alter-var-root` at namespace load time.
Caught by `jline-terminal-keymap-test` smoke test.

## agent-session namespaces

| Namespace                       | Role                                              |
|---------------------------------|---------------------------------------------------|
| `core.clj`                      | Public API, create-context, global wrappers       |
| `statechart.clj`                | Session statechart (idle/streaming/compacting/retrying) |
| `session.clj`                   | AgentSession data model, malli schemas            |
| `skills.clj`                    | Skill discovery, validation, prompt formatting    |
| `system_prompt.clj`             | System prompt assembly (tools, context, skills)   |
| `compaction.clj`                | Compaction algorithm (stub, injectable fn)        |
| `extensions.clj`                | Extension registry, loader, API, tool wrapping    |
| `tui/extension_ui.clj`         | Extension UI: dialogs, widgets, status, notifications, renderers |
| `persistence.clj`               | Append-only journal                               |
| `resolvers.clj`                 | EQL resolvers (:psi.agent-session/*, :psi.skill/*, :psi.ui/*) |
| `tools.clj`                     | Built-in tool implementations                     |
| `turn_statechart.clj`           | Per-turn streaming statechart (idle→text⇄tool→done) |
| `executor.clj`                  | ai ↔ agent-core streaming bridge (statechart-driven) |
| `main.clj`                      | Interactive REPL loop + TUI session (-main)       |
| `oauth/pkce.clj`                | PKCE verifier + S256 challenge (JDK crypto)       |
| `oauth/callback_server.clj`     | Local HTTP callback server (Nullable: null server) |
| `oauth/store.clj`               | Credential storage, priority chain (Nullable: in-memory) |
| `oauth/providers.clj`           | Provider registry + Anthropic OAuth impl          |
| `oauth/core.clj`                | Top-level OAuth API (Nullable: stub providers)    |

## Test Status

671 tests, 3191 assertions, 0 failures. 0 clj-kondo errors.

## Specs

| Spec file                  | Component mapping       | Status                                |
|----------------------------|-------------------------|---------------------------------------|
| `bootstrap-system.allium`  | `engine` + `query`      | ✓ implemented                         |
| `agent.allium`             | `agent-core`            | ✓ implemented                         |
| `ai-abstract-model.allium` | `ai`                    | ✓ implemented                         |
| `coding-agent.allium`      | `agent-session`         | ✓ split → 3 sub-specs; ✓ implemented  |
| `tools.allium`             | `agent-session/tools`   | ◇ target contracts (runtime policy + read/bash/edit/write/ls/find/grep/eql_query + path resolution + introspection) |
| `tool-output-handling.allium` | `agent-session/tools` | ◇ compatibility index (superseded by tools/*.allium) |
| `skills.allium`            | `agent-session/skills`  | ✓ implemented                         |
| `tui.allium`               | `tui`                   | ◇ partial — core session loop working; streaming/tool-status UX still open |
| `ui-extension-points.allium` | `tui/extension_ui`    | ✓ implemented                         |
| `oauth-auth.allium`        | `agent-session/oauth`   | ✓ implemented (Anthropic provider)    |
| `graph-emergence.allium`   | `query` + `introspection` | ◇ Step 7 spec authored (attribute links implicit; mutation side-effects deferred) |
| `memory-layer.allium`      | `query` + `history` + `introspection` | ◇ Step 10 spec authored (provenance, graph snapshots/deltas, recovery over session+history+graph) |
| `memory-backing-stores.allium` | `memory` | ✓ phase 1 implemented (provider contract + selection/fallback + `:psi.memory.store/*` EQL surface) |
| `memory-datalevin-store.allium` | `memory` | ✓ phase 2 implemented (Datalevin provider + write-through/hydration + runtime retention/migration hardening + provider failure telemetry surface) |
| `feed-forward-recursion.allium` | `memory` + `introspection` + `engine` | ◇ Step 11 spec authored (FUTURE_STATE loop, hooks, guardrails, memory writeback) |

## Step 7 Decisions (Spec)

- Source: `spec/graph-emergence.allium`
- Attribute links stay implicit on `:psi.graph/edges` (edge metadata), not first-class graph nodes
- Mutation side-effects are deferred in Step 7 (`sideEffects = nil`) — capability graph is IO-link based for now

## Step 10 Decisions (Spec)

- Source: `spec/memory-layer.allium`
- Recovery ranking defaults: text relevance 50%, recency 25%, capability proximity 25%
- Graph history retention: fixed-window compaction (keep latest 200 snapshots and 1000 deltas), trim oldest
- No graph-history summary entities in Step 10 (defer richer compaction/summarization)

## Step 9a Decisions (Spec)

- Sources: `spec/memory-backing-stores.allium`, `spec/memory-datalevin-store.allium`
- Default active memory store remains `in-memory` for backward compatibility
- Persistent stores are selected via provider registry; one active provider at a time
- Runtime can opt into Datalevin via `PSI_MEMORY_STORE=datalevin`
- remember/recover/graph artifacts now write-through to active provider; activation hydrates persisted records/snapshots/deltas/recoveries back into memory state
- Fallback policy defaults to automatic in-memory fallback when persistent provider is unavailable
- Runtime memory config is now available via CLI/env (store selection, fallback mode, history limit, retention limits)
- Datalevin open now enforces schema-version checks and optional migration hooks
- Provider operation telemetry is surfaced in store summaries/EQL (`write-count`, `read-count`, `failure-count`, `last-error`, `:psi.memory.store/last-failure`)
- Operator docs now cover fallback triage, retention windows, and migration-hook wiring (`README.md`)

## Step 11 Decisions (Spec)

- Source: `spec/feed-forward-recursion.allium`
- Trigger model in Step 11 is explicit/manual + event-driven hooks only (no periodic/background cadence)
- Active hooks are runtime-configured via `config.enabled_trigger_hooks`
- Manual approval remains default; low-risk proposals can auto-approve only in opt-in trusted local mode (`trusted_local_mode_enabled` + `auto_approve_low_risk_in_trusted_local_mode`)

## Canonical Telemetry Attrs (Step 7a)

Top-level EQL attrs for session telemetry — all reliably queryable in-session:

```clojure
[:psi.agent-session/messages-count]    ;; integer — total messages in agent-core
[:psi.agent-session/tool-call-count]   ;; integer — total tool calls made
[:psi.agent-session/start-time]        ;; java.time.Instant — session context creation
[:psi.agent-session/current-time]      ;; java.time.Instant — wall clock now
```

Combined query (mirrors the failing pattern, now fixed):
```clojure
[:psi.agent-session/phase :psi.agent-session/model :psi.agent-session/session-id
 :psi.agent-session/messages-count :psi.agent-session/tool-call-count
 :psi.agent-session/start-time :psi.agent-session/current-time]
```

## Memory Store Telemetry Attrs (Step 9.5)

Top-level EQL attrs for store durability/failure introspection:

```clojure
[:psi.memory.store/active-provider-id]          ;; string
[:psi.memory.store/selection]                   ;; fallback + reason
[:psi.memory.store/health]                      ;; active provider health map
[:psi.memory.store/active-provider-telemetry]   ;; write/read/failure counters + last-error
[:psi.memory.store/last-failure]                ;; most recent failure map across providers
[:psi.memory.store/providers]                   ;; provider entries incl. :telemetry
```

Combined query:
```clojure
[:psi.memory.store/active-provider-id
 :psi.memory.store/selection
 :psi.memory.store/health
 :psi.memory.store/active-provider-telemetry
 :psi.memory.store/last-failure
 :psi.memory.store/providers]
```

## Session startup prompts (Step 11 planned)

- Spec added: `spec/session-startup-prompts.allium`
- Repo config added: `.psi/startup-prompts.edn`
- Repo startup prompt set currently includes one prompt (`engage-nucleus`)
- Design decisions:
  - no session override source
  - no token budget guard
  - startup prompts execute as visible transcript turns (UI can see prompts + responses)
  - startup attrs must be top-level EQL attrs and discoverable via graph introspection (`:psi.graph/*`)

## Open Questions
- Startup prompts: run as one concatenated turn or sequential turns per prompt?
- Startup prompts: should forked sessions re-run startup prompts by default?
- TUI: per-token streaming (currently shows spinner until agent done)
- TUI: tool execution status display during agent loop
- Extension UI: should dialogs support auto-dismiss timeout?
- Extension UI: widget ordering when multiple extensions contribute to same placement
- Extension UI: should render fns receive a theme map for consistent styling?
- Extension UI: editor text injection (set-editor-text, paste-to-editor)
- Extension UI: working message override during streaming ("Analyzing..." vs "thinking…")
- Graph emergence: when should mutation side-effects move from IO-only links to structured effect entities?

## nREPL

Port: `8888`
