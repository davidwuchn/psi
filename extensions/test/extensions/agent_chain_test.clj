(ns extensions.agent-chain-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.agent-chain :as sut]
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
  (testing "run_chain returns immediately (non-blocking) when wait is not set"
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

(deftest run-chain-interactive-wait-true-stays-non-blocking-test
  (testing "interactive tool calls ignore wait=true and start in background"
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
                  t0      (System/currentTimeMillis)
                  result  (execute {"task" "say hello"
                                    "wait" true}
                                   {:on-update #(swap! updates conj %)})
                  dt      (- (System/currentTimeMillis) t0)]
              (is (false? (:is-error result)))
              (is (< dt 1000))
              (is (str/includes? (:content result) "Chain run started:"))
              (is (str/includes? (:content result) "wait=true ignored for interactive tool calls"))
              (is (contains? (:workflows @state) "run-1"))
              (is (seq @updates)))))
        (finally
          (.delete tmp))))))
