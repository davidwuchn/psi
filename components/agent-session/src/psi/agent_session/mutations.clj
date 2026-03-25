(ns psi.agent-session.mutations
  "EQL mutation surface for the agent-session extension API.
   Contains mutations that call dispatch or ext/ directly without
   routing through core.clj *-in! wrappers."
  (:require
   [clojure.string :as str]
   [clojure.walk :as walk]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-core.core :as agent]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.core :as core]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.executor :as executor]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.ai.models :as models]
   [psi.agent-session.workflows :as wf]
   [psi.ui.state :as ui-state]))

;;; Prompt-contribution helper

(defn- prompt-contribution-mutation-view
  [c]
  {:psi.extension.prompt-contribution/id         (:id c)
   :psi.extension.prompt-contribution/ext-path   (:ext-path c)
   :psi.extension.prompt-contribution/section    (:section c)
   :psi.extension.prompt-contribution/content    (:content c)
   :psi.extension.prompt-contribution/priority   (:priority c)
   :psi.extension.prompt-contribution/enabled    (:enabled c)
   :psi.extension.prompt-contribution/created-at (:created-at c)
   :psi.extension.prompt-contribution/updated-at (:updated-at c)})

;;; Session metadata mutations

(pco/defmutation set-session-name
  "Set the human-readable name of the current session."
  [_ {:keys [psi/agent-session-ctx name]}]
  {::pco/op-name 'psi.extension/set-session-name
   ::pco/params  [:psi/agent-session-ctx :name]
   ::pco/output  [:psi.agent-session/session-name]}
  (dispatch/dispatch! agent-session-ctx :session/set-session-name {:name name} {:origin :mutations})
  {:psi.agent-session/session-name name})

;;; Prompt-template and skill registration mutations

(pco/defmutation add-prompt-template
  "Add a prompt template to the session if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx template]}]
  {::pco/op-name 'psi.extension/add-prompt-template
   ::pco/params  [:psi/agent-session-ctx :template]
   ::pco/output  [:psi.prompt-template/added?
                  :psi.prompt-template/count]}
  (let [{:keys [added? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-prompt-template
                            {:template template}
                            {:origin :mutations})]
    {:psi.prompt-template/added? (boolean added?)
     :psi.prompt-template/count  (or count 0)}))

(pco/defmutation add-skill
  "Add a skill to the session if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx skill]}]
  {::pco/op-name 'psi.extension/add-skill
   ::pco/params  [:psi/agent-session-ctx :skill]
   ::pco/output  [:psi.skill/added?
                  :psi.skill/count]}
  (let [{:keys [added? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-skill
                            {:skill skill}
                            {:origin :mutations})]
    {:psi.skill/added? (boolean added?)
     :psi.skill/count  (or count 0)}))

;;; Tool mutations

(pco/defmutation add-tool
  "Add a tool to the active agent tool set if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx tool]}]
  {::pco/op-name 'psi.extension/add-tool
   ::pco/params  [:psi/agent-session-ctx :tool]
   ::pco/output  [:psi.tool/added?
                  :psi.tool/count]}
  (let [{:keys [added? count]}
        (or (dispatch/dispatch! agent-session-ctx
                                :session/add-tool
                                {:tool tool}
                                {:origin :mutations})
            {:added? false :count 0})]
    {:psi.tool/added? (boolean added?)
     :psi.tool/count  (or count 0)}))

(pco/defmutation set-active-tools
  "Replace the agent's active tool set with the named subset."
  [_ {:keys [psi/agent-session-ctx tool-names]}]
  {::pco/op-name 'psi.extension/set-active-tools
   ::pco/params  [:psi/agent-session-ctx :tool-names]
   ::pco/output  [:psi.tool/count
                  :psi.tool/names]}
  (let [agent-ctx      (ss/agent-ctx-in agent-session-ctx)
        current-tools  (:tools (agent/get-data-in agent-ctx))
        by-name        (into {} (map (juxt :name identity)) current-tools)
        selected-tools (vec (keep by-name tool-names))]
    (dispatch/dispatch! agent-session-ctx
                        :session/set-active-tools
                        {:tool-maps selected-tools}
                        {:origin :mutations})
    {:psi.tool/count (count selected-tools)
     :psi.tool/names (mapv :name selected-tools)}))

;;; Extension registration mutations

(pco/defmutation register-tool
  "Register an extension-owned tool into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path tool]}]
  {::pco/op-name 'psi.extension/register-tool
   ::pco/params  [:psi/agent-session-ctx :ext-path :tool]
   ::pco/output  [:psi.extension/path
                  :psi.extension/tool-names]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-tool-in! reg ext-path tool)
    {:psi.extension/path       ext-path
     :psi.extension/tool-names (vec (ext/tool-names-in reg))}))

(pco/defmutation register-command
  "Register an extension-owned command into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path name opts]}]
  {::pco/op-name 'psi.extension/register-command
   ::pco/params  [:psi/agent-session-ctx :ext-path :name :opts]
   ::pco/output  [:psi.extension/path
                  :psi.extension/command-names]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-command-in! reg ext-path (assoc opts :name name))
    {:psi.extension/path          ext-path
     :psi.extension/command-names (vec (ext/command-names-in reg))}))

