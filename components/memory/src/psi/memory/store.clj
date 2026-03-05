(ns psi.memory.store
  "Memory backing-store protocol + registry helpers.

   Phase 1: define provider contract and runtime selection with an in-memory
   default provider for backwards compatibility."
  (:import
   (java.time Instant)))

(def ^:const +default-provider-id+
  "Built-in provider id used for current in-memory behavior."
  "in-memory")

(def ^:const +fallback-provider-id+
  "Built-in fallback provider id when selected provider is unavailable."
  "in-memory")

(defprotocol StoreProvider
  (provider-id [provider]
    "Stable provider id string.")
  (provider-capabilities [provider]
    "Capability map used for introspection and compatibility checks.")
  (open-provider! [provider opts]
    "Open provider resources and transition provider into ready/degraded state.")
  (^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]} close-provider! [provider]
    "Close provider resources and transition provider into closed/unavailable state.")
  (provider-status [provider]
    "Current provider status keyword.")
  (provider-health [provider]
    "Health map for diagnostics.")
  (^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]} provider-write! [provider entity-type payload]
    "Persist one memory artifact; entity-type is one of :memory-record,
     :graph-snapshot, :graph-delta, :recovery-run.")
  (^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]} provider-query! [provider query]
    "Query records for recovery filtering.")
  (^{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]} provider-load-state [provider]
    "Hydrate provider-backed memory state on startup."))

(defn- now []
  (Instant/now))

