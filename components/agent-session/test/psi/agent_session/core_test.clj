(ns psi.agent-session.core-test
  "Integration tests for the agent-session component.

  Every test gets its own isolated context via `create-context` (Nullable
  pattern) — no global-state mutations, no ordering dependencies."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [com.fulcrologic.statecharts.chart :as chart]
   [com.fulcrologic.statecharts.elements :as ele]
   [psi.agent-core.core :as agent-core]
   [psi.agent-session.background-job-runtime :as bg-rt]
   [psi.agent-session.bootstrap :as bootstrap]
   [psi.agent-session.core :as session]
   [psi.agent-session.dispatch :as dispatch]
   [psi.agent-session.extension-runtime :as ext-rt]
   [psi.agent-session.extensions :as ext]
   [psi.agent-session.mutations :as mutations]
   [psi.agent-session.persistence :as persist]
   [psi.agent-session.project-preferences :as project-prefs]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.session-state :as ss]
   [psi.agent-session.state-accessors :as sa]
   [psi.agent-session.test-support :as test-support]
   [psi.agent-session.tool-plan :as tool-plan]
   [psi.history.git :as history-git]
   [psi.query.core :as query]
   [psi.recursion.core :as recursion])
  (:import
   (java.io File)))

;; ── Context creation and isolation ─────────────────────────────────────────

(deftest context-isolation-test
  (testing "two contexts are independent"
    (let [ctx-a (session/create-context)
          ctx-b (session/create-context)]
      (dispatch/dispatch! ctx-a :session/set-session-name {:name "alpha"} {:origin :core})
      (is (= "alpha" (:session-name (ss/get-session-data-in ctx-a))))
      (is (nil? (:session-name (ss/get-session-data-in ctx-b))))))

  (testing "create-context starts in :idle phase"
    (let [ctx (session/create-context)]
      (is (= :idle (ss/sc-phase-in ctx)))
      (is (ss/idle-in? ctx))))

  (testing "session lifecycle events can be routed through dispatch statechart boundary"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context {:persist? false})]
      (session/new-session-in! ctx)
      (is (= :idle (ss/sc-phase-in ctx)))
      (session/prompt-in! ctx "hello")
      (is (= :streaming (ss/sc-phase-in ctx)))
      (let [entries     (dispatch/event-log-entries)
            submit-e    (first (filter #(= :session/prompt-submit (:event-type %)) entries))
            prompt-e    (first (filter #(= :session/prompt (:event-type %)) entries))
            execute-e   (first (filter #(= :session/prompt-execute (:event-type %)) entries))
            user-msg    (:user-msg (:event-data execute-e))]
        (is (= :core (:origin submit-e)))
        (is (= :core (:origin prompt-e)))
        (is (= :core (:origin execute-e)))
        (is (= "user" (:role user-msg)))
        (is (= [{:type :text :text "hello"}] (:content user-msg)))
        (is (instance? java.time.Instant (:timestamp user-msg))))))

  (testing "statechart action handlers use pure session-update results"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context {:persist? false})]
      (session/new-session-in! ctx)
      (is (false? (:is-streaming (ss/get-session-data-in ctx))))
      (dispatch/dispatch! ctx :on-streaming-entered nil {:origin :statechart})
      (let [sd    (ss/get-session-data-in ctx)
            entry (last (dispatch/event-log-entries))]
        (is (true? (:is-streaming sd)))
        (is (= :root-state-update (:pure-result-kind entry)))))))

;; ── Session lifecycle ───────────────────────────────────────────────────────

(deftest context-index-registry-test
  (testing "create-context starts with an empty context session index"
    (let [ctx  (session/create-context)]
      (is (some? (ss/active-session-id-in ctx)))
      (is (seq (ss/get-sessions-map-in ctx)))))

  (testing "new-session-in! registers first real session and sets it active"
    (let [ctx        (session/create-context)
          seed-id    (:session-id (ss/get-session-data-in ctx))]
      (session/new-session-in! ctx)
      (let [sid-after (:session-id (ss/get-session-data-in ctx))]
        (is (not= seed-id sid-after))
        (is (= sid-after (ss/active-session-id-in ctx)))
        (is (= #{sid-after} (set (keys (ss/get-sessions-map-in ctx))))))))

  (testing "ensure-session-loaded-in! resumes by context session id"
    (let [cwd   (str (System/getProperty "java.io.tmpdir") "/psi-context-load-" (java.util.UUID/randomUUID))
          _     (.mkdirs (java.io.File. cwd))
          ctx   (session/create-context {:cwd cwd})
          _     (session/new-session-in! ctx)
          sid1  (:session-id (ss/get-session-data-in ctx))
          path1 (:session-file (ss/get-session-data-in ctx))
          _     (persist/flush-journal! (java.io.File. path1)
                                        sid1
                                        cwd
                                        nil
                                        nil
                                        [(persist/thinking-level-entry :off)])
          _     (session/new-session-in! ctx)
          sid2  (:session-id (ss/get-session-data-in ctx))]
      (is (not= sid1 sid2))
      (session/ensure-session-loaded-in! ctx sid1)
      (is (= sid1 (:session-id (ss/get-session-data-in ctx))))
      (is (= sid1 (ss/active-session-id-in ctx)))))

  (testing "set-context-active-session-in! changes active session when id exists"
    (let [ctx (session/create-context)]
      (session/new-session-in! ctx)
      (let [first-id (:session-id (ss/get-session-data-in ctx))]
        (session/new-session-in! ctx)
        (let [second-id (:session-id (ss/get-session-data-in ctx))]
          (ss/set-context-active-session-in! ctx first-id)
          (is (= first-id (ss/active-session-id-in ctx)))
          (ss/set-context-active-session-in! ctx second-id)
          (is (= second-id (ss/active-session-id-in ctx)))))))

  (testing "new-session-in! accepts explicit worktree-path and session-name and keeps prior context peer"
    (let [ctx (session/create-context {:cwd "/repo/main"
                                       :initial-session {:worktree-path "/repo/main"}})]
      (session/new-session-in! ctx {:session-name "main"
                                    :worktree-path "/repo/main"})
      (let [sid-1     (:session-id (ss/get-session-data-in ctx))
            created-1 (:created-at (ss/get-session-data-in ctx sid-1))]
        (Thread/sleep 5)
        (session/new-session-in! ctx {:session-name "feature work"
                                      :worktree-path "/repo/feature-work"})
        (let [sd        (ss/get-session-data-in ctx)
              sid-2     (:session-id sd)
              sessions  (ss/get-sessions-map-in ctx)
              created-2 (:created-at (ss/get-session-data-in ctx sid-2))]
          (is (= "feature work" (:session-name sd)))
          (is (= "/repo/feature-work" (:worktree-path sd)))
          (is (= sid-2 (ss/active-session-id-in ctx)))
          (is (= #{sid-1 sid-2} (set (keys sessions))))
          (is (= "/repo/main" (:worktree-path (ss/get-session-data-in ctx sid-1))))
          (is (instance? java.time.Instant created-1))
          (is (instance? java.time.Instant created-2))
          (is (= created-1 (:created-at (ss/get-session-data-in ctx sid-1))) "existing session keeps original created-at")
          (is (not= created-1 created-2) "new session gets distinct created-at"))))))

(deftest new-session-test
  (testing "new-session-in! resets session-id"
    (let [ctx    (session/create-context)
          old-id (:session-id (ss/get-session-data-in ctx))]
      (session/new-session-in! ctx)
      (let [new-id (:session-id (ss/get-session-data-in ctx))]
        (is (not= old-id new-id)))))

  (testing "new-session-in! clears queues"
    (let [ctx (session/create-context)]
      (session/steer-in! ctx "steer me")
      (session/new-session-in! ctx)
      (let [sd (ss/get-session-data-in ctx)]
        (is (= [] (:steering-messages sd))))))

  (testing "new-session-in! is cancelled when extension returns cancel"
    (let [ctx (session/create-context)
          reg (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/cancel")
      (ext/register-handler-in! reg "/ext/cancel" "session_before_switch"
                                (fn [_] {:cancel true}))
      (let [old-id (:session-id (ss/get-session-data-in ctx))]
        (session/new-session-in! ctx)
        ;; session-id unchanged because cancelled
        (is (= old-id (:session-id (ss/get-session-data-in ctx)))))))

  (testing "new-session-in! appends active model entry when model exists"
    (let [model {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}
          ctx   (session/create-context {:initial-session {:model model}})]
      (session/new-session-in! ctx)
      (is (some #(and (= :model (:kind %))
                      (= "anthropic" (get-in % [:data :provider]))
                      (= "claude-3-5-sonnet" (get-in % [:data :model-id])))
                (persist/all-entries-in ctx)))))

  (testing "new-session-in! resets startup telemetry"
    (let [ctx (test-support/make-session-ctx
               {:session-data {:startup-prompts [{:id "engage-nucleus"}]
                               :startup-bootstrap-completed? true
                               :startup-bootstrap-started-at (java.time.Instant/now)
                               :startup-bootstrap-completed-at (java.time.Instant/now)
                               :startup-message-ids ["m1"]}})]
      (session/new-session-in! ctx)
      (let [sd (ss/get-session-data-in ctx)]
        (is (= [] (:startup-prompts sd)))
        (is (false? (:startup-bootstrap-completed? sd)))
        (is (nil? (:startup-bootstrap-started-at sd)))
        (is (nil? (:startup-bootstrap-completed-at sd)))
        (is (= [] (:startup-message-ids sd)))))))

(deftest fork-session-resets-startup-telemetry-test
  (let [ctx (test-support/make-session-ctx {})]
    (session/new-session-in! ctx)
    (let [entry-id (:id (ss/journal-append-in! ctx (persist/message-entry {:role "user"
                                                                           :content [{:type :text :text "hello"}]
                                                                           :timestamp (java.time.Instant/now)})))]
      (test-support/update-state! ctx :session-data merge {:startup-prompts [{:id "engage-nucleus"}]
                                                           :startup-bootstrap-completed? true
                                                           :startup-bootstrap-started-at (java.time.Instant/now)
                                                           :startup-bootstrap-completed-at (java.time.Instant/now)
                                                           :startup-message-ids ["m1"]})
      (session/fork-session-in! ctx entry-id)
      (let [sd (ss/get-session-data-in ctx)]
        (is (= [] (:startup-prompts sd)))
        (is (false? (:startup-bootstrap-completed? sd)))
        (is (nil? (:startup-bootstrap-started-at sd)))
        (is (nil? (:startup-bootstrap-completed-at sd)))
        (is (= [] (:startup-message-ids sd)))))))

(deftest fork-session-persists-child-file-with-parent-lineage-test
  (let [cwd        (str (System/getProperty "java.io.tmpdir") "/psi-fork-" (java.util.UUID/randomUUID))
        _          (.mkdirs (java.io.File. cwd))
        ctx        (session/create-context {:cwd cwd})
        _          (session/new-session-in! ctx)
        parent-sd  (ss/get-session-data-in ctx)
        parent-file (:session-file parent-sd)
        entry-id   (:id (ss/journal-append-in! ctx (persist/message-entry {:role "user"
                                                                           :content [{:type :text :text "branch-here"}]
                                                                           :timestamp (java.time.Instant/now)})))]
    (session/fork-session-in! ctx entry-id)
    (let [child-sd     (ss/get-session-data-in ctx)
          child-file   (:session-file child-sd)
          loaded-child (persist/load-session-file child-file)]
      (is (string? child-file))
      (is (.exists (java.io.File. child-file)))
      (is (= parent-file (get-in loaded-child [:header :parent-session])))
      (is (= (:session-id parent-sd) (get-in loaded-child [:header :parent-session-id])))
      (is (= (:session-id child-sd) (get-in loaded-child [:header :id])))
      (is (= (count (persist/all-entries-in ctx)) (count (:entries loaded-child)))))))

(deftest resume-session-model-fallback-test
  (testing "resume-session-in! keeps current model when resumed journal has no model entry"
    (let [initial-model {:provider "openai" :id "gpt-5.3-codex" :reasoning true}
          ctx           (session/create-context {:initial-session {:model initial-model
                                                                   :thinking-level :high}})
          f             (File/createTempFile "psi-resume-no-model" ".ndedn")
          entries       [(persist/thinking-level-entry :minimal)
                         (persist/message-entry {:role "user"
                                                 :content [{:type :text :text "hello"}]
                                                 :timestamp (java.time.Instant/now)})]]
      (.deleteOnExit f)
      (persist/flush-journal! f "sess-no-model" "/tmp/project" nil entries)
      (session/resume-session-in! ctx (.getAbsolutePath f))
      (let [sd (ss/get-session-data-in ctx)
            r  (session/query-in ctx [:psi.agent-session/model-provider
                                      :psi.agent-session/model-id
                                      :psi.agent-session/thinking-level])]
        (is (= initial-model (:model sd)))
        (is (= :minimal (:thinking-level sd)))
        (is (= "openai" (:psi.agent-session/model-provider r)))
        (is (= "gpt-5.3-codex" (:psi.agent-session/model-id r)))
        (is (= :minimal (:psi.agent-session/thinking-level r))))))

  (testing "resume-session-in! restores persisted worktree-path and runtime prompt metadata from header"
    (let [ctx (session/create-context {:cwd "/repo/main"
                                       :initial-session {:system-prompt "base prompt"
                                                         :model {:provider "openai"
                                                                 :id "gpt-5.3-codex"
                                                                 :reasoning true}}})
          f   (File/createTempFile "psi-resume-worktree" ".ndedn")]
      (.deleteOnExit f)
      (spit f (str "{:type :session :version 4 :id \"sess-worktree\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/legacy/cwd\" :worktree-path \"/repo/feature-x\"}\n"
                   "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :thinking-level :data {:thinking-level :medium}}\n"
                   "{:id \"e2\" :parent-id \"e1\" :timestamp #inst \"2024-01-01T00:00:02Z\" :kind :session-info :data {:name \"Feature X\"}}\n"
                   "{:id \"e3\" :parent-id \"e2\" :timestamp #inst \"2024-01-01T00:00:03Z\" :kind :message :data {:message {:role \"assistant\" :content [{:type :text :text \"hello\"}]}}}\n"))
      (session/resume-session-in! ctx (.getAbsolutePath f))
      (let [sd     (ss/get-session-data-in ctx)
            result (session/query-in ctx [:psi.agent-session/cwd
                                          :psi.agent-session/session-name
                                          :psi.agent-session/system-prompt])]
        (is (= "/repo/feature-x" (:worktree-path sd)))
        (is (= "/repo/feature-x" (ss/effective-cwd-in ctx)))
        (is (= "/repo/feature-x" (:psi.agent-session/cwd result)))
        (is (= "Feature X" (:session-name sd)))
        (is (= "Feature X" (:psi.agent-session/session-name result)))
        (is (= "base prompt" (:psi.agent-session/system-prompt result))))))

  (testing "resume-session-in! missing-file fallback is logged through dispatch"
    (dispatch/clear-event-log!)
    (let [f   (str (System/getProperty "java.io.tmpdir") "/psi-missing-" (java.util.UUID/randomUUID) ".ndedn")
          ctx (session/create-context {:persist? false})]
      (session/resume-session-in! ctx f)
      (let [sd    (ss/get-session-data-in ctx)
            entry (first (filter #(= :session/resume-missing-initialize (:event-type %))
                                 (dispatch/event-log-entries)))]
        (is (= f (:session-file sd)))
        (is (= [] (persist/all-entries-in ctx)))
        (is (= :core (:origin entry)))
        (is (= {:session-path f} (:event-data entry)))))))

;; ── Model management ────────────────────────────────────────────────────────

(deftest model-management-test
  (testing "set-model-in! updates model and persists entry"
    (let [ctx   (session/create-context)
          model {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}]
      (dispatch/dispatch! ctx :session/set-model {:model model} {:origin :core})
      (let [sd (ss/get-session-data-in ctx)]
        (is (= model (:model sd))))
      (is (pos? (count (persist/all-entries-in ctx))))))

  (testing "set-model-in! clamps thinking level for non-reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :high}})]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (is (= :off (:thinking-level (ss/get-session-data-in ctx))))))

  (testing "set-model-in! preserves thinking level for reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :high}})]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (is (= :high (:thinking-level (ss/get-session-data-in ctx)))))))

;; ── Thinking level ──────────────────────────────────────────────────────────

(deftest model-thinking-dispatch-test
  (testing "set-model-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx   (session/create-context)
          model {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}]
      (dispatch/dispatch! ctx :session/set-model {:model model} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-model (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:model model} (:event-data entry))))))

  (testing "set-thinking-level-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (dispatch/clear-event-log!)
      (dispatch/dispatch! ctx :session/set-thinking-level {:level :medium} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-thinking-level (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:level :medium} (:event-data entry))))))

  (testing "set-system-prompt-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-system-prompt {:prompt "graph-aware system prompt"} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-system-prompt (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:prompt "graph-aware system prompt"} (:event-data entry))))))

  (testing "refresh-system-prompt-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/refresh-system-prompt nil {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/refresh-system-prompt (:event-type entry)))
        (is (= :core (:origin entry)))))))

(deftest prompt-contribution-dispatch-test
  (testing "register/update/unregister prompt contributions route through dispatch and preserve return payloads"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)
          r1  (dispatch/dispatch! ctx :session/register-prompt-contribution
                                  {:ext-path "/ext/a" :id "c1"
                                   :contribution {:section "Extension Capabilities"
                                                  :content "tool: x"
                                                  :priority 10}}
                                  {:origin :core})
          e1  (last (dispatch/event-log-entries))
          r2  (dispatch/dispatch! ctx :session/update-prompt-contribution
                                  {:ext-path "/ext/a" :id "c1" :patch {:content "tool: y"}}
                                  {:origin :core})
          e2  (last (dispatch/event-log-entries))
          r3  (dispatch/dispatch! ctx :session/unregister-prompt-contribution
                                  {:ext-path "/ext/a" :id "c1"}
                                  {:origin :core})
          e3  (last (dispatch/event-log-entries))]
      (is (true? (:registered? r1)))
      (is (= :session/register-prompt-contribution (:event-type e1)))
      (is (= :core (:origin e1)))

      (is (true? (:updated? r2)))
      (is (= "tool: y" (:content (:contribution r2))))
      (is (= :session/update-prompt-contribution (:event-type e2)))
      (is (= :core (:origin e2)))

      (is (true? (:removed? r3)))
      (is (= :session/unregister-prompt-contribution (:event-type e3)))
      (is (= :core (:origin e3))))))

