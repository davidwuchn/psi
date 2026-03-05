(ns psi.agent-session.runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.runtime :as runtime]
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
  [_ai-ctx _ctx _agent-ctx _ai-model _user-messages _opts]
  {:role "assistant"
   :content []})

(deftest run-agent-loop-sync-on-git-head-change-triggers-recursion-hook-test
  (let [recursion-ctx        (recursion/create-context)
        maybe-sync-calls     (atom [])
        orchestration-calls  (atom [])
        ctx                  {:cwd "/tmp/psi-runtime-hook-test"
                              :memory-ctx nil
                              :recursion-ctx recursion-ctx
                              :agent-ctx {}}]
    (with-redefs [runtime/invoke-git-head-sync!
                  (fn [opts]
                    (swap! maybe-sync-calls conj opts)
                    {:ok? true
                     :changed? true
                     :reason :head-changed
                     :head "sha-2"
                     :previous-head "sha-1"
                     :sync sync-payload})
                  recursion/orchestrate-manual-trigger-in!
                  (fn [rctx trigger opts]
                    (swap! orchestration-calls conj {:rctx rctx
                                                     :trigger trigger
                                                     :opts opts})
                    {:ok? true
                     :phase :completed
                     :trigger-result {:result :accepted}})]
      (let [result (runtime/run-agent-loop-in!
                    ctx nil {} []
                    {:run-loop-fn success-runner
                     :sync-on-git-head-change? true})
            call   (first @orchestration-calls)]
        (testing "agent loop still returns normal assistant result"
          (is (= "assistant" (:role result))))

        (testing "git-head hook is called once"
          (is (= 1 (count @maybe-sync-calls))))

        (testing "changed head triggers recursion orchestration"
          (is (= 1 (count @orchestration-calls)))
          (is (= recursion-ctx (:rctx call)))
          (is (= :graph-changed (get-in call [:trigger :type])))
          (is (= "git-head-changed" (get-in call [:trigger :reason])))
          (is (= :approve (get-in call [:opts :approval-decision])))
          (is (true? (get-in call [:opts :system-state :memory-ready])))
          (is (= :stable (get-in call [:opts :graph-state :status]))))))))

(deftest run-agent-loop-without-sync-flag-skips-git-head-hook-test
  (let [calls (atom 0)
        ctx   {:cwd "/tmp/psi-runtime-no-sync-flag"
               :agent-ctx {}
               :recursion-ctx (recursion/create-context)}]
    (with-redefs [runtime/invoke-git-head-sync!
                  (fn [_]
                    (swap! calls inc)
                    {:ok? true :changed? true})]
      (runtime/run-agent-loop-in!
       ctx nil {} []
       {:run-loop-fn success-runner})
      (is (= 0 @calls)))))

(deftest run-agent-loop-sync-flag-with-unchanged-head-skips-recursion-trigger-test
  (let [orchestration-calls (atom 0)
        ctx                 {:cwd "/tmp/psi-runtime-unchanged"
                             :agent-ctx {}
                             :recursion-ctx (recursion/create-context)}]
    (with-redefs [runtime/invoke-git-head-sync!
                  (fn [_]
                    {:ok? true
                     :changed? false
                     :reason :head-unchanged
                     :head "sha-1"
                     :previous-head "sha-1"})
                  recursion/orchestrate-manual-trigger-in!
                  (fn [_ _ _]
                    (swap! orchestration-calls inc)
                    {:ok? true})]
      (runtime/run-agent-loop-in!
       ctx nil {} []
       {:run-loop-fn success-runner
        :sync-on-git-head-change? true})
      (is (= 0 @orchestration-calls)))))
