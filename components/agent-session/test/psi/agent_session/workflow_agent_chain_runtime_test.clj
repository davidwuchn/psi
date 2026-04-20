(ns psi.agent-session.workflow-agent-chain-runtime-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.core :as session]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.workflow-agent-chain-runtime :as runtime]
   [psi.agent-session.workflow-runtime :as workflow-runtime]))

(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

(deftest register-agent-chain-definitions-test
  (testing "legacy agent-chain config compiles and registers canonical workflow definitions"
    (let [tmp (str (java.nio.file.Files/createTempDirectory
                    "psi-agent-chain-runtime-test-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
          cfg (io/file tmp ".psi" "agents" "agent-chain.edn")]
      (try
        (io/make-parents cfg)
        (spit cfg
              (pr-str [{:name "plan-build"
                        :description "Plan and build"
                        :steps [{:agent "planner" :prompt "$INPUT"}
                                {:agent "builder" :prompt "Execute: $INPUT"}]}
                       {:name "prompt-build"
                        :description "Build prompts"
                        :steps [{:agent "prompt-compiler" :prompt "compile: $INPUT"}]}]))
        (let [[ctx _] (create-session-context {:persist? false :cwd tmp})
              report  (runtime/register-agent-chain-definitions! ctx)
              defs    (->> (get-in @(:state* ctx) [:workflows :definitions])
                           vals
                           (sort-by :definition-id)
                           vec)]
          (is (nil? (:error report)))
          (is (= 2 (:registered-count report)))
          (is (= ["plan-build" "prompt-build"] (:definition-ids report)))
          (is (= ["plan-build" "prompt-build"] (mapv :definition-id defs)))
          (is (= "$INPUT"
                 (get-in (workflow-runtime/workflow-definition-in @(:state* ctx) "plan-build")
                         [:steps "step-1-planner" :prompt-template])))
          (session/shutdown-context! ctx))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))

(deftest register-agent-chain-definitions-error-test
  (testing "invalid config leaves state unchanged and reports error"
    (let [tmp (str (java.nio.file.Files/createTempDirectory
                    "psi-agent-chain-runtime-error-test-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
          cfg (io/file tmp ".psi" "agents" "agent-chain.edn")]
      (try
        (io/make-parents cfg)
        (spit cfg "[")
        (let [[ctx _] (create-session-context {:persist? false :cwd tmp})
              state0  @(:state* ctx)
              report  (runtime/register-agent-chain-definitions! ctx)]
          (is (string? (:error report)))
          (is (= state0 @(:state* ctx)))
          (session/shutdown-context! ctx))
        (finally
          (doseq [f (reverse (file-seq (io/file tmp)))]
            (.delete f)))))))