(deftest dispatch-registry-and-event-log-eql-test
  (testing "query-in resolves registered dispatch handler metadata attrs"
    (let [ctx (session/create-context)
          result (session/query-in ctx
                                   [:psi.agent-session/registered-dispatch-event-count
                                    {:psi.agent-session/registered-dispatch-events
                                     [:psi.dispatch-handler/event-type]}])]
      (is (pos? (:psi.agent-session/registered-dispatch-event-count result)))
      (is (seq (:psi.agent-session/registered-dispatch-events result)))
      (is (some #(= :session/set-session-name
                    (:psi.dispatch-handler/event-type %))
                (:psi.agent-session/registered-dispatch-events result)))))

  (testing "query-in resolves dispatch event log attrs"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-session-name {:name "dispatch-visible"} {:origin :core})
      (let [result (session/query-in ctx
                                     [:psi.agent-session/dispatch-event-log-count
                                      {:psi.agent-session/dispatch-event-log
                                       [:psi.dispatch-event/event-type
                                        :psi.dispatch-event/origin
                                        :psi.dispatch-event/event-data
                                        :psi.dispatch-event/replaying?
                                        :psi.dispatch-event/statechart-claimed?
                                        :psi.dispatch-event/pure-result-kind
                                        :psi.dispatch-event/declared-effects
                                        :psi.dispatch-event/applied-effects
                                        :psi.dispatch-event/db-summary-before
                                        :psi.dispatch-event/db-summary-after]}])]
        (is (pos? (:psi.agent-session/dispatch-event-log-count result)))
        (let [entry (last (:psi.agent-session/dispatch-event-log result))]
          (is (= :session/set-session-name (:psi.dispatch-event/event-type entry)))
          (is (= :core (:psi.dispatch-event/origin entry)))
          (is (= {:name "dispatch-visible"} (:psi.dispatch-event/event-data entry)))
          (is (false? (:psi.dispatch-event/replaying? entry)))
          (is (false? (:psi.dispatch-event/statechart-claimed? entry)))
          (is (= :root-state-update (:psi.dispatch-event/pure-result-kind entry)))
          (is (= [{:effect/type :persist/journal-append-session-info-entry
                   :name "dispatch-visible"}]
                 (:psi.dispatch-event/declared-effects entry)))
          (is (= [{:effect/type :persist/journal-append-session-info-entry
                   :name "dispatch-visible"}]
                 (:psi.dispatch-event/applied-effects entry)))
          (is (map? (:psi.dispatch-event/db-summary-before entry)))
          (is (map? (:psi.dispatch-event/db-summary-after entry)))))))

  (testing "replay-dispatch-event-log-in! replays retained entries against session state"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-session-name {:name "replay me"} {:origin :core})
      (dispatch/dispatch! ctx :session/set-worktree-path {:worktree-path "/repo/replay"} {:origin :core})
      (is (= "replay me" (:session-name (ss/get-session-data-in ctx))))
      (is (= "/repo/replay" (:worktree-path (ss/get-session-data-in ctx))))
      (ss/apply-root-state-update-in! ctx (ss/session-update #(assoc % :session-name "before" :worktree-path "/repo/main")))
      (session/replay-dispatch-event-log-in! ctx)
      (let [sd (ss/get-session-data-in ctx)]
        (is (= "replay me" (:session-name sd)))
        (is (= "/repo/replay" (:worktree-path sd))))))

  (testing "set-context-active-session-in! is logged through dispatch"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (session/new-session-in! ctx)
      (let [first-id (:session-id (ss/get-session-data-in ctx))]
        (session/new-session-in! ctx)
        (ss/set-context-active-session-in! ctx first-id)
        (let [entries (dispatch/event-log-entries)
              entry   (last entries)]
          (is (= :session/set-context-active-session (:event-type entry)))
          (is (= :core (:origin entry)))
          (is (= {:session-id first-id} (:event-data entry)))))))

  (testing "new-session-in! initialization is logged through dispatch"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context {:persist? false})
          old-id (:session-id (ss/get-session-data-in ctx))]
      (session/new-session-in! ctx {:session-name "dispatch-new"
                                    :worktree-path "/repo/dispatch-new"})
      (let [entry (first (filter #(= :session/new-initialize (:event-type %))
                                 (dispatch/event-log-entries)))]
        (is (not= old-id (:session-id (ss/get-session-data-in ctx))))
        (is (= :core (:origin entry)))
        (is (= "dispatch-new" (get-in entry [:event-data :session-name])))
        (is (= "/repo/dispatch-new" (get-in entry [:event-data :worktree-path]))))))

  (testing "resume-session-in! loaded state is logged through dispatch"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context {:initial-session {:model {:provider "openai"
                                                                 :id "gpt-5.3-codex"
                                                                 :reasoning true}}
                                       :persist? false})
          f   (File/createTempFile "psi-resume-dispatch" ".ndedn")]
      (.deleteOnExit f)
      (spit f (str "{:type :session :version 4 :id \"sess-dispatch\" :timestamp #inst \"2024-01-01T00:00:00Z\" :cwd \"/legacy/cwd\" :worktree-path \"/repo/resume-dispatch\"}\n"
                   "{:id \"e1\" :parent-id nil :timestamp #inst \"2024-01-01T00:00:01Z\" :kind :thinking-level :data {:thinking-level :medium}}\n"
                   "{:id \"e2\" :parent-id \"e1\" :timestamp #inst \"2024-01-01T00:00:02Z\" :kind :session-info :data {:name \"Resume Dispatch\"}}\n"))
      (session/resume-session-in! ctx (.getAbsolutePath f))
      (let [entry (first (filter #(= :session/resume-loaded (:event-type %))
                                 (dispatch/event-log-entries)))]
        (is (= :core (:origin entry)))
        (is (= "sess-dispatch" (get-in entry [:event-data :session-id])))
        (is (= "/repo/resume-dispatch" (get-in entry [:event-data :worktree-path])))
        (is (= :medium (get-in entry [:event-data :thinking-level]))))))

  (testing "fork-session-in! initialization is logged through dispatch"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context {:persist? false})]
      (session/new-session-in! ctx)
      (let [parent-id (:session-id (ss/get-session-data-in ctx))
            entry-id  (:id (ss/journal-append-in! ctx (persist/message-entry {:role "user"
                                                                              :content [{:type :text :text "fork-dispatch"}]
                                                                              :timestamp (java.time.Instant/now)})))]
        (session/fork-session-in! ctx entry-id)
        (let [entry (first (filter #(= :session/fork-initialize (:event-type %))
                                   (dispatch/event-log-entries)))]
          (is (= :core (:origin entry)))
          (is (= parent-id (get-in entry [:event-data :parent-session-id])))
          (is (= entry-id (get-in entry [:event-data :entry-id])))
          (is (pos? (get-in entry [:event-data :entry-count]))))))))

