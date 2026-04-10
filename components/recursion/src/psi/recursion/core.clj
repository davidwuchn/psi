(ns psi.recursion.core
  (:require
   [clojure.string :as str]
   [psi.recursion.future-state :as future-state]
   [psi.recursion.policy :as policy]))

(defrecord RecursionContext [state-atom config host-ctx host-path])

(defn initial-state
  "Return the initial controller state map."
  []
  {:status :idle
   :current-future-state nil
   :policy (policy/default-policy)
   :config (policy/default-config)
   :hooks []
   :cycles []
   :paused-reason nil
   :paused-checkpoint nil
   :last-error nil})

(defn- hooks-from-config
  "Derive hook list from recursion config accepted/enabled trigger sets."
  [config]
  (let [accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)]
    (mapv (fn [t]
            {:id           (str "hook-" (name t))
             :trigger-type t
             :enabled      (contains? enabled t)
             :timeout-ms   nil})
          (sort-by name accepted))))

(defn create-context
  ([]
   (create-context {}))
  ([{:keys [state-overrides config-overrides]
     :or   {state-overrides {}
            config-overrides {}}}]
   (let [base-state    (initial-state)
         merged-config (merge (policy/default-config) config-overrides)
         state         (merge base-state
                              {:config merged-config
                               :hooks  (hooks-from-config merged-config)}
                              state-overrides)]
     (->RecursionContext
      (atom state)
      merged-config
      nil
      nil))))

(defonce ^:private global-ctx (atom nil))

(defn- ensure-global-ctx!
  []
  (or @global-ctx
      (let [ctx (create-context)]
        (reset! global-ctx ctx)
        ctx)))

(defn global-context
  "Return the global recursion context singleton, creating it when absent."
  []
  (ensure-global-ctx!))

(defn reset-global-context!
  "Reset the global context to nil. Useful for testing."
  []
  (reset! global-ctx nil))

(defn create-hosted-context
  [host-ctx host-path]
  (let [existing (get-in @(:state* host-ctx) host-path)
        state    (or existing (initial-state))]
    (swap! (:state* host-ctx) assoc-in host-path state)
    (->RecursionContext
     nil
     (:config state)
     host-ctx
     host-path)))

(defn get-state-in
  "Return the full controller state map from `ctx`."
  [ctx]
  (if-let [state-atom (:state-atom ctx)]
    @state-atom
    (get-in @(:state* (:host-ctx ctx)) (:host-path ctx))))

(defn swap-state-in!
  "Apply `f` to controller state atom in `ctx`."
  [ctx f & args]
  (if-let [state-atom (:state-atom ctx)]
    (apply swap! state-atom f args)
    (apply swap! (:state* (:host-ctx ctx)) update-in (:host-path ctx) f args)))

(defn get-state
  "Global wrapper for `get-state-in`."
  []
  (get-state-in (global-context)))

(defn swap-state!
  "Global wrapper for `swap-state-in!`."
  [f & args]
  (apply swap-state-in! (global-context) f args))

;;; --- Hooks ---

(defn register-hooks-in!
  "Initialize (or refresh) hooks from config accepted/enabled trigger sets."
  [ctx]
  (let [hooks (hooks-from-config (:config (get-state-in ctx)))]
    (swap-state-in! ctx assoc :hooks hooks)
    hooks))

(defn register-hooks!
  "Global wrapper for `register-hooks-in!`."
  []
  (register-hooks-in! (global-context)))

;;; --- Trigger intake and readiness gating ---

(def remember-manual-trigger-prompt-name
  "remember-manual-trigger")

(def remember-manual-trigger-prompt
  "Trigger a manual remember cycle. Capture operator reason and current readiness context.")

(defn manual-trigger-signal
  ([reason]
   (manual-trigger-signal reason {}))
  ([reason {:keys [actor source extra-payload]
            :or {actor "operator" source :unknown extra-payload {}}}]
   {:type :manual
    :reason (or reason "manual-trigger")
    :payload (merge {:prompt-name remember-manual-trigger-prompt-name
                     :prompt-body remember-manual-trigger-prompt
                     :actor actor
                     :source source}
                    extra-payload)
    :timestamp (java.time.Instant/now)}))

