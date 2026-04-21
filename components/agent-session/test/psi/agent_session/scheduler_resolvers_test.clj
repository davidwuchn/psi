(ns psi.agent-session.scheduler-resolvers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.test-support :as test-support]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest scheduler-resolver-test
  (testing "scheduler attrs resolve from session root and entity-seeded schedule id"
    (let [[ctx session-id] (create-session-context {:persist? false})
          created         (dispatch/dispatch! ctx :scheduler/create
                                             {:session-id session-id
                                              :schedule-id "sch-1"
                                              :label "check-build"
                                              :message "check build"
                                              :fire-at (java.time.Instant/parse "2099-04-21T18:00:00Z")}
                                             {:origin :core})
          root-result     (session/query-in ctx session-id
                                            [:psi.scheduler/pending-count
                                             {:psi.scheduler/schedules
                                              [:psi.scheduler/schedule-id
                                               :psi.scheduler/label
                                               :psi.scheduler/status]}])
          detail-result   (session/query-in ctx session-id
                                            [:psi.scheduler/message
                                             :psi.scheduler/fire-at
                                             :psi.scheduler/status]
                                            {:psi.scheduler/schedule-id "sch-1"})]
      (is (= "sch-1" (:schedule-id created)))
      (is (= 1 (:psi.scheduler/pending-count root-result)))
      (is (= [{:psi.scheduler/schedule-id "sch-1"
               :psi.scheduler/label "check-build"
               :psi.scheduler/status :pending}]
             (:psi.scheduler/schedules root-result)))
      (is (= "check build" (:psi.scheduler/message detail-result)))
      (is (= :pending (:psi.scheduler/status detail-result))))))