(deftest session-update-helper-consolidation-test
  (testing "session-update wrapper composes with apply-root-state-update-in! to update session data and context index"
    (let [ctx (session/create-context)]
      (ss/apply-root-state-update-in! ctx (ss/session-update #(assoc % :session-name "helper-name")))
      (let [sd  (ss/get-session-data-in ctx)
            sid (:session-id sd)]
        (is (= "helper-name" (:session-name sd)))
        (is (= "helper-name" (:session-name (ss/get-session-data-in ctx sid))))
        (is (= sid (ss/active-session-id-in ctx)))))))

(deftest projection-and-transition-helper-dispatch-test
  (testing "projection setters still route through dispatch after transition-helper extraction"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-rpc-trace {:enabled? true :file "/tmp/rpc-trace.ndedn"} {:origin :core})
      (sa/set-nrepl-runtime-in! ctx {:host "localhost" :port 5555 :endpoint "localhost:5555"})
      (sa/set-oauth-projection-in! ctx {:authenticated-providers ["anthropic"]})
      (sa/set-recursion-state-in! ctx {:status :idle})
      (let [state @(:state* ctx)
            events (mapv :event-type (dispatch/event-log-entries))]
        (is (= {:enabled? true :file "/tmp/rpc-trace.ndedn"}
               (get-in state [:runtime :rpc-trace])))
        (is (= {:host "localhost" :port 5555 :endpoint "localhost:5555"}
               (get-in state [:runtime :nrepl])))
        (is (= {:authenticated-providers ["anthropic"]}
               (get-in state [:oauth])))
        (is (= {:status :idle}
               (get-in state [:recursion])))
        (is (= [:session/set-rpc-trace
                :session/set-nrepl-runtime
                :session/set-oauth-projection
                :session/set-recursion-state]
               events))))

    (testing "telemetry setters now route through dispatch too"
      (dispatch/clear-event-log!)
      (let [ctx  (session/create-context)
            stat {:tool-name "bash" :context-bytes-added 12}
            _    (sa/set-turn-context-in! ctx {:turn-id "t-1"})
            _    (sa/append-tool-call-attempt-in! ctx {:id "tc-1" :name "read"})
            _    (sa/append-provider-request-capture-in! ctx {:provider "anthropic" :turn-id "t-1"})
            _    (sa/append-provider-reply-capture-in! ctx {:provider "anthropic" :turn-id "t-1" :event {:type :done}})
            _    (sa/record-tool-output-stat-in! ctx stat 12 false)
            state @(:state* ctx)
            events (mapv :event-type (dispatch/event-log-entries))]
        (let [sid (get-in state [:agent-session :active-session-id])]
          (is (= {:turn-id "t-1"} (get-in state [:agent-session :sessions sid :turn :ctx])))
          (is (= "tc-1" (get-in state [:agent-session :sessions sid :telemetry :tool-call-attempts 0 :id])))
          (is (= "anthropic" (get-in state [:agent-session :sessions sid :telemetry :provider-requests 0 :provider])))
          (is (= "anthropic" (get-in state [:agent-session :sessions sid :telemetry :provider-replies 0 :provider])))
          (is (= [stat] (get-in state [:agent-session :sessions sid :telemetry :tool-output-stats :calls]))))
        (is (= [:session/set-turn-context
                :session/append-tool-call-attempt
                :session/append-provider-request-capture
                :session/append-provider-reply-capture
                :session/record-tool-output-stat]
               events))))))

(deftest interrupt-and-bootstrap-prompt-dispatch-test
  (testing "request-interrupt-in! routes session-data changes through dispatch"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (test-support/update-state! ctx :session-data assoc
                                  :interrupt-pending false
                                  :steering-messages ["queued steer"])
      (session/prompt-in! ctx "interrupt me")
      (session/request-interrupt-in! ctx)
      (let [sd    (ss/get-session-data-in ctx)
            entry (last (dispatch/event-log-entries))]
        (is (true? (:interrupt-pending sd)))
        (is (= [] (:steering-messages sd)))
        (is (instance? java.time.Instant (:interrupt-requested-at sd)))
        (is (= :session/request-interrupt (:event-type entry)))
        (is (= :core (:origin entry))))))

  (testing "bootstrap-in! prompt metadata initialization is logged through dispatch"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (bootstrap/bootstrap-in!
       ctx {:register-global-query? false
            :system-prompt          "sys"
            :developer-prompt       "dev"
            :developer-prompt-source :explicit})
      (let [sd    (ss/get-session-data-in ctx)
            entry (first (filter #(= :session/bootstrap-prompt-state (:event-type %))
                                 (dispatch/event-log-entries)))]
        (is (= "sys" (:base-system-prompt sd)))
        (is (= "sys" (:system-prompt sd)))
        (is (= "dev" (:developer-prompt sd)))
        (is (= :explicit (:developer-prompt-source sd)))
        (is (= :core (:origin entry)))
        (is (= {:system-prompt "sys"
                :developer-prompt "dev"
                :developer-prompt-source :explicit}
               (:event-data entry)))))))

(deftest thinking-level-test
  (testing "set-thinking-level-in! updates level"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (dispatch/dispatch! ctx :session/set-thinking-level {:level :medium} {:origin :core})
      (is (= :medium (:thinking-level (ss/get-session-data-in ctx))))))

  (testing "set-model-in! persists project preferences"
    (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-project-prefs-" (java.util.UUID/randomUUID))
          _   (.mkdirs (java.io.File. cwd))
          ctx (session/create-context {:cwd cwd})
          model {:provider "anthropic" :id "claude-sonnet-4-6" :reasoning true}]
      (dispatch/dispatch! ctx :session/set-model {:model model} {:origin :core})
      (let [prefs (project-prefs/read-preferences cwd)]
        (is (= "anthropic" (get-in prefs [:agent-session :model-provider])))
        (is (= "claude-sonnet-4-6" (get-in prefs [:agent-session :model-id])))
        (is (= :off (get-in prefs [:agent-session :thinking-level]))))))

  (testing "set-thinking-level-in! persists project preferences"
    (let [cwd (str (System/getProperty "java.io.tmpdir") "/psi-project-prefs-" (java.util.UUID/randomUUID))
          _   (.mkdirs (java.io.File. cwd))
          ctx (session/create-context {:cwd cwd})]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (dispatch/dispatch! ctx :session/set-thinking-level {:level :high} {:origin :core})
      (let [prefs (project-prefs/read-preferences cwd)]
        (is (= :high (get-in prefs [:agent-session :thinking-level]))))))

  (testing "cycle-thinking-level-in! advances level for reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :off}})]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning true}} {:origin :core})
      (session/cycle-thinking-level-in! ctx)
      (is (= :minimal (:thinking-level (ss/get-session-data-in ctx))))))

  (testing "cycle-thinking-level-in! is no-op for non-reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :off}})]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (session/cycle-thinking-level-in! ctx)
      (is (= :off (:thinking-level (ss/get-session-data-in ctx)))))))

;; ── Session naming ──────────────────────────────────────────────────────────

(deftest session-naming-test
  (testing "set-session-name-in! updates name and appends entry"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-session-name {:name "my session"} {:origin :core})
      (is (= "my session" (:session-name (ss/get-session-data-in ctx))))
      (is (some #(= :session-info (:kind %)) (persist/all-entries-in ctx))))))

(deftest session-config-dispatch-test
  (testing "set-session-name-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-session-name {:name "my session"} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-session-name (:event-type entry)))
        (is (= :core (:origin entry)))
        (is (= {:name "my session"} (:event-data entry))))))

  (testing "set-worktree-path-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-worktree-path {:worktree-path "/repo/feature-z"} {:origin :core})
      (is (= "/repo/feature-z" (:worktree-path (ss/get-session-data-in ctx))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-worktree-path (:event-type entry)))
        (is (= :core (:origin entry)))

        (is (= {:worktree-path "/repo/feature-z"} (:event-data entry))))))

  (testing "set-cache-breakpoints-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-cache-breakpoints {:breakpoints #{:system :tools}} {:origin :core})
      (is (= #{:system :tools} (:cache-breakpoints (ss/get-session-data-in ctx))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-cache-breakpoints (:event-type entry)))
        (is (= :core (:origin entry)))

        (is (= {:breakpoints #{:system :tools}} (:event-data entry))))))

  (testing "set-active-tools-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx       (session/create-context)
          tool-maps [{:name "read"} {:name "bash"}]]
      (dispatch/dispatch! ctx :session/set-active-tools {:tool-maps tool-maps} {:origin :core})
      (is (= #{"read" "bash"} (:active-tools (ss/get-session-data-in ctx))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-active-tools (:event-type entry)))
        (is (= :core (:origin entry)))

        (is (= {:tool-maps tool-maps} (:event-data entry)))))))

;; ── Context token tracking ──────────────────────────────────────────────────

(deftest context-usage-test
  (testing "update-context-usage-in! stores tokens and window"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/update-context-usage {:tokens 5000 :window 100000} {:origin :core})
      (let [sd (ss/get-session-data-in ctx)]
        (is (= 5000 (:context-tokens sd)))
        (is (= 100000 (:context-window sd))))))

  (testing "update-context-usage-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/update-context-usage {:tokens 5000 :window 100000} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/update-context-usage (:event-type entry)))
        (is (= :core (:origin entry)))

        (is (= {:tokens 5000 :window 100000} (:event-data entry))))))

  (testing "context fraction reflects stored usage"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/update-context-usage {:tokens 80000 :window 100000} {:origin :core})
      (let [sd (ss/get-session-data-in ctx)]
        (is (= 0.8 (session-data/context-fraction-used sd))))))

  (testing "queued input helpers and bootstrap resource registration route through dispatch"
    (dispatch/clear-event-log!)
    (let [ctx      (session/create-context)
          template {:name "greet" :description "d" :content "c" :source :project :file-path "/tmp/greet.md"}
          skill    {:name "coding" :description "d" :file-path "/tmp/SKILL.md"
                    :base-dir "/tmp" :source :project :disable-model-invocation false}]
      (session/steer-in! ctx "steer")
      (session/follow-up-in! ctx "follow")
      (dispatch/dispatch! ctx :session/register-prompt-template {:template template} {:origin :core})
      (dispatch/dispatch! ctx :session/register-skill {:skill skill} {:origin :core})
      (session/consume-queued-input-text-in! ctx)
      (let [sd           (ss/get-session-data-in ctx)
            entries       (dispatch/event-log-entries)
            event-types   (mapv :event-type entries)]
        (is (= [] (:steering-messages sd)))
        (is (= [] (:follow-up-messages sd)))
        (is (= 1 (count (:prompt-templates sd))))
        (is (= 1 (count (:skills sd))))
        (is (= [:session/enqueue-steering-message
                :session/enqueue-follow-up-message
                :session/register-prompt-template
                :session/register-skill
                :session/clear-queued-messages]
               event-types))))))

