(ns psi.history.git-test
  "Tests for psi.history.git.

   Uses create-null-context — an isolated temp git repo with seeded commits.
   No mocks, no dependency on the real project repo, no shared state."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [psi.history.git :as git])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(def ^:private seed-commits
  [{:message "⚒ Initial commit"
    :files   {"README.md"    "# psi\n"
              "src/core.clj" "(ns core)\n(defresolver foo [] {})\n"}}
   {:message "λ First learning captured"
    :files   {"LEARNING.md" "## learned something\n"}}
   {:message "Δ Show a delta here"
    :files   {"CHANGELOG.md" "## v0.1\n"}}
   {:message "⚒ Add another feature"
    :files   {"src/extra.clj" "(ns extra)\n"}}])

(def ^:private shared-ro-ctx
  "Delayed null context for read-only tests. Created once per test run."
  (delay (git/create-null-context seed-commits)))

(defn- canonical-path
  [path]
  (.getCanonicalPath (File. (str path))))

(defn- worktree-listed?
  [worktrees path]
  (some #(= (canonical-path path)
            (canonical-path (:git.worktree/path %)))
        worktrees))

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

;;; git/log

(deftest log-returns-commits
  (let [ctx     @shared-ro-ctx
        commits (git/log ctx {:n 10})]
    (testing "returns the seeded commits"
      (is (= 4 (count commits))))
    (testing "each commit has required fields"
      (is (every? :git.commit/sha commits))
      (is (every? :git.commit/subject commits))
      (is (every? :git.commit/author commits))
      (is (every? :git.commit/date commits))
      (is (every? :git.commit/symbols commits)))
    (testing "symbols is always a set"
      (is (every? set? (map :git.commit/symbols commits))))))

(deftest log-n-limits-results
  (let [ctx     @shared-ro-ctx
        commits (git/log ctx {:n 2})]
    (testing ":n limits results"
      (is (<= (count commits) 2)))))

(deftest log-grep-filters-by-message
  (let [ctx     @shared-ro-ctx
        grepped (git/log ctx {:grep "λ"})]
    (testing "grep returns only matching commits"
      (is (= 1 (count grepped)))
      (is (str/includes? (:git.commit/subject (first grepped)) "λ")))))

