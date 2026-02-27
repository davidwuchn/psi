(ns extensions.mcp-tasks-run-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [extensions.mcp-tasks-run :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(deftest derive-task-state-test
  (testing "derive-task-state"
    (is (= :terminated
           (sut/derive-task-state {:status :closed})))
    (is (= :complete
           (sut/derive-task-state {:status :open :pr-num 12 :pr-merged? true})))
    (is (= :wait-pr-merge
           (sut/derive-task-state {:status :open :pr-num 12})))
    (is (= :awaiting-pr
           (sut/derive-task-state {:status :done :code-reviewed "2026-01-01"})))
    (is (= :done
           (sut/derive-task-state {:status :done})))
    (is (= :refined
           (sut/derive-task-state {:status :open :meta {:refined "true"}})))
    (is (= :unrefined
           (sut/derive-task-state {:status :open :meta {:x 1}})))))

(deftest derive-story-state-test
  (testing "derive-story-state"
    (is (= :terminated
           (sut/derive-story-state {:status :closed} [])))
    (is (= :complete
           (sut/derive-story-state {:status :open :pr-num 10 :pr-merged? true}
                                   [{:status :closed}])))
    (is (= :has-tasks
           (sut/derive-story-state {:status :open :meta {:refined "true"}}
                                   [{:status :open} {:status :closed}])))
    (is (= :wait-pr-merge
           (sut/derive-story-state {:status :open :pr-num 10 :meta {:refined "true"}}
                                   [{:status :closed}])))
    (is (= :awaiting-pr
           (sut/derive-story-state {:status :open
                                    :meta {:refined "true"}
                                    :code-reviewed "2026-01-01"}
                                   [{:status :closed} {:status :done}])))
    (is (= :done
           (sut/derive-story-state {:status :open :meta {:refined "true"}}
                                   [{:status :closed} {:status :done}])))
    (is (= :refined
           (sut/derive-story-state {:status :open :meta {:refined "true"}} [])))
    (is (= :unrefined
           (sut/derive-story-state {:status :open} [])))))

(deftest init-registers-surface-test
  (testing "init registers workflow type, command, and lifecycle handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (is (contains? (:workflow-types @state) :mcp-tasks-run))
      (is (contains? (:commands @state) "mcp-tasks-run"))
      (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
      (is (= "mcp-tasks-run loaded"
             (-> @state :notifications last :text))))))

(deftest command-basic-behavior-test
  (testing "command prints usage and can start/list runs"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/mcp_tasks_run.clj"})]
      (sut/init api)
      (let [handler (get-in @state [:commands "mcp-tasks-run" :handler])]
        (is (string? (with-out-str (handler ""))))

        (let [out-start (with-out-str (handler "42"))]
          (is (re-find #"Started mcp-tasks run run-1" out-start))
          (is (contains? (:workflows @state) "run-1")))

        (let [out-list (with-out-str (handler "list"))]
          (is (re-find #"run-1" out-list)))))))
