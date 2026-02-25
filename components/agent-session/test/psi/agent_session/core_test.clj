(ns psi.agent-session.core-test
  "Integration tests for the agent-session component.

  Every test gets its own isolated context via `create-context` (Nullable
  pattern) — no global-state mutations, no ordering dependencies."
  (:require
   [clojure.test :refer [deftest testing is]]
   [psi.agent-session.core :as session]
   [psi.agent-session.session :as session-data]
   [psi.agent-session.extensions :as ext]))

;; ── Context creation and isolation ─────────────────────────────────────────

(deftest context-isolation-test
  (testing "two contexts are independent"
    (let [ctx-a (session/create-context)
          ctx-b (session/create-context)]
      (session/set-session-name-in! ctx-a "alpha")
      (is (= "alpha" (:session-name (session/get-session-data-in ctx-a))))
      (is (nil? (:session-name (session/get-session-data-in ctx-b))))))

  (testing "create-context starts in :idle phase"
    (let [ctx (session/create-context)]
      (is (= :idle (session/sc-phase-in ctx)))
      (is (session/idle-in? ctx)))))

;; ── Session lifecycle ───────────────────────────────────────────────────────

(deftest new-session-test
  (testing "new-session-in! resets session-id"
    (let [ctx    (session/create-context)
          old-id (:session-id (session/get-session-data-in ctx))]
      (session/new-session-in! ctx)
      (let [new-id (:session-id (session/get-session-data-in ctx))]
        (is (not= old-id new-id)))))

  (testing "new-session-in! clears queues"
    (let [ctx (session/create-context)]
      (session/steer-in! ctx "steer me")
      (session/new-session-in! ctx)
      (let [sd (session/get-session-data-in ctx)]
        (is (= [] (:steering-messages sd))))))

  (testing "new-session-in! is cancelled when extension returns cancel"
    (let [ctx (session/create-context)
          reg (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/cancel")
      (ext/register-handler-in! reg "/ext/cancel" "session_before_switch"
                                 (fn [_] {:cancel true}))
      (let [old-id (:session-id (session/get-session-data-in ctx))]
        (session/new-session-in! ctx)
        ;; session-id unchanged because cancelled
        (is (= old-id (:session-id (session/get-session-data-in ctx))))))))

;; ── Model management ────────────────────────────────────────────────────────

(deftest model-management-test
  (testing "set-model-in! updates model and persists entry"
    (let [ctx   (session/create-context)
          model {:provider "anthropic" :id "claude-3-5-sonnet" :reasoning false}]
      (session/set-model-in! ctx model)
      (let [sd (session/get-session-data-in ctx)]
        (is (= model (:model sd))))
      (is (pos? (count @(:journal-atom ctx))))))

  (testing "set-model-in! clamps thinking level for non-reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :high}})]
      (session/set-model-in! ctx {:provider "x" :id "y" :reasoning false})
      (is (= :off (:thinking-level (session/get-session-data-in ctx))))))

  (testing "set-model-in! preserves thinking level for reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :high}})]
      (session/set-model-in! ctx {:provider "x" :id "y" :reasoning true})
      (is (= :high (:thinking-level (session/get-session-data-in ctx)))))))

;; ── Thinking level ──────────────────────────────────────────────────────────

(deftest thinking-level-test
  (testing "set-thinking-level-in! updates level"
    (let [ctx (session/create-context)]
      (session/set-model-in! ctx {:provider "x" :id "y" :reasoning true})
      (session/set-thinking-level-in! ctx :medium)
      (is (= :medium (:thinking-level (session/get-session-data-in ctx))))))

  (testing "cycle-thinking-level-in! advances level for reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :off}})]
      (session/set-model-in! ctx {:provider "x" :id "y" :reasoning true})
      (session/cycle-thinking-level-in! ctx)
      (is (= :minimal (:thinking-level (session/get-session-data-in ctx))))))

  (testing "cycle-thinking-level-in! is no-op for non-reasoning model"
    (let [ctx (session/create-context {:initial-session {:thinking-level :off}})]
      (session/set-model-in! ctx {:provider "x" :id "y" :reasoning false})
      (session/cycle-thinking-level-in! ctx)
      (is (= :off (:thinking-level (session/get-session-data-in ctx)))))))

;; ── Session naming ──────────────────────────────────────────────────────────

