(ns psi.agent-session.dispatch-handlers.scheduler
  "Dispatch handlers for session-scoped scheduled prompt injection."
  (:require
   [psi.agent-core.core :as agent]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.session-lifecycle :as session-lifecycle]
   [psi.agent-session.session-state :as ss]))

(defn- register-core-handler! [event handler]
  (dispatch/register-handler! event handler))

(defn- scheduler-state-in
  [ctx session-id]
  (or (ss/get-state-value-in ctx (ss/state-path :scheduler session-id))
      (scheduler/empty-state)))

(defn- scheduler-update
  [session-id f]
  (ss/session-update
   session-id
   #(update % :scheduler (fn [scheduler-state]
                           (f (or scheduler-state (scheduler/empty-state)))))))

(defn- scheduler-error-summary [e]
  {:message (or (ex-message e) (str e))
   :class (.getName (class e))
   :data (ex-data e)})

(defn- scheduled-user-message
  [{:keys [schedule-id label message]}]
  {:role "user"
   :content [{:type :text :text message}]
   :timestamp (java.time.Instant/now)
   :source :scheduled
   :schedule-id schedule-id
   :label label})

(defn- session-config-summary
  [session-config]
  (when session-config
    {:session-name (get session-config :session-name)
     :model (get session-config :model)
     :thinking-level (get session-config :thinking-level)
     :skill-count (count (or (get session-config :skills) []))
     :tool-count (count (or (get session-config :tool-defs) []))
     :has-system-prompt? (boolean (get session-config :system-prompt))
     :has-developer-prompt? (boolean (get session-config :developer-prompt))
     :preloaded-message-count (count (or (get session-config :preloaded-messages) []))
     :has-prompt-component-selection? (boolean (get session-config :prompt-component-selection))}))

