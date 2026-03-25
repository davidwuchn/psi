(ns extensions.agent-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.agent :as sut]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(def expected-tool-names
  #{"agent"})

(def expected-command-names
  #{"agent" "agent-cont" "agent-rm" "agent-clear" "agent-list"})

(defn with-test-agents-dir
  [agent-files f]
  (let [tmp-dir    (java.nio.file.Files/createTempDirectory "agent-test"
                                                            (make-array java.nio.file.attribute.FileAttribute 0))
        root       (.toFile tmp-dir)
        agents-dir (io/file root ".psi" "agents")]
    (.mkdirs agents-dir)
    (doseq [[name content] agent-files]
      (spit (io/file agents-dir name) content))
    (nullable/with-user-dir (.getAbsolutePath root)
      (try
        (f)
        (finally
          (doseq [f* (reverse (file-seq root))]
            (.delete f*)))))))

(deftest init-registers-surface-test
  (testing "agent registers workflow type, tools, commands, and lifecycle handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (is (contains? (:workflow-types @state) :agent))
      (is (= expected-tool-names
             (set (keys (:tools @state)))))
      (is (= expected-command-names
             (set (keys (:commands @state)))))
      (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
      (is (= "agent loaded (workflow runtime, ui=console)"
             (-> @state :notifications last :text)))
      (is (= 1 (count (:prompt-contributions @state))))
      (is (contains? (:prompt-contributions @state)
                     ["/test/agent.clj" "agent-capabilities"])))))

(deftest tool-validation-test
  (testing "agent validates missing action"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: action must be one of create, continue, remove, list."
                :is-error true}
               (execute {}))))))

  (testing "agent create validates empty task"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: task is required." :is-error true}
               (execute {"action" "create"}))))))

  (testing "agent list returns empty-state summary"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "No active agents." :is-error false}
               (execute {"action" "list"}))))))

  (testing "agent list reuses workflow display lines when present"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (swap! state assoc-in [:workflows "8"]
             {:psi.extension/path "/test/agent.clj"
              :psi.extension.workflow/id "8"
              :psi.extension.workflow/type :agent
              :psi.extension.workflow/done? true
              :psi.extension.workflow/running? false
              :psi.extension.workflow/error? false
              :psi.extension.workflow/data {:agent/display {:top-line "✓ public top"
                                                            :detail-line "  ↳ final useful line"}}})
      (let [execute (get-in @state [:tools "agent" :execute])
            result  (execute {"action" "list"})]
        (is (false? (:is-error result)))
        (is (str/includes? (:content result) "✓ public top"))
        (is (str/includes? (:content result) "↳ final useful line")))))

  (testing "agent create rejects unknown agent"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: Unknown agent 'not-real'." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "agent" "not-real"}))))))

  (testing "agent continue rejects mode parameter"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: mode is only supported for action=create" :is-error true}
               (execute {"action" "continue"
                         "id" 1
                         "prompt" "next"
                         "mode" "async"}))))))

  (testing "agent create validates mode value"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: mode must be one of sync, async" :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "mode" "bogus"}))))))

  (testing "agent create validates timeout_ms"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: timeout_ms must be a positive integer." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "mode" "sync"
                         "timeout_ms" 0}))))))

  (testing "agent create validates fork_session"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: fork_session must be true or false." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "fork_session" "maybe"}))))))

  (testing "agent continue rejects fork_session parameter"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: fork_session is only supported for action=create" :is-error true}
               (execute {"action" "continue"
                         "id" 1
                         "prompt" "next"
                         "fork_session" true}))))))

  (testing "agent create validates include_result_in_context"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: include_result_in_context must be true or false." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "include_result_in_context" "maybe"}))))))

  (testing "agent continue validates include_result_in_context"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (is (= {:content "Error: include_result_in_context must be true or false." :is-error true}
               (execute {"action" "continue"
                         "id" 1
                         "prompt" "next"
                         "include_result_in_context" "maybe"})))))))

