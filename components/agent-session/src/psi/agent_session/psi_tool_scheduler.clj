(ns psi.agent-session.psi-tool-scheduler
  "Scheduler action handler for psi-tool: parse, validate, summarise, and execute scheduler ops."
  (:require
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.session-state :as ss]))

(def ^:private scheduler-min-delay-ms 1000)
(def ^:private scheduler-max-delay-ms (* 24 60 60 1000))
(def ^:private scheduler-max-pending-per-session 50)

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

(defn parse-absolute-instant
  [at]
  (try
    (java.time.Instant/parse at)
    (catch Exception e
      (throw (ex-info "psi-tool scheduler `at` must be an ISO-8601 UTC instant"
                      {:phase :validate :action "scheduler" :op "create" :at at}
                      e)))))

(defn normalize-fire-at
  [{:keys [delay-ms at]}]
  (let [now (java.time.Instant/now)]
    (cond
      (some? delay-ms)
      (do
        (when-not (integer? delay-ms)
          (throw (ex-info "psi-tool scheduler `delay-ms` must be an integer"
                          {:phase :validate :action "scheduler" :op "create" :delay-ms delay-ms})))
        (when (< delay-ms scheduler-min-delay-ms)
          (throw (ex-info "psi-tool scheduler delay-ms must be at least 1000ms"
                          {:phase :validate :action "scheduler" :op "create"
                           :delay-ms delay-ms
                           :min-delay-ms scheduler-min-delay-ms})))
        (when (> delay-ms scheduler-max-delay-ms)
          (throw (ex-info "psi-tool scheduler delay-ms must be at most 24h"
                          {:phase :validate :action "scheduler" :op "create"
                           :delay-ms delay-ms
                           :max-delay-ms scheduler-max-delay-ms})))
        (.plusMillis now delay-ms))

      (some? at)
      (let [instant  (parse-absolute-instant at)
            delta-ms (.toMillis (java.time.Duration/between now instant))]
        (when (> delta-ms scheduler-max-delay-ms)
          (throw (ex-info "psi-tool scheduler absolute time must be within 24h"
                          {:phase :validate :action "scheduler" :op "create"
                           :at at
                           :max-delay-ms scheduler-max-delay-ms})))
        (if (neg? delta-ms) now instant))

      :else
      (throw (ex-info "psi-tool scheduler create requires `delay-ms` or `at`"
                      {:phase :validate :action "scheduler" :op "create"})))))

(defn- scheduler-records
  [ctx session-id]
  (vals (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules] {})))

(defn pending-schedule-count
  [ctx session-id]
  (->> (scheduler-records ctx session-id)
       (filter #(contains? #{:pending :queued} (:status %)))
       count))

(defn- ensure-create-capacity!
  [ctx session-id]
  (let [count (pending-schedule-count ctx session-id)]
    (when (>= count scheduler-max-pending-per-session)
      (throw (ex-info "psi-tool scheduler pending schedule cap reached"
                      {:phase :validate :action "scheduler" :op "create"
                       :pending-count count
                       :max-pending scheduler-max-pending-per-session}))))
  true)

(defn scheduler-summary
  [schedule]
  {:schedule-id (:schedule-id schedule)
   :label (:label schedule)
   :message (:message schedule)
   :status (:status schedule)
   :created-at (:created-at schedule)
   :fire-at (:fire-at schedule)
   :session-id (:session-id schedule)
   :source (:source schedule)})

(defn execute-psi-tool-scheduler-report
  [{:keys [ctx session-id]} {:keys [op message label delay-ms at schedule-id]}]
  (let [started-at (System/nanoTime)]
    (try
      (when-not ctx
        (throw (ex-info "psi-tool scheduler action requires live runtime ctx"
                        {:phase :validate :action "scheduler" :op op})))
      (let [session-id (resolve-session-id ctx session-id)
            result     (case op
                         "create"
                         (do
                           (when-not (string? message)
                             (throw (ex-info "psi-tool scheduler create requires `message`"
                                             {:phase :validate :action "scheduler" :op op})))
                           (when (and delay-ms at)
                             (throw (ex-info "psi-tool scheduler create accepts either `delay-ms` or `at`, not both"
                                             {:phase :validate :action "scheduler" :op op})))
                           (ensure-create-capacity! ctx session-id)
                           (let [fire-at      (normalize-fire-at {:delay-ms delay-ms :at at})
                                 schedule-id  (str "sch-" (java.util.UUID/randomUUID))
                                 schedule     (dispatch/dispatch! ctx
                                                                  :scheduler/create
                                                                  {:session-id session-id
                                                                   :schedule-id schedule-id
                                                                   :label label
                                                                   :message message
                                                                   :fire-at fire-at}
                                                                  {:origin :core})]
                             {:psi-tool/action         :scheduler
                              :psi-tool/scheduler-op   :create
                              :psi-tool/overall-status :ok
                              :psi-tool/scheduler      {:schedule (scheduler-summary schedule)}}))

                         "list"
                         (let [schedules (->> (scheduler-records ctx session-id)
                                              (filter #(contains? #{:pending :queued} (:status %)))
                                              (sort-by (juxt :fire-at :created-at :schedule-id))
                                              (mapv scheduler-summary))]
                           {:psi-tool/action         :scheduler
                            :psi-tool/scheduler-op   :list
                            :psi-tool/overall-status :ok
                            :psi-tool/scheduler      {:schedule-count (count schedules)
                                                      :schedules schedules}})

                         "cancel"
                         (do
                           (when-not (string? schedule-id)
                             (throw (ex-info "psi-tool scheduler cancel requires `schedule-id`"
                                             {:phase :validate :action "scheduler" :op op})))
                           (let [cancel-result (dispatch/dispatch! ctx
                                                                   :scheduler/cancel
                                                                   {:session-id session-id
                                                                    :schedule-id schedule-id}
                                                                   {:origin :core})]
                             {:psi-tool/action         :scheduler
                              :psi-tool/scheduler-op   :cancel
                              :psi-tool/overall-status (if (:cancelled? cancel-result) :ok :error)
                              :psi-tool/scheduler      cancel-result})))]
        (assoc result :psi-tool/duration-ms (long (/ (- (System/nanoTime) started-at) 1000000))))
      (catch Exception e
        {:psi-tool/action         :scheduler
         :psi-tool/scheduler-op   (some-> op keyword)
         :psi-tool/duration-ms    (long (/ (- (System/nanoTime) started-at) 1000000))
         :psi-tool/overall-status :error
         :psi-tool/error          (psi-tool-error-summary :scheduler e)}))))
