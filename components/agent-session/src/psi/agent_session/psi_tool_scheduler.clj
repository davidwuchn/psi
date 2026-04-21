(ns psi.agent-session.psi-tool-scheduler
  "Scheduler action handler for psi-tool: parse, validate, and execute scheduler ops."
  (:require
   [clojure.string :as str]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.session-state :as ss]))

(defn- psi-tool-error-summary
  ([e] (psi-tool-error-summary nil e))
  ([default-phase e]
   {:message (or (ex-message e) (str e))
    :class   (.getName (class e))
    :phase   (or (:phase (ex-data e)) default-phase :execute)
    :data    (ex-data e)}))

(defn- resolve-session-id
  [ctx session-id]
  (or session-id
      (some->> (ss/list-context-sessions-in ctx) first :session-id)))

(defn- scheduler-state-in
  [ctx session-id]
  (or (ss/get-state-value-in ctx (ss/state-path :scheduler-state session-id))
      (scheduler/empty-state)))

(defn- parse-utc-instant!
  [s]
  (try
    (java.time.Instant/parse s)
    (catch Exception e
      (throw (ex-info "scheduler `at` must be an ISO-8601 UTC instant"
                      {:phase :validate :action "scheduler" :field :at :input s}
                      e)))))

(defn- now [] (java.time.Instant/now))

(defn- millis-until
  [target now*]
  (max 0 (.toMillis (java.time.Duration/between now* target))))

(defn- create-summary
  [schedule]
  {:schedule-id (:schedule-id schedule)
   :label (:label schedule)
   :message (:message schedule)
   :status (:status schedule)
   :created-at (:created-at schedule)
   :fire-at (:fire-at schedule)})

(defn- list-summary
  [state]
  (let [schedules (scheduler/list-schedules state [:pending :queued])]
    {:schedule-count (count schedules)
     :schedules (mapv create-summary schedules)}))

(defn- resolve-fire-time!
  [{:keys [delay-ms at]}]
  (cond
    (and delay-ms at)
    (throw (ex-info "scheduler create accepts either `delay-ms` or `at`, not both"
                    {:phase :validate :action "scheduler" :op "create"}))

    delay-ms
    (let [validated (scheduler/validate-delay-ms! delay-ms)
          created-at (now)]
      {:created-at created-at
       :delay-ms validated
       :fire-at (.plusMillis created-at validated)})

    at
    (let [created-at (now)
          fire-at (parse-utc-instant! at)
          delay (millis-until fire-at created-at)]
      (when (pos? delay)
        (scheduler/validate-delay-ms! (int delay)))
      {:created-at created-at
       :delay-ms (int delay)
       :fire-at fire-at})

    :else
    (throw (ex-info "scheduler create requires `delay-ms` or `at`"
                    {:phase :validate :action "scheduler" :op "create"}))))

(defn execute-psi-tool-scheduler-report
  [{:keys [ctx session-id]} {:keys [op delay-ms at label message schedule-id]}]
  (let [started-at (System/nanoTime)]
    (try
      (when-not ctx
        (throw (ex-info "psi-tool scheduler action requires live runtime ctx"
                        {:phase :validate :action "scheduler" :op op})))
      (let [session-id (resolve-session-id ctx session-id)]
        (when-not session-id
          (throw (ex-info "psi-tool scheduler action requires a target session"
                          {:phase :validate :action "scheduler" :op op})))
        (let [result
              (case op
                "create"
                (do
                  (when (str/blank? (str message))
                    (throw (ex-info "scheduler create requires `message`"
                                    {:phase :validate :action "scheduler" :op op})))
                  (let [state (scheduler-state-in ctx session-id)]
                    (when (>= (scheduler/pending-count state) scheduler/default-max-pending-per-session)
                      (throw (ex-info "scheduler pending cap exceeded"
                                      {:phase :validate
                                       :action "scheduler"
                                       :op op
                                       :cap scheduler/default-max-pending-per-session})))
                    (let [{:keys [created-at fire-at delay-ms]} (resolve-fire-time! {:delay-ms delay-ms :at at})
                          schedule-id (str "sch-" (java.util.UUID/randomUUID))
                          schedule (dispatch/dispatch! ctx
                                                      :scheduler/create
                                                      {:session-id session-id
                                                       :schedule-id schedule-id
                                                       :label label
                                                       :message message
                                                       :created-at created-at
                                                       :fire-at fire-at
                                                       :delay-ms delay-ms}
                                                      {:origin :core})]
                      {:psi-tool/action :scheduler
                       :psi-tool/scheduler-op :create
                       :psi-tool/overall-status :ok
                       :psi-tool/scheduler {:schedule (create-summary schedule)}})))

                "list"
                (let [state (scheduler-state-in ctx session-id)]
                  {:psi-tool/action :scheduler
                   :psi-tool/scheduler-op :list
                   :psi-tool/overall-status :ok
                   :psi-tool/scheduler (list-summary state)})

                "cancel"
                (do
                  (when-not (string? schedule-id)
                    (throw (ex-info "scheduler cancel requires `schedule-id`"
                                    {:phase :validate :action "scheduler" :op op})))
                  (let [schedule (dispatch/dispatch! ctx
                                                    :scheduler/cancel
                                                    {:session-id session-id
                                                     :schedule-id schedule-id}
                                                    {:origin :core})]
                    {:psi-tool/action :scheduler
                     :psi-tool/scheduler-op :cancel
                     :psi-tool/overall-status :ok
                     :psi-tool/scheduler {:schedule (create-summary schedule)}})))]
          (assoc result :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000)))))
      (catch Exception e
        {:psi-tool/action :scheduler
         :psi-tool/scheduler-op (some-> op keyword)
         :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))
         :psi-tool/overall-status :error
         :psi-tool/error (psi-tool-error-summary :scheduler e)}))))
