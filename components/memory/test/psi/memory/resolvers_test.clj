(ns psi.memory.resolvers-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.core :as memory]
   [psi.memory.resolvers :as resolvers]
   [psi.query.core :as query])
  (:import
   (java.time Instant)))

(defn- memory-query-ctx
  []
  (let [qctx (query/create-query-context)]
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! qctx r))
    (query/rebuild-env-in! qctx)
    qctx))

(deftest memory-attrs-are-queryable-from-scaffold-context
  (let [memory-ctx (memory/create-context)
        qctx       (memory-query-ctx)
        result     (query/query-in qctx
                                   {:psi/memory-ctx memory-ctx}
                                   [:psi.memory/status
                                    :psi.memory/entry-count
                                    :psi.memory/entries
                                    :psi.memory/search-results
                                    :psi.memory/recent-entries
                                    :psi.memory/recovery-count
                                    :psi.memory/recoveries
                                    :psi.memory/graph-snapshots
                                    :psi.memory/graph-deltas
                                    :psi.memory/by-tag
                                    :psi.memory/capability-history
                                    :psi.memory/index-stats
                                    :psi.memory.store/providers
                                    :psi.memory.store/active-provider-id
                                    :psi.memory.store/default-provider-id
                                    :psi.memory.store/fallback-provider-id
                                    :psi.memory.store/selection
                                    :psi.memory.store/health
                                    :psi.memory.store/active-provider-telemetry
                                    :psi.memory.store/last-failure
                                    :psi.memory.remember/status
                                    :psi.memory.remember/captures
                                    :psi.memory.remember/last-capture-at
                                    :psi.memory.remember/last-error])]
    (testing "status and counters are present"
      (is (= :initializing (:psi.memory/status result)))
      (is (= 0 (:psi.memory/entry-count result)))
      (is (= 0 (:psi.memory/recovery-count result))))

    (testing "structured values are returned"
      (is (vector? (:psi.memory/entries result)))
      (is (vector? (:psi.memory/search-results result)))
      (is (vector? (:psi.memory/recent-entries result)))
      (is (vector? (:psi.memory/recoveries result)))
      (is (vector? (:psi.memory/graph-snapshots result)))
      (is (vector? (:psi.memory/graph-deltas result)))
      (is (vector? (:psi.memory/capability-history result)))
      (is (map? (:psi.memory/by-tag result)))
      (is (map? (:psi.memory/index-stats result))))

    (testing "store registry attrs are queryable"
      (is (vector? (:psi.memory.store/providers result)))
      (is (= "in-memory" (:psi.memory.store/active-provider-id result)))
      (is (= "in-memory" (:psi.memory.store/default-provider-id result)))
      (is (= "in-memory" (:psi.memory.store/fallback-provider-id result)))
      (is (map? (:psi.memory.store/selection result)))
      (is (map? (:psi.memory.store/health result)))
      (is (map? (:psi.memory.store/active-provider-telemetry result)))
      (is (nil? (:psi.memory.store/last-failure result))))

    (testing "remember telemetry attrs are queryable"
      (is (= :idle (:psi.memory.remember/status result)))
      (is (vector? (:psi.memory.remember/captures result)))
      (is (nil? (:psi.memory.remember/last-capture-at result)))
      (is (nil? (:psi.memory.remember/last-error result))))))

(deftest remember-telemetry-prefers-remember-sourced-records
  (let [memory-ctx (memory/create-context)
        qctx       (memory-query-ctx)
        older      (Instant/parse "2026-03-05T00:00:00Z")
        newest     (Instant/parse "2026-03-06T01:00:00Z")
        _          (swap! (:state-atom memory-ctx)
                          assoc
                          :records [{:record-id "r1"
                                     :content-type :discovery
                                     :content "old remember"
                                     :tags [:remember "cycle"]
                                     :timestamp older
                                     :provenance {:source :remember}}
                                    {:record-id "r2"
                                     :content-type :session-user-message
                                     :content "session"
                                     :tags [:session :user]
                                     :timestamp newest
                                     :provenance {:source :session}}
                                    {:record-id "r3"
                                     :content-type :discovery
                                     :content "new remember"
                                     :tags [:remember "cycle"]
                                     :timestamp newest
                                     :provenance {:source :remember}}])
        result     (query/query-in qctx
                                   {:psi/memory-ctx memory-ctx}
                                   [:psi.memory.remember/status
                                    :psi.memory.remember/captures
                                    :psi.memory.remember/last-capture-at
                                    :psi.memory.remember/last-error])
        captures   (:psi.memory.remember/captures result)]
    (is (= :idle (:psi.memory.remember/status result)))
    (is (= ["r3" "r1"] (mapv :record-id captures)))
    (is (= newest (:psi.memory.remember/last-capture-at result)))
    (is (nil? (:psi.memory.remember/last-error result)))))

(deftest recent-entries-supports-source-tag-and-limit-params
  (let [memory-ctx (memory/create-context)
        qctx       (memory-query-ctx)
        now        (Instant/parse "2026-03-06T00:00:00Z")
        older      (Instant/parse "2026-03-05T00:00:00Z")
        newest     (Instant/parse "2026-03-06T01:00:00Z")
        _          (swap! (:state-atom memory-ctx)
                          assoc
                          :records [{:record-id "r1"
                                     :content-type :discovery
                                     :content "ff old"
                                     :tags [:remember "cycle"]
                                     :timestamp older
                                     :provenance {:source :remember}}
                                    {:record-id "r2"
                                     :content-type :session-user-message
                                     :content "session"
                                     :tags [:session :user]
                                     :timestamp now
                                     :provenance {:source :session}}
                                    {:record-id "r3"
                                     :content-type :discovery
                                     :content "ff new"
                                     :tags [:remember "cycle"]
                                     :timestamp newest
                                     :provenance {:source :remember}}])
        result  (query/query-in qctx
                                {:psi/memory-ctx memory-ctx}
                                [:psi.memory/recent-entries])
        entries (:psi.memory/recent-entries result)]
    (testing "returns only remember entries sorted by newest first"
      (is (= 2 (count entries)))
      (is (= ["r3" "r1"] (mapv :record-id entries))))))
