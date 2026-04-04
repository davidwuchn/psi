(ns psi.history.resolvers-test
  "Tests for psi.history.resolvers.

   Both the git infrastructure and the query graph are isolated:
   - git/create-null-context  → isolated temp repo (Nullable git)
   - query/create-query-context → isolated Pathom env (Nullable query)
   No mocks, no shared state, no dependency on the real project repo."
  (:require
   [clojure.test :refer [deftest testing is]]
   [com.wsscode.pathom3.connect.operation :as pco]
   [psi.history.git :as git]
   [psi.history.resolvers :as resolvers]
   [psi.query.core :as query])
  (:import
   (java.io File)))

;;; Seed data for null git repos

(def ^:private seed-commits
  [{:message "⚒ Initial commit"
    :files   {"README.md"    "# psi\n"
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
  (let [f    (File. (str repo-dir File/separator rel-path))
        run! (fn [& args]
               (let [pb   (ProcessBuilder. ^java.util.List (vec args))
                     _    (.directory pb (File. ^String repo-dir))
                     _    (doto (.environment pb)
                            (.put "GIT_AUTHOR_NAME" "Test Author")
                            (.put "GIT_AUTHOR_EMAIL" "test@example.com")
                            (.put "GIT_COMMITTER_NAME" "Test Author")
                            (.put "GIT_COMMITTER_EMAIL" "test@example.com"))
                     proc (.start pb)
                     _    (slurp (.getInputStream proc))
                     err  (slurp (.getErrorStream proc))
                     exit (.waitFor proc)]
                 (when (pos? exit)
                   (throw (ex-info "git helper failed"
                                   {:args args :err err :exit exit})))))]
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
  ;; Null repo should report :clean after seeded commits.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.repo/status
                                   :git.repo/current-commit
                                   :git.repo/has-changes])]
    (testing "git-repo-status on a clean seeded repo"
      (testing "status is :clean"
        (is (= :clean (:git.repo/status result))))
      (testing "current commit is a sha string"
        (is (string? (:git.repo/current-commit result))))
      (testing "has-changes is false"
        (is (false? (:git.repo/has-changes result)))))))

(deftest repo-status-resolves-dirty-repo
  ;; Uncommitted changes should surface as has-changes true.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        dirty-file (str (:repo-dir git-ctx) File/separator "README.md")
        _         (spit dirty-file "# psi\nchanged\n")
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.repo/status
                                   :git.repo/has-changes])]
    (testing "git-repo-status on a dirty repo"
      (testing "status reports a changed worktree"
        (is (contains? #{:modified :staged} (:git.repo/status result))
            (str "expected modified or staged status, got " (:git.repo/status result))))
      (testing "has-changes is true"
        (is (true? (:git.repo/has-changes result)))))))

;;; git-repo-commits resolver

(deftest repo-commits-resolves
  ;; Null repo has three seeded commits.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.repo/commits
                                   :git.repo/has-history])]
    (testing "git-repo-commits returns history for the seeded repo"
      (testing "commits list is present"
        (is (seq (:git.repo/commits result))))
      (testing "has-history is true"
        (is (true? (:git.repo/has-history result))))
      (testing "commit count matches the seed data"
        (is (= 3 (count (:git.repo/commits result)))
            "seed repo should expose exactly three commits")))))

(deftest repo-commits-each-has-required-fields
  ;; Every commit must carry all spec-required fields.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        commits   (-> (query/query-in query-ctx
                                      {:git/context git-ctx}
                                      [:git.repo/commits])
                      :git.repo/commits)]
    (testing "git-repo-commits returns commits with required fields"
      (doseq [commit commits]
        (testing (str "commit " (:git.commit/sha commit) " has required fields")
          (is (:git.commit/sha commit))
          (is (:git.commit/date commit))
          (is (:git.commit/author commit))
          (is (:git.commit/subject commit))
          (is (set? (:git.commit/symbols commit))))))))

(deftest repo-commits-support-limit-param
  ;; Optional :n param should bound the number of returned commits.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [(list :git.repo/commits {:n 1})])
        commits   (:git.repo/commits result)]
    (testing "git-repo-commits supports the :n param"
      (is (= 1 (count commits)) "expected exactly one commit when :n is 1")
      (is (string? (:git.commit/sha (first commits)))))))

