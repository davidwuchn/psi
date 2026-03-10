(ns psi.agent-session.background-jobs
  "In-memory background job tracking for tool-initiated async work."
  (:require
   [clojure.string :as str]))

(def non-terminal-statuses
  #{:running :pending-cancel})

(def terminal-statuses
  #{:completed :failed :cancelled :timed-out})

(def default-list-statuses
  [:running :pending-cancel])

(def default-terminal-history-max-per-thread
  20)

(defn empty-state []
  {:jobs-by-id {}
   :tool-call->job-id {}
   :tool-call->mode {}
   :next-job-seq 0
   :next-terminal-seq 0})

(defn create-store []
  (atom (empty-state)))

(defn non-terminal-status?
  [status]
  (contains? non-terminal-statuses status))

(defn terminal-status?
  [status]
  (contains? terminal-statuses status))

(defn- now
  []
  (java.time.Instant/now))

(defn- ensure-thread-id!
  [thread-id]
  (when (str/blank? (str thread-id))
    (throw (ex-info "thread-id is required" {:thread-id thread-id}))))

(defn- ensure-tool-call-id!
  [tool-call-id]
  (when (str/blank? (str tool-call-id))
    (throw (ex-info "tool-call-id is required" {:tool-call-id tool-call-id}))))

(defn- ensure-tool-name!
  [tool-name]
  (when (str/blank? (str tool-name))
    (throw (ex-info "tool-name is required" {:tool-name tool-name}))))

(defn record-synchronous-result-in!
  "Record synchronous (non-background) completion for a tool invocation.
   Enforces exclusive dual-mode selection per tool-call-id."
  [store-atom {:keys [tool-call-id thread-id tool-name result]}]
  (ensure-tool-call-id! tool-call-id)
  (ensure-thread-id! thread-id)
  (ensure-tool-name! tool-name)
  (swap! store-atom
         (fn [{:keys [tool-call->mode] :as state}]
           (when (contains? tool-call->mode tool-call-id)
             (throw (ex-info "Tool invocation already resolved"
                             {:tool-call-id tool-call-id
                              :mode (get tool-call->mode tool-call-id)})))
           (assoc-in state [:tool-call->mode tool-call-id] :synchronous)))
  {:mode :synchronous
   :job-id nil
   :status nil
   :result result})

(defn start-background-job-in!
  "Create a running background job for one tool invocation.
   Enforces one background job per tool-call-id and global job-id uniqueness.
   Enforces exclusive dual-mode selection per tool-call-id."
  [store-atom {:keys [tool-call-id thread-id tool-name job-id
                      job-kind workflow-ext-path workflow-id]}]
  (ensure-tool-call-id! tool-call-id)
  (ensure-thread-id! thread-id)
  (ensure-tool-name! tool-name)
  (let [job-id-out (atom nil)]
    (swap! store-atom
           (fn [{:keys [jobs-by-id tool-call->job-id tool-call->mode next-job-seq] :as state}]
             (when (contains? tool-call->mode tool-call-id)
               (throw (ex-info "Tool invocation already resolved"
                               {:tool-call-id tool-call-id
                                :mode (get tool-call->mode tool-call-id)})))
             (when (contains? tool-call->job-id tool-call-id)
               (throw (ex-info "Background job already exists for tool_call_id"
                               {:tool-call-id tool-call-id
                                :existing-job-id (get tool-call->job-id tool-call-id)})))
             (let [resolved-job-id (or job-id (str (java.util.UUID/randomUUID)))]
               (when (contains? jobs-by-id resolved-job-id)
                 (throw (ex-info "Duplicate job-id"
                                 {:job-id resolved-job-id})))
               (let [job-seq (inc (long (or next-job-seq 0)))
                     _ (reset! job-id-out resolved-job-id)
                     job {:job-id resolved-job-id
                          :thread-id (str thread-id)
                          :tool-call-id (str tool-call-id)
                          :tool-name (str tool-name)
                          :job-kind (or job-kind :generic)
                          :workflow-ext-path workflow-ext-path
                          :workflow-id workflow-id
                          :job-seq job-seq
                          :started-at (now)
                          :completed-at nil
                          :completed-seq nil
                          :status :running
                          :terminal-payload nil
                          :terminal-payload-file nil
                          :cancel-requested-at nil
                          :terminal-message-emitted false
                          :terminal-message-emitted-at nil}]
                 (-> state
                     (assoc-in [:jobs-by-id resolved-job-id] job)
                     (assoc-in [:tool-call->job-id tool-call-id] resolved-job-id)
                     (assoc-in [:tool-call->mode tool-call-id] :background)
                     (assoc :next-job-seq job-seq))))))
    {:mode :background
     :job-id @job-id-out
     :status :running
     :result nil}))

(defn get-job-in
  [store-atom job-id]
  (get-in @store-atom [:jobs-by-id job-id]))

(defn find-job-by-workflow-in
  [store-atom {:keys [workflow-ext-path workflow-id]}]
  (some (fn [job]
          (when (and (= workflow-ext-path (:workflow-ext-path job))
                     (= (some-> workflow-id str) (:workflow-id job)))
            job))
        (vals (:jobs-by-id @store-atom))))

(defn request-cancel-in!
  [store-atom {:keys [thread-id job-id]}]
  (ensure-thread-id! thread-id)
  (swap! store-atom
         (fn [state]
           (let [job (get-in state [:jobs-by-id job-id])]
             (when-not job
               (throw (ex-info "Job not found" {:job-id job-id})))
             (when-not (= thread-id (:thread-id job))
               (throw (ex-info "Job not found in this thread"
                               {:job-id job-id :thread-id thread-id})))
             (if (= :running (:status job))
               (-> state
                   (assoc-in [:jobs-by-id job-id :status] :pending-cancel)
                   (assoc-in [:jobs-by-id job-id :cancel-requested-at] (java.time.Instant/now)))
               state))))
  (get-job-in store-atom job-id))

(defn- completion-order-key
  [job]
  [(:completed-at job)
   (or (:completed-seq job) Long/MAX_VALUE)
   (or (:job-seq job) Long/MAX_VALUE)
   (:job-id job)])

(defn- enforce-terminal-retention*
  [state thread-id terminal-history-max-per-thread]
  (let [terminal-jobs (->> (vals (:jobs-by-id state))
                           (filter #(= thread-id (:thread-id %)))
                           (filter #(terminal-status? (:status %)))
                           (sort-by completion-order-key)
                           vec)
        overflow      (- (count terminal-jobs) terminal-history-max-per-thread)]
    (if (pos? overflow)
      (let [evict-ids      (->> terminal-jobs (take overflow) (map :job-id) set)
            evict-tool-calls (->> (:tool-call->job-id state)
                                  (keep (fn [[tc jid]]
                                          (when (contains? evict-ids jid)
                                            tc)))
                                  set)]
        (-> state
            (update :jobs-by-id (fn [m] (apply dissoc m evict-ids)))
            (update :tool-call->job-id
                    (fn [m]
                      (into {}
                            (remove (fn [[_tc jid]]
                                      (contains? evict-ids jid))
                                    m))))
            (update :tool-call->mode
                    (fn [m]
                      (apply dissoc m evict-tool-calls)))))
      state)))

(defn mark-terminal-in!
  [store-atom {:keys [job-id outcome payload terminal-history-max-per-thread]
               :or {terminal-history-max-per-thread default-terminal-history-max-per-thread}}]
  (when-not (terminal-status? outcome)
    (throw (ex-info "Terminal outcome required"
                    {:outcome outcome
                     :allowed terminal-statuses})))
  (swap! store-atom
         (fn [{:keys [next-terminal-seq] :as state}]
           (let [job (get-in state [:jobs-by-id job-id])]
             (when-not job
               (throw (ex-info "Job not found" {:job-id job-id})))
             (let [completed-seq (inc (long (or next-terminal-seq 0)))]
               (-> state
                   (assoc :next-terminal-seq completed-seq)
                   (assoc-in [:jobs-by-id job-id :status] outcome)
                   (assoc-in [:jobs-by-id job-id :completed-at] (now))
                   (assoc-in [:jobs-by-id job-id :completed-seq] completed-seq)
                   (assoc-in [:jobs-by-id job-id :terminal-payload] payload)
                   (enforce-terminal-retention* (:thread-id job) terminal-history-max-per-thread))))))
  (get-job-in store-atom job-id))

(defn list-jobs-in
  ([store-atom thread-id]
   (list-jobs-in store-atom thread-id default-list-statuses))
  ([store-atom thread-id statuses]
   (ensure-thread-id! thread-id)
   (let [status-set (set statuses)]
     (->> (vals (:jobs-by-id @store-atom))
          (filter #(= thread-id (:thread-id %)))
          (filter #(contains? status-set (:status %)))
          (sort-by (fn [job] [(:started-at job) (or (:job-seq job) Long/MAX_VALUE) (:job-id job)]))
          vec))))

(defn inspect-job-in
  [store-atom {:keys [thread-id job-id]}]
  (ensure-thread-id! thread-id)
  (let [job (get-in @store-atom [:jobs-by-id job-id])]
    (when-not job
      (throw (ex-info "Job not found" {:job-id job-id})))
    (when-not (= thread-id (:thread-id job))
      (throw (ex-info "Job not found in this thread"
                      {:job-id job-id :thread-id thread-id})))
    job))

(defn pending-terminal-jobs-in
  "Return terminal jobs for thread-id that have not yet emitted synthetic messages,
   ordered by completion time with deterministic tie-breakers." 
  [store-atom thread-id]
  (ensure-thread-id! thread-id)
  (->> (vals (:jobs-by-id @store-atom))
       (filter #(= thread-id (:thread-id %)))
       (filter #(terminal-status? (:status %)))
       (filter #(not (:terminal-message-emitted %)))
       (sort-by completion-order-key)
       vec))

(defn set-terminal-payload-file-in!
  [store-atom {:keys [job-id path]}]
  (swap! store-atom
         (fn [state]
           (let [job (get-in state [:jobs-by-id job-id])]
             (when-not job
               (throw (ex-info "Job not found" {:job-id job-id})))
             (assoc-in state [:jobs-by-id job-id :terminal-payload-file] path))))
  (get-job-in store-atom job-id))

(defn claim-terminal-message-emission-in!
  "Atomically claim terminal message emission for a job.
   Returns true when this call claimed emission, false when already claimed."
  [store-atom {:keys [job-id]}]
  (loop []
    (let [state @store-atom
          job   (get-in state [:jobs-by-id job-id])]
      (when-not job
        (throw (ex-info "Job not found" {:job-id job-id})))
      (if (:terminal-message-emitted job)
        false
        (let [next-state (-> state
                             (assoc-in [:jobs-by-id job-id :terminal-message-emitted] true)
                             (assoc-in [:jobs-by-id job-id :terminal-message-emitted-at] (now)))]
          (if (compare-and-set! store-atom state next-state)
            true
            (recur)))))))

(defn mark-terminal-message-emitted-in!
  [store-atom {:keys [job-id]}]
  (claim-terminal-message-emission-in! store-atom {:job-id job-id})
  (get-job-in store-atom job-id))

(defn retry-job-in!
  "Manual retry is intentionally unsupported for background jobs."
  [_store-atom {:keys [thread-id job-id]}]
  (throw (ex-info "Manual retry is not supported for background jobs"
                  {:thread-id thread-id
                   :job-id job-id})))

(defn process-restarted-in!
  "Reset in-memory job tracking as if process restarted." 
  [store-atom]
  (reset! store-atom (empty-state))
  {:reinitialized? true})