;; ── Auto-retry and compaction config ───────────────────────────────────────

(deftest config-flags-test
  (testing "set-auto-retry-in! enables/disables retry"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-auto-retry {:enabled? false} {:origin :core})
      (is (false? (:auto-retry-enabled (ss/get-session-data-in ctx))))))

  (testing "set-auto-compaction-in! enables/disables compaction"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-auto-compaction {:enabled? true} {:origin :core})
      (is (true? (:auto-compaction-enabled (ss/get-session-data-in ctx)))))))

(deftest config-flags-dispatch-test
  (testing "set-auto-retry-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-auto-retry {:enabled? false} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-auto-retry (:event-type entry)))
        (is (= :core (:origin entry)))

        (is (= {:enabled? false} (:event-data entry))))))

  (testing "set-auto-compaction-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-auto-compaction {:enabled? true} {:origin :core})
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-auto-compaction (:event-type entry)))
        (is (= :core (:origin entry)))

        (is (= {:enabled? true} (:event-data entry))))))

  (testing "set-ui-type-in! routes through dispatch log"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-ui-type {:ui-type :emacs} {:origin :core})
      (is (= :emacs (:ui-type (ss/get-session-data-in ctx))))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-ui-type (:event-type entry)))
        (is (= :core (:origin entry)))

        (is (= {:ui-type :emacs} (:event-data entry)))))))

;; ── Manual compaction ───────────────────────────────────────────────────────

(deftest manual-compaction-test
  (testing "manual-compact-in! runs stub compaction and returns to idle"
    (dispatch/clear-event-log!)
    (let [ctx    (session/create-context)
          result (session/manual-compact-in! ctx)]
      (is (string? (:summary result)))
      (is (= :idle (ss/sc-phase-in ctx)))
      (let [event-types (mapv :event-type (dispatch/event-log-entries))]
        (is (= [:session/compact-start
                :session/compaction-finished
                :session/manual-compaction-execute
                :session/compact-done]
               event-types)))))

  (testing "manual-compact-in! with custom compaction-fn uses that fn"
    (dispatch/clear-event-log!)
    (let [custom-fn (fn [_sd _prep _instr]
                      {:summary "custom summary"
                       :first-kept-entry-id nil
                       :tokens-before nil
                       :details nil})
          ctx    (session/create-context {:compaction-fn custom-fn})
          result (session/manual-compact-in! ctx)]
      (is (= "custom summary" (:summary result)))
      (let [entry (first (filter #(= :session/manual-compaction-execute (:event-type %))
                                 (dispatch/event-log-entries)))]
        (is (= :core (:origin entry)))
        (is (= {:custom-instructions nil} (:event-data entry))))))

  (testing "manual-compact-in! can be cancelled by extension"
    (dispatch/clear-event-log!)
    (let [ctx (session/create-context)
          reg (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                (fn [_] {:cancel true}))
      (let [result (session/manual-compact-in! ctx)]
        (is (nil? result))
        (let [event-types (mapv :event-type (dispatch/event-log-entries))]
          (is (= [:session/compact-start
                  :session/manual-compaction-execute
                  :session/compact-done]
                 event-types))))))

  (testing "extension can supply custom CompactionResult"
    (dispatch/clear-event-log!)
    (let [custom-result {:summary "ext summary"
                         :first-kept-entry-id nil
                         :tokens-before nil
                         :details nil}
          ctx (session/create-context)
          reg (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                (fn [_] {:compaction custom-result}))
      (let [result (session/manual-compact-in! ctx)]
        (is (= "ext summary" (:summary result)))
        (let [event-types (mapv :event-type (dispatch/event-log-entries))]
          (is (= [:session/compact-start
                  :session/compaction-finished
                  :session/manual-compaction-execute
                  :session/compact-done]
                 event-types)))))))

;; ── Extension dispatch ──────────────────────────────────────────────────────

(deftest extension-dispatch-test
  (testing "dispatch-extension-event-in! fires handlers"
    (let [ctx   (session/create-context)
          fired (atom false)]
      (ext/register-extension-in! (:extension-registry ctx) "/ext/a")
      (ext/register-handler-in! (:extension-registry ctx) "/ext/a" "my_event"
                                (fn [_] (reset! fired true) nil))
      (ext/dispatch-in (:extension-registry ctx) "my_event" {:x 1})
      (is (true? @fired)))))

;; ── Diagnostics ─────────────────────────────────────────────────────────────

(deftest diagnostics-test
  (testing "diagnostics-in returns all expected keys"
    (let [ctx (session/create-context)
          d   (session/diagnostics-in ctx)]
      (is (contains? d :phase))
      (is (contains? d :session-id))
      (is (contains? d :is-idle))
      (is (contains? d :is-streaming))
      (is (contains? d :is-compacting))
      (is (contains? d :is-retrying))
      (is (contains? d :model))
      (is (contains? d :thinking-level))
      (is (contains? d :pending-messages))
      (is (contains? d :retry-attempt))
      (is (contains? d :extension-count))
      (is (contains? d :journal-entries))
      (is (contains? d :agent-diagnostics))))

  (testing "diagnostics-in reflects session state"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/set-model {:model {:provider "x" :id "y" :reasoning false}} {:origin :core})
      (dispatch/dispatch! ctx :session/set-session-name {:name "test"} {:origin :core})
      (let [d (session/diagnostics-in ctx)]
        (is (= :idle (:phase d)))
        (is (true? (:is-idle d)))
        (is (= {:provider "x" :id "y" :reasoning false} (:model d)))
        (is (pos? (:journal-entries d)))))))

;; ── EQL introspection ───────────────────────────────────────────────────────