(deftest prompt-contribution-lists-available-agents-test
  (testing "prompt contribution lists names discovered from .psi/agents"
    (with-test-agents-dir
      {"planner.md" "---\nname: planner\ndescription: plan\n---\nYou are planner."
       "builder.md" "---\nname: builder\ndescription: build\n---\nYou are builder."
       "README.txt" "ignored"}
      (fn []
        (let [{:keys [api state]} (nullable/create-nullable-extension-api
                                   {:path "/test/agent.clj"
                                    :query-fn (fn [q]
                                                (cond
                                                  (= q [:psi.agent-session/cwd])
                                                  {:psi.agent-session/cwd (System/getProperty "user.dir")}

                                                  (= q [:psi.agent-session/model])
                                                  {:psi.agent-session/model {:provider :anthropic
                                                                             :id       "claude-sonnet-4-6"}}

                                                  (= q [:psi.agent-session/system-prompt])
                                                  {:psi.agent-session/system-prompt "base"}

                                                  :else
                                                  {}))})]
          (sut/init api)
          (let [contrib (get-in @state [:prompt-contributions
                                        ["/test/agent.clj" "agent-capabilities"]
                                        :content])]
            (is (str/includes? contrib "tool: agent"))
            (is (str/includes? contrib "available agents:"))
            (is (str/includes? contrib "- planner: plan"))
            (is (str/includes? contrib "- builder: build"))))))))

(deftest create-mode-behavior-test
  (testing "create defaults to async and returns background job id"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])
            result  (execute {"action" "create" "task" "test task"} {:tool-call-id "tc-create-1"})]
        (is (false? (:is-error result)))
        (is (str/includes? (:content result) "spawned in background"))
        (is (str/includes? (:content result) "job job-1")))))

  (testing "create sync returns terminal output without job id"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (with-redefs [sut/await-terminal-workflow (fn [_id _timeout]
                                                  {:workflow {:psi.extension.workflow/error? false
                                                              :psi.extension.workflow/result "sync ok"
                                                              :psi.extension.workflow/data {:agent/elapsed-ms 12}}})]
        (let [execute (get-in @state [:tools "agent" :execute])
              result  (execute {"action" "create" "task" "sync task" "mode" "sync"}
                               {:tool-call-id "tc-create-sync"})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "Agent #1 finished"))
          (is (str/includes? (:content result) "sync ok"))
          (is (not (str/includes? (:content result) "job ")))))))

  (testing "create sync error returns is-error true and terminal text"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (with-redefs [sut/await-terminal-workflow (fn [_id _timeout]
                                                  {:workflow {:psi.extension.workflow/error? true
                                                              :psi.extension.workflow/error-message "boom"
                                                              :psi.extension.workflow/data {:agent/elapsed-ms 20}}})]
        (let [execute (get-in @state [:tools "agent" :execute])
              result  (execute {"action" "create" "task" "sync task" "mode" "sync"}
                               {:tool-call-id "tc-create-sync-err"})]
          (is (true? (:is-error result)))
          (is (str/includes? (:content result) "boom")))))))

(deftest sync-mode-spawn-does-not-directly-emit-result-test
  (testing "sync mode does not emit result from spawn-agent! itself"
    (let [{:keys [api]} (nullable/create-nullable-extension-api
                         {:path "/test/agent.clj"})
          emit-count (atom 0)]
      (sut/init api)
      (with-redefs [sut/await-terminal-workflow (fn [_id _timeout]
                                                {:workflow {:psi.extension.workflow/error? false
                                                            :psi.extension.workflow/result "sync ok"
                                                            :psi.extension.workflow/data {:agent/elapsed-ms 12}}
                                                 :timeout false
                                                 :error nil})
                    sut/emit-result-message! (fn [& _]
                                               (swap! emit-count inc))]
        (let [result (#'sut/spawn-agent! "sync task" nil {:mode :sync
                                                       :tool-call-id "tc-sync-no-double"})]
          (is (= 0 @emit-count))
          (is (false? (:is-error result)))
          (is (= "sync ok" (:content result))))))))

