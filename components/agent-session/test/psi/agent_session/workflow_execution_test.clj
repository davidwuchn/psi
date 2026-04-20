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

(def blocked-definition
  {:definition-id "blocked-review"
   :name "Blocked Review"
   :step-order ["step-1-review"]
   :steps {"step-1-review" {:executor {:type :agent :profile "reviewer" :mode :sync}
                             :prompt-template "$INPUT"
                             :input-bindings {:input {:source :workflow-input :path [:input]}}
                             :result-schema :any
                             :retry-policy {:max-attempts 1 :retry-on #{:execution-failed}}}}})

(def retry-definition
  {:definition-id "retry-build"
   :name "Retry Build"
   :step-order ["step-1-builder"]
   :steps {"step-1-builder" {:executor {:type :agent :profile "builder" :mode :sync}
                              :prompt-template "$INPUT"
                              :input-bindings {:input {:source :workflow-input :path [:input]}}
                              :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
                              :retry-policy {:max-attempts 2 :retry-on #{:execution-failed}}}}})

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

(deftest execute-run-blocked-test
  (testing "execute-run! stops and reports blocked runs"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state blocked-definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "blocked-review"
                                                                            :run-id "run-b1"
                                                                            :workflow-input {:input "need approval"}})]
                       state2)))]
      (with-redefs [psi.agent-session.workflow-execution/execute-current-step! (fn [_ctx _sid _run-id]
                                                                                 (swap! (:state* ctx)
                                                                                        update-in
                                                                                        [:workflows :runs "run-b1"]
                                                                                        #(-> %
                                                                                             (assoc :status :blocked
                                                                                                    :blocked {:question "approve?"})
                                                                                             (assoc-in [:step-runs "step-1-review" :attempts]
                                                                                                       [{:attempt-id "a1"
                                                                                                         :status :blocked
                                                                                                         :execution-session-id "child-1"
                                                                                                         :blocked {:question "approve?"}}])))
                                                                                 {:run-id "run-b1"
                                                                                  :step-id "step-1-review"
                                                                                  :attempt-id "a1"
                                                                                  :execution-session-id "child-1"
                                                                                  :status :blocked})]
        (let [result (workflow-execution/execute-run! ctx session-id "run-b1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-b1")]
          (is (= :blocked (:status result)))
          (is (false? (:terminal? result)))
          (is (true? (:blocked? result)))
          (is (= :blocked (:status run)))
          (is (= {:question "approve?"} (:blocked run))))))))

(deftest execute-run-retry-test
  (testing "execute-run! retries when execution failure leaves the run retryable"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state retry-definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "retry-build"
                                                                            :run-id "run-r1"
                                                                            :workflow-input {:input "retry me"}})]
                       (-> state2
                           (assoc-in [:workflows :runs "run-r1" :step-runs "step-1-builder" :attempts]
                                     [{:attempt-id "a1"
                                       :status :running
                                       :execution-session-id "child-1"}])))))
          calls* (atom 0)]
      (with-redefs [psi.agent-session.workflow-execution/execute-current-step!
                    (fn [_ctx _sid _run-id]
                      (let [n (swap! calls* inc)]
                        (if (= 1 n)
                          (do
                            (swap! (:state* ctx)
                                   psi.agent-session.workflow-progression/record-execution-failure
                                   "run-r1"
                                   "step-1-builder"
                                   {:message "transient provider error"})
                            {:run-id "run-r1"
                             :step-id "step-1-builder"
                             :attempt-id "a1"
                             :execution-session-id "child-1"
                             :status :running
                             :error "transient provider error"})
                          (do
                            (swap! (:state* ctx)
                                   update-in
                                   [:workflows :runs "run-r1" :step-runs "step-1-builder" :attempts]
                                   conj
                                   {:attempt-id "a2"
                                    :status :running
                                    :execution-session-id "child-2"})
                            (swap! (:state* ctx)
                                   psi.agent-session.workflow-progression/submit-result-envelope
                                   "run-r1"
                                   "step-1-builder"
                                   {:outcome :ok :outputs {:text "done"}})
                            {:run-id "run-r1"
                             :step-id "step-1-builder"
                             :attempt-id "a2"
                             :execution-session-id "child-2"
                             :status :completed}))))]
        (let [result (workflow-execution/execute-run! ctx session-id "run-r1")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-r1")]
          (is (= 2 @calls*))
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= :completed (:status run)))
          (is (= {:outcome :ok :outputs {:text "done"}}
                 (get-in run [:step-runs "step-1-builder" :accepted-result]))))))))

(deftest resume-and-execute-run-test
  (testing "resume-and-execute-run! resumes blocked runs and continues with a fresh attempt"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 _ _] (workflow-runtime/register-definition state blocked-definition)
                           [state2 _ _] (workflow-runtime/create-run state1 {:definition-id "blocked-review"
                                                                            :run-id "run-resume"
                                                                            :workflow-input {:input "need approval"}})]
                       (-> state2
                           (assoc-in [:workflows :runs "run-resume" :status] :blocked)
                           (assoc-in [:workflows :runs "run-resume" :blocked] {:question "approve?"})
                           (assoc-in [:workflows :runs "run-resume" :step-runs "step-1-review" :attempts]
                                     [{:attempt-id "a1"
                                       :status :blocked
                                       :execution-session-id "child-1"
                                       :blocked {:question "approve?"}}])))))
          calls* (atom 0)]
      (with-redefs [psi.agent-session.workflow-execution/execute-current-step!
                    (fn [_ctx _sid _run-id]
                      (swap! calls* inc)
                      (swap! (:state* ctx)
                             update-in
                             [:workflows :runs "run-resume" :step-runs "step-1-review" :attempts]
                             conj
                             {:attempt-id "a2"
                              :status :running
                              :execution-session-id "child-2"})
                      (swap! (:state* ctx)
                             psi.agent-session.workflow-progression/submit-result-envelope
                             "run-resume"
                             "step-1-review"
                             {:outcome :ok :outputs {:text "approved"}})
                      {:run-id "run-resume"
                       :step-id "step-1-review"
                       :attempt-id "a2"
                       :execution-session-id "child-2"
                       :status :completed})]
        (let [result (workflow-execution/resume-and-execute-run! ctx session-id "run-resume")
              run    (workflow-runtime/workflow-run-in @(:state* ctx) "run-resume")]
          (is (= 1 @calls*))
          (is (= :completed (:status result)))
          (is (true? (:terminal? result)))
          (is (= :completed (:status run)))
          (is (nil? (:blocked run))))))))
