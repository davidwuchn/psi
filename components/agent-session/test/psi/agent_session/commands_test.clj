(ns psi.agent-session.commands-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.extensions :as ext]
   [psi.agent-core.core :as agent]
   [psi.memory.core :as memory]
   [psi.recursion.core :as recursion]))

;; ── Test helper ─────────────────────────────────────────────

(defn- make-test-ctx
  "Create a minimal session context for testing commands."
  ([] (make-test-ctx {}))
  ([opts]
   (session/create-context
    {:initial-session (merge {:model {:provider "anthropic"
                                      :id       "test-model"
                                      :reasoning false}
                              :system-prompt "test prompt"}
                             opts)
     :persist? false})))

(def ^:private test-ai-model
  {:provider :anthropic :id "test-model" :name "Test"})

(def ^:private cmd-opts
  {:oauth-ctx nil :ai-model test-ai-model})

(defn- with-recursion-ctx
  [ctx]
  (let [rctx (recursion/create-context)]
    (recursion/register-hooks-in! rctx)
    (assoc ctx :recursion-ctx rctx)))

(defn- with-ready-memory-ctx
  [ctx]
  (assoc ctx :memory-ctx
         (memory/create-context {:state-overrides {:status :ready}})))

(defn- with-unready-memory-ctx
  [ctx]
  (assoc ctx :memory-ctx
         (memory/create-context {:state-overrides {:status :initializing}})))

;; ── dispatch tests ──────────────────────────────────────────

(deftest dispatch-quit-test
  (let [ctx (make-test-ctx)]
    (is (= {:type :quit} (commands/dispatch ctx "/quit" cmd-opts)))
    (is (= {:type :quit} (commands/dispatch ctx "/exit" cmd-opts)))))

(deftest dispatch-new-session-test
  (let [ctx (make-test-ctx)
        _   (session/new-session-in! ctx)
        old-id (:session-id (session/get-session-data-in ctx))
        result (commands/dispatch ctx "/new" cmd-opts)]
    (is (= :new-session (:type result)))
    (is (string? (:message result)))
    ;; Session ID should have changed
    (is (not= old-id (:session-id (session/get-session-data-in ctx))))))

(deftest dispatch-new-session-uses-callback-when-provided-test
  (let [ctx (make-test-ctx)
        called? (atom 0)
        result (commands/dispatch ctx
                                  "/new"
                                  (assoc cmd-opts
                                         :on-new-session!
                                         (fn []
                                           (swap! called? inc)
                                           {:messages [{:role :assistant :text "startup"}]
                                            :tool-calls {"call-1" {:name "read"}}
                                            :tool-order ["call-1"]}))) ]
    (is (= :new-session (:type result)))
    (is (= 1 @called?))
    (is (= [{:role :assistant :text "startup"}]
           (get-in result [:rehydrate :messages])))
    (is (= ["call-1"]
           (get-in result [:rehydrate :tool-order])))))

(deftest dispatch-resume-test
  (let [ctx (make-test-ctx)]
    (is (= {:type :resume} (commands/dispatch ctx "/resume" cmd-opts)))))

(deftest dispatch-status-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/status" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Session status"))
    (is (str/includes? (:message result) "Phase"))))

(deftest dispatch-history-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/history" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Message history"))
    (is (str/includes? (:message result) "(empty)"))))

(deftest dispatch-help-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/help" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "/quit"))
    (is (str/includes? (:message result) "/new"))
    (is (str/includes? (:message result) "/login")))
  (testing "/? is an alias for /help"
    (let [ctx (make-test-ctx)]
      (is (= :text (:type (commands/dispatch ctx "/?" cmd-opts)))))))

(deftest dispatch-prompts-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/prompts" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Prompt Templates"))))

(deftest dispatch-skills-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/skills" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Skills"))))

(deftest dispatch-model-no-arg-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/model" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Current model:"))))