(defn ready-status?
  "True when provider status can serve reads."
  [status]
  (contains? #{:ready :degraded} status))

(defn- status->health
  [status]
  (case status
    :ready :healthy
    :degraded :degraded
    :healthy :healthy
    :unavailable :unavailable
    :closed :unavailable
    :opening :degraded
    :registering :degraded
    :closing :degraded
    :error :unavailable
    :unavailable))

(defrecord InMemoryProvider [id status-atom]
  StoreProvider
  (provider-id [_] id)
  (provider-capabilities [_]
    {:durability :ephemeral
     :supports-restart-recovery? false
     :supports-retention-compaction? true
     :supports-capability-history-query? true
     :query-mode :scan})
  (open-provider! [provider _opts]
    (reset! status-atom :ready)
    provider)
  (close-provider! [provider]
    (reset! status-atom :closed)
    provider)
  (provider-status [_]
    @status-atom)
  (provider-health [provider]
    {:status (status->health (provider-status provider))
     :checked-at (now)
     :details nil})
  (provider-write! [_provider _entity-type _payload]
    {:ok? true
     :idempotent-replay? false})
  (provider-query! [_provider _query]
    {:ok? true
     :results []})
  (provider-load-state [_provider]
    {:ok? true
     :records []
     :graph-snapshots []
     :graph-deltas []
     :index-stats nil}))

(defn create-in-memory-provider
  "Create default in-memory provider.

   Options:
   - :id
   - :initial-status"
  ([]
   (create-in-memory-provider {}))
  ([{:keys [id initial-status]
     :or {id +default-provider-id+
          initial-status :registering}}]
   (->InMemoryProvider id (atom initial-status))))

(defn- provider-entry
  [provider]
  ;; Keep protocol operation vars referenced in this namespace so clojure-lsp
  ;; doesn't flag contract methods as unused before phase-2 routing lands.
  (let [_protocol-method-vars [close-provider!
                               provider-write!
                               provider-query!
                               provider-load-state]]
    {:id (provider-id provider)
     :instance provider
     :status (provider-status provider)
     :capabilities (provider-capabilities provider)
     :health (provider-health provider)}))

(defn- refresh-provider-entry
  [entry]
  (if-let [provider (:instance entry)]
    (assoc entry
           :status (provider-status provider)
           :capabilities (provider-capabilities provider)
           :health (provider-health provider))
    entry))

(defn refresh-registry
  "Refresh all provider status/health in a registry map."
  [registry]
  (update registry :providers
          (fn [providers]
            (into {}
                  (map (fn [[provider-id entry]]
                         [provider-id (refresh-provider-entry entry)]))
                  (or providers {})))))

(defn bootstrap-registry
  "Create provider registry with built-in in-memory provider selected active.

   Options:
   - :default-provider-id
   - :fallback-provider-id
   - :in-memory-provider"
  ([]
   (bootstrap-registry {}))
  ([{:keys [default-provider-id fallback-provider-id in-memory-provider]
     :or   {default-provider-id +default-provider-id+
            fallback-provider-id +fallback-provider-id+}}]
   (let [provider (or in-memory-provider
                      (create-in-memory-provider {:id default-provider-id}))
         _        (open-provider! provider {:reason :bootstrap})
         pid      (provider-id provider)]
     {:providers {pid (provider-entry provider)}
      :default-provider-id default-provider-id
      :fallback-provider-id fallback-provider-id
      :active-provider-id pid
      :selection {:requested-provider-id nil
                  :selected-provider-id pid
                  :used-fallback false
                  :reason :bootstrap
                  :selected-at (now)}})))

(defn register-provider
  "Register a provider in registry. Existing provider-id is a no-op.

   By default provider is opened before registration."
  ([registry provider]
   (register-provider registry provider {}))
  ([registry provider {:keys [open?]
                       :or   {open? true}}]
   (let [provider-id (provider-id provider)]
     (if (contains? (or (:providers registry) {}) provider-id)
       (refresh-registry registry)
       (let [_ (when open?
                 (open-provider! provider {:reason :register}))]
         (-> registry
             (update :providers (fnil assoc {}) provider-id (provider-entry provider))
             refresh-registry))))))

(defn active-provider-entry
  "Return active provider entry (with runtime :instance) from registry."
  [registry]
  (let [registry* (refresh-registry registry)]
    (get-in registry* [:providers (:active-provider-id registry*)])))

(defn- active-provider-instance
  "Return active provider instance from registry, or nil."
  [registry]
  (:instance (active-provider-entry registry)))

(defn select-provider
  "Select active provider by id.

   When requested provider is unavailable and :auto-fallback? is true,
   fallback provider is selected.

   Returns updated registry map with :active-provider-id and :selection."
  ([registry requested-provider-id]
   (select-provider registry requested-provider-id {}))
  ([registry requested-provider-id {:keys [auto-fallback? selected-at]
                                    :or   {auto-fallback? true
                                           selected-at (now)}}]
   (let [registry*        (refresh-registry registry)
         requested?       (some? requested-provider-id)
         candidate-id     (or requested-provider-id (:default-provider-id registry*))
         candidate-entry  (get-in registry* [:providers candidate-id])
         candidate-ready? (and candidate-entry (ready-status? (:status candidate-entry)))
         fallback-id      (:fallback-provider-id registry*)
         fallback-entry   (get-in registry* [:providers fallback-id])
         fallback-ready?  (and fallback-entry (ready-status? (:status fallback-entry)))
         current-active   (:active-provider-id registry*)]
     (cond
       candidate-ready?
       (assoc registry*
              :active-provider-id candidate-id
              :selection {:requested-provider-id requested-provider-id
                          :selected-provider-id candidate-id
                          :used-fallback false
                          :reason nil
                          :selected-at selected-at})

       (and auto-fallback? fallback-ready?)
       (assoc registry*
              :active-provider-id fallback-id
              :selection {:requested-provider-id requested-provider-id
                          :selected-provider-id fallback-id
                          :used-fallback true
                          :reason (if requested?
                                    :requested-provider-unavailable
                                    :default-provider-unavailable)
                          :selected-at selected-at})

       :else
       (assoc registry*
              :selection {:requested-provider-id requested-provider-id
                          :selected-provider-id current-active
                          :used-fallback false
                          :reason (if requested?
                                    :requested-provider-unavailable
                                    :default-provider-unavailable)
                          :selected-at selected-at})))))

(defn registry-summary
  "Return EQL-friendly provider registry summary (strips runtime instances)."
  [registry]
  (let [registry*        (refresh-registry registry)
        providers        (->> (vals (:providers registry*))
                              (map #(dissoc % :instance))
                              (sort-by :id)
                              vec)
        active-id        (:active-provider-id registry*)
        active           (some (fn [provider]
                                 (when (= (:id provider) active-id)
                                   provider))
                               providers)
        active-instance  (active-provider-instance registry*)
        active-health    (or (some-> active-instance provider-health)
                             (:health active))]
    {:providers providers
     :active-provider-id active-id
     :default-provider-id (:default-provider-id registry*)
     :fallback-provider-id (:fallback-provider-id registry*)
     :selection (:selection registry*)
     :health active-health}))