(deftest repo-commits-support-grep-param
  ;; Optional :grep param should filter commit messages.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [(list :git.repo/commits {:grep "λ"})])
        commits   (:git.repo/commits result)]
    (testing "git-repo-commits supports the :grep param"
      (is (= 1 (count commits)) "expected exactly one λ commit")
      (is (every? #(contains? (:git.commit/symbols %) "λ") commits)))))

(deftest repo-commits-support-path-param
  ;; Optional :path param should restrict history to a matching file path.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [(list :git.repo/commits {:path "README.md"})])
        commits   (:git.repo/commits result)]
    (testing "git-repo-commits supports the :path param"
      (is (= 1 (count commits)) "expected README.md history to include one seed commit")
      (is (= "⚒ Initial commit" (:git.commit/subject (first commits)))))))

;;; git-commit-detail resolver

(deftest commit-detail-resolves
  ;; Resolving HEAD sha returns full detail including diff.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        sha       (git/current-commit git-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx
                                   :git.commit/sha sha}
                                  [:git.commit/subject
                                   :git.commit/author
                                   :git.commit/body
                                   :git.commit/diff
                                   :git.commit/symbols
                                   :git.commit/is-learning
                                   :git.commit/is-delta])]
    (testing "git-commit-detail resolves commit detail for a sha"
      (testing "subject is a string"
        (is (string? (:git.commit/subject result))))
      (testing "diff is a string"
        (is (string? (:git.commit/diff result))))
      (testing "symbols is a set"
        (is (set? (:git.commit/symbols result))))
      (testing "is-learning is boolean"
        (is (boolean? (:git.commit/is-learning result))))
      (testing "is-delta is boolean"
        (is (boolean? (:git.commit/is-delta result)))))))

;;; git-commit-derived resolver

(deftest commit-derived-lambda
  ;; is-learning should be true when symbols contains λ.
  (let [query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git.commit/symbols #{"λ"}}
                                  [:git.commit/is-learning
                                   :git.commit/is-delta])]
    (testing "git-commit-derived recognizes λ commits"
      (testing "is-learning is true for λ"
        (is (true? (:git.commit/is-learning result))))
      (testing "is-delta is false without Δ"
        (is (false? (:git.commit/is-delta result)))))))

(deftest commit-derived-delta
  ;; is-delta should be true when symbols contains Δ.
  (let [query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git.commit/symbols #{"Δ" "⚒"}}
                                  [:git.commit/is-learning
                                   :git.commit/is-delta])]
    (testing "git-commit-derived recognizes Δ commits"
      (testing "is-learning is false without λ"
        (is (false? (:git.commit/is-learning result))))
      (testing "is-delta is true for Δ"
        (is (true? (:git.commit/is-delta result)))))))

;;; git-grep resolver

(deftest grep-resolver-finds-pattern
  ;; Null repo has defresolver in src/core.clj.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        results   (-> (query/query-in query-ctx
                                      {:git/context git-ctx
                                       :git.repo/grep-pattern "defresolver"}
                                      [:git.repo/grep-results])
                      :git.repo/grep-results)]
    (testing "git-grep returns matches for a present pattern"
      (testing "results are non-empty"
        (is (seq results)))
      (testing "each result has file, line, and content"
        (is (every? :git.grep/file results))
        (is (every? :git.grep/line results))
        (is (every? :git.grep/content results))))))

(deftest grep-resolver-no-match
  ;; Nonsense pattern should return an empty result set.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        results   (-> (query/query-in query-ctx
                                      {:git/context git-ctx
                                       :git.repo/grep-pattern "XYZZY_NOTHING_9999"}
                                      [:git.repo/grep-results])
                      :git.repo/grep-results)]
    (testing "git-grep returns empty for a missing pattern"
      (is (or (nil? results) (empty? results))))))

(deftest grep-resolver-supports-path-and-limit-params
  ;; Optional grep params should restrict results by path and count.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        results   (-> (query/query-in query-ctx
                                      {:git/context git-ctx
                                       :git.repo/grep-pattern "defresolver"}
                                      [(list :git.repo/grep-results {:path "src" :n 1})])
                      :git.repo/grep-results)]
    (testing "git-grep supports :path and :n params"
      (is (= 1 (count results)) "expected result count to be bounded by :n")
      (is (= "src/core.clj" (:git.grep/file (first results)))))))

