(ns extensions.subagent-widget-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [extensions.subagent-widget :as sut]
   [psi.agent-session.executor :as executor]
   [psi.extension-test-helpers.nullable-api :as nullable]))

(def expected-tool-names
  #{"subagent"})

(def expected-command-names
  #{"sub" "subcont" "subrm" "subclear" "sublist"})

(defn with-test-agents-dir
  [agent-files f]
  (let [tmp-dir    (java.nio.file.Files/createTempDirectory "subagent-widget-test"
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
  (testing "subagent widget registers workflow type, tools, commands, and lifecycle handler"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (is (contains? (:workflow-types @state) :subagent))
      (is (= expected-tool-names
             (set (keys (:tools @state)))))
      (is (= expected-command-names
             (set (keys (:commands @state)))))
      (is (= 1 (count (get-in @state [:handlers "session_switch"]))))
      (is (= "subagent-widget loaded (workflow runtime, ui=console)"
             (-> @state :notifications last :text)))
      (is (= 1 (count (:prompt-contributions @state))))
      (is (contains? (:prompt-contributions @state)
                     ["/test/subagent_widget.clj" "subagent-widget-capabilities"])))))

(deftest tool-validation-test
  (testing "subagent validates missing action"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: action must be one of create, continue, remove, list."
                :is-error true}
               (execute {}))))))

  (testing "subagent create validates empty task"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: task is required." :is-error true}
               (execute {"action" "create"}))))))

  (testing "subagent list returns empty-state summary"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "No active subagents." :is-error false}
               (execute {"action" "list"}))))))

  (testing "subagent list reuses workflow display lines when present"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (swap! state assoc-in [:workflows "8"]
             {:psi.extension/path "/test/subagent_widget.clj"
              :psi.extension.workflow/id "8"
              :psi.extension.workflow/type :subagent
              :psi.extension.workflow/done? true
              :psi.extension.workflow/running? false
              :psi.extension.workflow/error? false
              :psi.extension.workflow/data {:subagent/display {:top-line "✓ public top"
                                                               :detail-line "  ↳ final useful line"}}})
      (let [execute (get-in @state [:tools "subagent" :execute])
            result  (execute {"action" "list"})]
        (is (false? (:is-error result)))
        (is (str/includes? (:content result) "✓ public top"))
        (is (str/includes? (:content result) "↳ final useful line")))))

  (testing "subagent create rejects unknown agent"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: Unknown agent 'not-real'." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "agent" "not-real"}))))))

  (testing "subagent continue rejects mode parameter"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: mode is only supported for action=create" :is-error true}
               (execute {"action" "continue"
                         "id" 1
                         "prompt" "next"
                         "mode" "async"}))))))

  (testing "subagent create validates mode value"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: mode must be one of sync, async" :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "mode" "bogus"}))))))

  (testing "subagent create validates timeout_ms"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: timeout_ms must be a positive integer." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "mode" "sync"
                         "timeout_ms" 0}))))))

  (testing "subagent create validates fork_session"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: fork_session must be true or false." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "fork_session" "maybe"}))))))

  (testing "subagent continue rejects fork_session parameter"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: fork_session is only supported for action=create" :is-error true}
               (execute {"action" "continue"
                         "id" 1
                         "prompt" "next"
                         "fork_session" true}))))))

  (testing "subagent create validates include_result_in_context"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (is (= {:content "Error: include_result_in_context must be true or false." :is-error true}
               (execute {"action" "create"
                         "task" "do thing"
                         "include_result_in_context" "maybe"}))))))

  (testing "subagent continue validates include_result_in_context"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
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
                                   {:path "/test/subagent_widget.clj"
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
                                        ["/test/subagent_widget.clj" "subagent-widget-capabilities"]
                                        :content])]
            (is (str/includes? contrib "tool: subagent"))
            (is (str/includes? contrib "available agents:"))
            (is (str/includes? contrib "- planner: plan"))
            (is (str/includes? contrib "- builder: build"))))))))