(pco/defmutation register-handler
  "Register an extension-owned event handler into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path event-name handler-fn]}]
  {::pco/op-name 'psi.extension/register-handler
   ::pco/params  [:psi/agent-session-ctx :ext-path :event-name :handler-fn]
   ::pco/output  [:psi.extension/path
                  :psi.extension/handler-count]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-handler-in! reg ext-path event-name handler-fn)
    {:psi.extension/path          ext-path
     :psi.extension/handler-count (ext/handler-count-in reg)}))

(pco/defmutation register-flag
  "Register an extension-owned flag into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path name opts]}]
  {::pco/op-name 'psi.extension/register-flag
   ::pco/params  [:psi/agent-session-ctx :ext-path :name :opts]
   ::pco/output  [:psi.extension/path
                  :psi.extension/flag-names]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-flag-in! reg ext-path (assoc opts :name name))
    {:psi.extension/path       ext-path
     :psi.extension/flag-names (vec (ext/flag-names-in reg))}))

(pco/defmutation register-shortcut
  "Register an extension-owned keyboard shortcut into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path key opts]}]
  {::pco/op-name 'psi.extension/register-shortcut
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :opts]
   ::pco/output  [:psi.extension/path]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-shortcut-in! reg ext-path (assoc opts :key key))
    {:psi.extension/path ext-path}))

;;; Prompt-contribution mutations

