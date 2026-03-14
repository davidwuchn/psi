(ns psi.history.resolvers-test
  "Tests for psi.history.resolvers.

   Both the git infrastructure and the query graph are isolated:
   - git/create-null-context  → isolated temp repo (Nullable git)
   - query/create-query-context → isolated Pathom env (Nullable query)
   No mocks, no shared state, no dependency on the real project repo."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.history.resolvers :as resolvers]
   [psi.history.git :as git]
   [psi.query.core :as query])
  (:import
   (java.io File)))

;;; Seed data for null git repos

(def ^:private seed-commits
  [{:message "⚒ Initial commit"
    :files   {"README.md"   "# psi\n"
              "src/core.clj" "(ns core)\n(defresolver foo [] {})\n"}}
   {:message "λ First learning captured"
    :files   {"LEARNING.md" "## discovery one\n"}}
   {:message "Δ Delta entry"
    :files   {"CHANGELOG.md" "## changes\n"}}])

;;; Factory helpers

(defn- make-git-ctx []
  (git/create-null-context seed-commits))

(defn- linked-worktree-path
  [ctx name]
  (let [repo-file (File. (:repo-dir ctx))
        parent    (.getCanonicalPath (.getParentFile repo-file))
        suffix    (.getName repo-file)
        uniq      (str (java.util.UUID/randomUUID))]
    (str parent File/separator name "-" suffix "-" uniq)))

(defn- append-and-commit!
  [repo-dir rel-path content message]
  (let [f (File. (str repo-dir File/separator rel-path))
        run! (fn [& args]
               (let [pb   (ProcessBuilder. ^java.util.List (vec args))
                     _    (.directory pb (File. ^String repo-dir))
                     _    (doto (.environment pb)
                            (.put "GIT_AUTHOR_NAME"     "Test Author")
                            (.put "GIT_AUTHOR_EMAIL"    "test@example.com")
                            (.put "GIT_COMMITTER_NAME"  "Test Author")
                            (.put "GIT_COMMITTER_EMAIL" "test@example.com"))
                     proc (.start pb)
                     _    (slurp (.getInputStream proc))
                     err  (slurp (.getErrorStream proc))
                     exit (.waitFor proc)]
                 (when (pos? exit)
                   (throw (ex-info "git helper failed" {:args args :err err :exit exit})))))]
    (.mkdirs (.getParentFile f))
    (spit f content)
    (run! "git" "add" rel-path)
    (run! "git" "commit" "-m" message)))

(defn- make-query-ctx []
  (let [ctx (query/create-query-context)]
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! ctx r))
    (doseq [m resolvers/all-mutations]
      (query/register-mutation-in! ctx m))
    (query/rebuild-env-in! ctx)
    ctx))

(defn- mutate
  [qctx git-ctx op params]
  (get (query/query-in qctx
                       {:git/context git-ctx}
                       [(list op (assoc params :git/context git-ctx))])
       op))

;;; git-repo-status resolver

(deftest repo-status-resolves
  ;; null repo should report :clean after seeded commits
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.repo/status
                                   :git.repo/current-commit
                                   :git.repo/has-changes])]
    (testing "status is :clean"
      (is (= :clean (:git.repo/status result))))
    (testing "current-commit is a sha string"
      (is (string? (:git.repo/current-commit result))))
    (testing "has-changes is false on clean repo"
      (is (false? (:git.repo/has-changes result))))))

;;; git-repo-commits resolver

(deftest repo-commits-resolves
  ;; null repo has 3 seeded commits
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.repo/commits
                                   :git.repo/has-history])]
    (testing "commits list is present"
      (is (seq (:git.repo/commits result))))
    (testing "has-history is true"
      (is (true? (:git.repo/has-history result))))
    (testing "commit count matches seeds"
      (is (= 3 (count (:git.repo/commits result)))))))

(deftest repo-commits-each-has-required-fields
  ;; every commit must carry all spec-required fields
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        commits   (-> (query/query-in query-ctx
                                      {:git/context git-ctx}
                                      [:git.repo/commits])
                      :git.repo/commits)]
    (doseq [c commits]
      (testing (str "commit " (:git.commit/sha c) " has required fields")
        (is (:git.commit/sha c))
        (is (:git.commit/date c))
        (is (:git.commit/author c))
        (is (:git.commit/subject c))
        (is (set? (:git.commit/symbols c)))))))

