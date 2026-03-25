(ns psi.agent-session.resolvers
  "Pathom3 EQL resolvers for the agent-session component.

   Attribute namespace: :psi.agent-session/
   Seed key:           :psi/agent-session-ctx   — the session context map

   All resolvers receive the session context via :psi/agent-session-ctx
   in the Pathom entity map and delegate to pure read fns in core.clj.

   Exposed attributes
   ──────────────────
   :psi.agent-session/session-id
   :psi.agent-session/session-file
   :psi.agent-session/session-name
   :psi.agent-session/context-active-session-id
   :psi.agent-session/context-session-count
   :psi.agent-session/context-sessions
   :psi.agent-session/model
   :psi.agent-session/thinking-level
   :psi.agent-session/prompt-mode
   :psi.agent-session/is-streaming
   :psi.agent-session/is-compacting
   :psi.agent-session/is-idle
   :psi.agent-session/phase               — statechart phase keyword
   :psi.agent-session/base-system-prompt
   :psi.agent-session/system-prompt
   :psi.agent-session/developer-prompt
   :psi.agent-session/developer-prompt-source
   :psi.agent-session/prompt-layers
   :psi.agent-session/prompt-contributions
   :psi.extension/prompt-contributions
   :psi.extension/prompt-contribution-count
   :psi.agent-session/pending-message-count
   :psi.agent-session/has-pending-messages
   :psi.agent-session/retry-attempt
   :psi.agent-session/auto-retry-enabled
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/scoped-models
   :psi.agent-session/skills
   :psi.agent-session/prompt-templates
   :psi.agent-session/extension-summary   — map describing registered extensions
   :psi.agent-session/prompt-contributions — ordered extension prompt contribution maps
   :psi.agent-session/extension-last-prompt-source
   :psi.agent-session/extension-last-prompt-delivery
   :psi.agent-session/extension-last-prompt-at
   :psi.agent-session/session-entry-count
   :psi.agent-session/session-entries     — [{:psi.session-entry/*}] full journal contents
   :psi.agent-session/journal-flushed?    — true once initial bulk write has happened
   :psi.agent-session/context-tokens
   :psi.agent-session/context-window
   :psi.agent-session/context-fraction    — nil or 0.0–1.0
   :psi.agent-session/messages-count      — total message count in agent-core (user + assistant)
   :psi.agent-session/ai-call-count       — total AI model calls (assistant messages with usage)
   :psi.agent-session/tool-call-count     — committed tool results in the session journal/transcript
   :psi.agent-session/executed-tool-count — canonical executed-tool count from lifecycle summaries
   :psi.agent-session/start-time          — Instant when session context was created
   :psi.agent-session/current-time        — current wall-clock Instant
   :psi.agent-session/stats               — SessionStats snapshot
   :psi.agent-session/tool-call-history   — [{:psi.tool-call/*}], transcript-derived compatibility projection of tool calls
   :psi.agent-session/tool-call-history-count — number of transcript-visible tool calls in that compatibility projection
   :psi.agent-session/tool-call-attempt-count — streamed provider tool-call attempts
   :psi.agent-session/tool-call-attempt-unmatched-count — attempts without matching toolResult
   :psi.agent-session/tool-call-attempts — [{:psi.tool-call-attempt/*}], attempt-level stream entities
   :psi.agent-session/tool-lifecycle-event-count — canonical tool lifecycle event count
   :psi.agent-session/tool-lifecycle-events — [{:psi.tool-lifecycle/*}], canonical tool lifecycle entities
   :psi.agent-session/tool-lifecycle-summary-count — grouped lifecycle read-model count by tool call
   :psi.agent-session/tool-lifecycle-summaries — [{:psi.tool-lifecycle.summary/*}], grouped lifecycle read-models by tool call
   :psi.agent-session/tool-lifecycle-summary-for-tool-id — single grouped lifecycle read-model lookup seeded by :psi.agent-session/lookup-tool-id
   :psi.agent-session/provider-request-count — captured outbound provider requests
   :psi.agent-session/provider-reply-count — captured inbound provider reply events
   :psi.agent-session/provider-last-request — latest provider request entity
   :psi.agent-session/provider-last-reply — latest provider reply entity
   :psi.agent-session/provider-last-error-reply — latest provider reply entity whose event type is :error
   :psi.agent-session/provider-requests — [{:psi.provider-request/*}], recent provider requests
   :psi.agent-session/provider-replies — [{:psi.provider-reply/*}], recent provider reply events
   :psi.agent-session/provider-error-replies — [{:psi.provider-reply/*}], recent provider reply events whose type is :error
   :psi.agent-session/provider-request-for-turn-id — single provider request entity lookup seeded by :psi.agent-session/lookup-turn-id
   :psi.agent-session/provider-reply-for-turn-id — single provider reply entity lookup seeded by :psi.agent-session/lookup-turn-id
   :psi.agent-session/background-job-count — number of tracked background jobs in current thread
   :psi.agent-session/background-job-statuses — ordered status vocabulary for background jobs
   :psi.agent-session/background-jobs     — [{:psi.background-job/*}], current-thread background jobs
   :psi.agent-session/dispatch-event-log-count — number of retained dispatch event log entries
   :psi.agent-session/dispatch-event-log  — [{:psi.dispatch-event/*}], recent dispatch event entries
   :psi.agent-session/registered-dispatch-event-count — number of registered dispatch event handlers
   :psi.agent-session/registered-dispatch-events — [{:psi.dispatch-handler/*}], registered dispatch handler metadata

   Tool-output policy and telemetry
   ────────────────────────────────
   :psi.tool-output/default-max-lines
   :psi.tool-output/default-max-bytes
   :psi.tool-output/overrides
   :psi.tool-output/calls            — [{:psi.tool-output.call/*}]
   :psi.tool-output/stats            — {:total-context-bytes :by-tool :limit-hits-by-tool}

   Tool-output call entities (nested under :psi.tool-output/calls)
   ──────────────────────────────────────────────────────────────
   :psi.tool-output.call/tool-call-id
   :psi.tool-output.call/tool-name
   :psi.tool-output.call/timestamp
   :psi.tool-output.call/limit-hit?
   :psi.tool-output.call/truncated-by
   :psi.tool-output.call/effective-max-lines
   :psi.tool-output.call/effective-max-bytes
   :psi.tool-output.call/output-bytes
   :psi.tool-output.call/context-bytes-added

   Session entry entities (nested under session-entries)
   ──────────────────────────────────────────────────────
   :psi.session-entry/id
   :psi.session-entry/parent-id
   :psi.session-entry/timestamp
   :psi.session-entry/kind
   :psi.session-entry/data

   Session listing (cross-session)
   ────────────────────────────────
   :psi.session/list                      — [{:psi.session-info/*}] for current cwd
   :psi.session/list-all                  — [{:psi.session-info/*}] across all projects
   :psi.session-info/path
   :psi.session-info/id
   :psi.session-info/cwd
   :psi.session-info/name
   :psi.session-info/parent-session-path
   :psi.session-info/created
   :psi.session-info/modified
   :psi.session-info/message-count
   :psi.session-info/first-message
   :psi.session-info/all-messages-text

   Tool call entities (nested under tool-call-history)
   ───────────────────────────────────────────────────
   :psi.tool-call/id                      — unique call ID (from list resolver)
   :psi.tool-call/name                    — tool name (from list resolver)
   :psi.tool-call/arguments               — raw argument string (from list resolver)
   :psi.tool-call/result                  — result text (from detail resolver, lazy)
   :psi.tool-call/is-error                — error flag (from detail resolver, lazy)

   Tool call attempt entities (nested under tool-call-attempts)
   ───────────────────────────────────────────────────────────
   :psi.tool-call-attempt/id
   :psi.tool-call-attempt/name
   :psi.tool-call-attempt/content-index
   :psi.tool-call-attempt/turn-id
   :psi.tool-call-attempt/status            — :started | :ended | :result-recorded | :partial
   :psi.tool-call-attempt/started-at
   :psi.tool-call-attempt/ended-at
   :psi.tool-call-attempt/delta-count
   :psi.tool-call-attempt/argument-bytes
   :psi.tool-call-attempt/executed?

   Tool lifecycle entities (nested under tool-lifecycle-events)
   ─────────────────────────────────────────────────────────
   :psi.tool-lifecycle/event-kind
   :psi.tool-lifecycle/tool-id
   :psi.tool-lifecycle/tool-name
   :psi.tool-lifecycle/timestamp
   :psi.tool-lifecycle/details
   :psi.tool-lifecycle/is-error
   :psi.tool-lifecycle/content
   :psi.tool-lifecycle/result-text
   :psi.tool-lifecycle/arguments
   :psi.tool-lifecycle/parsed-args

   Tool lifecycle summary entities (nested under tool-lifecycle-summaries)
   ───────────────────────────────────────────────────────────────────
   :psi.tool-lifecycle.summary/tool-id
   :psi.tool-lifecycle.summary/tool-name
   :psi.tool-lifecycle.summary/event-count
   :psi.tool-lifecycle.summary/last-event-kind
   :psi.tool-lifecycle.summary/started-at
   :psi.tool-lifecycle.summary/last-updated-at
   :psi.tool-lifecycle.summary/completed?
   :psi.tool-lifecycle.summary/is-error
   :psi.tool-lifecycle.summary/result-text
   :psi.tool-lifecycle.summary/arguments
   :psi.tool-lifecycle.summary/parsed-args

   Tool lifecycle lookup seed / entity
   ───────────────────────────────────
   :psi.agent-session/lookup-tool-id
   :psi.agent-session/tool-lifecycle-summary-for-tool-id
   :psi.tool-call-attempt/result-recorded?

   Provider request entities (nested under :psi.agent-session/provider-requests)
   ─────────────────────────────────────────────────────────────────────────
   :psi.provider-request/provider
   :psi.provider-request/api
   :psi.provider-request/url
   :psi.provider-request/turn-id
   :psi.provider-request/timestamp
   :psi.provider-request/headers
   :psi.provider-request/body

   Provider reply entities (nested under :psi.agent-session/provider-replies)
   ───────────────────────────────────────────────────────────────────────
   :psi.provider-reply/provider
   :psi.provider-reply/api
   :psi.provider-reply/url
   :psi.provider-reply/turn-id
   :psi.provider-reply/timestamp
   :psi.provider-reply/event

   Background job entities (nested under :psi.agent-session/background-jobs)
   ─────────────────────────────────────────────────────────────────────────
   :psi.background-job/id
   :psi.background-job/thread-id
   :psi.background-job/tool-call-id
   :psi.background-job/tool-name
   :psi.background-job/job-kind
   :psi.background-job/workflow-ext-path
   :psi.background-job/workflow-id
   :psi.background-job/job-seq
   :psi.background-job/started-at
   :psi.background-job/completed-at
   :psi.background-job/completed-seq
   :psi.background-job/status
   :psi.background-job/terminal-payload
   :psi.background-job/terminal-payload-file
   :psi.background-job/cancel-requested-at
   :psi.background-job/terminal-message-emitted
   :psi.background-job/terminal-message-emitted-at
   :psi.background-job/is-terminal
   :psi.background-job/is-non-terminal

   API error diagnostics (hierarchical)
   ────────────────────────────────────
   :psi.agent-session/api-error-count     — number of API errors in session
   :psi.agent-session/api-errors          — [{:psi.api-error/*}] error entities

   Level 1 (list, cheap — identity from assistant error messages):
   :psi.api-error/message-index           — position in agent-core message vec
   :psi.api-error/http-status             — HTTP status code (e.g. 400), or nil
   :psi.api-error/timestamp               — Instant of the error
   :psi.api-error/error-message-brief     — first 120 chars of error text

   Level 2 (detail, moderate — seeded by :psi.api-error/message-index):
   :psi.api-error/error-message-full      — complete error text
   :psi.api-error/request-id              — API request-id parsed from error text
   :psi.api-error/surrounding-messages    — [{:psi.context-message/*}] nearby messages

   Level 3 (request shape, expensive — seeded by :psi.api-error/message-index):
   :psi.api-error/request-shape           — {:psi.request-shape/*} at point of failure

   Context message entities (nested under surrounding-messages)
   ────────────────────────────────────────────────────────────
   :psi.context-message/index             — position in agent-core message vec
   :psi.context-message/role              — message role string
   :psi.context-message/content-types     — [:text :tool-call :error ...]
   :psi.context-message/snippet           — first 200 chars of primary content

   Request shape (shared between api-error and current diagnostics)
   ────────────────────────────────────────────────────────────────
   :psi.agent-session/request-shape       — {:psi.request-shape/*} for current state
   :psi.request-shape/message-count       — raw agent-core message count
   :psi.request-shape/system-prompt-chars — system prompt character count
   :psi.request-shape/message-chars       — total message characters (pr-str estimate)
   :psi.request-shape/tool-schema-chars   — tool definition characters
   :psi.request-shape/total-chars         — sum of above
   :psi.request-shape/estimated-tokens    — total-chars / 4 (rough estimate)
   :psi.request-shape/context-window      — model context window size
   :psi.request-shape/max-output-tokens   — model max output tokens
   :psi.request-shape/headroom-tokens     — context-window - estimated - max-output
   :psi.request-shape/role-distribution   — {role count}
   :psi.request-shape/tool-count          — number of tool definitions
   :psi.request-shape/tool-use-count      — tool_use blocks in assistant messages
   :psi.request-shape/tool-result-count   — toolResult messages
   :psi.request-shape/missing-tool-results — tool_use IDs without matching results
   :psi.request-shape/orphan-tool-results  — toolResult IDs without matching tool_use
   :psi.request-shape/alternation-valid?   — true if role alternation is correct
   :psi.request-shape/alternation-violations — count of consecutive same-role pairs
   :psi.request-shape/empty-content-count  — messages with empty content

   Prompt template introspection
   ─────────────────────────────
   :psi.prompt-template/summary           — overall template summary
   :psi.prompt-template/names             — vector of template name strings
   :psi.prompt-template/count             — number of discovered templates
   :psi.prompt-template/by-source         — templates grouped by source
   :psi.prompt-template/detail            — single enriched template (seed: :psi.prompt-template/name)

   Skill introspection
   ───────────────────
   :psi.skill/summary                     — overall skill summary
   :psi.skill/names                       — vector of skill name strings
   :psi.skill/count                       — total discovered skills
   :psi.skill/visible-count               — skills available to model
   :psi.skill/hidden-count                — skills with disable-model-invocation
   :psi.skill/by-source                   — skills grouped by source
   :psi.skill/detail                      — single enriched skill (seed: :psi.skill/name)

   Tool introspection
   ──────────────────
   :psi.tool/summary                      — overall tool summary
   :psi.tool/names                        — vector of active tool names
   :psi.tool/count                        — number of active tools
   :psi.tool/detail                       — single tool map (seed: :psi.tool/name)

   Extension UI state (read-only)
   ──────────────────────────────
   :psi.ui/dialog-queue-empty?            — true when no dialogs active or pending
   :psi.ui/active-dialog                  — current dialog map (sans promise), or nil
   :psi.ui/pending-dialog-count           — number of queued dialogs
   :psi.ui/widgets                        — vector of legacy widget maps (content-lines)
   :psi.ui/widget-specs                   — vector of declarative WidgetSpec maps
   :psi.ui/statuses                       — vector of status entry maps
   :psi.ui/visible-notifications          — vector of non-dismissed notification maps
   :psi.ui/tool-renderers                 — vector of tool renderer metadata maps
   :psi.ui/message-renderers              — vector of message renderer metadata maps

   Agent chain discovery
   ─────────────────────
   :psi.agent-chain/config-path           — absolute path to .psi/agents/agent-chain.edn
   :psi.agent-chain/count                 — number of configured chains
   :psi.agent-chain/names                 — configured chain names
   :psi.agent-chain/chains                — configured chain summaries
   :psi.agent-chain/error                 — parse/load error string, or nil

   Session-derived usage and git attrs (read-only)
   ───────────────────────────────────────────────
   :psi.agent-session/cwd
   :psi.agent-session/git-branch          — nil when outside git, \"detached\" on detached HEAD
   :psi.runtime/nrepl-host                — runtime nREPL host, or nil when disabled
   :psi.runtime/nrepl-port                — runtime nREPL port, or nil when disabled
   :psi.runtime/nrepl-endpoint            — runtime nREPL endpoint host:port, or nil when disabled
   :psi.agent-session/usage-input
   :psi.agent-session/usage-output
   :psi.agent-session/usage-cache-read
   :psi.agent-session/usage-cache-write
   :psi.agent-session/usage-cost-total
   :psi.agent-session/model-provider
   :psi.agent-session/model-id
   :psi.agent-session/model-reasoning
   :psi.agent-session/effective-reasoning-effort
   :psi.agent-session/ui-type             — runtime UI type hint (:console | :tui | :emacs)
   :psi.agent-session/model-catalog       — runtime model picker payload [{:provider :id :name :reasoning}]
   :psi.agent-session/authenticated-providers — provider ids with configured auth for this session
   :psi.agent-session/rpc-trace-enabled   — true when rpc frame tracing is enabled
   :psi.agent-session/rpc-trace-file      — trace output file path, or nil when unset

   Startup bootstrap introspection
   ──────────────────────────────
   :psi.startup/bootstrap-summary           — map of bootstrap execution details
   :psi.startup/bootstrap-timestamp         — Instant when bootstrap finished
   :psi.startup/prompt-count                — prompts loaded during bootstrap
   :psi.startup/skill-count                 — skills loaded during bootstrap
   :psi.startup/tool-count                  — tools loaded during bootstrap
   :psi.startup/extension-loaded-count      — extensions loaded successfully
   :psi.startup/extension-error-count       — extension load errors
   :psi.startup/extension-errors            — [{:path :error}] extension failures
   :psi.startup/mutations                   — mutation symbols used by bootstrap

   Session startup prompt telemetry
   ───────────────────────────────
   :psi.agent-session/startup-prompts
   :psi.agent-session/startup-bootstrap-completed?
   :psi.agent-session/startup-bootstrap-started-at
   :psi.agent-session/startup-bootstrap-completed-at
   :psi.agent-session/startup-message-ids"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [psi.graph.analysis :as graph]
   [psi.history.git :as git]
   [psi.history.resolvers :as history-resolvers]
   [psi.engine.core :as engine]
   [psi.memory.core :as memory]
   [psi.memory.resolvers :as memory-resolvers]
   [psi.query.registry :as registry]
   [psi.recursion.core :as recursion]
   [psi.recursion.resolvers :as recursion-resolvers]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session :as session]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.tool-output :as tool-output]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.workflows :as wf]
   [psi.ui.state :as ui-state]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.turn-statechart :as turn-sc]
   [psi.ai.models :as ai-models]
   [psi.agent-core.core :as agent]))

(declare tool-lifecycle-summaries)

;; ── Core session fields ─────────────────────────────────

(pco/defresolver agent-session-identity
  "Resolve stable identity, naming, and context session registry fields."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/session-id
                 :psi.agent-session/session-file
                 :psi.agent-session/session-name
                 :psi.agent-session/context-active-session-id
                 :psi.agent-session/context-session-count
                 {:psi.agent-session/context-sessions
                  [:psi.session-info/id
                   :psi.session-info/path
                   :psi.session-info/cwd
                   :psi.session-info/worktree-path
                   :psi.session-info/name
                   :psi.session-info/parent-session-id
                   :psi.session-info/parent-session-path
                   :psi.session-info/created]}]}
  (let [sd         (session/get-session-data-in agent-session-ctx)
        state      @(:state* agent-session-ctx)
        active-sid (get-in state [:agent-session :active-session-id])
        sessions   (get-in state [:agent-session :sessions])
        hs         (->> (vals sessions)
                        (map :data)
                        (filter some?)
                        (sort-by :session-id)
                        vec)]
    {:psi.agent-session/session-id              (:session-id sd)
     :psi.agent-session/session-file            (:session-file sd)
     :psi.agent-session/session-name            (:session-name sd)
     :psi.agent-session/context-active-session-id  active-sid
     :psi.agent-session/context-session-count      (count hs)
     :psi.agent-session/context-sessions
     (mapv (fn [m]
             {:psi.session-info/id                  (:session-id m)
              :psi.session-info/path                (:session-file m)
              :psi.session-info/cwd                 (:worktree-path m)
              :psi.session-info/worktree-path       (:worktree-path m)
              :psi.session-info/name                (:session-name m)
              :psi.session-info/parent-session-id   (:parent-session-id m)
              :psi.session-info/parent-session-path (:parent-session-path m)
              :psi.session-info/created             (:created-at m)})
           hs)}))

;; ── Phase and streaming state ───────────────────────────

(pco/defresolver agent-session-phase
  "Resolve statechart phase and derived boolean flags."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/phase
                 :psi.agent-session/is-streaming
                 :psi.agent-session/is-compacting
                 :psi.agent-session/is-idle]}
  (let [sc-env       (:sc-env agent-session-ctx)
        sc-session-id (ss/sc-session-id-in agent-session-ctx)
        phase (sc/sc-phase sc-env sc-session-id)]
    {:psi.agent-session/phase        phase
     :psi.agent-session/is-streaming (= phase :streaming)
     :psi.agent-session/is-compacting (= phase :compacting)
     :psi.agent-session/is-idle      (= phase :idle)}))

;; ── Model and thinking level ────────────────────────────

(pco/defresolver agent-session-model
  "Resolve model, thinking level, prompt mode, and UI type."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model
                 :psi.agent-session/thinking-level
                 :psi.agent-session/prompt-mode
                 :psi.agent-session/ui-type]}
  (let [sd (session/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/model          (:model sd)
     :psi.agent-session/thinking-level (:thinking-level sd)
     :psi.agent-session/prompt-mode    (:prompt-mode sd)
     :psi.agent-session/ui-type        (:ui-type sd)}))

(defn- contribution->attrs
  [c]
  {:psi.extension.prompt-contribution/id         (:id c)
   :psi.extension.prompt-contribution/ext-path   (:ext-path c)
   :psi.extension.prompt-contribution/section    (:section c)
   :psi.extension.prompt-contribution/content    (:content c)
   :psi.extension.prompt-contribution/priority   (:priority c)
   :psi.extension.prompt-contribution/enabled    (:enabled c)
   :psi.extension.prompt-contribution/created-at (:created-at c)
   :psi.extension.prompt-contribution/updated-at (:updated-at c)})

;; ── Queues and message counts ───────────────────────────

(pco/defresolver agent-session-queues
  "Resolve queue depths and prompt layers."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/base-system-prompt
                 :psi.agent-session/system-prompt
                 :psi.agent-session/developer-prompt
                 :psi.agent-session/developer-prompt-source
                 :psi.agent-session/prompt-layers
                 :psi.agent-session/prompt-contributions
                 :psi.agent-session/pending-message-count
                 :psi.agent-session/has-pending-messages
                 :psi.agent-session/steering-messages
                 :psi.agent-session/follow-up-messages]}
  (let [sd         (session/get-session-data-in agent-session-ctx)
        base       (:base-system-prompt sd)
        sys        (:system-prompt sd)
        dev        (:developer-prompt sd)
        dev-source (:developer-prompt-source sd)
        contribs   (vec (:prompt-contributions sd))]
    {:psi.agent-session/base-system-prompt     base
     :psi.agent-session/system-prompt          sys
     :psi.agent-session/developer-prompt       dev
     :psi.agent-session/developer-prompt-source dev-source
     :psi.agent-session/prompt-layers          {:base-system-prompt base
                                                :system-prompt sys
                                                :developer-prompt dev
                                                :developer-prompt-source dev-source
                                                :prompt-contributions (mapv contribution->attrs contribs)}
     :psi.agent-session/prompt-contributions   (mapv contribution->attrs contribs)
     :psi.agent-session/pending-message-count  (session/pending-message-count sd)
     :psi.agent-session/has-pending-messages   (session/has-pending-messages? sd)
     :psi.agent-session/steering-messages      (:steering-messages sd)
     :psi.agent-session/follow-up-messages     (:follow-up-messages sd)}))

;; ── Retry and compaction config ─────────────────────────

(pco/defresolver agent-session-retry-compact
  "Resolve retry and compaction operational fields."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/retry-attempt
                 :psi.agent-session/auto-retry-enabled
                 :psi.agent-session/auto-compaction-enabled
                 :psi.agent-session/scoped-models]}
  (let [sd (session/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/retry-attempt           (:retry-attempt sd)
     :psi.agent-session/auto-retry-enabled      (:auto-retry-enabled sd)
     :psi.agent-session/auto-compaction-enabled (:auto-compaction-enabled sd)
     :psi.agent-session/scoped-models           (:scoped-models sd)}))

;; ── Resources ──────────────────────────────────────────

(pco/defresolver agent-session-resources
  "Resolve registered skills and prompt templates."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/skills
                 :psi.agent-session/prompt-templates]}
  (let [sd (session/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/skills           (:skills sd)
     :psi.agent-session/prompt-templates (:prompt-templates sd)}))

;; ── Extension summary ───────────────────────────────────

(pco/defresolver agent-session-extensions
  "Resolve extension registry summary and extension prompt telemetry."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/extension-summary
                 :psi.agent-session/extension-last-prompt-source
                 :psi.agent-session/extension-last-prompt-delivery
                 :psi.agent-session/extension-last-prompt-at]}
  (let [sd (session/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/extension-summary              (ext/summary-in (:extension-registry agent-session-ctx))
     :psi.agent-session/extension-last-prompt-source   (:extension-last-prompt-source sd)
     :psi.agent-session/extension-last-prompt-delivery (:extension-last-prompt-delivery sd)
     :psi.agent-session/extension-last-prompt-at       (:extension-last-prompt-at sd)}))

;; ── Extension introspection ─────────────────────────────

(pco/defresolver extension-paths-resolver
  "Resolve list of registered extension paths."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/paths
                 :psi.extension/count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/paths (vec (ext/extensions-in reg))
     :psi.extension/count (ext/extension-count-in reg)}))

(pco/defresolver extension-handlers-resolver
  "Resolve handler event names and total handler count."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/handler-events
                 :psi.extension/handler-count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/handler-events (vec (ext/handler-event-names-in reg))
     :psi.extension/handler-count  (ext/handler-count-in reg)}))

(pco/defresolver extension-tools-resolver
  "Resolve all registered extension tools."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/tools
                 :psi.extension/tool-names]}
  (let [reg   (:extension-registry agent-session-ctx)
        tools (ext/all-tools-in reg)]
    {:psi.extension/tools      (mapv #(dissoc % :execute) tools)
     :psi.extension/tool-names (vec (ext/tool-names-in reg))}))

(pco/defresolver extension-commands-resolver
  "Resolve all registered extension commands."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/commands
                 :psi.extension/command-names]}
  (let [reg  (:extension-registry agent-session-ctx)
        cmds (ext/all-commands-in reg)]
    {:psi.extension/commands      (mapv #(dissoc % :handler) cmds)
     :psi.extension/command-names (vec (ext/command-names-in reg))}))

(pco/defresolver extension-flags-resolver
  "Resolve all registered extension flags with current values."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/flags
                 :psi.extension/flag-names
                 :psi.extension/flag-values]}
  (let [reg   (:extension-registry agent-session-ctx)
        flags (ext/all-flags-in reg)]
    {:psi.extension/flags      flags
     :psi.extension/flag-names (vec (ext/flag-names-in reg))
     :psi.extension/flag-values (ext/all-flag-values-in reg)}))

(pco/defresolver extension-details-resolver
  "Resolve per-extension detail maps."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/details]}
  {:psi.extension/details
   (ext/extension-details-in (:extension-registry agent-session-ctx))})

(pco/defresolver extension-prompt-contributions-resolver
  "Resolve extension-managed prompt contributions in deterministic render order."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/prompt-contributions
                 :psi.extension/prompt-contribution-count]}
  (let [contribs (->> (or (:prompt-contributions (session/get-session-data-in agent-session-ctx)) [])
                      (filter map?)
                      (sort-by (fn [{:keys [priority ext-path id]}]
                                 [(or priority 1000)
                                  (or ext-path "")
                                  (or id "")]))
                      (mapv contribution->attrs))]
    {:psi.extension/prompt-contributions contribs
     :psi.extension/prompt-contribution-count (count contribs)}))

(pco/defresolver extension-detail-by-path-resolver
  "Resolve detail for a single extension by path.
   Seed input: {:psi.extension/path \"path\"}"
  [{:keys [psi/agent-session-ctx psi.extension/path]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path]
   ::pco/output [:psi.extension/detail]}
  {:psi.extension/detail
   (ext/extension-detail-in (:extension-registry agent-session-ctx) path)})

;; ── Extension workflow introspection ───────────────────

(def ^:private workflow-event-output
  [:psi.extension.workflow.event/name
   :psi.extension.workflow.event/data
   :psi.extension.workflow.event/timestamp])

(def ^:private workflow-output
  [:psi.extension.workflow/id
   :psi.extension/path
   :psi.extension.workflow/type
   :psi.extension.workflow/phase
   :psi.extension.workflow/configuration
   :psi.extension.workflow/running?
   :psi.extension.workflow/done?
   :psi.extension.workflow/error?
   :psi.extension.workflow/error-message
   :psi.extension.workflow/input
   :psi.extension.workflow/meta
   :psi.extension.workflow/data
   :psi.extension.workflow/result
   :psi.extension.workflow/created-at
   :psi.extension.workflow/started-at
   :psi.extension.workflow/updated-at
   :psi.extension.workflow/finished-at
   :psi.extension.workflow/elapsed-ms
   :psi.extension.workflow/event-count
   :psi.extension.workflow/last-event
   {:psi.extension.workflow/events workflow-event-output}])

(defn- event->eql
  [event]
  {:psi.extension.workflow.event/name      (:name event)
   :psi.extension.workflow.event/data      (:data event)
   :psi.extension.workflow.event/timestamp (:timestamp event)})

(defn- workflow->eql
  [workflow]
  (let [created-at (:created-at workflow)
        finished-at (:finished-at workflow)
        elapsed-ms (when created-at
                     (let [end (or finished-at (java.time.Instant/now))]
                       (- (.toEpochMilli ^java.time.Instant end)
                          (.toEpochMilli ^java.time.Instant created-at))))]
    {:psi.extension.workflow/id            (:id workflow)
     :psi.extension/path                   (:ext-path workflow)
     :psi.extension.workflow/type          (:type workflow)
     :psi.extension.workflow/phase         (:phase workflow)
     :psi.extension.workflow/configuration (:configuration workflow)
     :psi.extension.workflow/running?      (:running? workflow)
     :psi.extension.workflow/done?         (:done? workflow)
     :psi.extension.workflow/error?        (:error? workflow)
     :psi.extension.workflow/error-message (:error-message workflow)
     :psi.extension.workflow/input         (:input workflow)
     :psi.extension.workflow/meta          (:meta workflow)
     :psi.extension.workflow/data          (:data workflow)
     :psi.extension.workflow/result        (:result workflow)
     :psi.extension.workflow/created-at    created-at
     :psi.extension.workflow/started-at    (:started-at workflow)
     :psi.extension.workflow/updated-at    (:updated-at workflow)
     :psi.extension.workflow/finished-at   finished-at
     :psi.extension.workflow/elapsed-ms    elapsed-ms
     :psi.extension.workflow/event-count   (:event-count workflow)
     :psi.extension.workflow/last-event    (:last-event workflow)
     :psi.extension.workflow/events        (mapv event->eql (:events workflow))}))

(defn- read-agent-chain-config
  [cwd]
  (let [path (str cwd "/.psi/agents/agent-chain.edn")
        f    (io/file path)]
    (if (.exists f)
      (try
        (let [data (edn/read-string (slurp f))]
          {:path   path
           :chains (if (vector? data) data [])
           :error  nil})
        (catch Exception e
          {:path   path
           :chains []
           :error  (ex-message e)}))
      {:path   path
       :chains []
       :error  nil})))

(defn- chain->summary
  [chain]
  (let [steps (vec (or (:steps chain) []))]
    {:name         (:name chain)
     :description  (:description chain)
     :step-count   (count steps)
     :agents       (vec (keep :agent steps))
     :steps        (mapv (fn [idx step]
                           {:index  (inc idx)
                            :agent  (:agent step)
                            :prompt (:prompt step)})
                         (range)
                         steps)}))

(pco/defresolver extension-workflow-summary-resolver
  "Resolve workflow counts and workflow type names across extensions."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension.workflow/count
                 :psi.extension.workflow/running-count
                 :psi.extension.workflow/type-names]}
  (let [reg (:workflow-registry agent-session-ctx)]
    {:psi.extension.workflow/count         (wf/workflow-count-in reg)
     :psi.extension.workflow/running-count (wf/running-count-in reg)
     :psi.extension.workflow/type-names    (wf/type-names-in reg)}))

(pco/defresolver agent-chain-discovery-resolver
  "Resolve discoverable agent-chain definitions from .psi/agents/agent-chain.edn."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-chain/config-path
                 :psi.agent-chain/count
                 :psi.agent-chain/names
                 :psi.agent-chain/chains
                 :psi.agent-chain/error]}
  (let [cwd (:cwd agent-session-ctx)
        {:keys [path chains error]} (read-agent-chain-config cwd)
        summaries (mapv chain->summary chains)]
    {:psi.agent-chain/config-path path
     :psi.agent-chain/count       (count summaries)
     :psi.agent-chain/names       (mapv :name summaries)
     :psi.agent-chain/chains      summaries
     :psi.agent-chain/error       error}))

(pco/defresolver extension-workflows-resolver
  "Resolve workflow instances.
   When :psi.extension/path is present in the seed/entity, results are filtered
   to that extension; otherwise all workflows are returned."
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.extension/workflows workflow-output}]}
  (let [agent-session-ctx (:psi/agent-session-ctx entity)
        path              (:psi.extension/path entity)
        reg               (:workflow-registry agent-session-ctx)]
    {:psi.extension/workflows
     (mapv workflow->eql (wf/workflows-in reg path))}))

(pco/defresolver extension-workflow-detail-resolver
  "Resolve one workflow instance by extension path + id.
   Seed input includes :psi.extension/path and :psi.extension.workflow/id."
  [{:keys [psi/agent-session-ctx psi.extension/path psi.extension.workflow/id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path :psi.extension.workflow/id]
   ::pco/output [:psi.extension.workflow/detail]}
  (let [reg (:workflow-registry agent-session-ctx)]
    {:psi.extension.workflow/detail
     (some-> (wf/workflow-in reg path id) workflow->eql)}))

;; ── Background jobs introspection ───────────────────────

(def ^:private background-job-status-order
  [:running :pending-cancel :completed :failed :cancelled :timed-out])

(def ^:private background-job-output
  [:psi.background-job/id
   :psi.background-job/thread-id
   :psi.background-job/tool-call-id
   :psi.background-job/tool-name
   :psi.background-job/job-kind
   :psi.background-job/workflow-ext-path
   :psi.background-job/workflow-id
   :psi.background-job/job-seq
   :psi.background-job/started-at
   :psi.background-job/completed-at
   :psi.background-job/completed-seq
   :psi.background-job/status
   :psi.background-job/terminal-payload
   :psi.background-job/terminal-payload-file
   :psi.background-job/cancel-requested-at
   :psi.background-job/terminal-message-emitted
   :psi.background-job/terminal-message-emitted-at
   :psi.background-job/is-terminal
   :psi.background-job/is-non-terminal])

(defn- background-job->eql
  [job]
  {:psi.background-job/id                          (:job-id job)
   :psi.background-job/thread-id                   (:thread-id job)
   :psi.background-job/tool-call-id                (:tool-call-id job)
   :psi.background-job/tool-name                   (:tool-name job)
   :psi.background-job/job-kind                    (:job-kind job)
   :psi.background-job/workflow-ext-path           (:workflow-ext-path job)
   :psi.background-job/workflow-id                 (:workflow-id job)
   :psi.background-job/job-seq                     (:job-seq job)
   :psi.background-job/started-at                  (:started-at job)
   :psi.background-job/completed-at                (:completed-at job)
   :psi.background-job/completed-seq               (:completed-seq job)
   :psi.background-job/status                      (:status job)
   :psi.background-job/terminal-payload            (:terminal-payload job)
   :psi.background-job/terminal-payload-file       (:terminal-payload-file job)
   :psi.background-job/cancel-requested-at         (:cancel-requested-at job)
   :psi.background-job/terminal-message-emitted    (boolean (:terminal-message-emitted job))
   :psi.background-job/terminal-message-emitted-at (:terminal-message-emitted-at job)
   :psi.background-job/is-terminal                 (bg-jobs/terminal-status? (:status job))
   :psi.background-job/is-non-terminal             (bg-jobs/non-terminal-status? (:status job))})

(defn- session-thread-id
  [agent-session-ctx]
  (:session-id (session/get-session-data-in agent-session-ctx)))

(defn- reconcile-workflow-background-jobs!
  [agent-session-ctx]
  ((resolve 'psi.agent-session.background-job-runtime/reconcile-workflow-background-jobs-in!) agent-session-ctx))

(pco/defresolver agent-session-background-jobs
  "Resolve background jobs for the active session thread.
   Includes counts and status vocabulary for UI/query clients."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/background-job-count
                 :psi.agent-session/background-job-statuses
                 :psi.agent-session/background-jobs
                 {:psi.agent-session/background-jobs background-job-output}]}
  (reconcile-workflow-background-jobs! agent-session-ctx)
  (let [thread-id (session-thread-id agent-session-ctx)
        store     (session/get-state-value-in agent-session-ctx (session/state-path :background-jobs))
        jobs      (if thread-id
                    (bg-jobs/list-jobs-in store thread-id background-job-status-order)
                    [])]
    {:psi.agent-session/background-job-count    (count jobs)
     :psi.agent-session/background-job-statuses background-job-status-order
     :psi.agent-session/background-jobs         (mapv background-job->eql jobs)}))

(pco/defresolver agent-session-dispatch-registry
  "Resolve registered dispatch handler metadata."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/registered-dispatch-event-count
                 {:psi.agent-session/registered-dispatch-events
                  [:psi.dispatch-handler/event-type]}]}
  (let [_       agent-session-ctx
        types   (sort (dispatch/registered-event-types))
        entries (mapv (fn [event-type]
                        {:psi.dispatch-handler/event-type event-type})
                      types)]
    {:psi.agent-session/registered-dispatch-event-count (count entries)
     :psi.agent-session/registered-dispatch-events entries}))

(pco/defresolver agent-session-dispatch-event-log
  "Resolve the retained dispatch event log from the dispatch pipeline."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/dispatch-event-log-count
                 {:psi.agent-session/dispatch-event-log
                  [:psi.dispatch-event/event-type
                   :psi.dispatch-event/event-data
                   :psi.dispatch-event/origin
                   :psi.dispatch-event/ext-id
                   :psi.dispatch-event/blocked?
                   :psi.dispatch-event/block-reason
                   :psi.dispatch-event/replaying?
                   :psi.dispatch-event/statechart-claimed?
                   :psi.dispatch-event/validation-error
                   :psi.dispatch-event/pure-result-kind
                   :psi.dispatch-event/declared-effects
                   :psi.dispatch-event/applied-effects
                   :psi.dispatch-event/db-summary-before
                   :psi.dispatch-event/db-summary-after
                   :psi.dispatch-event/timestamp
                   :psi.dispatch-event/duration-ms]}]}
  (let [_       agent-session-ctx
        entries (dispatch/event-log-entries)
        entry->eql (fn [entry]
                     {:psi.dispatch-event/event-type          (:event-type entry)
                      :psi.dispatch-event/event-data          (:event-data entry)
                      :psi.dispatch-event/origin              (:origin entry)
                      :psi.dispatch-event/ext-id              (:ext-id entry)
                      :psi.dispatch-event/blocked?            (:blocked? entry)
                      :psi.dispatch-event/block-reason        (:block-reason entry)
                      :psi.dispatch-event/replaying?          (:replaying? entry)
                      :psi.dispatch-event/statechart-claimed? (:statechart-claimed? entry)
                      :psi.dispatch-event/validation-error    (:validation-error entry)
                      :psi.dispatch-event/pure-result-kind    (:pure-result-kind entry)
                      :psi.dispatch-event/declared-effects    (:declared-effects entry)
                      :psi.dispatch-event/applied-effects     (:applied-effects entry)
                      :psi.dispatch-event/db-summary-before   (:db-summary-before entry)
                      :psi.dispatch-event/db-summary-after    (:db-summary-after entry)
                      :psi.dispatch-event/timestamp           (:timestamp entry)
                      :psi.dispatch-event/duration-ms         (:duration-ms entry)})]
    {:psi.agent-session/dispatch-event-log-count (count entries)
     :psi.agent-session/dispatch-event-log       (mapv entry->eql entries)}))

;; ── Context usage ───────────────────────────────────────

(pco/defresolver agent-session-context-usage
  "Resolve context token usage and fraction."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/context-tokens
                 :psi.agent-session/context-window
                 :psi.agent-session/context-fraction]}
  (let [sd (session/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/context-tokens   (:context-tokens sd)
     :psi.agent-session/context-window   (:context-window sd)
     :psi.agent-session/context-fraction (session/context-fraction-used sd)}))

;; ── Tool-output policy and telemetry ───────────────────

(defn- tool-output-call->eql
  [call]
  {:psi.tool-output.call/tool-call-id        (:tool-call-id call)
   :psi.tool-output.call/tool-name           (:tool-name call)
   :psi.tool-output.call/timestamp           (:timestamp call)
   :psi.tool-output.call/limit-hit?          (:limit-hit call)
   :psi.tool-output.call/truncated-by        (:truncated-by call)
   :psi.tool-output.call/effective-max-lines (:effective-max-lines call)
   :psi.tool-output.call/effective-max-bytes (:effective-max-bytes call)
   :psi.tool-output.call/output-bytes        (:output-bytes call)
   :psi.tool-output.call/context-bytes-added (:context-bytes-added call)})

(pco/defresolver tool-output-policy
  "Resolve tool-output defaults and per-tool overrides."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.tool-output/default-max-lines
                 :psi.tool-output/default-max-bytes
                 :psi.tool-output/overrides]}
  {:psi.tool-output/default-max-lines tool-output/default-max-lines
   :psi.tool-output/default-max-bytes tool-output/default-max-bytes
   :psi.tool-output/overrides         (or (:tool-output-overrides
                                           (session/get-session-data-in agent-session-ctx))
                                          {})})

(pco/defresolver tool-output-calls
  "Resolve per-call tool-output telemetry records."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.tool-output/calls
                  [:psi.tool-output.call/tool-call-id
                   :psi.tool-output.call/tool-name
                   :psi.tool-output.call/timestamp
                   :psi.tool-output.call/limit-hit?
                   :psi.tool-output.call/truncated-by
                   :psi.tool-output.call/effective-max-lines
                   :psi.tool-output.call/effective-max-bytes
                   :psi.tool-output.call/output-bytes
                   :psi.tool-output.call/context-bytes-added]}]}
  {:psi.tool-output/calls
   (let [sid (ss/active-session-id-in agent-session-ctx)]
     (mapv tool-output-call->eql
           (or (:calls (session/get-state-value-in agent-session-ctx (session/state-path :tool-output-stats sid))) [])))})

(pco/defresolver tool-output-stats
  "Resolve aggregate tool-output telemetry."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.tool-output/stats]}
  {:psi.tool-output/stats
   (let [sid        (ss/active-session-id-in agent-session-ctx)
         aggregates (:aggregates (session/get-state-value-in agent-session-ctx (session/state-path :tool-output-stats sid)))]
     {:total-context-bytes (or (:total-context-bytes aggregates) 0)
      :by-tool             (or (:by-tool aggregates) {})
      :limit-hits-by-tool  (or (:limit-hits-by-tool aggregates) {})})})

;; ── Journal ─────────────────────────────────────────────

(pco/defresolver agent-session-journal
  "Resolve session entry count, flush state, and full entry list."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/session-entry-count
                 :psi.agent-session/journal-flushed?
                 {:psi.agent-session/session-entries
                  [:psi.session-entry/id
                   :psi.session-entry/parent-id
                   :psi.session-entry/timestamp
                   :psi.session-entry/kind
                   :psi.session-entry/data]}]}
  (let [entries  ((resolve 'psi.agent-session.state-accessors/journal-state-in) agent-session-ctx)
        flushed? (:flushed? ((resolve 'psi.agent-session.state-accessors/flush-state-in) agent-session-ctx))]
    {:psi.agent-session/session-entry-count (count entries)
     :psi.agent-session/journal-flushed?    flushed?
     :psi.agent-session/session-entries
     (mapv (fn [e]
             {:psi.session-entry/id        (:id e)
              :psi.session-entry/parent-id (:parent-id e)
              :psi.session-entry/timestamp (:timestamp e)
              :psi.session-entry/kind      (:kind e)
              :psi.session-entry/data      (:data e)})
           entries)}))

;; ── Stats snapshot ──────────────────────────────────────

(defn- stats-snapshot
  "Build canonical session telemetry stats from current session/journal state.

   Count semantics:
   - :tool-calls counts committed `toolResult` messages in the journal/transcript
   - this may differ from tool lifecycle summary count, which is derived from
     canonical lifecycle telemetry in `:tool-lifecycle-events`"
  [agent-session-ctx]
  (let [sd      (session/get-session-data-in agent-session-ctx)
        journal ((resolve 'psi.agent-session.state-accessors/journal-state-in) agent-session-ctx)
        msgs    (keep #(when (= :message (:kind %)) (get-in % [:data :message])) journal)]
    {:session-id         (:session-id sd)
     :session-file       (:session-file sd)
     :user-messages      (count (filter #(= "user" (:role %)) msgs))
     :assistant-messages (count (filter #(= "assistant" (:role %)) msgs))
     :ai-calls           (count (filter #(and (= "assistant" (:role %))
                                              (map? (:usage %)))
                                        msgs))
     :tool-calls         (count (filter #(= "toolResult" (:role %)) msgs))
     :total-messages     (count msgs)
     :entry-count        (count journal)
     :context-tokens     (:context-tokens sd)
     :context-window     (:context-window sd)}))

(defn- canonical-start-time
  [agent-session-ctx]
  (let [sd      (session/get-session-data-in agent-session-ctx)
        startup (:startup-bootstrap sd)
        journal ((resolve 'psi.agent-session.state-accessors/journal-state-in) agent-session-ctx)
        first-ts (:timestamp (first journal))]
    (or (:timestamp startup)
        first-ts
        (java.time.Instant/now))))

(pco/defresolver agent-session-canonical-telemetry
  "Resolve canonical top-level telemetry attrs from the same source as :psi.agent-session/stats.
   Time attrs intentionally return java.time.Instant for stable in-process representation.

   Count semantics:
   - :psi.agent-session/tool-call-count is journal/transcript-based
   - :psi.agent-session/executed-tool-count is canonical lifecycle-summary-based
   - :psi.agent-session/tool-lifecycle-summary-count is canonical lifecycle-telemetry-based"
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/messages-count
                 :psi.agent-session/ai-call-count
                 :psi.agent-session/tool-call-count
                 :psi.agent-session/executed-tool-count
                 :psi.agent-session/start-time
                 :psi.agent-session/current-time]}
  (let [stats               (stats-snapshot agent-session-ctx)
        executed-tool-count (count (tool-lifecycle-summaries agent-session-ctx))]
    {:psi.agent-session/messages-count      (:total-messages stats)
     :psi.agent-session/ai-call-count       (:ai-calls stats)
     :psi.agent-session/tool-call-count     (:tool-calls stats)
     :psi.agent-session/executed-tool-count executed-tool-count
     :psi.agent-session/start-time          (canonical-start-time agent-session-ctx)
     :psi.agent-session/current-time        (java.time.Instant/now)}))

(pco/defresolver agent-session-stats
  "Resolve a SessionStats snapshot."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/stats]}
  {:psi.agent-session/stats (stats-snapshot agent-session-ctx)})

;; ── Tool call history ───────────────────────────────────
;;
;; Canonical executed-tool history lives in tool lifecycle telemetry:
;;   - :psi.agent-session/tool-lifecycle-events      ; canonical event stream
;;   - :psi.agent-session/tool-lifecycle-summaries   ; canonical grouped read model
;;   - :psi.agent-session/executed-tool-count        ; canonical grouped count

(defn- agent-core-messages
  "Extract the message vec from agent-core inside a session context."
  [agent-session-ctx]
  (:messages (psi.agent-core.core/get-data-in (ss/agent-ctx-in agent-session-ctx))))

(defn- utf8-byte-count
  [s]
  (count (.getBytes (str (or s "")) "UTF-8")))

(defn- tool-call-attempt-events
  [agent-session-ctx]
  (let [sid (ss/active-session-id-in agent-session-ctx)]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :tool-call-attempts sid))
             []))))

(defn- tool-result-ids
  [agent-session-ctx]
  (->> (agent-core-messages agent-session-ctx)
       (filter #(= "toolResult" (:role %)))
       (keep :tool-call-id)
       set))

(pco/defresolver agent-session-tool-call-attempts
  "Resolve streamed provider tool-call attempts.

   Attempts are captured during provider streaming (:toolcall-start/:delta/:end),
   then correlated with committed toolResult messages to identify unmatched calls."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input
   [:psi/agent-session-ctx]
   ::pco/output
   [:psi.agent-session/tool-call-attempt-count
    :psi.agent-session/tool-call-attempt-unmatched-count
    {:psi.agent-session/tool-call-attempts
     [:psi.tool-call-attempt/id
      :psi.tool-call-attempt/name
      :psi.tool-call-attempt/content-index
      :psi.tool-call-attempt/turn-id
      :psi.tool-call-attempt/status
      :psi.tool-call-attempt/started-at
      :psi.tool-call-attempt/ended-at
      :psi.tool-call-attempt/delta-count
      :psi.tool-call-attempt/argument-bytes
      :psi.tool-call-attempt/executed?
      :psi.tool-call-attempt/result-recorded?]}]}
  (let [events      (tool-call-attempt-events agent-session-ctx)
        result-ids  (tool-result-ids agent-session-ctx)
        attempts    (->> events
                         (reduce (fn [acc {:keys [event-kind turn-id content-index id name delta timestamp]}]
                                   (let [k   [turn-id content-index]
                                         cur (get acc k
                                                  {:psi.tool-call-attempt/id            nil
                                                   :psi.tool-call-attempt/name          nil
                                                   :psi.tool-call-attempt/content-index content-index
                                                   :psi.tool-call-attempt/turn-id       turn-id
                                                   :psi.tool-call-attempt/started-at    nil
                                                   :psi.tool-call-attempt/ended-at      nil
                                                   :psi.tool-call-attempt/delta-count   0
                                                   :psi.tool-call-attempt/argument-bytes 0})]
                                     (case event-kind
                                       :toolcall-start
                                       (assoc acc k
                                              (-> cur
                                                  (assoc :psi.tool-call-attempt/id (or id (:psi.tool-call-attempt/id cur)))
                                                  (assoc :psi.tool-call-attempt/name (or name (:psi.tool-call-attempt/name cur)))
                                                  (assoc :psi.tool-call-attempt/content-index content-index)
                                                  (assoc :psi.tool-call-attempt/turn-id turn-id)
                                                  (assoc :psi.tool-call-attempt/started-at
                                                         (or (:psi.tool-call-attempt/started-at cur) timestamp))))

                                       :toolcall-delta
                                       (assoc acc k
                                              (-> cur
                                                  (assoc :psi.tool-call-attempt/content-index content-index)
                                                  (assoc :psi.tool-call-attempt/turn-id turn-id)
                                                  (update :psi.tool-call-attempt/delta-count (fnil inc 0))
                                                  (update :psi.tool-call-attempt/argument-bytes (fnil + 0)
                                                          (utf8-byte-count delta))))

                                       :toolcall-end
                                       (assoc acc k
                                              (-> cur
                                                  (assoc :psi.tool-call-attempt/id (or id (:psi.tool-call-attempt/id cur)))
                                                  (assoc :psi.tool-call-attempt/name (or name (:psi.tool-call-attempt/name cur)))
                                                  (assoc :psi.tool-call-attempt/content-index content-index)
                                                  (assoc :psi.tool-call-attempt/turn-id turn-id)
                                                  (assoc :psi.tool-call-attempt/ended-at
                                                         (or (:psi.tool-call-attempt/ended-at cur) timestamp))))

                                       acc)))
                                 {})
                         vals
                         (sort-by (juxt :psi.tool-call-attempt/started-at
                                        :psi.tool-call-attempt/turn-id
                                        :psi.tool-call-attempt/content-index))
                         vec)
        attempts*   (mapv (fn [attempt]
                            (let [id       (:psi.tool-call-attempt/id attempt)
                                  recorded? (and (string? id)
                                                 (contains? result-ids id))
                                  status   (cond
                                             recorded? :result-recorded
                                             (:psi.tool-call-attempt/ended-at attempt) :ended
                                             (:psi.tool-call-attempt/started-at attempt) :started
                                             :else :partial)]
                              (assoc attempt
                                     :psi.tool-call-attempt/status status
                                     :psi.tool-call-attempt/executed? recorded?
                                     :psi.tool-call-attempt/result-recorded? recorded?)))
                          attempts)
        unmatched   (count (filter (complement :psi.tool-call-attempt/result-recorded?)
                                   attempts*))]
    {:psi.agent-session/tool-call-attempt-count           (count attempts*)
     :psi.agent-session/tool-call-attempt-unmatched-count unmatched
     :psi.agent-session/tool-call-attempts                attempts*}))

(defn- tool-lifecycle-events
  [agent-session-ctx]
  (let [sid (ss/active-session-id-in agent-session-ctx)]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :tool-lifecycle-events sid))
             []))))

(defn- tool-lifecycle-event->eql
  [event]
  {:psi.tool-lifecycle/event-kind (:event-kind event)
   :psi.tool-lifecycle/tool-id    (:tool-id event)
   :psi.tool-lifecycle/tool-name  (:tool-name event)
   :psi.tool-lifecycle/timestamp  (:timestamp event)
   :psi.tool-lifecycle/details    (:details event)
   :psi.tool-lifecycle/is-error   (:is-error event)
   :psi.tool-lifecycle/content    (:content event)
   :psi.tool-lifecycle/result-text (:result-text event)
   :psi.tool-lifecycle/arguments  (:arguments event)
   :psi.tool-lifecycle/parsed-args (:parsed-args event)})

(defn- tool-lifecycle-summaries
  [agent-session-ctx]
  (->> (tool-lifecycle-events agent-session-ctx)
       (reduce (fn [acc {:keys [tool-id tool-name event-kind timestamp is-error result-text arguments parsed-args]}]
                 (let [k   tool-id
                       cur (get acc k {:psi.tool-lifecycle.summary/tool-id k
                                       :psi.tool-lifecycle.summary/tool-name tool-name
                                       :psi.tool-lifecycle.summary/event-count 0
                                       :psi.tool-lifecycle.summary/last-event-kind nil
                                       :psi.tool-lifecycle.summary/started-at nil
                                       :psi.tool-lifecycle.summary/last-updated-at nil
                                       :psi.tool-lifecycle.summary/completed? false
                                       :psi.tool-lifecycle.summary/is-error false
                                       :psi.tool-lifecycle.summary/result-text nil
                                       :psi.tool-lifecycle.summary/arguments nil
                                       :psi.tool-lifecycle.summary/parsed-args nil})]
                   (assoc acc k
                          (-> cur
                              (assoc :psi.tool-lifecycle.summary/tool-name
                                     (or tool-name (:psi.tool-lifecycle.summary/tool-name cur)))
                              (update :psi.tool-lifecycle.summary/event-count (fnil inc 0))
                              (assoc :psi.tool-lifecycle.summary/last-event-kind event-kind)
                              (assoc :psi.tool-lifecycle.summary/last-updated-at timestamp)
                              (update :psi.tool-lifecycle.summary/started-at
                                      #(or % (when (= :tool-start event-kind) timestamp) timestamp))
                              (assoc :psi.tool-lifecycle.summary/completed?
                                     (boolean (contains? #{:tool-result} event-kind)))
                              (assoc :psi.tool-lifecycle.summary/is-error (boolean is-error))
                              (assoc :psi.tool-lifecycle.summary/result-text
                                     (or result-text (:psi.tool-lifecycle.summary/result-text cur)))
                              (update :psi.tool-lifecycle.summary/arguments
                                      #(or % arguments))
                              (update :psi.tool-lifecycle.summary/parsed-args
                                      #(or % parsed-args))))))
               {})
       vals
       (sort-by (juxt :psi.tool-lifecycle.summary/started-at
                      :psi.tool-lifecycle.summary/tool-id))
       vec))

(pco/defresolver agent-session-tool-lifecycle-events
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input
   [:psi/agent-session-ctx]
   ::pco/output
   [:psi.agent-session/tool-lifecycle-event-count
    :psi.agent-session/tool-lifecycle-summary-count
    {:psi.agent-session/tool-lifecycle-events
     [:psi.tool-lifecycle/event-kind
      :psi.tool-lifecycle/tool-id
      :psi.tool-lifecycle/tool-name
      :psi.tool-lifecycle/timestamp
      :psi.tool-lifecycle/details
      :psi.tool-lifecycle/is-error
      :psi.tool-lifecycle/content
      :psi.tool-lifecycle/result-text
      :psi.tool-lifecycle/arguments
      :psi.tool-lifecycle/parsed-args]}
    {:psi.agent-session/tool-lifecycle-summaries
     [:psi.tool-lifecycle.summary/tool-id
      :psi.tool-lifecycle.summary/tool-name
      :psi.tool-lifecycle.summary/event-count
      :psi.tool-lifecycle.summary/last-event-kind
      :psi.tool-lifecycle.summary/started-at
      :psi.tool-lifecycle.summary/last-updated-at
      :psi.tool-lifecycle.summary/completed?
      :psi.tool-lifecycle.summary/is-error
      :psi.tool-lifecycle.summary/result-text
      :psi.tool-lifecycle.summary/arguments
      :psi.tool-lifecycle.summary/parsed-args]}]}
  (let [events    (mapv tool-lifecycle-event->eql
                        (tool-lifecycle-events agent-session-ctx))
        summaries (tool-lifecycle-summaries agent-session-ctx)]
    {:psi.agent-session/tool-lifecycle-event-count   (count events)
     :psi.agent-session/tool-lifecycle-events        events
     :psi.agent-session/tool-lifecycle-summary-count (count summaries)
     :psi.agent-session/tool-lifecycle-summaries     summaries}))

(pco/defresolver tool-lifecycle-summary-by-tool-id
  [{:keys [psi.agent-session/lookup-tool-id psi/agent-session-ctx]}]
  {::pco/input
   [:psi.agent-session/lookup-tool-id :psi/agent-session-ctx]
   ::pco/output
   [{:psi.agent-session/tool-lifecycle-summary-for-tool-id
     [:psi.tool-lifecycle.summary/tool-id
      :psi.tool-lifecycle.summary/tool-name
      :psi.tool-lifecycle.summary/event-count
      :psi.tool-lifecycle.summary/last-event-kind
      :psi.tool-lifecycle.summary/started-at
      :psi.tool-lifecycle.summary/last-updated-at
      :psi.tool-lifecycle.summary/completed?
      :psi.tool-lifecycle.summary/is-error
      :psi.tool-lifecycle.summary/result-text
      :psi.tool-lifecycle.summary/arguments
      :psi.tool-lifecycle.summary/parsed-args]}]}
  {:psi.agent-session/tool-lifecycle-summary-for-tool-id
   (some (fn [summary]
           (when (= lookup-tool-id (:psi.tool-lifecycle.summary/tool-id summary))
             summary))
         (tool-lifecycle-summaries agent-session-ctx))})

(defn- provider-requests
  [agent-session-ctx]
  (let [sid (ss/active-session-id-in agent-session-ctx)]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :provider-requests sid))
             []))))

(defn- provider-nonerror-replies
  [agent-session-ctx]
  (let [sid (ss/active-session-id-in agent-session-ctx)]
    (vec (or (session/get-state-value-in agent-session-ctx (session/state-path :provider-replies sid))
             []))))

(defn- provider-error-replies
  [agent-session-ctx]
  (vec (or (:provider-error-replies (session/get-session-data-in agent-session-ctx))
           [])))

(defn- provider-replies
  [agent-session-ctx]
  (let [nonerror (provider-nonerror-replies agent-session-ctx)
        errors   (provider-error-replies agent-session-ctx)]
    (if (seq errors)
      (->> (concat (remove #(= :error (get-in % [:event :type])) nonerror)
                   errors)
           (sort-by :timestamp)
           vec)
      nonerror)))

(defn- provider-request->eql
  [capture]
  {:psi.provider-request/provider  (:provider capture)
   :psi.provider-request/api       (:api capture)
   :psi.provider-request/url       (:url capture)
   :psi.provider-request/turn-id   (:turn-id capture)
   :psi.provider-request/timestamp (:timestamp capture)
   :psi.provider-request/headers   (get-in capture [:request :headers])
   :psi.provider-request/body      (get-in capture [:request :body])})

(defn- provider-reply->eql
  [capture]
  {:psi.provider-reply/provider  (:provider capture)
   :psi.provider-reply/api       (:api capture)
   :psi.provider-reply/url       (:url capture)
   :psi.provider-reply/turn-id   (:turn-id capture)
   :psi.provider-reply/timestamp (:timestamp capture)
   :psi.provider-reply/event     (:event capture)})

(pco/defresolver provider-request-by-turn-id
  "Resolve a single captured provider request by turn-id."
  [{:keys [psi.agent-session/lookup-turn-id psi/agent-session-ctx]}]
  {::pco/input  [:psi.agent-session/lookup-turn-id :psi/agent-session-ctx]
   ::pco/output [{:psi.agent-session/provider-request-for-turn-id
                  [:psi.provider-request/provider
                   :psi.provider-request/api
                   :psi.provider-request/url
                   :psi.provider-request/turn-id
                   :psi.provider-request/timestamp
                   :psi.provider-request/headers
                   :psi.provider-request/body]}]}
  {:psi.agent-session/provider-request-for-turn-id
   (some->> (provider-requests agent-session-ctx)
            (some (fn [capture]
                    (when (= lookup-turn-id (:turn-id capture))
                      (provider-request->eql capture)))))})

(pco/defresolver provider-reply-by-turn-id
  "Resolve a single captured provider reply by turn-id."
  [{:keys [psi.agent-session/lookup-turn-id psi/agent-session-ctx]}]
  {::pco/input  [:psi.agent-session/lookup-turn-id :psi/agent-session-ctx]
   ::pco/output [{:psi.agent-session/provider-reply-for-turn-id
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}]}
  {:psi.agent-session/provider-reply-for-turn-id
   (some->> (provider-replies agent-session-ctx)
            reverse
            (some (fn [capture]
                    (when (= lookup-turn-id (:turn-id capture))
                      (provider-reply->eql capture)))))})

(pco/defresolver agent-session-provider-captures
  "Resolve captured outbound provider requests and inbound provider reply events."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/provider-request-count
                 :psi.agent-session/provider-reply-count
                 {:psi.agent-session/provider-last-request
                  [:psi.provider-request/provider
                   :psi.provider-request/api
                   :psi.provider-request/url
                   :psi.provider-request/turn-id
                   :psi.provider-request/timestamp
                   :psi.provider-request/headers
                   :psi.provider-request/body]}
                 {:psi.agent-session/provider-last-reply
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}
                 {:psi.agent-session/provider-last-error-reply
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}
                 {:psi.agent-session/provider-requests
                  [:psi.provider-request/provider
                   :psi.provider-request/api
                   :psi.provider-request/url
                   :psi.provider-request/turn-id
                   :psi.provider-request/timestamp
                   :psi.provider-request/headers
                   :psi.provider-request/body]}
                 {:psi.agent-session/provider-replies
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}
                 {:psi.agent-session/provider-error-replies
                  [:psi.provider-reply/provider
                   :psi.provider-reply/api
                   :psi.provider-reply/url
                   :psi.provider-reply/turn-id
                   :psi.provider-reply/timestamp
                   :psi.provider-reply/event]}]}
  (let [requests*         (mapv provider-request->eql (provider-requests agent-session-ctx))
        raw-replies       (provider-replies agent-session-ctx)
        raw-error-replies (provider-error-replies agent-session-ctx)
        replies*          (mapv provider-reply->eql raw-replies)
        error-replies*    (mapv provider-reply->eql raw-error-replies)]
    {:psi.agent-session/provider-request-count   (count requests*)
     :psi.agent-session/provider-reply-count     (count replies*)
     :psi.agent-session/provider-last-request    (last requests*)
     :psi.agent-session/provider-last-reply      (last replies*)
     :psi.agent-session/provider-last-error-reply (last error-replies*)
     :psi.agent-session/provider-requests        requests*
     :psi.agent-session/provider-replies         replies*
     :psi.agent-session/provider-error-replies   error-replies*}))

;; ── API error diagnostics (helpers) ─────────────────────

(defn- error-message-text
  "Extract the first :error block text from an assistant message."
  [msg]
  (some #(when (= :error (:type %)) (:text %)) (:content msg)))

(defn- parse-request-id
  "Extract request-id from provider error text.
   Supports both old clj-http header-map formatting and the normalized
   `... [request-id req_xxx]` suffix emitted by provider adapters."
  [error-text]
  (when error-text
    (or (second (re-find #"\"request-id\"\s+\"([^\"]+)\"" error-text))
        (second (re-find #"\[request-id\s+([^\]\s]+)\]" error-text)))))

(defn- api-errors-from-messages
  [agent-session-ctx]
  (if-not (ss/agent-ctx-in agent-session-ctx)
    []
    (let [msgs (agent-core-messages agent-session-ctx)]
      (->> msgs
           (map-indexed vector)
           (filter (fn [[_ m]]
                     (and (= "assistant" (:role m))
                          (= :error (:stop-reason m)))))
           (mapv (fn [[idx m]]
                   (let [err-text (error-message-text m)
                         brief    (when err-text
                                    (subs err-text
                                          0 (min 120 (count err-text))))]
                     {:psi.api-error/message-index idx
                      :psi.api-error/http-status (:http-status m)
                      :psi.api-error/timestamp (:timestamp m)
                      :psi.api-error/error-message-brief brief
                      :psi.api-error/error-message-full err-text
                      :psi.api-error/request-id (parse-request-id err-text)
                      :psi/agent-session-ctx agent-session-ctx})))))))

(defn- provider-error-reply->api-error
  [agent-session-ctx idx capture]
  (let [event      (:event capture)
        err-text   (:error-message event)
        body       (:body event)
        body-text  (:body-text event)
        request-id (or (get-in event [:headers "request-id"])
                       (:request_id body)
                       (parse-request-id err-text))
        brief-src  (or err-text body-text (some-> body pr-str))
        brief      (when (seq brief-src)
                     (subs brief-src 0 (min 120 (count brief-src))))]
    {:psi.api-error/message-index idx
     :psi.api-error/http-status (:http-status event)
     :psi.api-error/timestamp (:timestamp capture)
     :psi.api-error/error-message-brief brief
     :psi.api-error/error-message-full err-text
     :psi.api-error/request-id request-id
     :psi.api-error/provider (:provider capture)
     :psi.api-error/api (:api capture)
     :psi.api-error/url (:url capture)
     :psi.api-error/turn-id (:turn-id capture)
     :psi.api-error/provider-event event
     :psi.api-error/provider-reply-capture capture
     :psi/agent-session-ctx agent-session-ctx}))

(defn- find-provider-reply-by-request-id
  [agent-session-ctx request-id]
  (when (seq request-id)
    (->> (provider-replies agent-session-ctx)
         reverse
         (some (fn [capture]
                 (let [event (:event capture)
                       body  (:body event)]
                   (when (= request-id
                            (or (get-in event [:headers "request-id"])
                                (:request_id body)
                                (parse-request-id (:error-message event))))
                     capture)))))))

(defn- enrich-api-error-from-provider-reply
  [agent-session-ctx error]
  (if (or (:psi.api-error/provider-event error)
          (not (:psi/agent-session-ctx error)))
    error
    (if-let [capture (find-provider-reply-by-request-id agent-session-ctx
                                                        (:psi.api-error/request-id error))]
      (merge error
             (dissoc (provider-error-reply->api-error agent-session-ctx
                                                      (:psi.api-error/message-index error)
                                                      capture)
                     :psi.api-error/message-index
                     :psi/agent-session-ctx))
      error)))

(defn- api-errors-from-provider-replies
  [agent-session-ctx]
  (->> (provider-replies agent-session-ctx)
       (keep-indexed (fn [idx capture]
                       (when (= :error (get-in capture [:event :type]))
                         (provider-error-reply->api-error agent-session-ctx idx capture))))
       vec))

(defn- dedupe-api-errors
  [errors]
  (->> errors
       (reduce (fn [acc error]
                 (let [k [(or (:psi.api-error/request-id error) ::no-request-id)
                          (or (:psi.api-error/error-message-full error)
                              (:psi.api-error/error-message-brief error)
                              ::no-message)]
                       existing (get acc k)]
                   (assoc acc k
                          (cond
                            (nil? existing)
                            error

                            (and (nil? (:psi.api-error/provider-event existing))
                                 (:psi.api-error/provider-event error))
                            (merge existing error)

                            :else
                            existing))))
               {})
       vals
       vec))

(defn- message-summary
  "Lightweight summary of an agent-core message for context display."
  [msg idx]
  (let [snippet (some (fn [c]
                        (case (:type c)
                          :text      (let [t (:text c)]
                                       (when (seq t)
                                         (subs t 0 (min 200 (count t)))))
                          :tool-call (str "[tool:" (:name c) "]")
                          :error     (let [t (:text c)]
                                       (when (seq t)
                                         (subs t 0 (min 200 (count t)))))
                          nil))
                      (:content msg))]
    {:psi.context-message/index         idx
     :psi.context-message/role          (:role msg)
     :psi.context-message/content-types (mapv :type (:content msg))
     :psi.context-message/snippet       (or snippet "")}))

(def ^:private request-shape-output
  "Shared output spec for :psi.request-shape/* attributes."
  [:psi.request-shape/message-count
   :psi.request-shape/system-prompt-chars
   :psi.request-shape/message-chars
   :psi.request-shape/tool-schema-chars
   :psi.request-shape/total-chars
   :psi.request-shape/estimated-tokens
   :psi.request-shape/context-window
   :psi.request-shape/max-output-tokens
   :psi.request-shape/headroom-tokens
   :psi.request-shape/role-distribution
   :psi.request-shape/tool-count
   :psi.request-shape/tool-use-count
   :psi.request-shape/tool-result-count
   :psi.request-shape/missing-tool-results
   :psi.request-shape/orphan-tool-results
   :psi.request-shape/alternation-valid?
   :psi.request-shape/alternation-violations
   :psi.request-shape/empty-content-count])

(defn- compute-request-shape
  "Compute request diagnostics from agent-core messages.
   Provider-agnostic: estimates tokens from serialized char count."
  [system-prompt messages tools context-window max-output-tokens]
  (let [;; Role counts
        role-counts   (frequencies (map :role messages))

        ;; Tool pairing
        tool-use-ids  (into #{}
                            (comp (filter #(= "assistant" (:role %)))
                                  (mapcat :content)
                                  (filter #(= :tool-call (:type %)))
                                  (map :id))
                            messages)
        tool-result-ids (into #{}
                              (comp (filter #(= "toolResult" (:role %)))
                                    (map :tool-call-id))
                              messages)

        ;; Size estimates (pr-str approximates wire format)
        sys-chars  (count (str system-prompt))
        msg-chars  (transduce (map #(count (pr-str %))) + 0 messages)
        tool-chars (transduce (map #(count (pr-str %))) + 0 tools)
        total      (+ sys-chars msg-chars tool-chars)
        est-tokens (quot total 4)
        headroom   (- context-window est-tokens max-output-tokens)

        ;; Alternation: map toolResult→user, deduplicate consecutive same roles
        api-roles  (->> messages
                        (keep #(case (:role %)
                                 "user"       "user"
                                 "assistant"  "assistant"
                                 "toolResult" "user"
                                 nil)))
        merged     (reduce (fn [acc r]
                             (if (= r (peek acc)) acc (conj acc r)))
                           [] api-roles)
        violations (count (filter (fn [[a b]] (= a b))
                                  (partition 2 1 merged)))

        ;; Empty content
        empty-ct   (count (filter #(empty? (:content %)) messages))]

    {:psi.request-shape/message-count          (count messages)
     :psi.request-shape/system-prompt-chars    sys-chars
     :psi.request-shape/message-chars          msg-chars
     :psi.request-shape/tool-schema-chars      tool-chars
     :psi.request-shape/total-chars            total
     :psi.request-shape/estimated-tokens       est-tokens
     :psi.request-shape/context-window         context-window
     :psi.request-shape/max-output-tokens      max-output-tokens
     :psi.request-shape/headroom-tokens        headroom
     :psi.request-shape/role-distribution      role-counts
     :psi.request-shape/tool-count             (count tools)
     :psi.request-shape/tool-use-count         (count tool-use-ids)
     :psi.request-shape/tool-result-count      (count tool-result-ids)
     :psi.request-shape/missing-tool-results   (count (set/difference tool-use-ids tool-result-ids))
     :psi.request-shape/orphan-tool-results    (count (set/difference tool-result-ids tool-use-ids))
     :psi.request-shape/alternation-valid?     (zero? violations)
     :psi.request-shape/alternation-violations violations
     :psi.request-shape/empty-content-count    empty-ct}))

(defn- resolve-context-window
  "Best-effort context window from session data or model config atom."
  [agent-session-ctx]
  (or (:context-window (session/get-session-data-in agent-session-ctx))
      (some-> (:model-config-atom agent-session-ctx) deref :context-window)
      200000))

(defn- resolve-max-output-tokens
  "Best-effort max output tokens from session data or model config atom."
  [agent-session-ctx]
  (or (some-> (:model-config-atom agent-session-ctx) deref :max-tokens)
      16384))

;; ── Level 1: API error list (cheap) ────────────────────

(pco/defresolver api-error-list
  "Extract API errors from assistant messages and provider reply captures.
   Message-derived errors preserve conversation position.
   Provider reply errors expose raw provider failures even when no assistant
   error message was persisted."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/api-error-count
                 {:psi.agent-session/api-errors
                  [:psi.api-error/message-index
                   :psi.api-error/http-status
                   :psi.api-error/timestamp
                   :psi.api-error/error-message-brief
                   :psi.api-error/error-message-full
                   :psi.api-error/request-id
                   :psi.api-error/provider
                   :psi.api-error/api
                   :psi.api-error/url
                   :psi.api-error/turn-id
                   :psi.api-error/provider-event
                   :psi/agent-session-ctx]}]}
  (let [message-errors  (mapv #(enrich-api-error-from-provider-reply agent-session-ctx %)
                              (api-errors-from-messages agent-session-ctx))
        provider-errors (api-errors-from-provider-replies agent-session-ctx)
        errors          (dedupe-api-errors (vec (concat message-errors provider-errors)))]
    {:psi.agent-session/api-error-count (count errors)
     :psi.agent-session/api-errors      errors}))

;; ── Level 2: API error detail (moderate) ────────────────

(pco/defresolver api-error-detail
  "Resolve full error text, request-id, provider metadata, and surrounding message context.
   Seeded by :psi.api-error/message-index from the list resolver."
  [{:keys [psi.api-error/message-index psi/agent-session-ctx]
    :as entity}]
  {::pco/input  [:psi.api-error/message-index :psi/agent-session-ctx]
   ::pco/output [:psi.api-error/error-message-full
                 :psi.api-error/request-id
                 :psi.api-error/provider
                 :psi.api-error/api
                 :psi.api-error/url
                 :psi.api-error/turn-id
                 :psi.api-error/provider-event
                 {:psi.api-error/surrounding-messages
                  [:psi.context-message/index
                   :psi.context-message/role
                   :psi.context-message/content-types
                   :psi.context-message/snippet]}]}
  (let [msgs              (agent-core-messages agent-session-ctx)
        msg               (nth msgs message-index nil)
        err-text          (or (:psi.api-error/error-message-full entity)
                              (when msg (error-message-text msg)))
        provider-event?   (and (nil? msg)
                               (some? (:psi.api-error/provider-event entity)))
        surr              (if provider-event?
                            []
                            (let [start (max 0 (- message-index 5))
                                  end   (min (count msgs) (+ message-index 3))]
                              (mapv #(message-summary (nth msgs %) %) (range start end))))]
    {:psi.api-error/error-message-full   err-text
     :psi.api-error/request-id           (or (:psi.api-error/request-id entity)
                                             (parse-request-id err-text))
     :psi.api-error/provider             (:psi.api-error/provider entity)
     :psi.api-error/api                  (:psi.api-error/api entity)
     :psi.api-error/url                  (:psi.api-error/url entity)
     :psi.api-error/turn-id              (:psi.api-error/turn-id entity)
     :psi.api-error/provider-event       (:psi.api-error/provider-event entity)
     :psi.api-error/surrounding-messages surr}))

;; ── Level 3: API error request shape (expensive) ────────

(pco/defresolver api-error-request-shape
  "Reconstruct the request shape at the point of an API error.
   Uses messages[0..message-index) — what was sent when the error occurred.
   Expensive: full message scan + size estimation."
  [{:keys [psi.api-error/message-index psi/agent-session-ctx]}]
  {::pco/input  [:psi.api-error/message-index :psi/agent-session-ctx]
   ::pco/output [{:psi.api-error/request-shape request-shape-output}]}
  (let [data      (agent/get-data-in (ss/agent-ctx-in agent-session-ctx))
        msgs      (:messages data)
        pre-error (subvec (vec msgs) 0 (min message-index (count msgs)))]
    {:psi.api-error/request-shape
     (compute-request-shape (:system-prompt data)
                            pre-error
                            (:tools data)
                            (resolve-context-window agent-session-ctx)
                            (resolve-max-output-tokens agent-session-ctx))}))

;; ── Current request shape (live diagnostics) ────────────

(pco/defresolver current-request-shape
  "Request shape for the current conversation state.
   Answers: 'if I send a prompt now, what does the context look like?'
   Expensive: full message scan + size estimation."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.agent-session/request-shape request-shape-output}]}
  (let [data (agent/get-data-in (ss/agent-ctx-in agent-session-ctx))]
    {:psi.agent-session/request-shape
     (compute-request-shape (:system-prompt data)
                            (:messages data)
                            (:tools data)
                            (resolve-context-window agent-session-ctx)
                            (resolve-max-output-tokens agent-session-ctx))}))

;; ── Turn streaming state ────────────────────────────────

(pco/defresolver agent-session-turn
  "Resolve per-turn streaming statechart state.
   Returns nil/empty values when no turn is active."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.turn/phase
                 :psi.turn/is-streaming
                 :psi.turn/text
                 :psi.turn/tool-calls
                 :psi.turn/tool-call-count
                 :psi.turn/final-message
                 :psi.turn/error-message
                 :psi.turn/last-provider-event
                 :psi.turn/content-blocks
                 :psi.turn/is-text-accumulating
                 :psi.turn/is-tool-accumulating
                 :psi.turn/is-done
                 :psi.turn/is-error]}
  (if-let [turn-ctx ((resolve 'psi.agent-session.state-accessors/turn-context-in) agent-session-ctx)]
    (let [phase (turn-sc/turn-phase turn-ctx)
          td    (turn-sc/get-turn-data turn-ctx)]
      {:psi.turn/phase                phase
       :psi.turn/is-streaming         (boolean (#{:text-accumulating :tool-accumulating} phase))
       :psi.turn/text                 (:text-buffer td)
       :psi.turn/tool-calls           (vec (vals (:tool-calls td)))
       :psi.turn/tool-call-count      (count (:tool-calls td))
       :psi.turn/final-message        (:final-message td)
       :psi.turn/error-message        (:error-message td)
       :psi.turn/last-provider-event  (:last-provider-event td)
       :psi.turn/content-blocks       (vec (vals (:content-blocks td)))
       :psi.turn/is-text-accumulating (= :text-accumulating phase)
       :psi.turn/is-tool-accumulating (= :tool-accumulating phase)
       :psi.turn/is-done              (= :done phase)
       :psi.turn/is-error             (= :error phase)})
    {:psi.turn/phase                nil
     :psi.turn/is-streaming         false
     :psi.turn/text                 nil
     :psi.turn/tool-calls           []
     :psi.turn/tool-call-count      0
     :psi.turn/final-message        nil
     :psi.turn/error-message        nil
     :psi.turn/last-provider-event  nil
     :psi.turn/content-blocks       []
     :psi.turn/is-text-accumulating false
     :psi.turn/is-tool-accumulating false
     :psi.turn/is-done              false
     :psi.turn/is-error             false}))

;; ── Prompt template introspection ────────────────────────

(pco/defresolver prompt-template-summary-resolver
  "Resolve prompt template summary: count, names, per-source grouping."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.prompt-template/summary
                 :psi.prompt-template/names
                 :psi.prompt-template/count
                 :psi.prompt-template/by-source]}
  (let [templates (:prompt-templates (session/get-session-data-in agent-session-ctx))]
    {:psi.prompt-template/summary   (pt/template-summary templates)
     :psi.prompt-template/names     (pt/template-names templates)
     :psi.prompt-template/count     (count templates)
     :psi.prompt-template/by-source (pt/templates-by-source templates)}))

(pco/defresolver prompt-template-detail-resolver
  "Resolve a single enriched prompt template by name.
   Seed input: {:psi.prompt-template/name \"template-name\"}"
  [{:keys [psi/agent-session-ctx psi.prompt-template/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.prompt-template/name]
   ::pco/output [:psi.prompt-template/detail]}
  (let [templates (:prompt-templates (session/get-session-data-in agent-session-ctx))
        tpl       (pt/find-template templates name)]
    {:psi.prompt-template/detail
     (when tpl (pt/enrich-template tpl))}))

;; ── Skill introspection ──────────────────────────────────

(pco/defresolver skill-summary-resolver
  "Resolve skill summary: count, visible/hidden counts, per-source grouping."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.skill/summary
                 :psi.skill/names
                 :psi.skill/count
                 :psi.skill/visible-count
                 :psi.skill/hidden-count
                 :psi.skill/by-source]}
  (let [all-skills (:skills (session/get-session-data-in agent-session-ctx))
        summary    (skills/skill-summary all-skills)]
    {:psi.skill/summary       summary
     :psi.skill/names         (skills/skill-names all-skills)
     :psi.skill/count         (:skill-count summary)
     :psi.skill/visible-count (:visible-count summary)
     :psi.skill/hidden-count  (:hidden-count summary)
     :psi.skill/by-source     (skills/skills-by-source all-skills)}))

(pco/defresolver skill-detail-resolver
  "Resolve a single enriched skill by name.
   Seed input: {:psi.skill/name \"skill-name\"}"
  [{:keys [psi/agent-session-ctx psi.skill/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.skill/name]
   ::pco/output [:psi.skill/detail]}
  (let [all-skills (:skills (session/get-session-data-in agent-session-ctx))
        skill      (skills/find-skill all-skills name)]
    {:psi.skill/detail
     (when skill (skills/enrich-skill skill))}))

;; ── Tool introspection ───────────────────────────────────

(pco/defresolver tool-summary-resolver
  "Resolve active tool summary: count and names."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.tool/summary
                 :psi.tool/names
                 :psi.tool/count]}
  (let [tools (:tools (agent/get-data-in (ss/agent-ctx-in agent-session-ctx)))
        names (mapv :name tools)]
    {:psi.tool/summary {:tool-count (count tools)
                        :tools      (mapv #(select-keys % [:name :label :description]) tools)}
     :psi.tool/names   names
     :psi.tool/count   (count tools)}))

(pco/defresolver tool-detail-resolver
  "Resolve a single active tool by name.
   Seed input: {:psi.tool/name \"tool-name\"}"
  [{:keys [psi/agent-session-ctx psi.tool/name]}]
  {::pco/input  [:psi/agent-session-ctx :psi.tool/name]
   ::pco/output [:psi.tool/detail]}
  (let [tools (:tools (agent/get-data-in (ss/agent-ctx-in agent-session-ctx)))
        tool  (first (filter #(= (:name %) name) tools))]
    {:psi.tool/detail tool}))

;; ── Session listing ─────────────────────────────────────

(defn- session-info->eql
  "Convert a SessionInfo map to :psi.session-info/* attributes."
  [info]
  (let [worktree-path (or (:worktree-path info) (:cwd info))]
    {:psi.session-info/path                (:path info)
     :psi.session-info/id                  (:id info)
     :psi.session-info/cwd                 worktree-path
     :psi.session-info/worktree-path       worktree-path
     :psi.session-info/name                (:name info)
     :psi.session-info/parent-session-id   (:parent-session-id info)
     :psi.session-info/parent-session-path (:parent-session-path info)
     :psi.session-info/created             (:created info)
     :psi.session-info/modified            (:modified info)
     :psi.session-info/message-count       (:message-count info)
     :psi.session-info/first-message       (:first-message info)
     :psi.session-info/all-messages-text   (:all-messages-text info)}))

(pco/defresolver session-list-resolver
  "Resolve all sessions for the current session's cwd, sorted by modified desc."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.session/list
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/cwd
                   :psi.session-info/worktree-path
                   :psi.session-info/name
                   :psi.session-info/parent-session-id
                   :psi.session-info/parent-session-path
                   :psi.session-info/created
                   :psi.session-info/modified
                   :psi.session-info/message-count
                   :psi.session-info/first-message
                   :psi.session-info/all-messages-text]}]}
  {:psi.session/list
   (mapv session-info->eql
         (persist/list-sessions
          (persist/session-dir-for
           (or (:worktree-path (session/get-session-data-in agent-session-ctx))
               (:cwd agent-session-ctx)))))})

(pco/defresolver session-list-all-resolver
  "Resolve all sessions across all project directories, sorted by modified desc."
  [{_ctx :psi/agent-session-ctx}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.session/list-all
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/cwd
                   :psi.session-info/worktree-path
                   :psi.session-info/name
                   :psi.session-info/parent-session-id
                   :psi.session-info/parent-session-path
                   :psi.session-info/created
                   :psi.session-info/modified
                   :psi.session-info/message-count
                   :psi.session-info/first-message
                   :psi.session-info/all-messages-text]}]}
  {:psi.session/list-all
   (mapv session-info->eql (persist/list-all-sessions))})

;; ── Extension UI state ───────────────────────────────

(pco/defresolver extension-ui-resolver
  "Resolve extension UI state snapshot (read-only introspection)."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.ui/dialog-queue-empty?
                 :psi.ui/active-dialog
                 :psi.ui/pending-dialog-count
                 :psi.ui/widgets
                 :psi.ui/widget-specs
                 :psi.ui/statuses
                 :psi.ui/visible-notifications
                 :psi.ui/tool-renderers
                 :psi.ui/message-renderers]}
  (let [snap (ui-state/snapshot-state (get-in @(:state* agent-session-ctx) [:ui :extension-ui]))]
    (if snap
      {:psi.ui/dialog-queue-empty?   (:dialog-queue-empty? snap)
       :psi.ui/active-dialog         (:active-dialog snap)
       :psi.ui/pending-dialog-count  (:pending-dialog-count snap)
       :psi.ui/widgets               (:widgets snap)
       :psi.ui/widget-specs          (:widget-specs snap)
       :psi.ui/statuses              (:statuses snap)
       :psi.ui/visible-notifications (:visible-notifications snap)
       :psi.ui/tool-renderers        (:tool-renderers snap)
       :psi.ui/message-renderers     (:message-renderers snap)}
      {:psi.ui/dialog-queue-empty?   true
       :psi.ui/active-dialog         nil
       :psi.ui/pending-dialog-count  0
       :psi.ui/widgets               []
       :psi.ui/widget-specs          []
       :psi.ui/statuses              []
       :psi.ui/visible-notifications []
       :psi.ui/tool-renderers        []
       :psi.ui/message-renderers     []})))

;; ── Footer data ───────────────────────────────────────

(defn- usage-number
  [usage k1 k2]
  (let [v (or (get usage k1) (get usage k2) 0)]
    (if (number? v) v 0)))

(defn- usage-cost-total
  [usage]
  (let [v (or (get-in usage [:cost :total])
              (:cost-total usage)
              0.0)]
    (if (number? v) v 0.0)))

(defn- session-usage-totals
  [agent-session-ctx]
  (let [current-session-id (:session-id (session/get-session-data-in agent-session-ctx))]
    (reduce
     (fn [acc entry]
       (let [msg       (get-in entry [:data :message])
             entry-sid (:session-id entry)]
         (if (and (= :message (:kind entry))
                  (or (nil? entry-sid)
                      (= current-session-id entry-sid))
                  (= "assistant" (:role msg))
                  (map? (:usage msg)))
           (let [u (:usage msg)]
             (-> acc
                 (update :input + (usage-number u :input-tokens :input))
                 (update :output + (usage-number u :output-tokens :output))
                 (update :cache-read + (usage-number u :cache-read-tokens :cache-read))
                 (update :cache-write + (usage-number u :cache-write-tokens :cache-write))
                 (update :cost + (usage-cost-total u))))
           acc)))
     {:input 0 :output 0 :cache-read 0 :cache-write 0 :cost 0.0}
     (session/get-state-value-in agent-session-ctx (session/state-path :journal (ss/active-session-id-in agent-session-ctx))))))

(defn- find-git-head-path
  [cwd]
  (loop [dir (when cwd (java.io.File. cwd))]
    (when dir
      (let [git-path (java.io.File. dir ".git")]
        (if (.exists git-path)
          (try
            (cond
              (.isDirectory git-path)
              (let [head (java.io.File. git-path "HEAD")]
                (when (.exists head)
                  (.getAbsolutePath head)))

              (.isFile git-path)
              (let [content (str/trim (slurp git-path))]
                (when (str/starts-with? content "gitdir: ")
                  (let [git-dir (subs content 8)
                        head    (java.io.File. (java.io.File. dir git-dir) "HEAD")]
                    (when (.exists head)
                      (.getAbsolutePath head)))))

              :else nil)
            (catch Exception _ nil))
          (recur (.getParentFile dir)))))))

(defn- git-branch-from-cwd
  [cwd]
  (try
    (when-let [head-path (find-git-head-path cwd)]
      (let [content (str/trim (slurp head-path))]
        (if (str/starts-with? content "ref: refs/heads/")
          (subs content 16)
          "detached")))
    (catch Exception _ nil)))

(pco/defresolver agent-session-cwd
  "Resolve working directory for the current session context.
   Prefers session-bound worktree-path over process context cwd."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/cwd]}
  (let [sd (session/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/cwd (or (:worktree-path sd)
                                (:cwd agent-session-ctx))}))

(pco/defresolver agent-session-git-branch
  "Resolve current git branch for :psi.agent-session/cwd.
   Returns nil outside git repos and \"detached\" for detached HEAD."
  [{:keys [psi.agent-session/cwd]}]
  {::pco/input  [:psi.agent-session/cwd]
   ::pco/output [:psi.agent-session/git-branch]}
  {:psi.agent-session/git-branch (git-branch-from-cwd cwd)})

(pco/defresolver runtime-nrepl-info
  "Expose runtime nREPL endpoint information from canonical runtime metadata on session context.
   Returns nil attrs when nREPL is not running/registered."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.runtime/nrepl-host
                 :psi.runtime/nrepl-port
                 :psi.runtime/nrepl-endpoint]}
  (let [runtime* ((resolve 'psi.agent-session.state-accessors/nrepl-runtime-in) agent-session-ctx)
        host     (:host runtime*)
        port     (:port runtime*)
        endpoint (or (:endpoint runtime*)
                     (when (and (string? host) (integer? port))
                       (str host ":" port)))]
    {:psi.runtime/nrepl-host host
     :psi.runtime/nrepl-port port
     :psi.runtime/nrepl-endpoint endpoint}))

(pco/defresolver agent-session-git-context
  "Bridge resolver: derive :git/context from :psi.agent-session/cwd so
   history resolvers can be queried from in-session app-query-tool roots."
  [{:keys [psi.agent-session/cwd]}]
  {::pco/input  [:psi.agent-session/cwd]
   ::pco/output [:git/context]}
  (if (seq cwd)
    {:git/context (git/create-context cwd)}
    {}))

(pco/defresolver agent-session-usage-input
  "Resolve cumulative input tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-input]}
  {:psi.agent-session/usage-input (:input (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-output
  "Resolve cumulative output tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-output]}
  {:psi.agent-session/usage-output (:output (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-cache-read
  "Resolve cumulative cache-read tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-cache-read]}
  {:psi.agent-session/usage-cache-read (:cache-read (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-cache-write
  "Resolve cumulative cache-write tokens across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-cache-write]}
  {:psi.agent-session/usage-cache-write (:cache-write (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-usage-cost-total
  "Resolve cumulative total cost across assistant messages in the session journal."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/usage-cost-total]}
  {:psi.agent-session/usage-cost-total (:cost (session-usage-totals agent-session-ctx))})

(pco/defresolver agent-session-model-provider
  "Resolve active model provider string from session state."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-provider]}
  {:psi.agent-session/model-provider (:provider (:model (session/get-session-data-in agent-session-ctx)))})

(pco/defresolver agent-session-model-id
  "Resolve active model id from session state."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-id]}
  {:psi.agent-session/model-id (:id (:model (session/get-session-data-in agent-session-ctx)))})

(def ^:private thinking-level->reasoning-effort
  {:off nil
   :minimal "minimal"
   :low "low"
   :medium "medium"
   :high "high"
   :xhigh "high"})

(defn- effective-reasoning-effort
  [model thinking-level]
  (when (:reasoning model)
    (get thinking-level->reasoning-effort thinking-level "medium")))

(pco/defresolver agent-session-model-reasoning
  "Resolve whether the active model supports reasoning and effective effort."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-reasoning
                 :psi.agent-session/effective-reasoning-effort]}
  (let [sd    (session/get-session-data-in agent-session-ctx)
        model (:model sd)
        level (:thinking-level sd)]
    {:psi.agent-session/model-reasoning
     (boolean (:reasoning model))
     :psi.agent-session/effective-reasoning-effort
     (effective-reasoning-effort model level)}))

(defn- runtime-model-catalog
  "Return deterministic runtime model catalog for frontend selectors."
  []
  (->> ai-models/all-models
       vals
       (map (fn [m]
              {:provider  (name (:provider m))
               :id        (:id m)
               :name      (:name m)
               :reasoning (boolean (:supports-reasoning m))}))
       (sort-by (juxt :provider :id))
       vec))

(defn- authenticated-provider-ids
  "Return provider ids with configured auth for this session context.
   Delegates oauth projection refresh to the session core API."
  [agent-session-ctx]
  ((resolve 'psi.agent-session.state-accessors/refresh-oauth-authenticated-providers-in!) agent-session-ctx))

(pco/defresolver agent-session-model-catalog
  "Resolve runtime model catalog for frontend model selectors."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model-catalog]}
  (let [_ agent-session-ctx]
    {:psi.agent-session/model-catalog (runtime-model-catalog)}))

(pco/defresolver agent-session-authenticated-providers
  "Resolve provider ids with configured auth for this session."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/authenticated-providers
                 :psi.oauth/authenticated-providers
                 :psi.oauth/last-login-provider
                 :psi.oauth/last-login-at
                 :psi.oauth/pending-login]}
  (let [ids   (authenticated-provider-ids agent-session-ctx)
        oauth-state (or ((resolve 'psi.agent-session.state-accessors/oauth-projection-in) agent-session-ctx) {})]
    {:psi.agent-session/authenticated-providers ids
     :psi.oauth/authenticated-providers ids
     :psi.oauth/last-login-provider (:last-login-provider oauth-state)
     :psi.oauth/last-login-at (:last-login-at oauth-state)
     :psi.oauth/pending-login (:pending-login oauth-state)}))

(pco/defresolver agent-session-rpc-trace
  "Resolve RPC trace runtime config for the current session transport."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/rpc-trace-enabled
                 :psi.agent-session/rpc-trace-file]}
  (let [trace-state (or ((resolve 'psi.agent-session.state-accessors/rpc-trace-state-in) agent-session-ctx) {})]
    {:psi.agent-session/rpc-trace-enabled (boolean (:enabled? trace-state))
     :psi.agent-session/rpc-trace-file (:file trace-state)}))

(pco/defresolver startup-prompts-resolver
  "Resolve startup prompt execution telemetry for the current session."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/startup-prompts
                 :psi.agent-session/startup-bootstrap-completed?
                 :psi.agent-session/startup-bootstrap-started-at
                 :psi.agent-session/startup-bootstrap-completed-at
                 :psi.agent-session/startup-message-ids]}
  (let [sd (session/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/startup-prompts               (:startup-prompts sd [])
     :psi.agent-session/startup-bootstrap-completed?  (boolean (:startup-bootstrap-completed? sd))
     :psi.agent-session/startup-bootstrap-started-at  (:startup-bootstrap-started-at sd)
     :psi.agent-session/startup-bootstrap-completed-at (:startup-bootstrap-completed-at sd)
     :psi.agent-session/startup-message-ids           (:startup-message-ids sd [])}))

(pco/defresolver startup-bootstrap-resolver
  "Resolve startup bootstrap summary and derived fields."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.startup/bootstrap-summary
                 :psi.startup/bootstrap-timestamp
                 :psi.startup/prompt-count
                 :psi.startup/skill-count
                 :psi.startup/tool-count
                 :psi.startup/extension-loaded-count
                 :psi.startup/extension-error-count
                 :psi.startup/extension-errors
                 :psi.startup/mutations]}
  (let [summary (:startup-bootstrap (session/get-session-data-in agent-session-ctx))]
    {:psi.startup/bootstrap-summary      summary
     :psi.startup/bootstrap-timestamp    (:timestamp summary)
     :psi.startup/prompt-count           (:prompt-count summary 0)
     :psi.startup/skill-count            (:skill-count summary 0)
     :psi.startup/tool-count             (:tool-count summary 0)
     :psi.startup/extension-loaded-count (:extension-loaded-count summary 0)
     :psi.startup/extension-error-count  (:extension-error-count summary 0)
     :psi.startup/extension-errors       (:extension-errors summary [])
     :psi.startup/mutations              (:mutations summary [])}))

;; ── Query graph bridge (seeded by :psi/agent-session-ctx) ────────────────

(declare all-resolvers)

(defn session-resolver-surface
  "Canonical resolver set used by agent-session/query-in.

   Kept in sync with `build-env` so graph introspection reflects what is
   actually queryable from session root, independent of global registry state."
  []
  (->> (concat all-resolvers
               history-resolvers/all-resolvers
               memory-resolvers/all-resolvers
               recursion-resolvers/all-resolvers)
       vec))

(defn- operation-metadata
  []
  {:resolver-ops (mapv #(graph/operation->metadata :resolver %)
                       (session-resolver-surface))
   :mutation-ops (mapv #(graph/operation->metadata :mutation %)
                       (registry/all-mutations))})

(pco/defresolver query-graph-bridge
  "Resolve all :psi.graph/* attrs from :psi/agent-session-ctx so app-query-tool can access
   the Step 7 graph discovery surface without requiring a :psi/query-ctx seed.

   Exposes the canonical graph attrs:
     :psi.graph/resolver-count   — count of registered resolver operations
     :psi.graph/mutation-count   — count of registered mutation operations
     :psi.graph/resolver-syms    — set of registered resolver symbols
     :psi.graph/mutation-syms    — set of registered mutation symbols
     :psi.graph/env-built        — true when a non-empty introspection graph/env is available
     :psi.graph/nodes            — capability graph nodes (:resolver | :mutation | :capability)
     :psi.graph/edges            — operation->capability edges annotated by :attribute
     :psi.graph/capabilities     — rich per-domain summaries for domains present in the graph
     :psi.graph/domain-coverage  — normalized per-domain counts, including required zero-count domains

   Additional discovery diagnostics:
     :psi.graph/root-seeds           — root context attrs injected for session-root querying
     :psi.graph/root-queryable-attrs — keyword attrs reachable from root seeds via resolvers only

   Notes:
   - :psi.graph/edges are capability-membership edges, not dependency-flow edges
   - edge :attribute values may be keyword attrs, join-map attrs, or nil
   - root-queryable attrs exclude the seed attrs themselves"
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.graph/resolver-count
                 :psi.graph/mutation-count
                 :psi.graph/resolver-syms
                 :psi.graph/mutation-syms
                 :psi.graph/env-built
                 :psi.graph/nodes
                 :psi.graph/edges
                 :psi.graph/capabilities
                 :psi.graph/domain-coverage
                 :psi.graph/root-seeds
                 :psi.graph/root-queryable-attrs]}
  (let [_                    agent-session-ctx
        op-meta              (operation-metadata)
        cgraph               (graph/derive-capability-graph op-meta)
        resolver-syms        (->> (:resolver-ops op-meta)
                                  (map :symbol)
                                  set)
        mutation-syms        (->> (:mutation-ops op-meta)
                                  (map :symbol)
                                  set)
        root-queryable-attrs (graph/derive-root-queryable-attrs
                              (:resolver-ops op-meta)
                              #{:psi/agent-session-ctx :psi/memory-ctx :psi/recursion-ctx :psi/engine-ctx})]
    {:psi.graph/resolver-count       (count resolver-syms)
     :psi.graph/mutation-count       (count mutation-syms)
     :psi.graph/resolver-syms        resolver-syms
     :psi.graph/mutation-syms        mutation-syms
     :psi.graph/env-built            (boolean (seq resolver-syms))
     :psi.graph/nodes                (:nodes cgraph)
     :psi.graph/edges                (:edges cgraph)
     :psi.graph/capabilities         (:capabilities cgraph)
     :psi.graph/domain-coverage      (:domain-coverage cgraph)
     :psi.graph/root-seeds           [:psi/agent-session-ctx
                                      :psi/memory-ctx
                                      :psi/recursion-ctx
                                      :psi/engine-ctx]
     :psi.graph/root-queryable-attrs root-queryable-attrs}))

;; ── All resolvers ───────────────────────────────────────

(def all-resolvers
  [agent-session-identity
   agent-session-phase
   agent-session-model
   agent-session-queues
   agent-session-retry-compact
   agent-session-resources
   agent-session-extensions
   agent-session-context-usage
   tool-output-policy
   tool-output-calls
   tool-output-stats
   agent-session-journal
   agent-session-canonical-telemetry
   agent-session-stats
   ;; Session-derived usage/git attrs
   agent-session-cwd
   agent-session-git-branch
   runtime-nrepl-info
   agent-session-git-context
   agent-session-usage-input
   agent-session-usage-output
   agent-session-usage-cache-read
   agent-session-usage-cache-write
   agent-session-usage-cost-total
   agent-session-model-provider
   agent-session-model-id
   agent-session-model-reasoning
   agent-session-model-catalog
   agent-session-authenticated-providers
   agent-session-rpc-trace
   startup-prompts-resolver
   startup-bootstrap-resolver
   query-graph-bridge
   agent-session-tool-call-attempts
   agent-session-tool-lifecycle-events
   tool-lifecycle-summary-by-tool-id
   agent-session-provider-captures
   provider-request-by-turn-id
   provider-reply-by-turn-id
   ;; API error diagnostics (hierarchical)
   api-error-list
   api-error-detail
   api-error-request-shape
   current-request-shape
   agent-session-turn
   prompt-template-summary-resolver
   prompt-template-detail-resolver
   skill-summary-resolver
   skill-detail-resolver
   tool-summary-resolver
   tool-detail-resolver
   ;; Extension introspection
   extension-paths-resolver
   extension-handlers-resolver
   extension-tools-resolver
   extension-commands-resolver
   extension-flags-resolver
   extension-details-resolver
   extension-prompt-contributions-resolver
   extension-detail-by-path-resolver
   extension-workflow-summary-resolver
   agent-chain-discovery-resolver
   extension-workflows-resolver
   extension-workflow-detail-resolver
   ;; Background jobs
   agent-session-background-jobs
   agent-session-dispatch-registry
   agent-session-dispatch-event-log
   ;; Extension UI
   extension-ui-resolver
   ;; Session listing
   session-list-resolver
   session-list-all-resolver])

;; ── Local Pathom env (for component-local queries) ──────

(defn build-env
  "Build a Pathom3 environment for querying an agent-session context.
   Includes memory + recursion resolvers locally so :psi.memory/* and
   :psi.recursion/* attrs are queryable via agent-session/query-in."
  []
  (-> (session-resolver-surface)
      pci/register))

(def ^:private query-env (atom nil))

(defn- ensure-query-env! []
  (or @query-env (reset! query-env (build-env))))

(defn- snapshot-engine-context
  "Build a read-only engine context snapshot from global engine wrappers.

   Avoids direct dependency on engine private/global internals while still
   seeding :psi/engine-ctx for introspection resolvers that expect an
   EngineContext-shaped map."
  []
  {:engines           (atom (or (engine/get-all-engines) {}))
   :system-state      (atom (engine/get-system-state))
   :state-transitions (atom (vec (or (engine/get-state-transitions) [])))
   :sc-env            (atom nil)})

(defn query-in
  "Run EQL `q` against `ctx` using this component's Pathom graph.
   Transparently seeds root contexts so callers don't need to pass them:
   - :psi/agent-session-ctx (always)
   - :psi/memory-ctx (session or global)
   - :psi/recursion-ctx (session or global)
   - :psi/engine-ctx (session override or read-only global snapshot)

   This keeps session-root app-query-tool ergonomic while still allowing advanced
   introspection attrs that depend on non-session roots."
  [ctx q]
  (let [memory-ctx    (or (:memory-ctx ctx)
                          (memory/global-context))
        recursion-ctx (or (:recursion-ctx ctx)
                          (recursion/global-context))
        engine-ctx    (or (:engine-ctx ctx)
                          (snapshot-engine-context))]
    (p.eql/process (ensure-query-env!)
                   {:psi/agent-session-ctx ctx
                    :psi/memory-ctx        memory-ctx
                    :psi/recursion-ctx     recursion-ctx
                    :psi/engine-ctx        engine-ctx}
                   q)))