(deftest execute-agent-tool-timeout-test
  (testing "create passes timeout_ms through to spawn-agent"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})
          captured (atom nil)]
      (sut/init api)
      (with-redefs [sut/spawn-agent! (fn [_task _agent opts]
                                       (reset! captured opts)
                                       {:ok 1 :mode :async :job-id "job-x"})]
        (let [execute (get-in @state [:tools "agent" :execute])
              _       (execute {"action" "create"
                                "task" "x"
                                "timeout_ms" "1234"
                                "fork_session" true
                                "include_result_in_context" true}
                               {:tool-call-id "tc-timeout-1"})]
          (is (= 1234 (:timeout-ms @captured)))
          (is (= :async (:mode @captured)))
          (is (= true (:fork-session? @captured)))
          (is (= true (:include-result-in-context? @captured)))
          (is (= "tc-timeout-1" (:tool-call-id @captured)))))))

  (testing "create uses default timeout when timeout_ms omitted"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})
          captured (atom nil)]
      (sut/init api)
      (with-redefs [sut/spawn-agent! (fn [_task _agent opts]
                                       (reset! captured opts)
                                       {:ok 1 :mode :async :job-id "job-x"})]
        (let [execute (get-in @state [:tools "agent" :execute])
              _       (execute {"action" "create"
                                "task" "x"}
                               {:tool-call-id "tc-timeout-default"})]
          (is (= 300000 (:timeout-ms @captured)))
          (is (= :async (:mode @captured)))))))

  (testing "create rejects non-numeric timeout_ms"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])
            result  (execute {"action" "create"
                              "task" "x"
                              "mode" "sync"
                              "timeout_ms" "abc"}
                             {:tool-call-id "tc-timeout-invalid"})]
        (is (= {:content "Error: timeout_ms must be a positive integer."
                :is-error true}
               result))))))

