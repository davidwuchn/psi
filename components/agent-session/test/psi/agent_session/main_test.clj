(ns psi.agent-session.main-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.main :as main]
   [psi.agent-session.oauth.core :as oauth]
   [psi.agent-session.prompt-templates :as pt]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.rpc :as rpc]
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as sys-prompt]
   [psi.introspection.core :as introspection]
   [psi.memory.runtime :as memory-runtime]
   [psi.tui.app :as tui-app]))

(deftest select-login-provider-test
  (let [providers [{:id :anthropic :name "Anthropic"}
                   {:id :openai :name "OpenAI"}]]

    (testing "defaults to active model provider when no explicit provider arg"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai nil)]
        (is (nil? error))
        (is (= :openai (:id provider)))))

    (testing "explicit provider arg overrides active provider"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai "anthropic")]
        (is (nil? error))
        (is (= :anthropic (:id provider)))))

    (testing "returns clear error for unknown explicit provider"
      (let [{:keys [provider error]}
            (commands/select-login-provider providers :openai "not-a-provider")]
        (is (nil? provider))
        (is (str/includes? error "Unknown OAuth provider"))
        (is (str/includes? error "anthropic"))
        (is (str/includes? error "openai"))))))

(deftest select-login-provider-missing-active-provider-test
  (testing "does not silently fall back to another provider"
    (let [providers [{:id :anthropic :name "Anthropic"}]
          {:keys [provider error]}
          (commands/select-login-provider providers :openai nil)]
      (is (nil? provider))
      (is (str/includes? error "not available for model provider openai"))
      (is (str/includes? error "OPENAI_API_KEY"))
      (is (str/includes? error "anthropic")))))

(defn- with-main-bootstrap-stubs
  [f]
  (with-redefs [psi.agent-session.main/resolve-model
                (fn [_]
                  {:provider           :anthropic
                   :id                 "test-model"
                   :name               "Test Model"
                   :supports-reasoning false
                   :context-window     200000})
                oauth/create-context (fn [] nil)
                pt/discover-templates (fn [] [])
                skills/discover-skills (fn [] {:skills [] :diagnostics []})
                sys-prompt/discover-context-files (fn [_] [])
                sys-prompt/build-system-prompt (fn [_] "")
                ext/discover-extension-paths (fn [& _] [])
                session/bootstrap-in!
                (fn [_ctx _]
                  {:extension-errors [] :extension-loaded-count 0})]
    (f)))