(deftest dispatch-model-set-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/model openai gpt-5.3-codex" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "✓ Model set to"))
    (is (= "openai" (get-in (session/get-session-data-in ctx) [:model :provider])))
    (is (= "gpt-5.3-codex" (get-in (session/get-session-data-in ctx) [:model :id])))))

(deftest dispatch-model-invalid-arity-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/model openai" cmd-opts)]
    (is (= :text (:type result)))
    (is (= "Usage: /model OR /model <provider> <model-id>" (:message result)))))

(deftest dispatch-model-unknown-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/model openai no-such-model" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Unknown model:"))))

(deftest dispatch-thinking-no-arg-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/thinking" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Current thinking level:"))))

(deftest dispatch-thinking-set-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/thinking high" cmd-opts)]
    (is (= :text (:type result)))
    ;; test model in fixture has reasoning false, so level clamps to :off
    (is (str/includes? (:message result) "✓ Thinking level set to off"))
    (is (= :off (:thinking-level (session/get-session-data-in ctx))))))

(deftest dispatch-thinking-unknown-level-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/thinking turbo" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Unknown thinking level: turbo"))
    (is (str/includes? (:message result) "Allowed:"))))

(deftest dispatch-thinking-invalid-arity-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/thinking high extra" cmd-opts)]
    (is (= :text (:type result)))
    (is (= "Usage: /thinking OR /thinking <level>" (:message result)))))

(deftest dispatch-feed-forward-without-recursion-ctx-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/feed-forward" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "not configured"))))

(deftest dispatch-feed-forward-accepted-test
  (let [ctx    (-> (make-test-ctx)
                   with-recursion-ctx
                   with-ready-memory-ctx)
        result (commands/dispatch ctx "/feed-forward verify runtime" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "trigger accepted"))
    (is (str/includes? (:message result) "feed-forward-manual-trigger"))
    (is (str/includes? (:message result) "cycle-id:"))
    (is (str/includes? (:message result) "awaiting approval"))))

(deftest dispatch-feed-forward-busy-test
  (let [ctx  (-> (make-test-ctx)
                 with-recursion-ctx
                 with-ready-memory-ctx)
        _    (commands/dispatch ctx "/feed-forward first" cmd-opts)
        busy (commands/dispatch ctx "/feed-forward second" cmd-opts)]
    (is (= :text (:type busy)))
    (is (str/includes? (:message busy) "trigger rejected"))
    (is (str/includes? (:message busy) "controller-busy"))))

(deftest dispatch-feed-forward-blocked-when-live-readiness-fails-test
  (let [ctx    (-> (make-test-ctx)
                   with-recursion-ctx
                   with-unready-memory-ctx)
        result (commands/dispatch ctx "/feed-forward check live readiness" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "trigger blocked"))
    (is (str/includes? (:message result) "recursion_prerequisites_not_ready"))))

(deftest dispatch-feed-forward-approve-current-cycle-test
  (let [ctx     (-> (make-test-ctx)
                    with-recursion-ctx
                    with-ready-memory-ctx)
        _       (commands/dispatch ctx "/feed-forward ship step" cmd-opts)
        result  (commands/dispatch ctx "/feed-forward approve looks good" cmd-opts)
        rctx    (:recursion-ctx ctx)
        state   (recursion/get-state-in rctx)
        cycle   (last (:cycles state))]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "cycle approved"))
    (is (str/includes? (:message result) "cycle-status: completed"))
    (is (str/includes? (:message result) "outcome: success"))
    (is (= :idle (:status state)))
    (is (= :completed (:status cycle)))
    (is (= :success (get-in cycle [:outcome :status])))))