;;; git-learning-commits resolver

(deftest learning-commits-resolver
  ;; Null repo has one λ commit.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        commits   (-> (query/query-in query-ctx
                                      {:git/context git-ctx}
                                      [:git.repo/learning-commits])
                      :git.repo/learning-commits)]
    (testing "git-learning-commits returns λ commits"
      (testing "exactly one λ commit is returned"
        (is (= 1 (count commits))))
      (testing "all returned commits include the λ symbol"
        (is (every? #(contains? (:git.commit/symbols %) "λ") commits))))))

;;; Context isolation

(deftest two-query-contexts-are-independent
  ;; Registering into ctx-a must not affect ctx-b.
  (let [ctx-a (query/create-query-context)
        ctx-b (query/create-query-context)]
    (doseq [resolver resolvers/all-resolvers]
      (query/register-resolver-in! ctx-a resolver))
    (query/rebuild-env-in! ctx-a)
    (testing "query contexts are isolated"
      (testing "ctx-a has registered resolvers"
        (is (pos? (:resolver-count (query/graph-summary-in ctx-a)))))
      (testing "ctx-b remains empty"
        (is (zero? (:resolver-count (query/graph-summary-in ctx-b))))))))

;;; git-worktree resolvers

(deftest worktree-resolvers-inside-repo
  ;; Worktree resolvers should reflect the main repo worktree when inside git.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.worktree/list
                                   :git.worktree/current
                                   :git.worktree/count
                                   :git.worktree/inside-repo?
                                   :git.branch/default-branch])]
    (testing "worktree resolvers inside a git repo"
      (testing "inside-repo? is true"
        (is (true? (:git.worktree/inside-repo? result))))
      (testing "list is a vector"
        (is (vector? (:git.worktree/list result))))
      (testing "current worktree is a map"
        (is (map? (:git.worktree/current result))))
      (testing "count matches the worktree list size"
        (is (= (count (:git.worktree/list result))
               (:git.worktree/count result))))
      (testing "default branch metadata is available"
        (is (map? (:git.branch/default-branch result)))
        (is (string? (get-in result [:git.branch/default-branch :branch])))))))

(deftest worktree-resolvers-outside-repo
  ;; Worktree resolvers should degrade safely outside a git repo.
  (let [tmp       (str (java.nio.file.Files/createTempDirectory
                        "psi-history-no-git-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        git-ctx   (git/create-context tmp)
        query-ctx (make-query-ctx)
        result    (query/query-in query-ctx
                                  {:git/context git-ctx}
                                  [:git.worktree/list
                                   :git.worktree/current
                                   :git.worktree/count
                                   :git.worktree/inside-repo?])]
    (testing "worktree resolvers outside a git repo"
      (testing "inside-repo? is false"
        (is (false? (:git.worktree/inside-repo? result))))
      (testing "list is empty"
        (is (= [] (:git.worktree/list result))))
      (testing "current worktree is nil"
        (is (nil? (:git.worktree/current result))))
      (testing "count is zero"
        (is (= 0 (:git.worktree/count result)))))))

;;; git mutations

(deftest worktree-mutations-roundtrip-via-eql
  ;; Adding and removing a worktree should roundtrip through EQL mutations.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        wt-path   (linked-worktree-path git-ctx "resolver-feature")
        added     (mutate query-ctx git-ctx 'git.worktree/add!
                          {:input {:path wt-path :branch "resolver-feature"}})
        listed    (query/query-in query-ctx {:git/context git-ctx} [:git.worktree/list])
        removed   (mutate query-ctx git-ctx 'git.worktree/remove!
                          {:input {:path wt-path}})]
    (testing "git.worktree add/remove mutations roundtrip through EQL"
      (testing "add succeeds"
        (is (true? (:success added))))
      (testing "added worktree appears in list output"
        (is (some #(= (.getCanonicalPath (File. wt-path))
                      (.getCanonicalPath (File. (:git.worktree/path %))))
                  (:git.worktree/list listed))))
      (testing "remove succeeds"
        (is (true? (:success removed)))))))

