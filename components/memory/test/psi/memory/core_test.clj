(ns psi.memory.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.engine.core :as engine]
   [psi.history.git :as git]
   [psi.memory.core :as memory]
   [psi.memory.datalevin :as datalevin]
   [psi.memory.graph-history :as graph-history]
   [psi.memory.ranking :as ranking]
   [psi.memory.store :as store]
   [psi.query.core :as query]))

(defn- temp-dir-path
  []
  (-> (java.nio.file.Files/createTempDirectory
       "psi-memory-core-datalevin-"
       (make-array java.nio.file.attribute.FileAttribute 0))
      str))

(defn- delete-recursively!
  [root]
  (let [root-path (java.nio.file.Path/of root (make-array String 0))]
    (when (java.nio.file.Files/exists root-path (make-array java.nio.file.LinkOption 0))
      (let [paths (with-open [stream (java.nio.file.Files/walk root-path (make-array java.nio.file.FileVisitOption 0))]
                    (vec (iterator-seq (.iterator stream))))]
        (run! #(java.nio.file.Files/deleteIfExists %)
              (reverse paths))))))

(deftest create-context-initializes-required-state-keys
  (let [ctx   (memory/create-context)
        state (memory/get-state-in ctx)]
    (testing "status starts initializing"
      (is (= :initializing (:status state))))

    (testing "required state holders exist"
      (is (contains? state :sessions))
      (is (contains? state :records))
      (is (contains? state :graph-snapshots))
      (is (contains? state :graph-deltas))
      (is (contains? state :recoveries))
      (is (contains? state :index-stats)))

    (testing "retention defaults are scaffolded"
      (is (= graph-history/snapshot-retention-limit
             (get-in state [:retention :snapshots])))
      (is (= graph-history/delta-retention-limit
             (get-in state [:retention :deltas]))))

    (testing "ranking defaults are scaffolded"
      (is (= ranking/default-weights (:ranking-defaults state))))))

(deftest create-context-supports-overrides
  (let [ctx   (memory/create-context {:state-overrides {:status :ready}})
        state (memory/get-state-in ctx)]
    (is (= :ready (:status state)))))

(deftest create-context-supports-retention-overrides
  (let [ctx   (memory/create-context {:retention-overrides {:snapshots 7
                                                            :deltas 11}})
        state (memory/get-state-in ctx)]
    (is (= 7 (get-in state [:retention :snapshots])))
    (is (= 11 (get-in state [:retention :deltas])))))

(deftest swap-state-in-updates-isolated-context-only
  (let [ctx-a (memory/create-context)
        ctx-b (memory/create-context)]
    (memory/swap-state-in! ctx-a assoc :status :ready)
    (is (= :ready (:status (memory/get-state-in ctx-a))))
    (is (= :initializing (:status (memory/get-state-in ctx-b))))))

(deftest create-context-bootstraps-in-memory-store-registry
  (let [ctx     (memory/create-context)
        summary (memory/store-summary-in ctx)]
    (is (= "in-memory" (:active-provider-id summary)))
    (is (= "in-memory" (:default-provider-id summary)))
    (is (= "in-memory" (:fallback-provider-id summary)))
    (is (= #{"in-memory"}
           (set (map :id (:providers summary)))))))

(deftest register-and-select-store-provider-updates-active-provider
  (let [ctx         (memory/create-context)
        status-atom (atom :registering)
        provider    (reify store/StoreProvider
                      (provider-id [_] "test-store")
                      (provider-capabilities [_]
                        {:durability :persistent
                         :supports-restart-recovery? true
                         :supports-retention-compaction? true
                         :supports-capability-history-query? true
                         :query-mode :indexed})
                      (open-provider! [this _]
                        (reset! status-atom :ready)
                        this)
                      (close-provider! [this]
                        (reset! status-atom :closed)
                        this)
                      (provider-status [_] @status-atom)
                      (provider-health [_]
                        {:status (if (= :ready @status-atom) :healthy :unavailable)
                         :checked-at (java.time.Instant/now)
                         :details nil})
                      (provider-write! [_ _ _] {:ok? true})
                      (provider-query! [_ _] {:ok? true :results []})
                      (provider-load-state [_] {:ok? true}))
        _           (memory/register-store-provider-in! ctx provider)
        summary     (memory/select-store-provider-in! ctx "test-store")]
    (is (= "test-store" (:active-provider-id summary)))
    (is (= "test-store" (get-in summary [:selection :selected-provider-id])))
    (is (= #{"in-memory" "test-store"}
           (set (map :id (:providers summary)))))))