(deftest dispatch-feed-forward-reject-current-cycle-test
  (let [ctx     (-> (make-test-ctx)
                    with-recursion-ctx
                    with-ready-memory-ctx)
        _       (commands/dispatch ctx "/feed-forward evaluate risk" cmd-opts)
        result  (commands/dispatch ctx "/feed-forward reject not now" cmd-opts)
        rctx    (:recursion-ctx ctx)
        state   (recursion/get-state-in rctx)
        cycle   (last (:cycles state))]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "cycle rejected"))
    (is (str/includes? (:message result) "cycle-status: failed"))
    (is (str/includes? (:message result) "outcome: aborted"))
    (is (= :idle (:status state)))
    (is (= :failed (:status cycle)))
    (is (= :aborted (get-in cycle [:outcome :status])))))

(deftest dispatch-feed-forward-continue-without-active-cycle-test
  (let [ctx    (-> (make-test-ctx)
                   with-recursion-ctx
                   with-ready-memory-ctx)
        result (commands/dispatch ctx "/feed-forward continue" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "No active feed-forward cycle"))))

(deftest dispatch-not-a-command-test
  (testing "plain text returns nil"
    (let [ctx (make-test-ctx)]
      (is (nil? (commands/dispatch ctx "hello world" cmd-opts)))))
  (testing "skill invocation returns nil (handled by agent)"
    (let [ctx (make-test-ctx)]
      (is (nil? (commands/dispatch ctx "/skill:foo" cmd-opts)))))
  (testing "prompt template returns nil (handled by agent)"
    (let [ctx (make-test-ctx)]
      (is (nil? (commands/dispatch ctx "/my-template" cmd-opts))))))

(deftest dispatch-login-no-oauth-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/login" {:oauth-ctx nil :ai-model test-ai-model})]
    (is (= :login-error (:type result)))))

(deftest dispatch-logout-no-oauth-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/logout" {:oauth-ctx nil :ai-model test-ai-model})]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "not available"))))

(deftest dispatch-extension-cmd-test
  (let [ctx       (make-test-ctx)
        reg       (:extension-registry ctx)
        called    (atom nil)
        handler   (fn [args] (reset! called args))
        _         (ext/register-extension-in! reg "test-ext")
        _         (ext/register-command-in! reg
                                            "test-ext"
                                            {:name "test-cmd"
                                             :description "A test command"
                                             :handler handler})
        result    (commands/dispatch ctx "/test-cmd some args" cmd-opts)]
    (is (= :extension-cmd (:type result)))
    (is (= "test-cmd" (:name result)))
    (is (= "some args" (:args result)))
    (is (fn? (:handler result)))))

;; ── format-* tests ──────────────────────────────────────────

(deftest format-status-test
  (let [ctx (make-test-ctx)
        s   (commands/format-status ctx)]
    (is (str/includes? s "Phase"))
    (is (str/includes? s "idle"))
    (is (str/includes? s "Roots"))
    (is (str/includes? s "agent-session-ctx"))))

(deftest format-history-empty-test
  (let [ctx (make-test-ctx)
        s   (commands/format-history ctx)]
    (is (str/includes? s "(empty)"))))

(deftest format-history-with-messages-test
  (let [ctx (make-test-ctx)]
    (agent/append-message-in! (:agent-ctx ctx)
                              {:role "user" :content [{:type :text :text "hello"}]})
    (let [s (commands/format-history ctx)]
      (is (str/includes? s "[user]"))
      (is (str/includes? s "hello")))))

(deftest format-help-includes-all-commands-test
  (let [ctx (make-test-ctx)
        s   (commands/format-help ctx)]
    (doseq [cmd ["/quit" "/status" "/history" "/new" "/resume"
                 "/login" "/logout" "/feed-forward" "/help" "/prompts" "/skills"
                 "/feed-forward approve" "/feed-forward reject" "/feed-forward continue"]]
      (is (str/includes? s cmd) (str "help should mention " cmd)))))

(deftest format-prompts-none-test
  (let [ctx (make-test-ctx)
        s   (commands/format-prompts ctx)]
    (is (str/includes? s "(none discovered)"))))

(deftest format-skills-none-test
  (let [ctx (make-test-ctx)
        s   (commands/format-skills ctx)]
    (is (str/includes? s "(none discovered)"))))

