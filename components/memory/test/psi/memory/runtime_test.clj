(ns psi.memory.runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.history.git :as git]
   [psi.memory.core :as memory]
   [psi.memory.runtime :as runtime]))

(deftest runtime-public-api-vars-exist
  (is (var? #'runtime/sync-memory-layer!))
  (is (var? #'runtime/ingest-git-history-in!))
  (is (var? #'runtime/remember-session-message!))
  (is (var? #'runtime/recover-for-query!)))

(deftest ingest-git-history-in-imports-and-dedupes-commits
  (let [memory-ctx   (memory/create-context)
        git-ctx      (git/create-null-context)
        commit-count (count (git/log git-ctx {:n 50}))
        first-sync   (runtime/ingest-git-history-in! memory-ctx git-ctx {:n 50})
        second-sync  (runtime/ingest-git-history-in! memory-ctx git-ctx {:n 50})
        state        (memory/get-state-in memory-ctx)
        history-recs (->> (:records state)
                          (filter #(= :history (get-in % [:provenance :source])))
                          vec)]
    (testing "imports all commits on first run"
      (is (true? (:ok? first-sync)))
      (is (= commit-count (:imported-count first-sync)))
      (is (= 0 (:error-count first-sync))))

    (testing "dedupes by commit SHA on subsequent runs"
      (is (true? (:ok? second-sync)))
      (is (= 0 (:imported-count second-sync)))
      (is (= commit-count (:skipped-count second-sync))))

    (testing "stores entries as history records"
      (is (= commit-count (count history-recs)))
      (is (= commit-count (get-in state [:index-stats :by-source :history])))
      (is (every? #(= :git-commit (:content-type %)) history-recs))
      (is (some (fn [record] (some #{:learning} (:tags record))) history-recs))
      (is (some (fn [record] (some #{:delta} (:tags record))) history-recs)))

    (testing "recover can return ingested history records"
      (let [recovery (memory/recover-in! memory-ctx {:sources [:session :history :graph]
                                                     :query-text "learning"})]
        (is (true? (:ok? recovery)))
        (is (pos? (:result-count recovery)))
        (is (some #(= :git-commit (:content-type %)) (:results recovery)))))))