(deftest select-store-provider-falls-back-when-requested-provider-unavailable
  (let [ctx         (memory/create-context)
        status-atom (atom :unavailable)
        provider    (reify store/StoreProvider
                      (provider-id [_] "down-store")
                      (provider-capabilities [_]
                        {:durability :persistent
                         :supports-restart-recovery? true
                         :supports-retention-compaction? true
                         :supports-capability-history-query? true
                         :query-mode :indexed})
                      (open-provider! [this _] this)
                      (close-provider! [this] this)
                      (provider-status [_] @status-atom)
                      (provider-health [_]
                        {:status :unavailable
                         :checked-at (java.time.Instant/now)
                         :details "unavailable for test"})
                      (provider-write! [_ _ _] {:ok? false})
                      (provider-query! [_ _] {:ok? false :results []})
                      (provider-load-state [_] {:ok? false}))
        _           (memory/register-store-provider-in! ctx provider {:open? false})
        summary     (memory/select-store-provider-in! ctx "down-store")]
    (is (= "in-memory" (:active-provider-id summary)))
    (is (true? (get-in summary [:selection :used-fallback])))
    (is (= :requested-provider-unavailable
           (get-in summary [:selection :reason])))))

(deftest remember-failed-store-write-falls-back-to-in-memory
  (let [ctx         (memory/create-context)
        status-atom (atom :ready)
        provider    (reify store/StoreProvider
                      (provider-id [_] "failing-store")
                      (provider-capabilities [_]
                        {:durability :persistent
                         :supports-restart-recovery? true
                         :supports-retention-compaction? true
                         :supports-capability-history-query? true
                         :query-mode :indexed})
                      (open-provider! [this _] this)
                      (close-provider! [this] this)
                      (provider-status [_] @status-atom)
                      (provider-health [_]
                        {:status :healthy
                         :checked-at (java.time.Instant/now)
                         :details nil})
                      (provider-write! [_ _ _]
                        {:ok? false
                         :error :boom
                         :message "write failed"})
                      (provider-query! [_ _] {:ok? true :results []})
                      (provider-load-state [_] {:ok? true}))]
    (memory/register-store-provider-in! ctx provider)
    (memory/select-store-provider-in! ctx "failing-store")
    (let [result  (memory/remember-in! ctx {:content-type :note
                                            :content "still in memory"
                                            :tags [:fallback]
                                            :provenance {:source :session}})
          summary (memory/store-summary-in ctx)
          failing-provider (some #(when (= "failing-store" (:id %)) %)
                                 (:providers summary))]
      (is (true? (:ok? result)))
      (is (= "in-memory" (:active-provider-id summary)))
      (is (true? (get-in result [:store :fallback-selected?])))
      (is (= 1 (get-in failing-provider [:telemetry :write-count])))
      (is (= 1 (get-in failing-provider [:telemetry :failure-count])))
      (is (= :boom
             (get-in failing-provider [:telemetry :last-error :error])))
      (is (= "failing-store"
             (get-in summary [:last-failure :provider-id]))))))