(pco/defmutation register-prompt-contribution
  "Register or replace an extension-owned prompt contribution by id."
  [_ {:keys [psi/agent-session-ctx ext-path id contribution]}]
  {::pco/op-name 'psi.extension/register-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :ext-path :id :contribution]
   ::pco/output  [:psi.extension/path
                  :psi.extension.prompt-contribution/id
                  :psi.extension.prompt-contribution/registered?
                  :psi.extension.prompt-contribution/count
                  :psi.extension.prompt-contribution/ext-path
                  :psi.extension.prompt-contribution/section
                  :psi.extension.prompt-contribution/content
                  :psi.extension.prompt-contribution/priority
                  :psi.extension.prompt-contribution/enabled
                  :psi.extension.prompt-contribution/created-at
                  :psi.extension.prompt-contribution/updated-at]}
  (let [{:keys [registered? contribution count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-prompt-contribution
                            {:ext-path ext-path :id id :contribution contribution}
                            {:origin :mutations})]
    (merge {:psi.extension/path                            (str ext-path)
            :psi.extension.prompt-contribution/id          (str id)
            :psi.extension.prompt-contribution/registered? registered?
            :psi.extension.prompt-contribution/count       count}
           (prompt-contribution-mutation-view contribution))))

(pco/defmutation update-prompt-contribution
  "Patch an existing extension-owned prompt contribution."
  [_ {:keys [psi/agent-session-ctx ext-path id patch]}]
  {::pco/op-name 'psi.extension/update-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :ext-path :id :patch]
   ::pco/output  [:psi.extension/path
                  :psi.extension.prompt-contribution/id
                  :psi.extension.prompt-contribution/updated?
                  :psi.extension.prompt-contribution/count
                  :psi.extension.prompt-contribution/ext-path
                  :psi.extension.prompt-contribution/section
                  :psi.extension.prompt-contribution/content
                  :psi.extension.prompt-contribution/priority
                  :psi.extension.prompt-contribution/enabled
                  :psi.extension.prompt-contribution/created-at
                  :psi.extension.prompt-contribution/updated-at]}
  (let [{:keys [updated? contribution count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/update-prompt-contribution
                            {:ext-path ext-path :id id :patch patch}
                            {:origin :mutations})]
    (merge {:psi.extension/path                         (str ext-path)
            :psi.extension.prompt-contribution/id       (str id)
            :psi.extension.prompt-contribution/updated? updated?
            :psi.extension.prompt-contribution/count    count}
           (when contribution
             (prompt-contribution-mutation-view contribution)))))

(pco/defmutation unregister-prompt-contribution
  "Remove an extension-owned prompt contribution by id."
  [_ {:keys [psi/agent-session-ctx ext-path id]}]
  {::pco/op-name 'psi.extension/unregister-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :ext-path :id]
   ::pco/output  [:psi.extension/path
                  :psi.extension.prompt-contribution/id
                  :psi.extension.prompt-contribution/removed?
                  :psi.extension.prompt-contribution/count]}
  (let [{:keys [removed? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/unregister-prompt-contribution
                            {:ext-path ext-path :id id}
                            {:origin :mutations})]
    {:psi.extension/path                          (str ext-path)
     :psi.extension.prompt-contribution/id        (str id)
     :psi.extension.prompt-contribution/removed?  removed?
     :psi.extension.prompt-contribution/count     count}))

;;; Model mutation

(pco/defmutation set-model
  "Set the active model and clamp thinking-level for the current session.
   Optional :scope — :session (runtime only), :project (default), :user (user-global)."
  [_ {:keys [psi/agent-session-ctx model scope]}]
  {::pco/op-name 'psi.extension/set-model
   ::pco/params  [:psi/agent-session-ctx :model]
   ::pco/output  [:psi.agent-session/model
                  :psi.agent-session/thinking-level]}
  (let [result (dispatch/dispatch! agent-session-ctx :session/set-model
                                   (cond-> {:model model} scope (assoc :scope scope))
                                   {:origin :mutations})]
    {:psi.agent-session/model          (:model result)
     :psi.agent-session/thinking-level (:thinking-level result)}))

;;; Tool mutations

(defn- run-tool-mutation-in!
  "Execute a single tool call and normalize result attrs for mutation payloads."
  [ctx tool-name args]
  (when-not (map? args)
    (throw (ex-info "Tool args must be a map"
                    {:tool tool-name
                     :args args})))
  (let [step-id         (keyword (str "tool-" tool-name "-" (java.util.UUID/randomUUID)))
        normalized-args (walk/stringify-keys args)
        result          (tool-plan/run-tool-plan-step-in! ctx step-id tool-name normalized-args)]
    {:psi.extension.tool/name     tool-name
     :psi.extension.tool/content  (:content result)
     :psi.extension.tool/is-error (boolean (:is-error result))
     :psi.extension.tool/details  (:details result)
     :psi.extension.tool/result   result}))

(defn- run-tool-plan-mutation-payload
  [ctx steps stop-on-error?]
  (let [plan-opts   (cond-> {:steps steps}
                      (some? stop-on-error?) (assoc :stop-on-error? stop-on-error?))
        {:keys [succeeded? step-count completed-count failed-step-id results result-by-id error]}
        (tool-plan/run-tool-plan-in! ctx plan-opts)]
    {:psi.extension.tool-plan/succeeded?      succeeded?
     :psi.extension.tool-plan/step-count      step-count
     :psi.extension.tool-plan/completed-count completed-count
     :psi.extension.tool-plan/failed-step-id  failed-step-id
     :psi.extension.tool-plan/results         results
     :psi.extension.tool-plan/result-by-id    result-by-id
     :psi.extension.tool-plan/error           error}))

(pco/defmutation run-read-tool
  "Read a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx path offset limit]}]
  {::pco/op-name 'psi.extension.tool/read
   ::pco/params  [:psi/agent-session-ctx :path]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   "read"
   (cond-> {:path path}
     (some? offset) (assoc :offset offset)
     (some? limit)  (assoc :limit limit))))

(pco/defmutation run-bash-tool
  "Run a bash command via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx command timeout]}]
  {::pco/op-name 'psi.extension.tool/bash
   ::pco/params  [:psi/agent-session-ctx :command]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   "bash"
   (cond-> {:command command}
     (some? timeout) (assoc :timeout timeout))))

(pco/defmutation run-write-tool
  "Write a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx path content]}]
  {::pco/op-name 'psi.extension.tool/write
   ::pco/params  [:psi/agent-session-ctx :path :content]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   "write"
   {:path path
    :content content}))

(pco/defmutation run-update-tool
  "Apply a text update to a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx path oldText newText]}]
  {::pco/op-name 'psi.extension.tool/update
   ::pco/params  [:psi/agent-session-ctx :path :oldText :newText]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   "edit"
   {:path path
    :oldText oldText
    :newText newText}))

(pco/defmutation run-tool-plan
  "Execute a data-driven sequential tool plan."
  [_ {:keys [psi/agent-session-ctx steps stop-on-error?]}]
  {::pco/op-name 'psi.extension/run-tool-plan
   ::pco/params  [:psi/agent-session-ctx :steps]
   ::pco/output  [:psi.extension.tool-plan/succeeded?
                  :psi.extension.tool-plan/step-count
                  :psi.extension.tool-plan/completed-count
                  :psi.extension.tool-plan/failed-step-id
                  :psi.extension.tool-plan/results
                  :psi.extension.tool-plan/result-by-id
                  :psi.extension.tool-plan/error]}
  (run-tool-plan-mutation-payload agent-session-ctx steps stop-on-error?))

(pco/defmutation run-chain-tool
  "Execute a chained multi-step tool plan."
  [_ {:keys [psi/agent-session-ctx steps stop-on-error?]}]
  {::pco/op-name 'psi.extension.tool/chain
   ::pco/params  [:psi/agent-session-ctx :steps]
   ::pco/output  [:psi.extension.tool-plan/succeeded?
                  :psi.extension.tool-plan/step-count
                  :psi.extension.tool-plan/completed-count
                  :psi.extension.tool-plan/failed-step-id
                  :psi.extension.tool-plan/results
                  :psi.extension.tool-plan/result-by-id
                  :psi.extension.tool-plan/error]}
  (run-tool-plan-mutation-payload agent-session-ctx steps stop-on-error?))

;;; Workflow helpers

(defn- elapsed-ms
  [created-at finished-at]
  (when created-at
    (let [end (or finished-at (java.time.Instant/now))]
      (- (.toEpochMilli ^java.time.Instant end)
         (.toEpochMilli ^java.time.Instant created-at)))))

(defn- workflow->attrs
  [workflow]
  (if-not workflow
    {}
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
     :psi.extension.workflow/created-at    (:created-at workflow)
     :psi.extension.workflow/started-at    (:started-at workflow)
     :psi.extension.workflow/updated-at    (:updated-at workflow)
     :psi.extension.workflow/finished-at   (:finished-at workflow)
     :psi.extension.workflow/elapsed-ms    (elapsed-ms (:created-at workflow)
                                                       (:finished-at workflow))
     :psi.extension.workflow/event-count   (:event-count workflow)
     :psi.extension.workflow/last-event    (:last-event workflow)
     :psi.extension.workflow/events        (:events workflow)}))

;;; Workflow mutations

(pco/defmutation register-workflow-type
  "Register or replace an extension workflow type."
  [_ {:keys [psi/agent-session-ctx ext-path type description chart start-event initial-data-fn public-data-fn]}]
  {::pco/op-name 'psi.extension.workflow/register-type
   ::pco/params  [:psi/agent-session-ctx :ext-path :type :chart]
   ::pco/output  [:psi.extension/path
                  :psi.extension.workflow.type/name
                  :psi.extension.workflow.type/registered?
                  :psi.extension.workflow.type/names
                  :psi.extension.workflow/error]}
  (let [{:keys [registered? type type-names error]}
        (wf/register-type-in! (:workflow-registry agent-session-ctx)
                              ext-path
                              {:type            type
                               :description     description
                               :chart           chart
                               :start-event     start-event
                               :initial-data-fn initial-data-fn
                               :public-data-fn  public-data-fn})]
    {:psi.extension/path                            ext-path
     :psi.extension.workflow.type/name              type
     :psi.extension.workflow.type/registered?       registered?
     :psi.extension.workflow.type/names             type-names
     :psi.extension.workflow/error                  error}))

(pco/defmutation create-workflow
  "Create a workflow instance for an extension."
  [_ {:keys [psi/agent-session-ctx ext-path type id input meta auto-start? start-event
             track-background-job?]}]
  {::pco/op-name 'psi.extension.workflow/create
   ::pco/params  [:psi/agent-session-ctx :ext-path :type]
   ::pco/output  [:psi.extension.workflow/created?
                  :psi.extension.workflow/error
                  :psi.extension.workflow/id
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
                  :psi.extension.workflow/events
                  :psi.extension.background-job/id]}
  (let [{:keys [created? workflow error]}
        (wf/create-workflow-in! (:workflow-registry agent-session-ctx)
                                ext-path
                                {:type        type
                                 :id          id
                                 :input       input
                                 :meta        meta
                                 :auto-start? auto-start?
                                 :start-event start-event})
        payload (merge {:psi.extension.workflow/created? created?
                        :psi.extension.workflow/error    error}
                       (workflow->attrs workflow))
        job     (when created?
                  (bg-rt/maybe-track-background-workflow-job!
                   agent-session-ctx
                   'psi.extension.workflow/create
                   (cond-> {:ext-path ext-path :type type :id id :input input :meta meta
                            :auto-start? auto-start? :start-event start-event}
                     (some? track-background-job?)
                     (assoc :track-background-job? track-background-job?))
                   payload))]
    (cond-> payload
      (:job-id job) (assoc :psi.extension.background-job/id (:job-id job)))))

(pco/defmutation send-workflow-event
  "Send an event to an extension workflow instance."
  [_ {:keys [psi/agent-session-ctx ext-path id event data track-background-job?]}]
  {::pco/op-name 'psi.extension.workflow/send-event
   ::pco/params  [:psi/agent-session-ctx :ext-path :id :event]
   ::pco/output  [:psi.extension.workflow/event-accepted?
                  :psi.extension.workflow/error
                  :psi.extension.workflow/id
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
                  :psi.extension.workflow/events
                  :psi.extension.background-job/id]}
  (let [{:keys [event-accepted? workflow error]}
        (wf/send-event-in! (:workflow-registry agent-session-ctx) ext-path id event data)
        payload (merge {:psi.extension.workflow/event-accepted? event-accepted?
                        :psi.extension.workflow/error           error}
                       (workflow->attrs workflow))
        job     (when event-accepted?
                  (bg-rt/maybe-track-background-workflow-job!
                   agent-session-ctx
                   'psi.extension.workflow/send-event
                   (cond-> {:ext-path ext-path
                            :id id
                            :event event
                            :data data}
                     (some? track-background-job?)
                     (assoc :track-background-job? track-background-job?))
                   payload))]
    (cond-> payload
      (:job-id job) (assoc :psi.extension.background-job/id (:job-id job)))))

(pco/defmutation abort-workflow
  "Abort a running extension workflow instance."
  [_ {:keys [psi/agent-session-ctx ext-path id reason]}]
  {::pco/op-name 'psi.extension.workflow/abort
   ::pco/params  [:psi/agent-session-ctx :ext-path :id]
   ::pco/output  [:psi.extension.workflow/aborted?
                  :psi.extension.workflow/error
                  :psi.extension.workflow/id
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
                  :psi.extension.workflow/events]}
  (let [{:keys [aborted? workflow error]}
        (wf/abort-workflow-in! (:workflow-registry agent-session-ctx) ext-path id reason)]
    (merge {:psi.extension.workflow/aborted? aborted?
            :psi.extension.workflow/error    error}
           (workflow->attrs workflow))))

(pco/defmutation remove-workflow
  "Remove a completed or aborted extension workflow instance."
  [_ {:keys [psi/agent-session-ctx ext-path id]}]
  {::pco/op-name 'psi.extension.workflow/remove
   ::pco/params  [:psi/agent-session-ctx :ext-path :id]
   ::pco/output  [:psi.extension.workflow/removed?
                  :psi.extension.workflow/error
                  :psi.extension/path
                  :psi.extension.workflow/id]}
  (let [{:keys [removed? id error]} (wf/remove-workflow-in! (:workflow-registry agent-session-ctx) ext-path id)]
    {:psi.extension.workflow/removed? removed?
     :psi.extension.workflow/error    error
     :psi.extension/path              ext-path
     :psi.extension.workflow/id       id}))

;;; Extension and session lifecycle mutations

(pco/defmutation add-extension
  "Load an extension into the current session by path."
  [_ {:keys [psi/agent-session-ctx path]}]
  {::pco/op-name 'psi.extension/add-extension
   ::pco/params  [:psi/agent-session-ctx :path]
   ::pco/output  [:psi.extension/loaded?
                  :psi.extension/path
                  :psi.extension/error]}
  (let [{:keys [loaded? error]} (ext-rt/add-extension-in! agent-session-ctx path)]
    {:psi.extension/loaded? loaded?
     :psi.extension/path    path
     :psi.extension/error   error}))

(pco/defmutation create-session
  "Create a new session branch with optional name, worktree, system prompt, and thinking level."
  [_ {:keys [psi/agent-session-ctx session-name worktree-path system-prompt thinking-level]}]
  {::pco/op-name 'psi.extension/create-session
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.agent-session/session-id
                  :psi.agent-session/session-name
                  :psi.agent-session/cwd
                  :psi.agent-session/thinking-level]}
  (let [_  (core/new-session-in! agent-session-ctx {:session-name  session-name
                                                    :worktree-path worktree-path})
        _  (when system-prompt
             (dispatch/dispatch! agent-session-ctx :session/set-system-prompt {:prompt system-prompt} {:origin :mutations}))
        _  (when thinking-level
             (dispatch/dispatch! agent-session-ctx :session/set-thinking-level {:level thinking-level} {:origin :mutations}))
        sd (ss/get-session-data-in agent-session-ctx)]
    {:psi.agent-session/session-id     (:session-id sd)
     :psi.agent-session/session-name   (:session-name sd)
     :psi.agent-session/cwd            (:worktree-path sd)
     :psi.agent-session/thinking-level (:thinking-level sd)}))

(pco/defmutation create-child-session
  "Create a child session for agent execution without switching active session.
  Returns the child session-id. The child shares the parent's context but has
  its own journal, telemetry, and session data."
  [_ {:keys [psi/agent-session-ctx session-name system-prompt tool-schemas thinking-level]}]
  {::pco/op-name 'psi.extension/create-child-session
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.agent-session/session-id]}
  (let [child-sid (str (java.util.UUID/randomUUID))]
    (dispatch/dispatch! agent-session-ctx
                        :session/create-child
                        {:child-session-id child-sid
                         :session-name     session-name
                         :system-prompt    system-prompt
                         :tool-schemas     tool-schemas
                         :thinking-level   thinking-level}
                        {:origin :mutations})
    {:psi.agent-session/session-id child-sid}))

