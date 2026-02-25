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
   :psi.agent-session/context-tokens
   :psi.agent-session/context-window
   :psi.agent-session/context-fraction    — nil or 0.0–1.0
   :psi.agent-session/stats               — SessionStats snapshot"
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [com.wsscode.pathom3.connect.indexes :as pci]
   [com.wsscode.pathom3.interface.eql :as p.eql]
   [psi.agent-session.session :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.statechart :as sc]))

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
  "Resolve session entry count."
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.agent-session/session-entry-count]}
  {:psi.agent-session/session-entry-count
   (count @(:journal-atom agent-session-ctx))})

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
   agent-session-stats])

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
