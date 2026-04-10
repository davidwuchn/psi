(ns psi.recursion.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.recursion.core :as core]
   [psi.recursion.future-state :as future-state]
   [psi.recursion.policy :as policy]
   [psi.recursion.core-learning-finalization-test :as learning-finalization]))

(def ^:private all-ready
  {:query-ready true
   :graph-ready true
   :introspection-ready true
   :memory-ready true})

(def ^:private sample-graph-state
  {:node-count 12
   :capability-count 5
   :status :stable})

(def ^:private sample-memory-state
  {:entry-count 3
   :status :ready
   :recovery-count 1})

(defn- make-trigger
  ([ttype]
   (make-trigger ttype "test trigger"))
  ([ttype reason]
   (if (= :manual ttype)
     (core/manual-trigger-signal reason {:source :test})
     {:type      ttype
      :reason    reason
      :payload   {}
      :timestamp (java.time.Instant/now)})))

(defn- trigger-and-get-cycle-id
  [ctx]
  (core/register-hooks-in! ctx)
  (:cycle-id (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)))

;;; --- Plan proposal generation tests ---

(deftest plan-in-test
  ;; Tests plan proposal generation with policy constraints
  (testing "proposal bounded by max-actions-per-cycle=1"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          result   (core/plan-in! ctx cycle-id)]
      (is (true? (:ok? result)))
      (let [proposal (:proposal result)]
        (is (<= (count (:actions proposal)) 1)
            "should respect max-actions-per-cycle=1")
        (is (inst? (:generated-at proposal)))
        (is (true? (:requires-approval proposal))
            "default policy requires human approval")
        (is (nil? (:approved proposal)))
        (is (nil? (:approval-by proposal))))))

  (testing "proposal risk is aggregate of action risks"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Create observation with a gap (high priority → high risk action)
          _        (core/observe-in! ctx cycle-id all-ready
                                     {:capability-count 1 :status :initializing}
                                     {:entry-count 0 :status :ready})
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (= :high (:risk proposal))
          "gap goals have high priority → high risk action")))

  (testing "proposal with no gaps has lower risk"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; All ready, good counts → only opportunities (medium priority)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (= :medium (:risk proposal))
          "opportunity goals have medium priority → medium risk")))

  (testing "atomic-only policy enforced on generated actions"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (every? :atomic (:actions proposal))
          "all actions must be atomic when atomic-only=true")))

  (testing "FUTURE_STATE attached to controller after planning"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          _        (core/plan-in! ctx cycle-id)
          state    (core/get-state-in ctx)]
      (is (some? (:current-future-state state)))
      (is (= 1 (:version (:current-future-state state))))
      (is (true? (future-state/valid? (:current-future-state state))))))

  (testing "proposal attached to cycle"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          _        (core/plan-in! ctx cycle-id)
          state    (core/get-state-in ctx)
          cycle    (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (some? (:proposal cycle)))))

  (testing "plan with higher max-actions generates more actions"
    (let [ctx      (core/create-context
                    {:state-overrides {:policy (assoc (policy/default-policy)
                                                      :max-actions-per-cycle 5)}})
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Create observation with multiple gaps + opportunities
          _        (core/observe-in! ctx cycle-id all-ready
                                     {:capability-count 1 :status :initializing}
                                     {:entry-count 0 :status :ready})
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      ;; Should have more than 1 action (gaps + opportunities)
      (is (pos? (count (:actions proposal))))
      (is (<= (count (:actions proposal)) 5))))

  (testing "plan rejects wrong cycle status"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Cycle is in :observing, not :planning
          result   (core/plan-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "plan rejects if no observation"
    (let [ctx (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)]
      ;; Manually set cycle to planning without observation
      (core/swap-state-in! ctx
                           (fn [s]
                             (update s :cycles
                                     (fn [cs]
                                       (mapv #(if (= cycle-id (:cycle-id %))
                                                (assoc % :status :planning)
                                                %)
                                             cs)))))
      (let [result (core/plan-in! ctx cycle-id)]
        (is (false? (:ok? result)))
        (is (= :no-observation (:error result))))))

  (testing "empty proposal when no goals"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; Manually attach an observation with no gaps and no opportunities
          _        (core/observe-in! ctx cycle-id all-ready
                                     sample-graph-state sample-memory-state)
          ;; Override the observation to have empty gaps and opportunities
          _        (core/swap-state-in!
                    ctx
                    (fn [s]
                      (update s :cycles
                              (fn [cs]
                                (mapv #(if (= cycle-id (:cycle-id %))
                                         (assoc-in % [:observation :gaps] [])
                                         %)
                                      (mapv #(if (= cycle-id (:cycle-id %))
                                               (assoc-in % [:observation :opportunities] [])
                                               %)
                                            cs))))))
          result   (core/plan-in! ctx cycle-id)
          proposal (:proposal result)]
      (is (= :low (:risk proposal)) "empty actions → low risk")
      (is (empty? (:actions proposal))))))

