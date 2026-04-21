(ns psi.agent-session.dispatch-handlers.scheduler
  "Dispatch handlers for session-scoped scheduled prompt injection."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.scheduler :as scheduler]
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

(defn- scheduled-user-message
  [{:keys [schedule-id label message]}]
  {:role "user"
   :content [{:type :text :text message}]
   :timestamp (java.time.Instant/now)
   :source :scheduled
   :schedule-id schedule-id
   :label label})


(defn register!
  [_ctx]
  (register-core-handler!
   :scheduler/create
   (fn [ctx {:keys [session-id schedule-id label message created-at fire-at]}]
     (let [created-at (or created-at (java.time.Instant/now))
           {state' :state schedule :schedule}
           (scheduler/create-schedule
            (scheduler-state-in ctx session-id)
            {:schedule-id schedule-id
             :label label
             :message message
             :created-at created-at
             :fire-at fire-at
             :session-id session-id})]
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
                               :schedule-id schedule-id}
                  :origin :core})))))

  (register-core-handler!
   :scheduler/deliver
   (fn [ctx {:keys [session-id schedule-id]}]
     (let [{state' :state schedule :schedule}
           (scheduler/deliver-schedule (scheduler-state-in ctx session-id) schedule-id)
           user-msg (scheduled-user-message schedule)]
       {:root-state-update (scheduler-update session-id (constantly state'))
        :effects [{:effect/type :runtime/dispatch-event-with-effect-result
                   :event-type :session/submit-synthetic-user-prompt
                   :event-data {:session-id session-id
                                :user-msg user-msg}
                   :origin :core}]
        :return (assoc schedule :message-record user-msg)})))

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
