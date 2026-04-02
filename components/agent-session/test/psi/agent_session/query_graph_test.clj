(ns psi.agent-session.query-graph-test
  "Tests for global query graph registration, workflow mutations,
  tool mutations, bootstrap, and child session infrastructure."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.bootstrap :as bootstrap]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.history.git :as history-git]
   [psi.query.core :as query]
   [psi.recursion.core :as recursion])
  (:import
   (java.io File)))
(defn- create-session-context
  ([]
   (create-session-context {}))
  ([opts]
   (let [ctx (session/create-context (test-support/safe-context-opts opts))
         sd  (session/new-session-in! ctx nil {})]
     [ctx (:session-id sd)])))

;; ── Global query graph registration ─────────────────────────────────────────

(deftest register-resolvers-in!-test
  (testing "register-resolvers-in! wires agent-session into an isolated query ctx"
    (let [qctx (query/create-query-context)]
      (session/register-resolvers-in! qctx)
      ;; Agent-session resolvers should now be in the isolated graph
      (let [syms (query/resolver-syms-in qctx)]
        (is (contains? syms 'psi.agent-session.resolvers.session/agent-session-identity)
            "identity resolver should be registered")
        (is (contains? syms 'psi.agent-session.resolvers.session/agent-session-phase)
            "phase resolver should be registered"))))

  (testing "register-resolvers-in! enables EQL queries via the isolated ctx"
    (let [[session-ctx _] (create-session-context)
          qctx            (query/create-query-context)]
      (session/register-resolvers-in! qctx)
      (let [result (query/query-in qctx
                                   {:psi/agent-session-ctx session-ctx}
                                   [:psi.agent-session/session-id
                                    :psi.agent-session/phase])]
        (is (string? (:psi.agent-session/session-id result)))
        (is (= :idle (:psi.agent-session/phase result)))))))

(deftest register-mutations-in!-includes-history-mutations-test
  (testing "register-mutations-in! wires history git mutations into isolated query ctx"
    (let [git-ctx     (history-git/create-null-context)
          [ctx _]     (create-session-context {:cwd (:repo-dir git-ctx) :persist? false})
          qctx        (query/create-query-context)]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [syms (query/mutation-syms-in qctx)
            wt-path (str (.getParentFile (File. (:repo-dir git-ctx)))
                         File/separator
                         "ext-mutation-worktree-"
                         (java.util.UUID/randomUUID))
            result (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx
                                         :git/context (history-git/create-context (:repo-dir git-ctx))}
                                        [(list 'git.worktree/add!
                                               {:psi/agent-session-ctx ctx
                                                :git/context (history-git/create-context (:repo-dir git-ctx))
                                                :input {:path wt-path
                                                        :branch "ext-mutation-worktree"}})])
                        'git.worktree/add!)]
        (is (contains? syms 'git.worktree/add!))
        (is (true? (:success result))))))

  (testing "isolated extension mutation path can attach a worktree to an existing branch"
    (let [git-ctx       (history-git/create-null-context)
          repo-dir      (:repo-dir git-ctx)
          parent-dir    (.getParentFile (File. repo-dir))
          suffix        (str (java.util.UUID/randomUUID))
          branch-name   (str "fix-repeated-thinking-output-" suffix)
          source-path   (str parent-dir File/separator "branch-source-" suffix)
          worktree-path (str parent-dir File/separator branch-name)
          _             (history-git/worktree-add git-ctx {:path source-path
                                                           :branch branch-name})
          _             (history-git/worktree-remove git-ctx {:path source-path})
          [ctx _]       (create-session-context {:cwd repo-dir :persist? false})
          qctx          (query/create-query-context)]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [attach (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx
                                         :git/context (history-git/create-context repo-dir)}
                                        [(list 'git.worktree/add!
                                               {:psi/agent-session-ctx ctx
                                                :git/context (history-git/create-context repo-dir)
                                                :input {:path worktree-path
                                                        :branch branch-name
                                                        :create-branch false}})])
                        'git.worktree/add!)]
        (is (true? (:success attach)) (pr-str attach))
        (is (= branch-name (:branch attach)) (pr-str attach))))))

(deftest rpc-trace-mutation-and-resolver-test
  (let [[ctx session-id] (create-session-context {:persist? false})
        qctx       (query/create-query-context)
        mutate     (fn [op params]
                     (get (query/query-in qctx
                                          {:psi/agent-session-ctx ctx}
                                          [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                      (not (contains? params :session-id))
                                                      (assoc :session-id session-id)))])
                          op))]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)

    (is (contains? (query/mutation-syms-in qctx) 'psi.extension/set-rpc-trace))
    (is (= {:psi.agent-session/rpc-trace-enabled false
            :psi.agent-session/rpc-trace-file nil}
           (session/query-in ctx [:psi.agent-session/rpc-trace-enabled
                                  :psi.agent-session/rpc-trace-file])))

    (let [r1 (mutate 'psi.extension/set-rpc-trace
                     {:session-id session-id
                      :enabled true
                      :file "/tmp/psi-rpc-trace-from-mutation.ndedn"})]
      (is (= true (:psi.agent-session/rpc-trace-enabled r1)))
      (is (= "/tmp/psi-rpc-trace-from-mutation.ndedn"
             (:psi.agent-session/rpc-trace-file r1)))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-rpc-trace (:event-type entry)))
        (is (= :mutations (:origin entry)))
        (is (= {:enabled? true
                :file "/tmp/psi-rpc-trace-from-mutation.ndedn"}
               (dissoc (:event-data entry) :session-id)))))

    (let [r2 (mutate 'psi.extension/set-rpc-trace {:session-id session-id :enabled false})]
      (is (= false (:psi.agent-session/rpc-trace-enabled r2)))
      (is (= "/tmp/psi-rpc-trace-from-mutation.ndedn"
             (:psi.agent-session/rpc-trace-file r2)))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-rpc-trace (:event-type entry)))
        (is (= :mutations (:origin entry)))
        (is (= {:enabled? false
                :file "/tmp/psi-rpc-trace-from-mutation.ndedn"}
               (dissoc (:event-data entry) :session-id)))))))