(pco/defmutation run-agent-loop-in-session
  "Run the agent loop for a specific child session.
  Scopes the ctx to the target session-id and runs the executor.
  Blocks until the agent loop completes. Returns the final result."
  [_ {:keys [psi/agent-session-ctx session-id prompt model api-key]}]
  {::pco/op-name 'psi.extension/run-agent-loop-in-session
   ::pco/params  [:psi/agent-session-ctx :session-id :prompt]
   ::pco/output  [:psi.agent-session/agent-run-ok?
                  :psi.agent-session/agent-run-text
                  :psi.agent-session/agent-run-elapsed-ms
                  :psi.agent-session/agent-run-error-message]}
  (let [scoped-ctx  (assoc agent-session-ctx :target-session-id session-id)
        ;; Resolve model to full schema from models/all-models
        session-model (:model (ss/get-session-data-in scoped-ctx))
        resolved-model (or model
                           (when session-model
                             (some (fn [m]
                                     (when (and (= (:provider m) (keyword (:provider session-model)))
                                                (= (:id m) (:id session-model)))
                                       m))
                                   (vals models/all-models)))
                           (get models/all-models :sonnet-4.6))
        user-msg   {:role      "user"
                    :content   [{:type :text :text (or prompt "")}]
                    :timestamp (java.time.Instant/now)}]
    (try
      (let [result (executor/run-agent-loop!
                    nil scoped-ctx nil
                    resolved-model
                    [user-msg]
                    (cond-> {}
                      api-key (assoc :api-key api-key)))
            text   (->> (:content result)
                        (keep (fn [c]
                                (case (:type c)
                                  :text (:text c)
                                  :error (:text c)
                                  nil)))
                        (clojure.string/join "\n"))
            ok?    (not= :error (:stop-reason result))]
        {:psi.agent-session/agent-run-ok?           ok?
         :psi.agent-session/agent-run-text          text
         :psi.agent-session/agent-run-elapsed-ms    (or (:elapsed-ms result) 0)
         :psi.agent-session/agent-run-error-message (:error-message result)})
      (catch Throwable e
        {:psi.agent-session/agent-run-ok?           false
         :psi.agent-session/agent-run-text          (str "Error: " (or (ex-message e) (.getMessage e) (str e)))
         :psi.agent-session/agent-run-elapsed-ms    0
         :psi.agent-session/agent-run-error-message (or (ex-message e) (.getMessage e) (str e))}))))