(deftest branch-mutations-via-eql
  ;; Branch merge/delete/default should work through EQL mutations.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        wt-path   (linked-worktree-path git-ctx "resolver-merge")
        _         (mutate query-ctx git-ctx 'git.worktree/add!
                          {:input {:path wt-path :branch "resolver-merge"}})
        _         (append-and-commit! wt-path
                                      "src/resolver_merge.clj"
                                      "(ns resolver-merge)\n"
                                      "⚒ resolver merge commit")
        merged    (mutate query-ctx git-ctx 'git.branch/merge!
                          {:input {:branch "resolver-merge"}})
        removed   (mutate query-ctx git-ctx 'git.worktree/remove!
                          {:input {:path wt-path}})
        deleted   (mutate query-ctx git-ctx 'git.branch/delete!
                          {:input {:branch "resolver-merge"}})
        default   (mutate query-ctx git-ctx 'git.branch/default {})]
    (testing "branch merge/delete/default mutations work through EQL"
      (testing "merge succeeds without error"
        (is (true? (:merged merged)))
        (is (nil? (:error merged))))
      (testing "worktree remove succeeds"
        (is (true? (:success removed))))
      (testing "branch delete succeeds"
        (is (true? (:deleted deleted))))
      (testing "default branch resolves to main"
        (is (= "main" (:branch default)))))))

(deftest branch-rebase-via-eql
  ;; Branch rebase should work from a linked worktree context.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        wt-path   (linked-worktree-path git-ctx "resolver-rebase")
        _         (mutate query-ctx git-ctx 'git.worktree/add!
                          {:input {:path wt-path :branch "resolver-rebase"}})
        _         (append-and-commit! wt-path
                                      "src/rebase_from_resolver.clj"
                                      "(ns rebase-from-resolver)\n"
                                      "⚒ feature before resolver rebase")
        _         (append-and-commit! (:repo-dir git-ctx)
                                      "src/main_for_resolver_rebase.clj"
                                      "(ns main-for-resolver-rebase)\n"
                                      "⚒ main before resolver rebase")
        wt-ctx    (git/create-context wt-path)
        rebased   (mutate query-ctx wt-ctx 'git.branch/rebase!
                          {:input {:onto "main"}})]
    (testing "branch rebase mutation works through EQL"
      (testing "rebase succeeds"
        (is (true? (:success rebased))))
      (testing "rebase reports the rebased branch name"
        (is (= "resolver-rebase" (:branch rebased)))))))

(deftest worktree-remove-nonexistent-path-via-eql
  ;; Removing a missing worktree should report failure data rather than throwing.
  (let [git-ctx   (make-git-ctx)
        query-ctx (make-query-ctx)
        wt-path   (linked-worktree-path git-ctx "missing-worktree")
        result    (mutate query-ctx git-ctx 'git.worktree/remove!
                          {:input {:path wt-path}})]
    (testing "git.worktree/remove! handles missing paths"
      (is (false? (:success result)))
      (is (string? (:error result))))))

;;; Resolver/mutation count sanity

(deftest all-resolvers-registered
  ;; Resolver registry should expose the expected resolver count.
  (testing "history resolver registry contains the expected number of resolvers"
    (is (= 12 (count resolvers/all-resolvers)))))

(deftest all-mutations-registered
  ;; Mutation registry should expose the expected mutation count.
  (testing "history mutation registry contains the expected number of mutations"
    (is (= 6 (count resolvers/all-mutations)))))

(deftest resolver-index-contains-mutation-symbols
  ;; Pathom mutation op names should match the exported mutations.
  (let [mutation-syms (->> resolvers/all-mutations
                           (map #(-> % pco/operation-config ::pco/op-name))
                           set)]
    (testing "Pathom mutation index includes history mutation symbols"
      (is (contains? mutation-syms 'git.worktree/add!))
      (is (contains? mutation-syms 'git.worktree/remove!))
      (is (contains? mutation-syms 'git.branch/merge!))
      (is (contains? mutation-syms 'git.branch/delete!))
      (is (contains? mutation-syms 'git.branch/rebase!))
      (is (contains? mutation-syms 'git.branch/default)))))
