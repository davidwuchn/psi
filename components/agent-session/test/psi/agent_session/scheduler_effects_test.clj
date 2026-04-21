(ns psi.agent-session.scheduler-effects-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch-effects :as dispatch-effects]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts (assoc opts :persist? false)))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest scheduler-start-and-cancel-timer-effects-test
  (dispatch-effects/cancel-all-scheduler-timers!)
  (testing "start-timer dispatches scheduler/fired after delay and removes handle"
    (let [[ctx session-id] (create-session-context)
          fired (promise)]
      (with-redefs [psi.agent-session.dispatch/dispatch!
                    (fn [_ctx event-type event-data _opts]
                      (when (= :scheduler/fired event-type)
                        (deliver fired event-data))
                      nil)]
        (is (= 0 (dispatch-effects/scheduler-timer-handle-count)))
        (dispatch-effects/execute-effect! ctx {:effect/type :scheduler/start-timer
                                               :session-id session-id
                                               :schedule-id "sch-1"
                                               :delay-ms 20})
        (is (= {:session-id session-id :schedule-id "sch-1"}
               (deref fired 1000 ::timeout)))
        (loop [i 0]
          (when (and (< i 20) (not= 0 (dispatch-effects/scheduler-timer-handle-count)))
            (Thread/sleep 10)
            (recur (inc i))))
        (is (= 0 (dispatch-effects/scheduler-timer-handle-count))))))

  (testing "cancel-timer interrupts and removes handle"
    (let [[ctx session-id] (create-session-context)]
      (with-redefs [psi.agent-session.dispatch/dispatch!
                    (fn [_ctx _event-type _event-data _opts]
                      (throw (ex-info "should not fire" {})))]
        (dispatch-effects/execute-effect! ctx {:effect/type :scheduler/start-timer
                                               :session-id session-id
                                               :schedule-id "sch-2"
                                               :delay-ms 200})
        (is (= 1 (dispatch-effects/scheduler-timer-handle-count)))
        (is (= {:schedule-id "sch-2" :cancelled? true}
               (dispatch-effects/execute-effect! ctx {:effect/type :scheduler/cancel-timer
                                                      :schedule-id "sch-2"})))
        (Thread/sleep 30)
        (is (= 0 (dispatch-effects/scheduler-timer-handle-count)))))))

(deftest shutdown-context-cancels-scheduler-timers-test
  (dispatch-effects/cancel-all-scheduler-timers!)
  (let [[ctx session-id] (create-session-context)]
    (dispatch-effects/execute-effect! ctx {:effect/type :scheduler/start-timer
                                           :session-id session-id
                                           :schedule-id "sch-3"
                                           :delay-ms 500})
    (is (= 1 (dispatch-effects/scheduler-timer-handle-count)))
    (session/shutdown-context! ctx)
    (is (= 0 (dispatch-effects/scheduler-timer-handle-count)))))