(pco/defmutation switch-session
  "Switch the active session to the given session-id."
  [_ {:keys [psi/agent-session-ctx session-id]}]
  {::pco/op-name 'psi.extension/switch-session
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/session-id
                  :psi.agent-session/session-name
                  :psi.agent-session/cwd]}
  (let [sd (core/ensure-session-loaded-in! agent-session-ctx session-id)]
    {:psi.agent-session/session-id   (:session-id sd)
     :psi.agent-session/session-name (:session-name sd)
     :psi.agent-session/cwd          (:worktree-path sd)}))

(pco/defmutation set-rpc-trace
  "Enable, disable, or toggle RPC trace logging for the current session."
  [_ {:keys [psi/agent-session-ctx enabled file] :as params}]
  {::pco/op-name 'psi.extension/set-rpc-trace
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.agent-session/rpc-trace-enabled
                  :psi.agent-session/rpc-trace-file]}
  (let [current       (or (session/get-state-value-in agent-session-ctx
                                                      (session/state-path :rpc-trace))
                          {:enabled? false :file nil})
        enabled?      (if (contains? params :enabled)
                        (boolean enabled)
                        (not (boolean (:enabled? current))))
        file-present? (contains? params :file)
        file*         (if file-present?
                        (when-not (str/blank? file)
                          file)
                        (:file current))]
    (when (and enabled? (str/blank? file*))
      (throw (ex-info "rpc trace requires a non-empty :file when enabled"
                      {:error-code "request/invalid-params"})))
    (dispatch/dispatch! agent-session-ctx :session/set-rpc-trace {:enabled? enabled? :file file*} {:origin :mutations})
    {:psi.agent-session/rpc-trace-enabled enabled?
     :psi.agent-session/rpc-trace-file    file*}))