;;; --- Approval policy tests ---

(defn- setup-planned-cycle
  "Helper: create a context, trigger, observe, and plan a cycle.
   Returns [ctx cycle-id]."
  ([]
   (setup-planned-cycle {}))
  ([{:keys [config-overrides state-overrides graph-state memory-state]
     :or {config-overrides {}
          state-overrides {}
          graph-state sample-graph-state
          memory-state sample-memory-state}}]
   (let [ctx (core/create-context {:config-overrides config-overrides
                                   :state-overrides state-overrides})
         cycle-id (trigger-and-get-cycle-id ctx)
         _ (core/observe-in! ctx cycle-id all-ready graph-state memory-state)
         _ (core/plan-in! ctx cycle-id)]
     [ctx cycle-id])))

(deftest requires-manual-approval-test
  ;; Test the pure approval policy function
  (let [default-config (policy/default-config)]

    (testing "medium-risk proposal requires manual approval regardless of trusted-local"
      (is (true? (policy/requires-manual-approval?
                  {:risk :medium} default-config)))
      (is (true? (policy/requires-manual-approval?
                  {:risk :medium}
                  (assoc default-config
                         :trusted-local-mode-enabled true
                         :auto-approve-low-risk-in-trusted-local-mode true)))))

    (testing "high-risk proposal requires manual approval"
      (is (true? (policy/requires-manual-approval?
                  {:risk :high} default-config)))
      (is (true? (policy/requires-manual-approval?
                  {:risk :high}
                  (assoc default-config :trusted-local-mode-enabled true)))))

    (testing "low-risk with default config (trusted-local=false) requires manual approval"
      (is (true? (policy/requires-manual-approval?
                  {:risk :low :requires-approval true} default-config))))

    (testing "low-risk with trusted-local=true AND auto-approve=true auto-approves (AC #8)"
      (is (false? (policy/requires-manual-approval?
                   {:risk :low :requires-approval true}
                   (assoc default-config
                          :trusted-local-mode-enabled true
                          :auto-approve-low-risk-in-trusted-local-mode true)))))

    (testing "low-risk with trusted-local=true AND auto-approve=false requires manual"
      (is (true? (policy/requires-manual-approval?
                  {:risk :low :requires-approval true}
                  (assoc default-config
                         :trusted-local-mode-enabled true
                         :auto-approve-low-risk-in-trusted-local-mode false)))))

    (testing "auto-approve? is inverse of requires-manual-approval?"
      (is (true? (policy/auto-approve?
                  {:risk :low}
                  (assoc default-config
                         :trusted-local-mode-enabled true
                         :auto-approve-low-risk-in-trusted-local-mode true))))
      (is (false? (policy/auto-approve?
                   {:risk :high} default-config))))))

(deftest apply-approval-gate-manual-test
  ;; AC #7: medium/high risk enters awaiting-approval
  (testing "medium-risk proposal gets manual gate"
    (let [[ctx cycle-id] (setup-planned-cycle
                          {:graph-state {:capability-count 1 :status :initializing}
                           :memory-state {:entry-count 0 :status :ready}})
          ;; The gap-based observation produces high-risk actions
          result (core/apply-approval-gate-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :manual (:gate result)))
      (is (= :awaiting-approval (:status state)))
      (is (= :awaiting-approval (:status cycle)))))

  (testing "default config (trusted-local=false) with any risk gets manual gate"
    (let [[ctx cycle-id] (setup-planned-cycle)
          result (core/apply-approval-gate-in! ctx cycle-id)
          state (core/get-state-in ctx)]
      (is (= :manual (:gate result)))
      (is (= :awaiting-approval (:status state))))))

