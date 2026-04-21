(ns psi.agent-session.scheduler-handlers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.dispatch-handlers.scheduler :as scheduler-handlers]
   [psi.agent-session.dispatch-handlers.statechart-actions :as statechart-actions]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]))

(defn- invoke-handler
  [ctx event-type data]
  (let [handler-fn (get-in (dispatch/handler-entry event-type) [:fn])]
    (handler-fn ctx data)))

(defn- apply-root-state-update!
  [ctx result]
  (when-let [f (:root-state-update result)]
    (swap! (:state* ctx) f))
  result)

(defn- instant
  [s]
  (java.time.Instant/parse s))

(defn- with-registered-handlers
  [ctx f]
  (dispatch/clear-handlers!)
  (try
    (scheduler-handlers/register! ctx)
    (statechart-actions/register! ctx)
    (f)
    (finally
      (dispatch/clear-handlers!))))

(deftest scheduler-create-cancel-fire-deliver-handlers-test
  (let [[ctx session-id] (test-support/make-session-ctx {})]
    (with-registered-handlers
      ctx
      #(do
         (testing "create stores schedule and emits timer effect"
           (let [result (invoke-handler ctx :scheduler/create {:session-id session-id
                                                               :schedule-id "sch-1"
                                                               :label "check-build"
                                                               :message "check build"
                                                               :created-at (instant "2026-04-21T18:00:00Z")
                                                               :fire-at (instant "2026-04-21T18:05:00Z")
                                                               :delay-ms 5000})]
             (apply-root-state-update! ctx result)
             (is (= :pending (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :status])))
             (is (= [{:effect/type :scheduler/start-timer
                      :schedule-id "sch-1"
                      :delay-ms 5000}]
                    (:effects result)))))

         (testing "fired queues when session is busy"
           (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming true))))
           (let [result (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-1"})]
             (apply-root-state-update! ctx result)
             (is (= :queued (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :status])))
             (is (= ["sch-1"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
             (is (nil? (:effects result)))))

         (testing "cancel removes queued schedule from queue and emits cancel effect"
           (let [result (invoke-handler ctx :scheduler/cancel {:session-id session-id :schedule-id "sch-1"})]
             (apply-root-state-update! ctx result)
             (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-1" :status])))
             (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
             (is (= [{:effect/type :scheduler/cancel-timer
                      :schedule-id "sch-1"}]
                    (:effects result)))))

         (testing "cancel-all cancels all non-terminal schedules and emits one timer-cancel effect per schedule"
           (let [create-a (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-bulk-a"
                                                                 :message "a"
                                                                 :created-at (instant "2026-04-21T18:20:00Z")
                                                                 :fire-at (instant "2026-04-21T18:21:00Z")
                                                                 :delay-ms 1000})
                 _ (apply-root-state-update! ctx create-a)
                 create-b (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-bulk-b"
                                                                 :message "b"
                                                                 :created-at (instant "2026-04-21T18:20:01Z")
                                                                 :fire-at (instant "2026-04-21T18:21:01Z")
                                                                 :delay-ms 1000})
                 _ (apply-root-state-update! ctx create-b)
                 bulk-r (invoke-handler ctx :scheduler/cancel-all {:session-id session-id})]
             (apply-root-state-update! ctx bulk-r)
             (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-bulk-a" :status])))
             (is (= :cancelled (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-bulk-b" :status])))
             (is (= [] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
             (is (= 2 (count (:effects bulk-r))))
             (is (= #{"sch-bulk-a" "sch-bulk-b"}
                    (set (map :schedule-id (:effects bulk-r)))))))

         (testing "deliver marks delivered and routes through canonical prompt lifecycle"
           (let [create-r (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-2"
                                                                 :message "wake up"
                                                                 :created-at (instant "2026-04-21T18:10:00Z")
                                                                 :fire-at (instant "2026-04-21T18:11:00Z")
                                                                 :delay-ms 1000})]
             (apply-root-state-update! ctx create-r)
             (let [result (invoke-handler ctx :scheduler/deliver {:session-id session-id :schedule-id "sch-2"})]
               (apply-root-state-update! ctx result)
               (is (= :delivered (get-in (ss/get-session-data-in ctx session-id) [:scheduler :schedules "sch-2" :status])))
               (is (= 1 (count (:effects result))))
               (is (= :runtime/dispatch-event-with-effect-result (-> result :effects first :effect/type)))
               (is (= :session/submit-synthetic-user-prompt (-> result :effects first :event-type))))))))))

(deftest scheduler-drain-and-statechart-idle-hooks-test
  (let [[ctx session-id] (test-support/make-session-ctx {:session-data {:is-streaming true}})]
    (with-registered-handlers
      ctx
      #(do
         (let [create-a (invoke-handler ctx :scheduler/create {:session-id session-id
                                                               :schedule-id "sch-a"
                                                               :message "a"
                                                               :created-at (instant "2026-04-21T18:00:00Z")
                                                               :fire-at (instant "2026-04-21T18:05:00Z")
                                                               :delay-ms 1000})]
           (apply-root-state-update! ctx create-a)
           (let [create-b (invoke-handler ctx :scheduler/create {:session-id session-id
                                                                 :schedule-id "sch-b"
                                                                 :message "b"
                                                                 :created-at (instant "2026-04-21T18:00:01Z")
                                                                 :fire-at (instant "2026-04-21T18:05:01Z")
                                                                 :delay-ms 1000})]
             (apply-root-state-update! ctx create-b))
           (apply-root-state-update! ctx (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-a"}))
           (apply-root-state-update! ctx (invoke-handler ctx :scheduler/fired {:session-id session-id :schedule-id "sch-b"}))
           (swap! (:state* ctx) (ss/session-update session-id (fn [session] (assoc session :is-streaming false))))

           (testing "drain-queue delivers one queued schedule when idle"
             (let [result (invoke-handler ctx :scheduler/drain-queue {:session-id session-id})]
               (apply-root-state-update! ctx result)
               (is (= ["sch-b"] (get-in (ss/get-session-data-in ctx session-id) [:scheduler :queue])))
               (is (= "sch-a" (get-in result [:return :schedule-id])))
               (is (= 1 (count (:effects result))))
               (is (= :runtime/dispatch-event-with-effect-result (-> result :effects first :effect/type)))
               (is (= :session/submit-synthetic-user-prompt (-> result :effects first :event-type)))))

           (testing "idle transitions emit scheduler drain effects"
             (let [done-r (invoke-handler ctx :on-agent-done {:session-id session-id})
                   abort-r (invoke-handler ctx :on-abort {:session-id session-id})
                   compact-r (invoke-handler ctx :on-compact-done {:session-id session-id})]
               (is (some (fn [effect] (= :scheduler/drain-queue (:event-type effect))) (:effects done-r)))
               (is (some (fn [effect] (= :scheduler/drain-queue (:event-type effect))) (:effects abort-r)))
               (is (some (fn [effect] (= :scheduler/drain-queue (:event-type effect))) (:effects compact-r))))))))))