(pco/defmutation interrupt
  "Request an interrupt at the next turn boundary."
  [_ {:keys [psi/agent-session-ctx]}]
  {::pco/op-name 'psi.extension/interrupt
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.agent-session/interrupt-pending
                  :psi.agent-session/is-idle]}
  (let [{:keys [pending?]} (core/request-interrupt-in! agent-session-ctx)]
    {:psi.agent-session/interrupt-pending (boolean pending?)
     :psi.agent-session/is-idle           (ss/idle-in? agent-session-ctx)}))

(pco/defmutation compact
  "Trigger manual context compaction for the current session."
  [_ {:keys [psi/agent-session-ctx instructions]}]
  {::pco/op-name 'psi.extension/compact
   ::pco/params  [:psi/agent-session-ctx]
   ::pco/output  [:psi.agent-session/is-compacting
                  :psi.agent-session/session-entry-count]}
  (core/manual-compact-in! agent-session-ctx instructions)
  {:psi.agent-session/is-compacting     false
   :psi.agent-session/session-entry-count
   (count (session/get-state-value-in agent-session-ctx (session/state-path :journal (ss/active-session-id-in agent-session-ctx))))})

(pco/defmutation append-entry
  "Append a custom journal entry to the current session."
  [_ {:keys [psi/agent-session-ctx custom-type data]}]
  {::pco/op-name 'psi.extension/append-entry
   ::pco/params  [:psi/agent-session-ctx :custom-type]
   ::pco/output  [:psi.agent-session/session-entry-count]}
  (ss/journal-append-in! agent-session-ctx
                         (persist/custom-message-entry custom-type (str data) nil false))
  {:psi.agent-session/session-entry-count
   (count (session/get-state-value-in agent-session-ctx (session/state-path :journal (ss/active-session-id-in agent-session-ctx))))})