(deftest session-query-in-exposes-recursion-attrs-test
  (testing "session/query-in can read recursion attrs from live session recursion-ctx"
    (let [rctx (recursion/create-context)
          _    (recursion/register-hooks-in! rctx)
          [ctx _] (create-session-context {:recursion-ctx rctx})
          result  (session/query-in ctx
                                    [:psi.recursion/status
                                     :psi.recursion/paused?
                                     :psi.recursion/current-cycle
                                     :psi.recursion/hooks])]
      (is (= :idle (:psi.recursion/status result)))
      (is (false? (:psi.recursion/paused? result)))
      (is (nil? (:psi.recursion/current-cycle result)))
      (is (vector? (:psi.recursion/hooks result)))
      (is (pos? (count (:psi.recursion/hooks result)))))))

(deftest workflow-background-job-terminal-injection-test
  (testing "workflow completion emits background-job-terminal assistant message"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-bg-test}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :running}))
                                   (ele/state {:id :running}
                                              (ele/transition {:event :workflow/finish :target :done}
                                                              (ele/script {:expr (fn [_ data]
                                                                                   [{:op :assign
                                                                                     :data {:result (get-in data [:_event :data :result])}}])})))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (mutate 'psi.extension.workflow/register-type
              {:ext-path "/ext/bg"
               :type     :bg-simple
               :chart    chart})
      (mutate 'psi.extension.workflow/create
              {:ext-path "/ext/bg"
               :type     :bg-simple
               :id       "wf-bg-1"
               :auto-start? false})
      (mutate 'psi.extension.workflow/send-event
              {:ext-path "/ext/bg"
               :id       "wf-bg-1"
               :event    :workflow/start
               :track-background-job? true
               :data     {:tool-call-id "tc-wf-bg-1"}})
      (mutate 'psi.extension.workflow/send-event
              {:ext-path "/ext/bg"
               :id       "wf-bg-1"
               :event    :workflow/finish
               :data     {:result {:ok true}}})

      ;; Ensure extension-injected messages are persisted into transcript in this test.
      (test-support/update-state! ctx :session-data assoc :startup-bootstrap-completed? true)

      ;; Turn boundary / idle checkpoint triggers background terminal injection path.
      (ext-rt/set-extension-run-fn-in! ctx session-id (fn [_text _source] nil))
      (Thread/sleep 30)

      (let [assistant-msgs (->> (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx session-id)))
                                (filter #(= "assistant" (:role %)))
                                vec)
            injected (some #(when (= "background-job-terminal" (:custom-type %)) %) assistant-msgs)
            injected-text (get-in injected [:content 0 :text])]
        (is (map? injected) (str "assistant messages: " assistant-msgs))
        (is (string? injected-text))
        (is (re-find #"workflow-id" injected-text))))))

(deftest workflow-mutations-and-resolvers-test
  (testing "workflow mutation ops and resolver attrs are wired"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-test}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :running}))
                                   (ele/state {:id :running}
                                              (ele/transition {:event :workflow/finish :target :done}
                                                              (ele/script {:expr (fn [_ data]
                                                                                   [{:op :assign
                                                                                     :data {:result (get-in data [:_event :data :result])}}])})))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (let [r1 (mutate 'psi.extension.workflow/register-type
                       {:ext-path "/ext/a"
                        :type     :simple
                        :chart    chart})
            r2 (mutate 'psi.extension.workflow/create
                       {:ext-path "/ext/a"
                        :type     :simple
                        :id       "w1"
                        :auto-start? false})
            r3 (mutate 'psi.extension.workflow/send-event
                       {:ext-path "/ext/a"
                        :id       "w1"
                        :event    :workflow/start})
            r4 (mutate 'psi.extension.workflow/send-event
                       {:ext-path "/ext/a"
                        :id       "w1"
                        :event    :workflow/finish
                        :data     {:result 99}})
            q  (session/query-in ctx
                                 [:psi.extension.workflow/count
                                  :psi.extension.workflow/running-count
                                  {:psi.extension/workflows
                                   [:psi.extension/path
                                    :psi.extension.workflow/id
                                    :psi.extension.workflow/phase
                                    :psi.extension.workflow/result]}])]
        (is (true? (:psi.extension.workflow.type/registered? r1)))
        (is (true? (:psi.extension.workflow/created? r2)))
        (is (true? (:psi.extension.workflow/event-accepted? r3)))
        (is (true? (:psi.extension.workflow/event-accepted? r4)))
        (is (= 1 (:psi.extension.workflow/count q)))
        (is (= 0 (:psi.extension.workflow/running-count q)))
        (is (= [{:psi.extension/path "/ext/a"
                 :psi.extension.workflow/id "w1"
                 :psi.extension.workflow/phase :done
                 :psi.extension.workflow/result 99}]
               (:psi.extension/workflows q)))))))