(deftest eql-introspection-test
  (testing "query-in resolves :psi.agent-session/session-id"
    (let [ctx    (session/create-context)
          result (session/query-in ctx [:psi.agent-session/session-id])]
      (is (string? (:psi.agent-session/session-id result)))))

  (testing "query-in resolves phase"
    (let [ctx    (session/create-context)
          result (session/query-in ctx [:psi.agent-session/phase
                                        :psi.agent-session/is-idle])]
      (is (= :idle (:psi.agent-session/phase result)))
      (is (true? (:psi.agent-session/is-idle result)))))

  (testing "query-in resolves model after set-model-in!"
    (let [ctx   (session/create-context)
          model {:provider "x" :id "y" :reasoning false}]
      (dispatch/dispatch! ctx :session/set-model {:model model} {:origin :core})
      (let [result (session/query-in ctx [:psi.agent-session/model])]
        (is (= model (:psi.agent-session/model result))))))

  (testing "set-system-prompt-in! updates session + agent-core prompt state"
    (let [ctx    (session/create-context)
          prompt "graph-aware system prompt"
          _      (dispatch/dispatch! ctx :session/set-system-prompt {:prompt prompt} {:origin :core})
          result (session/query-in ctx [:psi.agent-session/system-prompt])]
      (is (= prompt (:psi.agent-session/system-prompt result)))
      (is (= prompt (:system-prompt (agent-core/get-data-in (ss/agent-ctx-in ctx)))))))

  (testing "new-session-in! preserves stable prompt (cwd frozen at context creation)"
    (let [ctx       (session/create-context {:cwd "/tmp/main"})
          base      (str "Prompt body"
                         "\nCurrent date and time: Friday, March 13, 2026 at 11:00:00 am GMT-04:00"
                         "\nCurrent working directory: /tmp/main"
                         "\nCurrent worktree directory: /tmp/main")]
      (test-support/update-state! ctx :session-data merge {:worktree-path "/tmp/main"
                                                           :base-system-prompt base
                                                           :system-prompt base})
      (session/new-session-in! ctx {:worktree-path "/tmp/worktree"})
      (let [sd (ss/get-session-data-in ctx)]
        ;; Prompt cwd is frozen at context creation — does not change with worktree
        (is (str/includes? (:system-prompt sd) "Current working directory: /tmp/main"))
        (is (= (:base-system-prompt sd) (:system-prompt sd))))))

  (testing "query-in resolves graph capabilities via agent-session bridge"
    (let [ctx    (session/create-context)
          result (session/query-in ctx [:psi.graph/capabilities])]
      (is (vector? (:psi.graph/capabilities result)))
      (is (seq (:psi.graph/capabilities result)))
      (is (some #(= :agent-session (:domain %))
                (:psi.graph/capabilities result)))))

  (testing "query-in resolves developer prompt"
    (let [ctx (session/create-context {:initial-session {:developer-prompt "dev layer"
                                                         :developer-prompt-source :explicit}})
          result (session/query-in ctx [:psi.agent-session/developer-prompt
                                        :psi.agent-session/developer-prompt-source
                                        :psi.agent-session/prompt-layers])]
      (is (= "dev layer" (:psi.agent-session/developer-prompt result)))
      (is (= :explicit (:psi.agent-session/developer-prompt-source result)))
      (is (= {:base-system-prompt ""
              :system-prompt ""
              :developer-prompt "dev layer"
              :developer-prompt-source :explicit
              :prompt-contributions []}
             (:psi.agent-session/prompt-layers result)))))

  (testing "query-in resolves context fraction"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/update-context-usage {:tokens 4000 :window 10000} {:origin :core})
      (let [result (session/query-in ctx [:psi.agent-session/context-fraction])]
        (is (= 0.4 (:psi.agent-session/context-fraction result))))))

  (testing "query-in resolves extension-summary"
    (let [ctx (session/create-context)]
      (ext/register-extension-in! (:extension-registry ctx) "/ext/a")
      (let [result (session/query-in ctx [:psi.agent-session/extension-summary])]
        (is (= 1 (get-in result [:psi.agent-session/extension-summary :extension-count]))))))

  (testing "query-in resolves prompt contribution attrs"
    (let [ctx (session/create-context)]
      (dispatch/dispatch! ctx :session/register-prompt-contribution
                          {:ext-path "/ext/a" :id "c1"
                           :contribution {:content "Hint" :priority 10 :enabled true}}
                          {:origin :core})
      (let [result (session/query-in ctx [:psi.agent-session/base-system-prompt
                                          :psi.agent-session/prompt-contributions
                                          :psi.extension/prompt-contribution-count])]
        (is (= "" (:psi.agent-session/base-system-prompt result)))
        (is (= 1 (count (:psi.agent-session/prompt-contributions result))))
        (is (= 1 (:psi.extension/prompt-contribution-count result))))))

  (testing "query-in resolves stats"
    (let [ctx    (session/create-context)
          result (session/query-in ctx [:psi.agent-session/stats])]
      (is (map? (:psi.agent-session/stats result)))
      (is (contains? (:psi.agent-session/stats result) :session-id))))

  (testing "query-in resolves canonical telemetry attrs directly"
    (let [ctx (session/create-context)]
      (doseq [attr [:psi.agent-session/messages-count
                    :psi.agent-session/ai-call-count
                    :psi.agent-session/tool-call-count
                    :psi.agent-session/executed-tool-count
                    :psi.agent-session/start-time
                    :psi.agent-session/current-time]]
        (let [result (session/query-in ctx [attr])]
          (is (contains? result attr))
          (is (not (contains? result :com.wsscode.pathom3.connect.runner/attribute-errors))))))

    (let [ctx    (session/create-context)
          result (session/query-in ctx [:psi.agent-session/messages-count
                                        :psi.agent-session/ai-call-count
                                        :psi.agent-session/tool-call-count
                                        :psi.agent-session/executed-tool-count
                                        :psi.agent-session/start-time
                                        :psi.agent-session/current-time])]
      (is (int? (:psi.agent-session/messages-count result)))
      (is (int? (:psi.agent-session/ai-call-count result)))
      (is (int? (:psi.agent-session/tool-call-count result)))
      (is (int? (:psi.agent-session/executed-tool-count result)))
      (is (instance? java.time.Instant (:psi.agent-session/start-time result)))
      (is (instance? java.time.Instant (:psi.agent-session/current-time result)))
      (is (<= 0 (:psi.agent-session/messages-count result)))
      (is (<= 0 (:psi.agent-session/ai-call-count result)))
      (is (<= 0 (:psi.agent-session/tool-call-count result)))
      (is (<= 0 (:psi.agent-session/executed-tool-count result)))))

  (testing "query-in resolves usage aggregates from journal assistant usage"
    (let [ctx (session/create-context)
          assistant-1 {:role "assistant"
                       :content [{:type :text :text "a"}]
                       :usage {:input-tokens 1000
                               :output-tokens 200
                               :cache-read-tokens 50
                               :cache-write-tokens 10
                               :cost {:total 0.123}}
                       :timestamp (java.time.Instant/now)}
          assistant-2 {:role "assistant"
                       :content [{:type :text :text "b"}]
                       :usage {:input 500
                               :output 100
                               :cache-read 0
                               :cache-write 0
                               :cost {:total 0.050}}
                       :timestamp (java.time.Instant/now)}]
      (test-support/update-state! ctx :journal
                                  into
                                  [(persist/message-entry assistant-1)
                                   (persist/message-entry assistant-2)])
      (let [result (session/query-in ctx [:psi.agent-session/usage-input
                                          :psi.agent-session/usage-output
                                          :psi.agent-session/usage-cache-read
                                          :psi.agent-session/usage-cache-write
                                          :psi.agent-session/usage-cost-total])]
        (is (= 1500 (:psi.agent-session/usage-input result)))
        (is (= 300 (:psi.agent-session/usage-output result)))
        (is (= 50 (:psi.agent-session/usage-cache-read result)))
        (is (= 10 (:psi.agent-session/usage-cache-write result)))
        (is (< (Math/abs (- 0.173 (double (:psi.agent-session/usage-cost-total result))))
               1.0e-9)))))

  (testing "query-in resolves model provider/id/reasoning attrs"
    (let [ctx (session/create-context)
          model {:provider "openai" :id "gpt-5.3-codex" :reasoning true}]
      (dispatch/dispatch! ctx :session/set-model {:model model} {:origin :core})
      (let [result (session/query-in ctx [:psi.agent-session/model-provider
                                          :psi.agent-session/model-id
                                          :psi.agent-session/model-reasoning
                                          :psi.agent-session/effective-reasoning-effort
                                          :psi.agent-session/cwd
                                          :psi.agent-session/git-branch])]
        (is (= "openai" (:psi.agent-session/model-provider result)))
        (is (= "gpt-5.3-codex" (:psi.agent-session/model-id result)))
        (is (true? (:psi.agent-session/model-reasoning result)))
        (is (nil? (:psi.agent-session/effective-reasoning-effort result)))
        (is (string? (:psi.agent-session/cwd result)))
        (is (contains? result :psi.agent-session/git-branch)))))

  (testing "query-in resolves tool summary"
    (let [ctx  (session/create-context)
          tool {:name "foo" :label "Foo" :description "Bar" :parameters "{}"}]
      (agent-core/set-tools-in! (ss/agent-ctx-in ctx) [tool])
      (let [summary (session/query-in ctx [:psi.tool/count :psi.tool/names :psi.tool/summary])]
        (is (= 1 (:psi.tool/count summary)))
        (is (= ["foo"] (:psi.tool/names summary)))
        (is (= "foo" (get-in summary [:psi.tool/summary :tools 0 :name]))))))

  (testing "effective-cwd-in prefers session worktree-path over context cwd"
    (let [ctx (session/create-context {:cwd "/repo/main"
                                       :initial-session {:worktree-path "/repo/feature-x"}})]
      (is (= "/repo/feature-x" (ss/effective-cwd-in ctx)))
      (dispatch/dispatch! ctx :session/set-worktree-path {:worktree-path "/repo/feature-y"} {:origin :core})
      (is (= "/repo/feature-y" (ss/effective-cwd-in ctx)))))

  (testing "query-in resolves effective reasoning effort for reasoning models"
    (let [ctx (session/create-context {:initial-session {:model {:provider "openai"
                                                                 :id "gpt-5.3-codex"
                                                                 :reasoning true}
                                                         :thinking-level :high}})
          result (session/query-in ctx [:psi.agent-session/model-reasoning
                                        :psi.agent-session/effective-reasoning-effort])]
      (is (true? (:psi.agent-session/model-reasoning result)))
      (is (= "high" (:psi.agent-session/effective-reasoning-effort result))))))

(deftest tool-output-eql-introspection-test
  (testing "query-in resolves tool-output policy defaults and overrides"
    (let [ctx (session/create-context {:initial-session
                                       {:tool-output-overrides
                                        {"bash" {:max-lines 77 :max-bytes 2048}}}})
          result (session/query-in ctx [:psi.tool-output/default-max-lines
                                        :psi.tool-output/default-max-bytes
                                        :psi.tool-output/overrides])]
      (is (= 1000 (:psi.tool-output/default-max-lines result)))
      (is (= 25600 (:psi.tool-output/default-max-bytes result)))
      (is (= {"bash" {:max-lines 77 :max-bytes 2048}}
             (:psi.tool-output/overrides result)))))

  (testing "query-in resolves tool-output calls and aggregate stats"
    (let [ctx (session/create-context)]
      (test-support/set-state! ctx :tool-output-stats
                               {:calls [{:tool-call-id "call-1"
                                         :tool-name "bash"
                                         :timestamp (java.time.Instant/now)
                                         :limit-hit true
                                         :truncated-by :bytes
                                         :effective-max-lines 1000
                                         :effective-max-bytes 25600
                                         :output-bytes 120
                                         :context-bytes-added 64}]
                                :aggregates {:total-context-bytes 64
                                             :by-tool {"bash" 64}
                                             :limit-hits-by-tool {"bash" 1}}})
      (let [result (session/query-in ctx [{:psi.tool-output/calls
                                           [:psi.tool-output.call/tool-call-id
                                            :psi.tool-output.call/tool-name
                                            :psi.tool-output.call/limit-hit?
                                            :psi.tool-output.call/truncated-by
                                            :psi.tool-output.call/effective-max-lines
                                            :psi.tool-output.call/effective-max-bytes
                                            :psi.tool-output.call/output-bytes
                                            :psi.tool-output.call/context-bytes-added]}
                                          :psi.tool-output/stats])
            calls  (:psi.tool-output/calls result)
            first-call (first calls)
            stats  (:psi.tool-output/stats result)]
        (is (= 1 (count calls)))
        (is (= "call-1" (:psi.tool-output.call/tool-call-id first-call)))
        (is (= "bash" (:psi.tool-output.call/tool-name first-call)))
        (is (true? (:psi.tool-output.call/limit-hit? first-call)))
        (is (= :bytes (:psi.tool-output.call/truncated-by first-call)))
        (is (= 1000 (:psi.tool-output.call/effective-max-lines first-call)))
        (is (= 25600 (:psi.tool-output.call/effective-max-bytes first-call)))
        (is (= 120 (:psi.tool-output.call/output-bytes first-call)))
        (is (= 64 (:psi.tool-output.call/context-bytes-added first-call)))
        (is (= {:total-context-bytes 64
                :by-tool {"bash" 64}
                :limit-hits-by-tool {"bash" 1}}
               stats))))))

;; ── API error diagnostics (hierarchical EQL) ────────────────────────────────

(defn- inject-messages!
  "Append a sequence of message maps into agent-core for testing."
  [ctx msgs]
  (let [agent-ctx (ss/agent-ctx-in ctx)]
    (doseq [m msgs]
      (agent-core/append-message-in! agent-ctx m))))

(defn- make-user-msg [text]
  {:role "user" :content [{:type :text :text text}]
   :timestamp (java.time.Instant/now)})

(defn- make-assistant-msg [text]
  {:role "assistant" :content [{:type :text :text text}]
   :stop-reason :stop :timestamp (java.time.Instant/now)})

(defn- make-tool-call-msg [text tool-id tool-name]
  {:role "assistant"
   :content [{:type :text :text text}
             {:type :tool-call :id tool-id :name tool-name :arguments "{}"}]
   :stop-reason :tool_use :timestamp (java.time.Instant/now)})

(defn- make-tool-result-msg [tool-id tool-name result]
  {:role "toolResult" :tool-call-id tool-id :tool-name tool-name
   :content [{:type :text :text result}] :is-error false
   :timestamp (java.time.Instant/now)})

(defn- make-error-msg [error-text http-status]
  {:role "assistant"
   :content [{:type :error :text error-text}]
   :stop-reason :error :http-status http-status
   :timestamp (java.time.Instant/now)})