(defn- new-cycle
  "Create a new cycle record for `trigger-signal` with the given initial `status`."
  [trigger-signal status]
  {:cycle-id           (str "cycle-" (random-uuid))
   :trigger            trigger-signal
   :started-at         (java.time.Instant/now)
   :ended-at           nil
   :status             status
   :observation        nil
   :proposal           nil
   :execution-attempts []
   :verification       nil
   :outcome            nil
   :learning-memory-ids #{}})

(defn- active-cycle?
  "True if cycle is in a non-terminal status."
  [cycle]
  (not (contains? #{:completed :failed :aborted :blocked} (:status cycle))))

(defn- readiness-ok?
  "Check all four readiness flags in `system-state`. Returns true when all ready."
  [system-state]
  (and (:query-ready system-state)
       (:graph-ready system-state)
       (:introspection-ready system-state)
       (:memory-ready system-state)))

(defn handle-trigger-in!
  [ctx trigger-signal system-state]
  (let [state    (get-state-in ctx)
        config   (:config state)
        ttype    (:type trigger-signal)
        accepted (:accepted-trigger-types config)
        enabled  (:enabled-trigger-hooks config)]
    (cond
      ;; 1. Unknown trigger type
      (not (contains? accepted ttype))
      {:result :rejected, :reason :unknown-trigger-type}

      ;; 2. Disabled trigger — no state change, no cycle
      (not (contains? enabled ttype))
      {:result :ignored}

      ;; 3. Controller busy (not idle or has active cycles)
      (or (not= :idle (:status state))
          (some active-cycle? (:cycles state)))
      {:result :rejected, :reason :controller-busy}

      ;; 4. Readiness prerequisites fail
      (not (readiness-ok? system-state))
      (let [cycle (new-cycle trigger-signal :blocked)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :paused)
                                  (assoc :paused-reason "recursion_prerequisites_not_ready")
                                  (update :cycles conj cycle))))
        {:result :blocked, :cycle-id (:cycle-id cycle)})

      ;; 5. All checks pass — create observing cycle
      :else
      (let [cycle (new-cycle trigger-signal :observing)]
        (swap-state-in! ctx (fn [s]
                              (-> s
                                  (assoc :status :observing)
                                  (update :cycles conj cycle))))
        {:result :accepted, :cycle-id (:cycle-id cycle)}))))

(defn handle-trigger!
  "Global wrapper for `handle-trigger-in!`."
  [trigger-signal system-state]
  (handle-trigger-in! (global-context) trigger-signal system-state))

(declare find-cycle)
(declare observe-in!)
(declare plan-in!)
(declare apply-approval-gate-in!)
(declare approve-proposal-in!)
(declare reject-proposal-in!)
(declare execute-in!)
(declare verify-in!)
(declare learn-in!)
(declare update-future-state-from-outcome-in!)
(declare finalize-cycle-in!)

(defn- resolve-memory-ctx
  [memory-ctx]
  (or memory-ctx
      (let [global-memory-context (requiring-resolve 'psi.memory.core/global-context)]
        (global-memory-context))))

(defn- normalize-approval-decision
  [approval-decision]
  (cond
    (nil? approval-decision) nil
    (keyword? approval-decision) approval-decision
    (string? approval-decision)
    (keyword (str/lower-case approval-decision))
    :else ::invalid))

(defn- orchestration-result
  [ok? phase cycle-id steps]
  (merge {:ok? ok?
          :phase phase
          :cycle-id cycle-id}
         steps))

(defn- approval-step-result
  [ctx cycle-id gate-result decision approver approval-notes]
  (cond
    (not (contains? #{:manual :auto-approved} (:gate gate-result)))
    {:status :error
     :result (orchestration-result false :approval-gate cycle-id {:gate-result gate-result})}

    (and (= :manual (:gate gate-result)) (nil? decision))
    {:status :awaiting
     :result (orchestration-result true :awaiting-approval cycle-id
                                   {:gate-result gate-result
                                    :approval {:required? true
                                               :pending? true
                                               :decision nil}})}

    (= :manual (:gate gate-result))
    {:status :continue
     :approval-result (case decision
                        :approve (approve-proposal-in! ctx cycle-id approver approval-notes)
                        :reject (reject-proposal-in! ctx cycle-id approver approval-notes)
                        {:ok? false :error :approval-decision-required})}

    :else
    {:status :continue
     :approval-result nil}))

(defn- cycle-status-in
  [ctx cycle-id]
  (some-> (get-state-in ctx) :cycles (find-cycle cycle-id) :status))

(defn- run-execute-step
  [ctx cycle-id hook-executor]
  (when (= :executing (cycle-status-in ctx cycle-id))
    (if hook-executor
      (execute-in! ctx cycle-id hook-executor)
      (execute-in! ctx cycle-id))))

(defn- run-verify-step
  [ctx cycle-id check-runner]
  (when (= :verifying (cycle-status-in ctx cycle-id))
    (if check-runner
      (verify-in! ctx cycle-id check-runner)
      (verify-in! ctx cycle-id))))

(defn- run-learn-step
  [ctx cycle-id memory-ctx]
  (when (= :learning (cycle-status-in ctx cycle-id))
    (learn-in! ctx cycle-id (resolve-memory-ctx memory-ctx))))

(defn- run-orchestration-steps
  [ctx cycle-id {:keys [hook-executor check-runner memory-ctx]}]
  (let [execute-result      (run-execute-step ctx cycle-id hook-executor)
        verify-result       (run-verify-step ctx cycle-id check-runner)
        learn-result        (run-learn-step ctx cycle-id memory-ctx)
        future-state-result (when (:ok? learn-result)
                              (update-future-state-from-outcome-in! ctx cycle-id))
        finalize-result     (when (:ok? future-state-result)
                              (finalize-cycle-in! ctx cycle-id))]
    {:execute-result execute-result
     :verify-result verify-result
     :learn-result learn-result
     :future-state-result future-state-result
     :finalize-result finalize-result}))

(defn- failed-approval-step [steps]
  (when (and (:approval-result steps) (not (:ok? (:approval-result steps))))
    [:approval (:approval-result steps)]))

(defn- failed-execute-step [steps]
  (when (and (:execute-result steps) (not (:ok? (:execute-result steps))))
    [:execute (:execute-result steps)]))

(defn- failed-verify-step [steps]
  (when (and (:verify-result steps) (not (:ok? (:verify-result steps))))
    [:verify (:verify-result steps)]))

(defn- failed-learn-step [steps]
  (when (and (:learn-result steps) (not (:ok? (:learn-result steps))))
    [:learn (:learn-result steps)]))

(defn- failed-future-state-step [steps]
  (when (and (:future-state-result steps) (not (:ok? (:future-state-result steps))))
    [:future-state (:future-state-result steps)]))

(defn- failed-finalize-step [steps]
  (when (and (:finalize-result steps) (not (:ok? (:finalize-result steps))))
    [:finalize (:finalize-result steps)]))

(defn- failed-step
  [steps]
  (or (failed-approval-step steps)
      (failed-execute-step steps)
      (failed-verify-step steps)
      (failed-learn-step steps)
      (failed-future-state-step steps)
      (failed-finalize-step steps)))

(defn- invalid-approval-result
  [trigger-result approval-decision]
  {:ok? false
   :error :invalid-approval-decision
   :approval-decision approval-decision
   :expected #{:approve :reject nil}
   :trigger-result trigger-result})

(defn- pre-approval-steps
  [ctx cycle-id system-state graph-state memory-state trigger-result]
  (let [observe-result (observe-in! ctx cycle-id system-state graph-state memory-state)
        plan-result    (when (:ok? observe-result)
                         (plan-in! ctx cycle-id))
        gate-result    (when (:ok? plan-result)
                         (apply-approval-gate-in! ctx cycle-id))]
    {:trigger-result trigger-result
     :observe-result observe-result
     :plan-result plan-result
     :gate-result gate-result}))

(defn- pre-approval-failure
  [cycle-id {:keys [observe-result plan-result] :as base-steps}]
  (cond
    (not (:ok? observe-result))
    (orchestration-result false :observe cycle-id base-steps)

    (not (:ok? plan-result))
    (orchestration-result false :plan cycle-id base-steps)

    :else nil))

(defn- continue-after-approval
  [ctx cycle-id base-steps approval-result hook-executor check-runner memory-ctx]
  (let [step-results (merge base-steps
                            {:approval-result approval-result}
                            (run-orchestration-steps ctx cycle-id {:hook-executor hook-executor
                                                                  :check-runner check-runner
                                                                  :memory-ctx memory-ctx}))]
    (if-let [[phase _failed-result] (failed-step step-results)]
      (orchestration-result false phase cycle-id step-results)
      (orchestration-result true :completed cycle-id step-results))))

(defn- approval-phase-result
  [ctx cycle-id gate-result decision approver approval-notes base-steps hook-executor check-runner memory-ctx]
  (let [{:keys [status result approval-result]}
        (approval-step-result ctx cycle-id gate-result decision approver approval-notes)]
    (case status
      :error (merge base-steps result)
      :awaiting (merge base-steps result)
      :continue (continue-after-approval ctx cycle-id base-steps approval-result hook-executor check-runner memory-ctx))))

(defn orchestrate-manual-trigger-in!
  [ctx trigger-signal
   {:keys [system-state graph-state memory-state memory-ctx
           approver approval-notes approval-decision
           hook-executor check-runner]
    :or {system-state {:query-ready true
                       :graph-ready true
                       :introspection-ready true
                       :memory-ready true}
         graph-state {}
         memory-state {}
         approver "operator"
         approval-notes ""}}]
  (let [trigger-result (handle-trigger-in! ctx trigger-signal system-state)
        decision       (normalize-approval-decision approval-decision)]
    (cond
      (not= :accepted (:result trigger-result))
      {:ok? true
       :trigger-result trigger-result
       :phase :trigger}

      (= ::invalid decision)
      (invalid-approval-result trigger-result approval-decision)

      :else
      (let [cycle-id   (:cycle-id trigger-result)
            base-steps (pre-approval-steps ctx cycle-id system-state graph-state memory-state trigger-result)]
        (or (pre-approval-failure cycle-id base-steps)
            (approval-phase-result ctx cycle-id (:gate-result base-steps) decision approver approval-notes
                                   base-steps hook-executor check-runner memory-ctx))))))

(defn orchestrate-manual-trigger!
  "Global wrapper for `orchestrate-manual-trigger-in!`."
  ([trigger-signal]
   (orchestrate-manual-trigger! trigger-signal {}))
  ([trigger-signal opts]
   (orchestrate-manual-trigger-in! (global-context) trigger-signal opts)))

;;; --- Observation ---

(defn- find-cycle
  "Find a cycle by id in the cycles vector."
  [cycles cycle-id]
  (first (filter #(= cycle-id (:cycle-id %)) cycles)))

(defn- update-cycle
  "Update the cycle matching `cycle-id` with `f`."
  [cycles cycle-id f]
  (mapv (fn [c]
          (if (= cycle-id (:cycle-id c))
            (f c)
            c))
        cycles))

(defn- extract-graph-signals
  "Extract signal strings from graph-state map."
  [graph-state]
  (let [signals (transient #{})]
    (when-let [nc (:node-count graph-state)]
      (conj! signals (str "node-count=" nc)))
    (when-let [cc (:capability-count graph-state)]
      (conj! signals (str "capability-count=" cc)))
    (when-let [s (:status graph-state)]
      (conj! signals (str "status=" (name s))))
    (persistent! signals)))

(defn- extract-memory-signals
  "Extract signal strings from memory-state map."
  [memory-state]
  (let [signals (transient #{})]
    (when-let [ec (:entry-count memory-state)]
      (conj! signals (str "entry-count=" ec)))
    (when-let [s (:status memory-state)]
      (conj! signals (str "status=" (name s))))
    (when-let [rc (:recovery-count memory-state)]
      (conj! signals (str "recovery-count=" rc)))
    (persistent! signals)))

(defn- extract-gaps
  "Identify gaps from readiness and capability data."
  [readiness graph-state memory-state]
  (let [gaps (transient [])]
    (when-not (:query-ready readiness)
      (conj! gaps "query not ready"))
    (when-not (:graph-ready readiness)
      (conj! gaps "graph not ready"))
    (when-not (:introspection-ready readiness)
      (conj! gaps "introspection not ready"))
    (when-not (:memory-ready readiness)
      (conj! gaps "memory not ready"))
    (when (and (:capability-count graph-state)
               (< (:capability-count graph-state) 3))
      (conj! gaps "low capability count"))
    (when (and (:entry-count memory-state)
               (zero? (:entry-count memory-state)))
      (conj! gaps "no memory entries"))
    (persistent! gaps)))

(defn- extract-opportunities
  "Identify opportunities from system state."
  [readiness graph-state memory-state]
  (let [opps (transient [])]
    (when (and (:query-ready readiness)
               (:graph-ready readiness)
               (:introspection-ready readiness)
               (:memory-ready readiness))
      (conj! opps "system ready for evolution"))
    (when (and (:status graph-state) (= :stable (:status graph-state)))
      (conj! opps "stable graph available"))
    (when (and (:entry-count memory-state)
               (pos? (:entry-count memory-state)))
      (conj! opps "memory entries available for learning"))
    (persistent! opps)))

(defn observe-in!
  [ctx cycle-id system-state graph-state memory-state]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :observing (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (let [readiness {:query (boolean (:query-ready system-state))
                       :graph (boolean (:graph-ready system-state))
                       :introspection (boolean (:introspection-ready system-state))
                       :memory (boolean (:memory-ready system-state))}
            observation {:captured-at (java.time.Instant/now)
                         :readiness readiness
                         :graph-signals (extract-graph-signals graph-state)
                         :memory-signals (extract-memory-signals memory-state)
                         :gaps (extract-gaps system-state graph-state memory-state)
                         :opportunities (extract-opportunities system-state graph-state memory-state)}]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :planning)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :observation observation)
                                           (assoc :status :planning))))))
        {:ok? true, :observation observation}))))

(defn observe!
  "Global wrapper for `observe-in!`."
  [cycle-id system-state graph-state memory-state]
  (observe-in! (global-context) cycle-id system-state graph-state memory-state))

;;; --- Plan proposal generation ---

(def ^:private risk-order
  "Risk level ordering for aggregation (highest wins)."
  {:low 0, :medium 1, :high 2})

(defn- aggregate-risk
  "Return the highest risk level among actions. Defaults to :low."
  [actions]
  (if (empty? actions)
    :low
    (let [max-idx (apply max (map #(get risk-order (:risk %) 0) actions))]
      (first (keep (fn [[k v]] (when (= v max-idx) k)) risk-order)))))

(defn- goal->action
  [goal]
  {:id (str "action-" (:id goal))
   :title (str "Address: " (:title goal))
   :description (:description goal)
   :domain :planning
   :risk (:priority goal)
   :atomic true
   :expected-impact #{(:title goal)}
   :verification-hints #{"tests" "lint"}})

(defn plan-in!
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :planning (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      (nil? (:observation cycle))
      {:ok? false, :error :no-observation}

      :else
      (let [policy (:policy state)
            max-actions (:max-actions-per-cycle policy)
            current-fs (:current-future-state state)
            new-fs (future-state/synthesize-future-state current-fs (:observation cycle))
            ;; Sort goals: high priority first, then medium
            sorted-goals (sort-by (fn [g]
                                    (case (:priority g)
                                      :high 0
                                      :medium 1
                                      :low 2
                                      3))
                                  (:goals new-fs))
            ;; Generate actions bounded by max-actions-per-cycle
            actions (->> sorted-goals
                         (map goal->action)
                         (take max-actions)
                         vec)
            proposal {:actions actions
                      :risk (aggregate-risk actions)
                      :requires-approval (:require-human-approval policy)
                      :approved nil
                      :approval-by nil
                      :approval-notes nil
                      :generated-at (java.time.Instant/now)}]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :current-future-state new-fs)
                              (update :cycles update-cycle cycle-id
                                      #(assoc % :proposal proposal)))))
        {:ok? true, :proposal proposal, :future-state new-fs}))))

(defn plan!
  "Global wrapper for `plan-in!`."
  [cycle-id]
  (plan-in! (global-context) cycle-id))

;;; --- Approval gate ---

(defn apply-approval-gate-in!
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :planning (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      (nil? (:proposal cycle))
      {:ok? false, :error :no-proposal}

      :else
      (let [config (:config state)
            proposal (:proposal cycle)]
        (if (policy/requires-manual-approval? proposal config)
          ;; Manual approval required
          (do
            (swap-state-in! ctx
                            (fn [s]
                              (-> s
                                  (assoc :status :awaiting-approval)
                                  (update :cycles update-cycle cycle-id
                                          #(assoc % :status :awaiting-approval)))))
            {:gate :manual})
          ;; Auto-approve
          (do
            (swap-state-in! ctx
                            (fn [s]
                              (-> s
                                  (assoc :status :executing)
                                  (update :cycles update-cycle cycle-id
                                          #(-> %
                                               (assoc :status :executing)
                                               (assoc-in [:proposal :approved] true)
                                               (assoc-in [:proposal :requires-approval] false))))))
            {:gate :auto-approved}))))))

(defn apply-approval-gate!
  "Global wrapper for `apply-approval-gate-in!`."
  [cycle-id]
  (apply-approval-gate-in! (global-context) cycle-id))

;;; --- Approve / Reject proposals ---

(defn approve-proposal-in!
  [ctx cycle-id approver notes]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :awaiting-approval (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (do
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :executing)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :status :executing)
                                           (assoc-in [:proposal :approved] true)
                                           (assoc-in [:proposal :approval-by] approver)
                                           (assoc-in [:proposal :approval-notes] notes))))))
        {:ok? true}))))

(defn approve-proposal!
  "Global wrapper for `approve-proposal-in!`."
  [cycle-id approver notes]
  (approve-proposal-in! (global-context) cycle-id approver notes))

(defn reject-proposal-in!
  [ctx cycle-id approver notes]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :awaiting-approval (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (let [rejection-reason (if (seq notes) notes "proposal_rejected")
            outcome {:status :aborted
                     :summary "proposal_rejected"
                     :evidence #{rejection-reason}
                     :changed-goals #{}}]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :learning)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :status :learning)
                                           (assoc :outcome outcome)
                                           (assoc-in [:proposal :approved] false)
                                           (assoc-in [:proposal :approval-by] approver)
                                           (assoc-in [:proposal :approval-notes] notes))))))
        {:ok? true}))))

