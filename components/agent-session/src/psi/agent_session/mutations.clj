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
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.post-tool :as post-tool]
   [psi.agent-session.service-protocol :as service-protocol]
   [psi.agent-session.service-protocol-stdio-jsonrpc :as stdio-jsonrpc]
   [psi.agent-session.services :as services]
   [psi.agent-session.session :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.ai.models :as models]
   [psi.agent-session.workflow-mutations :as workflow-mutations]))

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
  [_ {:keys [psi/agent-session-ctx session-id name]}]
  {::pco/op-name 'psi.extension/set-session-name
   ::pco/params  [:psi/agent-session-ctx :session-id :name]
   ::pco/output  [:psi.agent-session/session-name]}
  (dispatch/dispatch! agent-session-ctx :session/set-session-name {:session-id session-id :name name} {:origin :mutations})
  {:psi.agent-session/session-name name})

;;; Prompt-template and skill registration mutations

(pco/defmutation add-prompt-template
  "Add a prompt template to the session if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx session-id template]}]
  {::pco/op-name 'psi.extension/add-prompt-template
   ::pco/params  [:psi/agent-session-ctx :session-id :template]
   ::pco/output  [:psi.prompt-template/added?
                  :psi.prompt-template/count]}
  (let [{:keys [added? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-prompt-template
                            {:session-id session-id :template template}
                            {:origin :mutations})]
    {:psi.prompt-template/added? (boolean added?)
     :psi.prompt-template/count  (or count 0)}))

(pco/defmutation add-skill
  "Add a skill to the session if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx session-id skill]}]
  {::pco/op-name 'psi.extension/add-skill
   ::pco/params  [:psi/agent-session-ctx :session-id :skill]
   ::pco/output  [:psi.skill/added?
                  :psi.skill/count]}
  (let [{:keys [added? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/register-skill
                            {:session-id session-id :skill skill}
                            {:origin :mutations})]
    {:psi.skill/added? (boolean added?)
     :psi.skill/count  (or count 0)}))

;;; Tool mutations

(pco/defmutation add-tool
  "Add a tool to the active agent tool set if its :name is not already present."
  [_ {:keys [psi/agent-session-ctx session-id tool]}]
  {::pco/op-name 'psi.extension/add-tool
   ::pco/params  [:psi/agent-session-ctx :session-id :tool]
   ::pco/output  [:psi.tool/added?
                  :psi.tool/count]}
  (let [{:keys [added? count]}
        (or (dispatch/dispatch! agent-session-ctx
                                :session/add-tool
                                {:session-id session-id :tool tool}
                                {:origin :mutations})
            {:added? false :count 0})]
    {:psi.tool/added? (boolean added?)
     :psi.tool/count  (or count 0)}))

(pco/defmutation set-active-tools
  "Replace the agent's active tool set with the named subset."
  [_ {:keys [psi/agent-session-ctx session-id tool-names]}]
  {::pco/op-name 'psi.extension/set-active-tools
   ::pco/params  [:psi/agent-session-ctx :session-id :tool-names]
   ::pco/output  [:psi.tool/count
                  :psi.tool/names]}
  (let [agent-ctx      (ss/agent-ctx-in agent-session-ctx session-id)
        current-tools  (:tools (agent/get-data-in agent-ctx))
        by-name        (into {} (map (juxt :name identity)) current-tools)
        selected-tools (vec (keep by-name tool-names))]
    (dispatch/dispatch! agent-session-ctx
                        :session/set-active-tools
                        {:session-id session-id :tool-maps selected-tools}
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

(pco/defmutation set-allowed-events
  "Set the explicit allowed event set for an extension."
  [_ {:keys [psi/agent-session-ctx ext-path allowed-events]}]
  {::pco/op-name 'psi.extension/set-allowed-events
   ::pco/params  [:psi/agent-session-ctx :ext-path :allowed-events]
   ::pco/output  [:psi.extension/path
                  :psi.extension/allowed-events]}
  (let [reg          (:extension-registry agent-session-ctx)
        allowed-set  (set (or allowed-events #{}))]
    (ext/set-allowed-events-in! reg ext-path allowed-set)
    {:psi.extension/path           ext-path
     :psi.extension/allowed-events (vec (sort allowed-set))}))

(pco/defmutation register-shortcut
  "Register an extension-owned keyboard shortcut into the extension registry."
  [_ {:keys [psi/agent-session-ctx ext-path key opts]}]
  {::pco/op-name 'psi.extension/register-shortcut
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :opts]
   ::pco/output  [:psi.extension/path]}
  (let [reg (:extension-registry agent-session-ctx)]
    (ext/register-shortcut-in! reg ext-path (assoc opts :key key))
    {:psi.extension/path ext-path}))

(pco/defmutation register-post-tool-processor
  "Register an extension-owned post-tool processor."
  [_ {:keys [psi/agent-session-ctx ext-path name match timeout-ms handler]}]
  {::pco/op-name 'psi.extension/register-post-tool-processor
   ::pco/params  [:psi/agent-session-ctx :ext-path :name :match :timeout-ms :handler]
   ::pco/output  [:psi.extension/path]}
  (post-tool/register-processor-in!
   agent-session-ctx
   {:name name :ext-path ext-path :match match :timeout-ms timeout-ms :handler handler})
  {:psi.extension/path ext-path})

(pco/defmutation ensure-service
  "Ensure an extension-owned managed service exists."
  [_ {:keys [psi/agent-session-ctx ext-path key type spec]}]
  {::pco/op-name 'psi.extension/ensure-service
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :type :spec]
   ::pco/output  [:psi.extension/path]}
  (services/ensure-service-in!
   agent-session-ctx
   {:key key :type type :spec spec :ext-path ext-path})
  (when (and (= :subprocess (or type :subprocess))
             (#{:json-rpc :jsonrpc} (:protocol spec)))
    (stdio-jsonrpc/attach-jsonrpc-runtime-in! agent-session-ctx key))
  {:psi.extension/path ext-path})

(pco/defmutation stop-service
  "Stop an extension-owned managed service by key."
  [_ {:keys [psi/agent-session-ctx ext-path key]}]
  {::pco/op-name 'psi.extension/stop-service
   ::pco/params  [:psi/agent-session-ctx :ext-path :key]
   ::pco/output  [:psi.extension/path]}
  (services/stop-service-in! agent-session-ctx key)
  {:psi.extension/path ext-path})

(pco/defmutation service-request
  "Send a correlated request to an extension-owned managed service."
  [_ {:keys [psi/agent-session-ctx ext-path key request-id payload timeout-ms dispatch-id]}]
  {::pco/op-name 'psi.extension/service-request
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :request-id :payload :timeout-ms :dispatch-id]
   ::pco/output  [:psi.extension/path
                  :psi.extension.service/service-key
                  :psi.extension.service/request-id
                  :psi.extension.service/payload
                  :psi.extension.service/timeout-ms
                  :psi.extension.service/response]}
  (let [result (service-protocol/send-service-request!
                agent-session-ctx key
                {:request-id request-id
                 :payload payload
                 :timeout-ms timeout-ms}
                {:dispatch-id dispatch-id})]
    {:psi.extension/path                ext-path
     :psi.extension.service/service-key (:service-key result)
     :psi.extension.service/request-id  (:request-id result)
     :psi.extension.service/payload     (:payload result)
     :psi.extension.service/timeout-ms  (:timeout-ms result)
     :psi.extension.service/response    (:response result)}))

(pco/defmutation service-notify
  "Send a notification to an extension-owned managed service."
  [_ {:keys [psi/agent-session-ctx ext-path key payload dispatch-id]}]
  {::pco/op-name 'psi.extension/service-notify
   ::pco/params  [:psi/agent-session-ctx :ext-path :key :payload :dispatch-id]
   ::pco/output  [:psi.extension/path
                  :psi.extension.service/service-key
                  :psi.extension.service/payload]}
  (let [result (service-protocol/send-service-notification!
                agent-session-ctx key payload {:dispatch-id dispatch-id})]
    {:psi.extension/path                ext-path
     :psi.extension.service/service-key (:service-key result)
     :psi.extension.service/payload     (:payload result)}))

;;; Prompt-contribution mutations

(pco/defmutation register-prompt-contribution
  "Register or replace an extension-owned prompt contribution by id."
  [_ {:keys [psi/agent-session-ctx session-id ext-path id contribution]}]
  {::pco/op-name 'psi.extension/register-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :id :contribution]
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
                            {:session-id session-id :ext-path ext-path :id id :contribution contribution}
                            {:origin :mutations})]
    (merge {:psi.extension/path                            (str ext-path)
            :psi.extension.prompt-contribution/id          (str id)
            :psi.extension.prompt-contribution/registered? registered?
            :psi.extension.prompt-contribution/count       count}
           (prompt-contribution-mutation-view contribution))))

(pco/defmutation update-prompt-contribution
  "Patch an existing extension-owned prompt contribution."
  [_ {:keys [psi/agent-session-ctx session-id ext-path id patch]}]
  {::pco/op-name 'psi.extension/update-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :id :patch]
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
                            {:session-id session-id :ext-path ext-path :id id :patch patch}
                            {:origin :mutations})]
    (merge {:psi.extension/path                         (str ext-path)
            :psi.extension.prompt-contribution/id       (str id)
            :psi.extension.prompt-contribution/updated? updated?
            :psi.extension.prompt-contribution/count    count}
           (when contribution
             (prompt-contribution-mutation-view contribution)))))

(pco/defmutation unregister-prompt-contribution
  "Remove an extension-owned prompt contribution by id."
  [_ {:keys [psi/agent-session-ctx session-id ext-path id]}]
  {::pco/op-name 'psi.extension/unregister-prompt-contribution
   ::pco/params  [:psi/agent-session-ctx :session-id :ext-path :id]
   ::pco/output  [:psi.extension/path
                  :psi.extension.prompt-contribution/id
                  :psi.extension.prompt-contribution/removed?
                  :psi.extension.prompt-contribution/count]}
  (let [{:keys [removed? count]}
        (dispatch/dispatch! agent-session-ctx
                            :session/unregister-prompt-contribution
                            {:session-id session-id :ext-path ext-path :id id}
                            {:origin :mutations})]
    {:psi.extension/path                          (str ext-path)
     :psi.extension.prompt-contribution/id        (str id)
     :psi.extension.prompt-contribution/removed?  removed?
     :psi.extension.prompt-contribution/count     count}))

;;; Model mutation

(pco/defmutation set-model
  "Set the active model and clamp thinking-level for the current session.
   Optional :scope — :session (runtime only), :project (default), :user (user-global)."
  [_ {:keys [psi/agent-session-ctx session-id model scope]}]
  {::pco/op-name 'psi.extension/set-model
   ::pco/params  [:psi/agent-session-ctx :session-id :model]
   ::pco/output  [:psi.agent-session/model
                  :psi.agent-session/thinking-level]}
  (let [result (dispatch/dispatch! agent-session-ctx :session/set-model
                                   (cond-> {:session-id session-id :model model}
                                     scope (assoc :scope scope))
                                   {:origin :mutations})]
    {:psi.agent-session/model          (:model result)
     :psi.agent-session/thinking-level (:thinking-level result)}))

;;; Tool mutations

(defn- run-tool-mutation-in!
  "Execute a single tool call and normalize result attrs for mutation payloads."
  [ctx session-id tool-name args]
  (when-not (map? args)
    (throw (ex-info "Tool args must be a map"
                    {:tool tool-name
                     :args args})))
  (let [step-id         (keyword (str "tool-" tool-name "-" (java.util.UUID/randomUUID)))
        normalized-args (walk/stringify-keys args)
        result          (tool-plan/run-tool-plan-step-in! ctx session-id step-id tool-name normalized-args)]
    {:psi.extension.tool/name     tool-name
     :psi.extension.tool/content  (:content result)
     :psi.extension.tool/is-error (boolean (:is-error result))
     :psi.extension.tool/details  (:details result)
     :psi.extension.tool/result   result}))

(defn- run-tool-plan-mutation-payload
  [ctx session-id steps stop-on-error?]
  (let [plan-opts   (cond-> {:steps steps}
                      (some? stop-on-error?) (assoc :stop-on-error? stop-on-error?))
        {:keys [succeeded? step-count completed-count failed-step-id results result-by-id error]}
        (tool-plan/run-tool-plan-in! ctx session-id plan-opts)]
    {:psi.extension.tool-plan/succeeded?      succeeded?
     :psi.extension.tool-plan/step-count      step-count
     :psi.extension.tool-plan/completed-count completed-count
     :psi.extension.tool-plan/failed-step-id  failed-step-id
     :psi.extension.tool-plan/results         results
     :psi.extension.tool-plan/result-by-id    result-by-id
     :psi.extension.tool-plan/error           error}))

(pco/defmutation run-read-tool
  "Read a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id path offset limit]}]
  {::pco/op-name 'psi.extension.tool/read
   ::pco/params  [:psi/agent-session-ctx :session-id :path]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "read"
   (cond-> {:path path}
     (some? offset) (assoc :offset offset)
     (some? limit)  (assoc :limit limit))))

(pco/defmutation run-bash-tool
  "Run a bash command via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id command timeout]}]
  {::pco/op-name 'psi.extension.tool/bash
   ::pco/params  [:psi/agent-session-ctx :session-id :command]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "bash"
   (cond-> {:command command}
     (some? timeout) (assoc :timeout timeout))))

(pco/defmutation run-write-tool
  "Write a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id path content]}]
  {::pco/op-name 'psi.extension.tool/write
   ::pco/params  [:psi/agent-session-ctx :session-id :path :content]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "write"
   {:path path
    :content content}))

(pco/defmutation run-update-tool
  "Apply a text update to a file via the runtime tool executor."
  [_ {:keys [psi/agent-session-ctx session-id path oldText newText]}]
  {::pco/op-name 'psi.extension.tool/update
   ::pco/params  [:psi/agent-session-ctx :session-id :path :oldText :newText]
   ::pco/output  [:psi.extension.tool/name
                  :psi.extension.tool/content
                  :psi.extension.tool/is-error
                  :psi.extension.tool/details
                  :psi.extension.tool/result]}
  (run-tool-mutation-in!
   agent-session-ctx
   session-id
   "edit"
   {:path path
    :oldText oldText
    :newText newText}))

(pco/defmutation run-tool-plan
  "Execute a data-driven sequential tool plan."
  [_ {:keys [psi/agent-session-ctx session-id steps stop-on-error?]}]
  {::pco/op-name 'psi.extension/run-tool-plan
   ::pco/params  [:psi/agent-session-ctx :session-id :steps]
   ::pco/output  [:psi.extension.tool-plan/succeeded?
                  :psi.extension.tool-plan/step-count
                  :psi.extension.tool-plan/completed-count
                  :psi.extension.tool-plan/failed-step-id
                  :psi.extension.tool-plan/results
                  :psi.extension.tool-plan/result-by-id
                  :psi.extension.tool-plan/error]}
  (run-tool-plan-mutation-payload agent-session-ctx session-id steps stop-on-error?))

(pco/defmutation run-chain-tool
  "Execute a chained multi-step tool plan."
  [_ {:keys [psi/agent-session-ctx session-id steps stop-on-error?]}]
  {::pco/op-name 'psi.extension.tool/chain
   ::pco/params  [:psi/agent-session-ctx :session-id :steps]
   ::pco/output  [:psi.extension.tool-plan/succeeded?
                  :psi.extension.tool-plan/step-count
                  :psi.extension.tool-plan/completed-count
                  :psi.extension.tool-plan/failed-step-id
                  :psi.extension.tool-plan/results
                  :psi.extension.tool-plan/result-by-id
                  :psi.extension.tool-plan/error]}
  (run-tool-plan-mutation-payload agent-session-ctx session-id steps stop-on-error?))

;;; Extension and session lifecycle mutations

(pco/defmutation add-extension
  "Load an extension into the current session by path."
  [_ {:keys [psi/agent-session-ctx session-id path]}]
  {::pco/op-name 'psi.extension/add-extension
   ::pco/params  [:psi/agent-session-ctx :session-id :path]
   ::pco/output  [:psi.extension/loaded?
                  :psi.extension/path
                  :psi.extension/error]}
  (let [{:keys [loaded? error]} (ext-rt/add-extension-in! agent-session-ctx session-id path)]
    {:psi.extension/loaded? loaded?
     :psi.extension/path    path
     :psi.extension/error   error}))

(pco/defmutation create-session
  "Create a new session branch with optional name, worktree, system prompt, and thinking level."
  [_ {:keys [psi/agent-session-ctx parent-session-id session-name worktree-path system-prompt thinking-level]}]
  {::pco/op-name 'psi.extension/create-session
   ::pco/params  [:psi/agent-session-ctx :parent-session-id]
   ::pco/output  [:psi.agent-session/session-id
                  :psi.agent-session/session-name
                  :psi.agent-session/cwd
                  :psi.agent-session/thinking-level]}
  (let [sd                 (core/new-session-in! agent-session-ctx parent-session-id {:session-name  session-name
                                                                                      :worktree-path worktree-path})
        new-sid            (:session-id sd)
        _       (when system-prompt
                  (dispatch/dispatch! agent-session-ctx :session/set-system-prompt {:session-id new-sid :prompt system-prompt} {:origin :mutations}))
        _       (when thinking-level
                  (dispatch/dispatch! agent-session-ctx :session/set-thinking-level {:session-id new-sid :level thinking-level} {:origin :mutations}))
        sd      (ss/get-session-data-in agent-session-ctx new-sid)]
    {:psi.agent-session/session-id     (:session-id sd)
     :psi.agent-session/session-name   (:session-name sd)
     :psi.agent-session/cwd            (:worktree-path sd)
     :psi.agent-session/thinking-level (:thinking-level sd)}))

(pco/defmutation create-child-session
  "Create a child session for agent execution without switching active session.
  Returns the child session-id. The child shares the parent's context but has
  its own journal, telemetry, and session data."
  [_ {:keys [psi/agent-session-ctx session-id session-name system-prompt tool-defs thinking-level]}]
  {::pco/op-name 'psi.extension/create-child-session
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/session-id]}
  (let [child-sid  (str (java.util.UUID/randomUUID))]
    (dispatch/dispatch! agent-session-ctx
                        :session/create-child
                        {:session-id       session-id
                         :child-session-id child-sid
                         :session-name     session-name
                         :system-prompt    system-prompt
                         :tool-defs        tool-defs
                         :thinking-level   thinking-level}
                        {:origin :mutations})
    {:psi.agent-session/session-id child-sid}))

(pco/defmutation run-agent-loop-in-session
  "Run the prompt lifecycle for a specific child session.
  Scopes the ctx to the target session-id and blocks until the turn completes.
  Returns the final assistant result summary."
  [_ {:keys [psi/agent-session-ctx session-id prompt model api-key]}]
  {::pco/op-name 'psi.extension/run-agent-loop-in-session
   ::pco/params  [:psi/agent-session-ctx :session-id :prompt]
   ::pco/output  [:psi.agent-session/agent-run-ok?
                  :psi.agent-session/agent-run-text
                  :psi.agent-session/agent-run-elapsed-ms
                  :psi.agent-session/agent-run-error-message]}
  (let [session-model   (:model (ss/get-session-data-in agent-session-ctx session-id))
        resolved-model  (or model
                            (when session-model
                              (some (fn [m]
                                      (when (and (= (:provider m) (keyword (:provider session-model)))
                                                 (= (:id m) (:id session-model)))
                                        m))
                                    (vals models/all-models)))
                            (get models/all-models :sonnet-4.6))
        started-ms      (System/currentTimeMillis)]
    (try
      (dispatch/dispatch! agent-session-ctx :session/set-model
                          {:session-id session-id
                           :model resolved-model
                           :scope :session}
                          {:origin :mutations})
      (core/prompt-in! agent-session-ctx session-id (or prompt "") nil
                       {:runtime-opts (cond-> {}
                                        api-key (assoc :api-key api-key))})
      (let [result (core/last-assistant-message-in agent-session-ctx session-id)
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
         :psi.agent-session/agent-run-elapsed-ms    (- (System/currentTimeMillis) started-ms)
         :psi.agent-session/agent-run-error-message (:error-message result)})
      (catch Throwable e
        {:psi.agent-session/agent-run-ok?           false
         :psi.agent-session/agent-run-text          (str "Error: " (or (ex-message e) (.getMessage e) (str e)))
         :psi.agent-session/agent-run-elapsed-ms    (- (System/currentTimeMillis) started-ms)
         :psi.agent-session/agent-run-error-message (or (ex-message e) (.getMessage e) (str e))}))))

(pco/defmutation switch-session
  "Switch the active session to the given session-id."
  [_ {:keys [psi/agent-session-ctx source-session-id session-id]}]
  {::pco/op-name 'psi.extension/switch-session
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/session-id
                  :psi.agent-session/session-name
                  :psi.agent-session/cwd]}
  (let [origin-session-id (or source-session-id session-id)
        sd                (core/ensure-session-loaded-in! agent-session-ctx origin-session-id session-id)]
    {:psi.agent-session/session-id   (:session-id sd)
     :psi.agent-session/session-name (:session-name sd)
     :psi.agent-session/cwd          (:worktree-path sd)}))

(pco/defmutation set-rpc-trace
  "Enable, disable, or toggle RPC trace logging for the current session."
  [_ {:keys [psi/agent-session-ctx session-id enabled file] :as params}]
  {::pco/op-name 'psi.extension/set-rpc-trace
   ::pco/params  [:psi/agent-session-ctx :session-id]
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
    (dispatch/dispatch! agent-session-ctx :session/set-rpc-trace {:session-id session-id :enabled? enabled? :file file*} {:origin :mutations})
    {:psi.agent-session/rpc-trace-enabled enabled?
     :psi.agent-session/rpc-trace-file    file*}))

(pco/defmutation interrupt
  "Request an interrupt at the next turn boundary."
  [_ {:keys [psi/agent-session-ctx session-id]}]
  {::pco/op-name 'psi.extension/interrupt
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/interrupt-pending
                  :psi.agent-session/is-idle]}
  (let [{:keys [pending?]} (core/request-interrupt-in! agent-session-ctx session-id)]
    {:psi.agent-session/interrupt-pending (boolean pending?)
     :psi.agent-session/is-idle           (ss/idle-in? agent-session-ctx session-id)}))