(deftest tool-call-attempts-eql-introspection-test
  (testing "query-in resolves tool-call attempts and unmatched counts"
    (let [ctx (session/create-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 10)
          t2  (.plusMillis t0 20)
          t3  (.plusMillis t0 30)
          t4  (.plusMillis t0 40)]
      (test-support/update-state! ctx :tool-call-attempts into
                                  [{:turn-id "turn-1"
                                    :timestamp t0
                                    :event-kind :toolcall-start
                                    :content-index 0
                                    :id "call-1"
                                    :name "bash"}
                                   {:turn-id "turn-1"
                                    :timestamp t1
                                    :event-kind :toolcall-delta
                                    :content-index 0
                                    :delta "{\"command\":\"ls\"}"}
                                   {:turn-id "turn-1"
                                    :timestamp t2
                                    :event-kind :toolcall-end
                                    :content-index 0}
                                   {:turn-id "turn-2"
                                    :timestamp t3
                                    :event-kind :toolcall-start
                                    :content-index 0
                                    :id "call-2"
                                    :name "read"}
                                   {:turn-id "turn-2"
                                    :timestamp t4
                                    :event-kind :toolcall-end
                                    :content-index 0}])

      ;; Only call-1 has a committed toolResult; call-2 is unmatched.
      (inject-messages! ctx [(make-tool-result-msg "call-1" "bash" "ok")])

      (let [r        (session/query-in ctx
                                       [:psi.agent-session/tool-call-attempt-count
                                        :psi.agent-session/tool-call-attempt-unmatched-count
                                        {:psi.agent-session/tool-call-attempts
                                         [:psi.tool-call-attempt/id
                                          :psi.tool-call-attempt/name
                                          :psi.tool-call-attempt/status
                                          :psi.tool-call-attempt/delta-count
                                          :psi.tool-call-attempt/argument-bytes
                                          :psi.tool-call-attempt/result-recorded?]}])
            attempts (:psi.agent-session/tool-call-attempts r)
            by-id    (into {} (map (juxt :psi.tool-call-attempt/id identity)) attempts)
            call-1   (get by-id "call-1")
            call-2   (get by-id "call-2")]
        (is (= 2 (:psi.agent-session/tool-call-attempt-count r)))
        (is (= 1 (:psi.agent-session/tool-call-attempt-unmatched-count r)))

        (is (= :result-recorded (:psi.tool-call-attempt/status call-1)))
        (is (true? (:psi.tool-call-attempt/result-recorded? call-1)))
        (is (= 1 (:psi.tool-call-attempt/delta-count call-1)))
        (is (pos? (:psi.tool-call-attempt/argument-bytes call-1)))

        (is (= :ended (:psi.tool-call-attempt/status call-2)))
        (is (false? (:psi.tool-call-attempt/result-recorded? call-2)))))))

(deftest tool-call-count-vs-lifecycle-summary-count-test
  (testing "tool-call-count is transcript-based while lifecycle-summary-count is canonical lifecycle-based"
    (let [ctx (session/create-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 10)
          t2  (.plusMillis t0 20)]
      (ss/journal-append-in! ctx
                             {:kind :message
                              :data {:message {:role "toolResult"
                                               :tool-call-id "call-journal-1"
                                               :tool-name "read"
                                               :content [{:type :text :text "ok"}]
                                               :is-error false}}})
      (test-support/update-state! ctx :tool-lifecycle-events into
                                  [{:event-kind :tool-start
                                    :tool-id "call-life-1"
                                    :tool-name "bash"
                                    :timestamp t0}
                                   {:event-kind :tool-executing
                                    :tool-id "call-life-1"
                                    :tool-name "bash"
                                    :arguments "{}"
                                    :parsed-args {}
                                    :timestamp t1}
                                   {:event-kind :tool-result
                                    :tool-id "call-life-1"
                                    :tool-name "bash"
                                    :result-text "done"
                                    :is-error false
                                    :timestamp t2}
                                   {:event-kind :tool-start
                                    :tool-id "call-life-2"
                                    :tool-name "read"
                                    :timestamp t1}])
      (let [r (session/query-in ctx
                                [:psi.agent-session/tool-call-count
                                 :psi.agent-session/executed-tool-count
                                 :psi.agent-session/tool-lifecycle-summary-count])]
        (is (= 1 (:psi.agent-session/tool-call-count r)))
        (is (= 2 (:psi.agent-session/executed-tool-count r)))
        (is (= 2 (:psi.agent-session/tool-lifecycle-summary-count r)))))))

(deftest tool-lifecycle-events-eql-introspection-test
  (testing "query-in resolves canonical tool lifecycle events as a distinct surface"
    (let [ctx (session/create-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 10)
          t2  (.plusMillis t0 20)]
      (test-support/update-state! ctx :tool-lifecycle-events into
                                  [{:event-kind :tool-start
                                    :tool-id "call-1"
                                    :tool-name "read"
                                    :timestamp t0}
                                   {:event-kind :tool-executing
                                    :tool-id "call-1"
                                    :tool-name "read"
                                    :arguments "{}"
                                    :parsed-args {}
                                    :timestamp t1}
                                   {:event-kind :tool-result
                                    :tool-id "call-1"
                                    :tool-name "read"
                                    :result-text "ok"
                                    :is-error false
                                    :timestamp t2}])
      (let [r (session/query-in ctx
                                [:psi.agent-session/tool-lifecycle-event-count
                                 :psi.agent-session/tool-lifecycle-summary-count
                                 {:psi.agent-session/tool-lifecycle-events
                                  [:psi.tool-lifecycle/event-kind
                                   :psi.tool-lifecycle/tool-id
                                   :psi.tool-lifecycle/tool-name
                                   :psi.tool-lifecycle/result-text
                                   :psi.tool-lifecycle/is-error
                                   :psi.tool-lifecycle/arguments
                                   :psi.tool-lifecycle/parsed-args]}
                                 {:psi.agent-session/tool-lifecycle-summaries
                                  [:psi.tool-lifecycle.summary/tool-id
                                   :psi.tool-lifecycle.summary/tool-name
                                   :psi.tool-lifecycle.summary/event-count
                                   :psi.tool-lifecycle.summary/last-event-kind
                                   :psi.tool-lifecycle.summary/completed?
                                   :psi.tool-lifecycle.summary/is-error
                                   :psi.tool-lifecycle.summary/result-text
                                   :psi.tool-lifecycle.summary/arguments
                                   :psi.tool-lifecycle.summary/parsed-args]}])
            events    (:psi.agent-session/tool-lifecycle-events r)
            summaries (:psi.agent-session/tool-lifecycle-summaries r)
            summary   (first summaries)]
        (is (= 3 (:psi.agent-session/tool-lifecycle-event-count r)))
        (is (= 1 (:psi.agent-session/tool-lifecycle-summary-count r)))
        (is (= [:tool-start :tool-executing :tool-result]
               (mapv :psi.tool-lifecycle/event-kind events)))
        (is (= "call-1" (:psi.tool-lifecycle/tool-id (first events))))
        (is (= "read" (:psi.tool-lifecycle/tool-name (first events))))
        (is (= "{}" (:psi.tool-lifecycle/arguments (second events))))
        (is (= {} (:psi.tool-lifecycle/parsed-args (second events))))
        (is (= "ok" (:psi.tool-lifecycle/result-text (last events))))
        (is (false? (:psi.tool-lifecycle/is-error (last events))))
        (is (= "call-1" (:psi.tool-lifecycle.summary/tool-id summary)))
        (is (= "read" (:psi.tool-lifecycle.summary/tool-name summary)))
        (is (= 3 (:psi.tool-lifecycle.summary/event-count summary)))
        (is (= :tool-result (:psi.tool-lifecycle.summary/last-event-kind summary)))
        (is (true? (:psi.tool-lifecycle.summary/completed? summary)))
        (is (false? (:psi.tool-lifecycle.summary/is-error summary)))
        (is (= "ok" (:psi.tool-lifecycle.summary/result-text summary)))
        (is (= "{}" (:psi.tool-lifecycle.summary/arguments summary)))
        (is (= {} (:psi.tool-lifecycle.summary/parsed-args summary)))

        (let [qctx (query/create-query-context)
              _    (session/register-resolvers-in! qctx false)
              lookup (query/query-in qctx {:psi/agent-session-ctx ctx
                                           :psi.agent-session/lookup-tool-id "call-1"}
                                     [{:psi.agent-session/tool-lifecycle-summary-for-tool-id
                                       [:psi.tool-lifecycle.summary/tool-id
                                        :psi.tool-lifecycle.summary/tool-name
                                        :psi.tool-lifecycle.summary/event-count
                                        :psi.tool-lifecycle.summary/last-event-kind
                                        :psi.tool-lifecycle.summary/completed?
                                        :psi.tool-lifecycle.summary/result-text
                                        :psi.tool-lifecycle.summary/arguments
                                        :psi.tool-lifecycle.summary/parsed-args]}])
              looked-up (:psi.agent-session/tool-lifecycle-summary-for-tool-id lookup)]
          (is (= "call-1" (:psi.tool-lifecycle.summary/tool-id looked-up)))
          (is (= "read" (:psi.tool-lifecycle.summary/tool-name looked-up)))
          (is (= 3 (:psi.tool-lifecycle.summary/event-count looked-up)))
          (is (= :tool-result (:psi.tool-lifecycle.summary/last-event-kind looked-up)))
          (is (true? (:psi.tool-lifecycle.summary/completed? looked-up)))
          (is (= "ok" (:psi.tool-lifecycle.summary/result-text looked-up)))
          (is (= "{}" (:psi.tool-lifecycle.summary/arguments looked-up)))
          (is (= {} (:psi.tool-lifecycle.summary/parsed-args looked-up))))))))