(deftest send-workflow-event-track-background-job-gated-test
  (testing "send-event tracks background jobs only when track-background-job? is true"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-track-gate}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :running}))
                                   (ele/state {:id :running}
                                              (ele/transition {:event :workflow/finish :target :done}))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))
          thread-id session-id]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (mutate 'psi.extension.workflow/register-type
              {:ext-path "/ext/gate"
               :type     :gate
               :chart    chart})
      (mutate 'psi.extension.workflow/create
              {:ext-path "/ext/gate"
               :type     :gate
               :id       "wg"
               :auto-start? false})

      (let [jobs-before (bg-rt/list-background-jobs-in! ctx thread-id)
            no-track    (mutate 'psi.extension.workflow/send-event
                                {:ext-path "/ext/gate"
                                 :id       "wg"
                                 :event    :workflow/start
                                 :data     {:tool-call-id "tc-gate-1"}})
            jobs-a      (bg-rt/list-background-jobs-in! ctx thread-id)]
        (is (true? (:psi.extension.workflow/event-accepted? no-track)))
        (is (nil? (:psi.extension.background-job/id no-track)))
        (is (= (count jobs-before) (count jobs-a))))

      (let [tracked (mutate 'psi.extension.workflow/send-event
                            {:ext-path "/ext/gate"
                             :id       "wg"
                             :event    :workflow/finish
                             :track-background-job? true
                             :data     {:tool-call-id "tc-gate-2"}})
            jobs-b  (bg-rt/list-background-jobs-in! ctx
                                                    thread-id
                                                    [:running :pending-cancel :completed :failed :cancelled :timed-out])]
        (is (true? (:psi.extension.workflow/event-accepted? tracked)))
        (is (string? (:psi.extension.background-job/id tracked)))
        (is (some #(= (:psi.extension.background-job/id tracked) (:job-id %)) jobs-b))))))

(deftest workflow-query-and-background-job-vertical-slice-test
  (testing "workflow public data and background-job projection are queryable and coherent"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          chart  (chart/statechart {:id :wf-vertical-slice}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :done}))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [registered (mutate 'psi.extension.workflow/register-type
                               {:ext-path "/ext/slice"
                                :type :mcp-slice
                                :chart chart
                                :start-event :workflow/start
                                :initial-data-fn (fn [_]
                                                   {:run/id "slice-1"
                                                    :run/current-state :done
                                                    :run/progress {:message "finished"}})
                                :public-data-fn (fn [data]
                                                  {:run/id (:run/id data)
                                                   :run/current-state (:run/current-state data)
                                                   :run/progress (:run/progress data)
                                                   :run/display {:top-line (str "✓ slice " (:run/id data))
                                                                 :detail-line (str "  " (get-in data [:run/progress :message]))}})})
            created (mutate 'psi.extension.workflow/create
                            {:ext-path "/ext/slice"
                             :type :mcp-slice
                             :id "slice-1"
                             :track-background-job? true
                             :input {:tool-call-id "tc-slice-1"}})
            _       (bg-rt/reconcile-workflow-background-jobs-in! ctx)
            resp    (session/query-in ctx
                                      [{:psi.extension/workflows
                                        [:psi.extension/path
                                         :psi.extension.workflow/id
                                         :psi.extension.workflow/phase
                                         :psi.extension.workflow/data]}
                                       {:psi.agent-session/background-jobs
                                        [:psi.background-job/workflow-ext-path
                                         :psi.background-job/workflow-id
                                         :psi.background-job/status
                                         :psi.background-job/is-terminal]}])
            wf      (first (:psi.extension/workflows resp))
            job     (first (:psi.agent-session/background-jobs resp))]
        (is (true? (:psi.extension.workflow.type/registered? registered)))
        (is (true? (:psi.extension.workflow/created? created)))
        (is (= "/ext/slice" (:psi.extension/path wf)))
        (is (= "slice-1" (:psi.extension.workflow/id wf)))
        (is (= :done (:psi.extension.workflow/phase wf)))
        (is (= :done (get-in wf [:psi.extension.workflow/data :run/current-state])))
        (is (= {:message "finished"}
               (get-in wf [:psi.extension.workflow/data :run/progress])))
        (is (= {:top-line "✓ slice slice-1"
                :detail-line "  finished"}
               (get-in wf [:psi.extension.workflow/data :run/display])))
        (is (= "/ext/slice" (:psi.background-job/workflow-ext-path job)))
        (is (= "slice-1" (:psi.background-job/workflow-id job)))
        (is (true? (:psi.background-job/is-terminal job)))
        (is (contains? #{:completed :failed} (:psi.background-job/status job)))))))

(deftest runtime-tool-executor-boundary-test
  (testing "execute-tool-runtime-in! uses the ctx-provided runtime tool executor when present"
    (let [calls      (atom [])
          [ctx session-id] (create-session-context {:persist? false})
          ctx        (assoc ctx :runtime-tool-executor-fn
                            (fn [_ctx tool-name args opts]
                              (swap! calls conj {:tool-name tool-name :args args :opts opts})
                              {:content "custom" :is-error false}))]
      (is (= {:content "custom" :is-error false}
             (tool-plan/execute-tool-runtime-in! ctx session-id "custom-tool" {"x" 1} {:tool-call-id "c1"})))
      (is (= [{:tool-name "custom-tool"
               :args {"x" 1}
               :opts {:tool-call-id "c1"}}]
             @calls))))

  (testing "default-execute-runtime-tool-in! remains the fallback runtime implementation"
    (let [[ctx session-id] (create-session-context {:persist? false})]
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "prefix-a"
         :execute (fn [args _opts]
                    {:content (str "A:" (get args "text" ""))
                     :is-error false})}])
      (is (= {:content "A:hello" :is-error false}
             (tool-plan/default-execute-runtime-tool-in! ctx session-id "prefix-a" {"text" "hello"} {})))
      (is (= {:content "A:hello" :is-error false}
             (tool-plan/execute-tool-runtime-in! ctx session-id "prefix-a" {"text" "hello"} {}))))))

(deftest tool-plan-runtime-tool-executor-boundary-test
  (testing "run-tool-plan uses the ctx-provided runtime tool executor boundary"
    (let [calls         (atom [])
          [ctx* session-id] (create-session-context {:persist? false})
          ctx            (assoc ctx*
                                :runtime-tool-executor-fn
                                (fn [_ctx tool-name args opts]
                                  (swap! calls conj {:tool-name tool-name :args args :opts opts})
                                  {:content (str "custom:" (get args "text" ""))
                                   :is-error false}))
          qctx       (query/create-query-context)
          mutate     (fn [op params]
                       (get (query/query-in qctx
                                            {:psi/agent-session-ctx ctx}
                                            [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                        (not (contains? params :session-id))
                                                        (assoc :session-id session-id)))])
                            op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [result (mutate 'psi.extension/run-tool-plan
                           {:steps [{:id :s1 :tool "custom-tool" :args {:text "hello"}}]})]
        (is (true? (:psi.extension.tool-plan/succeeded? result)))
        (is (= "custom:hello"
               (get-in result [:psi.extension.tool-plan/result-by-id :s1 :content])))
        (is (= 1 (count @calls)))
        (is (= "custom-tool" (:tool-name (first @calls))))
        (is (= {"text" "hello"} (:args (first @calls))))
        (is (string? (get-in @calls [0 :opts :tool-call-id])))))))

(deftest tool-plan-mutation-test
  (testing "run-tool-plan chains step outputs into later step args"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "prefix-a"
         :execute (fn [args _opts]
                    {:content  (str "A:" (get args "text" ""))
                     :is-error false})}
        {:name "prefix-b"
         :execute (fn [args _opts]
                    {:content  (str "B:" (get args "text" ""))
                     :is-error false})}])
      (let [result (mutate 'psi.extension/run-tool-plan
                           {:steps [{:id :s1 :tool "prefix-a" :args {:text "hello"}}
                                    {:id :s2 :tool "prefix-b" :args {:text [:from :s1 :content]}}]})]
        (is (true? (:psi.extension.tool-plan/succeeded? result)))
        (is (= 2 (:psi.extension.tool-plan/step-count result)))
        (is (= 2 (:psi.extension.tool-plan/completed-count result)))
        (is (nil? (:psi.extension.tool-plan/failed-step-id result)))
        (is (= "A:hello"
               (get-in result [:psi.extension.tool-plan/result-by-id :s1 :content])))
        (is (= "B:A:hello"
               (get-in result [:psi.extension.tool-plan/result-by-id :s2 :content])))
        (is (= {"text" "A:hello"}
               (get-in result [:psi.extension.tool-plan/results 1 :args]))))))

  (testing "run-tool-plan stops on first failing step by default"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          ran-last (atom false)
          mutate   (fn [op params]
                     (get (query/query-in qctx
                                          {:psi/agent-session-ctx ctx}
                                          [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                      (not (contains? params :session-id))
                                                      (assoc :session-id session-id)))])
                          op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "ok"
         :execute (fn [_args _opts]
                    {:content "ok"
                     :is-error false})}
        {:name "fail"
         :execute (fn [_args _opts]
                    {:content "boom"
                     :is-error true})}
        {:name "should-not-run"
         :execute (fn [_args _opts]
                    (reset! ran-last true)
                    {:content "ran"
                     :is-error false})}])
      (let [result (mutate 'psi.extension/run-tool-plan
                           {:steps [{:id :s1 :tool "ok"}
                                    {:id :s2 :tool "fail"}
                                    {:id :s3 :tool "should-not-run"}]})]
        (is (false? (:psi.extension.tool-plan/succeeded? result)))
        (is (= :s2 (:psi.extension.tool-plan/failed-step-id result)))
        (is (= 2 (:psi.extension.tool-plan/completed-count result)))
        (is (false? @ran-last))
        (is (string? (:psi.extension.tool-plan/error result)))))))

