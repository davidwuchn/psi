(ns psi.agent-session.resolvers.extensions
  "Pathom3 resolvers for extension introspection, workflows, agent chains, and UI state."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.resolvers.support :as support]
   [psi.agent-session.workflows :as wf]
   [psi.ui.state :as ui-state]))

;; ── Projection helpers ──────────────────────────────────



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
  (let [created-at  (:created-at workflow)
        finished-at (:finished-at workflow)
        elapsed-ms  (when created-at
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

;; ── Extension registry resolvers ────────────────────────

(pco/defresolver extension-paths-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/paths
                 :psi.extension/count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/paths (vec (ext/extensions-in reg))
     :psi.extension/count (ext/extension-count-in reg)}))

(pco/defresolver extension-handlers-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/handler-events
                 :psi.extension/handler-count]}
  (let [reg (:extension-registry agent-session-ctx)]
    {:psi.extension/handler-events (vec (ext/handler-event-names-in reg))
     :psi.extension/handler-count  (ext/handler-count-in reg)}))

(pco/defresolver extension-tools-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/tools
                 :psi.extension/tool-names]}
  (let [reg   (:extension-registry agent-session-ctx)
        tools (ext/all-tools-in reg)]
    {:psi.extension/tools      (mapv #(dissoc % :execute) tools)
     :psi.extension/tool-names (vec (ext/tool-names-in reg))}))

(pco/defresolver extension-commands-resolver
  [{:keys [psi/agent-session-ctx]}]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [:psi.extension/commands
                 :psi.extension/command-names]}
  (let [reg  (:extension-registry agent-session-ctx)
        cmds (ext/all-commands-in reg)]
    {:psi.extension/commands      (mapv #(dissoc % :handler) cmds)
     :psi.extension/command-names (vec (ext/command-names-in reg))}))

(pco/defresolver extension-flags-resolver
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
  (let [contribs (->> (or (:prompt-contributions (support/session-data agent-session-ctx)) [])
                      (filter map?)
                      (sort-by (fn [{:keys [priority ext-path id]}]
                                 [(or priority 1000)
                                  (or ext-path "")
                                  (or id "")]))
                      (mapv support/contribution->attrs))]
    {:psi.extension/prompt-contributions contribs
     :psi.extension/prompt-contribution-count (count contribs)}))

(pco/defresolver extension-detail-by-path-resolver
  "Resolve detail for a single extension by path."
  [{:keys [psi/agent-session-ctx psi.extension/path]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path]
   ::pco/output [:psi.extension/detail]}
  {:psi.extension/detail
   (ext/extension-detail-in (:extension-registry agent-session-ctx) path)})

;; ── Workflow resolvers ──────────────────────────────────

(pco/defresolver extension-workflow-summary-resolver
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
  [entity]
  {::pco/input  [:psi/agent-session-ctx]
   ::pco/output [{:psi.extension/workflows workflow-output}]}
  (let [agent-session-ctx (:psi/agent-session-ctx entity)
        path              (:psi.extension/path entity)
        reg               (:workflow-registry agent-session-ctx)]
    {:psi.extension/workflows
     (mapv workflow->eql (wf/workflows-in reg path))}))

(pco/defresolver extension-workflow-detail-resolver
  [{:keys [psi/agent-session-ctx psi.extension/path psi.extension.workflow/id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.extension/path :psi.extension.workflow/id]
   ::pco/output [:psi.extension.workflow/detail]}
  (let [reg (:workflow-registry agent-session-ctx)]
    {:psi.extension.workflow/detail
     (some-> (wf/workflow-in reg path id) workflow->eql)}))

;; ── Extension UI state ──────────────────────────────────

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

;; ── Resolver collection ─────────────────────────────────

(def resolvers
  [extension-paths-resolver
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
   extension-ui-resolver])