(deftest provider-capture-eql-introspection-test
  (testing "query-in resolves provider request/reply captures"
    (let [ctx (session/create-context)
          t0  (java.time.Instant/now)
          t1  (.plusMillis t0 25)
          t2  (.plusMillis t0 50)]
      (test-support/update-state! ctx :provider-requests
                                  conj
                                  {:provider :openai
                                   :api :openai-codex-responses
                                   :url "https://chatgpt.com/backend-api/codex/responses"
                                   :turn-id "turn-123"
                                   :timestamp t0
                                   :request {:headers {"Authorization" "Bearer ***REDACTED*** (len=99)"}
                                             :body {:model "gpt-5.3-codex" :tool_choice "auto"}}})
      (test-support/update-state! ctx :provider-replies
                                  into
                                  [{:provider :openai
                                    :api :openai-codex-responses
                                    :url "https://chatgpt.com/backend-api/codex/responses"
                                    :turn-id "turn-123"
                                    :timestamp t1
                                    :event {:type "response.completed"
                                            :response {:status "completed"}}}
                                   {:provider :anthropic
                                    :api :anthropic-messages
                                    :url "https://api.anthropic.com/v1/messages"
                                    :turn-id "turn-ant-1"
                                    :timestamp t2
                                    :event {:type :error
                                            :error-message "Error (status 400) [request-id req_ant_1]"
                                            :http-status 400}}])
      (test-support/update-state! ctx :provider-error-replies
                                  conj
                                  {:provider :anthropic
                                   :api :anthropic-messages
                                   :url "https://api.anthropic.com/v1/messages"
                                   :turn-id "turn-ant-1"
                                   :timestamp t2
                                   :event {:type :error
                                           :error-message "Error (status 400) [request-id req_ant_1]"
                                           :http-status 400}})

      (let [r (session/query-in ctx
                                [:psi.agent-session/provider-request-count
                                 :psi.agent-session/provider-reply-count
                                 {:psi.agent-session/provider-last-request
                                  [:psi.provider-request/provider
                                   :psi.provider-request/api
                                   :psi.provider-request/turn-id
                                   :psi.provider-request/body]}
                                 {:psi.agent-session/provider-last-reply
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}
                                 {:psi.agent-session/provider-last-error-reply
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}
                                 {:psi.agent-session/provider-error-replies
                                  [:psi.provider-reply/provider
                                   :psi.provider-reply/api
                                   :psi.provider-reply/turn-id
                                   :psi.provider-reply/event]}])]
        (is (= 1 (:psi.agent-session/provider-request-count r)))
        (is (= 2 (:psi.agent-session/provider-reply-count r)))

        (is (= :openai
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/provider])))
        (is (= :openai-codex-responses
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/api])))
        (is (= "turn-123"
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/turn-id])))
        (is (= "gpt-5.3-codex"
               (get-in r [:psi.agent-session/provider-last-request
                          :psi.provider-request/body
                          :model])))

        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/provider])))
        (is (= :anthropic-messages
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/api])))
        (is (= "turn-ant-1"
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/turn-id])))
        (is (= :error
               (get-in r [:psi.agent-session/provider-last-reply
                          :psi.provider-reply/event
                          :type])))

        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/provider])))
        (is (= :anthropic-messages
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/api])))
        (is (= "turn-ant-1"
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/turn-id])))
        (is (= :error
               (get-in r [:psi.agent-session/provider-last-error-reply
                          :psi.provider-reply/event
                          :type])))

        (is (= 1 (count (:psi.agent-session/provider-error-replies r))))
        (is (= :anthropic
               (get-in r [:psi.agent-session/provider-error-replies 0
                          :psi.provider-reply/provider])))))

    (testing "provider capture lookup by turn-id resolves exact request and reply"
      (let [ctx (session/create-context)
            t0  (java.time.Instant/now)
            t1  (.plusMillis t0 25)]
        (test-support/update-state! ctx :provider-requests
                                    conj
                                    {:provider :anthropic
                                     :api :anthropic-messages
                                     :url "https://api.anthropic.com/v1/messages"
                                     :turn-id "turn-ant-lookup"
                                     :timestamp t0
                                     :request {:headers {"x-test" "1"}
                                               :body {:model "claude-sonnet-4-6"
                                                      :messages [{:role "user" :content [{:type "text" :text "hi"}]}]}}})
        (test-support/update-state! ctx :provider-replies
                                    conj
                                    {:provider :anthropic
                                     :api :anthropic-messages
                                     :url "https://api.anthropic.com/v1/messages"
                                     :turn-id "turn-ant-lookup"
                                     :timestamp t1
                                     :event {:type :error
                                             :error-message "Error (status 400) [request-id req_lookup]"
                                             :http-status 400}})

        (let [req ((resolve 'psi.agent-session.resolvers/provider-request-by-turn-id)
                   {:psi.agent-session/lookup-turn-id "turn-ant-lookup"
                    :psi/agent-session-ctx ctx})
              reply ((resolve 'psi.agent-session.resolvers/provider-reply-by-turn-id)
                     {:psi.agent-session/lookup-turn-id "turn-ant-lookup"
                      :psi/agent-session-ctx ctx})]
          (is (= :anthropic
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/provider])))
          (is (= :anthropic-messages
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/api])))
          (is (= "claude-sonnet-4-6"
                 (get-in req [:psi.agent-session/provider-request-for-turn-id
                              :psi.provider-request/body
                              :model])))
          (is (= :error
                 (get-in reply [:psi.agent-session/provider-reply-for-turn-id
                                :psi.provider-reply/event
                                :type])))
          (is (= 400
                 (get-in reply [:psi.agent-session/provider-reply-for-turn-id
                                :psi.provider-reply/event
                                :http-status]))))))))

(deftest api-error-list-test
  (testing "no errors → count 0, empty list"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "hi")
                             (make-assistant-msg "hello")])
      (let [r (session/query-in ctx [:psi.agent-session/api-error-count])]
        (is (zero? (:psi.agent-session/api-error-count r))))))

  (testing "provider reply error is exposed via api-errors"
    (let [ctx (session/create-context)
          t0  (java.time.Instant/now)]
      (test-support/update-state! ctx :provider-replies
                                  conj
                                  {:provider :anthropic
                                   :api :anthropic-messages
                                   :url "https://api.anthropic.com/v1/messages"
                                   :turn-id "turn-ant-1"
                                   :timestamp t0
                                   :event {:type :error
                                           :error-message "Error (status 400) [request-id req_ant_123]"
                                           :http-status 400
                                           :headers {"request-id" "req_ant_123"}
                                           :body-text "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Error\"},\"request_id\":\"req_ant_123\"}"
                                           :body {:type "error"
                                                  :error {:type "invalid_request_error"
                                                          :message "Error"}
                                                  :request_id "req_ant_123"}}})
      (let [r (session/query-in ctx
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/http-status
                                   :psi.api-error/request-id
                                   :psi.api-error/provider
                                   :psi.api-error/api
                                   :psi.api-error/url
                                   :psi.api-error/turn-id
                                   :psi.api-error/error-message-full
                                   :psi.api-error/provider-event]}])
            err (first (:psi.agent-session/api-errors r))]
        (is (= 1 (count (:psi.agent-session/api-errors r))))
        (is (= 400 (:psi.api-error/http-status err)))
        (is (= "req_ant_123" (:psi.api-error/request-id err)))
        (is (= :anthropic (:psi.api-error/provider err)))
        (is (= :anthropic-messages (:psi.api-error/api err)))
        (is (= "https://api.anthropic.com/v1/messages" (:psi.api-error/url err)))
        (is (= "turn-ant-1" (:psi.api-error/turn-id err)))
        (is (= "Error (status 400) [request-id req_ant_123]"
               (:psi.api-error/error-message-full err)))
        (is (= :error (get-in err [:psi.api-error/provider-event :type])))
        (is (= 400 (get-in err [:psi.api-error/provider-event :http-status])))
        (is (string? (get-in err [:psi.api-error/provider-event :body-text]))))))

  (testing "single 400 error → count 1 with correct fields"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "hi")
                             (make-error-msg "clj-http: status 400 {}" 400)])
      (let [r (session/query-in ctx
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/message-index
                                   :psi.api-error/http-status
                                   :psi.api-error/error-message-brief]}])
            errors (:psi.agent-session/api-errors r)]
        (is (= 1 (count errors)))
        (is (= 1 (:psi.api-error/message-index (first errors))))
        (is (= 400 (:psi.api-error/http-status (first errors))))
        (is (string? (:psi.api-error/error-message-brief (first errors)))))))

  (testing "assistant error is enriched from matching provider reply by request-id"
    (let [ctx (session/create-context)
          t0  (java.time.Instant/now)]
      (inject-messages! ctx [(make-user-msg "hi")
                             (make-error-msg
                              "Error (status 400) [request-id req_ant_live]"
                              400)])
      (test-support/update-state! ctx :provider-replies
                                  conj
                                  {:provider :anthropic
                                   :api :anthropic-messages
                                   :url "https://api.anthropic.com/v1/messages"
                                   :turn-id "turn-ant-live"
                                   :timestamp t0
                                   :event {:type :error
                                           :error-message "Error (status 400) [request-id req_ant_live]"
                                           :http-status 400
                                           :headers {"request-id" "req_ant_live"}
                                           :body-text "{\"type\":\"error\",\"request_id\":\"req_ant_live\"}"
                                           :body {:type "error" :request_id "req_ant_live"}}})
      (let [r (session/query-in ctx
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/message-index
                                   :psi.api-error/request-id
                                   :psi.api-error/provider
                                   :psi.api-error/api
                                   :psi.api-error/url
                                   :psi.api-error/turn-id
                                   :psi.api-error/provider-event]}])
            errors (:psi.agent-session/api-errors r)
            err    (first errors)]
        (is (= 1 (count errors)))
        (is (= 1 (:psi.api-error/message-index err)))
        (is (= "req_ant_live" (:psi.api-error/request-id err)))
        (is (= :anthropic (:psi.api-error/provider err)))
        (is (= :anthropic-messages (:psi.api-error/api err)))
        (is (= "https://api.anthropic.com/v1/messages" (:psi.api-error/url err)))
        (is (= "turn-ant-live" (:psi.api-error/turn-id err)))
        (is (= :error (get-in err [:psi.api-error/provider-event :type]))))))

  (testing "multiple errors → all captured"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "hi")
                             (make-error-msg "error 1" 429)
                             (make-user-msg "retry")
                             (make-error-msg "error 2" 500)])
      (let [r (session/query-in ctx [:psi.agent-session/api-error-count])]
        (is (= 2 (:psi.agent-session/api-error-count r)))))))

(deftest api-error-detail-test
  (testing "request-id parsed from error text"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "hi")
                             (make-error-msg
                              "clj-http: status 400 {\"request-id\" \"req_abc123\"}"
                              400)])
      (let [r (session/query-in ctx
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/request-id]}])
            err (first (:psi.agent-session/api-errors r))]
        (is (= "req_abc123" (:psi.api-error/request-id err))))))

  (testing "request-id parsed from normalized provider error suffix"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "hi")
                             (make-error-msg
                              "Error (status 400) [request-id req_011CZ8hy9y3kRrVsNfhmugS1]"
                              400)])
      (let [r (session/query-in ctx
                                [{:psi.agent-session/api-errors
                                  [:psi.api-error/request-id]}])
            err (first (:psi.agent-session/api-errors r))]
        (is (= "req_011CZ8hy9y3kRrVsNfhmugS1" (:psi.api-error/request-id err))))))

  (testing "surrounding messages include context window"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "step 1")
                             (make-assistant-msg "response 1")
                             (make-user-msg "step 2")
                             (make-error-msg "boom" 400)])
      (let [r (session/query-in ctx
                                [{:psi.agent-session/api-errors
                                  [{:psi.api-error/surrounding-messages
                                    [:psi.context-message/index
                                     :psi.context-message/role]}]}])
            surr (-> r :psi.agent-session/api-errors first
                     :psi.api-error/surrounding-messages)]
        (is (pos? (count surr)))
        (is (= "user" (:psi.context-message/role (first surr))))))))

(deftest api-error-request-shape-test
  (testing "request shape computed at point of error"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "go")
                             (make-tool-call-msg "running" "tc1" "bash")
                             (make-tool-result-msg "tc1" "bash" "done")
                             (make-error-msg "status 400" 400)])
      (let [r (session/query-in ctx
                                [{:psi.agent-session/api-errors
                                  [{:psi.api-error/request-shape
                                    [:psi.request-shape/message-count
                                     :psi.request-shape/tool-use-count
                                     :psi.request-shape/tool-result-count
                                     :psi.request-shape/missing-tool-results
                                     :psi.request-shape/alternation-valid?
                                     :psi.request-shape/headroom-tokens]}]}])
            shape (-> r :psi.agent-session/api-errors first
                      :psi.api-error/request-shape)]
        ;; 3 messages before the error (user, assistant, toolResult)
        (is (= 3 (:psi.request-shape/message-count shape)))
        (is (= 1 (:psi.request-shape/tool-use-count shape)))
        (is (= 1 (:psi.request-shape/tool-result-count shape)))
        (is (zero? (:psi.request-shape/missing-tool-results shape)))
        (is (true? (:psi.request-shape/alternation-valid? shape)))
        (is (pos? (:psi.request-shape/headroom-tokens shape)))))))

(deftest current-request-shape-test
  (testing "current shape reflects all messages"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "hello")
                             (make-assistant-msg "world")])
      (let [r (session/query-in ctx
                                [{:psi.agent-session/request-shape
                                  [:psi.request-shape/message-count
                                   :psi.request-shape/estimated-tokens
                                   :psi.request-shape/context-window
                                   :psi.request-shape/alternation-valid?]}])
            shape (:psi.agent-session/request-shape r)]
        (is (= 2 (:psi.request-shape/message-count shape)))
        (is (pos? (:psi.request-shape/estimated-tokens shape)))
        (is (= 200000 (:psi.request-shape/context-window shape)))
        (is (true? (:psi.request-shape/alternation-valid? shape))))))

  (testing "headroom decreases as context grows"
    (let [ctx (session/create-context)]
      (inject-messages! ctx [(make-user-msg "a")])
      (let [r1 (session/query-in ctx
                                 [{:psi.agent-session/request-shape
                                   [:psi.request-shape/headroom-tokens]}])
            h1 (-> r1 :psi.agent-session/request-shape :psi.request-shape/headroom-tokens)]
        ;; Add more messages
        (inject-messages! ctx [(make-assistant-msg (apply str (repeat 1000 "x")))
                               (make-user-msg "b")])
        (let [r2 (session/query-in ctx
                                   [{:psi.agent-session/request-shape
                                     [:psi.request-shape/headroom-tokens]}])
              h2 (-> r2 :psi.agent-session/request-shape :psi.request-shape/headroom-tokens)]
          (is (< h2 h1)))))))