;;; git-commit-detail resolver

(deftest commit-detail-resolves
  ;; resolving HEAD sha returns full detail including diff
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        sha       (git/current-commit git-ctx)
        result    (query/query-in query-ctx
                                  {:git/context    git-ctx
                                   :git.commit/sha  sha}
                                  [:git.commit/subject
                                   :git.commit/author
                                   :git.commit/body
                                   :git.commit/diff
                                   :git.commit/symbols
                                   :git.commit/is-learning
                                   :git.commit/is-delta])]
    (testing "subject is a string"
      (is (string? (:git.commit/subject result))))
    (testing "diff is a string"
      (is (string? (:git.commit/diff result))))
    (testing "symbols is a set"
      (is (set? (:git.commit/symbols result))))
    (testing "is-learning is boolean"
      (is (boolean? (:git.commit/is-learning result))))
    (testing "is-delta is boolean"
      (is (boolean? (:git.commit/is-delta result))))))

;;; git-commit-derived resolver (pure — no git required)

(deftest commit-derived-lambda
  ;; is-learning = true when symbols contains λ — no git needed
  (let [query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git.commit/symbols #{"λ"}}
                                  [:git.commit/is-learning
                                   :git.commit/is-delta])]
    (testing "is-learning is true for λ"
      (is (true? (:git.commit/is-learning result))))
    (testing "is-delta is false without Δ"
      (is (false? (:git.commit/is-delta result))))))

(deftest commit-derived-delta
  ;; is-delta = true when symbols contains Δ — no git needed
  (let [query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git.commit/symbols #{"Δ" "⚒"}}
                                  [:git.commit/is-learning
                                   :git.commit/is-delta])]
    (testing "is-learning is false without λ"
      (is (false? (:git.commit/is-learning result))))
    (testing "is-delta is true for Δ"
      (is (true? (:git.commit/is-delta result))))))

;;; git-grep resolver

(deftest grep-resolver-finds-pattern
  ;; null repo has defresolver in src/core.clj — grep must find it
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        results   (-> (query/query-in query-ctx
                                      {:git/context           git-ctx
                                       :git.repo/grep-pattern  "defresolver"}
                                      [:git.repo/grep-results])
                      :git.repo/grep-results)]
    (testing "results are non-empty"
      (is (seq results)))
    (testing "each result has file, line, content"
      (is (every? :git.grep/file results))
      (is (every? :git.grep/line results))
      (is (every? :git.grep/content results)))))

(deftest grep-resolver-no-match
  ;; nonsense pattern must return empty seq
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        results   (-> (query/query-in query-ctx
                                      {:git/context           git-ctx
                                       :git.repo/grep-pattern  "XYZZY_NOTHING_9999"}
                                      [:git.repo/grep-results])
                      :git.repo/grep-results)]
    (testing "no match returns empty"
      (is (or (nil? results) (empty? results))))))

;;; git-learning-commits resolver

(deftest learning-commits-resolver
  ;; null repo has one λ commit
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        commits   (-> (query/query-in query-ctx
                                      {:git/context git-ctx}
                                      [:git.repo/learning-commits])
                      :git.repo/learning-commits)]
    (testing "exactly one λ commit"
      (is (= 1 (count commits))))
    (testing "all returned commits have λ symbol"
      (is (every? #(contains? (:git.commit/symbols %) "λ") commits)))))

;;; Context isolation — two query contexts are independent

(deftest two-query-contexts-are-independent
  ;; registering into ctx-a must not affect ctx-b
  (let [ctx-a (query/create-query-context)
        ctx-b (query/create-query-context)]
    (doseq [r resolvers/all-resolvers]
      (query/register-resolver-in! ctx-a r))
    (query/rebuild-env-in! ctx-a)
    (testing "ctx-a has resolvers"
      (is (pos? (:resolver-count (query/graph-summary-in ctx-a)))))
    (testing "ctx-b is still empty"
      (is (zero? (:resolver-count (query/graph-summary-in ctx-b)))))))

;;; git-worktree resolvers

(deftest worktree-resolvers-inside-repo
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.worktree/list
                                   :git.worktree/current
                                   :git.worktree/count
                                   :git.worktree/inside-repo?])]
    (is (true? (:git.worktree/inside-repo? result)))
    (is (vector? (:git.worktree/list result)))
    (is (map? (:git.worktree/current result)))
    (is (= (count (:git.worktree/list result))
           (:git.worktree/count result)))))

