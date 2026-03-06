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

;;; Shared null context fixture (per test via let — each test gets its own)

(def ^:private seed-commits
  [{:message "⚒ Initial commit"
    :files   {"README.md"   "# psi\n"
              "src/core.clj" "(ns core)\n(defresolver foo [] {})\n"}}
   {:message "λ First learning captured"
    :files   {"LEARNING.md" "## learned something\n"}}
   {:message "Δ Show a delta here"
    :files   {"CHANGELOG.md" "## v0.1\n"}}
   {:message "⚒ Add another feature"
    :files   {"src/extra.clj" "(ns extra)\n"}}])

;;; git/log

(deftest log-returns-commits
  ;; null context with seeded commits — log should see all four
  (let [ctx     (git/create-null-context seed-commits)
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
  ;; :n 2 must return at most 2 commits
  (let [ctx     (git/create-null-context seed-commits)
        commits (git/log ctx {:n 2})]
    (testing ":n limits results"
      (is (<= (count commits) 2)))))

(deftest log-grep-filters-by-message
  ;; grep λ must return only commits whose subject contains λ
  (let [ctx     (git/create-null-context seed-commits)
        grepped (git/log ctx {:grep "λ"})]
    (testing "grep returns only matching commits"
      (is (= 1 (count grepped)))
      (is (str/includes? (:git.commit/subject (first grepped)) "λ")))))

(deftest log-symbols-extracted
  ;; the λ commit should carry λ in its symbols set
  (let [ctx     (git/create-null-context seed-commits)
        commits (git/log ctx {})]
    (testing "λ symbol extracted from message"
      (let [lambda-c (first (filter #(str/includes? (:git.commit/subject %) "λ") commits))]
        (is (contains? (:git.commit/symbols lambda-c) "λ"))))
    (testing "Δ symbol extracted from message"
      (let [delta-c (first (filter #(str/includes? (:git.commit/subject %) "Δ") commits))]
        (is (contains? (:git.commit/symbols delta-c) "Δ"))))))

;;; git/show

(deftest show-returns-detail
  ;; show HEAD must echo the sha and include diff + stat
  (let [ctx    (git/create-null-context seed-commits)
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
  ;; a freshly committed null repo should be clean
  (let [ctx (git/create-null-context seed-commits)]
    (testing "status is :clean after commits"
      (is (= :clean (git/status ctx))))))

;;; git/current-commit

(deftest current-commit-is-40-char-sha
  ;; HEAD sha must be a 40-character lowercase hex string
  (let [ctx (git/create-null-context seed-commits)
        sha (git/current-commit ctx)]
    (testing "sha is 40 chars"
      (is (= 40 (count sha))))
    (testing "sha is lowercase hex"
      (is (re-matches #"[0-9a-f]+" sha)))))

;;; git/ls-files

(deftest ls-files-returns-tracked-paths
  ;; null repo seeds four source files
  (let [ctx   (git/create-null-context seed-commits)
        files (git/ls-files ctx {})]
    (testing "returns tracked files"
      (is (seq files))
      (is (every? string? files)))
    (testing "seeded files are present"
      (is (some #(= % "README.md") files))
      (is (some #(= % "LEARNING.md") files)))))

;;; git/grep

(deftest grep-finds-pattern
  ;; null repo contains "(defresolver foo" — grep must find it
  (let [ctx     (git/create-null-context seed-commits)
        results (git/grep ctx "defresolver" {})]
    (testing "finds defresolver"
      (is (seq results)))
    (testing "result fields are present"
      (is (every? :git.grep/file results))
      (is (every? :git.grep/line results))
      (is (every? :git.grep/content results)))
    (testing "line numbers are integers"
      (is (every? number? (map :git.grep/line results))))))

(deftest grep-no-match-returns-empty
  ;; searching for a nonsense string must return nil or empty
  (let [ctx     (git/create-null-context seed-commits)
        results (git/grep ctx "XYZZY_NOTHING_HERE_9999" {})]
    (testing "no match is nil or empty"
      (is (or (nil? results) (empty? results))))))

;;; worktree parsing + listing

(deftest parse-worktree-porcelain-empty
  (is (= [] (git/parse-worktree-porcelain ""))))

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
    (is (= 3 (count worktrees)))
    (is (= "master" (get-in worktrees [0 :git.worktree/branch-name])))
    (is (= "feature-x" (get-in worktrees [1 :git.worktree/branch-name])))
    (is (true? (get-in worktrees [2 :git.worktree/detached?])))))

(deftest inside-repo-detects-null-context
  (let [ctx (git/create-null-context seed-commits)]
    (is (true? (git/inside-repo? ctx)))))

(deftest worktree-list-includes-current-on-null-context
  (let [ctx       (git/create-null-context seed-commits)
        worktrees (git/worktree-list ctx)
        current   (git/current-worktree ctx)]
    (is (seq worktrees))
    (is (map? current))
    (is (true? (:git.worktree/current? current)))
    (is (some :git.worktree/current? worktrees))))

(deftest worktree-list-outside-repo-is-empty
  (let [tmp (str (Files/createTempDirectory "psi-no-git-"
                                            (make-array FileAttribute 0)))
        ctx (git/create-context tmp)]
    (is (false? (git/inside-repo? ctx)))
    (is (= [] (git/worktree-list ctx)))
    (is (nil? (git/current-worktree ctx)))))

(deftest worktree-list-marks-nested-cwd-as-current
  (let [ctx         (git/create-null-context seed-commits)
        root        (:repo-dir ctx)
        nested      (str root File/separator "src")
        _           (.mkdirs (File. nested))
        nested-ctx  (git/create-context nested)
        worktrees   (git/worktree-list nested-ctx)
        current     (git/current-worktree nested-ctx)]
    (is (seq worktrees))
    (is (map? current))
    (is (true? (:git.worktree/current? current)))
    (is (= (.getCanonicalPath (File. root))
           (.getCanonicalPath (File. (:git.worktree/path current)))))))

;;; context isolation

(deftest two-null-contexts-are-independent
  ;; each null context is a separate temp repo — no shared state
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