(deftest apply-approval-gate-auto-approve-test
  ;; AC #8: trusted-local low-risk auto-approve
  (testing "low-risk with trusted-local auto-approve gets auto-approved"
    (let [;; Create context with trusted-local mode and an observation that produces
          ;; only opportunities (medium priority → medium risk... but we need low risk)
          ;; We need to produce a low-risk proposal. Empty actions → low risk.
          ctx (core/create-context
               {:config-overrides {:trusted-local-mode-enabled true
                                   :auto-approve-low-risk-in-trusted-local-mode true}})
          cycle-id (trigger-and-get-cycle-id ctx)
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; Override observation to have no gaps/opportunities → empty actions → low risk
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (update s :cycles
                       (fn [cs]
                         (mapv #(if (= cycle-id (:cycle-id %))
                                  (-> %
                                      (assoc-in [:observation :gaps] [])
                                      (assoc-in [:observation :opportunities] []))
                                  %)
                               cs)))))
          _ (core/plan-in! ctx cycle-id)
          ;; Verify proposal is low risk
          state-before (core/get-state-in ctx)
          cycle-before (first (filter #(= cycle-id (:cycle-id %)) (:cycles state-before)))
          _ (assert (= :low (:risk (:proposal cycle-before))) "precondition: low risk")
          result (core/apply-approval-gate-in! ctx cycle-id)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :auto-approved (:gate result)))
      (is (= :executing (:status state)))
      (is (= :executing (:status cycle)))
      (is (true? (get-in cycle [:proposal :approved])))
      (is (false? (get-in cycle [:proposal :requires-approval]))))))

(deftest apply-approval-gate-rejects-invalid-state-test
  ;; Verification hardening for transition guard behavior around approval gate.
  (testing "apply-approval-gate rejects unknown cycle-id"
    (let [ctx (core/create-context)
          result (core/apply-approval-gate-in! ctx "nonexistent")]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result)))))

  (testing "apply-approval-gate rejects wrong cycle status"
    (let [ctx (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          ;; cycle is still :observing
          result (core/apply-approval-gate-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))
      (is (= :observing (:status result)))))

  (testing "apply-approval-gate rejects planning cycle missing proposal"
    (let [ctx (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          ;; cycle now in :planning, but no proposal attached
          result (core/apply-approval-gate-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :no-proposal (:error result))))))

(deftest approve-proposal-in-test
  ;; Approve transitions to executing
  (testing "approve transitions to executing"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          result (core/approve-proposal-in! ctx cycle-id "user@test" "looks good")
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :executing (:status state)))
      (is (= :executing (:status cycle)))
      (is (true? (get-in cycle [:proposal :approved])))
      (is (= "user@test" (get-in cycle [:proposal :approval-by])))
      (is (= "looks good" (get-in cycle [:proposal :approval-notes])))))

  (testing "approve rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Cycle is in :planning, not :awaiting-approval
          result (core/approve-proposal-in! ctx cycle-id "user" "notes")]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result))))))