;; ── Global query graph registration ─────────────────────────────────────────

(deftest register-resolvers-in!-test
  (testing "register-resolvers-in! wires agent-session into an isolated query ctx"
    (let [qctx (query/create-query-context)]
      (session/register-resolvers-in! qctx)
      ;; Agent-session resolvers should now be in the isolated graph
      (let [syms (query/resolver-syms-in qctx)]
        (is (contains? syms 'psi.agent-session.resolvers/agent-session-identity)
            "identity resolver should be registered")
        (is (contains? syms 'psi.agent-session.resolvers/agent-session-phase)
            "phase resolver should be registered"))))

  (testing "register-resolvers-in! enables EQL queries via the isolated ctx"
    (let [session-ctx (session/create-context)
          qctx        (query/create-query-context)]
      (session/register-resolvers-in! qctx)
      (let [result (query/query-in qctx
                                   {:psi/agent-session-ctx session-ctx}
                                   [:psi.agent-session/session-id
                                    :psi.agent-session/phase])]
        (is (string? (:psi.agent-session/session-id result)))
        (is (= :idle (:psi.agent-session/phase result)))))))

(deftest register-mutations-in!-includes-history-mutations-test
  (testing "register-mutations-in! wires history git mutations into isolated query ctx"
    (let [git-ctx (history-git/create-null-context)
          ctx     (session/create-context {:cwd (:repo-dir git-ctx) :persist? false})
          qctx    (query/create-query-context)]
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
          ctx           (session/create-context {:cwd repo-dir :persist? false})
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
  (let [ctx    (session/create-context {:persist? false})
        qctx   (query/create-query-context)
        mutate (fn [op params]
                 (get (query/query-in qctx
                                      {:psi/agent-session-ctx ctx}
                                      [(list op (assoc params :psi/agent-session-ctx ctx))])
                      op))]
    (session/register-resolvers-in! qctx false)
    (session/register-mutations-in! qctx mutations/all-mutations true)

    (is (contains? (query/mutation-syms-in qctx) 'psi.extension/set-rpc-trace))
    (is (= {:psi.agent-session/rpc-trace-enabled false
            :psi.agent-session/rpc-trace-file nil}
           (session/query-in ctx [:psi.agent-session/rpc-trace-enabled
                                  :psi.agent-session/rpc-trace-file])))

    (let [r1 (mutate 'psi.extension/set-rpc-trace
                     {:enabled true
                      :file "/tmp/psi-rpc-trace-from-mutation.ndedn"})]
      (is (= true (:psi.agent-session/rpc-trace-enabled r1)))
      (is (= "/tmp/psi-rpc-trace-from-mutation.ndedn"
             (:psi.agent-session/rpc-trace-file r1)))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-rpc-trace (:event-type entry)))
        (is (= :mutations (:origin entry)))
        (is (= {:enabled? true
                :file "/tmp/psi-rpc-trace-from-mutation.ndedn"}
               (:event-data entry)))))

    (let [r2 (mutate 'psi.extension/set-rpc-trace {:enabled false})]
      (is (= false (:psi.agent-session/rpc-trace-enabled r2)))
      (is (= "/tmp/psi-rpc-trace-from-mutation.ndedn"
             (:psi.agent-session/rpc-trace-file r2)))
      (let [entry (last (dispatch/event-log-entries))]
        (is (= :session/set-rpc-trace (:event-type entry)))
        (is (= :mutations (:origin entry)))
        (is (= {:enabled? false
                :file "/tmp/psi-rpc-trace-from-mutation.ndedn"}
               (:event-data entry)))))))

(deftest session-query-in-exposes-recursion-attrs-test
  (testing "session/query-in can read recursion attrs from live session recursion-ctx"
    (let [rctx (recursion/create-context)
          _    (recursion/register-hooks-in! rctx)
          ctx  (session/create-context {:recursion-ctx rctx})
          result (session/query-in ctx
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
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
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
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
      (ext-rt/set-extension-run-fn-in! ctx (fn [_text _source] nil))
      (Thread/sleep 30)

      (let [assistant-msgs (->> (:messages (agent-core/get-data-in (ss/agent-ctx-in ctx)))
                                (filter #(= "assistant" (:role %)))
                                vec)
            injected (some #(when (= "background-job-terminal" (:custom-type %)) %) assistant-msgs)
            injected-text (get-in injected [:content 0 :text])]
        (is (map? injected) (str "assistant messages: " assistant-msgs))
        (is (string? injected-text))
        (is (re-find #"workflow-id" injected-text))))))

(deftest workflow-mutations-and-resolvers-test
  (testing "workflow mutation ops and resolver attrs are wired"
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
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
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          chart  (chart/statechart {:id :wf-track-gate}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :running}))
                                   (ele/state {:id :running}
                                              (ele/transition {:event :workflow/finish :target :done}))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
                        op))
          thread-id (:session-id (ss/get-session-data-in ctx))]
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          chart  (chart/statechart {:id :wf-vertical-slice}
                                   (ele/state {:id :idle}
                                              (ele/transition {:event :workflow/start :target :done}))
                                   (ele/final {:id :done}))
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
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
    (let [calls (atom [])
          ctx   (assoc (session/create-context {:persist? false})
                       :runtime-tool-executor-fn
                       (fn [_ctx tool-name args opts]
                         (swap! calls conj {:tool-name tool-name :args args :opts opts})
                         {:content "custom" :is-error false}))]
      (is (= {:content "custom" :is-error false}
             (tool-plan/execute-tool-runtime-in! ctx "custom-tool" {"x" 1} {:tool-call-id "c1"})))
      (is (= [{:tool-name "custom-tool"
               :args {"x" 1}
               :opts {:tool-call-id "c1"}}]
             @calls))))

  (testing "default-execute-runtime-tool-in! remains the fallback runtime implementation"
    (let [ctx (session/create-context {:persist? false})]
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx)
       [{:name "prefix-a"
         :execute (fn [args _opts]
                    {:content (str "A:" (get args "text" ""))
                     :is-error false})}])
      (is (= {:content "A:hello" :is-error false}
             (tool-plan/default-execute-runtime-tool-in! ctx "prefix-a" {"text" "hello"} {})))
      (is (= {:content "A:hello" :is-error false}
             (tool-plan/execute-tool-runtime-in! ctx "prefix-a" {"text" "hello"} {}))))))

(deftest tool-plan-runtime-tool-executor-boundary-test
  (testing "run-tool-plan uses the ctx-provided runtime tool executor boundary"
    (let [calls  (atom [])
          ctx    (assoc (session/create-context {:persist? false})
                        :runtime-tool-executor-fn
                        (fn [_ctx tool-name args opts]
                          (swap! calls conj {:tool-name tool-name :args args :opts opts})
                          {:content (str "custom:" (get args "text" ""))
                           :is-error false}))
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx)
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
    (let [ctx      (session/create-context)
          qctx     (query/create-query-context)
          ran-last (atom false)
          mutate   (fn [op params]
                     (get (query/query-in qctx
                                          {:psi/agent-session-ctx ctx}
                                          [(list op (assoc params :psi/agent-session-ctx ctx))])
                          op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx)
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
                        op))]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      (agent-core/set-tools-in!
       (ss/agent-ctx-in ctx)
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
                        op))
          run-calls (atom [])]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      ;; Register a stub run-fn that captures calls
      (ext-rt/set-extension-run-fn-in! ctx (fn [text source]
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
    (let [ctx    (session/create-context)
          qctx   (query/create-query-context)
          mutate (fn [op params]
                   (get (query/query-in qctx
                                        {:psi/agent-session-ctx ctx}
                                        [(list op (assoc params :psi/agent-session-ctx ctx))])
                        op))
          run-calls (atom [])]
      (session/register-resolvers-in! qctx false)
      (session/register-mutations-in! qctx mutations/all-mutations true)
      ;; Force non-idle session phase so send-extension-prompt-in! takes deferred path.
      (session/prompt-in! ctx "seed busy turn")
      (ext-rt/set-extension-run-fn-in! ctx (fn [text source]
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
    (let [ctx      (session/create-context)
          template {:name "greet" :description "d" :content "c" :source :project :file-path "/tmp/greet.md"}
          skill    {:name "coding" :description "d" :file-path "/tmp/SKILL.md"
                    :base-dir "/tmp" :source :project :disable-model-invocation false}
          tool     {:name "foo" :label "Foo" :description "bar" :parameters "{}"}]
      (bootstrap/load-startup-resources-via-mutations-in!
       ctx {:templates [template] :skills [skill] :tools [tool] :extension-paths []})
      (let [sd (ss/get-session-data-in ctx)
            ad (agent-core/get-data-in (ss/agent-ctx-in ctx))]
        (is (= 1 (count (:prompt-templates sd))))
        (is (= 1 (count (:skills sd))))
        (is (= 1 (count (:tools ad))))
        (is (= "foo" (:name (first (:tools ad)))))))))

(deftest bootstrap-session-test
  (testing "bootstrap-in! stores startup summary and applies resources"
    (let [ctx      (session/create-context)
          template {:name "greet" :description "d" :content "c" :source :project :file-path "/tmp/greet.md"}
          skill    {:name "coding" :description "d" :file-path "/tmp/SKILL.md"
                    :base-dir "/tmp" :source :project :disable-model-invocation false}
          tool     {:name "foo" :label "Foo" :description "bar" :parameters "{}"}
          summary  (bootstrap/bootstrap-in!
                    ctx {:register-global-query? false
                         :base-tools             []
                         :system-prompt          "sys"
                         :templates              [template]
                         :skills                 [skill]
                         :tools                  [tool]
                         :extension-paths        []})
          sd       (ss/get-session-data-in ctx)
          ad       (agent-core/get-data-in (ss/agent-ctx-in ctx))]
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
    (let [ctx (session/create-context)]
      (bootstrap/bootstrap-in!
       ctx {:register-global-query? false
            :system-prompt          "sys"
            :developer-prompt       "dev"
            :developer-prompt-source :explicit})
      (is (= "dev" (:developer-prompt (ss/get-session-data-in ctx))))
      (is (= :explicit (:developer-prompt-source (ss/get-session-data-in ctx)))))))

;;; Child session infrastructure tests

(deftest create-child-session-dispatch-test
  ;; :session/create-child inserts a child session entry in state without
  ;; changing the parent's active-session-id.
  (let [ctx         (test-support/make-session-ctx {})
        parent-id   (ss/active-session-id-in ctx)
        child-id    (str (java.util.UUID/randomUUID))
        sys-prompt  "child system"
        tool-schemas [{:name "tool1"}]]
    (testing ":session/create-child dispatch"
      (dispatch/dispatch! ctx
                          :session/create-child
                          {:child-session-id child-id
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
          (is (= parent-id (ss/active-session-id-in ctx))
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
