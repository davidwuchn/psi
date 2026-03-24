(ns psi.agent-session.commands-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-session.background-jobs :as bg-jobs]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.commands :as commands]
   [psi.agent-session.core :as session]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.extensions :as ext]
   [psi.agent-core.core :as agent]
   [psi.memory.core :as memory]
   [psi.memory.store :as store]
   [psi.query.core :as query]))

;; ── Test helper ─────────────────────────────────────────────

(defn- temp-cwd []
  (let [p (str (java.nio.file.Files/createTempDirectory
                "psi-agent-session-commands-test-"
                (make-array java.nio.file.attribute.FileAttribute 0)))]
    (.mkdirs (java.io.File. p))
    p))

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
     :cwd (temp-cwd)
     :persist? false})))

(def ^:private test-ai-model
  {:provider :anthropic :id "test-model" :name "Test"})

(def ^:private cmd-opts
  {:oauth-ctx nil
   :ai-model test-ai-model
   :supports-session-tree? true})

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
        old-id (:session-id (ss/get-session-data-in ctx))
        result (commands/dispatch ctx "/new" cmd-opts)]
    (is (= :new-session (:type result)))
    (is (string? (:message result)))
    ;; Session ID should have changed
    (is (not= old-id (:session-id (ss/get-session-data-in ctx))))))

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
                                            :tool-order ["call-1"]})))]
    (is (= :new-session (:type result)))
    (is (= 1 @called?))
    (is (= [{:role :assistant :text "startup"}]
           (get-in result [:rehydrate :messages])))
    (is (= ["call-1"]
           (get-in result [:rehydrate :tool-order])))))

(deftest dispatch-resume-test
  (let [ctx (make-test-ctx)]
    (is (= {:type :resume} (commands/dispatch ctx "/resume" cmd-opts)))))

(deftest dispatch-tree-open-test
  (let [ctx (make-test-ctx)]
    (is (= {:type :tree-open} (commands/dispatch ctx "/tree" cmd-opts)))))

(deftest dispatch-tree-not-supported-test
  (let [ctx (make-test-ctx)
        result (commands/dispatch ctx "/tree" (assoc cmd-opts :supports-session-tree? false))]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "only available in TUI mode"))))

(deftest dispatch-tree-switch-by-id-test
  (let [ctx        (make-test-ctx)
        _          (session/new-session-in! ctx)
        active-id  (:session-id (ss/get-session-data-in ctx))
        result     (commands/dispatch ctx (str "/tree " active-id) cmd-opts)]
    ;; Explicit ids should be parsed as ids, not mistaken for bare /tree.
    (is (string? active-id))
    (is (not= :tree-open (:type result)))))

(deftest dispatch-tree-active-session-id-is-noop-test
  (let [ctx       (make-test-ctx)
        active-id (:session-id (ss/get-session-data-in ctx))
        result    (commands/dispatch ctx (str "/tree " active-id) cmd-opts)]
    (is (string? active-id))
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Already active session"))))

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

(deftest dispatch-worktree-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/worktree" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Git worktrees"))
    (is (str/includes? (:message result) "worktrees:"))))

(deftest dispatch-background-jobs-commands-test
  (let [ctx       (make-test-ctx)
        thread-id (:session-id (ss/get-session-data-in ctx))]
    (dispatch/dispatch! ctx :session/update-background-jobs-state
                        {:update-fn (fn [store]
                                      (:state (bg-jobs/start-background-job
                                               store
                                               {:tool-call-id "tc-cmd-j1"
                                                :thread-id thread-id
                                                :tool-name "agent-chain"
                                                :job-id "job-cmd-j1"})))}
                        {:origin :core})

    (let [jobs-result (commands/dispatch ctx "/jobs" cmd-opts)]
      (is (= :text (:type jobs-result)))
      (is (str/includes? (:message jobs-result) "Background jobs"))
      (is (str/includes? (:message jobs-result) "job-cmd-j1")))

    (let [jobs-filtered (commands/dispatch ctx "/jobs running" cmd-opts)]
      (is (= :text (:type jobs-filtered)))
      (is (str/includes? (:message jobs-filtered) "job-cmd-j1")))

    (let [jobs-invalid (commands/dispatch ctx "/jobs nope" cmd-opts)]
      (is (= :text (:type jobs-invalid)))
      (is (= "Usage: /jobs [status ...]" (:message jobs-invalid))))

    (let [inspect-result (commands/dispatch ctx "/job job-cmd-j1" cmd-opts)]
      (is (= :text (:type inspect-result)))
      (is (str/includes? (:message inspect-result) "Background job"))
      (is (str/includes? (:message inspect-result) "job-cmd-j1")))

    (let [inspect-usage (commands/dispatch ctx "/job" cmd-opts)]
      (is (= :text (:type inspect-usage)))
      (is (= "Usage: /job <job-id>" (:message inspect-usage))))

    (let [cancel-result (commands/dispatch ctx "/cancel-job job-cmd-j1" cmd-opts)
          job           (bg-rt/inspect-background-job-in! ctx thread-id "job-cmd-j1")]
      (is (= :text (:type cancel-result)))
      (is (str/includes? (:message cancel-result) "Cancellation requested"))
      (is (= :pending-cancel (:status job))))

    (let [cancel-usage (commands/dispatch ctx "/cancel-job" cmd-opts)]
      (is (= :text (:type cancel-usage)))
      (is (= "Usage: /cancel-job <job-id>" (:message cancel-usage))))))

