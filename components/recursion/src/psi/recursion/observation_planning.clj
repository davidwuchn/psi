(ns psi.recursion.observation-planning
  (:require
   [psi.recursion.future-state :as future-state]))

(defn find-cycle
  [cycles cycle-id]
  (first (filter #(= cycle-id (:cycle-id %)) cycles)))

(defn update-cycle
  [cycles cycle-id f]
  (mapv (fn [c]
          (if (= cycle-id (:cycle-id c))
            (f c)
            c))
        cycles))

(defn extract-graph-signals
  [graph-state]
  (let [signals (transient #{})]
    (when-let [nc (:node-count graph-state)]
      (conj! signals (str "node-count=" nc)))
    (when-let [cc (:capability-count graph-state)]
      (conj! signals (str "capability-count=" cc)))
    (when-let [s (:status graph-state)]
      (conj! signals (str "status=" (name s))))
    (persistent! signals)))

(defn extract-memory-signals
  [memory-state]
  (let [signals (transient #{})]
    (when-let [ec (:entry-count memory-state)]
      (conj! signals (str "entry-count=" ec)))
    (when-let [s (:status memory-state)]
      (conj! signals (str "status=" (name s))))
    (when-let [rc (:recovery-count memory-state)]
      (conj! signals (str "recovery-count=" rc)))
    (persistent! signals)))

(defn extract-gaps
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

(defn extract-opportunities
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
  [get-state-in swap-state-in! ctx cycle-id system-state graph-state memory-state]
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

(def ^:private risk-order
  {:low 0, :medium 1, :high 2})

(defn aggregate-risk
  [actions]
  (if (empty? actions)
    :low
    (let [max-idx (apply max (map #(get risk-order (:risk %) 0) actions))]
      (first (keep (fn [[k v]] (when (= v max-idx) k)) risk-order)))))

(defn goal->action
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
  [get-state-in swap-state-in! ctx cycle-id]
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
            sorted-goals (sort-by (fn [g]
                                    (case (:priority g)
                                      :high 0
                                      :medium 1
                                      :low 2
                                      3))
                                  (:goals new-fs))
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
