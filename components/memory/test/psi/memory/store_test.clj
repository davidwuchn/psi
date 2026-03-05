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
    (is (= :healthy (get-in summary [:health :status])))))

(deftest register-provider-adds-provider-and-select-provider-makes-it-active
  (let [registry   (store/bootstrap-registry)
        provider   (test-provider "datalevin")
        registered (store/register-provider registry provider)
        selected   (store/select-provider registered "datalevin")
        summary    (store/registry-summary selected)]
    (testing "provider registration"
      (is (= #{"in-memory" "datalevin"}
             (set (map :id (:providers summary))))))
    (testing "selection"
      (is (= "datalevin" (:active-provider-id summary)))
      (is (= "datalevin" (get-in summary [:selection :selected-provider-id])))
      (is (false? (true? (get-in summary [:selection :used-fallback])))))))

(deftest select-provider-falls-back-to-in-memory-when-requested-provider-unavailable
  (let [registry   (-> (store/bootstrap-registry)
                       (store/register-provider (test-provider "datalevin" :unavailable)
                                                {:open? false}))
        selected   (store/select-provider registry "datalevin" {:auto-fallback? true})
        summary    (store/registry-summary selected)]
    (is (= "in-memory" (:active-provider-id summary)))
    (is (true? (get-in summary [:selection :used-fallback])))
    (is (= :requested-provider-unavailable
           (get-in summary [:selection :reason])))))

(deftest select-provider-keeps-current-active-when-fallback-disabled
  (let [registry   (-> (store/bootstrap-registry)
                       (store/register-provider (test-provider "datalevin" :unavailable)
                                                {:open? false}))
        selected   (store/select-provider registry "datalevin" {:auto-fallback? false})
        summary    (store/registry-summary selected)]
    (is (= "in-memory" (:active-provider-id summary)))
    (is (false? (true? (get-in summary [:selection :used-fallback]))))
    (is (= :requested-provider-unavailable
           (get-in summary [:selection :reason])))))