(deftest tool-mutations-test
  (testing "built-in tool mutations execute read/write/update/bash"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))
          f      (File/createTempFile "psi-tool-mutation" ".txt")
          path   (.getAbsolutePath f)]
      (.deleteOnExit f)
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)

      (let [w  (mutate 'psi.extension.tool/write {:path path :content "alpha"})
            r1 (mutate 'psi.extension.tool/read {:path path})
            u  (mutate 'psi.extension.tool/update {:path path
                                                   :oldText "alpha"
                                                   :newText "beta"})
            r2 (mutate 'psi.extension.tool/read {:path path})
            b  (mutate 'psi.extension.tool/bash {:command "printf 'ok'"})]
        (is (= "write" (:psi.extension.tool/name w)))
        (is (false? (:psi.extension.tool/is-error w)))

        (is (= "read" (:psi.extension.tool/name r1)))
        (is (false? (:psi.extension.tool/is-error r1)))
        (is (= "alpha" (:psi.extension.tool/content r1)))

        ;; update is backed by the built-in edit tool
        (is (= "edit" (:psi.extension.tool/name u)))
        (is (false? (:psi.extension.tool/is-error u)))

        (is (= "beta" (:psi.extension.tool/content r2)))

        (is (= "bash" (:psi.extension.tool/name b)))
        (is (false? (:psi.extension.tool/is-error b))))))

  (testing "chain mutation is an alias to run-tool-plan"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx session-id)
       [{:name "prefix-a"
         :execute (fn [args _opts]
                    {:content  (str "A:" (get args "text" ""))
                     :is-error false})}
        {:name "prefix-b"
         :execute (fn [args _opts]
                    {:content  (str "B:" (get args "text" ""))
                     :is-error false})}])
      (let [result (mutate 'psi.extension.tool/chain
                           {:steps [{:id :s1 :tool "prefix-a" :args {:text "hello"}}
                                    {:id :s2 :tool "prefix-b" :args {:text [:from :s1 :content]}}]})]
        (is (true? (:psi.extension.tool-plan/succeeded? result)))
        (is (= "B:A:hello"
               (get-in result [:psi.extension.tool-plan/result-by-id :s2 :content]))))))

  (testing "register/update/unregister prompt contribution mutations"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (let [r1 (mutate 'psi.extension/register-prompt-contribution
                       {:ext-path "/ext/a"
                        :id "c1"
                        :contribution {:content "A" :priority 10 :enabled true}})
            r2 (mutate 'psi.extension/update-prompt-contribution
                       {:ext-path "/ext/a"
                        :id "c1"
                        :patch {:content "B"}})
            r3 (mutate 'psi.extension/unregister-prompt-contribution
                       {:ext-path "/ext/a"
                        :id "c1"})]
        (is (true? (:psi.extension.prompt-contribution/registered? r1)))
        (is (true? (:psi.extension.prompt-contribution/updated? r2)))
        (is (true? (:psi.extension.prompt-contribution/removed? r3))))))

  (testing "send-prompt mutation stores extension prompt telemetry (no run-fn → follow-up)"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      ;; Without a run-fn registered, delivery falls back to :follow-up (safe queue)
      (let [result (mutate 'psi.extension/send-prompt
                           {:content "hello from extension"
                            :source "plan-state-learning"})
            telemetry (session/query-in ctx
                                        [:psi.agent-session/extension-last-prompt-source
                                         :psi.agent-session/extension-last-prompt-delivery
                                         :psi.agent-session/extension-last-prompt-at])]
        (is (true? (:psi.extension/prompt-accepted? result)))
        (is (= :follow-up (:psi.extension/prompt-delivery result)))
        (is (= "plan-state-learning" (:psi.agent-session/extension-last-prompt-source telemetry)))
        (is (= :follow-up (:psi.agent-session/extension-last-prompt-delivery telemetry)))
        (is (inst? (:psi.agent-session/extension-last-prompt-at telemetry)))
        (let [entry (last (dispatch/event-log-entries))]
          (is (= :session/record-extension-prompt (:event-type entry)))
          (is (= :core (:origin entry)))
          (is (= "plan-state-learning" (:source (:event-data entry))))
          (is (= :follow-up (:delivery (:event-data entry))))))))

  (testing "send-prompt mutation with run-fn registered delivers as :prompt"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))
          run-calls (atom [])]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      ;; Register a stub run-fn that captures calls
      (ext-rt/set-extension-run-fn-in! ctx session-id (fn [text source]
                                                        (swap! run-calls conj {:text text :source source})))
      (let [result (mutate 'psi.extension/send-prompt
                           {:content "hello from extension"
                            :source "plan-state-learning"})
            telemetry (session/query-in ctx
                                        [:psi.agent-session/extension-last-prompt-source
                                         :psi.agent-session/extension-last-prompt-delivery
                                         :psi.agent-session/extension-last-prompt-at])]
        ;; Allow the background future to complete
        (Thread/sleep 50)
        (is (true? (:psi.extension/prompt-accepted? result)))
        (is (= :prompt (:psi.extension/prompt-delivery result)))
        (is (= "plan-state-learning" (:psi.agent-session/extension-last-prompt-source telemetry)))
        (is (= :prompt (:psi.agent-session/extension-last-prompt-delivery telemetry)))
        (is (inst? (:psi.agent-session/extension-last-prompt-at telemetry)))
        (is (= 1 (count @run-calls)))
        (is (= "hello from extension" (:text (first @run-calls))))
        (let [entry (last (dispatch/event-log-entries))]
          (is (= :session/record-extension-prompt (:event-type entry)))
          (is (= :core (:origin entry)))
          (is (= "plan-state-learning" (:source (:event-data entry))))
          (is (= :prompt (:delivery (:event-data entry))))))))

  (testing "send-prompt mutation while streaming with run-fn registered reports :deferred"
    (let [[ctx session-id] (create-session-context)
          qctx       (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (cond-> (assoc params :psi/agent-session-ctx ctx)
                                                    (not (contains? params :session-id))
                                                    (assoc :session-id session-id)))])
                        op))
          run-calls (atom [])]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      ;; Force non-idle session phase so send-extension-prompt-in! takes deferred path.
      (let [session-id session-id]
        (session/prompt-in! ctx session-id "seed busy turn"))
      (ext-rt/set-extension-run-fn-in! ctx session-id (fn [text source]
                                                        (swap! run-calls conj {:text text :source source})))
      (let [result (mutate 'psi.extension/send-prompt
                           {:content "hello while busy"
                            :source "plan-state-learning"})
            telemetry (session/query-in ctx
                                        [:psi.agent-session/extension-last-prompt-source
                                         :psi.agent-session/extension-last-prompt-delivery
                                         :psi.agent-session/extension-last-prompt-at])]
        ;; Allow the background future to run.
        (Thread/sleep 50)
        (is (true? (:psi.extension/prompt-accepted? result)))
        (is (= :deferred (:psi.extension/prompt-delivery result)))
        (is (= "plan-state-learning" (:psi.agent-session/extension-last-prompt-source telemetry)))
        (is (= :deferred (:psi.agent-session/extension-last-prompt-delivery telemetry)))
        (is (inst? (:psi.agent-session/extension-last-prompt-at telemetry)))
        (is (= 1 (count @run-calls)))
        (is (= "hello while busy" (:text (first @run-calls))))
        (let [entry (last (dispatch/event-log-entries))]
          (is (= :session/record-extension-prompt (:event-type entry)))
          (is (= :core (:origin entry)))
          (is (= "plan-state-learning" (:source (:event-data entry))))
          (is (= :deferred (:delivery (:event-data entry)))))))))