(deftest create-mode-behavior-test
  (testing "create defaults to async and returns background job id"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])
            result  (execute {"action" "create" "task" "test task"} {:tool-call-id "tc-create-1"})]
        (is (false? (:is-error result)))
        (is (str/includes? (:content result) "spawned in background"))
        (is (str/includes? (:content result) "job job-1")))))

  (testing "create sync returns terminal output without job id"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (with-redefs [sut/await-terminal-workflow (fn [_id _timeout]
                                                  {:workflow {:psi.extension.workflow/error? false
                                                              :psi.extension.workflow/result "sync ok"
                                                              :psi.extension.workflow/data {:subagent/elapsed-ms 12}}})]
        (let [execute (get-in @state [:tools "subagent" :execute])
              result  (execute {"action" "create" "task" "sync task" "mode" "sync"}
                               {:tool-call-id "tc-create-sync"})]
          (is (false? (:is-error result)))
          (is (str/includes? (:content result) "Subagent #1 finished"))
          (is (str/includes? (:content result) "sync ok"))
          (is (not (str/includes? (:content result) "job ")))))))

  (testing "create sync error returns is-error true and terminal text"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (with-redefs [sut/await-terminal-workflow (fn [_id _timeout]
                                                  {:workflow {:psi.extension.workflow/error? true
                                                              :psi.extension.workflow/error-message "boom"
                                                              :psi.extension.workflow/data {:subagent/elapsed-ms 20}}})]
        (let [execute (get-in @state [:tools "subagent" :execute])
              result  (execute {"action" "create" "task" "sync task" "mode" "sync"}
                               {:tool-call-id "tc-create-sync-err"})]
          (is (true? (:is-error result)))
          (is (str/includes? (:content result) "boom")))))))

(deftest execute-subagent-tool-timeout-test
  (testing "create passes parsed timeout_ms through to spawn-subagent"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})
          captured (atom nil)]
      (sut/init api)
      (with-redefs [sut/spawn-subagent! (fn [_task _agent opts]
                                          (reset! captured opts)
                                          {:ok 1 :mode :async :job-id "job-x"})]
        (let [execute (get-in @state [:tools "subagent" :execute])
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
                               {:path "/test/subagent_widget.clj"})
          captured (atom nil)]
      (sut/init api)
      (with-redefs [sut/spawn-subagent! (fn [_task _agent opts]
                                          (reset! captured opts)
                                          {:ok 1 :mode :async :job-id "job-x"})]
        (let [execute (get-in @state [:tools "subagent" :execute])
              _       (execute {"action" "create"
                                "task" "x"}
                               {:tool-call-id "tc-timeout-default"})]
          (is (= 300000 (:timeout-ms @captured)))
          (is (= :async (:mode @captured)))))))

  (testing "create rejects non-numeric timeout_ms"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])
            result  (execute {"action" "create"
                              "task" "x"
                              "mode" "sync"
                              "timeout_ms" "abc"}
                             {:tool-call-id "tc-timeout-invalid"})]
        (is (= {:content "Error: timeout_ms must be a positive integer."
                :is-error true}
               result))))))

(deftest run-subagent-job-executor-arg-order-test
  (testing "run-subagent-job passes executor args in run-agent-loop order"
    (let [captured          (atom nil)
          fake-agent-ctx    {:fake :agent-ctx}
          fake-session-ctx  {:agent-ctx fake-agent-ctx
                             :session-data-atom (atom {:tool-output-overrides {}})
                             :tool-output-stats-atom (atom {:calls []
                                                            :aggregates {:total-context-bytes 0
                                                                         :by-tool {}
                                                                         :limit-hits-by-tool {}}})}
          fake-model        {:provider "anthropic" :id "sonnet"}]
      (with-redefs [sut/resolve-active-model (fn [_] fake-model)
                    executor/run-agent-loop! (fn [& args]
                                               (reset! captured args)
                                               {:role "assistant"
                                                :stop-reason :stop
                                                :content [{:type :text :text "ok"}]})]
        (let [result (#'sut/run-subagent-job
                      {:agent-ctx      fake-agent-ctx
                       :session-ctx    fake-session-ctx
                       :prompt         "do thing"
                       :query-fn       (fn [_] nil)
                       :get-api-key-fn (fn [_] "test-api-key")})]
          (is (:ok? result))
          (is (= 6 (count @captured)))
          (is (nil? (nth @captured 0)))
          (is (= fake-session-ctx (nth @captured 1)))
          (is (= fake-agent-ctx (nth @captured 2)))
          (is (= fake-model (nth @captured 3)))
          (is (= "user" (get-in (nth @captured 4) [0 :role])))
          (is (= "do thing" (get-in (nth @captured 4) [0 :content 0 :text])))
          (is (= {:api-key "test-api-key"} (nth @captured 5))))))))

(deftest run-subagent-job-falls-back-to-created-session-ctx-test
  (testing "run-subagent-job creates a session ctx when missing"
    (let [captured          (atom nil)
          created           (atom nil)
          fake-agent-ctx    {:fake :agent-ctx}
          fake-session-ctx  {:agent-ctx fake-agent-ctx
                             :session-data-atom (atom {:tool-output-overrides {}})
                             :tool-output-stats-atom (atom {:calls []
                                                            :aggregates {:total-context-bytes 0
                                                                         :by-tool {}
                                                                         :limit-hits-by-tool {}}})}
          fake-model        {:provider "anthropic" :id "sonnet"}
          qf                (fn [_] nil)]
      (with-redefs [sut/resolve-active-model (fn [_] fake-model)
                    sut/create-sub-session-ctx (fn [agent-ctx query-fn]
                                                 (reset! created [agent-ctx query-fn])
                                                 fake-session-ctx)
                    executor/run-agent-loop! (fn [& args]
                                               (reset! captured args)
                                               {:role "assistant"
                                                :stop-reason :stop
                                                :content [{:type :text :text "ok"}]})]
        (let [result (#'sut/run-subagent-job
                      {:agent-ctx      fake-agent-ctx
                       :prompt         "fallback"
                       :query-fn       qf
                       :get-api-key-fn (fn [_] nil)})]
          (is (:ok? result))
          (is (= [fake-agent-ctx qf] @created))
          (is (= fake-session-ctx (nth @captured 1))))))))

