(ns psi.agent-session.scheduler
  "Pure session-scoped scheduler state model for one-shot delayed prompt injection."
  (:require
   [clojure.string :as str]))

(def schedule-statuses
  #{:pending :queued :delivered :cancelled})

(def non-terminal-statuses
  #{:pending :queued})

(def terminal-statuses
  #{:delivered :cancelled})

(def max-delay-ms
  (* 24 60 60 1000))

(def min-delay-ms
  1000)

(def default-max-pending-per-session
  50)

(defn empty-state []
  {:schedules {}
   :queue []})

(defn schedule-count
  [scheduler-state]
  (count (:schedules (or scheduler-state {}))))

(defn pending-count
  [scheduler-state]
  (->> (vals (:schedules (or scheduler-state {})))
       (filter #(contains? non-terminal-statuses (:status %)))
       count))

(defn get-schedule
  [scheduler-state schedule-id]
  (get-in scheduler-state [:schedules schedule-id]))

(defn list-schedules
  ([scheduler-state]
   (list-schedules scheduler-state [:pending :queued]))
  ([scheduler-state statuses]
   (let [status-set (set statuses)]
     (->> (vals (:schedules (or scheduler-state {})))
          (filter #(contains? status-set (:status %)))
          (sort-by (juxt :fire-at :created-at :schedule-id))
          vec))))

(defn idle-session?
  [session-data]
  (and (not (:is-streaming session-data))
       (not (:is-compacting session-data))))

(defn schedule-summary
  [schedule]
  {:schedule-id (:schedule-id schedule)
   :label (:label schedule)
   :message (:message schedule)
   :created-at (:created-at schedule)
   :fire-at (:fire-at schedule)
   :status (:status schedule)
   :session-id (:session-id schedule)
   :source (:source schedule)})

(defn validate-delay-ms!
  [delay-ms]
  (when-not (int? delay-ms)
    (throw (ex-info "delay-ms must be an integer"
                    {:delay-ms delay-ms})))
  (when (< delay-ms min-delay-ms)
    (throw (ex-info "delay-ms is below the minimum bound"
                    {:delay-ms delay-ms
                     :minimum min-delay-ms})))
  (when (> delay-ms max-delay-ms)
    (throw (ex-info "delay-ms exceeds the maximum bound"
                    {:delay-ms delay-ms
                     :maximum max-delay-ms})))
  delay-ms)

(defn validate-schedule-record!
  [{:keys [schedule-id message created-at fire-at status session-id source] :as schedule}]
  (when (str/blank? (str schedule-id))
    (throw (ex-info "schedule-id is required" {:schedule schedule})))
  (when (str/blank? (str message))
    (throw (ex-info "message is required" {:schedule schedule})))
  (when-not (instance? java.time.Instant created-at)
    (throw (ex-info "created-at must be an Instant" {:schedule schedule})))
  (when-not (instance? java.time.Instant fire-at)
    (throw (ex-info "fire-at must be an Instant" {:schedule schedule})))
  (when-not (contains? schedule-statuses status)
    (throw (ex-info "status is invalid" {:schedule schedule :status status})))
  (when (str/blank? (str session-id))
    (throw (ex-info "session-id is required" {:schedule schedule})))
  (when-not (= :scheduled source)
    (throw (ex-info "source must be :scheduled" {:schedule schedule :source source})))
  schedule)

(defn create-schedule
  [scheduler-state {:keys [schedule-id label message created-at fire-at session-id]
                    :or {label nil}}]
  (let [state (or scheduler-state (empty-state))
        _     (validate-schedule-record!
               {:schedule-id schedule-id
                :label label
                :message message
                :created-at created-at
                :fire-at fire-at
                :status :pending
                :session-id session-id
                :source :scheduled})]
    (when (get-schedule state schedule-id)
      (throw (ex-info "schedule-id already exists" {:schedule-id schedule-id})))
    (let [schedule {:schedule-id schedule-id
                    :label label
                    :message message
                    :created-at created-at
                    :fire-at fire-at
                    :status :pending
                    :session-id session-id
                    :source :scheduled}]
      {:state    (assoc-in state [:schedules schedule-id] schedule)
       :schedule schedule})))

(defn cancel-schedule
  [scheduler-state schedule-id]
  (let [state    (or scheduler-state (empty-state))
        schedule (get-schedule state schedule-id)]
    (when-not schedule
      (throw (ex-info "schedule not found" {:schedule-id schedule-id})))
    (when-not (contains? non-terminal-statuses (:status schedule))
      (throw (ex-info "schedule is not cancellable"
                      {:schedule-id schedule-id
                       :status (:status schedule)})))
    {:state    (-> state
                   (assoc-in [:schedules schedule-id :status] :cancelled)
                   (update :queue (fn [q] (vec (remove #{schedule-id} (or q []))))))
     :schedule (assoc schedule :status :cancelled)}))

(defn cancel-all-schedules
  [scheduler-state]
  (let [state         (or scheduler-state (empty-state))
        cancellable   (->> (vals (:schedules state))
                           (filter #(contains? non-terminal-statuses (:status %)))
                           (sort-by (juxt :fire-at :created-at :schedule-id))
                           vec)
        schedule-ids  (mapv :schedule-id cancellable)
        cancelled-set (set schedule-ids)]
    {:state (-> state
                (update :schedules
                        (fn [schedules]
                          (into {}
                                (map (fn [[schedule-id schedule]]
                                       [schedule-id
                                        (if (contains? cancelled-set schedule-id)
                                          (assoc schedule :status :cancelled)
                                          schedule)]))
                                (or schedules {}))))
                (assoc :queue []))
     :cancelled-schedules (mapv #(assoc % :status :cancelled) cancellable)
     :cancelled-ids schedule-ids}))

(defn fire-schedule
  [scheduler-state session-data schedule-id]
  (let [state    (or scheduler-state (empty-state))
        schedule (get-schedule state schedule-id)]
    (when-not schedule
      (throw (ex-info "schedule not found" {:schedule-id schedule-id})))
    (when-not (= :pending (:status schedule))
      (throw (ex-info "only pending schedules can fire"
                      {:schedule-id schedule-id
                       :status (:status schedule)})))
    (if (idle-session? session-data)
      {:state state
       :action :deliver
       :schedule schedule}
      {:state    (-> state
                     (assoc-in [:schedules schedule-id :status] :queued)
                     (update :queue (fnil conj []) schedule-id))
       :action   :queue
       :schedule (assoc schedule :status :queued)})))

(defn next-queued-schedule-id
  [scheduler-state]
  (first (:queue (or scheduler-state {}))))

(defn deliver-schedule
  [scheduler-state schedule-id]
  (let [state    (or scheduler-state (empty-state))
        schedule (get-schedule state schedule-id)]
    (when-not schedule
      (throw (ex-info "schedule not found" {:schedule-id schedule-id})))
    (when-not (contains? #{:pending :queued} (:status schedule))
      (throw (ex-info "schedule is not deliverable"
                      {:schedule-id schedule-id
                       :status (:status schedule)})))
    {:state    (-> state
                   (assoc-in [:schedules schedule-id :status] :delivered)
                   (update :queue (fn [q] (vec (remove #{schedule-id} (or q []))))))
     :schedule (assoc schedule :status :delivered)}))

(defn drain-one
  [scheduler-state session-data]
  (let [state       (or scheduler-state (empty-state))
        schedule-id (next-queued-schedule-id state)]
    (cond
      (not (idle-session? session-data))
      {:state state :drained? false :reason :session-busy}

      (nil? schedule-id)
      {:state state :drained? false :reason :empty}

      :else
      (let [{state' :state schedule :schedule} (deliver-schedule state schedule-id)]
        {:state state' :drained? true :schedule schedule}))))