(deftest run-agent-mutation-flow-test
  ;; run-agent creates a child session via mutation, then runs the loop via mutation.
  (testing "run-agent"
    (testing "delegates to create-child-session and run-agent-loop-in-session mutations"
      (let [mutations (atom [])]
        (reset! sut/state {:api {:mutate (fn [op params]
                                           (swap! mutations conj [op params])
                                           (case op
                                             psi.extension/create-child-session
                                             {:psi.agent-session/session-id "child-1"}

                                             psi.extension/run-agent-loop-in-session
                                             {:psi.agent-session/agent-run-ok? true
                                              :psi.agent-session/agent-run-text "done"
                                              :psi.agent-session/agent-run-elapsed-ms 42
                                              :psi.agent-session/agent-run-error-message nil}

                                             {}))}})
        (let [result (sut/run-agent {:config {:session-name "test"
                                              :system-prompt "hi"
                                              :tools []
                                              :thinking-level :off}
                                     :prompt "do thing"
                                     :model {:provider :test :id "m"}})]
          (is (true? (:ok? result)))
          (is (= "done" (:text result)))
          (is (= "child-1" (:session-id result)))
          (is (= 2 (count @mutations)))
          (is (= 'psi.extension/create-child-session (ffirst @mutations)))
          (is (= 'psi.extension/run-agent-loop-in-session (first (second @mutations)))))))

    (testing "reuses existing session-id when provided"
      (let [mutations (atom [])]
        (reset! sut/state {:api {:mutate (fn [op params]
                                           (swap! mutations conj [op params])
                                           {:psi.agent-session/agent-run-ok? true
                                            :psi.agent-session/agent-run-text "ok"
                                            :psi.agent-session/agent-run-elapsed-ms 10
                                            :psi.agent-session/agent-run-error-message nil})}})
        (let [result (sut/run-agent {:config {}
                                     :prompt "test"
                                     :model {:provider :test :id "m"}
                                     :existing-session-id "existing-1"})]
          (is (true? (:ok? result)))
          (is (= "existing-1" (:session-id result)))
          ;; Only run-agent-loop mutation, no create-child
          (is (= 1 (count @mutations)))
          (is (= 'psi.extension/run-agent-loop-in-session (ffirst @mutations))))))))

(deftest slash-agent-args-fork-flag-test
  (testing "parse-agent-args supports --fork and -f before optional agent"
    (is (= {:fork-session? true
            :task "do thing"}
           (#'sut/parse-agent-args "--fork do thing")))
    (is (= {:fork-session? true
            :agent "planner"
            :task "do thing"}
           (#'sut/parse-agent-args "-f @planner do thing")))
    (is (= {:fork-session? false
            :agent "builder"
            :task "ship it"}
           (#'sut/parse-agent-args "@builder ship it")))))

(deftest slash-agent-command-passes-fork-to-spawn-test
  (testing "/agent forwards fork flag and logs [fork] marker"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})
          captured (atom nil)]
      (sut/init api)
      (with-redefs [sut/spawn-agent! (fn [_task _agent opts]
                                       (reset! captured opts)
                                       {:ok 7 :mode :async :job-id "job-7"})]
        (let [handler (get-in @state [:commands "agent" :handler])]
          (handler "--fork @planner investigate")
          (is (= true (:fork-session? @captured)))
          (is (some #(str/includes? % "[fork]") (:log-lines @state))))))))

(deftest include-result-in-context-injection-test
  (testing "emit-result-message injects user+assistant context messages when enabled"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"
                                :query-fn (fn [q]
                                            (case q
                                              [:psi.agent-session/session-entries]
                                              {:psi.agent-session/session-entries []}
                                              {}))})]
      (sut/init api)
      (#'sut/emit-result-message! {:id 5
                                   :prompt "do thing"
                                   :turn-count 2
                                   :ok? true
                                   :elapsed-ms 20
                                   :result-text "answer"
                                   :include-result-in-context? true})
      (let [msgs (:messages @state)]
        (is (= 2 (count msgs)))
        (is (nil? (:custom-type (first msgs))))
        (is (= "user" (:role (first msgs))))
        (is (str/includes? (:content (first msgs)) "Agent job id: agent-5-turn-2"))
        (is (= "assistant" (:role (second msgs))))
        (is (= "answer" (:content (second msgs)))))))

  (testing "emit-result-message inserts assistant bridge when last role is user"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"})]
      (sut/init api)
      (with-redefs [sut/context-last-role (fn [_] "user")]
        (#'sut/emit-result-message! {:id 6
                                     :prompt "do thing"
                                     :turn-count 1
                                     :ok? true
                                     :elapsed-ms 20
                                     :result-text "answer"
                                     :include-result-in-context? true}))
      (let [msgs (:messages @state)]
        (is (= "(agent context bridge)" (:content (first msgs))))
        (is (= "assistant" (:role (first msgs))))
        (is (= "user" (:role (second msgs))))
        (is (= "assistant" (:role (nth msgs 2))))))))

(deftest agent-action-line-test
  (testing "completed/errored agent rows expose clickable remove action"
    (let [wf {:psi.extension.workflow/id "7"
              :psi.extension.workflow/running? false
              :psi.extension.workflow/done? true
              :psi.extension.workflow/error? false}
          line (#'sut/widget-action-line wf)]
      (is (map? line))
      (is (str/includes? (:text line) "✕ remove"))
      (is (str/includes? (:text line) "[your prompt]"))
      (is (not (str/includes? (:text line) "<prompt>")))
      (is (= "/agent-rm 7" (get-in line [:action :command])))))

  (testing "running agent rows do not expose remove action"
    (let [wf {:psi.extension.workflow/id "7"
              :psi.extension.workflow/running? true
              :psi.extension.workflow/done? false
              :psi.extension.workflow/error? false}]
      (is (nil? (#'sut/widget-action-line wf))))))

(deftest agent-public-data-detail-lines-test
  (testing "widget detail prefers workflow public last-line"
    (let [wf {:psi.extension.workflow/id "8"
              :psi.extension.workflow/running? false
              :psi.extension.workflow/done? true
              :psi.extension.workflow/error? false
              :psi.extension.workflow/data {:agent/last-line "final useful line"
                                            :agent/last-text "ignored\nbody"
                                            :agent/display {:top-line "✓ public top"
                                                            :detail-line "  ↳ final useful line"}}}
          line (#'sut/widget-detail-line wf)
          lines (#'sut/widget-lines wf)]
      (is (= "  ↳ final useful line" line))
      (is (= "✓ public top" (first lines)))
      (is (some #{"  ↳ final useful line"} lines))))

  (testing "widget detail prefers workflow public error-line"
    (let [wf {:psi.extension.workflow/id "9"
              :psi.extension.workflow/running? false
              :psi.extension.workflow/done? false
              :psi.extension.workflow/error? true
              :psi.extension.workflow/error-message "Error: fallback"
              :psi.extension.workflow/data {:agent/error-line "clean failure"
                                            :agent/display {:top-line "✗ public err"
                                                            :detail-line "  ! clean failure"}}}
          line (#'sut/widget-detail-line wf)
          lines (#'sut/widget-lines wf)]
      (is (= "  ! clean failure" line))
      (is (= "✗ public err" (first lines)))
      (is (some #{"  ! clean failure"} lines)))))

(deftest agent-widget-placement-follows-ui-type-test
  (testing "widgets render below editor in emacs ui"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/agent.clj"
                                :ui-type :emacs})]
      (sut/init api)
      (let [execute (get-in @state [:tools "agent" :execute])]
        (execute {"action" "create" "task" "test task"})
        (is (= :below-editor (get-in @state [:widgets "agent-1" :position])))))))

;;; Public boundary function tests

(deftest load-agent-defs-test
  ;; Tests the public boundary for loading agent definitions from a directory.
  ;; Contracts: returns map keyed by lowercase name, includes :name :description :tools :system-prompt.
  (testing "load-agent-defs"
    (testing "returns empty map for nil directory"
      (is (= {} (sut/load-agent-defs nil))))

    (testing "returns empty map for nonexistent directory"
      (is (= {} (sut/load-agent-defs "/nonexistent/path"))))

    (testing "loads agent definitions from markdown files"
      (with-test-agents-dir
        {"planner.md" "---\nname: Planner\ndescription: Plans\ntools: read,bash\n---\nYou plan."
         "builder.md" "---\nname: Builder\n---\nYou build."}
        (fn []
          (let [agents-dir (str (System/getProperty "user.dir") "/.psi/agents")
                defs       (sut/load-agent-defs agents-dir)]
            (is (= #{"planner" "builder"} (set (keys defs))))
            (is (= "planner" (:name (get defs "planner"))))
            (is (= "Plans" (:description (get defs "planner"))))
            (is (= "read,bash" (:tools (get defs "planner"))))
            (is (= "You plan." (:system-prompt (get defs "planner"))))
            (is (nil? (:tools (get defs "builder"))))
            (is (= "You build." (:system-prompt (get defs "builder"))))))))

    (testing "skips files without name in frontmatter"
      (with-test-agents-dir
        {"no-name.md" "---\ndescription: orphan\n---\nNo name here."}
        (fn []
          (let [agents-dir (str (System/getProperty "user.dir") "/.psi/agents")]
            (is (= {} (sut/load-agent-defs agents-dir)))))))))

(deftest resolve-agent-config-test
  ;; Tests the public boundary for resolving agent configuration.
  ;; Contracts: pure function, returns config map with :session-name :system-prompt :tools.
  (testing "resolve-agent-config"
    (testing "returns base prompt when agent-name is nil"
      (let [config (sut/resolve-agent-config nil nil "base prompt")]
        (is (= "base prompt" (:system-prompt config)))
        (is (nil? (:session-name config)))
        (is (vector? (:tools config)))))

    (testing "composes agent profile into system prompt"
      (with-test-agents-dir
        {"planner.md" "---\nname: Planner\ntools: read\n---\nYou plan."}
        (fn []
          (let [agents-dir (str (System/getProperty "user.dir") "/.psi/agents")
                config     (sut/resolve-agent-config "planner" agents-dir "base")]
            (is (= "planner" (:session-name config)))
            (is (str/includes? (:system-prompt config) "base"))
            (is (str/includes? (:system-prompt config) "[Agent Profile: planner]"))
            (is (str/includes? (:system-prompt config) "You plan."))
            (is (= 1 (count (:tools config))))
            (is (= "read" (:name (first (:tools config)))))))))

    (testing "uses default tools when agent has no tools field"
      (with-test-agents-dir
        {"builder.md" "---\nname: Builder\n---\nYou build."}
        (fn []
          (let [agents-dir (str (System/getProperty "user.dir") "/.psi/agents")
                config     (sut/resolve-agent-config "builder" agents-dir "base")]
            (is (= #{"read" "bash" "edit" "write"}
                   (set (map :name (:tools config)))))))))))

(deftest run-agent-test
  ;; Tests the public run-agent boundary via mutation mocking.
  ;; Covered more thoroughly in run-agent-mutation-flow-test above.
  (testing "run-agent"
    (testing "returns error when no mutate-fn available"
      (reset! sut/state {:api {}})
      (let [result (sut/run-agent {:config {:tools [] :system-prompt "hi"}
                                   :prompt "test"
                                   :model {:provider :test :id "m"}})]
        (is (false? (:ok? result)))
        (is (str/includes? (:text result) "Error"))))))
