(ns extensions.workflow-loader-delegate-test
  "Tests for the delegate tool async/sync mode, fork_session, and include_result_in_context."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [extensions.workflow-loader :as wl]))

;;; Test infrastructure — mock extension API

(def ^:private test-state (atom nil))

(defn- make-mock-api
  "Create a mock extension API that captures tool/command registrations
   and provides controllable mutate/query functions."
  [{:keys [definitions mutate-results query-result]}]
  (let [tools (atom {})
        commands (atom {})
        logs (atom [])
        notifications (atom [])
        prompt-contributions (atom {})
        mutate-calls (atom [])
        mutate-results* (atom (or mutate-results {}))]
    (reset! test-state
            {:tools tools
             :commands commands
             :logs logs
             :notifications notifications
             :prompt-contributions prompt-contributions
             :mutate-calls mutate-calls
             :mutate-results* mutate-results*})
    {:query (fn [_query]
              (or query-result
                  {:psi.agent-session/worktree-path "/tmp/test-worktree"
                   :psi.agent-session/session-id "test-session-1"
                   :psi.agent-session/session-entries []}))
     :mutate (fn [sym params]
               (swap! mutate-calls conj {:sym sym :params params})
               (let [results @mutate-results*
                     result (get results sym)]
                 (if (fn? result)
                   (result params)
                   (or result {}))))
     :log (fn [msg] (swap! logs conj msg))
     :notify (fn [msg level] (swap! notifications conj {:msg msg :level level}))
     :register-tool (fn [tool-def]
                      (swap! tools assoc (:name tool-def) tool-def))
     :register-command (fn [name cmd-def]
                         (swap! commands assoc name cmd-def))
     :register-prompt-contribution (fn [{:keys [id content] :as pc}]
                                     (swap! prompt-contributions assoc id pc))
     :on (fn [_event-name _handler] nil)}))

(defn- execute-tool [args]
  (let [tool-def (get @(:tools @test-state) "delegate")]
    (when tool-def
      ((:execute tool-def) args))))

(defn- get-mutate-calls []
  @(:mutate-calls @test-state))

(defn- get-notifications []
  @(:notifications @test-state))

;;; Tests

(deftest delegate-run-async-default-test
  (testing "action=run defaults to async mode and returns immediately"
    (let [run-created (atom false)
          execute-called (atom false)
          api (make-mock-api
               {:definitions {}
                :mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/definition-id "planner"
                          :psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   (reset! run-created true)
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.workflow/execute-run
                 (fn [_]
                   (reset! execute-called true)
                   ;; Simulate some work
                   (Thread/sleep 50)
                   {:psi.workflow/run-id "planner-123"
                    :psi.workflow/status :completed
                    :psi.workflow/result "plan output"})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      ;; Stub the loader to return a known definition
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans tasks"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "planner"
                                    :prompt "plan something"})]
          (is (string? result))
          (is (.contains ^String result "started asynchronously"))
          (is (true? @run-created))
          ;; Wait for async execution to complete
          (Thread/sleep 200)
          (is (true? @execute-called)))))))

(deftest delegate-run-sync-test
  (testing "action=run with mode=sync blocks until completion and returns result"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.workflow/execute-run
                 (fn [_]
                   (Thread/sleep 50)
                   {:psi.workflow/run-id "planner-sync"
                    :psi.workflow/status :completed
                    :psi.workflow/result "sync plan output"})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans tasks"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "planner"
                                    :prompt "plan something"
                                    :mode "sync"})]
          (is (string? result))
          (is (.contains ^String result "completed"))
          (is (.contains ^String result "sync plan output")))))))

(deftest delegate-run-sync-timeout-test
  (testing "sync mode returns timeout error when execution exceeds timeout_ms"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.workflow/execute-run
                 (fn [_]
                   ;; Simulate slow execution
                   (Thread/sleep 5000)
                   {:psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"slow" {:definition-id "slow"
                                             :name "slow"
                                             :summary "Slow workflow"
                                             :step-order ["step-1"]
                                             :steps {"step-1" {:label "slow"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "slow"
                                    :prompt "do something"
                                    :mode "sync"
                                    :timeout_ms 100})]
          (is (string? result))
          (is (.contains ^String result "Error"))
          (is (.contains ^String result "Timed out")))))))

