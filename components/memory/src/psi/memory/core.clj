(ns psi.memory.core
  "Memory component scaffold.

   Establishes an isolated memory context (Nullable pattern), global wrappers,
   and query resolver registration hooks.

   This namespace intentionally provides only Step 10 scaffold behavior.
   Lifecycle, remember/recover logic, and graph history tracking are added in
   follow-up tasks."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [psi.engine.core :as engine]
   [psi.history.git :as git]
   [psi.memory.graph-history :as graph-history]
   [psi.memory.ranking :as ranking]
   [psi.memory.resolvers :as resolvers]
   [psi.memory.store :as store]
   [psi.query.core :as query]))

(defrecord MemoryContext [state-atom config store-registry-atom])

(defn- graph-status-ready?
  "Step 10 gate: capability graph status is acceptable when stable or expanding."
  [graph-status]
  (contains? #{:stable :expanding} graph-status))

(defn- initial-index-stats
  []
  {:entry-count 0
   :by-type {}
   :by-tag {}
   :by-source {}})

(defn initial-state
  "Return initial in-memory scaffold state used by Step 10 memory context."
  []
  {:status :initializing
   :sessions []
   :records []
   :recoveries []
   :graph-snapshots []
   :graph-deltas []
   :search-results []
   :capability-history []
   :index-stats (initial-index-stats)
   :retention {:snapshots graph-history/snapshot-retention-limit
               :deltas graph-history/delta-retention-limit}
   :ranking-defaults ranking/default-weights})

(defn- normalize-retention-limit
  [value fallback]
  (cond
    (and (integer? value) (pos? value)) value
    (and (number? value) (pos? value)) (int value)
    :else fallback))

(defn create-context
  "Create an isolated MemoryContext.

   Options:
   - :state-overrides                map merged over initial memory state
   - :retention-overrides            {:snapshots int :deltas int}
   - :require-provenance-on-write?   feature flag for follow-up tasks
                                     (default true)
   - :store-registry-overrides       map merged over bootstrapped registry
   - :auto-store-fallback?           fallback to in-memory on provider selection failure
                                     (default true)"
  ([]
   (create-context {}))
  ([{:keys [state-overrides
            retention-overrides
            require-provenance-on-write?
            store-registry-overrides
            auto-store-fallback?]
     :or   {state-overrides {}
            retention-overrides {}
            require-provenance-on-write? true
            store-registry-overrides {}
            auto-store-fallback? true}}]
   (let [store-registry (merge (store/bootstrap-registry)
                               store-registry-overrides)
         base-state     (initial-state)
         retention      {:snapshots (normalize-retention-limit
                                     (:snapshots retention-overrides)
                                     (get-in base-state [:retention :snapshots]))
                         :deltas (normalize-retention-limit
                                  (:deltas retention-overrides)
                                  (get-in base-state [:retention :deltas]))}
         initial        (-> base-state
                            (assoc :retention retention)
                            (merge state-overrides))]
     (->MemoryContext
      (atom initial)
      {:require-provenance-on-write? require-provenance-on-write?
       :auto-store-fallback? auto-store-fallback?}
      (atom store-registry)))))

(defonce ^:private global-ctx (atom nil))

(defn- ensure-global-ctx!
  []
  (or @global-ctx
      (let [ctx (create-context)]
        (reset! global-ctx ctx)
        ctx)))

(defn global-context
  "Return the global memory context singleton, creating it when absent."
  []
  (ensure-global-ctx!))

(defn get-state-in
  "Return the full memory state map from `ctx`."
  [ctx]
  @(:state-atom ctx))

(defn swap-state-in!
  "Apply `f` to memory state atom in `ctx`."
  [ctx f & args]
  (apply swap! (:state-atom ctx) f args))

(defn get-state
  "Global wrapper for `get-state-in`."
  []
  (get-state-in (global-context)))

(defn swap-state!
  "Global wrapper for `swap-state-in!`."
  [f & args]
  (apply swap-state-in! (global-context) f args))

(defn get-store-registry-in
  "Return provider registry map for isolated memory `ctx`."
  [ctx]
  (store/refresh-registry @(:store-registry-atom ctx)))

(defn get-store-registry
  "Global wrapper for `get-store-registry-in`."
  []
  (get-store-registry-in (global-context)))

(defn store-summary-in
  "Return EQL-friendly provider registry summary for `ctx`."
  [ctx]
  (store/registry-summary (get-store-registry-in ctx)))

(defn store-summary
  "Global wrapper for `store-summary-in`."
  []
  (store-summary-in (global-context)))

(defn set-retention-in!
  "Set memory retention limits for graph snapshots/deltas in isolated `ctx`.

   `retention-overrides` accepts:
   - :snapshots positive integer
   - :deltas positive integer"
  [ctx {:keys [snapshots deltas]}]
  (swap-state-in! ctx
                  (fn [state]
                    (let [snapshot-default (get-in (initial-state) [:retention :snapshots])
                          delta-default    (get-in (initial-state) [:retention :deltas])
                          snapshot-limit   (normalize-retention-limit snapshots
                                                                      (get-in state [:retention :snapshots]
                                                                              snapshot-default))
                          delta-limit      (normalize-retention-limit deltas
                                                                      (get-in state [:retention :deltas]
                                                                              delta-default))]
                      (assoc state :retention {:snapshots snapshot-limit
                                               :deltas delta-limit}))))
  (:retention (get-state-in ctx)))

(defn set-retention!
  "Global wrapper for `set-retention-in!`."
  [retention-overrides]
  (set-retention-in! (global-context) retention-overrides))

(defn register-store-provider-in!
  "Register a backing store provider in isolated `ctx`.

   Provider must satisfy `psi.memory.store/StoreProvider`.
   Returns EQL-friendly registry summary after registration."
  ([ctx provider]
   (register-store-provider-in! ctx provider {}))
  ([ctx provider opts]
   (swap! (:store-registry-atom ctx)
          (fn [registry]
            (store/register-provider registry provider opts)))
   (store-summary-in ctx)))

(defn register-store-provider!
  "Global wrapper for `register-store-provider-in!`."
  ([provider]
   (register-store-provider! provider {}))
  ([provider opts]
   (register-store-provider-in! (global-context) provider opts)))

(defn select-store-provider-in!
  "Select active backing store provider for isolated `ctx`.

   Falls back to in-memory provider when
   `:auto-store-fallback?` is true in context config."
  ([ctx requested-provider-id]
   (select-store-provider-in! ctx requested-provider-id {}))
  ([ctx requested-provider-id opts]
   (let [auto-fallback? (get-in ctx [:config :auto-store-fallback?] true)]
     (swap! (:store-registry-atom ctx)
            (fn [registry]
              (store/select-provider registry
                                     requested-provider-id
                                     (merge {:auto-fallback? auto-fallback?}
                                            opts))))
     (store-summary-in ctx))))

(defn select-store-provider!
  "Global wrapper for `select-store-provider-in!`."
  ([requested-provider-id]
   (select-store-provider! requested-provider-id {}))
  ([requested-provider-id opts]
   (select-store-provider-in! (global-context) requested-provider-id opts)))

(defn- active-store-provider-in
  [ctx]
  (some-> (get-store-registry-in ctx)
          store/active-provider-entry
          :instance))

(defn- record-provider-operation-in!
  [ctx provider operation result]
  (when (and provider (:store-registry-atom ctx))
    (swap! (:store-registry-atom ctx)
           (fn [registry]
             (store/record-provider-operation registry
                                             (store/provider-id provider)
                                             operation
                                             result)))))

(defn- fallback-on-store-failure-in!
  [ctx]
  (let [{:keys [active-provider-id fallback-provider-id]} (store-summary-in ctx)
        auto-fallback? (get-in ctx [:config :auto-store-fallback?] true)]
    (when (and auto-fallback?
               (some? fallback-provider-id)
               (not= active-provider-id fallback-provider-id))
      (select-store-provider-in! ctx fallback-provider-id)
      true)))

(defn- persist-entity-in!
  [ctx entity-type payload]
  (if-let [provider (active-store-provider-in ctx)]
    (try
      (let [result (store/provider-write! provider entity-type payload)]
        (record-provider-operation-in! ctx
                                       provider
                                       :write
                                       (assoc result :entity-type entity-type))
        (if (:ok? result)
          {:ok? true
           :provider-id (store/provider-id provider)
           :result result}
          {:ok? false
           :provider-id (store/provider-id provider)
           :error (:error result)
           :message (:message result)
           :fallback-selected? (boolean (fallback-on-store-failure-in! ctx))}))
      (catch Exception e
        (let [result {:ok? false
                      :error :store-write-exception
                      :message (ex-message e)
                      :entity-type entity-type}]
          (record-provider-operation-in! ctx provider :write result)
          {:ok? false
           :provider-id (store/provider-id provider)
           :error :store-write-exception
           :message (ex-message e)
           :fallback-selected? (boolean (fallback-on-store-failure-in! ctx))})))
    {:ok? false
     :error :no-active-store-provider}))

(defn- persist-capability-history-events-in!
  [ctx events]
  (when (seq events)
    (mapv #(persist-entity-in! ctx :capability-history %) events)))

(defn- merge-unique-by
  [existing incoming id-fn]
  (let [seen (atom (set (map id-fn existing)))]
    (reduce (fn [acc item]
              (let [k (id-fn item)]
                (if (contains? @seen k)
                  acc
                  (do
                    (swap! seen conj k)
                    (conj acc item)))))
            (vec existing)
            (or incoming []))))

(defn- hydrate-state-from-provider-in!
  [ctx]
  (if-let [provider (active-store-provider-in ctx)]
    (let [caps (store/provider-capabilities provider)
          persistent? (= :persistent (:durability caps))]
      (if-not persistent?
        {:ok? true
         :hydrated? false
         :reason :ephemeral-provider}
        (try
          (let [loaded (store/provider-load-state provider)]
            (record-provider-operation-in! ctx provider :load-state loaded)
            (if-not (:ok? loaded)
              {:ok? false
               :hydrated? false
               :error (:error loaded)
               :message (:message loaded)}
              (do
                (swap-state-in! ctx
                                (fn [state]
                                  (let [merged-records (merge-unique-by (:records state)
                                                                        (:records loaded)
                                                                        (fn [record]
                                                                          (or (:record-id record)
                                                                              [(:content-type record)
                                                                               (:content record)
                                                                               (:timestamp record)])))
                                        merged-snapshots (merge-unique-by (:graph-snapshots state)
                                                                          (:graph-snapshots loaded)
                                                                          (fn [snapshot]
                                                                            (or (:snapshot-id snapshot)
                                                                                (:fingerprint snapshot))))
                                        merged-deltas (merge-unique-by (:graph-deltas state)
                                                                       (:graph-deltas loaded)
                                                                       (fn [delta]
                                                                         (or (:delta-id delta)
                                                                             [(:from-fingerprint delta)
                                                                              (:to-fingerprint delta)
                                                                              (:timestamp delta)])))
                                        merged-recoveries (merge-unique-by (:recoveries state)
                                                                           (:recoveries loaded)
                                                                           (fn [recovery]
                                                                             (or (:recovery-id recovery)
                                                                                 (:timestamp recovery))))
                                        merged-capability-history (merge-unique-by (:capability-history state)
                                                                                   (:capability-history loaded)
                                                                                   (fn [event]
                                                                                     (or (:event-id event)
                                                                                         [(:event-type event)
                                                                                          (:timestamp event)
                                                                                          (:record-id event)
                                                                                          (:capability-id event)])))]
                                    (-> state
                                        (assoc :records merged-records)
                                        (assoc :graph-snapshots merged-snapshots)
                                        (assoc :graph-deltas merged-deltas)
                                        (assoc :recoveries merged-recoveries)
                                        (assoc :capability-history merged-capability-history)
                                        (assoc :index-stats (or (:index-stats loaded)
                                                                (:index-stats state)))))))
                {:ok? true
                 :hydrated? true
                 :provider-id (store/provider-id provider)
                 :records-loaded (count (:records loaded))
                 :graph-snapshots-loaded (count (:graph-snapshots loaded))
                 :graph-deltas-loaded (count (:graph-deltas loaded))
                 :recoveries-loaded (count (:recoveries loaded))})))
          (catch Exception e
            (let [result {:ok? false
                          :error :store-load-exception
                          :message (ex-message e)}]
              (record-provider-operation-in! ctx provider :load-state result)
              {:ok? false
               :hydrated? false
               :error :store-load-exception
               :message (ex-message e)})))))
    {:ok? false
     :hydrated? false
     :error :no-active-store-provider}))

(defn activation-gates-in
  "Compute Step 10 activation gates.

   Required gates:
   - query env built
   - git repository has history
   - capability graph status in #{:stable :expanding}"
  [_ctx {:keys [query-ctx git-ctx capability-graph-status]
         :or   {git-ctx (git/create-context)}}]
  (let [query-summary   (query/graph-summary-in query-ctx)
        commits         (try
                          (git/log git-ctx {:n 1})
                          (catch Exception _
                            []))
        has-git-history (pos? (count commits))
        env-built?      (true? (:env-built? query-summary))
        graph-ready?    (graph-status-ready? capability-graph-status)]
    {:query-env-built? env-built?
     :has-git-history? has-git-history
     :graph-status capability-graph-status
     :graph-status-ready? graph-ready?
     :ready? (and env-built? has-git-history graph-ready?)}))

(defn activate-in!
  "Run Step 10 activation lifecycle for isolated `ctx`.

   On success:
   - memory status => :ready
   - engine readiness flags => history/knowledge/memory true

   On failure:
   - memory status => :error
   - memory-ready false
   - do not force unrelated readiness flags true"
  [ctx {:keys [engine-ctx query-ctx git-ctx capability-graph-status]
        :or   {capability-graph-status :stable}
        :as   opts}]
  (let [gates (activation-gates-in ctx {:query-ctx query-ctx
                                        :git-ctx git-ctx
                                        :capability-graph-status capability-graph-status})
        ready? (:ready? gates)
        hydration (when ready?
                    (hydrate-state-from-provider-in! ctx))]
    (swap-state-in! ctx assoc :status (if ready? :ready :error))
    (when engine-ctx
      (if ready?
        (do
          (engine/update-system-component-in! engine-ctx :history-ready true)
          (engine/update-system-component-in! engine-ctx :knowledge-ready true)
          (engine/update-system-component-in! engine-ctx :memory-ready true))
        (engine/update-system-component-in! engine-ctx :memory-ready false)))
    (assoc gates
           :memory-status (:status (get-state-in ctx))
           :store-hydration hydration
           :options (select-keys opts [:capability-graph-status]))))

(defn activate!
  "Global wrapper for `activate-in!`.

   Requires :query-ctx. Optionally accepts :engine-ctx, :git-ctx,
   and :capability-graph-status."
  [{:keys [query-ctx] :as opts}]
  (activate-in! (global-context) (assoc opts :query-ctx query-ctx)))

(defn- resolve-content-type
  [remember-input]
  (or (:content-type remember-input)
      (:contentType remember-input)))

(defn- resolve-timestamp
  [remember-input]
  (or (:timestamp remember-input)
      (java.time.Instant/now)))

(defn- normalize-tags
  [tags]
  (->> (or tags [])
       (remove nil?)
       (distinct)
       (vec)))

(defn- normalize-capability-ids
  [capability-ids]
  (->> (or capability-ids [])
       (keep (fn [capability-id]
               (cond
                 (keyword? capability-id) capability-id
                 (string? capability-id) capability-id
                 :else nil)))
       (distinct)
       (vec)))

(defn- append-capability-history
  [state events]
  (if (seq events)
    (update state :capability-history (fnil into []) events)
    state))

(defn- capability-history-events-for-record
  [record]
  (let [provenance        (:provenance record)
        capability-ids    (normalize-capability-ids (:capabilityIds provenance))
        source            (or (:source provenance)
                              (:source-type provenance)
                              :unknown)
        graph-fingerprint (:graphFingerprint provenance)]
    (mapv (fn [capability-id]
            {:event-id (str (random-uuid))
             :event-type :memory-linked
             :timestamp (:timestamp record)
             :capability-id capability-id
             :record-id (:record-id record)
             :content-type (:content-type record)
             :source source
             :graph-fingerprint graph-fingerprint})
          capability-ids)))

(defn- capability-history-events-for-baseline-snapshot
  [snapshot]
  (mapv (fn [capability-id]
          {:event-id (str (random-uuid))
           :event-type :graph-capability-baseline
           :timestamp (:timestamp snapshot)
           :capability-id capability-id
           :graph-fingerprint (:fingerprint snapshot)})
        (normalize-capability-ids (:capability-ids snapshot))))

(defn- capability-history-events-for-delta
  [delta]
  (let [timestamp        (:timestamp delta)
        from-fingerprint (:from-fingerprint delta)
        to-fingerprint   (:to-fingerprint delta)
        added-events     (mapv (fn [capability-id]
                                 {:event-id (str (random-uuid))
                                  :event-type :graph-capability-added
                                  :timestamp timestamp
                                  :capability-id capability-id
                                  :from-fingerprint from-fingerprint
                                  :to-fingerprint to-fingerprint})
                               (normalize-capability-ids (:added-capability-ids delta)))
        removed-events   (mapv (fn [capability-id]
                                 {:event-id (str (random-uuid))
                                  :event-type :graph-capability-removed
                                  :timestamp timestamp
                                  :capability-id capability-id
                                  :from-fingerprint from-fingerprint
                                  :to-fingerprint to-fingerprint})
                               (normalize-capability-ids (:removed-capability-ids delta)))]
    (into [] (concat added-events removed-events))))

(defn- capability-history-events-for-recovery
  [recovery]
  (let [timestamp      (:timestamp recovery)
        recovery-id    (:recovery-id recovery)
        query-text     (get-in recovery [:filters :query-text])
        requested-ids  (normalize-capability-ids (get-in recovery [:filters :capability-ids]))
        requested-set  (set requested-ids)]
    (into []
          (mapcat (fn [record]
                    (let [provenance        (:provenance record)
                          record-ids        (normalize-capability-ids (:capabilityIds provenance))
                          hit-capability-ids (if (seq requested-set)
                                               (filterv requested-set record-ids)
                                               record-ids)
                          source            (or (:source provenance)
                                                (:source-type provenance)
                                                :unknown)]
                      (mapv (fn [capability-id]
                              {:event-id (str (random-uuid))
                               :event-type :recovery-hit
                               :timestamp timestamp
                               :capability-id capability-id
                               :recovery-id recovery-id
                               :record-id (:record-id record)
                               :query-text query-text
                               :source source
                               :recovery-score (:recovery/score record)})
                            hit-capability-ids)))
                  (:results recovery)))))

(defn- enrich-provenance-with-graph
  [provenance capability-graph]
  (let [graph-fingerprint (or (:fingerprint capability-graph)
                              (:graph-fingerprint capability-graph)
                              (:graphFingerprint capability-graph))
        capability-ids    (or (:relevant-capability-ids capability-graph)
                              (:capability-ids capability-graph)
                              (:capabilityIds capability-graph)
                              (some->> (:capabilities capability-graph)
                                       (keep :id)
                                       vec))]
    (cond-> (or provenance {})
      graph-fingerprint (assoc :graphFingerprint graph-fingerprint)
      (seq capability-ids) (assoc :capabilityIds (vec capability-ids)))))

(defn- remember-validation-error
  [ctx remember-input]
  (let [content-type          (resolve-content-type remember-input)
        content               (:content remember-input)
        require-provenance?   (true? (get-in ctx [:config :require-provenance-on-write?]))
        has-provenance?       (some? (:provenance remember-input))]
    (cond
      (nil? content-type) :missing-content-type
      (nil? content) :missing-content
      (and require-provenance? (not has-provenance?)) :missing-provenance
      :else nil)))

(defn- update-index-stats
  [index-stats {:keys [content-type tags provenance]}]
  (let [source (or (:source provenance)
                   (:source-type provenance)
                   :unknown)]
    (-> index-stats
        (update :entry-count (fnil inc 0))
        (update-in [:by-type content-type] (fnil inc 0))
        (update-in [:by-source source] (fnil inc 0))
        ((fn [stats]
           (reduce (fn [acc tag]
                     (update-in acc [:by-tag tag] (fnil inc 0)))
                   stats
                   tags))))))

(defn remember-in!
  "Remember a record in isolated `ctx`.

   Required inputs:
   - :content-type (or :contentType)
   - :content
   - :tags
   - :provenance (required when :require-provenance-on-write? is true)

   Optional inputs:
   - :timestamp (defaults to now)
   - :capability-graph to enrich provenance with graph fingerprint and capability ids"
  [ctx {:keys [content tags provenance capability-graph] :as remember-input}]
  (if-let [error (remember-validation-error ctx remember-input)]
    {:ok? false
     :error error}
    (let [content-type               (resolve-content-type remember-input)
          normalized-tags            (normalize-tags tags)
          record-timestamp           (resolve-timestamp remember-input)
          full-provenance            (enrich-provenance-with-graph provenance capability-graph)
          memory-record              {:record-id (str (random-uuid))
                                      :content-type content-type
                                      :content content
                                      :tags normalized-tags
                                      :timestamp record-timestamp
                                      :provenance full-provenance}
          capability-history-events  (capability-history-events-for-record memory-record)]
      (swap-state-in! ctx
                      (fn [state]
                        (-> state
                            (update :records (fnil conj []) memory-record)
                            (update :index-stats update-index-stats memory-record)
                            (append-capability-history capability-history-events))))
      (let [store-write (persist-entity-in! ctx :memory-record memory-record)
            history-store-writes (persist-capability-history-events-in! ctx capability-history-events)]
        {:ok? true
         :record memory-record
         :entry-count (get-in (get-state-in ctx) [:index-stats :entry-count])
         :store store-write
         :capability-history-store history-store-writes}))))

(defn remember!
  "Global wrapper for `remember-in!`."
  [remember-input]
  (remember-in! (global-context) remember-input))

(defn- normalize-sources
  [sources]
  (let [normalized (->> (or sources [:session :history :graph])
                        (map keyword)
                        vec)]
    (if (seq normalized)
      normalized
      [:session :history :graph])))

(defn- source->record
  [record]
  (or (:source (:provenance record))
      (:source-type (:provenance record))
      :session))

(defn- normalize-query-text
  [query-text]
  (some-> query-text str str/lower-case str/trim))

(defn- text-relevance-score
  [query-text record]
  (let [q (normalize-query-text query-text)]
    (if (str/blank? q)
      0.0
      (let [haystack (str/lower-case (str (:content record) " " (or (:content-type record) "") " " (str/join " " (:tags record))))]
        (if (str/includes? haystack q) 1.0 0.0)))))

(defn- recency-score
  [now timestamp]
  (if (instance? java.time.Instant timestamp)
    (let [hours (Math/abs (double (.toHours (java.time.Duration/between timestamp now))))]
      (/ 1.0 (+ 1.0 hours)))
    0.0))

(defn- capability-proximity
  [requested-capability-ids record]
  (let [requested (set (map keyword (or requested-capability-ids [])))
        record-caps (set (map keyword (or (get-in record [:provenance :capabilityIds]) [])))]
    (if (or (empty? requested) (empty? record-caps))
      0.0
      (/ (double (count (set/intersection requested record-caps)))
         (double (count requested))))))

(defn- score-record
  [{:keys [query-text now capability-ids ranking-weights]} record]
  (let [{text-weight :text-relevance
         recency-weight :recency
         capability-weight :capability-proximity} ranking-weights
        tr (text-relevance-score query-text record)
        rs (recency-score now (:timestamp record))
        cp (capability-proximity capability-ids record)]
    (+ (* (/ text-weight 100.0) tr)
       (* (/ recency-weight 100.0) rs)
       (* (/ capability-weight 100.0) cp))))

(defn- dedupe-records
  [records]
  (vals
   (reduce (fn [acc record]
             (let [k (or (:record-id record)
                         [(:content-type record)
                          (:content record)
                          (:timestamp record)])]
               (if (contains? acc k)
                 acc
                 (assoc acc k record))))
           {}
           records)))

(defn- filter-record?
  [{:keys [tags capability-ids content-types since query-text]} record]
  (let [record-tags (set (:tags record))
        requested-tags (set (or tags []))
        record-caps (set (or (get-in record [:provenance :capabilityIds]) []))
        requested-caps (set (or capability-ids []))
        requested-types (set (or content-types []))
        timestamp (:timestamp record)
        text-q (normalize-query-text query-text)]
    (and
     (or (empty? requested-tags)
         (not-empty (set/intersection requested-tags record-tags)))
     (or (empty? requested-caps)
         (not-empty (set/intersection requested-caps record-caps)))
     (or (empty? requested-types)
         (contains? requested-types (:content-type record)))
     (or (nil? since)
         (and (instance? java.time.Instant timestamp)
              (not (neg? (compare timestamp since)))))
     (or (str/blank? text-q)
         (pos? (text-relevance-score text-q record))))))

(defn recover-in!
  "Recover memory records using required Step 10 source composition, filters, ranking and limit.

   Defaults:
   - required sources composed: :session, :history, :graph
   - limit: 50
   - ranking weights: 50/25/25 (must sum to 100)

   Returns map with :ok?, :results, :result-count, :resultsTruncated, :sources and :weights."
  [ctx {:keys [sources limit tags capability-ids content-types since query-text ranking-weights now]
        :or   {limit 50
               now (java.time.Instant/now)}}]
  (let [weights (or ranking-weights ranking/default-weights)]
    (if-not (ranking/weights-valid? weights)
      {:ok? false
       :error :invalid-ranking-weights
       :weights weights}
      (let [state             (get-state-in ctx)
            requested-sources (normalize-sources sources)]
        (if-not (= #{:session :history :graph} (set requested-sources))
          {:ok? false
           :error :required-sources-missing
           :sources requested-sources}
          (let [records           (:records state)
                session-records   (filter #(= :session (source->record %)) records)
                history-records   (filter #(contains? #{:history :git} (source->record %)) records)
                graph-records     (mapv (fn [snapshot]
                                          {:record-id (or (:snapshot-id snapshot)
                                                          (str (random-uuid)))
                                           :content-type :graph-snapshot
                                           :content (str "capability graph snapshot " (:fingerprint snapshot))
                                           :tags [:graph :snapshot]
                                           :timestamp (:timestamp snapshot)
                                           :provenance {:source :graph
                                                        :graphFingerprint (:fingerprint snapshot)
                                                        :capabilityIds (vec (or (:capability-ids snapshot) []))}
                                           :graph-snapshot snapshot})
                                        (:graph-snapshots state))
                records-by-source {:session session-records
                                   :history history-records
                                   :graph graph-records}
                merged            (->> requested-sources
                                       (mapcat #(get records-by-source % []))
                                       dedupe-records)
                filtered          (filter (partial filter-record? {:tags tags
                                                                   :capability-ids capability-ids
                                                                   :content-types content-types
                                                                   :since since
                                                                   :query-text query-text})
                                          merged)
                scored            (->> filtered
                                       (map (fn [record]
                                              (assoc record :recovery/score
                                                     (score-record {:query-text query-text
                                                                    :now now
                                                                    :capability-ids capability-ids
                                                                    :ranking-weights weights}
                                                                   record))))
                                       (sort-by :recovery/score >)
                                       vec)
                enforced-limit    (max 0 (or limit 50))
                total-count       (count scored)
                results           (vec (take enforced-limit scored))
                truncated?        (> total-count enforced-limit)
                recovery          {:recovery-id (str (random-uuid))
                                   :timestamp now
                                   :sources requested-sources
                                   :filters {:tags (vec (or tags []))
                                             :capability-ids (vec (or capability-ids []))
                                             :content-types (vec (or content-types []))
                                             :since since
                                             :query-text query-text}
                                   :weights weights
                                   :limit enforced-limit
                                   :result-count (count results)
                                   :resultsTruncated truncated?
                                   :results results}
                capability-history-events
                (capability-history-events-for-recovery recovery)]
            (swap-state-in! ctx
                            (fn [s]
                              (-> s
                                  (assoc :search-results results)
                                  (update :recoveries (fnil conj []) recovery)
                                  (append-capability-history capability-history-events))))
            (let [store-write (persist-entity-in! ctx :recovery-run recovery)
                  history-store-writes (persist-capability-history-events-in! ctx capability-history-events)]
              {:ok? true
               :sources requested-sources
               :weights weights
               :result-count (count results)
               :resultsTruncated truncated?
               :results results
               :recovery recovery
               :store store-write
               :capability-history-store history-store-writes})))))))

(defn recover!
  "Global wrapper for `recover-in!`."
  [recover-input]
  (recover-in! (global-context) recover-input))

(defn capture-graph-change-in!
  "Capture graph snapshot/delta into isolated `ctx` when fingerprint changes.

   Behavior:
   - no-op when fingerprint is missing
   - no-op when fingerprint matches latest snapshot
   - append snapshot on change
   - append delta when prior snapshot exists
   - enforce configured retention window (defaults: 200 snapshots, 1000 deltas)
   - does not produce summary entities"
  ([ctx capability-graph]
   (capture-graph-change-in! ctx capability-graph (java.time.Instant/now)))
  ([ctx capability-graph timestamp]
   (if-let [snapshot (graph-history/make-snapshot capability-graph timestamp)]
     (let [state           (get-state-in ctx)
           latest-snapshot (last (:graph-snapshots state))
           duplicate?      (= (:fingerprint latest-snapshot)
                              (:fingerprint snapshot))]
       (if duplicate?
         {:ok? true
          :changed? false
          :reason :unchanged-fingerprint
          :snapshot-added? false
          :delta-added? false
          :snapshot-count (count (:graph-snapshots state))
          :delta-count (count (:graph-deltas state))}
         (let [delta (when latest-snapshot
                       (graph-history/make-delta latest-snapshot snapshot timestamp))
               capability-history-events (if delta
                                           (capability-history-events-for-delta delta)
                                           (capability-history-events-for-baseline-snapshot snapshot))]
           (swap-state-in! ctx
                           (fn [s]
                             (let [snapshot-limit (normalize-retention-limit
                                                   (get-in s [:retention :snapshots])
                                                   graph-history/snapshot-retention-limit)
                                   delta-limit    (normalize-retention-limit
                                                   (get-in s [:retention :deltas])
                                                   graph-history/delta-retention-limit)
                                   with-snapshot  (update s :graph-snapshots
                                                          (fn [entries]
                                                            (graph-history/trim-window
                                                             (conj (vec (or entries [])) snapshot)
                                                             snapshot-limit)))
                                   with-delta     (if delta
                                                    (update with-snapshot :graph-deltas
                                                            (fn [entries]
                                                              (graph-history/trim-window
                                                               (conj (vec (or entries [])) delta)
                                                               delta-limit)))
                                                    with-snapshot)]
                               (append-capability-history with-delta capability-history-events))))
           (let [snapshot-store-write (persist-entity-in! ctx :graph-snapshot snapshot)
                 delta-store-write    (when delta
                                        (persist-entity-in! ctx :graph-delta delta))
                 history-store-writes (persist-capability-history-events-in! ctx capability-history-events)
                 updated              (get-state-in ctx)]
             {:ok? true
              :changed? true
              :snapshot-added? true
              :delta-added? (some? delta)
              :snapshot snapshot
              :delta delta
              :snapshot-store snapshot-store-write
              :delta-store delta-store-write
              :capability-history-store history-store-writes
              :snapshot-count (count (:graph-snapshots updated))
              :delta-count (count (:graph-deltas updated))}))))
     {:ok? false
      :changed? false
      :error :missing-fingerprint})))

(defn capture-graph-change!
  "Global wrapper for `capture-graph-change-in!`."
  ([capability-graph]
   (capture-graph-change-in! (global-context) capability-graph))
  ([capability-graph timestamp]
   (capture-graph-change-in! (global-context) capability-graph timestamp)))

(defn register-resolvers-in!
  "Register memory resolvers into isolated query context `qctx`.
   Rebuilds query env by default."
  ([qctx]
   (register-resolvers-in! qctx true))
  ([qctx rebuild?]
   (doseq [r resolvers/all-resolvers]
     (query/register-resolver-in! qctx r))
   (when rebuild?
     (query/rebuild-env-in! qctx))
   :ok))

(defn register-resolvers!
  "Register memory resolvers into global query context and rebuild env once."
  []
  (doseq [r resolvers/all-resolvers]
    (query/register-resolver! r))
  (query/rebuild-env!)
  :ok)
