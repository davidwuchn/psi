(ns extensions.agent-runtime-path-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.agent :as agent-ext]
   [extensions.agent-chain :as chain-ext]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.extensions.runtime-fns :as runtime-fns]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.prompt-runtime]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.workflows :as wf]
   [psi.extension-test-helpers.nullable-api :refer [with-user-dir]]
   [psi.query.core :as query]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "psi-agent-runtime-path-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-agent!
  [root name description tools body]
  (let [f (io/file root ".psi" "agents" (str name ".md"))]
    (io/make-parents f)
    (spit f (str "---\n"
                 "name: " name "\n"
                 "description: " description "\n"
                 (when (seq tools)
                   (str "tools: " tools "\n"))
                 "---\n\n"
                 body))))

(defn- write-chain-config!
  [root chains]
  (let [f (io/file root ".psi" "agents" "agent-chain.edn")]
    (io/make-parents f)
    (spit f (pr-str chains))))

(defn- create-runtime-session [cwd]
  (let [cwd-path (.getAbsolutePath (io/file cwd))
        ctx      (session/create-context {:persist? false
                                          :cwd cwd-path
                                          :mutations mutations/all-mutations
                                          :session-defaults {:model {:provider "anthropic"
                                                                     :id "claude-sonnet-4-6"}}})
        sd       (session/new-session-in! ctx nil {:worktree-path cwd-path})]
    [ctx (:session-id sd)]))

(defn- create-ext-api [ctx session-id ext-path]
  (let [qctx       (query/create-query-context)
        reg        (:extension-registry ctx)
        runtime-fs (runtime-fns/make-extension-runtime-fns ctx session-id ext-path)]
    (ext/register-extension-in! reg ext-path)
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)
    (ext/create-extension-api reg ext-path runtime-fs)))

(defn- wait-for-workflow-terminal
  [reg ext-path id]
  (loop [i 0]
    (let [wf (wf/workflow-in reg ext-path id)]
      (cond
        (>= i 200) wf
        (or (:psi.extension.workflow/done? wf)
            (:psi.extension.workflow/error? wf)
            (contains? #{:done :error} (:psi.extension.workflow/phase wf))) wf
        :else (do
                (Thread/sleep 20)
                (recur (inc i)))))))

(deftest agent-tool-runs-via-child-session-while-parent-streaming-test
  (testing "agent tool succeeds via child session while parent session is streaming"
    (let [root (temp-dir)]
      (try
        (write-agent! root "planner" "plan things" "read" "You are a planner.")
        (with-user-dir (.getAbsolutePath root)
          (let [[ctx session-id] (create-runtime-session root)
                api             (create-ext-api ctx session-id "/test/agent.clj")
                reg             (:extension-registry ctx)]
            (agent-ext/init api)
            (dispatch/dispatch! ctx :session/prompt {:session-id session-id} {:origin :core})
            (is (= :streaming (ss/sc-phase-in ctx session-id)))
            (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
                          (fn [_ai-ctx _ctx sid prepared _progress-queue]
                            {:execution-result/turn-id (:prepared-request/id prepared)
                             :execution-result/session-id sid
                             :execution-result/assistant-message {:role "assistant"
                                                                 :content [{:type :text :text (str "child ok " sid)}]
                                                                 :stop-reason :stop
                                                                 :timestamp (java.time.Instant/now)}
                             :execution-result/turn-outcome :turn.outcome/stop
                             :execution-result/tool-calls []
                             :execution-result/stop-reason :stop})]
              (let [tool   (ext/get-tool-in reg "agent")
                    result ((:execute tool) {"action" "create"
                                             "agent" "planner"
                                             "task" "say hello"
                                             "mode" "sync"}
                            {:tool-call-id "tc-agent-runtime-busy"})]
                (is (false? (:is-error result)) (pr-str result))
                (is (not (str/includes? (:content result) "Session is not idle")) (pr-str result))
                (is (str/includes? (:content result) "Agent #1") (pr-str result))
                (is (str/includes? (:content result) "finished") (pr-str result))
                (is (= :streaming (ss/sc-phase-in ctx session-id)))
                (let [wf (wait-for-workflow-terminal (:workflow-registry ctx) "/test/agent.clj" "1")]
                  (is (some? wf))
                  (is (= :done (:phase wf)) (pr-str wf))
                  (is (str/includes? (or (:result wf) "") "child ok ")))))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (.delete f)))))))

(deftest agent-chain-tool-runs-sub-agent-via-child-session-while-parent-streaming-test
  (testing "agent-chain tool succeeds while parent session is streaming"
    (let [root (temp-dir)]
      (try
        (write-agent! root "planner" "plan things" "read" "You are a planner.")
        (write-chain-config! root [{:name "plan-once"
                                    :description "single planner step"
                                    :steps [{:agent "planner" :prompt "$INPUT"}]}])
        (with-user-dir (.getAbsolutePath root)
          (let [[ctx session-id] (create-runtime-session root)
                api             (create-ext-api ctx session-id "/test/agent-chain.clj")
                reg             (:extension-registry ctx)]
            (chain-ext/init api)
            (dispatch/dispatch! ctx :session/prompt {:session-id session-id} {:origin :core})
            (is (= :streaming (ss/sc-phase-in ctx session-id)))
            (with-redefs [psi.agent-session.prompt-runtime/execute-prepared-request!
                          (fn [_ai-ctx _ctx sid prepared _progress-queue]
                            {:execution-result/turn-id (:prepared-request/id prepared)
                             :execution-result/session-id sid
                             :execution-result/assistant-message {:role "assistant"
                                                                 :content [{:type :text :text (str "chain child ok " sid)}]
                                                                 :stop-reason :stop
                                                                 :timestamp (java.time.Instant/now)}
                             :execution-result/turn-outcome :turn.outcome/stop
                             :execution-result/tool-calls []
                             :execution-result/stop-reason :stop})]
              (let [tool   (ext/get-tool-in reg "agent-chain")
                    start  ((:execute tool) {"action" "run"
                                             "chain" "plan-once"
                                             "task" "say hello"}
                            {:tool-call-id "tc-agent-chain-runtime-busy"})]
                (is (false? (:is-error start)) (pr-str start))
                (is (not (str/includes? (:content start) "Session is not idle")) (pr-str start))
                (let [wf (wait-for-workflow-terminal (:workflow-registry ctx) "/test/agent-chain.clj" "run-1")]
                  (is (some? wf))
                  (is (= :done (:phase wf)) (pr-str wf))
                  (is (false? (:error? wf)) (pr-str wf))
                  (is (str/includes? (or (:result wf) "") "chain child ok "))
                  (is (= :streaming (ss/sc-phase-in ctx session-id))))))))
        (finally
          (doseq [f (reverse (file-seq root))]
            (.delete f)))))))
