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
   [psi.agent-session.skills :as skills]
   [psi.agent-session.system-prompt :as sys-prompt]
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

(deftest run-session-initializes-session-file-test
  (let [orig-state @main/session-state]
    (try
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
                    session/bootstrap-session-in!
                    (fn [ctx _]
                      (session/new-session-in! ctx)
                      {:extension-errors [] :extension-loaded-count 0})
                    clojure.core/read-line (let [calls (atom 0)]
                                             (fn []
                                               (if (= 1 (swap! calls inc))
                                                 "/quit"
                                                 nil)))]
        (main/run-session :ignored)
        (let [ctx (:ctx @main/session-state)]
          (is (some? ctx))
          (is (string? (:session-file (session/get-session-data-in ctx))))))
      (finally
        (reset! main/session-state orig-state)))))

(deftest run-tui-session-passes-current-session-file-test
  (let [orig-state @main/session-state
        captured   (atom nil)]
    (try
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
                    session/bootstrap-session-in!
                    (fn [ctx _]
                      (session/new-session-in! ctx)
                      {:extension-errors [] :extension-loaded-count 0})
                    tui-app/start! (fn [_model-name _run-agent-fn opts]
                                     (reset! captured opts)
                                     :ok)]
        (is (= :ok (main/run-tui-session :ignored)))
        (is (string? (:current-session-file @captured)))
        (is (fn? (:dispatch-fn @captured))))
      (finally
        (reset! main/session-state orig-state)))))
