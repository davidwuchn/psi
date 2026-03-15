(ns psi.memory.store-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.store :as store]))

(defrecord TestProvider [id status-atom]
  store/StoreProvider
  (provider-id [_] id)
  (provider-capabilities [_]
    {:durability :persistent
     :supports-restart-recovery? true
     :supports-retention-compaction? true
     :supports-capability-history-query? true
     :query-mode :indexed})
  (open-provider! [provider _opts]
    (reset! status-atom :ready)
    provider)
  (close-provider! [provider]
    (reset! status-atom :closed)
    provider)
  (provider-status [_]
    @status-atom)
  (provider-health [provider]
    {:status (if (= :ready (store/provider-status provider))
               :healthy
               :unavailable)
     :checked-at (java.time.Instant/now)
     :details nil})
  (provider-write! [_ _ _]
    {:ok? true})
  (provider-query! [_ _]
    {:ok? true :results []})
  (provider-load-state [_]
    {:ok? true}))

(defn- test-provider
  ([id]
   (test-provider id :registering))
  ([id status]
   (->TestProvider id (atom status))))

(deftest bootstrap-registry-defaults-to-ready-in-memory-provider
  (let [registry (store/bootstrap-registry)
        summary  (store/registry-summary registry)
        provider (first (:providers summary))]
    (is (= store/+default-provider-id+ (:active-provider-id summary)))
    (is (= store/+default-provider-id+ (:default-provider-id summary)))
    (is (= store/+fallback-provider-id+ (:fallback-provider-id summary)))
    (is (= store/+default-provider-id+ (:id provider)))
    (is (= :ready (:status provider)))
    (is (= :healthy (get-in summary [:health :status])))
    (is (= 0 (get-in provider [:telemetry :write-count])))
    (is (= 0 (get-in provider [:telemetry :read-count])))
    (is (= 0 (get-in provider [:telemetry :failure-count])))
    (is (= (:telemetry provider)
           (:active-provider-telemetry summary)))))

(deftest register-provider-adds-provider-and-select-provider-makes-it-active
  (let [registry   (store/bootstrap-registry)
        provider   (test-provider "persistent-store")
        registered (store/register-provider registry provider)
        selected   (store/select-provider registered "persistent-store")
        summary    (store/registry-summary selected)]
    (testing "provider registration"
      (is (= #{"in-memory" "persistent-store"}
             (set (map :id (:providers summary))))))
    (testing "selection"
      (is (= "persistent-store" (:active-provider-id summary)))
      (is (= "persistent-store" (get-in summary [:selection :selected-provider-id])))
      (is (false? (true? (get-in summary [:selection :used-fallback])))))))

(deftest record-provider-operation-updates-telemetry-and-last-failure
  (let [registry (-> (store/bootstrap-registry)
                     (store/register-provider (test-provider "persistent-store")))
        updated  (-> registry
                     (store/record-provider-operation "persistent-store" :write {:ok? true})
                     (store/record-provider-operation "persistent-store" :load-state {:ok? false
                                                                                      :error :provider-load-state-failed
                                                                                      :message "boom"}))
        summary  (store/registry-summary updated)
        provider (some #(when (= "persistent-store" (:id %)) %)
                       (:providers summary))]
    (is (= 1 (get-in provider [:telemetry :write-count])))
    (is (= 1 (get-in provider [:telemetry :read-count])))
    (is (= 1 (get-in provider [:telemetry :failure-count])))
    (is (= :provider-load-state-failed
           (get-in provider [:telemetry :last-error :error])))
    (is (= :load-state
           (get-in summary [:last-failure :operation])))))

(deftest register-provider-records-open-failure-telemetry
  (let [status-atom (atom :registering)
        provider    (reify store/StoreProvider
                      (provider-id [_] "broken-store")
                      (provider-capabilities [_]
                        {:durability :persistent
                         :supports-restart-recovery? true
                         :supports-retention-compaction? true
                         :supports-capability-history-query? true
                         :query-mode :indexed})
                      (open-provider! [this _opts]
                        (reset! status-atom :error)
                        this)
                      (close-provider! [this]
                        (reset! status-atom :closed)
                        this)
                      (provider-status [_]
                        @status-atom)
                      (provider-health [_]
                        {:status :unavailable
                         :checked-at (java.time.Instant/now)
                         :details "cannot open"})
                      (provider-write! [_ _ _] {:ok? true})
                      (provider-query! [_ _] {:ok? true :results []})
                      (provider-load-state [_] {:ok? true}))
        summary     (-> (store/bootstrap-registry)
                        (store/register-provider provider)
                        store/registry-summary)
        broken      (some #(when (= "broken-store" (:id %)) %)
                          (:providers summary))]
    (is (= 1 (get-in broken [:telemetry :failure-count])))
    (is (= :provider-open-unavailable
           (get-in broken [:telemetry :last-error :error])))
    (is (= "cannot open"
           (get-in summary [:last-failure :message])))))

(deftest select-provider-falls-back-to-in-memory-when-requested-provider-unavailable
  (let [registry   (-> (store/bootstrap-registry)
                       (store/register-provider (test-provider "persistent-store" :unavailable)
                                                {:open? false}))
        selected   (store/select-provider registry "persistent-store" {:auto-fallback? true})
        summary    (store/registry-summary selected)]
    (is (= "in-memory" (:active-provider-id summary)))
    (is (true? (get-in summary [:selection :used-fallback])))
    (is (= :requested-provider-unavailable
           (get-in summary [:selection :reason])))))

(deftest select-provider-keeps-current-active-when-fallback-disabled
  (let [registry   (-> (store/bootstrap-registry)
                       (store/register-provider (test-provider "persistent-store" :unavailable)
                                                {:open? false}))
        selected   (store/select-provider registry "persistent-store" {:auto-fallback? false})
        summary    (store/registry-summary selected)]
    (is (= "in-memory" (:active-provider-id summary)))
    (is (false? (true? (get-in summary [:selection :used-fallback]))))
    (is (= :requested-provider-unavailable
           (get-in summary [:selection :reason])))))