(deftest reject-proposal-in-test
  ;; Reject transitions to learning
  (testing "reject transitions to learning"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          result (core/reject-proposal-in! ctx cycle-id "user@test" "too risky")
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :learning (:status state)))
      (is (= :learning (:status cycle)))
      (is (false? (get-in cycle [:proposal :approved])))
      (is (= "user@test" (get-in cycle [:proposal :approval-by])))
      (is (= "too risky" (get-in cycle [:proposal :approval-notes])))
      (is (= :aborted (get-in cycle [:outcome :status])))
      (is (= "proposal_rejected" (get-in cycle [:outcome :summary])))
      (is (= #{"too risky"} (get-in cycle [:outcome :evidence])))))

  (testing "reject sets default rejection evidence when notes are blank"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/reject-proposal-in! ctx cycle-id "user@test" "")
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= #{"proposal_rejected"} (get-in cycle [:outcome :evidence])))))

  (testing "reject rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          result (core/reject-proposal-in! ctx cycle-id "user" "notes")]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result))))))

;;; --- Execution tests ---

(deftest execute-in-test
  ;; Execute creates attempt records and transitions to verifying
  (testing "execute creates attempt records and transitions to verifying"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/approve-proposal-in! ctx cycle-id "user" "approved")
          executor (fn [action]
                     {:status :success
                      :output-summary (str "executed " (:id action))})
          result (core/execute-in! ctx cycle-id executor)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (vector? (:attempts result)))
      (is (= :verifying (:status state)))
      (is (= :verifying (:status cycle)))
      ;; Check attempt records
      (let [attempts (:execution-attempts cycle)]
        (is (pos? (count attempts)))
        (doseq [attempt attempts]
          (is (string? (:action-id attempt)))
          (is (inst? (:started-at attempt)))
          (is (inst? (:ended-at attempt)))
          (is (= :success (:status attempt)))
          (is (string? (:output-summary attempt)))))))

  (testing "execute with default hook-executor"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/approve-proposal-in! ctx cycle-id "user" "ok")
          result (core/execute-in! ctx cycle-id)
          state (core/get-state-in ctx)]
      (is (true? (:ok? result)))
      (is (= :verifying (:status state)))
      (doseq [attempt (:attempts result)]
        (is (= :success (:status attempt)))
        (is (= "hook-not-implemented" (:output-summary attempt))))))

  (testing "execute with failed hook"
    (let [[ctx cycle-id] (setup-planned-cycle)
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/approve-proposal-in! ctx cycle-id "user" "ok")
          executor (fn [_action]
                     {:status :failed
                      :output-summary "hook error"})
          result (core/execute-in! ctx cycle-id executor)]
      (is (true? (:ok? result)))
      (doseq [attempt (:attempts result)]
        (is (= :failed (:status attempt))))))

  (testing "execute rejects if proposal not approved"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Manually set cycle to executing without approval
          _ (core/swap-state-in!
             ctx
             (fn [s]
               (-> s
                   (assoc :status :executing)
                   (update :cycles
                           (fn [cs]
                             (mapv #(if (= cycle-id (:cycle-id %))
                                      (assoc % :status :executing)
                                      %)
                                   cs))))))
          result (core/execute-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :proposal-not-approved (:error result)))))

  (testing "execute rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Cycle is in :planning
          result (core/execute-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result))))))

;;; --- Verification and rollback tests ---

