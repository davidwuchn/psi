(ns psi.agent-session.resolvers.scheduler
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.agent-session.scheduler :as scheduler]))

(pco/defresolver agent-session-scheduler
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id]
   ::pco/output [:psi.scheduler/pending-count
                 :psi.scheduler/schedules
                 {:psi.scheduler/schedules
                  [:psi.scheduler/schedule-id
                   :psi.scheduler/label
                   :psi.scheduler/message
                   :psi.scheduler/source
                   :psi.scheduler/created-at
                   :psi.scheduler/fire-at
                   :psi.scheduler/status
                   :psi.scheduler/session-id]}]}
  (let [schedules (mapv scheduler/schedule->eql
                        (scheduler/schedules-in agent-session-ctx session-id))]
    {:psi.scheduler/pending-count (scheduler/pending-count-in agent-session-ctx session-id)
     :psi.scheduler/schedules     schedules}))

(pco/defresolver scheduler-by-id
  [{:keys [psi/agent-session-ctx psi.agent-session/session-id psi.scheduler/schedule-id]}]
  {::pco/input  [:psi/agent-session-ctx :psi.agent-session/session-id :psi.scheduler/schedule-id]
   ::pco/output [:psi.scheduler/label
                 :psi.scheduler/message
                 :psi.scheduler/source
                 :psi.scheduler/created-at
                 :psi.scheduler/fire-at
                 :psi.scheduler/status
                 :psi.scheduler/session-id]}
  (some-> (scheduler/schedule-in agent-session-ctx session-id schedule-id)
          scheduler/schedule->eql))

(def resolvers
  [agent-session-scheduler
   scheduler-by-id])