(defn reject-proposal!
  "Global wrapper for `reject-proposal-in!`."
  [cycle-id approver notes]
  (reject-proposal-in! (global-context) cycle-id approver notes))

;;; --- Execution ---

(defn- default-hook-executor
  "Default no-op hook executor. Returns success with a placeholder message."
  [_action]
  {:status :success, :output-summary "hook-not-implemented"})

(defn execute-in!
  ([ctx cycle-id]
   (execute-in! ctx cycle-id default-hook-executor))
  ([ctx cycle-id hook-executor]
   (let [state (get-state-in ctx)
         cycle (find-cycle (:cycles state) cycle-id)]
     (cond
       (nil? cycle)
       {:ok? false, :error :cycle-not-found}

       (not= :executing (:status cycle))
       {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

       (not (get-in cycle [:proposal :approved]))
       {:ok? false, :error :proposal-not-approved}

       :else
       (let [actions (get-in cycle [:proposal :actions])
             attempts (mapv (fn [action]
                              (let [started (java.time.Instant/now)
                                    result  (hook-executor action)
                                    ended   (java.time.Instant/now)]
                                {:action-id      (:id action)
                                 :started-at     started
                                 :ended-at       ended
                                 :status         (:status result)
                                 :output-summary (:output-summary result)}))
                            actions)]
         (swap-state-in! ctx
                         (fn [s]
                           (-> s
                               (assoc :status :verifying)
                               (update :cycles update-cycle cycle-id
                                       #(-> %
                                            (assoc :status :verifying)
                                            (update :execution-attempts into attempts))))))
         {:ok? true, :attempts attempts})))))

