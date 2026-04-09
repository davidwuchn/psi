(ns psi.agent-session.runtime-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.runtime :as runtime]
   [psi.agent-session.test-support :as test-support]
   [psi.recursion.core :as recursion]))

(def ^:private sync-payload
  {:activation {:query-env-built? true
                :memory-status :ready}
   :capability-graph {:status :stable
                      :node-count 3
                      :capability-ids [:cap/a :cap/b]}
   :capture {:changed? true}
   :history-sync {:imported-count 1
                  :memory-entry-count 42}})

(defn- success-runner
  [_ai-ctx _ctx _session-id _agent-ctx _ai-model _user-messages _opts]
  {:role "assistant"
   :content []})

(deftest run-agent-loop-sync-on-git-head-change-triggers-recursion-hook-test
  (let [recursion-ctx        (recursion/create-context)
        maybe-sync-calls     (atom [])
        orchestration-calls  (atom [])
        [ctx* session-id]             (test-support/make-session-ctx
                                       {:agent-ctx {}
                                        :session-data {:worktree-path "/tmp/psi-runtime-hook-test"
                                                       :session-id "runtime-test-session"}})
        ctx                  (assoc ctx*
                                    :cwd "/tmp/psi-runtime-hook-test"
                                    :memory-ctx nil
                                    :recursion-ctx recursion-ctx
                                    :extension-registry {:state (atom {:registration-order []
                                                                       :extensions {}})})]
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [ctx session-id]
                    (swap! maybe-sync-calls conj {:ctx ctx :session-id session-id})
                    (let [git-sync {:ok? true
                                    :changed? true
                                    :reason :head-changed
                                    :head "sha-2"
                                    :previous-head "sha-1"
                                    :classification {:kind :commit-created
                                                     :notify-extensions? true}
                                    :sync sync-payload}]
                      (#'runtime/maybe-trigger-recursion-from-git-sync! ctx git-sync)
                      (#'runtime/maybe-dispatch-git-head-changed-event! ctx session-id git-sync)
                      git-sync))
                  recursion/orchestrate-manual-trigger-in!
                  (fn [rctx trigger opts]
                    (swap! orchestration-calls conj {:rctx rctx
                                                     :trigger trigger
                                                     :opts opts})
                    {:ok? true
                     :phase :completed
                     :trigger-result {:result :accepted}})]
      (let [result (runtime/run-agent-loop-in!
                    ctx session-id nil {} []
                    {:run-loop-fn success-runner
                     :sync-on-git-head-change? true})
            call   (first @orchestration-calls)]
        (testing "agent loop still returns normal assistant result"
          (is (= "assistant" (:role result))))

        (testing "git-head hook is called once"
          (is (= 1 (count @maybe-sync-calls)))
          (is (= "runtime-test-session" (:session-id (first @maybe-sync-calls)))))

        (testing "changed head triggers recursion orchestration"
          (is (= 1 (count @orchestration-calls)))
          (is (= recursion-ctx (:rctx call)))
          (is (= :graph-changed (get-in call [:trigger :type])))
          (is (= "git-head-changed" (get-in call [:trigger :reason])))
          (is (= :approve (get-in call [:opts :approval-decision])))
          (is (true? (get-in call [:opts :system-state :memory-ready])))
          (is (= :stable (get-in call [:opts :graph-state :status]))))))))

(deftest run-agent-loop-without-sync-flag-skips-git-head-hook-test
  (let [calls   (atom 0)
        [ctx* session-id] (test-support/make-session-ctx
                           {:agent-ctx {}
                            :session-data {:worktree-path "/tmp/psi-runtime-no-sync-flag"
                                           :session-id "runtime-no-sync"}})
        ctx     (assoc ctx*
                       :cwd "/tmp/psi-runtime-no-sync-flag"
                       :recursion-ctx (recursion/create-context)
                       :extension-registry {:state (atom {:registration-order []
                                                          :extensions {}})})]
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [_ _]
                    (swap! calls inc)
                    {:ok? true :changed? true})]
      (runtime/run-agent-loop-in!
       ctx session-id nil {} []
       {:run-loop-fn success-runner})
      (is (= 0 @calls)))))

(deftest run-agent-loop-sync-flag-with-unchanged-head-skips-recursion-trigger-test
  (let [orchestration-calls (atom 0)
        extension-events    (atom [])
        [ctx* session-id]            (test-support/make-session-ctx
                                      {:agent-ctx {}
                                       :session-data {:worktree-path "/tmp/psi-runtime-unchanged"
                                                      :session-id "runtime-unchanged"}})
        ctx                 (assoc ctx*
                                   :cwd "/tmp/psi-runtime-unchanged"
                                   :recursion-ctx (recursion/create-context)
                                   :extension-registry {:state (atom {:registration-order []
                                                                      :extensions {}})})]
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [_ _]
                    {:ok? true
                     :changed? false
                     :reason :head-unchanged
                     :head "sha-1"
                     :previous-head "sha-1"})
                  recursion/orchestrate-manual-trigger-in!
                  (fn [_ _ _]
                    (swap! orchestration-calls inc)
                    {:ok? true})
                  ext/dispatch-in
                  (fn [_ event-name payload]
                    (swap! extension-events conj {:name event-name :payload payload})
                    {:cancelled? false :override nil :results []})]
      (runtime/run-agent-loop-in!
       ctx session-id nil {} []
       {:run-loop-fn success-runner
        :sync-on-git-head-change? true})
      (is (= 0 @orchestration-calls))
      (is (= [] @extension-events)))))