(deftest log-symbols-extracted
  (let [ctx     @shared-ro-ctx
        commits (git/log ctx {})]
    (testing "λ symbol extracted from message"
      (let [lambda-c (first (filter #(str/includes? (:git.commit/subject %) "λ") commits))]
        (is (contains? (:git.commit/symbols lambda-c) "λ"))))
    (testing "Δ symbol extracted from message"
      (let [delta-c (first (filter #(str/includes? (:git.commit/subject %) "Δ") commits))]
        (is (contains? (:git.commit/symbols delta-c) "Δ"))))))

;;; git/show

(deftest show-returns-detail
  (let [ctx    @shared-ro-ctx
        sha    (git/current-commit ctx)
        detail (git/show ctx sha)]
    (testing "sha matches"
      (is (= sha (:git.commit/sha detail))))
    (testing "has diff and stat strings"
      (is (string? (:git.commit/diff detail)))
      (is (string? (:git.commit/stat detail))))
    (testing "subject is a string"
      (is (string? (:git.commit/subject detail))))
    (testing "symbols is a set"
      (is (set? (:git.commit/symbols detail))))))

;;; git/status

(deftest status-clean-on-fresh-repo
  (let [ctx @shared-ro-ctx]
    (testing "status is :clean after commits"
      (is (= :clean (git/status ctx))))))

;;; git/current-commit

(deftest current-commit-is-40-char-sha
  (let [ctx @shared-ro-ctx
        sha (git/current-commit ctx)]
    (testing "sha is 40 chars"
      (is (= 40 (count sha))))
    (testing "sha is lowercase hex"
      (is (re-matches #"[0-9a-f]+" sha)))))

;;; git/ls-files

(deftest ls-files-returns-tracked-paths
  (let [ctx   @shared-ro-ctx
        files (git/ls-files ctx {})]
    (testing "returns tracked files"
      (is (seq files))
      (is (every? string? files)))
    (testing "seeded files are present"
      (is (some #(= % "README.md") files))
      (is (some #(= % "LEARNING.md") files)))))

;;; git/grep

(deftest grep-finds-pattern
  (let [ctx     @shared-ro-ctx
        results (git/grep ctx "defresolver" {})]
    (testing "finds matching lines"
      (is (seq results)))
    (testing "result fields are present"
      (is (every? :git.grep/file results))
      (is (every? :git.grep/line results))
      (is (every? :git.grep/content results)))
    (testing "line numbers are integers"
      (is (every? number? (map :git.grep/line results))))))

(deftest grep-no-match-returns-empty
  (let [ctx     @shared-ro-ctx
        results (git/grep ctx "XYZZY_NOTHING_HERE_9999" {})]
    (testing "no match is nil or empty"
      (is (or (nil? results) (empty? results))))))

;;; worktree parsing + listing

(deftest parse-worktree-porcelain-empty
  (testing "blank porcelain output parses to no worktrees"
    (is (= [] (git/parse-worktree-porcelain "")))))

(deftest parse-worktree-porcelain-linked-and-detached
  (let [porcelain (str "worktree /repo/main\n"
                       "HEAD 1111111111111111111111111111111111111111\n"
                       "branch refs/heads/master\n\n"
                       "worktree /repo/wt-1\n"
                       "HEAD 2222222222222222222222222222222222222222\n"
                       "branch refs/heads/feature-x\n\n"
                       "worktree /repo/wt-detached\n"
                       "HEAD 3333333333333333333333333333333333333333\n"
                       "detached\n")
        worktrees (git/parse-worktree-porcelain porcelain)]
    (testing "parses all worktrees"
      (is (= 3 (count worktrees))))
    (testing "parses branch names for linked worktrees"
      (is (= "master" (get-in worktrees [0 :git.worktree/branch-name])))
      (is (= "feature-x" (get-in worktrees [1 :git.worktree/branch-name]))))
    (testing "marks detached worktrees"
      (is (true? (get-in worktrees [2 :git.worktree/detached?]))))))

(deftest inside-repo-detects-null-context
  (let [ctx @shared-ro-ctx]
    (testing "null context is recognized as being inside a repo"
      (is (true? (git/inside-repo? ctx))))))

(deftest worktree-list-includes-current-on-null-context
  (let [ctx       @shared-ro-ctx
        worktrees (git/worktree-list ctx)
        current   (git/current-worktree ctx)]
    (testing "lists at least one worktree"
      (is (seq worktrees)))
    (testing "current worktree is identified"
      (is (map? current))
      (is (true? (:git.worktree/current? current))))
    (testing "one listed worktree is marked current"
      (is (some :git.worktree/current? worktrees)))))

(deftest worktree-list-outside-repo-is-empty
  (let [tmp (str (Files/createTempDirectory "psi-no-git-"
                                            (make-array FileAttribute 0)))
        ctx (git/create-context tmp)]
    (testing "outside-repo contexts are reported as outside a repo"
      (is (false? (git/inside-repo? ctx))))
    (testing "outside-repo contexts list no worktrees"
      (is (= [] (git/worktree-list ctx))))
    (testing "outside-repo contexts have no current worktree"
      (is (nil? (git/current-worktree ctx))))))

(deftest worktree-list-command-failure-emits-telemetry-and-empty
  (let [ctx    @shared-ro-ctx
        warned (atom nil)]
    (with-redefs [psi.history.git/inside-repo? (fn [_] true)
                  psi.history.git/run-git (fn [_ _]
                                            (throw (ex-info "boom" {:type :boom})))
                  psi.history.git/emit-worktree-parse-failed!
                  (fn [_ctx e]
                    (reset! warned {:event "git.worktree.parse_failed"
                                    :error (ex-message e)}))]
      (testing "returns an empty worktree list on command failure"
        (is (= [] (git/worktree-list ctx))))
      (testing "emits parse-failure telemetry"
        (is (= "git.worktree.parse_failed" (:event @warned)))
        (is (= "boom" (:error @warned)))))))

(deftest worktree-list-marks-nested-cwd-as-current
  (let [ctx        @shared-ro-ctx
        root       (:repo-dir ctx)
        nested     (str root File/separator "src")
        _          (.mkdirs (File. nested))
        nested-ctx (git/create-context nested)
        worktrees  (git/worktree-list nested-ctx)
        current    (git/current-worktree nested-ctx)]
    (testing "still lists worktrees when cwd is nested"
      (is (seq worktrees)))
    (testing "selects containing worktree as current"
      (is (map? current))
      (is (true? (:git.worktree/current? current)))
      (is (= (canonical-path root)
             (canonical-path (:git.worktree/path current)))))))

;;; worktree and branch mutations

(deftest worktree-add-and-remove-roundtrip
  (let [ctx     (git/create-null-context seed-commits)
        wt-path (linked-worktree-path ctx "feature-alpha")
        added   (git/worktree-add ctx {:path wt-path
                                       :branch "feature-alpha"})
        listed  (git/worktree-list ctx)
        removed (git/worktree-remove ctx {:path wt-path})]
    (testing "worktree add succeeds"
      (is (true? (:success added)))
      (is (= wt-path (:path added)))
      (is (= "feature-alpha" (:branch added)))
      (is (string? (:head added))))
    (testing "worktree appears in registry after add"
      (is (worktree-listed? listed wt-path)))
    (testing "worktree remove succeeds"
      (is (true? (:success removed))))
    (testing "worktree directory removed"
      (is (false? (.exists (File. wt-path)))))))

(deftest worktree-add-fails-when-branch-exists
  (let [ctx     (git/create-null-context seed-commits)
        wt-path (linked-worktree-path ctx "main-dup")
        result  (git/worktree-add ctx {:path wt-path
                                       :branch "main"})]
    (testing "rejects creating a worktree for an existing branch"
      (is (false? (:success result)))
      (is (= "branch already exists" (:error result))))))

(deftest worktree-add-with-existing-branch-fails-while-branch-is-checked-out-elsewhere
  (let [ctx     (git/create-null-context seed-commits)
        wt-src  (linked-worktree-path ctx "feature-attached-src")
        wt-path (linked-worktree-path ctx "feature-attached")
        _       (git/worktree-add ctx {:path wt-src :branch "feature-attached-src"})
        result  (git/worktree-add ctx {:path wt-path
                                       :branch "feature-attached-src"
                                       :create-branch false})
        listed  (git/worktree-list ctx)]
    (testing "fails when the target branch is already checked out elsewhere"
      (is (false? (:success result)))
      (is (string? (:error result))))
    (testing "does not add the rejected worktree"
      (is (not (worktree-listed? listed wt-path))))))

(deftest worktree-add-with-existing-branch-succeeds-after-branch-is-no-longer-checked-out-elsewhere
  (let [ctx     (git/create-null-context seed-commits)
        wt-src  (linked-worktree-path ctx "feature-attached-src")
        wt-path (linked-worktree-path ctx "feature-attached")
        _       (git/worktree-add ctx {:path wt-src :branch "feature-attached-src"})
        _       (git/worktree-remove ctx {:path wt-src})
        result  (git/worktree-add ctx {:path wt-path
                                       :branch "feature-attached-src"
                                       :create-branch false})
        listed  (git/worktree-list ctx)]
    (testing "succeeds once the branch is no longer checked out elsewhere"
      (is (true? (:success result)))
      (is (= wt-path (:path result)))
      (is (= "feature-attached-src" (:branch result))))
    (testing "adds the new worktree to the registry"
      (is (worktree-listed? listed wt-path)))))

(deftest worktree-remove-fails-for-main-worktree
  (let [ctx    (git/create-null-context seed-commits)
        result (git/worktree-remove ctx {:path (:repo-dir ctx)})]
    (testing "rejects removing the main worktree"
      (is (false? (:success result)))
      (is (= "cannot remove main worktree" (:error result))))))

(deftest branch-merge-fast-forward
  (let [ctx     (git/create-null-context seed-commits)
        wt-path (linked-worktree-path ctx "feature-merge")
        _       (git/worktree-add ctx {:path wt-path :branch "feature-merge"})
        _       (append-and-commit! wt-path "src/merge_feature.clj" "(ns merge-feature)\n" "⚒ feature merge commit")
        result  (git/branch-merge ctx {:branch "feature-merge"})
        files   (git/ls-files ctx {})]
    (testing "reports a successful fast-forward merge"
      (is (true? (:merged result)))
      (is (true? (:fast-forward result)))
      (is (false? (:conflict result)))
      (is (nil? (:error result))))
    (testing "merged files become visible on the target branch"
      (is (some #(= % "src/merge_feature.clj") files)))))

(deftest branch-merge-rejects-false-positive-success-when-run-on-source-branch
  (let [ctx         (git/create-null-context seed-commits)
        wt-path     (linked-worktree-path ctx "feature-self")
        _           (git/worktree-add ctx {:path wt-path :branch "feature-self"})
        _           (append-and-commit! wt-path "src/feature_self.clj" "(ns feature-self)\n" "⚒ feature self commit")
        feature-ctx (git/create-context wt-path)
        result      (git/branch-merge feature-ctx {:branch "feature-self"})]
    (testing "does not report a merge when target head did not change"
      (is (false? (:merged result)))
      (is (false? (:conflict result))))
    (testing "returns a diagnostic error explaining the false positive"
      (is (re-find #"merge reported success but target HEAD did not absorb branch" (:error result))))))

(deftest branch-merge-ff-only-fails-when-not-fast-forwardable
  (let [ctx     (git/create-null-context seed-commits)
        wt-path (linked-worktree-path ctx "feature-diverged")
        _       (git/worktree-add ctx {:path wt-path :branch "feature-diverged"})
        _       (append-and-commit! wt-path "src/diverged_feature.clj" "(ns diverged-feature)\n" "⚒ feature diverged commit")
        _       (append-and-commit! (:repo-dir ctx) "src/main_side.clj" "(ns main-side)\n" "⚒ main side commit")
        result  (git/branch-merge ctx {:branch "feature-diverged"
                                       :strategy :ff_only})]
    (testing "rejects non-fast-forward merges under ff-only strategy"
      (is (false? (:merged result)))
      (is (false? (:conflict result)))
      (is (= "not fast-forwardable; rebase first" (:error result))))))

(deftest branch-rebase-success
  (let [ctx     (git/create-null-context seed-commits)
        wt-path (linked-worktree-path ctx "feature-rebase")
        _       (git/worktree-add ctx {:path wt-path :branch "feature-rebase"})
        _       (append-and-commit! wt-path "src/rebase_feature.clj" "(ns rebase-feature)\n" "⚒ feature before rebase")
        _       (append-and-commit! (:repo-dir ctx) "src/main_rebase.clj" "(ns main-rebase)\n" "⚒ main before rebase")
        wt-ctx  (git/create-context wt-path)
        result  (git/branch-rebase wt-ctx {:onto "main"})]
    (testing "rebases the feature branch onto main"
      (is (true? (:success result)))
      (is (= "feature-rebase" (:branch result)))
      (is (false? (:conflict result))))))

(deftest branch-delete-success
  (let [ctx     (git/create-null-context seed-commits)
        wt-path (linked-worktree-path ctx "feature-delete")
        _       (git/worktree-add ctx {:path wt-path :branch "feature-delete"})
        _       (git/worktree-remove ctx {:path wt-path})
        result  (git/branch-delete ctx {:branch "feature-delete"})]
    (testing "deletes a non-current local branch"
      (is (true? (:deleted result)))
      (is (nil? (:error result))))))

(deftest default-branch-falls-back-to-main
  (let [ctx    @shared-ro-ctx
        result (git/default-branch ctx)]
    (testing "falls back to main when no symbolic ref or config is available"
      (is (= "main" (:branch result)))
      (is (= :fallback (:source result))))))

;;; context isolation

(deftest two-null-contexts-are-independent
  (let [ctx-a (git/create-null-context [{:message "only in A"
                                         :files   {"a.txt" "a\n"}}])
        ctx-b (git/create-null-context [{:message "only in B"
                                         :files   {"b.txt" "b\n"}}])]
    (testing "ctx-a has its commit"
      (is (str/includes?
           (:git.commit/subject (first (git/log ctx-a {})))
           "only in A")))
    (testing "ctx-b has its commit"
      (is (str/includes?
           (:git.commit/subject (first (git/log ctx-b {})))
           "only in B")))
    (testing "ctx-a does not see ctx-b commits"
      (is (not (some #(str/includes? (:git.commit/subject %) "only in B")
                     (git/log ctx-a {})))))))