(deftest worktree-resolvers-outside-repo
  (let [tmp       (str (java.nio.file.Files/createTempDirectory
                        "psi-history-no-git-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        git-ctx    (git/create-context tmp)
        query-ctx  (make-query-ctx)
        result     (query/query-in query-ctx
                                   {:git/context git-ctx}
                                   [:git.worktree/list
                                    :git.worktree/current
                                    :git.worktree/count
                                    :git.worktree/inside-repo?])]
    (is (false? (:git.worktree/inside-repo? result)))
    (is (= [] (:git.worktree/list result)))
    (is (nil? (:git.worktree/current result)))
    (is (= 0 (:git.worktree/count result)))))

;;; git mutations

(deftest worktree-mutations-roundtrip-via-eql
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        wt-path   (linked-worktree-path git-ctx "resolver-feature")
        added     (mutate query-ctx git-ctx 'git.worktree/add!
                          {:input {:path wt-path :branch "resolver-feature"}})
        listed     (query/query-in query-ctx {:git/context git-ctx} [:git.worktree/list])
        removed   (mutate query-ctx git-ctx 'git.worktree/remove!
                          {:input {:path wt-path}})]
    (is (true? (:success added)))
    (is (some #(= (.getCanonicalPath (File. wt-path))
                  (.getCanonicalPath (File. (:git.worktree/path %))))
              (:git.worktree/list listed)))
    (is (true? (:success removed)))))

(deftest branch-mutations-via-eql
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        wt-path   (linked-worktree-path git-ctx "resolver-merge")
        _         (mutate query-ctx git-ctx 'git.worktree/add!
                          {:input {:path wt-path :branch "resolver-merge"}})
        _         (append-and-commit! wt-path "src/resolver_merge.clj" "(ns resolver-merge)\n" "⚒ resolver merge commit")
        merged    (mutate query-ctx git-ctx 'git.branch/merge!
                          {:input {:branch "resolver-merge"}})
        removed   (mutate query-ctx git-ctx 'git.worktree/remove!
                          {:input {:path wt-path}})
        deleted   (mutate query-ctx git-ctx 'git.branch/delete!
                          {:input {:branch "resolver-merge"}})
        default   (mutate query-ctx git-ctx 'git.branch/default {})]
    (is (true? (:merged merged)))
    (is (true? (:success removed)))
    (is (true? (:deleted deleted)))
    (is (= "main" (:branch default)))))

(deftest branch-rebase-via-eql
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        wt-path   (linked-worktree-path git-ctx "resolver-rebase")
        _         (mutate query-ctx git-ctx 'git.worktree/add!
                          {:input {:path wt-path :branch "resolver-rebase"}})
        _         (append-and-commit! wt-path "src/rebase_from_resolver.clj" "(ns rebase-from-resolver)\n" "⚒ feature before resolver rebase")
        _         (append-and-commit! (:repo-dir git-ctx) "src/main_for_resolver_rebase.clj" "(ns main-for-resolver-rebase)\n" "⚒ main before resolver rebase")
        wt-ctx    (git/create-context wt-path)
        rebased   (mutate query-ctx wt-ctx 'git.branch/rebase!
                          {:input {:onto "main"}})]
    (is (true? (:success rebased)))
    (is (= "resolver-rebase" (:branch rebased)))))

;;; Resolver/mutation count sanity

(deftest all-resolvers-registered
  (testing "all-resolvers count"
    (is (= 11 (count resolvers/all-resolvers)))))

(deftest all-mutations-registered
  (testing "all-mutations count"
    (is (= 6 (count resolvers/all-mutations)))))

(deftest resolver-index-contains-mutation-symbols
  (testing "Pathom index includes history mutation op names"
    (let [mutation-syms (->> resolvers/all-mutations
                             (map #(-> % pco/operation-config ::pco/op-name))
                             set)]
      (is (contains? mutation-syms 'git.worktree/add!))
      (is (contains? mutation-syms 'git.worktree/remove!))
      (is (contains? mutation-syms 'git.branch/merge!))
      (is (contains? mutation-syms 'git.branch/delete!))
      (is (contains? mutation-syms 'git.branch/rebase!))
      (is (contains? mutation-syms 'git.branch/default)))))