(deftest activation-hydrates-state-from-datalevin-provider
  (let [db-dir   (temp-dir-path)
        ts       (java.time.Instant/parse "2026-03-03T12:00:00Z")]
    (try
      (let [writer-ctx (memory/create-context)
            provider-a (datalevin/create-provider {:db-dir db-dir})]
        (memory/register-store-provider-in! writer-ctx provider-a)
        (memory/select-store-provider-in! writer-ctx "datalevin")
        (memory/remember-in! writer-ctx
                             {:content-type :note
                              :content "durable memory"
                              :tags [:persist]
                              :timestamp ts
                              :provenance {:source :session}})
        (is (= 1 (count (:records (memory/get-state-in writer-ctx)))))
        (store/close-provider! provider-a))

      (let [reader-ctx (memory/create-context)
            provider-b (datalevin/create-provider {:db-dir db-dir})
            query-ctx  (doto (query/create-query-context)
                         (query/rebuild-env-in!))
            git-ctx    (git/create-null-context)
            _          (memory/register-store-provider-in! reader-ctx provider-b)
            _          (memory/select-store-provider-in! reader-ctx "datalevin")
            activation (memory/activate-in! reader-ctx
                                            {:query-ctx query-ctx
                                             :git-ctx git-ctx
                                             :capability-graph-status :stable})
            hydrated-records (:records (memory/get-state-in reader-ctx))
            summary (memory/store-summary-in reader-ctx)
            datalevin-provider (some #(when (= "datalevin" (:id %)) %)
                                     (:providers summary))]
        (is (true? (:ready? activation)))
        (is (true? (get-in activation [:store-hydration :hydrated?])))
        (is (= 1 (count hydrated-records)))
        (is (= "durable memory" (:content (first hydrated-records))))
        (is (= 1 (get-in datalevin-provider [:telemetry :read-count])))
        (store/close-provider! provider-b))
      (finally
        (delete-recursively! db-dir)))))

(deftest activation-success-sets-ready-status-and-readiness-flags
  (let [memory-ctx (memory/create-context)
        engine-ctx (engine/create-context)
        _          (engine/initialize-system-state-in! engine-ctx)
        query-ctx  (doto (query/create-query-context)
                     (query/rebuild-env-in!))
        git-ctx    (git/create-null-context)
        result     (memory/activate-in! memory-ctx
                                        {:engine-ctx engine-ctx
                                         :query-ctx query-ctx
                                         :git-ctx git-ctx
                                         :capability-graph-status :stable})
        state      (memory/get-state-in memory-ctx)
        sys        (engine/get-system-state-in engine-ctx)]
    (is (true? (:ready? result)))
    (is (= :ready (:status state)))
    (is (true? (:history-ready sys)))
    (is (true? (:knowledge-ready sys)))
    (is (true? (:memory-ready sys)))))

(deftest activation-failure-missing-query-env-enters-error-and-clears-memory-ready
  (let [memory-ctx (memory/create-context)
        engine-ctx (engine/create-context)
        _          (engine/initialize-system-state-in! engine-ctx)
        git-ctx    (git/create-null-context)
        result     (memory/activate-in! memory-ctx
                                        {:engine-ctx engine-ctx
                                         :query-ctx (query/create-query-context)
                                         :git-ctx git-ctx
                                         :capability-graph-status :stable})
        state      (memory/get-state-in memory-ctx)
        sys        (engine/get-system-state-in engine-ctx)]
    (is (false? (:ready? result)))
    (is (false? (:query-env-built? result)))
    (is (= :error (:status state)))
    (is (false? (:memory-ready sys)))
    (is (false? (:history-ready sys)))
    (is (false? (:knowledge-ready sys)))))

(deftest activation-failure-no-git-history-enters-error
  (let [memory-ctx (memory/create-context)
        engine-ctx (engine/create-context)
        _          (engine/initialize-system-state-in! engine-ctx)
        query-ctx  (doto (query/create-query-context)
                     (query/rebuild-env-in!))
        git-ctx    (git/create-null-context [])
        result     (memory/activate-in! memory-ctx
                                        {:engine-ctx engine-ctx
                                         :query-ctx query-ctx
                                         :git-ctx git-ctx
                                         :capability-graph-status :expanding})
        state      (memory/get-state-in memory-ctx)
        sys        (engine/get-system-state-in engine-ctx)]
    (is (false? (:ready? result)))
    (is (false? (:has-git-history? result)))
    (is (= :error (:status state)))
    (is (false? (:memory-ready sys)))))