(deftest run-session-initializes-session-file-test
  (let [orig-state @main/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [clojure.core/read-line (let [calls (atom 0)]
                                                 (fn []
                                                   (if (= 1 (swap! calls inc))
                                                     "/quit"
                                                     nil)))]
            (main/run-session :ignored)
            (let [ctx (:ctx @main/session-state)
                  sd  (session/get-session-data-in ctx)]
              (is (some? ctx))
              (is (string? (:session-file sd)))
              (is (= :console (:ui-type sd)))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest run-session-journals-command-inputs-test
  (let [orig-state @main/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [clojure.core/read-line (let [calls (atom 0)]
                                                 (fn []
                                                   (case (swap! calls inc)
                                                     1 "/history"
                                                     2 "/quit"
                                                     nil)))]
            (main/run-session :ignored)
            (let [ctx (:ctx @main/session-state)
                  msg-texts (->> @(:journal-atom ctx)
                                 (filter #(= :message (:kind %)))
                                 (map #(get-in % [:data :message :content 0 :text]))
                                 set)]
              (is (contains? msg-texts "/history"))
              (is (contains? msg-texts "/quit"))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest run-tui-session-passes-current-session-file-test
  (let [orig-state @main/session-state
        captured   (atom nil)]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [tui-app/start! (fn [_model-name _run-agent-fn opts]
                                         (reset! captured opts)
                                         :ok)]
            (is (= :ok (main/run-tui-session :ignored)))
            (is (string? (:current-session-file @captured)))
            (is (fn? (:dispatch-fn @captured)))
            (is (fn? (:on-interrupt-fn! @captured)))
            (is (= :tui (:ui-type (session/get-session-data-in (:ctx @main/session-state))))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest run-tui-dispatch-journals-command-input-test
  (let [orig-state @main/session-state]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [tui-app/start! (fn [_model-name _run-agent-fn opts]
                                         ((:dispatch-fn opts) "/history")
                                         :ok)]
            (is (= :ok (main/run-tui-session :ignored)))
            (let [ctx (:ctx @main/session-state)
                  msg-texts (->> @(:journal-atom ctx)
                                 (filter #(= :message (:kind %)))
                                 (map #(get-in % [:data :message :content 0 :text]))
                                 set)]
              (is (contains? msg-texts "/history"))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest run-rpc-session-initializes-session-file-test
  (let [orig-state @main/session-state
        captured   (atom nil)]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [rpc/run-stdio-loop!
                        (fn [opts]
                          (reset! captured opts)
                          :ok)]
            (#'main/run-rpc-edn-session! :ignored)
            (let [ctx (:ctx @main/session-state)
                  sd  (session/get-session-data-in ctx)
                  hs  (:handshake-server-info-fn @(:state @captured))]
              (is (some? ctx))
              (is (string? (:session-file sd)))
              (is (= :emacs (:ui-type sd)))
              (is (fn? hs))
              (is (= :emacs (:ui-type (hs))))
              (is (fn? (:request-handler @captured)))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest run-rpc-session-enables-rpc-trace-config-test
  (let [orig-state @main/session-state
        captured   (atom nil)
        trace-file (str (java.nio.file.Files/createTempFile
                         "psi-rpc-trace-test-"
                         ".ndedn"
                         (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (with-main-bootstrap-stubs
        (fn []
          (with-redefs [rpc/run-stdio-loop!
                        (fn [opts]
                          (reset! captured opts)
                          :ok)]
            (#'main/run-rpc-edn-session! :ignored {} {} {:rpc-trace-file trace-file})
            (let [ctx         (:ctx @main/session-state)
                  trace-state (session/get-state-value-in ctx (session/state-path :rpc-trace))
                  trace-fn    (:trace-fn @captured)]
              (is (fn? trace-fn))
              (is (= true (:enabled? trace-state)))
              (is (= trace-file (:file trace-state)))

              (trace-fn {:dir :in
                         :raw "{:id \"1\" :kind :request :op \"ping\"}"
                         :frame {:id "1" :kind :request :op "ping"}})
              (let [before-disable (count (str/split-lines (slurp trace-file)))]
                (is (pos? before-disable))
                (session/assoc-state-value-in!
                 ctx
                 (session/state-path :rpc-trace)
                 {:enabled? false :file trace-file})
                (trace-fn {:dir :out
                           :raw "{:kind :response :id \"1\" :op \"ping\" :ok true}"
                           :frame {:kind :response :id "1" :op "ping" :ok true}})
                (is (= before-disable
                       (count (str/split-lines (slurp trace-file))))))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest agent-messages->tui-resume-state-rehydrates-tool-rows-test
  (let [messages [{:role "user"
                   :content [{:type :text :text "read file"}]}
                  {:role "assistant"
                   :content [{:type :text :text "Sure"}
                             {:type :tool-call :id "call-1" :name "read"
                              :arguments "{\"path\":\"a.txt\"}"}]}
                  {:role "toolResult"
                   :tool-call-id "call-1"
                   :tool-name "read"
                   :content [{:type :text :text "hello"}
                             {:type :image :mime-type "image/png" :data "<base64>"}]
                   :details {:full-output-path "/tmp/all.log"}
                   :is-error false}
                  {:role "assistant"
                   :content [{:type :text :text "done"}]}]
        {:keys [messages tool-calls tool-order]}
        (#'main/agent-messages->tui-resume-state messages)]
    (is (= [{:role :user :text "read file"}
            {:role :assistant :text "Sure"}
            {:role :assistant :text "done"}]
           messages))
    (is (= ["call-1"] tool-order))
    (is (= "read" (get-in tool-calls ["call-1" :name])))
    (is (= :success (get-in tool-calls ["call-1" :status])))
    (is (= "hello" (get-in tool-calls ["call-1" :result])))
    (is (= {:full-output-path "/tmp/all.log"}
           (get-in tool-calls ["call-1" :details])))))

(deftest agent-messages->tui-resume-state-supports-structured-content-test
  (let [messages [{:role "assistant"
                   :content {:kind :structured
                             :blocks [{:kind :text :text "planning"}
                                      {:kind :tool-call :id "call-2" :name "read" :input {:path "README.md"}}]}}
                  {:role "toolResult"
                   :tool-call-id "call-2"
                   :tool-name "read"
                   :content [{:type :text :text "ok"}]
                   :is-error false}
                  {:role "assistant"
                   :content {:kind :structured
                             :blocks [{:kind :text :text "done"}]}}]
        {:keys [messages tool-calls tool-order]}
        (#'main/agent-messages->tui-resume-state messages)]
    (is (= [{:role :assistant :text "planning"}
            {:role :assistant :text "done"}]
           messages))
    (is (= ["call-2"] tool-order))
    (is (= "read" (get-in tool-calls ["call-2" :name])))
    (is (= "{:path \"README.md\"}"
           (get-in tool-calls ["call-2" :args])))))

(deftest memory-runtime-opts-from-args-test
  (is (= {:store-provider "in-memory"
          :auto-store-fallback? false
          :history-commit-limit 99
          :retention-snapshots 12
          :retention-deltas 34}
         (#'main/memory-runtime-opts-from-args
          ["--memory-store" "in-memory"
           "--memory-store-fallback" "off"
           "--memory-history-limit" "99"
           "--memory-retention-snapshots" "12"
           "--memory-retention-deltas" "34"])))
  (is (= {}
         (#'main/memory-runtime-opts-from-args
          ["--memory-history-limit" "not-a-number"
           "--memory-store-fallback" "maybe"]))))

(deftest session-runtime-config-from-args-test
  (testing "CLI flag sets timeout"
    (is (= {:llm-stream-idle-timeout-ms 90000}
           (#'main/session-runtime-config-from-args
            ["--llm-idle-timeout-ms" "90000"]))))

  (testing "env var is used when CLI flag is absent"
    (with-redefs [main/llm-idle-timeout-ms-from-env (fn [] 42000)]
      (is (= {:llm-stream-idle-timeout-ms 42000}
             (#'main/session-runtime-config-from-args [])))))

  (testing "CLI flag wins over env var"
    (with-redefs [main/llm-idle-timeout-ms-from-env (fn [] 42000)]
      (is (= {:llm-stream-idle-timeout-ms 90000}
             (#'main/session-runtime-config-from-args
              ["--llm-idle-timeout-ms" "90000"])))))

  (testing "invalid CLI value does not fall back to env"
    (with-redefs [main/llm-idle-timeout-ms-from-env (fn [] 42000)]
      (is (= {}
             (#'main/session-runtime-config-from-args
              ["--llm-idle-timeout-ms" "not-a-number"]))))))

(deftest rpc-trace-file-from-args-test
  (is (= "/tmp/rpc-trace.ndedn"
         (#'main/rpc-trace-file-from-args
          ["--rpc-trace-file" "/tmp/rpc-trace.ndedn"])))
  (is (nil? (#'main/rpc-trace-file-from-args
             ["--rpc-trace-file" "   "])))
  (is (nil? (#'main/rpc-trace-file-from-args []))))

(deftest bootstrap-runtime-session-initial-context-index-has-single-session-test
  (with-redefs [oauth/create-context (fn [] nil)
                pt/discover-templates (fn [] [])
                skills/discover-skills (fn [] {:skills [] :diagnostics []})
                sys-prompt/discover-context-files (fn [_] [])
                sys-prompt/build-system-prompt (fn [_] "")
                ext/discover-extension-paths (fn [& _] [])
                introspection/register-resolvers! (fn [] nil)
                memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                session/bootstrap-in!
                (fn [_ctx _]
                  {:extension-errors [] :extension-loaded-count 0})]
    (let [{:keys [ctx]} (#'main/bootstrap-runtime-session!
                         {:provider :anthropic
                          :id "test-model"
                          :name "Test Model"
                          :supports-reasoning false}
                         {})
          index (session/get-context-index-in ctx)
          sd   (session/get-session-data-in ctx)]
      (is (= 1 (count (:sessions index))))
      (is (= (:session-id sd) (:active-session-id index)))
      (is (= [(:session-id sd)] (vec (keys (:sessions index))))))))

(deftest bootstrap-runtime-session-passes-memory-runtime-opts-to-sync-test
  (let [captured (atom nil)]
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  ext/discover-extension-paths (fn [& _] [])
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [opts]
                                                      (reset! captured opts)
                                                      {:ok? true})
                  session/bootstrap-in!
                  (fn [_ctx _]
                    {:extension-errors [] :extension-loaded-count 0})]
      (let [{:keys [ctx]} (#'main/bootstrap-runtime-session!
                           {:provider :anthropic
                            :id "test-model"
                            :name "Test Model"
                            :supports-reasoning false}
                           {:memory-runtime-opts {:store-provider "in-memory"
                                                  :retention-snapshots 22
                                                  :retention-deltas 44}
                            :session-config {:llm-stream-idle-timeout-ms 54321}})]
        (is (= "in-memory" (:store-provider @captured)))
        (is (= 22 (:retention-snapshots @captured)))
        (is (= 44 (:retention-deltas @captured)))
        (is (string? (:cwd @captured)))
        (is (= 54321 (get-in ctx [:config :llm-stream-idle-timeout-ms])))))))

(deftest bootstrap-runtime-session-enriches-system-prompt-with-capabilities-test
  (with-redefs [oauth/create-context (fn [] nil)
                pt/discover-templates (fn [] [])
                skills/discover-skills (fn [] {:skills [] :diagnostics []})
                sys-prompt/discover-context-files (fn [_] [])
                ext/discover-extension-paths (fn [& _] [])
                introspection/register-resolvers! (fn [] nil)
                memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                session/bootstrap-in!
                (fn [ctx _]
                  (session/new-session-in! ctx)
                  {:extension-errors [] :extension-loaded-count 0})]
    (let [{:keys [ctx]} (#'main/bootstrap-runtime-session!
                         {:provider :anthropic
                          :id "test-model"
                          :name "Test Model"
                          :supports-reasoning false}
                         {})
          prompt (:psi.agent-session/system-prompt
                  (session/query-in ctx [:psi.agent-session/system-prompt]))]
      (is (str/includes? prompt "Current capabilities (from :psi.graph/capabilities):"))
      (is (str/includes? prompt "- agent-session (ops=")))))

(deftest bootstrap-runtime-session-wires-nrepl-runtime-atom-test
  (let [orig @main/nrepl-runtime]
    (try
      (reset! main/nrepl-runtime {:host "localhost"
                                  :port 8999
                                  :endpoint "localhost:8999"})
      (with-redefs [oauth/create-context (fn [] nil)
                    pt/discover-templates (fn [] [])
                    skills/discover-skills (fn [] {:skills [] :diagnostics []})
                    sys-prompt/discover-context-files (fn [_] [])
                    sys-prompt/build-system-prompt (fn [_] "")
                    ext/discover-extension-paths (fn [& _] [])
                    introspection/register-resolvers! (fn [] nil)
                    memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                    session/bootstrap-in!
                    (fn [ctx _]
                      (session/new-session-in! ctx)
                      {:extension-errors [] :extension-loaded-count 0})]
        (let [{:keys [ctx]} (#'main/bootstrap-runtime-session!
                             {:provider :anthropic
                              :id "test-model"
                              :name "Test Model"
                              :supports-reasoning false}
                             {})
              result (session/query-in ctx [:psi.runtime/nrepl-host
                                            :psi.runtime/nrepl-port
                                            :psi.runtime/nrepl-endpoint])]
          (is (= "localhost" (:psi.runtime/nrepl-host result)))
          (is (= 8999 (:psi.runtime/nrepl-port result)))
          (is (= "localhost:8999" (:psi.runtime/nrepl-endpoint result)))))
      (finally
        (reset! main/nrepl-runtime orig)))))

(deftest nrepl-runtime-eql-reflects-live-start-stop-test
  (let [orig-runtime @main/nrepl-runtime
        orig-user-dir (System/getProperty "user.dir")
        tmp-dir-file (java.io.File.
                      (str (System/getProperty "java.io.tmpdir")
                           "/psi-main-nrepl-runtime-"
                           (java.util.UUID/randomUUID)))
        _ (.mkdirs tmp-dir-file)
        tmp-dir (.getAbsolutePath tmp-dir-file)]
    (try
      (System/setProperty "user.dir" tmp-dir)
      (let [srv (#'main/start-nrepl! 0)
            cwd (System/getProperty "user.dir")]
        (try
          (is (pos-int? (:port srv)))
          (let [ctx (session/create-context {:persist? false
                                             :cwd cwd
                                             :nrepl-runtime-atom main/nrepl-runtime})
                result (session/query-in ctx [:psi.runtime/nrepl-host
                                              :psi.runtime/nrepl-port
                                              :psi.runtime/nrepl-endpoint])
                expected-endpoint (str (:psi.runtime/nrepl-host result)
                                       ":"
                                       (:psi.runtime/nrepl-port result))]
            (is (= "localhost" (:psi.runtime/nrepl-host result)))
            (is (integer? (:psi.runtime/nrepl-port result)))
            (is (= (:port srv) (:psi.runtime/nrepl-port result)))
            (is (= expected-endpoint (:psi.runtime/nrepl-endpoint result))))
          (finally
            (#'main/stop-nrepl! srv))))
      (let [ctx-after-stop (session/create-context {:persist? false
                                                    :cwd (System/getProperty "user.dir")
                                                    :nrepl-runtime-atom main/nrepl-runtime})
            result-after-stop (session/query-in ctx-after-stop
                                                [:psi.runtime/nrepl-host
                                                 :psi.runtime/nrepl-port
                                                 :psi.runtime/nrepl-endpoint])]
        (is (nil? (:psi.runtime/nrepl-host result-after-stop)))
        (is (nil? (:psi.runtime/nrepl-port result-after-stop)))
        (is (nil? (:psi.runtime/nrepl-endpoint result-after-stop))))
      (finally
        (System/setProperty "user.dir" orig-user-dir)
        (reset! main/nrepl-runtime orig-runtime)))))

(deftest bootstrap-runtime-session-applies-project-preferences-test
  (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-main-project-prefs-" (java.util.UUID/randomUUID))
        _   (.mkdirs (java.io.File. cwd))]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "openai"
      :model-id "gpt-5.3-codex"
      :thinking-level :high})
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  ext/discover-extension-paths (fn [& _] [])
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                  session/bootstrap-in!
                  (fn [ctx _]
                    (session/new-session-in! ctx)
                    {:extension-errors [] :extension-loaded-count 0})]
      (let [{:keys [ctx]} (#'main/bootstrap-runtime-session!
                           {:provider :anthropic
                            :id "claude-sonnet-4-6"
                            :name "Claude Sonnet 4.6"
                            :supports-reasoning true}
                           {:cwd cwd})
            sd (session/get-session-data-in ctx)]
        (is (= "openai" (get-in sd [:model :provider])))
        (is (= "gpt-5.3-codex" (get-in sd [:model :id])))
        (is (= :high (:thinking-level sd)))))))

(deftest bootstrap-runtime-session-invalid-project-model-falls-back-test
  (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-main-project-prefs-" (java.util.UUID/randomUUID))
        _   (.mkdirs (java.io.File. cwd))]
    (project-prefs/update-agent-session!
     cwd
     {:model-provider "nope"
      :model-id "missing"
      :thinking-level :xhigh})
    (with-redefs [oauth/create-context (fn [] nil)
                  pt/discover-templates (fn [] [])
                  skills/discover-skills (fn [] {:skills [] :diagnostics []})
                  sys-prompt/discover-context-files (fn [_] [])
                  sys-prompt/build-system-prompt (fn [_] "")
                  ext/discover-extension-paths (fn [& _] [])
                  introspection/register-resolvers! (fn [] nil)
                  memory-runtime/sync-memory-layer! (fn [_] {:ok? true})
                  session/bootstrap-in!
                  (fn [ctx _]
                    (session/new-session-in! ctx)
                    {:extension-errors [] :extension-loaded-count 0})]
      (let [{:keys [ctx]} (#'main/bootstrap-runtime-session!
                           {:provider :anthropic
                            :id "claude-sonnet-4-6"
                            :name "Claude Sonnet 4.6"
                            :supports-reasoning false}
                           {:cwd cwd})
            sd (session/get-session-data-in ctx)]
        (is (= "anthropic" (get-in sd [:model :provider])))
        (is (= "claude-sonnet-4-6" (get-in sd [:model :id])))
        (is (= :off (:thinking-level sd)))))))
