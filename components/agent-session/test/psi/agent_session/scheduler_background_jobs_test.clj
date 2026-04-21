(ns psi.agent-session.scheduler-background-jobs-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.background-job-runtime :as bg-rt]
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

(deftest scheduler-background-job-projection-test
  (testing "pending and queued schedules project into background jobs"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-1"
                                                :label "check-build"
                                                :message "check build"
                                                :fire-at (java.time.Instant/parse "2026-04-21T18:00:00Z")}
                                               {:origin :core})
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-2"
                                                :label "review"
                                                :message "review"
                                                :fire-at (java.time.Instant/parse "2026-04-21T19:00:00Z")}
                                               {:origin :core})
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :schedules "sch-2" :status] :queued)
          _                (swap! (:state* ctx) assoc-in [:agent-session :sessions session-id :data :scheduler :queue] ["sch-2"])
          jobs             (bg-rt/list-background-jobs-in! ctx session-id [:pending :queued])]
      (is (= 2 (count jobs)))
      (is (= #{{:job-id "sch-1" :status :pending :job-kind :scheduled-prompt :tool-name "scheduler"}
               {:job-id "sch-2" :status :queued :job-kind :scheduled-prompt :tool-name "scheduler"}}
             (set (map #(select-keys % [:job-id :status :job-kind :tool-name]) jobs))))))

  (testing "scheduler-projected background job cancel routes to scheduler cancel"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _                (dispatch/dispatch! ctx :scheduler/create
                                               {:session-id session-id
                                                :schedule-id "sch-1"
                                                :label "check-build"
                                                :message "check build"
                                                :fire-at (java.time.Instant/parse "2026-04-21T18:00:00Z")}
                                               {:origin :core})
          cancelled        (bg-rt/cancel-background-job-in! ctx session-id "sch-1" :user)]
      (is (= :cancelled (:status cancelled)))
      (is (= :cancelled (get-in @(:state* ctx) [:agent-session :sessions session-id :data :scheduler :schedules "sch-1" :status]))))))
