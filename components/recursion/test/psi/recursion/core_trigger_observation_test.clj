(ns psi.recursion.core-trigger-observation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.recursion.core :as core]
   [psi.recursion.future-state :as future-state]))

(def ^:private all-ready
  {:query-ready true
   :graph-ready true
   :introspection-ready true
   :memory-ready true})

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

(deftest register-hooks-in-test
  (testing "register-hooks-in! populates hooks from config"
    (let [ctx   (core/create-context)
          hooks (core/register-hooks-in! ctx)
          state (core/get-state-in ctx)]
      (is (= 5 (count hooks)) "should have one hook per accepted trigger type")
      (is (= 5 (count (:hooks state))))
      (is (every? :enabled hooks) "all hooks enabled by default config")
      (is (every? #(string? (:id %)) hooks))
      (is (every? #(keyword? (:trigger-type %)) hooks))))

  (testing "register-hooks-in! respects enabled subset"
    (let [ctx   (core/create-context {:config-overrides {:enabled-trigger-hooks #{:manual}}})
          hooks (core/register-hooks-in! ctx)]
      (is (= 1 (count (filter :enabled hooks))) "only :manual should be enabled")
      (is (= 4 (count (remove :enabled hooks)))))))

(deftest handle-trigger-accepted-test
  (testing "accepted trigger with all readiness"
    (let [ctx    (core/create-context)
          _      (core/register-hooks-in! ctx)
          result (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :accepted (:result result)))
      (is (string? (:cycle-id result)))
      (is (= :observing (:status state)) "controller should be observing")
      (is (= 1 (count (:cycles state))))
      (let [cycle (first (:cycles state))]
        (is (= :observing (:status cycle)) "cycle should be observing")
        (is (= (:cycle-id result) (:cycle-id cycle)))
        (is (= :manual (get-in cycle [:trigger :type])))
        (is (inst? (:started-at cycle)))
        (is (nil? (:ended-at cycle)))
        (is (nil? (:observation cycle)))
        (is (nil? (:proposal cycle)))
        (is (= [] (:execution-attempts cycle)))
        (is (nil? (:verification cycle)))
        (is (nil? (:outcome cycle)))
        (is (= #{} (:learning-memory-ids cycle)))))))

(deftest handle-trigger-ignored-test
  (testing "disabled trigger type is ignored"
    (let [ctx    (core/create-context
                  {:config-overrides {:enabled-trigger-hooks #{:manual}}})
          _      (core/register-hooks-in! ctx)
          result (core/handle-trigger-in! ctx (make-trigger :graph-changed) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :ignored (:result result)))
      (is (nil? (:cycle-id result)))
      (is (= :idle (:status state)) "controller state unchanged")
      (is (= [] (:cycles state)) "no cycle created"))))

(deftest handle-trigger-blocked-test
  (testing "blocked when memory not ready"
    (let [ctx    (core/create-context)
          _      (core/register-hooks-in! ctx)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :memory-ready false))
          state  (core/get-state-in ctx)]
      (is (= :blocked (:result result)))
      (is (string? (:cycle-id result)))
      (is (= :paused (:status state)) "controller should be paused")
      (is (= "recursion_prerequisites_not_ready" (:paused-reason state)))
      (let [cycle (first (:cycles state))]
        (is (= :blocked (:status cycle))))))

  (testing "blocked when query not ready"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :query-ready false))
          state  (core/get-state-in ctx)]
      (is (= :blocked (:result result)))
      (is (= :paused (:status state)))))

  (testing "blocked when introspection not ready"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :introspection-ready false))]
      (is (= :blocked (:result result)))))

  (testing "blocked when graph not ready"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :manual)
                                          (assoc all-ready :graph-ready false))]
      (is (= :blocked (:result result))))))

(deftest handle-trigger-rejected-unknown-test
  (testing "unknown trigger type rejected"
    (let [ctx    (core/create-context)
          result (core/handle-trigger-in! ctx (make-trigger :unknown-type) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :rejected (:result result)))
      (is (= :unknown-trigger-type (:reason result)))
      (is (= :idle (:status state)) "controller state unchanged")
      (is (= [] (:cycles state)) "no cycle created"))))

(deftest handle-trigger-rejected-busy-test
  (testing "rejected when controller not idle"
    (let [ctx    (core/create-context {:state-overrides {:status :observing}})
          result (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)
          state  (core/get-state-in ctx)]
      (is (= :rejected (:result result)))
      (is (= :controller-busy (:reason result)))
      (is (= :observing (:status state)) "status unchanged")))

  (testing "rejected when active cycle exists"
    (let [ctx    (core/create-context)
          _      (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)
          result (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)]
      (is (= :rejected (:result result)))
      (is (= :controller-busy (:reason result))))))

(deftest orchestrate-manual-trigger-awaits-explicit-approval-test
  (testing "orchestration stops at awaiting-approval without explicit decision"
    (let [ctx (core/create-context)
          _ (core/register-hooks-in! ctx)
          trigger (core/manual-trigger-signal "manual run" {:source :test})
          result (core/orchestrate-manual-trigger-in!
                  ctx
                  trigger
                  {:system-state all-ready
                   :graph-state {:node-count 5 :capability-count 3 :status :stable}
                   :memory-state {:entry-count 2 :status :ready :recovery-count 0}})
          state (core/get-state-in ctx)
          cycle (some->> (:cycles state) last)]
      (is (true? (:ok? result)))
      (is (= :awaiting-approval (:phase result)))
      (is (= :accepted (get-in result [:trigger-result :result])))
      (is (= :awaiting-approval (:status state)))
      (is (= :awaiting-approval (:status cycle)))
      (is (= :manual (get-in result [:gate-result :gate]))))))

(deftest orchestrate-manual-trigger-approve-completes-test
  (testing "orchestration with explicit :approve runs full cycle to finalize"
    (let [ctx (core/create-context)
          _ (core/register-hooks-in! ctx)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          trigger (core/manual-trigger-signal "manual approve" {:source :test})
          result (core/orchestrate-manual-trigger-in!
                  ctx
                  trigger
                  {:system-state all-ready
                   :graph-state {:node-count 5 :capability-count 3 :status :stable}
                   :memory-state {:entry-count 2 :status :ready :recovery-count 0}
                   :memory-ctx mem-ctx
                   :approval-decision :approve
                   :approver "reviewer"
                   :approval-notes "ship it"})
          state (core/get-state-in ctx)
          cycle (first (filter #(= (:cycle-id result) (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :completed (:phase result)))
      (is (= :idle (:status state)))
      (is (= :completed (:status cycle)))
      (is (= true (get-in cycle [:proposal :approved])))
      (is (= :success (get-in cycle [:outcome :status]))))))

(deftest orchestrate-manual-trigger-reject-completes-with-aborted-outcome-test
  (testing "orchestration with explicit :reject skips execution and finalizes failed"
    (let [ctx (core/create-context)
          _ (core/register-hooks-in! ctx)
          mem-ctx (memory/create-context
                   {:state-overrides {:status :ready}
                    :require-provenance-on-write? false})
          trigger (core/manual-trigger-signal "manual reject" {:source :test})
          result (core/orchestrate-manual-trigger-in!
                  ctx
                  trigger
                  {:system-state all-ready
                   :graph-state {:node-count 5 :capability-count 3 :status :stable}
                   :memory-state {:entry-count 2 :status :ready :recovery-count 0}
                   :memory-ctx mem-ctx
                   :approval-decision :reject
                   :approver "reviewer"
                   :approval-notes "not safe"})
          state (core/get-state-in ctx)
          cycle (first (filter #(= (:cycle-id result) (:cycle-id %)) (:cycles state)))]
      (is (true? (:ok? result)))
      (is (= :completed (:phase result)))
      (is (= :idle (:status state)))
      (is (= :failed (:status cycle)))
      (is (= false (get-in cycle [:proposal :approved])))
      (is (= :aborted (get-in cycle [:outcome :status])))
      (is (empty? (:execution-attempts cycle))))))

(def ^:private sample-graph-state
  {:node-count 12
   :capability-count 5
   :status :stable})

(def ^:private sample-memory-state
  {:entry-count 3
   :status :ready
   :recovery-count 1})

(defn- trigger-and-get-cycle-id
  [ctx]
  (core/register-hooks-in! ctx)
  (:cycle-id (core/handle-trigger-in! ctx (make-trigger :manual) all-ready)))

(deftest observe-in-test
  (testing "observation captures correct readiness map and signals"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          result   (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)]
      (is (true? (:ok? result)))
      (let [obs (:observation result)]
        (is (inst? (:captured-at obs)))

        (testing "readiness map"
          (is (= {:query true :graph true :introspection true :memory true}
                 (:readiness obs))))

        (testing "graph signals extracted"
          (is (contains? (:graph-signals obs) "node-count=12"))
          (is (contains? (:graph-signals obs) "capability-count=5"))
          (is (contains? (:graph-signals obs) "status=stable")))

        (testing "memory signals extracted"
          (is (contains? (:memory-signals obs) "entry-count=3"))
          (is (contains? (:memory-signals obs) "status=ready"))
          (is (contains? (:memory-signals obs) "recovery-count=1")))

        (testing "opportunities include system ready"
          (is (some #(= "system ready for evolution" %) (:opportunities obs))))

        (testing "opportunities include stable graph"
          (is (some #(= "stable graph available" %) (:opportunities obs))))

        (testing "opportunities include memory available"
          (is (some #(= "memory entries available for learning" %) (:opportunities obs))))

        (testing "no gaps when all ready with good counts"
          (is (empty? (:gaps obs)))))))

  (testing "observation detects gaps"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          result   (core/observe-in! ctx cycle-id all-ready
                                     {:capability-count 1 :status :initializing}
                                     {:entry-count 0 :status :ready})]
      (is (true? (:ok? result)))
      (let [obs (:observation result)]
        (is (some #(= "low capability count" %) (:gaps obs)))
        (is (some #(= "no memory entries" %) (:gaps obs))))))

  (testing "observation transitions cycle and controller to planning"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          state    (core/get-state-in ctx)
          cycle    (first (filter #(= cycle-id (:cycle-id %)) (:cycles state)))]
      (is (= :planning (:status state)) "controller should be planning")
      (is (= :planning (:status cycle)) "cycle should be planning")
      (is (some? (:observation cycle)) "observation should be attached")))

  (testing "observe rejects wrong cycle status"
    (let [ctx      (core/create-context)
          cycle-id (trigger-and-get-cycle-id ctx)
          _        (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)
          result   (core/observe-in! ctx cycle-id all-ready sample-graph-state sample-memory-state)]
      (is (false? (:ok? result)))
      (is (= :wrong-cycle-status (:error result)))))

  (testing "observe rejects unknown cycle-id"
    (let [ctx    (core/create-context)
          result (core/observe-in! ctx "nonexistent" all-ready sample-graph-state sample-memory-state)]
      (is (false? (:ok? result)))
      (is (= :cycle-not-found (:error result))))))

(deftest synthesize-future-state-test
  (testing "version increments from nil (→1)"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{"node-count=10"}
               :memory-signals #{"entry-count=5"}
               :gaps []
               :opportunities ["system ready for evolution"]}
          fs (future-state/synthesize-future-state nil obs)]
      (is (= 1 (:version fs)))
      (is (inst? (:generated-at fs)))
      (is (true? (future-state/valid? fs)))))

  (testing "version increments from existing (→N+1)"
    (let [existing (future-state/initial-future-state)
          obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps []
               :opportunities []}
          fs (future-state/synthesize-future-state existing obs)]
      (is (= 1 (:version fs)) "0→1")))

  (testing "goals generated from gaps (high priority)"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps ["query not ready" "low capability count"]
               :opportunities []}
          fs (future-state/synthesize-future-state nil obs)]
      (is (= 2 (count (:goals fs))))
      (is (every? #(= :high (:priority %)) (:goals fs)))
      (is (every? #(= :proposed (:status %)) (:goals fs)))))

  (testing "goals generated from opportunities (medium priority)"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps []
               :opportunities ["system ready for evolution" "stable graph available"]}
          fs (future-state/synthesize-future-state nil obs)]
      (is (= 2 (count (:goals fs))))
      (is (every? #(= :medium (:priority %)) (:goals fs)))
      (is (every? #(= :proposed (:status %)) (:goals fs)))))

  (testing "mixed gaps and opportunities produce correctly prioritized goals"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps ["a gap"]
               :opportunities ["an opportunity"]}
          fs (future-state/synthesize-future-state nil obs)
          gap-goal (first (filter #(= :high (:priority %)) (:goals fs)))
          opp-goal (first (filter #(= :medium (:priority %)) (:goals fs)))]
      (is (= 2 (count (:goals fs))))
      (is (some? gap-goal))
      (is (some? opp-goal))))

  (testing "assumptions derived from readiness"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph false :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps []
               :opportunities []}
          fs (future-state/synthesize-future-state nil obs)]
      (is (contains? (:assumptions fs) "graph=false"))
      (is (contains? (:assumptions fs) "query=true"))))

  (testing "deterministic goal IDs"
    (let [obs {:captured-at (java.time.Instant/now)
               :readiness {:query true :graph true :introspection true :memory true}
               :graph-signals #{}
               :memory-signals #{}
               :gaps ["same gap"]
               :opportunities []}
          fs1 (future-state/synthesize-future-state nil obs)
          fs2 (future-state/synthesize-future-state nil obs)]
      (is (= (map :id (:goals fs1))
             (map :id (:goals fs2)))))))
