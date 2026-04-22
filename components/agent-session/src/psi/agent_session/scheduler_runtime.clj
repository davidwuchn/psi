(ns psi.agent-session.scheduler-runtime
  "Projection helpers from scheduler state into public/query/UI surfaces."
  (:require
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.session-state :as ss]))

(def scheduler-eql-attrs
  [:psi.scheduler/schedule-id
   :psi.scheduler/label
   :psi.scheduler/message
   :psi.scheduler/source
   :psi.scheduler/created-at
   :psi.scheduler/fire-at
   :psi.scheduler/status
   :psi.scheduler/session-id])

(defn scheduler-state-in
  [ctx session-id]
  (or (ss/get-state-value-in ctx (ss/state-path :scheduler session-id))
      (scheduler/empty-state)))

(defn scheduler-schedules-in
  ([ctx session-id]
   (scheduler-schedules-in ctx session-id [:pending :queued :delivered :cancelled]))
  ([ctx session-id statuses]
   (scheduler/list-schedules (scheduler-state-in ctx session-id) statuses)))

(defn schedule->eql
  [schedule]
  {:psi.scheduler/schedule-id (:schedule-id schedule)
   :psi.scheduler/label (:label schedule)
   :psi.scheduler/message (:message schedule)
   :psi.scheduler/source (:source schedule)
   :psi.scheduler/created-at (:created-at schedule)
   :psi.scheduler/fire-at (:fire-at schedule)
   :psi.scheduler/status (:status schedule)
   :psi.scheduler/session-id (:session-id schedule)})

(defn eql->schedule
  [schedule]
  {:schedule-id (:psi.scheduler/schedule-id schedule)
   :label (:psi.scheduler/label schedule)
   :message (:psi.scheduler/message schedule)
   :source (:psi.scheduler/source schedule)
   :created-at (:psi.scheduler/created-at schedule)
   :fire-at (:psi.scheduler/fire-at schedule)
   :status (:psi.scheduler/status schedule)
   :session-id (:psi.scheduler/session-id schedule)})

(defn schedule->psi-tool-summary
  [schedule]
  {:schedule-id (:schedule-id schedule)
   :label (:label schedule)
   :message (:message schedule)
   :status (:status schedule)
   :created-at (:created-at schedule)
   :fire-at (:fire-at schedule)})

(defn scheduler-list->psi-tool-summary
  [schedules]
  {:schedule-count (count schedules)
   :schedules (mapv schedule->psi-tool-summary schedules)})

(defn schedule->background-job
  [session-id schedule]
  {:job-id (str "schedule/" (:schedule-id schedule))
   :thread-id session-id
   :tool-call-id nil
   :tool-name (or (:label schedule) "scheduler")
   :job-kind :scheduled-prompt
   :workflow-ext-path nil
   :workflow-id nil
   :job-seq nil
   :started-at (:created-at schedule)
   :completed-at nil
   :completed-seq nil
   :status (case (:status schedule)
             :pending :running
             :queued :running
             :delivered :completed
             :cancelled :cancelled
             :running)
   :scheduler-status (:status schedule)
   :terminal-payload nil
   :terminal-payload-file nil
   :cancel-requested-at nil
   :terminal-message-emitted false
   :terminal-message-emitted-at nil
   :schedule-id (:schedule-id schedule)
   :fire-at (:fire-at schedule)
   :message (:message schedule)
   :label (:label schedule)})

(defn scheduler-jobs-in
  [ctx session-id]
  (mapv (partial schedule->background-job session-id)
        (scheduler-schedules-in ctx session-id [:pending :queued :delivered :cancelled])))