(deftest run-agent-loop-sync-on-git-head-change-dispatches-extension-event-test
  (let [extension-events (atom [])
        sid              "runtime-event"
        ctx              {:cwd "/tmp/psi-runtime-event"
                          :recursion-ctx (recursion/create-context)
                          :extension-registry {:state (atom {:registration-order []
                                                             :extensions {}})}
                          :state* (atom {:agent-session {:sessions {sid {:data      {:worktree-path "/tmp/psi-runtime-event"
                                                                                     :session-id    sid}
                                                                         :agent-ctx {}}}}})}]
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [ctx session-id]
                    (let [git-sync {:ok? true
                                    :changed? true
                                    :reason :head-changed
                                    :head "abcdef1234567890"
                                    :previous-head "1111111111111111"
                                    :classification {:kind :commit-created
                                                     :notify-extensions? true
                                                     :parent-count 1}
                                    :sync sync-payload}]
                      (#'runtime/maybe-trigger-recursion-from-git-sync! ctx git-sync)
                      (#'runtime/maybe-dispatch-git-head-changed-event! ctx session-id git-sync)
                      git-sync))
                  ext/dispatch-in
                  (fn [_ event-name payload]
                    (swap! extension-events conj {:name event-name :payload payload})
                    {:cancelled? false :override nil :results []})]
      (runtime/run-agent-loop-in!
       ctx sid nil {} []
       {:run-loop-fn success-runner
        :sync-on-git-head-change? true})
      (is (= 1 (count @extension-events)))
      (let [{:keys [name payload]} (first @extension-events)]
        (is (= "git_commit_created" name))
        (is (= sid (:session-id payload)))
        (is (= "/tmp/psi-runtime-event" (:workspace-dir payload)))
        (is (= "/tmp/psi-runtime-event" (:cwd payload)))
        (is (= "abcdef1234567890" (:head payload)))
        (is (= "1111111111111111" (:previous-head payload)))
        (is (= "head-changed" (:reason payload)))
        (is (= :commit-created (get-in payload [:classification :kind])))
        (is (str/includes? (str (:timestamp payload)) "T"))))))

(deftest register-extension-run-fn-routes-through-prompt-lifecycle-test
  (let [ctx              (session/create-context (test-support/safe-context-opts {:persist? false}))
        sd               (session/new-session-in! ctx nil {})
        session-id       (:session-id sd)
        sync-calls       (atom [])]
    (dispatch/clear-event-log!)
    (with-redefs [runtime/safe-maybe-sync-on-git-head-change!
                  (fn [ctx sid]
                    (swap! sync-calls conj {:ctx ctx :session-id sid})
                    {:ok? true})
                  psi.agent-session.prompt-runtime/execute-prepared-request!
                  (fn [_ai-ctx _ctx sid _agent-ctx prepared _pq]
                    {:execution-result/turn-id (:prepared-request/id prepared)
                     :execution-result/session-id sid
                     :execution-result/assistant-message {:role "assistant"
                                                          :content [{:type :text :text "done"}]
                                                          :stop-reason :stop
                                                          :timestamp (java.time.Instant/now)}
                     :execution-result/turn-outcome :turn.outcome/stop
                     :execution-result/tool-calls []
                     :execution-result/stop-reason :stop})]
      (runtime/register-extension-run-fn-in! ctx session-id nil {:provider :anthropic :id "stub"})
      (let [run-fn @(:extension-run-fn-atom ctx)]
        (is (fn? run-fn))
        (run-fn "hello from extension" :test-source)
        (Thread/sleep 50)
        (let [entries   (dispatch/event-log-entries)
              messages  (->> (persist/all-entries-in ctx session-id)
                             (filter #(= :message (:kind %)))
                             (map #(get-in % [:data :message]))
                             vec)
              user-msg  (first messages)
              assistant (second messages)]
          (is (some #(= :session/set-model (:event-type %)) entries))
          (is (some #(= :session/prompt-submit (:event-type %)) entries))
          (is (some #(= :session/prompt-prepare-request (:event-type %)) entries))
          (is (some #(= :session/prompt-record-response (:event-type %)) entries))
          (is (some #(= :session/prompt-finish (:event-type %)) entries))
          (is (= 1 (count @sync-calls)))
          (is (= session-id (:session-id (first @sync-calls))))
          (is (= "user" (:role user-msg)))
          (is (= "hello from extension" (get-in user-msg [:content 0 :text])))
          (is (= "assistant" (:role assistant))))))))
