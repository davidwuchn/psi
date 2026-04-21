(ns psi.agent-session.session-close-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.session-state :as ss]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest close-session-cancels-owned-schedules-and-removes-session-test
  (testing "close-session-in! cancels pending/queued schedules, clears timers, and removes the session"
    (let [[ctx session-id] (create-session-context {:persist? false})
          sibling          (session/new-session-in! ctx session-id {:session-name "sibling"})
          sibling-id       (:session-id sibling)
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-pending"
                                                :label "pending"
                                                :message "pending"
                                                :fire-at (.plusSeconds (java.time.Instant/now) 60)}
                                               {:origin :core})
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-queued"
                                                :label "queued"
                                                :message "queued"
                                                :fire-at (.plusSeconds (java.time.Instant/now) 60)}
                                               {:origin :core})
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :schedules "sch-queued" :status] :queued)
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :queue] ["sch-queued"])
          result           (session/close-session-in! ctx session-id)]
      (is (true? (:closed? result)))
      (is (= session-id (:session-id result)))
      (is (= sibling-id (:active-session-id result)))
      (is (nil? (ss/get-session-data-in ctx session-id)))
      (is (some? (ss/get-session-data-in ctx sibling-id)))
      (is (= {} @(:scheduler-timers* ctx)))))

  (testing "close-session-in! closes the last remaining session and leaves no active session"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-1"
                                                :label "pending"
                                                :message "pending"
                                                :fire-at (.plusSeconds (java.time.Instant/now) 60)}
                                               {:origin :core})
          result           (session/close-session-in! ctx session-id)]
      (is (true? (:closed? result)))
      (is (nil? (:active-session-id result)))
      (is (= [] (ss/list-context-sessions-in ctx)))
      (is (= {} @(:scheduler-timers* ctx))))))