(pco/defmutation send-message
  "Inject an extension message into the session journal."
  [_ {:keys [psi/agent-session-ctx role content custom-type]}]
  {::pco/op-name 'psi.extension/send-message
   ::pco/params  [:psi/agent-session-ctx :role :content]
   ::pco/output  [:psi.extension/message]}
  (let [msg (ext-rt/send-extension-message-in! agent-session-ctx
                                               (or role "assistant")
                                               (or content "")
                                               custom-type)]
    ;; After injecting an extension message, check whether any workflow-backed
    ;; background jobs have reached a terminal state.  This covers the case
    ;; where a workflow (e.g. agent-chain) completes and emits its result via
    ;; send-message rather than through the normal agent turn boundary.
    ;;
    ;; We run one immediate pass plus a bounded retry loop to absorb async
    ;; workflow completion races (workflow flips to :done/:error shortly after
    ;; message injection).
    (when agent-session-ctx
      (bg-rt/reconcile-and-emit-background-job-terminals-in! agent-session-ctx)
      (future
        (dotimes [_ 12]
          (Thread/sleep 100)
          (try
            (bg-rt/reconcile-and-emit-background-job-terminals-in! agent-session-ctx)
            (catch Exception _ nil)))))
    {:psi.extension/message msg}))

(pco/defmutation send-prompt
  "Send an extension prompt to the current session."
  [_ {:keys [psi/agent-session-ctx content source]}]
  {::pco/op-name 'psi.extension/send-prompt
   ::pco/params  [:psi/agent-session-ctx :content]
   ::pco/output  [:psi.extension/prompt-accepted?
                  :psi.extension/prompt-delivery]}
  (let [{:keys [accepted delivery]}
        (ext-rt/send-extension-prompt-in! agent-session-ctx (or content "") source)]
    {:psi.extension/prompt-accepted? accepted
     :psi.extension/prompt-delivery  delivery}))