(pco/defmutation compact
  "Trigger manual context compaction for the current session."
  [_ {:keys [psi/agent-session-ctx session-id instructions]}]
  {::pco/op-name 'psi.extension/compact
   ::pco/params  [:psi/agent-session-ctx :session-id]
   ::pco/output  [:psi.agent-session/is-compacting
                  :psi.agent-session/session-entry-count]}
  (core/manual-compact-in! agent-session-ctx session-id instructions)
  {:psi.agent-session/is-compacting     false
   :psi.agent-session/session-entry-count
   (count (session/get-state-value-in agent-session-ctx (session/state-path :journal session-id)))})

(pco/defmutation append-entry
  "Append a custom journal entry to the current session."
  [_ {:keys [psi/agent-session-ctx session-id custom-type data]}]
  {::pco/op-name 'psi.extension/append-entry
   ::pco/params  [:psi/agent-session-ctx :session-id :custom-type]
   ::pco/output  [:psi.agent-session/session-entry-count]}
  (ss/journal-append-in! agent-session-ctx session-id
                         (persist/custom-message-entry custom-type (str data) nil false))
  {:psi.agent-session/session-entry-count
   (count (session/get-state-value-in agent-session-ctx (session/state-path :journal session-id)))})

(pco/defmutation send-message
  "Inject an extension message into the session journal."
  [_ {:keys [psi/agent-session-ctx session-id role content custom-type]}]
  {::pco/op-name 'psi.extension/send-message
   ::pco/params  [:psi/agent-session-ctx :session-id :role :content]
   ::pco/output  [:psi.extension/message]}
  (let [msg (ext-rt/send-extension-message-in! agent-session-ctx
                                               session-id
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
      (bg-rt/reconcile-and-emit-background-job-terminals-in! agent-session-ctx session-id)
      (future
        (dotimes [_ 12]
          (Thread/sleep 100)
          (try
            (bg-rt/reconcile-and-emit-background-job-terminals-in! agent-session-ctx session-id)
            (catch Exception _ nil)))))
    {:psi.extension/message msg}))

(pco/defmutation send-prompt
  "Send an extension prompt to the current session."
  [_ {:keys [psi/agent-session-ctx session-id content source]}]
  {::pco/op-name 'psi.extension/send-prompt
   ::pco/params  [:psi/agent-session-ctx :session-id :content]
   ::pco/output  [:psi.extension/prompt-accepted?
                  :psi.extension/prompt-delivery]}
  (let [{:keys [accepted delivery]}
        (ext-rt/send-extension-prompt-in! agent-session-ctx session-id (or content "") source)]
    {:psi.extension/prompt-accepted? accepted
     :psi.extension/prompt-delivery  delivery}))

;;; Widget spec mutations

(pco/defmutation set-widget-spec
  "Register or replace a declarative WidgetSpec for the calling extension.
   `spec` must be a valid psi.ui.widget-spec map (see psi.ui.widget-spec/validate-spec).
   Returns {:psi.ui/widget-spec-accepted? true} on success, or an error."
  [_ {:keys [psi/agent-session-ctx session-id spec]}]
  {::pco/op-name 'psi.ui/set-widget-spec
   ::pco/params  [:psi/agent-session-ctx :session-id :spec]
   ::pco/output  [:psi.ui/widget-spec-accepted?
                  :psi.ui/widget-spec-errors]}
  (let [{:keys [accepted? errors]}
        (dispatch/dispatch! agent-session-ctx
                            :session/ui-set-widget-spec
                            {:session-id   session-id
                             :extension-id (or (:extension-id spec) "unknown")
                             :spec         spec}
                            {:origin :mutations})]
    {:psi.ui/widget-spec-accepted? (boolean accepted?)
     :psi.ui/widget-spec-errors    errors}))

(pco/defmutation clear-widget-spec
  "Remove a declarative WidgetSpec by widget-id for the calling extension."
  [_ {:keys [psi/agent-session-ctx session-id extension-id widget-id]}]
  {::pco/op-name 'psi.ui/clear-widget-spec
   ::pco/params  [:psi/agent-session-ctx :session-id :extension-id :widget-id]
   ::pco/output  [:psi.ui/widget-spec-cleared?]}
  (dispatch/dispatch! agent-session-ctx
                      :session/ui-clear-widget-spec
                      {:session-id   session-id
                       :extension-id extension-id
                       :widget-id    widget-id}
                      {:origin :mutations})
  {:psi.ui/widget-spec-cleared? true})

;;; Registry

(def all-mutations
  "All agent-session mutations defined in this namespace."
  (into [set-session-name
         add-prompt-template
         add-skill
         add-tool
         set-active-tools
         register-tool
         register-command
         register-handler
         register-flag
         set-allowed-events
         register-shortcut
         register-post-tool-processor
         ensure-service
         stop-service
         service-request
         service-notify
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
         clear-widget-spec]
        workflow-mutations/all-mutations))
