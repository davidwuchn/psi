(ns psi.memory.resolvers
  "Pathom resolvers exposing scaffolded memory state via EQL."
  (:require
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.memory.store :as store]))

(defn- by-tag-index
  [records]
  (reduce (fn [acc {:keys [tags] :as record}]
            (reduce (fn [acc' tag]
                      (update acc' tag (fnil conj []) record))
                    acc
                    tags))
          {}
          records))

(pco/defresolver memory-context-state
  "Resolve :psi.memory/state from :psi/memory-ctx."
  [input]
  {::pco/input  [:psi/memory-ctx]
   ::pco/output [:psi.memory/state]}
  (let [memory-ctx (:psi/memory-ctx input)]
    {:psi.memory/state @(:state-atom memory-ctx)}))

(defn- store-summary-for-ctx
  [memory-ctx]
  (if-let [registry-atom (:store-registry-atom memory-ctx)]
    (store/registry-summary @registry-atom)
    (store/registry-summary (store/bootstrap-registry))))

(pco/defresolver memory-store-state
  "Resolve memory backing-store registry attrs from :psi/memory-ctx."
  [input]
  {::pco/input  [:psi/memory-ctx]
   ::pco/output [:psi.memory.store/providers
                 :psi.memory.store/active-provider-id
                 :psi.memory.store/default-provider-id
                 :psi.memory.store/fallback-provider-id
                 :psi.memory.store/selection
                 :psi.memory.store/health
                 :psi.memory.store/active-provider-telemetry
                 :psi.memory.store/last-failure]}
  (let [summary (store-summary-for-ctx (:psi/memory-ctx input))]
    {:psi.memory.store/providers                 (:providers summary)
     :psi.memory.store/active-provider-id        (:active-provider-id summary)
     :psi.memory.store/default-provider-id       (:default-provider-id summary)
     :psi.memory.store/fallback-provider-id      (:fallback-provider-id summary)
     :psi.memory.store/selection                 (:selection summary)
     :psi.memory.store/health                    (:health summary)
     :psi.memory.store/active-provider-telemetry (:active-provider-telemetry summary)
     :psi.memory.store/last-failure              (:last-failure summary)}))

(pco/defresolver memory-state
  "Resolve scaffolded Step 10 memory attrs from :psi.memory/state."
  [input]
  {::pco/input  [:psi.memory/state]
   ::pco/output [:psi.memory/status
                 :psi.memory/entry-count
                 :psi.memory/entries
                 :psi.memory/search-results
                 :psi.memory/recovery-count
                 :psi.memory/recoveries
                 :psi.memory/graph-snapshots
                 :psi.memory/graph-deltas
                 :psi.memory/by-tag
                 :psi.memory/capability-history
                 :psi.memory/index-stats]}
  (let [{:keys [status
                records
                recoveries
                graph-snapshots
                graph-deltas
                index-stats
                search-results
                capability-history]} (:psi.memory/state input)]
    {:psi.memory/status             status
     :psi.memory/entry-count        (count records)
     :psi.memory/entries            records
     :psi.memory/search-results     (or search-results [])
     :psi.memory/recovery-count     (count recoveries)
     :psi.memory/recoveries         recoveries
     :psi.memory/graph-snapshots    graph-snapshots
     :psi.memory/graph-deltas       graph-deltas
     :psi.memory/by-tag             (by-tag-index records)
     :psi.memory/capability-history (or capability-history [])
     :psi.memory/index-stats        index-stats}))

(defn- source->record
  [record]
  (or (:source (:provenance record))
      (:source-type (:provenance record))
      :session))

(defn- remember-message?
  [record]
  (= :remember (source->record record)))

(defn- sort-key
  [record]
  (or (:timestamp record)
      java.time.Instant/EPOCH))

(defn- remember-captures
  [records]
  (->> records
       (filter remember-message?)
       (sort-by sort-key #(compare %2 %1))
       vec))

(pco/defresolver memory-remember-telemetry
  "Resolve remember-capture telemetry attrs from :psi.memory/state.

   Attrs are stable and intentionally scoped to manual remember capture UX:
   - :psi.memory.remember/status
   - :psi.memory.remember/captures
   - :psi.memory.remember/last-capture-at
   - :psi.memory.remember/last-error"
  [input]
  {::pco/input  [:psi.memory/state]
   ::pco/output [:psi.memory.remember/status
                 :psi.memory.remember/captures
                 :psi.memory.remember/last-capture-at
                 :psi.memory.remember/last-error]}
  (let [state        (:psi.memory/state input)
        records      (:records state)
        captures     (remember-captures records)
        last-capture (some-> captures first :timestamp)]
    {:psi.memory.remember/status          (if (some? (:last-error state)) :error :idle)
     :psi.memory.remember/captures        captures
     :psi.memory.remember/last-capture-at last-capture
     :psi.memory.remember/last-error      (:last-error state)}))

(pco/defresolver memory-recent-entries
  "Resolve recent memory entries, prioritizing remember message retrieval.

   This attr returns recent entries where record provenance source is :remember,
   sorted by timestamp descending.

   Params are intentionally ignored for now because the current root EQL setup
   does not route parameterized joins for this attr in a stable way."
  [input]
  {::pco/input  [:psi.memory/state]
   ::pco/output [:psi.memory/recent-entries]}
  (let [records (:records (:psi.memory/state input))
        selected (remember-captures records)]
    {:psi.memory/recent-entries selected}))

(def all-resolvers
  [memory-context-state
   memory-store-state
   memory-state
   memory-remember-telemetry
   memory-recent-entries])