(defn- setup-executed-cycle
  "Helper: create a context, trigger, observe, plan, approve, and execute a cycle.
   Returns [ctx cycle-id]. Optionally accepts config-overrides and hook-executor."
  ([]
   (setup-executed-cycle {}))
  ([{:keys [config-overrides state-overrides hook-executor]
     :or {config-overrides {}
          state-overrides {}
          hook-executor (fn [_action] {:status :success :output-summary "test-ok"})}}]
   (let [ctx (core/create-context {:config-overrides config-overrides
                                   :state-overrides state-overrides})
         cycle-id (trigger-and-get-cycle-id ctx)
         _ (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
         _ (core/plan-in! ctx cycle-id)
         _ (core/apply-approval-gate-in! ctx cycle-id)
         _ (core/approve-proposal-in! ctx cycle-id "user" "approved")
         _ (core/execute-in! ctx cycle-id hook-executor)]
     [ctx cycle-id])))

(deftest verify-in-all-pass-test
  ;; AC #10: All checks pass → verification report has passed-all=true,
  ;; cycle transitions to learning, no rollback
  (testing "all checks pass → learning, no rollback, no outcome set"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [_check-name]
                         {:passed true :details "all good"})
          result (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))

      (testing "report structure"
        (let [report (:report result)]
          (is (true? (:passed-all report)))
          (is (inst? (:completed-at report)))
          (is (= 3 (count (:checks report))) "should have 3 required checks")
          (is (every? :passed (:checks report)))))

      (testing "cycle transitions to learning"
        (is (= :learning (:status state)))
        (is (= :learning (:status cycle))))

      (testing "verification report attached to cycle"
        (is (some? (:verification cycle)))
        (is (true? (get-in cycle [:verification :passed-all]))))

      (testing "no outcome set yet (success outcome set by learn phase)"
        (is (nil? (:outcome cycle))))

      (testing "no rollback evidence"
        (is (not-any? #(= :rollback (:type %)) (:execution-attempts cycle)))))))

(deftest verify-in-fail-with-rollback-test
  ;; AC #10: One check fails with rollback enabled → outcome is failed with
  ;; "verification_failed_rolled_back", rollback evidence recorded
  (testing "check fails with rollback-on-verification-failure=true"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [check-name]
                         (if (= check-name "tests")
                           {:passed false :details "2 failures"}
                           {:passed true :details "ok"}))
          result (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))

      (testing "report shows not all passed"
        (is (false? (get-in result [:report :passed-all]))))

      (testing "cycle transitions to learning"
        (is (= :learning (:status state)))
        (is (= :learning (:status cycle))))

      (testing "outcome is failed with rollback summary"
        (let [outcome (:outcome cycle)]
          (is (= :failed (:status outcome)))
          (is (= "verification_failed_rolled_back" (:summary outcome)))
          (is (contains? (:evidence outcome) "tests"))
          (is (= #{} (:changed-goals outcome)))))

      (testing "rollback evidence recorded in execution-attempts"
        (let [rollback-records (filter #(= :rollback (:type %))
                                       (:execution-attempts cycle))]
          (is (= 1 (count rollback-records)))
          (let [rb (first rollback-records)]
            (is (= cycle-id (:cycle-id rb)))
            (is (inst? (:timestamp rb)))
            (is (= "verification-failure" (:reason rb))))))

      (testing "verification report attached to cycle"
        (is (some? (:verification cycle)))
        (is (= 3 (count (get-in cycle [:verification :checks]))))))))

(deftest verify-in-fail-without-rollback-test
  ;; Check fails with rollback disabled → outcome is failed, no rollback
  (testing "check fails with rollback-on-verification-failure=false"
    (let [[ctx cycle-id] (setup-executed-cycle
                          {:state-overrides
                           {:policy (assoc (policy/default-policy)
                                           :rollback-on-verification-failure false)}})
          check-runner (fn [check-name]
                         (if (= check-name "lint")
                           {:passed false :details "lint errors"}
                           {:passed true :details "ok"}))
          result (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))

      (testing "cycle transitions to learning"
        (is (= :learning (:status state)))
        (is (= :learning (:status cycle))))

      (testing "outcome is failed without rollback"
        (let [outcome (:outcome cycle)]
          (is (= :failed (:status outcome)))
          (is (= "verification_failed" (:summary outcome)))
          (is (contains? (:evidence outcome) "lint"))))

      (testing "no rollback evidence"
        (is (not-any? #(= :rollback (:type %)) (:execution-attempts cycle)))))))

(deftest verify-in-report-contains-all-checks-test
  ;; Verification report contains all required check names
  (testing "report contains all required check names from config"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [check-name]
                         {:passed true :details (str check-name " ok")})
          result (core/verify-in! ctx cycle-id check-runner)
          check-names (set (map :name (get-in result [:report :checks])))]
      (is (= #{"tests" "lint" "eql-health"} check-names)))))

(deftest verify-in-multiple-failures-test
  ;; Multiple checks fail — all failed names in evidence
  (testing "multiple check failures captured in evidence"
    (let [[ctx cycle-id] (setup-executed-cycle)
          check-runner (fn [check-name]
                         (if (= check-name "lint")
                           {:passed true :details "ok"}
                           {:passed false :details "failed"}))
          _ (core/verify-in! ctx cycle-id check-runner)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))
          outcome (:outcome cycle)]
      (is (= :failed (:status outcome)))
      (is (contains? (:evidence outcome) "tests"))
      (is (contains? (:evidence outcome) "eql-health"))
      (is (not (contains? (:evidence outcome) "lint"))))))

(deftest verify-in-default-check-runner-test
  ;; Default check-runner passes all checks
  (testing "default check-runner passes all checks"
    (let [[ctx cycle-id] (setup-executed-cycle)
          result (core/verify-in! ctx cycle-id)
          state (core/get-state-in ctx)]
      (is (true? (:ok? result)))
      (is (true? (get-in result [:report :passed-all])))
      (is (= :learning (:status state))))))

(deftest verify-in-rejects-wrong-status-test
  ;; Verify rejects if cycle is not in :verifying status
  (testing "verify rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          ;; Cycle is in :planning, not :verifying
          result (core/verify-in! ctx cycle-id)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "verify rejects unknown cycle-id"
    (let [ctx (core/create-context)
          result (core/verify-in! ctx "nonexistent")]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))

;;; --- Learn phase + memory writeback tests ---

(deftest learn-in-success-path-test
  ;; AC #11: Learn writes memory record with correct tags and provenance
  (testing "learn writes memory record on success path"
    (let [[ctx cycle-id memory-ctx] (learning-finalization/setup-verified-cycle)
          result (core/learn-in! ctx cycle-id memory-ctx)]
      (is (true? (:ok? result)))
      (is (set? (:memory-ids result)))
      (is (= 1 (count (:memory-ids result))))

      (testing "memory record has correct tags"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))]
          (is (some? record))
          (is (= :discovery (:content-type record)))
          (is (some #{"remember"} (:tags record)))
          (is (some #{"cycle"} (:tags record)))
          (is (some #{"step-11"} (:tags record)))))

      (testing "memory record has correct provenance"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))
              prov (:provenance record)]
          (is (= :remember (:source prov)))
          (is (= cycle-id (:cycle-id prov)))
          (is (= :manual (:trigger-type prov)))
          (is (= :success (:outcome-status prov)))))

      (testing "memory content mentions cycle-id and outcome"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))]
          (is (str/includes? (:content record) cycle-id))
          (is (str/includes? (:content record) "success")))))))

(deftest learn-in-stores-memory-id-test
  ;; AC #11: Learn stores memory record-id in cycle's learning-memory-ids
  (testing "learn stores memory record-id in cycle"
    (let [[ctx cycle-id memory-ctx] (learning-finalization/setup-verified-cycle)
          result (core/learn-in! ctx cycle-id memory-ctx)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= (:memory-ids result) (:learning-memory-ids cycle)))
      (is (= 1 (count (:learning-memory-ids cycle)))))))

(deftest learn-in-sets-success-outcome-test
  ;; When cycle has no outcome (all checks passed), learn sets success outcome
  (testing "learn sets success outcome when none exists"
    (let [[ctx cycle-id memory-ctx] (learning-finalization/setup-verified-cycle)
          state-before (core/get-state-in ctx)
          cycle-before (first (filter #(= cycle-id (:cycle-id %)) (:cycles state-before)))
          _ (is (nil? (:outcome cycle-before)) "precondition: no outcome before learn")
          _ (core/learn-in! ctx cycle-id memory-ctx)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :success (get-in cycle [:outcome :status])))
      (is (= "cycle_completed_successfully" (get-in cycle [:outcome :summary])))
      (is (set? (get-in cycle [:outcome :evidence])))
      (is (set? (get-in cycle [:outcome :changed-goals]))))))

(deftest learn-in-failed-outcome-test
  ;; When cycle has a failed outcome (verification failed), learn preserves it
  (testing "learn preserves existing failed outcome"
    (let [[ctx cycle-id memory-ctx] (learning-finalization/setup-verified-failed-cycle)
          state-before (core/get-state-in ctx)
          cycle-before (first (filter #(= cycle-id (:cycle-id %)) (:cycles state-before)))
          _ (is (= :failed (get-in cycle-before [:outcome :status])) "precondition: failed outcome")
          result (core/learn-in! ctx cycle-id memory-ctx)]
      (is (true? (:ok? result)))

      (testing "memory record reflects failed outcome"
        (let [mem-state (memory/get-state-in memory-ctx)
              record (first (:records mem-state))
              prov (:provenance record)]
          (is (= :failed (:outcome-status prov))))))))

(deftest learn-in-preserves-aborted-outcome-test
  (testing "learn preserves rejected/aborted outcome"
    (let [[ctx cycle-id] (setup-planned-cycle)
          memory-ctx (memory/create-context
                      {:state-overrides {:status :ready}
                       :require-provenance-on-write? false})
          _ (core/apply-approval-gate-in! ctx cycle-id)
          _ (core/reject-proposal-in! ctx cycle-id "reviewer" "too risky")
          result (core/learn-in! ctx cycle-id memory-ctx)
          state (core/get-state-in ctx)
          cycle (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))
          record (first (:records (memory/get-state-in memory-ctx)))]
      (is (true? (:ok? result)))
      (is (= :aborted (get-in cycle [:outcome :status])))
      (is (= :aborted (get-in record [:provenance :outcome-status]))))))

(deftest learn-in-rejects-wrong-status-test
  (testing "learn rejects wrong cycle status"
    (let [[ctx cycle-id] (setup-planned-cycle)
          memory-ctx (memory/create-context {:require-provenance-on-write? false})
          result (core/learn-in! ctx cycle-id memory-ctx)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "learn rejects unknown cycle-id"
    (let [ctx (core/create-context)
          memory-ctx (memory/create-context {:require-provenance-on-write? false})
          result (core/learn-in! ctx "nonexistent" memory-ctx)]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))