(deftest delegate-run-include-result-test
  (testing "include_result_in_context injects messages after async completion"
    (let [appended-messages (atom [])
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.workflow/execute-run
                 (fn [_]
                   {:psi.workflow/status :completed
                    :psi.workflow/result "injected output"})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})
                 'psi.extension/append-message
                 (fn [params]
                   (swap! appended-messages conj params)
                   {})
                 'psi.extension/append-entry
                 (fn [_] {})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "planner"
                                    :prompt "plan it"
                                    :include_result_in_context true})]
          (is (.contains ^String result "started asynchronously"))
          ;; Wait for async completion
          (Thread/sleep 200)
          ;; Should have injected user + assistant messages
          (let [msgs @appended-messages
                roles (mapv :role msgs)]
            (is (>= (count msgs) 2) "should inject at least user + assistant")
            (is (some #(= "user" %) roles))
            (is (some #(= "assistant" %) roles))
            (is (some #(= "injected output" (:content %)) msgs))))))))

(deftest delegate-run-fork-session-test
  (testing "fork_session passes through in workflow-input"
    (let [create-params (atom nil)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   (reset! create-params params)
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.workflow/execute-run
                 (fn [_]
                   {:psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (execute-tool {:action "run"
                       :workflow "planner"
                       :prompt "plan it"
                       :fork_session true})
        ;; Wait for async
        (Thread/sleep 100)
        (is (some? @create-params))
        (is (true? (get-in @create-params [:workflow-input :fork-session])))))))

(deftest delegate-run-unknown-workflow-test
  (testing "run with unknown workflow returns error"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "run"
                                    :workflow "nonexistent"
                                    :prompt "do something"})]
          (is (.contains ^String result "Error"))
          (is (.contains ^String result "Unknown workflow")))))))

(deftest delegate-run-missing-params-test
  (testing "run without workflow or prompt returns appropriate errors"
    (let [api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (is (.contains ^String (execute-tool {:action "run" :prompt "x"})
                       "workflow is required"))
        (is (.contains ^String (execute-tool {:action "run" :workflow "planner"})
                       "prompt is required"))))))

(deftest delegate-list-shows-async-tag-test
  (testing "list action shows [async] tag for tracked runs"
    (let [created-run-id (atom nil)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/create-run
                 (fn [params]
                   (reset! created-run-id (:run-id params))
                   {:psi.workflow/run-id (:run-id params)
                    :psi.workflow/status :pending})
                 'psi.workflow/execute-run
                 (fn [_]
                   (Thread/sleep 5000)
                   {:psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_]
                   ;; Return the actual run-id that was created
                   {:psi.workflow/runs (if @created-run-id
                                         [{:run-id @created-run-id
                                           :status :running
                                           :source-definition-id "planner"}]
                                         [])})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {"planner" {:definition-id "planner"
                                                :name "planner"
                                                :summary "Plans"
                                                :step-order ["step-1"]
                                                :steps {"step-1" {:label "planner"}}}}
                       :errors []
                       :warnings []})]
        (wl/init api)
        ;; Start an async run with explicit name to populate active-runs
        (execute-tool {:action "run"
                       :workflow "planner"
                       :prompt "plan it"
                       :name "my-plan-run"})
        ;; Now list — should show [async] tag
        (let [list-result (execute-tool {:action "list"})]
          (is (string? list-result))
          (is (.contains ^String list-result "planner"))
          (is (.contains ^String list-result "[async]")))))))

(deftest delegate-continue-test
  (testing "continue resumes a blocked run asynchronously"
    (let [resume-called (atom false)
          api (make-mock-api
               {:mutate-results
                {'psi.workflow/register-definition
                 (fn [_] {:psi.workflow/registered? true})
                 'psi.workflow/resume-run
                 (fn [_]
                   (reset! resume-called true)
                   {:psi.workflow/run-id "run-1"
                    :psi.workflow/status :completed})
                 'psi.workflow/list-runs
                 (fn [_] {:psi.workflow/runs []})
                 'psi.extension/append-entry
                 (fn [_] {})}})]
      (with-redefs [psi.agent-session.workflow-file-loader/load-workflow-definitions
                    (fn [_]
                      {:definitions {}
                       :errors []
                       :warnings []})]
        (wl/init api)
        (let [result (execute-tool {:action "continue"
                                    :id "run-1"
                                    :prompt "continue with this"})]
          (is (.contains ^String result "Resuming"))
          ;; Wait for async
          (Thread/sleep 200)
          (is (true? @resume-called)))))))