(deftest session-naming-test
  (testing "set-session-name-in! updates name and appends entry"
    (let [ctx (session/create-context)]
      (session/set-session-name-in! ctx "my session")
      (is (= "my session" (:session-name (session/get-session-data-in ctx))))
      (is (some #(= :session-info (:kind %)) @(:journal-atom ctx))))))

;; ── Context token tracking ──────────────────────────────────────────────────

(deftest context-usage-test
  (testing "update-context-usage-in! stores tokens and window"
    (let [ctx (session/create-context)]
      (session/update-context-usage-in! ctx 5000 100000)
      (let [sd (session/get-session-data-in ctx)]
        (is (= 5000 (:context-tokens sd)))
        (is (= 100000 (:context-window sd))))))

  (testing "context fraction reflects stored usage"
    (let [ctx (session/create-context)]
      (session/update-context-usage-in! ctx 80000 100000)
      (let [sd (session/get-session-data-in ctx)]
        (is (= 0.8 (session-data/context-fraction-used sd)))))))

;; ── Auto-retry and compaction config ───────────────────────────────────────

(deftest config-flags-test
  (testing "set-auto-retry-in! enables/disables retry"
    (let [ctx (session/create-context)]
      (session/set-auto-retry-in! ctx false)
      (is (false? (:auto-retry-enabled (session/get-session-data-in ctx))))))

  (testing "set-auto-compaction-in! enables/disables compaction"
    (let [ctx (session/create-context)]
      (session/set-auto-compaction-in! ctx true)
      (is (true? (:auto-compaction-enabled (session/get-session-data-in ctx)))))))

;; ── Manual compaction ───────────────────────────────────────────────────────

(deftest manual-compaction-test
  (testing "manual-compact-in! runs stub compaction and returns to idle"
    (let [ctx    (session/create-context)
          result (session/manual-compact-in! ctx)]
      (is (string? (:summary result)))
      (is (= :idle (session/sc-phase-in ctx)))))

  (testing "manual-compact-in! with custom compaction-fn uses that fn"
    (let [custom-fn (fn [_sd _prep _instr]
                      {:summary "custom summary"
                       :first-kept-entry-id nil
                       :tokens-before nil
                       :details nil})
          ctx    (session/create-context {:compaction-fn custom-fn})
          result (session/manual-compact-in! ctx)]
      (is (= "custom summary" (:summary result)))))

  (testing "manual-compact-in! can be cancelled by extension"
    (let [ctx (session/create-context)
          reg (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                 (fn [_] {:cancel true}))
      (let [result (session/manual-compact-in! ctx)]
        (is (nil? result)))))

  (testing "extension can supply custom CompactionResult"
    (let [custom-result {:summary "ext summary"
                         :first-kept-entry-id nil
                         :tokens-before nil
                         :details nil}
          ctx (session/create-context)
          reg (:extension-registry ctx)]
      (ext/register-extension-in! reg "/ext/c")
      (ext/register-handler-in! reg "/ext/c" "session_before_compact"
                                 (fn [_] {:result custom-result}))
      (let [result (session/manual-compact-in! ctx)]
        (is (= "ext summary" (:summary result)))))))

;; ── Extension dispatch ──────────────────────────────────────────────────────

(deftest extension-dispatch-test
  (testing "dispatch-extension-event-in! fires handlers"
    (let [ctx   (session/create-context)
          fired (atom false)]
      (session/register-extension-in! ctx "/ext/a")
      (session/register-handler-in! ctx "/ext/a" "my_event"
                                    (fn [_] (reset! fired true) nil))
      (session/dispatch-extension-event-in! ctx "my_event" {:x 1})
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
      (session/set-model-in! ctx {:provider "x" :id "y" :reasoning false})
      (session/set-session-name-in! ctx "test")
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
      (session/set-model-in! ctx model)
      (let [result (session/query-in ctx [:psi.agent-session/model])]
        (is (= model (:psi.agent-session/model result))))))

  (testing "query-in resolves context fraction"
    (let [ctx (session/create-context)]
      (session/update-context-usage-in! ctx 4000 10000)
      (let [result (session/query-in ctx [:psi.agent-session/context-fraction])]
        (is (= 0.4 (:psi.agent-session/context-fraction result))))))

  (testing "query-in resolves extension-summary"
    (let [ctx (session/create-context)]
      (session/register-extension-in! ctx "/ext/a")
      (let [result (session/query-in ctx [:psi.agent-session/extension-summary])]
        (is (= 1 (get-in result [:psi.agent-session/extension-summary :extension-count]))))))

  (testing "query-in resolves stats"
    (let [ctx    (session/create-context)
          result (session/query-in ctx [:psi.agent-session/stats])]
      (is (map? (:psi.agent-session/stats result)))
      (is (contains? (:psi.agent-session/stats result) :session-id)))))
