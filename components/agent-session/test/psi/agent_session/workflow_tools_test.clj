(ns psi.agent-session.workflow-tools-test
  "psi-tool workflow action tests, split from tools-test to stay under file-length limit."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tools :as tools]
   [psi.agent-session.workflow-runtime]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest make-psi-tool-workflow-test
  (testing "workflow list-definitions reports registered definitions"
    (let [[ctx session-id] (create-session-context {:persist? false})
          _ (swap! (:state* ctx)
                   (fn [state]
                     (first
                      (let [[state' _ _]
                            (psi.agent-session.workflow-runtime/register-definition
                             state
                             {:definition-id "plan-build-review"
                              :name "Plan Build Review"
                              :step-order ["plan"]
                              :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                               :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                               :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}})]
                        [state']))))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "list-definitions"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :workflow (:psi-tool/action parsed)))
      (is (= :list-definitions (:psi-tool/workflow-op parsed)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (= 1 (get-in parsed [:psi-tool/workflow :definition-count])))
      (is (= ["plan-build-review"] (get-in parsed [:psi-tool/workflow :definition-ids])))))

  (testing "workflow register-agent-chains compiles and registers named chain definitions"
    (let [tmp    (str (java.nio.file.Files/createTempDirectory
                       "psi-tool-agent-chain-test-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
          cfg    (io/file tmp ".psi" "agents" "agent-chain.edn")]
      (try
        (io/make-parents cfg)
        (spit cfg (pr-str [{:name "plan-build"
                            :description "Plan and build"
                            :steps [{:agent "planner" :prompt "$INPUT"}
                                    {:agent "builder" :prompt "Execute: $INPUT"}]}]))
        (let [[ctx session-id] (create-session-context {:persist? false :cwd tmp})
              tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
              result ((:execute tool) {"action" "workflow" "op" "register-agent-chains"})
              parsed (read-string (:content result))]
          (is (false? (:is-error result)))
          (is (= :register-agent-chains (:psi-tool/workflow-op parsed)))
          (is (= :ok (:psi-tool/overall-status parsed)))
          (is (= 1 (get-in parsed [:psi-tool/workflow :registered-count])))
          (is (= ["plan-build"] (get-in parsed [:psi-tool/workflow :definition-ids])))
          (is (= "plan-build"
                 (:definition-id (get-in @(:state* ctx) [:workflows :definitions "plan-build"])))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f))))))

  (testing "workflow create-run-from-agent-chain registers then creates a run from named chain"
    (let [tmp    (str (java.nio.file.Files/createTempDirectory
                       "psi-tool-agent-chain-run-test-"
                       (make-array java.nio.file.attribute.FileAttribute 0)))
          cfg    (io/file tmp ".psi" "agents" "agent-chain.edn")]
      (try
        (io/make-parents cfg)
        (spit cfg (pr-str [{:name "plan-build"
                            :description "Plan and build"
                            :steps [{:agent "planner" :prompt "$INPUT"}
                                    {:agent "builder" :prompt "Execute: $INPUT"}]}]))
        (let [[ctx session-id] (create-session-context {:persist? false :cwd tmp})
              tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
              result ((:execute tool) {"action" "workflow"
                                       "op" "create-run-from-agent-chain"
                                       "chain-name" "plan-build"
                                       "workflow-input" "{:input \"ship it\" :original \"build this feature\"}"})
              parsed (read-string (:content result))
              run-id (get-in parsed [:psi-tool/workflow :run-id])]
          (is (false? (:is-error result)))
          (is (= :create-run-from-agent-chain (:psi-tool/workflow-op parsed)))
          (is (= :ok (:psi-tool/overall-status parsed)))
          (is (= "plan-build" (get-in parsed [:psi-tool/workflow :chain-name])))
          (is (= ["plan-build"] (get-in parsed [:psi-tool/workflow :registration :definition-ids])))
          (is (= :pending (get-in parsed [:psi-tool/workflow :run :status])))
          (is (= {:input "ship it" :original "build this feature"}
                 (get-in parsed [:psi-tool/workflow :run :workflow-input])))
          (is (= run-id (get-in @(:state* ctx) [:workflows :run-order 0]))))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f))))))

  (testing "workflow create-run creates a run from inline definition"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow"
                                   "op" "create-run"
                                   "definition" "{:name \"Inline\" :step-order [\"plan\"] :steps {\"plan\" {:executor {:type :agent :profile \"planner\" :mode :sync} :result-schema [:map [:outcome [:= :ok]] [:outputs :map]] :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}"
                                   "workflow-input" "{:task \"ship it\"}"})
          parsed (read-string (:content result))
          run-id (get-in parsed [:psi-tool/workflow :run-id])]
      (is (false? (:is-error result)))
      (is (= :create-run (:psi-tool/workflow-op parsed)))
      (is (= :ok (:psi-tool/overall-status parsed)))
      (is (string? run-id))
      (is (= :pending (get-in parsed [:psi-tool/workflow :run :status])))
      (is (= {:task "ship it"} (get-in parsed [:psi-tool/workflow :run :workflow-input])))
      (is (= run-id (get-in @(:state* ctx) [:workflows :run-order 0])))))

  (testing "workflow create-run plus execute-run completes an ad-hoc inline workflow"
    (let [[ctx session-id] (create-session-context {:persist? false})
          tool          (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          create-result ((:execute tool) {"action" "workflow"
                                          "op" "create-run"
                                          "definition" "{:name \"Inline Lambda Build\" :step-order [\"step-1-lambda-compiler\" \"step-2-lambda-decompiler\" \"step-3-lambda-compiler\"] :steps {\"step-1-lambda-compiler\" {:label \"lambda-compiler\" :executor {:type :agent :profile \"lambda-compiler\"} :prompt-template \"compile a lambda for: $INPUT\" :input-bindings {:input {:source :workflow-input :path [:input]}} :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]] :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}} \"step-2-lambda-decompiler\" {:label \"lambda-decompiler\" :executor {:type :agent :profile \"lambda-decompiler\"} :prompt-template \"decompile the lambda expression: $INPUT\" :input-bindings {:input {:source :step-output :path [\"step-1-lambda-compiler\" :outputs :text]}} :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]] :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}} \"step-3-lambda-compiler\" {:label \"lambda-compiler\" :executor {:type :agent :profile \"lambda-compiler\"} :prompt-template \"compile a lambda for: $INPUT\" :input-bindings {:input {:source :step-output :path [\"step-2-lambda-decompiler\" :outputs :text]}} :result-schema [:map [:outcome [:= :ok]] [:outputs [:map [:text :string]]]] :retry-policy {:max-attempts 1 :retry-on #{:execution-failed :validation-failed}}}}}"
                                          "workflow-input" "{:input \"refine the scope clearly and collaboratively\"}"})
          create-parsed (read-string (:content create-result))
          run-id        (get-in create-parsed [:psi-tool/workflow :run-id])
          exec-result   ((:execute tool) {"action" "workflow" "op" "execute-run" "run-id" run-id})
          exec-parsed   (read-string (:content exec-result))]
      (is (false? (:is-error create-result)))
      (is (= :create-run (:psi-tool/workflow-op create-parsed)))
      (is (string? run-id))
      (is (false? (:is-error exec-result)))
      (is (= :execute-run (:psi-tool/workflow-op exec-parsed)))
      (is (= :completed (get-in exec-parsed [:psi-tool/workflow :status])))
      (is (true? (get-in exec-parsed [:psi-tool/workflow :terminal?])))
      (is (= :completed (get-in exec-parsed [:psi-tool/workflow :run :status])))
      (is (= 3 (count (get-in exec-parsed [:psi-tool/workflow :steps-executed]))))
      (is (= :completed (get-in @(:state* ctx) [:workflows :runs run-id :status])))))

  (testing "workflow list-runs and read-run return run summaries"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                       :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                       :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 _ _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       state2)))
          tool        (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          list-result ((:execute tool) {"action" "workflow" "op" "list-runs"})
          list-parsed (read-string (:content list-result))
          read-result ((:execute tool) {"action" "workflow" "op" "read-run" "run-id" "run-1"})
          read-parsed (read-string (:content read-result))]
      (is (false? (:is-error list-result)))
      (is (= :list-runs (:psi-tool/workflow-op list-parsed)))
      (is (= 1 (get-in list-parsed [:psi-tool/workflow :run-count])))
      (is (= ["run-1"] (get-in list-parsed [:psi-tool/workflow :run-ids])))
      (is (false? (:is-error read-result)))
      (is (= :read-run (:psi-tool/workflow-op read-parsed)))
      (is (= "run-1" (get-in read-parsed [:psi-tool/workflow :run-id])))
      (is (= :pending (get-in read-parsed [:psi-tool/workflow :run :status])))))

  (testing "workflow resume-run resumes blocked runs and continues execution"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                       :prompt-template "Return exactly {:outcome :ok :outputs {}}"
                                       :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                       :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 run-id _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       (-> state2
                           (assoc-in [:workflows :runs run-id :status] :blocked)
                           (assoc-in [:workflows :runs run-id :blocked] {:reason "needs resume"})))))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "resume-run" "run-id" "run-1"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :resume-run (:psi-tool/workflow-op parsed)))
      (is (= :completed (get-in parsed [:psi-tool/workflow :run :status])))
      (is (true? (get-in parsed [:psi-tool/workflow :terminal?])))
      (is (false? (get-in parsed [:psi-tool/workflow :blocked?])))
      (is (= :completed (get-in @(:state* ctx) [:workflows :runs "run-1" :status])))))

  (testing "workflow execute-run executes pending runs to completion"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                       :prompt-template "Return exactly {:outcome :ok :outputs {}}"
                                       :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                       :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 _ _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       state2)))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "execute-run" "run-id" "run-1"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :execute-run (:psi-tool/workflow-op parsed)))
      (is (= :completed (get-in parsed [:psi-tool/workflow :status])))
      (is (true? (get-in parsed [:psi-tool/workflow :terminal?])))
      (is (= :completed (get-in parsed [:psi-tool/workflow :run :status])))
      (is (= :completed (get-in @(:state* ctx) [:workflows :runs "run-1" :status])))))

  (testing "workflow cancel-run cancels non-terminal runs"
    (let [[ctx session-id] (create-session-context {:persist? false})
          definition {:definition-id "plan-build-review"
                      :name "Plan Build Review"
                      :step-order ["plan"]
                      :steps {"plan" {:executor {:type :agent :profile "planner" :mode :sync}
                                       :result-schema [:map [:outcome [:= :ok]] [:outputs :map]]
                                       :retry-policy {:max-attempts 1 :retry-on #{:validation-failed}}}}}
          _ (swap! (:state* ctx)
                   (fn [state]
                     (let [[state1 definition-id _] (psi.agent-session.workflow-runtime/register-definition state definition)
                           [state2 _ _] (psi.agent-session.workflow-runtime/create-run state1 {:definition-id definition-id :run-id "run-1"})]
                       state2)))
          tool   (tools/make-psi-tool (fn [_q] {}) {:ctx ctx :session-id session-id})
          result ((:execute tool) {"action" "workflow" "op" "cancel-run" "run-id" "run-1" "reason" "operator request"})
          parsed (read-string (:content result))]
      (is (false? (:is-error result)))
      (is (= :cancel-run (:psi-tool/workflow-op parsed)))
      (is (= :cancelled (get-in parsed [:psi-tool/workflow :run :status])))
      (is (= "operator request" (get-in parsed [:psi-tool/workflow :run :terminal-outcome :reason])))
      (is (= :cancelled (get-in @(:state* ctx) [:workflows :runs "run-1" :status]))))))
