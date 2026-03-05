(ns psi.memory.datalevin-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.memory.datalevin :as datalevin]
   [psi.memory.store :as store])
  (:import
   (java.nio.file Files Path)
   (java.nio.file.attribute FileAttribute)
   (java.time Instant)))

(defn- temp-dir-path
  []
  (-> (Files/createTempDirectory
       "psi-memory-datalevin-"
       (make-array FileAttribute 0))
      str))

(defn- delete-recursively!
  [^String root]
  (let [root-path (Path/of root (make-array String 0))]
    (when (Files/exists root-path (make-array java.nio.file.LinkOption 0))
      (let [paths (with-open [stream (Files/walk root-path (make-array java.nio.file.FileVisitOption 0))]
                    (vec (iterator-seq (.iterator stream))))]
        (run! #(Files/deleteIfExists %)
              (reverse paths))))))

(deftest datalevin-provider-open-write-load-close
  (let [db-dir   (temp-dir-path)
        provider (datalevin/create-provider {:db-dir db-dir})
        ts       (Instant/parse "2026-03-03T12:00:00Z")]
    (try
      (store/open-provider! provider {:reason :test})

      (testing "provider opens and reports ready/healthy"
        (is (= :ready (store/provider-status provider)))
        (is (= :healthy (get-in (store/provider-health provider) [:status]))))

      (testing "memory artifacts persist and load"
        (let [record-write (store/provider-write! provider
                                                  :memory-record
                                                  {:record-id "r-1"
                                                   :content-type :note
                                                   :content "hello datalevin"
                                                   :tags [:persist :memory]
                                                   :timestamp ts
                                                   :provenance {:source :session
                                                                :capabilityIds [:cap/a]}})
              snapshot-write (store/provider-write! provider
                                                    :graph-snapshot
                                                    {:snapshot-id "s-1"
                                                     :fingerprint "fp-1"
                                                     :timestamp ts
                                                     :capability-ids [:cap/a]})
              delta-write (store/provider-write! provider
                                                 :graph-delta
                                                 {:delta-id "d-1"
                                                  :from-fingerprint "fp-0"
                                                  :to-fingerprint "fp-1"
                                                  :timestamp ts})
              recovery-write (store/provider-write! provider
                                                    :recovery-run
                                                    {:recovery-id "rec-1"
                                                     :timestamp ts
                                                     :filters {:query-text "hello"}})
              loaded (store/provider-load-state provider)]
          (is (true? (:ok? record-write)))
          (is (true? (:ok? snapshot-write)))
          (is (true? (:ok? delta-write)))
          (is (true? (:ok? recovery-write)))

          (is (true? (:ok? loaded)))
          (is (= 1 (count (:records loaded))))
          (is (= 1 (count (:graph-snapshots loaded))))
          (is (= 1 (count (:graph-deltas loaded))))
          (is (= 1 (count (:recoveries loaded))))
          (is (= 1 (get-in loaded [:index-stats :entry-count]))))

        (testing "provider query supports filter keys"
          (let [query-result (store/provider-query! provider {:tags [:persist]
                                                              :query-text "hello"
                                                              :limit 10})]
            (is (true? (:ok? query-result)))
            (is (= 1 (count (:results query-result)))))))

      (testing "provider closes cleanly"
        (store/close-provider! provider)
        (is (= :closed (store/provider-status provider))))
      (finally
        (delete-recursively! db-dir)))))

(deftest datalevin-provider-persists-across-provider-instances
  (let [db-dir (temp-dir-path)
        ts     (Instant/parse "2026-03-03T12:00:00Z")]
    (try
      (let [provider-a (datalevin/create-provider {:db-dir db-dir})]
        (store/open-provider! provider-a {:reason :test-a})
        (is (true? (:ok? (store/provider-write! provider-a
                                             :memory-record
                                             {:record-id "r-1"
                                              :content-type :note
                                              :content "persist me"
                                              :tags [:persist]
                                              :timestamp ts
                                              :provenance {:source :session}}))))
        (store/close-provider! provider-a))

      (let [provider-b (datalevin/create-provider {:db-dir db-dir})]
        (store/open-provider! provider-b {:reason :test-b})
        (let [loaded (store/provider-load-state provider-b)]
          (is (true? (:ok? loaded)))
          (is (= 1 (count (:records loaded))))
          (is (= "persist me" (:content (first (:records loaded))))))
        (store/close-provider! provider-b))
      (finally
        (delete-recursively! db-dir)))))