;;; Widget spec mutations

(pco/defmutation set-widget-spec
  "Register or replace a declarative WidgetSpec for the calling extension.
   `spec` must be a valid psi.ui.widget-spec map (see psi.ui.widget-spec/validate-spec).
   Returns {:psi.ui/widget-spec-accepted? true} on success, or an error."
  [_ {:keys [psi/agent-session-ctx spec]}]
  {::pco/op-name 'psi.ui/set-widget-spec
   ::pco/params  [:psi/agent-session-ctx :spec]
   ::pco/output  [:psi.ui/widget-spec-accepted?
                  :psi.ui/widget-spec-errors]}
  (let [ui-atom (ss/ui-state-view-in agent-session-ctx)
        ;; Extension id is derived from the spec's :extension-id field,
        ;; falling back to a generic sentinel. Extensions should set :extension-id.
        ext-id  (or (:extension-id spec) "unknown")
        result  (ui-state/set-widget-spec! ui-atom ext-id spec)]
    (if result
      {:psi.ui/widget-spec-accepted? false
       :psi.ui/widget-spec-errors    (:errors result)}
      {:psi.ui/widget-spec-accepted? true
       :psi.ui/widget-spec-errors    nil})))

(pco/defmutation clear-widget-spec
  "Remove a declarative WidgetSpec by widget-id for the calling extension."
  [_ {:keys [psi/agent-session-ctx extension-id widget-id]}]
  {::pco/op-name 'psi.ui/clear-widget-spec
   ::pco/params  [:psi/agent-session-ctx :extension-id :widget-id]
   ::pco/output  [:psi.ui/widget-spec-cleared?]}
  (let [ui-atom (ss/ui-state-view-in agent-session-ctx)]
    (ui-state/clear-widget-spec! ui-atom extension-id widget-id)
    {:psi.ui/widget-spec-cleared? true}))

;;; Registry

(def all-mutations
  "All agent-session mutations defined in this namespace."
  [set-session-name
   add-prompt-template
   add-skill
   add-tool
   set-active-tools
   register-tool
   register-command
   register-handler
   register-flag
   register-shortcut
   register-prompt-contribution
   update-prompt-contribution
   unregister-prompt-contribution
   set-model
   run-read-tool
   run-bash-tool
   run-write-tool
   run-update-tool
   run-tool-plan
   run-chain-tool
   register-workflow-type
   create-workflow
   send-workflow-event
   abort-workflow
   remove-workflow
   add-extension
   create-session
   create-child-session
   run-agent-loop-in-session
   switch-session
   set-rpc-trace
   interrupt
   compact
   append-entry
   send-message
   send-prompt
   set-widget-spec
   clear-widget-spec])
