(ns extensions.agent-chain-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [extensions.agent-chain :as sut]
   [psi.agent-session.workflows :as wf]
   [psi.extension-test-helpers.nullable-api :as nullable :refer [with-user-dir]]))

(defn- temp-dir
  []
  (.toFile (java.nio.file.Files/createTempDirectory
            "psi-agent-chain-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-chain-config!
  [tmp chains]
  (let [f (io/file tmp ".psi" "agents" "agent-chain.edn")]
    (io/make-parents f)
    (spit f (pr-str chains))))

(def invoke-chart
  (chart/statechart {:id :invoke-workflow}
                    (ele/state {:id :idle}
                               (ele/transition {:event :workflow/start :target :running}))

                    (ele/state {:id :running}
                               (ele/invoke
                                {:id     :job
                                 :type   :future
                                 :params (fn [_ data] {:value (:value data)})
                                 :src    (fn [{:keys [value]}]
                                           (Thread/sleep 40)
                                           {:value value})})
                               (ele/transition {:event :done.invoke.job :target :done}
                                               (ele/script
                                                {:expr (fn [_ data]
                                                         [{:op :assign
                                                           :data {:result (get-in data [:_event :data :value])}}])})))

                    (ele/final {:id :done})))

(deftest init-registers-surface-test
  (testing "agent chain registers workflow type, tool, commands, session handler, and prompt contribution"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "plan-build-review"
           :description "Plan then build"
           :steps [{:agent "planner" :prompt "$INPUT"}]}])
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            (is (contains? (:workflow-types @state) :agent-chain-run))
            (is (contains? (:tools @state) "agent-chain"))
            (is (= #{"chain" "chain-reload"}
                   (set (keys (:commands @state)))))
            (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
            (is (contains? (:widgets @state) "agent-chain"))
            (is (= :above-editor (get-in @state [:widgets "agent-chain" :position])))
            ;; Widget no longer shows "active:" — just the header
            (is (str/includes?
                 (str/join "\n" (get-in @state [:widgets "agent-chain" :lines]))
                 "Agent Chain"))
            ;; Prompt contribution registered with chain catalog
            (let [contrib-key ["/test/agent-chain.clj" "agent-chain-chains"]
                  contrib     (get-in @state [:prompt-contributions contrib-key])]
              (is (some? contrib) "prompt contribution should be registered")
              (is (str/includes? (:content contrib) "agent-chain"))
              (is (str/includes? (:content contrib) "plan-build-review"))
              (is (str/includes? (:content contrib) "Plan then build")))))
        (finally
          (.delete tmp))))))

(deftest agent-chain-run-requires-chain-arg-test
  (testing "agent-chain action=run returns a helpful error when no chain arg given"
    (let [tmp (temp-dir)]
      (try
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            (let [execute (get-in @state [:tools "agent-chain" :execute])
                  updates (atom [])]
              (is (= {:content  "chain is required. Use action=\"list\" to see available chains."
                      :is-error true}
                     (execute {"action" "run" "task" "summarize this"})))
              (is (= {:content  "chain is required. Use action=\"list\" to see available chains."
                      :is-error true}
                     (execute {"action" "run" "task" "summarize this"}
                              {:on-update #(swap! updates conj %)})))
              (is (empty? @updates)))))
        (finally
          (.delete tmp))))))

(deftest agent-chain-run-unknown-chain-test
  (testing "agent-chain action=run returns error for unknown chain"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "plan-build-review"
           :description "Plan then build"
           :steps [{:agent "planner" :prompt "$INPUT"}]}])
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            (let [execute (get-in @state [:tools "agent-chain" :execute])
                  result  (execute {"action" "run" "chain" "no-such-chain" "task" "do it"})]
              (is (true? (:is-error result)))
              (is (str/includes? (:content result) "not found")))))
        (finally
          (.delete tmp))))))

(deftest agent-chain-run-starts-in-background-test
  (testing "agent-chain action=run returns immediately (non-blocking)"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "prompt-build"
           :description "Build prompts"
           :steps [{:agent "prompt-compiler" :prompt "$INPUT"}]}])
        (spit (io/file tmp ".psi" "agents" "prompt-compiler.md")
              (str "---\n"
                   "name: prompt-compiler\n"
                   "description: test agent\n"
                   "tools: read,bash\n"
                   "---\n\n"
                   "Use prompt-compiler skill."))
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            (let [execute (get-in @state [:tools "agent-chain" :execute])
                  result  (execute {"action" "run" "chain" "prompt-build" "task" "say hello"})]
              (is (false? (:is-error result)))
              (is (str/includes? (:content result) "Chain run started:"))
              (is (str/includes? (:content result) "Monitor with agent-chain"))
              (is (contains? (:workflows @state) "run-1")))))
        (finally
          (.delete tmp))))))