(defn execute!
  "Global wrapper for `execute-in!`."
  ([cycle-id]
   (execute-in! (global-context) cycle-id))
  ([cycle-id hook-executor]
   (execute-in! (global-context) cycle-id hook-executor)))

;;; --- Verification and rollback ---

(defn- default-check-runner
  "Default no-op check runner. Returns passing for each check."
  [_check-name]
  {:passed true, :details "check-not-implemented"})

(defn- failed-check-names
  "Return set of check names that did not pass."
  [checks]
  (into #{} (comp (remove :passed) (map :name)) checks))

(defn rollback-in!
  [ctx cycle-id]
  (swap-state-in! ctx
                  (fn [s]
                    (update s :cycles update-cycle cycle-id
                            #(update % :execution-attempts conj
                                     {:type :rollback
                                      :cycle-id cycle-id
                                      :timestamp (java.time.Instant/now)
                                      :reason "verification-failure"}))))
  {:ok? true})

(defn rollback!
  "Global wrapper for `rollback-in!`."
  [cycle-id]
  (rollback-in! (global-context) cycle-id))

(defn verify-in!
  ([ctx cycle-id]
   (verify-in! ctx cycle-id default-check-runner))
  ([ctx cycle-id check-runner]
   (let [state (get-state-in ctx)
         cycle (find-cycle (:cycles state) cycle-id)]
     (cond
       (nil? cycle)
       {:ok? false, :error :cycle-not-found}

       (not= :verifying (:status cycle))
       {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

       :else
       (let [config (:config state)
             policy (:policy state)
             required-checks (:required-verification-checks config)
             checks (mapv (fn [check-name]
                            (let [result (check-runner check-name)]
                              {:name check-name
                               :passed (:passed result)
                               :details (:details result)}))
                          (sort required-checks))
             passed-all (every? :passed checks)
             report {:checks checks
                     :passed-all passed-all
                     :completed-at (java.time.Instant/now)}]
         (if passed-all
           ;; All checks pass — transition to learning, no outcome set yet
           (do
             (swap-state-in! ctx
                             (fn [s]
                               (-> s
                                   (assoc :status :learning)
                                   (update :cycles update-cycle cycle-id
                                           #(-> %
                                                (assoc :status :learning)
                                                (assoc :verification report))))))
             {:ok? true, :report report})
           ;; Some checks failed
           (let [failed (failed-check-names checks)
                 rollback? (:rollback-on-verification-failure policy)
                 summary (if rollback?
                           "verification_failed_rolled_back"
                           "verification_failed")
                 outcome {:status :failed
                          :summary summary
                          :evidence failed
                          :changed-goals #{}}]
             ;; Record rollback if policy says so
             (when rollback?
               (rollback-in! ctx cycle-id))
             ;; Set outcome and transition to learning
             (swap-state-in! ctx
                             (fn [s]
                               (-> s
                                   (assoc :status :learning)
                                   (update :cycles update-cycle cycle-id
                                           #(-> %
                                                (assoc :status :learning)
                                                (assoc :verification report)
                                                (assoc :outcome outcome))))))
             {:ok? true, :report report})))))))

(defn verify!
  "Global wrapper for `verify-in!`."
  ([cycle-id]
   (verify-in! (global-context) cycle-id))
  ([cycle-id check-runner]
   (verify-in! (global-context) cycle-id check-runner)))

;;; --- Learn phase + memory writeback ---

(defn- build-success-outcome
  "Build a success outcome from the cycle's proposal actions and future-state goals."
  [cycle future-state]
  (let [action-titles (into #{} (map :title) (get-in cycle [:proposal :actions]))
        changed-goals (into #{} (map :id) (:goals future-state))]
    {:status :success
     :summary "cycle_completed_successfully"
     :evidence action-titles
     :changed-goals changed-goals}))

(defn- build-memory-content
  "Build the memory content string for a cycle outcome."
  [cycle-id outcome cycle]
  (let [action-titles (mapv :title (get-in cycle [:proposal :actions]))]
    (str "Remember cycle " cycle-id ": "
         (name (:status outcome)) ". "
         (:summary outcome) ". "
         "Actions: " (pr-str action-titles) ".")))

(defn learn-in!
  [ctx cycle-id memory-ctx]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (not= :learning (:status cycle))
      {:ok? false, :error :wrong-cycle-status, :status (:status cycle)}

      :else
      (let [;; If no outcome yet, this is the success path
            outcome (or (:outcome cycle)
                        (build-success-outcome cycle (:current-future-state state)))
            ;; Set the outcome on the cycle if it wasn't already set
            _ (when (nil? (:outcome cycle))
                (swap-state-in! ctx
                                (fn [s]
                                  (update s :cycles update-cycle cycle-id
                                          #(assoc % :outcome outcome)))))
            ;; Build memory content
            content (build-memory-content cycle-id outcome cycle)
            trigger-type (get-in cycle [:trigger :type])
            ;; Write to memory using remember-in!
            ;; We need to call the memory module's remember-in!
            remember-fn (requiring-resolve 'psi.memory.core/remember-in!)
            mem-result (remember-fn memory-ctx
                                    {:content-type :discovery
                                     :content content
                                     :tags ["remember" "cycle" "step-11"]
                                     :provenance {:source :remember
                                                  :cycle-id cycle-id
                                                  :trigger-type trigger-type
                                                  :outcome-status (:status outcome)}})
            record-id (get-in mem-result [:record :record-id])]
        (if (:ok? mem-result)
          (do
            ;; Store memory record ID on the cycle
            (swap-state-in! ctx
                            (fn [s]
                              (update s :cycles update-cycle cycle-id
                                      #(update % :learning-memory-ids conj record-id))))
            {:ok? true, :memory-ids #{record-id}})
          {:ok? false, :error :memory-write-failed, :details mem-result})))))

(defn learn!
  "Global wrapper for `learn-in!`."
  [cycle-id memory-ctx]
  (learn-in! (global-context) cycle-id memory-ctx))

;;; --- FUTURE_STATE updates from outcome ---

(defn update-future-state-from-outcome-in!
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (nil? (:outcome cycle))
      {:ok? false, :error :no-outcome}

      :else
      (let [outcome (:outcome cycle)
            current-fs (or (:current-future-state state)
                           (future-state/initial-future-state))
            updated-fs (case (:status outcome)
                         :success
                         (future-state/advance-goals current-fs (:changed-goals outcome))

                         (:failed :blocked :aborted)
                         (future-state/add-blockers current-fs (:evidence outcome))

                         ;; Fallback: no change, just increment version
                         (future-state/next-version current-fs))]
        (swap-state-in! ctx assoc :current-future-state updated-fs)
        {:ok? true, :future-state updated-fs}))))

(defn update-future-state-from-outcome!
  "Global wrapper for `update-future-state-from-outcome-in!`."
  [cycle-id]
  (update-future-state-from-outcome-in! (global-context) cycle-id))

;;; --- Cycle finalization ---

(defn finalize-cycle-in!
  [ctx cycle-id]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (cond
      (nil? cycle)
      {:ok? false, :error :cycle-not-found}

      (nil? (:outcome cycle))
      {:ok? false, :error :no-outcome}

      :else
      (let [outcome (:outcome cycle)
            final-status (if (= :success (:status outcome))
                           :completed
                           :failed)]
        (swap-state-in! ctx
                        (fn [s]
                          (-> s
                              (assoc :status :idle)
                              (assoc :paused-reason nil)
                              (assoc :paused-checkpoint nil)
                              (update :cycles update-cycle cycle-id
                                      #(-> %
                                           (assoc :status final-status)
                                           (assoc :ended-at (java.time.Instant/now)))))))
        {:ok? true, :final-status final-status}))))

(defn finalize-cycle!
  "Global wrapper for `finalize-cycle-in!`."
  [cycle-id]
  (finalize-cycle-in! (global-context) cycle-id))

;;; --- Continue / resume an approved cycle ---

(defn- continue-cycle-precheck
  [cycle]
  (cond
    (nil? cycle)
    {:ok? false :error :cycle-not-found}

    (= :awaiting-approval (:status cycle))
    {:ok? false :error :awaiting-approval}

    (contains? #{:completed :failed :aborted :blocked} (:status cycle))
    {:ok? true :phase :terminal :status (:status cycle)}

    :else nil))

(defn- continue-cycle-execute-step
  [ctx cycle-id cycle hook-executor]
  (when (= :executing (:status cycle))
    (if hook-executor
      (execute-in! ctx cycle-id hook-executor)
      (execute-in! ctx cycle-id))))

(defn- continue-cycle-verify-step
  [ctx cycle-id check-runner]
  (let [cycle-after-exec (find-cycle (:cycles (get-state-in ctx)) cycle-id)]
    (when (= :verifying (:status cycle-after-exec))
      (if check-runner
        (verify-in! ctx cycle-id check-runner)
        (verify-in! ctx cycle-id)))))

(defn- continue-cycle-learn-step
  [ctx cycle-id memory-ctx]
  (let [cycle-after-verify (find-cycle (:cycles (get-state-in ctx)) cycle-id)]
    (when (= :learning (:status cycle-after-verify))
      (learn-in! ctx cycle-id (resolve-memory-ctx memory-ctx)))))

(defn- continue-cycle-results
  [ctx cycle-id cycle {:keys [memory-ctx hook-executor check-runner]}]
  (let [execute-result      (continue-cycle-execute-step ctx cycle-id cycle hook-executor)
        verify-result       (continue-cycle-verify-step ctx cycle-id check-runner)
        learn-result        (continue-cycle-learn-step ctx cycle-id memory-ctx)
        future-state-result (when (:ok? learn-result)
                              (update-future-state-from-outcome-in! ctx cycle-id))
        finalize-result     (when (:ok? future-state-result)
                              (finalize-cycle-in! ctx cycle-id))]
    {:execute-result execute-result
     :verify-result verify-result
     :learn-result learn-result
     :future-state-result future-state-result
     :finalize-result finalize-result}))

(defn- continue-cycle-failure
  [{:keys [execute-result verify-result learn-result future-state-result finalize-result]}]
  (cond
    (and execute-result (not (:ok? execute-result)))
    {:ok? false :phase :execute :execute-result execute-result}

    (and verify-result (not (:ok? verify-result)))
    {:ok? false :phase :verify
     :execute-result execute-result
     :verify-result verify-result}

    (and learn-result (not (:ok? learn-result)))
    {:ok? false :phase :learn
     :execute-result execute-result
     :verify-result verify-result
     :learn-result learn-result}

    (and future-state-result (not (:ok? future-state-result)))
    {:ok? false :phase :future-state
     :execute-result execute-result
     :verify-result verify-result
     :learn-result learn-result
     :future-state-result future-state-result}

    (and finalize-result (not (:ok? finalize-result)))
    {:ok? false :phase :finalize
     :execute-result execute-result
     :verify-result verify-result
     :learn-result learn-result
     :future-state-result future-state-result
     :finalize-result finalize-result}

    :else nil))

(defn continue-cycle-in!
  [ctx cycle-id opts]
  (let [state (get-state-in ctx)
        cycle (find-cycle (:cycles state) cycle-id)]
    (or (continue-cycle-precheck cycle)
        (let [results (continue-cycle-results ctx cycle-id cycle opts)]
          (or (continue-cycle-failure results)
              (merge {:ok? true
                      :phase :completed}
                     results))))))

(defn continue-cycle!
  "Global wrapper for `continue-cycle-in!`."
  ([cycle-id]
   (continue-cycle! cycle-id {}))
  ([cycle-id opts]
   (continue-cycle-in! (global-context) cycle-id opts)))

;;; --- EQL resolver registration ---

(defn register-resolvers-in!
  ([qctx]
   (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (let [resolvers (requiring-resolve 'psi.recursion.resolvers/all-resolvers)
         register-fn (requiring-resolve 'psi.query.core/register-resolver-in!)
         rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env-in!)]
     (doseq [r @resolvers]
       (register-fn qctx r))
     (when rebuild?
       (rebuild-fn qctx))
     :ok)))

(defn register-mutations-in!
  ([qctx]
   (register-mutations-in! qctx true))
  ([qctx rebuild?]
   (let [mutations (requiring-resolve 'psi.recursion.resolvers/all-mutations)
         register-fn (requiring-resolve 'psi.query.core/register-mutation-in!)
         rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env-in!)]
     (doseq [m @mutations]
       (register-fn qctx m))
     (when rebuild?
       (rebuild-fn qctx))
     :ok)))

(defn register-resolvers!
  []
  (let [resolvers (requiring-resolve 'psi.recursion.resolvers/all-resolvers)
        mutations (requiring-resolve 'psi.recursion.resolvers/all-mutations)
        register-resolver-fn (requiring-resolve 'psi.query.core/register-resolver!)
        register-mutation-fn (requiring-resolve 'psi.query.core/register-mutation!)
        rebuild-fn (requiring-resolve 'psi.query.core/rebuild-env!)]
    (doseq [r @resolvers]
      (register-resolver-fn r))
    (doseq [m @mutations]
      (register-mutation-fn m))
    (rebuild-fn)
    :ok))