(defn register!
  [_ctx]
  (register-core-handler!
   :scheduler/create
   (fn [ctx {:keys [session-id schedule-id kind label message session-config created-at fire-at]}]
     (let [created-at (or created-at (java.time.Instant/now))
           {state' :state schedule :schedule}
           (scheduler/create-schedule
            (scheduler-state-in ctx session-id)
            {:schedule-id schedule-id
             :kind (or kind :message)
             :label label
             :message message
             :created-at created-at
             :fire-at fire-at
             :origin-session-id session-id
             :session-config session-config
             :session-config-summary (session-config-summary session-config)})]
       {:root-state-update (scheduler-update session-id (constantly state'))
        :effects [{:effect/type :scheduler/start-timer
                   :session-id session-id
                   :schedule-id schedule-id
                   :fire-at fire-at}]
        :return schedule})))

  (register-core-handler!
   :scheduler/cancel
   (fn [ctx {:keys [session-id schedule-id]}]
     (let [{state' :state schedule :schedule}
           (scheduler/cancel-schedule (scheduler-state-in ctx session-id) schedule-id)]
       {:root-state-update (scheduler-update session-id (constantly state'))
        :effects [{:effect/type :scheduler/cancel-timer
                   :schedule-id schedule-id}]
        :return schedule})))

  (register-core-handler!
   :scheduler/cancel-all
   (fn [ctx {:keys [session-id]}]
     (let [{state' :state cancelled-ids :cancelled-ids cancelled-schedules :cancelled-schedules}
           (scheduler/cancel-all-schedules (scheduler-state-in ctx session-id))]
       {:root-state-update (scheduler-update session-id (constantly state'))
        :effects (mapv (fn [schedule-id]
                         {:effect/type :scheduler/cancel-timer
                          :schedule-id schedule-id})
                       cancelled-ids)
        :return {:cancelled-count (count cancelled-ids)
                 :cancelled-schedule-ids cancelled-ids
                 :cancelled-schedules cancelled-schedules}})))

  (register-core-handler!
   :scheduler/fired
   (fn [ctx {:keys [session-id schedule-id]}]
     (let [{state' :state action :action schedule :schedule}
           (scheduler/fire-schedule
            (scheduler-state-in ctx session-id)
            (ss/get-session-data-in ctx session-id)
            schedule-id)]
       (cond-> {:root-state-update (scheduler-update session-id (constantly state'))
                :return schedule}
         (= :deliver action)
         (update :effects (fnil conj [])
                 {:effect/type :runtime/dispatch-event
                  :event-type :scheduler/deliver
                  :event-data {:session-id session-id
                               :schedule-id schedule-id
                               :delivery-phase (when (= :session (:kind schedule)) :create-session)}
                  :origin :core})))))

  (register-core-handler!
   :scheduler/deliver
   (fn [ctx {:keys [session-id schedule-id]}]
     (let [schedule (scheduler/get-schedule (scheduler-state-in ctx session-id) schedule-id)]
       (if (= :session (:kind schedule))
         (try
           (let [session-config   (or (:session-config schedule) {})
                 created         (session-lifecycle/create-top-level-session-in!
                                  ctx
                                  session-id
                                  {:session-name (:session-name session-config)
                                   :worktree-path (ss/session-worktree-path-in ctx session-id)
                                   :scheduled-origin-session-id session-id
                                   :scheduled-from-schedule-id schedule-id
                                   :scheduled-from-label (:label schedule)})
                 created-id      (:session-id created)
                 _               (when-let [system-prompt (:system-prompt session-config)]
                                   (dispatch/dispatch! ctx :session/set-system-prompt {:session-id created-id :prompt system-prompt} {:origin :core}))
                 _               (when-let [thinking-level (:thinking-level session-config)]
                                   (swap! (:state* ctx) update-in [:agent-session :sessions created-id :data]
                                          assoc :thinking-level thinking-level))
                 _               (when-let [model (:model session-config)]
                                   (dispatch/dispatch! ctx :session/set-model {:session-id created-id :model model} {:origin :core}))
                 _               (when-let [cache-breakpoints (:cache-breakpoints session-config)]
                                   (dispatch/dispatch! ctx :session/set-cache-breakpoints {:session-id created-id :breakpoints cache-breakpoints} {:origin :core}))
                 _               (when-let [developer-prompt (:developer-prompt session-config)]
                                   (swap! (:state* ctx) update-in [:agent-session :sessions created-id :data]
                                          assoc :developer-prompt developer-prompt))
                 _               (when-let [developer-prompt-source (:developer-prompt-source session-config)]
                                   (swap! (:state* ctx) update-in [:agent-session :sessions created-id :data]
                                          assoc :developer-prompt-source developer-prompt-source))
                 _               (when-let [skills (:skills session-config)]
                                   (swap! (:state* ctx) update-in [:agent-session :sessions created-id :data]
                                          assoc :skills (vec skills)))
                 _               (when-let [tool-defs (:tool-defs session-config)]
                                   (swap! (:state* ctx) update-in [:agent-session :sessions created-id :data]
                                          assoc :tool-defs (vec tool-defs)))
                 _               (when-let [prompt-component-selection (:prompt-component-selection session-config)]
                                   (swap! (:state* ctx) update-in [:agent-session :sessions created-id :data]
                                          assoc :prompt-component-selection prompt-component-selection))
                 preloaded       (vec (or (:preloaded-messages session-config) []))
                 _               (when (seq preloaded)
                                   (swap! (:state* ctx)
                                          (fn [state]
                                            (-> state
                                                (assoc-in [:agent-session :sessions created-id :persistence :journal]
                                                          (vec (map persist/message-entry preloaded))))))
                                   (when-let [agent-ctx (ss/agent-ctx-in ctx created-id)]
                                     (agent/replace-messages-in! agent-ctx preloaded)))
                 prompt-result   (let [result (dispatch/dispatch! ctx :session/submit-synthetic-user-prompt
                                                                  {:session-id created-id
                                                                   :user-msg (scheduled-user-message schedule)}
                                                                  {:origin :core})]
                                   (when-not (:submitted? result)
                                     (throw (ex-info "scheduled session prompt submission failed"
                                                     {:created-session-id created-id
                                                      :delivery-phase :prompt-submit
                                                      :result result})))
                                   result)
                 {state' :state schedule' :schedule}
                 (scheduler/deliver-schedule (scheduler-state-in ctx session-id) schedule-id)
                 final-schedule  (assoc schedule'
                                   :created-session-id created-id
                                   :delivery-phase :prompt-submit)
                 final-state     (assoc-in state' [:schedules schedule-id] final-schedule)]
             {:root-state-update (scheduler-update session-id (constantly final-state))
              :return {:schedule final-schedule
                       :created-session-id created-id
                       :prompt-result prompt-result}})
           (catch Exception e
             (let [created-session-id (or (some-> e ex-data :created-session-id)
                                          (some-> e ex-data :session-id))
                   delivery-phase    (or (some-> e ex-data :delivery-phase)
                                         (if created-session-id :prompt-submit :create-session))
                   {state' :state failed :schedule}
                   (scheduler/fail-schedule (scheduler-state-in ctx session-id)
                                            schedule-id
                                            {:delivery-phase delivery-phase
                                             :created-session-id created-session-id
                                             :error-summary (scheduler-error-summary e)})]
               {:root-state-update (scheduler-update session-id (constantly state'))
                :return failed})))
         (let [{state' :state schedule' :schedule}
               (scheduler/deliver-schedule (scheduler-state-in ctx session-id) schedule-id)
               user-msg (scheduled-user-message schedule')]
           {:root-state-update (scheduler-update session-id (constantly state'))
            :effects [{:effect/type :runtime/dispatch-event-with-effect-result
                       :event-type :session/submit-synthetic-user-prompt
                       :event-data {:session-id session-id
                                    :user-msg user-msg}
                       :origin :core}]
            :return (assoc schedule' :message-record user-msg)})))))

  (register-core-handler!
   :scheduler/drain-queue
   (fn [ctx {:keys [session-id]}]
     (let [{state' :state drained? :drained? schedule :schedule}
           (scheduler/drain-one
            (scheduler-state-in ctx session-id)
            (ss/get-session-data-in ctx session-id))
           user-msg (when schedule (scheduled-user-message schedule))]
       (cond-> {:root-state-update (scheduler-update session-id (constantly state'))
                :return {:drained? drained?
                         :schedule-id (:schedule-id schedule)}}
         drained?
         (update :effects into [{:effect/type :runtime/dispatch-event-with-effect-result
                                 :event-type :session/submit-synthetic-user-prompt
                                 :event-data {:session-id session-id
                                              :user-msg user-msg}
                                 :origin :core}]))))))