(deftest agent-chain-list-test
  (testing "agent-chain action=list returns chain and agent info"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "plan-build-review"
           :description "Plan then build"
           :steps [{:agent "planner" :prompt "$INPUT"}]}])
        (spit (io/file tmp ".psi" "agents" "planner.md")
              (str "---\n"
                   "name: planner\n"
                   "description: planning agent\n"
                   "tools: read\n"
                   "---\n\n"
                   "You are a planner."))
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            (let [execute (get-in @state [:tools "agent-chain" :execute])
                  result  (execute {"action" "list"})]
              (is (false? (:is-error result)))
              (is (str/includes? (:content result) "plan-build-review"))
              (is (str/includes? (:content result) "Planner")))))
        (finally
          (.delete tmp))))))

(deftest agent-chain-reload-test
  (testing "agent-chain action=reload reloads chains and agents and updates prompt contribution"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "initial-chain"
           :description "initial"
           :steps [{:agent "planner" :prompt "$INPUT"}]}])
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            ;; Modify config and reload
            (write-chain-config!
             tmp
             [{:name "initial-chain" :description "initial" :steps [{:agent "planner" :prompt "$INPUT"}]}
              {:name "new-chain" :description "new stuff" :steps [{:agent "builder" :prompt "$INPUT"}]}])
            (let [execute (get-in @state [:tools "agent-chain" :execute])
                  result  (execute {"action" "reload"})]
              (is (false? (:is-error result)))
              (is (str/includes? (:content result) "Reloaded"))
              (is (str/includes? (:content result) "2 chains"))
              ;; Contribution updated to include new chain
              (let [contrib-key ["/test/agent-chain.clj" "agent-chain-chains"]
                    contrib     (get-in @state [:prompt-contributions contrib-key])]
                (is (str/includes? (:content contrib) "new-chain"))
                (is (str/includes? (:content contrib) "new stuff"))))))
        (finally
          (.delete tmp))))))

(deftest agent-chain-unknown-action-test
  (testing "agent-chain returns error for unknown action"
    (let [tmp (temp-dir)]
      (try
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            (let [execute (get-in @state [:tools "agent-chain" :execute])
                  result  (execute {"action" "explode"})]
              (is (true? (:is-error result)))
              (is (str/includes? (:content result) "Unknown action")))))
        (finally
          (.delete tmp))))))

(deftest agent-chain-widget-placement-follows-ui-type-test
  (testing "agent-chain widget renders below editor in emacs ui"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "prompt-build"
           :description "Build prompts"
           :steps [{:agent "prompt-compiler" :prompt "$INPUT"}]}])
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"
                                      :ui-type :emacs})]
            (sut/init api)
            (is (= :below-editor
                   (get-in @state [:widgets "agent-chain" :position])))))
        (finally
          (.delete tmp))))))

(deftest chain-command-lists-chains-test
  (testing "/chain prints available chains"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "plan-build-review"
           :description "Plan then build"
           :steps [{:agent "planner" :prompt "$INPUT"}]}
          {:name "prompt-build"
           :description "Build prompts"
           :steps [{:agent "prompt-compiler" :prompt "$INPUT"}]}])
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent-chain.clj"})]
            (sut/init api)
            (let [chain-handler (get-in @state [:commands "chain" :handler])
                  output        (with-out-str (chain-handler nil))]
              (is (str/includes? output "plan-build-review"))
              (is (str/includes? output "prompt-build")))))
        (finally
          (.delete tmp))))))

(deftest workflow-runtime-future-invoke-smoke-test
  (testing "workflow runtime handles :future invoke completion"
    (let [reg (wf/create-registry)]
      (try
        (is (true?
             (:registered?
              (wf/register-type-in!
               reg
               "/test/agent-chain.clj"
               {:type :smoke
                :chart invoke-chart
                :initial-data-fn (fn [input] {:value (:value input)})}))))
        (is (true?
             (:created?
              (wf/create-workflow-in!
               reg
               "/test/agent-chain.clj"
               {:type :smoke :id "w1" :input {:value 9}}))))
        (loop [i 0]
          (let [w (wf/workflow-in reg "/test/agent-chain.clj" "w1")]
            (if (or (>= i 200) (= :done (:phase w)))
              (do
                (is (= :done (:phase w)))
                (is (= 9 (:result w))))
              (do (Thread/sleep 10)
                  (recur (inc i))))))
        (finally
          (wf/shutdown-in! reg))))))