(deftest startup-resources-via-mutations-test
  (testing "load-startup-resources-via-mutations-in! adds prompts/skills/tools"
    (let [[ctx session-id] (create-session-context)
          template   {:name "greet" :description "d" :content "c" :source :project :file-path "/tmp/greet.md"}
          skill      {:name "coding" :description "d" :file-path "/tmp/SKILL.md"
                      :base-dir "/tmp" :source :project :disable-model-invocation false}
          tool       {:name "foo" :label "Foo" :description "bar" :parameters "{}"}]
      (bootstrap/load-startup-resources-via-mutations-in!
       ctx session-id {:templates [template] :skills [skill] :tools [tool] :extension-paths []})
      (let [sd (ss/get-session-data-in ctx session-id)
            ad (agent-core/get-data-in (ss/agent-ctx-in ctx session-id))]
        (is (= 1 (count (:prompt-templates sd))))
        (is (= 1 (count (:skills sd))))
        (is (= 1 (count (:tools ad))))
        (is (= "foo" (:name (first (:tools ad)))))))))

(deftest bootstrap-session-test
  (testing "bootstrap-in! stores startup summary and applies resources"
    (let [[ctx session-id] (create-session-context {:persist? false})
          template   {:name "greet" :description "d" :content "c" :source :project :file-path "/tmp/greet.md"}
          skill      {:name "coding" :description "d" :file-path "/tmp/SKILL.md"
                      :base-dir "/tmp" :source :project :disable-model-invocation false}
          tool       {:name "foo" :label "Foo" :description "bar" :parameters "{}"}
          summary    (bootstrap/bootstrap-in!
                      ctx session-id
                      {:register-global-query? false
                       :base-tools             []
                       :system-prompt          "sys"
                       :templates              [template]
                       :skills                 [skill]
                       :tools                  [tool]
                       :extension-paths        []})
          sd         (ss/get-session-data-in ctx session-id)
          ad         (agent-core/get-data-in (ss/agent-ctx-in ctx session-id))]
      (is (= 1 (:prompt-count summary)))
      (is (= 1 (:skill-count summary)))
      (is (= 1 (:tool-count summary)))
      (is (= 0 (:extension-error-count summary)))
      (is (map? (:startup-bootstrap sd)))
      (is (= :console (:ui-type sd)))
      (is (nil? (:session-file sd)))
      (is (= "sys" (:developer-prompt sd)))
      (is (= :fallback (:developer-prompt-source sd)))
      (is (= 1 (count (:prompt-templates sd))))
      (is (= 1 (count (:skills sd))))
      (is (= 1 (count (:tools ad)))))))

