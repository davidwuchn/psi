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
   :psi.agent-session/model
   :psi.agent-session/thinking-level
   :psi.agent-session/is-streaming
   :psi.agent-session/is-compacting
   :psi.agent-session/is-idle
   :psi.agent-session/phase               — statechart phase keyword
   :psi.agent-session/system-prompt
   :psi.agent-session/pending-message-count
   :psi.agent-session/has-pending-messages
   :psi.agent-session/retry-attempt
   :psi.agent-session/auto-retry-enabled
   :psi.agent-session/auto-compaction-enabled
   :psi.agent-session/scoped-models
   :psi.agent-session/skills
   :psi.agent-session/prompt-templates
   :psi.agent-session/extension-summary   — map describing registered extensions
   :psi.agent-session/session-entry-count
   :psi.agent-session/session-entries     — [{:psi.session-entry/*}] full journal contents
   :psi.agent-session/journal-flushed?    — true once initial bulk write has happened
   :psi.agent-session/context-tokens
   :psi.agent-session/context-window
   :psi.agent-session/context-fraction    — nil or 0.0–1.0
   :psi.agent-session/stats               — SessionStats snapshot
   :psi.agent-session/tool-call-history   — [{:psi.tool-call/*}], nested tool call entities
   :psi.agent-session/tool-call-history-count — number of tool calls

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

   Extension UI state (read-only)
   ──────────────────────────────
   :psi.ui/dialog-queue-empty?            — true when no dialogs active or pending
   :psi.ui/active-dialog                  — current dialog map (sans promise), or nil
   :psi.ui/pending-dialog-count           — number of queued dialogs
   :psi.ui/widgets                        — vector of widget maps
   :psi.ui/statuses                       — vector of status entry maps
   :psi.ui/visible-notifications          — vector of non-dismissed notification maps
   :psi.ui/tool-renderers                 — vector of tool renderer metadata maps
   :psi.ui/message-renderers              — vector of message renderer metadata maps"
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session :as session]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.extensions :as ext]
   [psi.tui.extension-ui :as ext-ui]
   [psi.agent-session.statechart :as sc]
   [psi.agent-session.turn-statechart :as turn-sc]
   [psi.agent-core.core :as agent]))

;; ── Core session fields ─────────────────────────────────

(pco/defresolver agent-session-identity
  "Resolve stable identity and naming fields."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/session-id
                 :psi.agent-session/session-file
                 :psi.agent-session/session-name]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/session-id   (:session-id sd)
     :psi.agent-session/session-file (:session-file sd)
     :psi.agent-session/session-name (:session-name sd)}))

;; ── Phase and streaming state ───────────────────────────

(pco/defresolver agent-session-phase
  "Resolve statechart phase and derived boolean flags."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/phase
                 :psi.agent-session/is-streaming
                 :psi.agent-session/is-compacting
                 :psi.agent-session/is-idle]}
  (let [{:keys [sc-env sc-session-id]} agent-session-ctx
        phase (sc/sc-phase sc-env sc-session-id)]
    {:psi.agent-session/phase        phase
     :psi.agent-session/is-streaming (= phase :streaming)
     :psi.agent-session/is-compacting (= phase :compacting)
     :psi.agent-session/is-idle      (= phase :idle)}))

;; ── Model and thinking level ────────────────────────────

(pco/defresolver agent-session-model
  "Resolve model and thinking level."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/model
                 :psi.agent-session/thinking-level]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/model          (:model sd)
     :psi.agent-session/thinking-level (:thinking-level sd)}))

;; ── Queues and message counts ───────────────────────────

(pco/defresolver agent-session-queues
  "Resolve queue depths and system prompt."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/system-prompt
                 :psi.agent-session/pending-message-count
                 :psi.agent-session/has-pending-messages
                 :psi.agent-session/steering-messages
                 :psi.agent-session/follow-up-messages]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/system-prompt          (:system-prompt sd)
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
  (let [sd @(:session-data-atom agent-session-ctx)]
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
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/skills           (:skills sd)
     :psi.agent-session/prompt-templates (:prompt-templates sd)}))

;; ── Extension summary ───────────────────────────────────

(pco/defresolver agent-session-extensions
  "Resolve extension registry summary."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/extension-summary]}
  {:psi.agent-session/extension-summary
   (ext/summary-in (:extension-registry agent-session-ctx))})

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

(pco/defresolver extension-detail-by-path-resolver
  "Resolve detail for a single extension by path.
   Seed input: {:psi.extension/path \"path\"}"
  [{:keys [psi/agent-session-ctx psi.extension/path]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path]
   ::pco/output [:psi.extension/detail]}
  {:psi.extension/detail
   (ext/extension-detail-in (:extension-registry agent-session-ctx) path)})

;; ── Context usage ───────────────────────────────────────

(pco/defresolver agent-session-context-usage
  "Resolve context token usage and fraction."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/context-tokens
                 :psi.agent-session/context-window
                 :psi.agent-session/context-fraction]}
  (let [sd @(:session-data-atom agent-session-ctx)]
    {:psi.agent-session/context-tokens   (:context-tokens sd)
     :psi.agent-session/context-window   (:context-window sd)
     :psi.agent-session/context-fraction (session/context-fraction-used sd)}))

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
  (let [entries  @(:journal-atom agent-session-ctx)
        flushed? (:flushed? @(:flush-state-atom agent-session-ctx))]
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

(pco/defresolver agent-session-stats
  "Resolve a SessionStats snapshot."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/stats]}
  (let [sd      @(:session-data-atom agent-session-ctx)
        journal @(:journal-atom agent-session-ctx)
        msgs    (keep #(when (= :message (:kind %)) (get-in % [:data :message])) journal)]
    {:psi.agent-session/stats
     {:session-id        (:session-id sd)
      :session-file      (:session-file sd)
      :user-messages     (count (filter #(= "user" (:role %)) msgs))
      :assistant-messages (count (filter #(= "assistant" (:role %)) msgs))
      :tool-calls        (count (filter #(= "toolResult" (:role %)) msgs))
      :total-messages    (count msgs)
      :entry-count       (count journal)
      :context-tokens    (:context-tokens sd)
      :context-window    (:context-window sd)}}))

;; ── Tool call history ───────────────────────────────────
;;
;; Two-level hierarchy:
;;   1. List resolver — extracts lightweight tool call identity from
;;      assistant messages (id, name, arguments). Cheap: no result loading.
;;   2. Detail resolver — given a :psi.tool-call/id, scans journal for the
;;      matching toolResult message. Only runs when result/error is queried.
;;
;; Usage:
;;   [:psi.agent-session/tool-call-history-count]
;;   [{:psi.agent-session/tool-call-history [:psi.tool-call/id :psi.tool-call/name]}]
;;   [{:psi.agent-session/tool-call-history [:psi.tool-call/name :psi.tool-call/result :psi.tool-call/is-error]}]

(defn- agent-core-messages
  "Extract the message vec from agent-core inside a session context."
  [agent-session-ctx]
  (:messages (psi.agent-core.core/get-data-in (:agent-ctx agent-session-ctx))))

(pco/defresolver agent-session-tool-calls
  "Resolve tool call list from assistant messages in agent-core.
   Each entry carries identity + the session context for downstream resolvers."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/tool-call-history-count
                 {:psi.agent-session/tool-call-history
                  [:psi.tool-call/id
                   :psi.tool-call/name
                   :psi.tool-call/arguments
                   :psi/agent-session-ctx]}]}
  (let [msgs  (agent-core-messages agent-session-ctx)
        calls (->> msgs
                   (filter #(= "assistant" (:role %)))
                   (mapcat (fn [msg]
                             (->> (:content msg)
                                  (filter #(= :tool-call (:type %)))
                                  (map (fn [tc]
                                         {:psi.tool-call/id         (:id tc)
                                          :psi.tool-call/name       (:name tc)
                                          :psi.tool-call/arguments  (:arguments tc)
                                          :psi/agent-session-ctx    agent-session-ctx})))))
                   vec)]
    {:psi.agent-session/tool-call-history       calls
     :psi.agent-session/tool-call-history-count (count calls)}))

(pco/defresolver tool-call-result
  "Resolve the result and error status for a single tool call.
   Scans agent-core messages for the matching toolResult."
  [{:keys [psi.tool-call/id psi/agent-session-ctx]}]
  {::pco/input  [:psi.tool-call/id :psi/agent-session-ctx]
   ::pco/output [:psi.tool-call/result
                 :psi.tool-call/is-error]}
  (let [result-msg (->> (agent-core-messages agent-session-ctx)
                        (filter #(= "toolResult" (:role %)))
                        (filter #(= id (:tool-call-id %)))
                        first)]
    {:psi.tool-call/result   (some #(when (= :text (:type %)) (:text %))
                                   (:content result-msg))
     :psi.tool-call/is-error (:is-error result-msg)}))

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
                 :psi.turn/is-text-accumulating
                 :psi.turn/is-tool-accumulating
                 :psi.turn/is-done
                 :psi.turn/is-error]}
  (if-let [turn-ctx (some-> (:turn-ctx-atom agent-session-ctx) deref)]
    (let [phase (turn-sc/turn-phase turn-ctx)
          td    (turn-sc/get-turn-data turn-ctx)]
      {:psi.turn/phase                phase
       :psi.turn/is-streaming         (boolean (#{:text-accumulating :tool-accumulating} phase))
       :psi.turn/text                 (:text-buffer td)
       :psi.turn/tool-calls           (vec (vals (:tool-calls td)))
       :psi.turn/tool-call-count      (count (:tool-calls td))
       :psi.turn/final-message        (:final-message td)
       :psi.turn/error-message        (:error-message td)
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
  (let [templates (:prompt-templates @(:session-data-atom agent-session-ctx))]
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
  (let [templates (:prompt-templates @(:session-data-atom agent-session-ctx))
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
  (let [all-skills (:skills @(:session-data-atom agent-session-ctx))
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
  (let [all-skills (:skills @(:session-data-atom agent-session-ctx))
        skill      (skills/find-skill all-skills name)]
    {:psi.skill/detail
     (when skill (skills/enrich-skill skill))}))

;; ── Session listing ─────────────────────────────────────

(defn- session-info->eql
  "Convert a SessionInfo map to :psi.session-info/* attributes."
  [info]
  {:psi.session-info/path                (:path info)
   :psi.session-info/id                  (:id info)
   :psi.session-info/cwd                 (:cwd info)
   :psi.session-info/name                (:name info)
   :psi.session-info/parent-session-path (:parent-session-path info)
   :psi.session-info/created             (:created info)
   :psi.session-info/modified            (:modified info)
   :psi.session-info/message-count       (:message-count info)
   :psi.session-info/first-message       (:first-message info)
   :psi.session-info/all-messages-text   (:all-messages-text info)})

(pco/defresolver session-list-resolver
  "Resolve all sessions for the current session's cwd, sorted by modified desc."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.session/list
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/cwd
                   :psi.session-info/name
                   :psi.session-info/parent-session-path
                   :psi.session-info/created
                   :psi.session-info/modified
                   :psi.session-info/message-count
                   :psi.session-info/first-message
                   :psi.session-info/all-messages-text]}]}
  {:psi.session/list
   (mapv session-info->eql
         (persist/list-sessions
          (persist/session-dir-for (:cwd agent-session-ctx))))})

(pco/defresolver session-list-all-resolver
  "Resolve all sessions across all project directories, sorted by modified desc."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.session/list-all
                  [:psi.session-info/path
                   :psi.session-info/id
                   :psi.session-info/cwd
                   :psi.session-info/name
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
                 :psi.ui/statuses
                 :psi.ui/visible-notifications
                 :psi.ui/tool-renderers
                 :psi.ui/message-renderers]}
  (let [snap (ext-ui/snapshot (:ui-state-atom agent-session-ctx))]
    (if snap
      {:psi.ui/dialog-queue-empty?   (:dialog-queue-empty? snap)
       :psi.ui/active-dialog         (:active-dialog snap)
       :psi.ui/pending-dialog-count  (:pending-dialog-count snap)
       :psi.ui/widgets               (:widgets snap)
       :psi.ui/statuses              (:statuses snap)
       :psi.ui/visible-notifications (:visible-notifications snap)
       :psi.ui/tool-renderers        (:tool-renderers snap)
       :psi.ui/message-renderers     (:message-renderers snap)}
      {:psi.ui/dialog-queue-empty?   true
       :psi.ui/active-dialog         nil
       :psi.ui/pending-dialog-count  0
       :psi.ui/widgets               []
       :psi.ui/statuses              []
       :psi.ui/visible-notifications []
       :psi.ui/tool-renderers        []
       :psi.ui/message-renderers     []})))

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
   agent-session-journal
   agent-session-stats
   agent-session-tool-calls
   tool-call-result
   agent-session-turn
   prompt-template-summary-resolver
   prompt-template-detail-resolver
   skill-summary-resolver
   skill-detail-resolver
   ;; Extension introspection
   extension-paths-resolver
   extension-handlers-resolver
   extension-tools-resolver
   extension-commands-resolver
   extension-flags-resolver
   extension-details-resolver
   extension-detail-by-path-resolver
   ;; Extension UI
   extension-ui-resolver
   ;; Session listing
   session-list-resolver
   session-list-all-resolver])

;; ── Local Pathom env (for component-local queries) ──────

(defn build-env
  "Build a Pathom3 environment for querying an agent-session context."
  []
  (pci/register all-resolvers))

(defonce ^:private query-env (atom nil))

(defn- ensure-query-env! []
  (or @query-env (reset! query-env (build-env))))

(defn query-in
  "Run EQL `q` against `ctx` using this component's Pathom graph."
  [ctx q]
  (p.eql/process (ensure-query-env!)
                 {:psi/agent-session-ctx ctx}
                 q))
