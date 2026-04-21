(ns psi.agent-session.scheduler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.scheduler :as scheduler]))

(defn- instant
  [s]
  (java.time.Instant/parse s))

(deftest empty-state-test
  (is (= {:schedules {} :queue []}
         (scheduler/empty-state))))

(deftest create-and-list-schedule-test
  (let [{state :state schedule :schedule}
        (scheduler/create-schedule
         (scheduler/empty-state)
         {:schedule-id "sch-1"
          :label "check-build"
          :message "check build status"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})]
    (is (= "sch-1" (:schedule-id schedule)))
    (is (= :pending (:status schedule)))
    (is (= 1 (scheduler/schedule-count state)))
    (is (= 1 (scheduler/pending-count state)))
    (is (= ["sch-1"] (mapv :schedule-id (scheduler/list-schedules state [:pending]))))))

(deftest validate-delay-ms-test
  (testing "accepts inclusive bounds"
    (is (= scheduler/min-delay-ms (scheduler/validate-delay-ms! scheduler/min-delay-ms)))
    (is (= scheduler/max-delay-ms (scheduler/validate-delay-ms! scheduler/max-delay-ms))))

  (testing "rejects too small"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"minimum"
                          (scheduler/validate-delay-ms! 999))))

  (testing "rejects too large"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"maximum"
                          (scheduler/validate-delay-ms! (inc scheduler/max-delay-ms))))))

(deftest fire-schedule-test
  (let [{state :state}
        (scheduler/create-schedule
         nil
         {:schedule-id "sch-1"
          :message "wake up"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})]
    (testing "idle session delivers immediately"
      (let [{state' :state action :action schedule :schedule}
            (scheduler/fire-schedule state {:is-streaming false :is-compacting false} "sch-1")]
        (is (= :deliver action))
        (is (= :pending (:status schedule)))
        (is (= [] (:queue state')))))

    (testing "busy session queues schedule"
      (let [{state' :state action :action schedule :schedule}
            (scheduler/fire-schedule state {:is-streaming true :is-compacting false} "sch-1")]
        (is (= :queue action))
        (is (= :queued (:status schedule)))
        (is (= ["sch-1"] (:queue state')))
        (is (= :queued (:status (scheduler/get-schedule state' "sch-1"))))))))

(deftest deliver-and-cancel-test
  (let [{state0 :state}
        (scheduler/create-schedule
         nil
         {:schedule-id "sch-1"
          :message "wake up"
          :created-at (instant "2026-04-21T18:00:00Z")
          :fire-at (instant "2026-04-21T18:05:00Z")
          :session-id "sid-1"})
        state1 (:state (scheduler/fire-schedule state0 {:is-streaming true :is-compacting false} "sch-1"))]
    (testing "deliver moves queued schedule to delivered and removes it from queue"
      (let [{state2 :state schedule :schedule} (scheduler/deliver-schedule state1 "sch-1")]
        (is (= :delivered (:status schedule)))
        (is (= [] (:queue state2)))
        (is (= :delivered (:status (scheduler/get-schedule state2 "sch-1"))))))

    (testing "cancel marks queued schedule cancelled and removes it from queue"
      (let [{state2 :state schedule :schedule} (scheduler/cancel-schedule state1 "sch-1")]
        (is (= :cancelled (:status schedule)))
        (is (= [] (:queue state2)))
        (is (= :cancelled (:status (scheduler/get-schedule state2 "sch-1"))))))))

(deftest drain-one-test
  (let [{state0 :state} (scheduler/create-schedule nil {:schedule-id "sch-a"
                                                        :message "a"
                                                        :created-at (instant "2026-04-21T18:00:00Z")
                                                        :fire-at (instant "2026-04-21T18:05:00Z")
                                                        :session-id "sid-1"})
        {state1 :state} (scheduler/create-schedule state0 {:schedule-id "sch-b"
                                                           :message "b"
                                                           :created-at (instant "2026-04-21T18:00:01Z")
                                                           :fire-at (instant "2026-04-21T18:05:01Z")
                                                           :session-id "sid-1"})
        state2 (:state (scheduler/fire-schedule state1 {:is-streaming true :is-compacting false} "sch-a"))
        state3 (:state (scheduler/fire-schedule state2 {:is-streaming true :is-compacting false} "sch-b"))]
    (testing "drain-one is FIFO by queue order when session is idle"
      (let [{state4 :state drained? :drained? schedule :schedule} (scheduler/drain-one state3 {:is-streaming false :is-compacting false})]
        (is (true? drained?))
        (is (= "sch-a" (:schedule-id schedule)))
        (is (= ["sch-b"] (:queue state4)))
        (is (= :delivered (:status (scheduler/get-schedule state4 "sch-a"))))))

    (testing "drain-one is a no-op when session is busy"
      (let [{state4 :state drained? :drained? reason :reason} (scheduler/drain-one state3 {:is-streaming true :is-compacting false})]
        (is (false? drained?))
        (is (= :session-busy reason))
        (is (= (:queue state3) (:queue state4)))))))