(deftest bootstrap-session-developer-prompt-override-test
  (testing "bootstrap-in! accepts explicit developer prompt"
    (let [[ctx session-id] (create-session-context)]
      (bootstrap/bootstrap-in!
       ctx session-id
       {:register-global-query? false
        :system-prompt          "sys"
        :developer-prompt       "dev"
        :developer-prompt-source :explicit})
      (is (= "dev" (:developer-prompt (ss/get-session-data-in ctx session-id))))
      (is (= :explicit (:developer-prompt-source (ss/get-session-data-in ctx session-id)))))))

;;; Child session infrastructure tests

(deftest create-child-session-dispatch-test
  ;; :session/create-child inserts a child session entry in state without
  ;; changing the parent's active-session-id.
  (let [[ctx session-id] (test-support/make-session-ctx {})
        parent-id     session-id
        child-id    (str (java.util.UUID/randomUUID))
        sys-prompt  "child system"
        tool-schemas [{:name "tool1"}]]
    (testing ":session/create-child dispatch"
      (dispatch/dispatch! ctx
                          :session/create-child
                          {:session-id       parent-id
                           :child-session-id child-id
                           :session-name     "child-work"
                           :system-prompt    sys-prompt
                           :tool-schemas     tool-schemas
                           :thinking-level   :off}
                          {:origin :core})
      (let [sessions   (ss/get-sessions-map-in ctx)
            child-entry (get sessions child-id)
            child-sd   (:data child-entry)]
        (testing "child entry exists in sessions map"
          (is (some? child-entry)
              (str "expected child session in sessions, keys: " (keys sessions))))
        (testing "spawn-mode is :agent"
          (is (= :agent (:spawn-mode child-sd))
              (str "spawn-mode should be :agent, got " (:spawn-mode child-sd))))
        (testing "parent-session-id is the parent"
          (is (= parent-id (:parent-session-id child-sd))
              (str "parent-session-id should be " parent-id
                   ", got " (:parent-session-id child-sd))))
        (testing "system-prompt matches"
          (is (= sys-prompt (:system-prompt child-sd))
              (str "system-prompt should be " sys-prompt
                   ", got " (:system-prompt child-sd))))
        (testing "parent active-session-id unchanged"
          (is (= parent-id (:session-id (ss/get-session-data-in ctx parent-id)))
              "parent active-session-id must not change"))
        (testing "child journal is empty"
          (let [child-journal (ss/get-state-value-in
                               ctx (ss/state-path :journal child-id))]
            (is (= [] child-journal)
                (str "child journal should be empty, got " child-journal))))
        (testing "child telemetry is initialized"
          (let [telemetry (get-in sessions [child-id :telemetry])]
            (is (some? telemetry)
                "child telemetry should be initialized")))
        (testing "child turn slot is initialized"
          (let [turn (get-in sessions [child-id :turn])]
            (is (= {:ctx nil} turn)
                (str "child turn should be {:ctx nil}, got " turn))))))))
