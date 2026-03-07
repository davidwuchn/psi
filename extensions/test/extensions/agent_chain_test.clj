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
  (testing "agent chain registers workflow type, tool, commands, and session handler"
    (let [tmp (temp-dir)]
      (try
        (write-chain-config!
         tmp
         [{:name "plan-build-review"
           :description "Plan then build"
           :steps [{:agent "planner" :prompt "$INPUT"}]}])
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent_chain.clj"})]
            (sut/init api)
            (is (contains? (:workflow-types @state) :agent-chain-run))
            (is (contains? (:tools @state) "run_chain"))
            (is (= #{"chain" "chain-list" "chain-reload"}
                   (set (keys (:commands @state)))))
            (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
            (is (contains? (:widgets @state) "agent-chain"))
            (is (str/includes?
                 (str/join "\n" (get-in @state [:widgets "agent-chain" :lines]))
                 "active: (none)"))))
        (finally
          (.delete tmp))))))

(deftest run-chain-requires-active-chain-test
  (testing "run_chain returns a helpful error when no chain is active"
    (let [tmp (temp-dir)]
      (try
        (with-user-dir (.getAbsolutePath tmp)
          (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                     {:path "/test/agent_chain.clj"})]
            (sut/init api)
            (let [execute (get-in @state [:tools "run_chain" :execute])
                  updates (atom [])]
              (is (= {:content "No chain active. Use /chain to select one."
                      :is-error true}
                     (execute {"task" "summarize this"})))
              (is (= {:content "No chain active. Use /chain to select one."
                      :is-error true}
                     (execute {"task" "summarize this"}
                              {:on-update #(swap! updates conj %)})))
              (is (empty? @updates)))))
        (finally
          (.delete tmp))))))

(deftest run-chain-starts-in-background-by-default-test
  (testing "run_chain returns immediately (non-blocking)"
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
                                     {:path "/test/agent_chain.clj"})]
            (sut/init api)
            ((get-in @state [:commands "chain" :handler]) "prompt-build")
            (let [execute (get-in @state [:tools "run_chain" :execute])
                  result  (execute {"task" "say hello"})]
              (is (false? (:is-error result)))
              (is (str/includes? (:content result) "Chain run started:"))
              (is (str/includes? (:content result) "Monitor with /chain-list."))
              (is (contains? (:workflows @state) "run-1")))))
        (finally
          (.delete tmp))))))

(deftest chain-command-selects-by-name-test
  (testing "/chain accepts a chain name, case-insensitive"
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
                                     {:path "/test/agent_chain.clj"})]
            (sut/init api)
            (let [chain-handler (get-in @state [:commands "chain" :handler])]
              (is (str/includes? (with-out-str (chain-handler "Prompt-Build"))
                                 "✓ Active chain: prompt-build"))
              (is (str/includes?
                   (str/join "\n" (get-in @state [:widgets "agent-chain" :lines]))
                   "active: prompt-build")))))
        (finally
          (.delete tmp))))))

(deftest run-chain-rejects-wait-arg-test
  (testing "run_chain returns an error when wait arg is provided"
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
                                     {:path "/test/agent_chain.clj"})]
            (sut/init api)
            ((get-in @state [:commands "chain" :handler]) "prompt-build")
            (let [execute (get-in @state [:tools "run_chain" :execute])
                  updates (atom [])
                  result  (execute {"task" "say hello"
                                    "wait" true}
                                   {:on-update #(swap! updates conj %)})]
              (is (= {:content "Unsupported argument: wait. run_chain is always non-blocking; monitor with /chain-list."
                      :is-error true}
                     result))
              (is (empty? @updates))
              (is (empty? (:workflows @state))))))
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
               "/test/agent_chain.clj"
               {:type :smoke
                :chart invoke-chart
                :initial-data-fn (fn [input] {:value (:value input)})}))))
        (is (true?
             (:created?
              (wf/create-workflow-in!
               reg
               "/test/agent_chain.clj"
               {:type :smoke :id "w1" :input {:value 9}}))))
        (loop [i 0]
          (let [w (wf/workflow-in reg "/test/agent_chain.clj" "w1")]
            (if (or (>= i 200) (= :done (:phase w)))
              (do
                (is (= :done (:phase w)))
                (is (= 9 (:result w))))
              (do (Thread/sleep 10)
                  (recur (inc i))))))
        (finally
          (wf/shutdown-in! reg))))))
