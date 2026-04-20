(ns psi.agent-session.workflow-execution-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-execution :as workflow-execution]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([] (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(def definition
  {:definition-id "plan-build"
   :name "Plan Build"
   :step-order ["step-1-planner" "step-2-builder"]
   :steps {"step-1-planner" {:executor {:type :agent :profile "planner" :mode :sync}
                              :prompt-template "$INPUT"
                              :input-bindings {:input {:source :workflow-input :path [:input]}
                                               :original {:source :workflow-input :path [:original]}}
                              :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                              :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}
           "step-2-builder" {:executor {:type :agent :profile "builder" :mode :sync}
                              :prompt-template "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"
                              :input-bindings {:input {:source :step-output :path ["step-1-planner" :outputs :text]}
                                               :original {:source :workflow-input :path [:original]}}
                              :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                              :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}}})

(deftest materialize-step-inputs-and-prompt-test
  (let [[state1 _ _] (workflow-runtime/register-definition {:workflows {:definitions {} :runs {} :run-order []}} definition)
        [state2 run-id _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                               :run-id "run-1"
                                                               :workflow-input {:input "ship it" :original "build this feature"}})
        run0 (workflow-runtime/workflow-run-in state2 run-id)
        prompt0 (workflow-execution/step-prompt run0 "step-1-planner")
        state3 (assoc-in state2 [:workflows :runs run-id :step-runs "step-1-planner" :accepted-result]
                         {:outcome :ok :outputs {:text "plan text"}})
        run1 (workflow-runtime/workflow-run-in state3 run-id)
        prompt1 (workflow-execution/step-prompt run1 "step-2-builder")]
    (is (= {:input "ship it" :original "build this feature"} (:step-inputs prompt0)))
    (is (= "ship it" (:prompt prompt0)))
    (is (= {:input "plan text" :original "build this feature"} (:step-inputs prompt1)))
    (is (= "Execute this plan:\n\nplan text\n\nOriginal request: build this feature"
           (:prompt prompt1)))))

(deftest execute-current-step-test
  (testing "current step execution creates an attempt session, prompts it, and advances the workflow"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                                            :run-id "run-1"
                                                                            :workflow-input {:input "ship it"
                                                                                             :original "build this feature"}})]
                       state2)))
          prompted* (atom [])]
      (with-redefs [psi.agent-session.core/prompt-in! (fn [_ctx child-session-id prompt]
                                                        (swap! prompted* conj {:session-id child-session-id :prompt prompt})
                                                        nil)
                    psi.agent-session.core/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                       {:content "planner output"})]
        (let [result (workflow-execution/execute-current-step! ctx session-id "run-1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")]
          (is (= "step-1-planner" (:step-id result)))
          (is (= :running (:status run)))
          (is (= "step-2-builder" (:current-step-id run)))
          (is (= {:outcome :ok :outputs {:text "planner output"}}
                 (get-in run [:step-runs "step-1-planner" :accepted-result])))
          (is (= [{:session-id (:execution-session-id result)
                   :prompt "ship it"}]
                 @prompted*)))))))

(deftest execute-run-test
  (testing "execute-run! drives a sequential workflow through all steps to completion"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "plan-build"
                                                                            :run-id "run-1"
                                                                            :workflow-input {:input "ship it"
                                                                                             :original "build this feature"}})]
                       state2)))
          prompts*   (atom [])
          responses* (atom ["planner output" "builder output"])]
      (with-redefs [psi.agent-session.core/prompt-in! (fn [_ctx child-session-id prompt]
                                                        (swap! prompts* conj {:session-id child-session-id :prompt prompt})
                                                        nil)
                    psi.agent-session.core/last-assistant-message-in (fn [_ctx _child-session-id]
                                                                       (let [resp (first @responses*)]
                                                                         (swap! responses* subvec 1)
                                                                         {:content resp}))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-1")]
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= 2 (count (:steps-executed result))))
          (is (= :completed (:status run)))
          (is (= {:outcome :ok :outputs {:text "builder output"}}
                 (get-in run [:step-runs "step-2-builder" :accepted-result])))
          (is (= ["ship it"
                  "Execute this plan:\n\nplanner output\n\nOriginal request: build this feature"]
                 (mapv :prompt @prompts*))))))))