(deftest dispatch-model-no-arg-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/model" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Current model:"))))

(deftest workflow-send-event-tracked-job-visible-via-commands-test
  (testing "workflow send-event tracked job is visible via /jobs and /job"
    (let [ctx       (make-test-ctx)
          thread-id (:session-id (ss/get-session-data-in ctx))
          qctx      (query/create-query-context)
          chart     (chart/statechart {:id :cmd-wf}
                                      (ele/state {:id :idle}
                                                 (ele/transition {:event :workflow/start :target :running}))
                                      (ele/state {:id :running})
                                      (ele/final {:id :done}))
          mutate    (fn [op params]
                      (get (query/query-in qctx
                                           {:psi/agent-session-ctx ctx}
                                           [(list op (assoc params :psi/agent-session-ctx ctx))])
                           op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (mutate 'psi.extension.workflow/register-type
              {:ext-path "/ext/cmd"
               :type     :cmdwf
               :chart    chart})

      (mutate 'psi.extension.workflow/create
              {:ext-path "/ext/cmd"
               :type     :cmdwf
               :id       "wf-cmd"
               :auto-start? false})

      (let [send-r (mutate 'psi.extension.workflow/send-event
                           {:ext-path "/ext/cmd"
                            :id       "wf-cmd"
                            :event    :workflow/start
                            :track-background-job? true
                            :data     {:tool-call-id "tc-cmd-send-1"}})
            job-id (:psi.extension.background-job/id send-r)]
        (is (string? job-id))

        (let [jobs-result (commands/dispatch ctx "/jobs running" cmd-opts)]
          (is (= :text (:type jobs-result)))
          (is (str/includes? (:message jobs-result) job-id)))

        (let [inspect-result (commands/dispatch ctx (str "/job " job-id) cmd-opts)]
          (is (= :text (:type inspect-result)))
          (is (str/includes? (:message inspect-result) job-id)))

        (let [listed (bg-rt/list-background-jobs-in! ctx thread-id)]
          (is (some #(= job-id (:job-id %)) listed)))))))

(deftest dispatch-model-set-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/model openai gpt-5.3-codex" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "✓ Model set to"))
    (is (= "openai" (get-in (ss/get-session-data-in ctx) [:model :provider])))
    (is (= "gpt-5.3-codex" (get-in (ss/get-session-data-in ctx) [:model :id])))))

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
    (is (= :off (:thinking-level (ss/get-session-data-in ctx))))))

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

(deftest dispatch-remember-without-memory-ctx-test
  (let [ctx    (make-test-ctx)
        result (commands/dispatch ctx "/remember" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Memory context not configured"))))

(deftest dispatch-remember-accepted-test
  (let [ctx          (-> (make-test-ctx)
                         with-ready-memory-ctx)
        before-count (count (:records (memory/get-state-in (:memory-ctx ctx))))
        result       (commands/dispatch ctx "/remember verify runtime" cmd-opts)
        after-state  (memory/get-state-in (:memory-ctx ctx))
        after-count  (count (:records after-state))
        rec          (last (:records after-state))
        prov         (:provenance rec)
        telemetry    (session/query-in ctx
                                       [:psi.memory.remember/captures
                                        :psi.memory.remember/last-capture-at])]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Remembered"))
    (is (str/includes? (:message result) "record-id:"))
    (is (= 1 (- after-count before-count)) "exactly one remember record per invocation")
    (is (= "verify runtime" (:content rec)))
    (is (= :remember (get-in rec [:provenance :source])))
    (is (string? (:sessionId prov)))
    (is (= (:cwd ctx) (:cwd prov)))
    (is (contains? prov :gitBranch))
    (is (some #(= (:record-id rec) (:record-id %))
              (:psi.memory.remember/captures telemetry))
        "captured remember record should be visible in remember telemetry captures")
    (is (= (:timestamp rec) (:psi.memory.remember/last-capture-at telemetry)))))

(deftest dispatch-remember-blocked-when-memory-not-ready-test
  (let [ctx    (-> (make-test-ctx)
                   with-unready-memory-ctx)
        result (commands/dispatch ctx "/remember check live readiness" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Remember blocked"))
    (is (str/includes? (:message result) "memory_capture_prerequisites_not_ready"))))

(deftest dispatch-remember-store-write-failure-surfaces-fallback-test
  (let [ctx         (-> (make-test-ctx)
                        with-ready-memory-ctx)
        status-atom (atom :ready)
        provider    (reify store/StoreProvider
                      (provider-id [_] "failing-store")
                      (provider-capabilities [_]
                        {:durability :persistent
                         :supports-restart-recovery? true
                         :supports-retention-compaction? true
                         :supports-capability-history-query? true
                         :query-mode :indexed})
                      (open-provider! [this _] this)
                      (close-provider! [this] this)
                      (provider-status [_] @status-atom)
                      (provider-health [_]
                        {:status :healthy
                         :checked-at (java.time.Instant/now)
                         :details nil})
                      (provider-write! [_ _ _]
                        {:ok? false :error :boom :message "write failed"})
                      (provider-query! [_ _] {:ok? true :results []})
                      (provider-load-state [_] {:ok? true}))
        _ (memory/register-store-provider-in! (:memory-ctx ctx) provider)
        _ (memory/select-store-provider-in! (:memory-ctx ctx) "failing-store")
        result (commands/dispatch ctx "/remember provider outage path" cmd-opts)]
    (is (= :text (:type result)))
    (is (str/includes? (:message result) "Remembered with store fallback"))
    (is (str/includes? (:message result) "store-error: boom"))
    (is (str/includes? (:message result) "provider: failing-store"))))

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

(deftest format-status-includes-effective-reasoning-effort-test
  (let [ctx (make-test-ctx {:model {:provider "openai"
                                    :id "gpt-5.3-codex"
                                    :reasoning true}
                            :thinking-level :high})
        s   (commands/format-status ctx)]
    (is (str/includes? s "thinking high"))))

(deftest format-worktree-test
  (let [ctx (make-test-ctx)
        s   (commands/format-worktree ctx)]
    (is (str/includes? s "Git worktrees"))
    (is (str/includes? s "cwd"))
    (is (str/includes? s "worktrees:"))))

(deftest format-history-empty-test
  (let [ctx (make-test-ctx)
        s   (commands/format-history ctx)]
    (is (str/includes? s "(empty)"))))

(deftest format-history-with-messages-test
  (let [ctx (make-test-ctx)]
    (agent/append-message-in! (ss/agent-ctx-in ctx)
                              {:role "user" :content [{:type :text :text "hello"}]})
    (let [s (commands/format-history ctx)]
      (is (str/includes? s "[user]"))
      (is (str/includes? s "hello")))))

(deftest format-help-includes-all-commands-test
  (let [ctx (make-test-ctx)
        s   (commands/format-help ctx)]
    (doseq [cmd ["/quit" "/status" "/history" "/new" "/resume" "/tree"
                 "/login" "/logout" "/remember" "/worktree"
                 "/jobs" "/job" "/cancel-job"
                 "/help" "/prompts" "/skills"]]
      (is (str/includes? s cmd) (str "help should mention " cmd)))))

(deftest format-prompts-none-test
  (let [ctx (make-test-ctx)
        s   (commands/format-prompts ctx)]
    (is (str/includes? s "(none discovered)"))))

(deftest format-skills-none-test
  (let [ctx (make-test-ctx)
        s   (commands/format-skills ctx)]
    (is (str/includes? s "(none discovered)"))))