(deftest remember-in-success-persists-record-and-index-stats
  (let [memory-ctx (memory/create-context)
        before     (memory/get-state-in memory-ctx)
        result     (memory/remember-in! memory-ctx
                                        {:content-type :note
                                         :content "Persist this"
                                         :tags [:step10 :memory]
                                         :provenance {:source :session}})
        after      (memory/get-state-in memory-ctx)
        record     (:record result)]
    (is (true? (:ok? result)))
    (is (= 0 (count (:records before))))
    (is (= 1 (count (:records after))))
    (is (= :note (:content-type record)))
    (is (= "Persist this" (:content record)))
    (is (= [:step10 :memory] (:tags record)))
    (is (= 1 (get-in after [:index-stats :entry-count])))
    (is (= 1 (get-in after [:index-stats :by-type :note])))
    (is (= 1 (get-in after [:index-stats :by-source :session])))
    (is (= 1 (get-in after [:index-stats :by-tag :step10])))
    (is (= 1 (get-in after [:index-stats :by-tag :memory])))))

(deftest remember-in-missing-provenance-is-rejected-and-side-effect-free
  (let [memory-ctx (memory/create-context {:require-provenance-on-write? true})
        before     (memory/get-state-in memory-ctx)
        result     (memory/remember-in! memory-ctx
                                        {:content-type :note
                                         :content "No provenance"
                                         :tags [:invalid]})
        after      (memory/get-state-in memory-ctx)]
    (is (false? (:ok? result)))
    (is (= :missing-provenance (:error result)))
    (is (= (:records before) (:records after)))
    (is (= (:index-stats before) (:index-stats after)))))

(deftest remember-in-augments-provenance-from-capability-graph
  (let [memory-ctx (memory/create-context)
        result     (memory/remember-in! memory-ctx
                                        {:content-type :learning
                                         :content "Linked to graph"
                                         :tags [:graph]
                                         :provenance {:source :session}
                                         :capability-graph {:fingerprint "fp-123"
                                                            :capability-ids [:cap/a :cap/b]}})
        record     (:record result)]
    (is (true? (:ok? result)))
    (is (= "fp-123" (get-in record [:provenance :graphFingerprint])))
    (is (= [:cap/a :cap/b] (get-in record [:provenance :capabilityIds])))))

