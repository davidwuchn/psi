(ns psi.agent-session.workflow-agent-chain-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.workflow-agent-chain :as workflow-agent-chain]
   [psi.agent-session.workflow-model :as workflow-model]))

(def sample-chain
  {:name "plan-build-review"
   :description "Plan, build, and review code changes"
   :steps [{:agent "planner"
            :prompt "$INPUT"}
           {:agent "builder"
            :prompt "Execute this plan:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}
           {:agent "reviewer"
            :prompt "Review the following implementation:\n\n$INPUT\n\nOriginal request: $ORIGINAL"}]})

(deftest chain->workflow-definition-test
  (testing "agent-chain config compiles to a canonical workflow definition"
    (let [definition (workflow-agent-chain/chain->workflow-definition sample-chain)
          [plan-id build-id review-id] (:step-order definition)]
      (is (= "plan-build-review" (:definition-id definition)))
      (is (= "Plan, build, and review code changes" (:summary definition)))
      (is (= ["step-1-planner" "step-2-builder" "step-3-reviewer"]
             (:step-order definition)))
      (is (workflow-model/valid-workflow-definition? definition))
      (is (= {:type :agent :profile "planner"}
             (get-in definition [:steps plan-id :executor])))
      (is (= "$INPUT"
             (get-in definition [:steps plan-id :prompt-template])))
      (is (= {:input {:source :workflow-input :path [:input]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps plan-id :input-bindings])))
      (is (= {:input {:source :step-output :path [plan-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps build-id :input-bindings])))
      (is (= {:input {:source :step-output :path [build-id :outputs :text]}
              :original {:source :workflow-input :path [:original]}}
             (get-in definition [:steps review-id :input-bindings])))
      (is (= [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]]
             (get-in definition [:steps review-id :result-schema])))
      (is (= {:max-attempts 1
              :retry-on #{:execution-failed :validation-failed}}
             (get-in definition [:steps review-id :retry-policy])))))

  (testing "multiple chains compile to multiple canonical workflow definitions"
    (let [definitions (workflow-agent-chain/chains->workflow-definitions
                       [sample-chain
                        {:name "prompt-build"
                         :description "Build a new prompt"
                         :steps [{:agent "prompt-compiler" :prompt "compile: $INPUT"}]}])]
      (is (= ["plan-build-review" "prompt-build"]
             (mapv :definition-id definitions)))
      (is (every? workflow-model/valid-workflow-definition? definitions)))))
