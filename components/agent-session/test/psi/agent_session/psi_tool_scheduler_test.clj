(ns psi.agent-session.psi-tool-scheduler-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.psi-tool-scheduler :as psi-tool-scheduler]
   [psi.agent-session.scheduler :as scheduler]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts (assoc opts :persist? false)))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest psi-tool-scheduler-create-list-cancel-test
  (let [[ctx session-id] (create-session-context)
        tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
    (testing "create adds a pending schedule"
      (let [result ((:execute tool) {"action" "scheduler"
                                     "op" "create"
                                     "message" "wake later"
                                     "delay-ms" 1000
                                     "label" "wake-test"})
            parsed (read-string (:content result))
            schedule (get-in parsed [:psi-tool/scheduler :schedule])]
        (is (false? (:is-error result)))
        (is (= :scheduler (:psi-tool/action parsed)))
        (is (= :create (:psi-tool/scheduler-op parsed)))
        (is (= :ok (:psi-tool/overall-status parsed)))
        (is (= :pending (:status schedule)))
        (is (= "wake-test" (:label schedule)))
        (is (string? (:schedule-id schedule)))))

    (testing "list returns pending schedules"
      (let [result ((:execute tool) {"action" "scheduler" "op" "list"})
            parsed (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= :list (:psi-tool/scheduler-op parsed)))
        (is (= 1 (get-in parsed [:psi-tool/scheduler :schedule-count])))
        (is (= :pending (get-in parsed [:psi-tool/scheduler :schedules 0 :status])))))

    (testing "cancel marks schedule cancelled"
      (let [schedule-id (-> ((:execute tool) {"action" "scheduler" "op" "list"})
                            :content read-string
                            (get-in [:psi-tool/scheduler :schedules 0 :schedule-id]))
            result ((:execute tool) {"action" "scheduler" "op" "cancel" "schedule-id" schedule-id})
            parsed (read-string (:content result))]
        (is (false? (:is-error result)))
        (is (= :cancel (:psi-tool/scheduler-op parsed)))
        (is (= :cancelled (get-in parsed [:psi-tool/scheduler :schedule :status]))))))

  (testing "past absolute instant fires immediately as zero-delay pending schedule creation"
    (let [[ctx session-id] (create-session-context)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "message" "wake now"
                                   "at" "2020-01-01T00:00:00Z"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (string? (get-in parsed [:psi-tool/scheduler :schedule :schedule-id])))))

  (testing "bounds rejection surfaces as scheduler error"
    (let [[ctx session-id] (create-session-context)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "create"
                                   "message" "too fast"
                                   "delay-ms" 10})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))))

  (testing "cap rejection blocks the 51st pending schedule"
    (let [[ctx session-id] (create-session-context)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})]
      (dotimes [i scheduler/default-max-pending-per-session]
        (let [result ((:execute tool) {"action" "scheduler"
                                       "op" "create"
                                       "message" (str "m-" i)
                                       "delay-ms" 1000})]
          (is (false? (:is-error result)))))
      (let [result ((:execute tool) {"action" "scheduler"
                                     "op" "create"
                                     "message" "overflow"
                                     "delay-ms" 1000})
            parsed (read-string (:content result))]
        (is (true? (:is-error result)))
        (is (= :error (:psi-tool/overall-status parsed))))))

  (testing "scheduler requires invoking or explicit session-id"
    (let [[ctx _session-id] (create-session-context)
          tool (tools/make-psi-tool (fn [_q] {}) {:ctx ctx})
          result ((:execute tool) {"action" "scheduler"
                                   "op" "list"})
          parsed (read-string (:content result))]
      (is (true? (:is-error result)))
      (is (= :error (:psi-tool/overall-status parsed)))
      (is (= :validate (get-in parsed [:psi-tool/error :phase])))))

  (testing "explicit session-id is used when provided directly to scheduler report"
    (let [[ctx session-id] (create-session-context)
          report (psi-tool-scheduler/execute-psi-tool-scheduler-report
                  {:ctx ctx :session-id session-id}
                  {:op "create"
                   :message "wake later"
                   :delay-ms 1000})]
      (is (= :ok (:psi-tool/overall-status report)))
      (is (= :pending (get-in report [:psi-tool/scheduler :schedule :status]))))))