(deftest slash-sub-args-fork-flag-test
  (testing "parse-sub-args supports --fork and -f before optional agent"
    (is (= {:fork-session? true
            :task "do thing"}
           (#'sut/parse-sub-args "--fork do thing")))
    (is (= {:fork-session? true
            :agent "planner"
            :task "do thing"}
           (#'sut/parse-sub-args "-f @planner do thing")))
    (is (= {:fork-session? false
            :agent "builder"
            :task "ship it"}
           (#'sut/parse-sub-args "@builder ship it")))))

(deftest slash-sub-command-passes-fork-to-spawn-test
  (testing "/sub forwards fork flag and prints [fork] marker"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})
          captured (atom nil)
          printed  (atom nil)]
      (sut/init api)
      (with-redefs [sut/spawn-subagent! (fn [_task _agent opts]
                                          (reset! captured opts)
                                          {:ok 7 :mode :async :job-id "job-7"})
                    println (fn [& xs] (reset! printed (apply str xs)))]
        (let [handler (get-in @state [:commands "sub" :handler])]
          (handler "--fork @planner investigate")
          (is (= true (:fork-session? @captured)))
          (is (str/includes? @printed "[fork]")))))))

(deftest include-result-in-context-injection-test
  (testing "emit-result-message injects user+assistant context messages when enabled"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"
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
        (is (str/includes? (:content (first msgs)) "Subagent job id: subagent-5-turn-2"))
        (is (= "assistant" (:role (second msgs))))
        (is (= "answer" (:content (second msgs)))))))

  (testing "emit-result-message inserts assistant bridge when last role is user"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"})]
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
        (is (= "(subagent context bridge)" (:content (first msgs))))
        (is (= "assistant" (:role (first msgs))))
        (is (= "user" (:role (second msgs))))
        (is (= "assistant" (:role (nth msgs 2))))))))

(deftest subagent-widget-action-line-test
  (testing "completed/errored subagent rows expose clickable remove action"
    (let [wf {:psi.extension.workflow/id "7"
              :psi.extension.workflow/running? false
              :psi.extension.workflow/done? true
              :psi.extension.workflow/error? false}
          line (#'sut/widget-action-line wf)]
      (is (map? line))
      (is (str/includes? (:text line) "✕ remove"))
      (is (= "/subrm 7" (get-in line [:action :command])))))

  (testing "running subagent rows do not expose remove action"
    (let [wf {:psi.extension.workflow/id "7"
              :psi.extension.workflow/running? true
              :psi.extension.workflow/done? false
              :psi.extension.workflow/error? false}]
      (is (nil? (#'sut/widget-action-line wf))))))

(deftest subagent-public-data-detail-lines-test
  (testing "widget detail prefers workflow public last-line"
    (let [wf {:psi.extension.workflow/id "8"
              :psi.extension.workflow/running? false
              :psi.extension.workflow/done? true
              :psi.extension.workflow/error? false
              :psi.extension.workflow/data {:subagent/last-line "final useful line"
                                            :subagent/last-text "ignored\nbody"
                                            :subagent/display {:top-line "✓ public top"
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
              :psi.extension.workflow/data {:subagent/error-line "clean failure"
                                            :subagent/display {:top-line "✗ public err"
                                                               :detail-line "  ! clean failure"}}}
          line (#'sut/widget-detail-line wf)
          lines (#'sut/widget-lines wf)]
      (is (= "  ! clean failure" line))
      (is (= "✗ public err" (first lines)))
      (is (some #{"  ! clean failure"} lines)))))

(deftest subagent-widget-placement-follows-ui-type-test
  (testing "widgets render below editor in emacs ui"
    (let [{:keys [api state]} (nullable/create-nullable-extension-api
                               {:path "/test/subagent_widget.clj"
                                :ui-type :emacs})]
      (sut/init api)
      (let [execute (get-in @state [:tools "subagent" :execute])]
        (execute {"action" "create" "task" "test task"})
        (is (= :below-editor (get-in @state [:widgets "sub-1" :position])))))))
