(ns psi.memory.runtime-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [psi.history.git :as git]
   [psi.memory.core :as memory]
   [psi.memory.runtime :as runtime]))

(deftest runtime-public-api-vars-exist
  (is (var? #'runtime/sync-memory-layer!))
  (is (var? #'runtime/maybe-sync-on-git-head-change!))
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

(deftest runtime-config-resolution-merges-explicit-opts-and-env
  (with-redefs [psi.memory.runtime/getenv (fn [k]
                                            ({"PSI_MEMORY_STORE" "in-memory"
                                              "PSI_MEMORY_STORE_AUTO_FALLBACK" "false"
                                              "PSI_MEMORY_HISTORY_COMMIT_LIMIT" "321"
                                              "PSI_MEMORY_RETENTION_SNAPSHOTS" "123"
                                              "PSI_MEMORY_RETENTION_DELTAS" "456"}
                                             k))]
    (let [resolved (#'runtime/resolve-runtime-config {:store-provider "in-memory"
                                                      :history-commit-limit 42
                                                      :retention-deltas 9})]
      (is (= :in-memory (:store-provider resolved)))
      (is (false? (:auto-store-fallback? resolved)))
      (is (= 42 (:history-commit-limit resolved)))
      (is (= 123 (:retention-snapshots resolved)))
      (is (= 9 (:retention-deltas resolved))))))

(deftest sync-memory-layer-applies-retention-overrides
  (let [memory-ctx (memory/create-context)
        git-ctx    (git/create-null-context)]
    (with-redefs [memory/global-context (fn [] memory-ctx)
                  git/create-context (fn
                                       ([] git-ctx)
                                       ([_] git-ctx))]
      (runtime/sync-memory-layer! {:retention-snapshots 5
                                   :retention-deltas 7
                                   :history-commit-limit 1})
      (let [state (memory/get-state-in memory-ctx)]
        (is (= 5 (get-in state [:retention :snapshots])))
        (is (= 7 (get-in state [:retention :deltas])))))))

(deftest sync-memory-layer-reports-unknown-store-provider
  (let [memory-ctx (memory/create-context)
        git-ctx    (git/create-null-context)]
    (with-redefs [memory/global-context (fn [] memory-ctx)
                  git/create-context (fn
                                       ([] git-ctx)
                                       ([_] git-ctx))]
      (let [result (runtime/sync-memory-layer! {:store-provider "unknown-store"
                                                :history-commit-limit 1})]
        (is (= :unknown-store-provider
               (get-in result [:store-registration :error])))
        (is (= "in-memory"
               (get-in result [:store-registration :store-summary :active-provider-id])))))))

(deftest maybe-sync-on-git-head-change-baseline-and-change
  (let [cwd        (str "/tmp/psi-runtime-test-" (random-uuid))
        git-ctx    {:repo-dir cwd}
        heads      (atom ["sha-1" "sha-1" "sha-2"])
        sync-calls (atom [])]
    (with-redefs [git/create-context (fn
                                       ([] git-ctx)
                                       ([_] git-ctx))
                  git/current-commit (fn [_]
                                       (let [head (first @heads)]
                                         (swap! heads subvec 1)
                                         head))
                  git/operation-state (fn [_]
                                        {:merge? false
                                         :rebase? false
                                         :cherry-pick? false
                                         :revert? false
                                         :bisect? false
                                         :transient? false})
                  git/head-reflog-latest (fn [_]
                                           {:head "sha-2"
                                            :selector "HEAD@{0}"
                                            :subject "commit: normal commit"})
                  git/commit-parent-count (fn [_ _] 1)
                  runtime/sync-memory-layer! (fn [opts]
                                               (swap! sync-calls conj opts)
                                               {:ok? true
                                                :git-head "sha-2"})]
      (let [first-result  (runtime/maybe-sync-on-git-head-change! {:cwd cwd})
            second-result (runtime/maybe-sync-on-git-head-change! {:cwd cwd})
            third-result  (runtime/maybe-sync-on-git-head-change! {:cwd cwd})]
        (is (= :head-baseline-established (:reason first-result)))
        (is (false? (:changed? first-result)))

        (is (= :head-unchanged (:reason second-result)))
        (is (false? (:changed? second-result)))

        (is (= :head-changed (:reason third-result)))
        (is (true? (:changed? third-result)))
        (is (= "sha-1" (:previous-head third-result)))
        (is (= "sha-2" (:head third-result)))
        (is (= :commit-created (get-in third-result [:classification :kind])))
        (is (true? (get-in third-result [:classification :notify-extensions?])))
        (is (= 1 (count @sync-calls)))
        (is (= cwd (:cwd (first @sync-calls))))))))

(deftest sync-memory-layer-does-not-clobber-git-head-baseline-test
  (let [cwd        (str "/tmp/psi-runtime-test-sync-cache-" (random-uuid))
        git-ctx    {:repo-dir cwd}
        memory-ctx (memory/create-context)
        heads      (atom ["sha-1" "sha-2" "sha-2"])]
    (reset! @#'runtime/git-head-cache {})
    (with-redefs [git/create-context (fn
                                       ([] git-ctx)
                                       ([_] git-ctx))
                  git/current-commit (fn [_]
                                       (let [head (first @heads)]
                                         (swap! heads subvec 1)
                                         head))
                  git/log (fn [_ _] [])
                  git/operation-state (fn [_]
                                        {:merge? false
                                         :rebase? false
                                         :cherry-pick? false
                                         :revert? false
                                         :bisect? false
                                         :transient? false})
                  git/head-reflog-latest (fn [_]
                                           {:head "sha-2"
                                            :selector "HEAD@{0}"
                                            :subject "commit: normal commit"})
                  git/commit-parent-count (fn [_ _] 1)
                  runtime/current-capability-graph (fn [] {:status :stable})
                  runtime/maybe-register-store-provider! (fn [_ _] {:ok? true})
                  memory/activate-in! (fn [_ _] {:ready? true})
                  memory/capture-graph-change-in! (fn [_ _] {:ok? true :changed? false})]
      (let [baseline (runtime/maybe-sync-on-git-head-change! {:cwd cwd
                                                              :git-ctx git-ctx
                                                              :memory-ctx memory-ctx
                                                              :query-ctx :query})]
        (is (= :head-baseline-established (:reason baseline)))
        (is (= "sha-1" (#'runtime/cached-head-for-cwd cwd))))

      (runtime/sync-memory-layer! {:cwd cwd
                                   :git-ctx git-ctx
                                   :memory-ctx memory-ctx
                                   :query-ctx :query})

      (is (= "sha-1" (#'runtime/cached-head-for-cwd cwd))
          "standalone sync-memory-layer! should not advance git-head baseline")

      (let [result (runtime/maybe-sync-on-git-head-change! {:cwd cwd
                                                            :git-ctx git-ctx
                                                            :memory-ctx memory-ctx
                                                            :query-ctx :query})]
        (is (= :head-changed (:reason result)))
        (is (true? (:changed? result)))
        (is (= "sha-1" (:previous-head result)))
        (is (= "sha-2" (:head result)))))))

(deftest maybe-sync-on-git-head-change-classifies-rebase-without-extension-notify
  (let [cwd     (str "/tmp/psi-runtime-test-rebase-" (random-uuid))
        git-ctx {:repo-dir cwd}
        heads   (atom ["sha-1" "sha-2"])]
    (with-redefs [git/create-context (fn
                                       ([] git-ctx)
                                       ([_] git-ctx))
                  git/current-commit (fn [_]
                                       (let [head (first @heads)]
                                         (swap! heads subvec 1)
                                         head))
                  git/operation-state (fn [_]
                                        {:merge? false
                                         :rebase? false
                                         :cherry-pick? false
                                         :revert? false
                                         :bisect? false
                                         :transient? false})
                  git/head-reflog-latest (fn [_]
                                           {:head "sha-2"
                                            :selector "HEAD@{0}"
                                            :subject "rebase (finish): returning to refs/heads/main"})
                  git/commit-parent-count (fn [_ _] 1)
                  runtime/sync-memory-layer! (fn [_]
                                               {:ok? true
                                                :git-head "sha-2"})]
      (runtime/maybe-sync-on-git-head-change! {:cwd cwd})
      (let [result (runtime/maybe-sync-on-git-head-change! {:cwd cwd})]
        (is (= :head-changed (:reason result)))
        (is (= :rebase (get-in result [:classification :kind])))
        (is (false? (get-in result [:classification :notify-extensions?])))))))

(deftest maybe-sync-on-git-head-change-reports-head-unavailable
  (let [cwd (str "/tmp/psi-runtime-test-no-head-" (random-uuid))
        git-ctx {:repo-dir cwd}]
    (with-redefs [git/create-context (fn
                                       ([] git-ctx)
                                       ([_] git-ctx))
                  git/current-commit (fn [_]
                                       (throw (ex-info "no git" {})))
                  runtime/sync-memory-layer! (fn [_]
                                               (throw (ex-info "should-not-sync" {})))]
      (let [result (runtime/maybe-sync-on-git-head-change! {:cwd cwd})]
        (is (false? (:ok? result)))
        (is (false? (:changed? result)))
        (is (= :git-head-unavailable (:reason result)))))))
