(ns psi.agent-session.scheduler
  "Pure scheduler projections and helper functions over canonical session scheduler state."
  (:require
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.session-state :as ss]))

(defn schedules-in
  [ctx session-id]
  (->> (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules] {})
       vals
       (sort-by (juxt :fire-at :created-at :schedule-id))
       vec))

(defn schedule-in
  [ctx session-id schedule-id]
  (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules schedule-id]))

(defn pending-schedules-in
  [ctx session-id]
  (->> (schedules-in ctx session-id)
       (filter #(contains? #{:pending :queued} (:status %)))
       vec))

(defn pending-count-in
  [ctx session-id]
  (count (pending-schedules-in ctx session-id)))

(defn schedule->background-job
  [schedule]
  {:job-id                   (:schedule-id schedule)
   :thread-id                (:session-id schedule)
   :tool-call-id             nil
   :tool-name                "scheduler"
   :job-kind                 :scheduled-prompt
   :workflow-ext-path        nil
   :workflow-id              nil
   :job-seq                  nil
   :started-at               (:created-at schedule)
   :completed-at             nil
   :completed-seq            nil
   :status                   (:status schedule)
   :terminal-payload         {:schedule-id (:schedule-id schedule)
                              :label (:label schedule)
                              :message (:message schedule)
                              :fire-at (:fire-at schedule)}
   :terminal-payload-file    nil
   :cancel-requested-at      nil
   :terminal-message-emitted false
   :terminal-message-emitted-at nil})

(defn scheduled-background-jobs-in
  [ctx session-id]
  (->> (pending-schedules-in ctx session-id)
       (mapv schedule->background-job)))

(def schedule-eql-attrs
  [:psi.scheduler/schedule-id
   :psi.scheduler/label
   :psi.scheduler/message
   :psi.scheduler/source
   :psi.scheduler/created-at
   :psi.scheduler/fire-at
   :psi.scheduler/status
   :psi.scheduler/session-id])

(defn schedule->eql
  [schedule]
  {:psi.scheduler/schedule-id (:schedule-id schedule)
   :psi.scheduler/label       (:label schedule)
   :psi.scheduler/message     (:message schedule)
   :psi.scheduler/source      (:source schedule)
   :psi.scheduler/created-at  (:created-at schedule)
   :psi.scheduler/fire-at     (:fire-at schedule)
   :psi.scheduler/status      (:status schedule)
   :psi.scheduler/session-id  (:session-id schedule)})