(deftest remember-in-appends-capability-history-events
  (let [memory-ctx (memory/create-context)
        _          (memory/remember-in! memory-ctx
                                        {:content-type :learning
                                         :content "Linked to graph"
                                         :tags [:graph]
                                         :provenance {:source :session
                                                      :capabilityIds [:cap/a :cap/b]}
                                         :timestamp (java.time.Instant/parse "2026-02-25T00:00:00Z")})
        history    (:capability-history (memory/get-state-in memory-ctx))]
    (is (= 2 (count history)))
    (is (= #{:memory-linked} (set (map :event-type history))))
    (is (= #{:cap/a :cap/b} (set (map :capability-id history))))
    (is (every? #(= :session (:source %)) history))
    (is (every? string? (map :record-id history)))))

(deftest capture-graph-change-creates-snapshot-and-delta-on-fingerprint-change
  (let [memory-ctx (memory/create-context)
        first      (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "fp-1"
                                                     :nodes [1 2]
                                                     :edges [[:a :b]]
                                                     :capability-ids [:cap/a]
                                                     :operations [:op/read]})
        second     (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "fp-2"
                                                     :nodes [1 2 3]
                                                     :edges [[:a :b] [:b :c]]
                                                     :capability-ids [:cap/a :cap/b]
                                                     :operations [:op/read :op/write]})
        state      (memory/get-state-in memory-ctx)]
    (is (true? (:ok? first)))
    (is (true? (:changed? first)))
    (is (false? (:delta-added? first)))
    (is (true? (:ok? second)))
    (is (true? (:changed? second)))
    (is (true? (:delta-added? second)))
    (is (= 2 (count (:graph-snapshots state))))
    (is (= 1 (count (:graph-deltas state))))
    (is (= [:cap/b]
           (get-in state [:graph-deltas 0 :added-capability-ids])))))

(deftest capture-graph-change-appends-capability-history-events
  (let [memory-ctx (memory/create-context)
        _          (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "fp-1"
                                                     :capability-ids [:cap/a :cap/b]
                                                     :operations [:op/read]})
        _          (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "fp-2"
                                                     :capability-ids [:cap/b :cap/c]
                                                     :operations [:op/read :op/write]})
        history    (:capability-history (memory/get-state-in memory-ctx))]
    (is (= 4 (count history)))
    (is (= #{:graph-capability-baseline
             :graph-capability-added
             :graph-capability-removed}
           (set (map :event-type history))))
    (is (= #{:cap/a :cap/b :cap/c}
           (set (map :capability-id history))))
    (is (= 2 (count (filter #(= :graph-capability-baseline (:event-type %)) history))))
    (is (= [:cap/c]
           (->> history
                (filter #(= :graph-capability-added (:event-type %)))
                (map :capability-id)
                vec)))
    (is (= [:cap/a]
           (->> history
                (filter #(= :graph-capability-removed (:event-type %)))
                (map :capability-id)
                vec)))))

(deftest capture-graph-change-noop-when-fingerprint-unchanged
  (let [memory-ctx (memory/create-context)
        _          (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "same-fp"
                                                     :capability-ids [:cap/a]})
        result     (memory/capture-graph-change-in! memory-ctx
                                                    {:fingerprint "same-fp"
                                                     :capability-ids [:cap/a :cap/b]})
        state      (memory/get-state-in memory-ctx)]
    (is (true? (:ok? result)))
    (is (false? (:changed? result)))
    (is (= :unchanged-fingerprint (:reason result)))
    (is (= 1 (count (:graph-snapshots state))))
    (is (= 0 (count (:graph-deltas state))))))

(deftest capture-graph-change-enforces-fixed-window-retention
  (let [memory-ctx (memory/create-context)]
    (doseq [n (range 1205)]
      (memory/capture-graph-change-in! memory-ctx
                                       {:fingerprint (str "fp-" n)
                                        :capability-ids [(keyword (str "cap/" n))]
                                        :operations [(keyword (str "op/" n))]}))
    (let [state (:state-atom memory-ctx)
          snapshots (:graph-snapshots @state)
          deltas (:graph-deltas @state)]
      (is (= graph-history/snapshot-retention-limit (count snapshots)))
      (is (= graph-history/delta-retention-limit (count deltas)))
      (is (= "fp-1005" (:fingerprint (first snapshots))))
      (is (= "fp-1204" (:fingerprint (last snapshots)))))))

(deftest capture-graph-change-respects-runtime-retention-overrides
  (let [memory-ctx (memory/create-context {:retention-overrides {:snapshots 3
                                                                 :deltas 4}})]
    (doseq [n (range 10)]
      (memory/capture-graph-change-in! memory-ctx
                                       {:fingerprint (str "fp-" n)
                                        :capability-ids [(keyword (str "cap/" n))]
                                        :operations [(keyword (str "op/" n))]}))
    (let [state (memory/get-state-in memory-ctx)]
      (is (= 3 (count (:graph-snapshots state))))
      (is (= 4 (count (:graph-deltas state))))
      (is (= "fp-7" (get-in state [:graph-snapshots 0 :fingerprint])))
      (is (= "fp-9" (get-in state [:graph-snapshots 2 :fingerprint]))))))

(deftest recover-composes-required-sources-and-truncates-results
  (let [memory-ctx (memory/create-context)
        _         (memory/remember-in! memory-ctx
                                       {:content-type :note
                                        :content "session note"
                                        :tags [:recover]
                                        :provenance {:source :session
                                                     :capabilityIds [:cap/a]}
                                        :timestamp (java.time.Instant/parse "2026-02-25T00:00:00Z")})
        _         (memory/remember-in! memory-ctx
                                       {:content-type :commit
                                        :content "history note"
                                        :tags [:recover]
                                        :provenance {:source :history
                                                     :capabilityIds [:cap/b]}
                                        :timestamp (java.time.Instant/parse "2026-02-26T00:00:00Z")})
        _         (memory/capture-graph-change-in! memory-ctx
                                                   {:fingerprint "fp-recover"
                                                    :capability-ids [:cap/a :cap/c]
                                                    :operations [:op/read]}
                                                   (java.time.Instant/parse "2026-02-27T00:00:00Z"))
        result    (memory/recover-in! memory-ctx
                                      {:sources [:session :history :graph]
                                       :limit 1
                                       :now (java.time.Instant/parse "2026-02-28T00:00:00Z")})
        state     (memory/get-state-in memory-ctx)]
    (is (true? (:ok? result)))
    (is (= [:session :history :graph] (:sources result)))
    (is (= 1 (:result-count result)))
    (is (true? (:resultsTruncated result)))
    (is (= 1 (count (:search-results state))))
    (is (= 1 (count (:recoveries state))))))

(deftest recover-applies-filters-dedup-and-default-limit
  (let [memory-ctx (memory/create-context)
        ts        (java.time.Instant/parse "2026-02-28T00:00:00Z")
        _         (memory/remember-in! memory-ctx
                                       {:content-type :note
                                        :content "alpha"
                                        :tags [:t1]
                                        :provenance {:source :session
                                                     :capabilityIds [:cap/a]}
                                        :timestamp ts})
        _         (memory/remember-in! memory-ctx
                                       {:content-type :note
                                        :content "alpha"
                                        :tags [:t1]
                                        :provenance {:source :session
                                                     :capabilityIds [:cap/a]}
                                        :timestamp ts})
        _         (memory/remember-in! memory-ctx
                                       {:content-type :task
                                        :content "beta"
                                        :tags [:t2]
                                        :provenance {:source :history
                                                     :capabilityIds [:cap/b]}
                                        :timestamp ts})
        result    (memory/recover-in! memory-ctx
                                      {:sources [:session :history :graph]
                                       :tags [:t1]
                                       :content-types [:note]
                                       :capability-ids [:cap/a]
                                       :since (java.time.Instant/parse "2026-02-27T00:00:00Z")
                                       :query-text "alpha"
                                       :now (java.time.Instant/parse "2026-03-01T00:00:00Z")})]
    (is (true? (:ok? result)))
    (is (= 2 (:result-count result)))
    (is (false? (:resultsTruncated result)))
    (is (every? #(= :note (:content-type %)) (:results result)))
    (is (every? #(some #{:t1} (:tags %)) (:results result)))))

(deftest recover-appends-capability-history-hit-events
  (let [memory-ctx (memory/create-context)
        _          (memory/remember-in! memory-ctx
                                        {:content-type :note
                                         :content "alpha session"
                                         :tags [:recover]
                                         :provenance {:source :session
                                                      :capabilityIds [:cap/a :cap/b]}
                                         :timestamp (java.time.Instant/parse "2026-02-25T00:00:00Z")})
        _          (memory/remember-in! memory-ctx
                                        {:content-type :note
                                         :content "alpha history"
                                         :tags [:recover]
                                         :provenance {:source :history
                                                      :capabilityIds [:cap/b]}
                                         :timestamp (java.time.Instant/parse "2026-02-26T00:00:00Z")})
        before-count (count (:capability-history (memory/get-state-in memory-ctx)))
        result      (memory/recover-in! memory-ctx
                                        {:sources [:session :history :graph]
                                         :query-text "alpha"
                                         :capability-ids [:cap/b]
                                         :now (java.time.Instant/parse "2026-03-01T00:00:00Z")})
        state       (memory/get-state-in memory-ctx)
        history     (:capability-history state)
        recovery-id (get-in result [:recovery :recovery-id])
        recovery-events (filter #(= :recovery-hit (:event-type %)) history)]
    (is (true? (:ok? result)))
    (is (= 2 (:result-count result)))
    (is (= (+ before-count 2) (count history)))
    (is (= 2 (count recovery-events)))
    (is (= #{:cap/b} (set (map :capability-id recovery-events))))
    (is (= #{recovery-id} (set (map :recovery-id recovery-events))))
    (is (= #{:session :history} (set (map :source recovery-events))))
    (is (= #{"alpha"} (set (map :query-text recovery-events))))))

(deftest recover-rejects-missing-required-sources
  (let [memory-ctx (memory/create-context)
        result    (memory/recover-in! memory-ctx {:sources [:session :history]})]
    (is (false? (:ok? result)))
    (is (= :required-sources-missing (:error result)))))

(deftest recover-rejects-invalid-ranking-weights
  (let [memory-ctx (memory/create-context)
        result    (memory/recover-in! memory-ctx {:ranking-weights {:text-relevance 50
                                                                    :recency 25
                                                                    :capability-proximity 20}})]
    (is (false? (:ok? result)))
    (is (= :invalid-ranking-weights (:error result)))))
